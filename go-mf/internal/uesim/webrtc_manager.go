package uesim

import (
	"encoding/json"
	"fmt"
	"regexp"
	"log"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/demo/ims-dc-mf/internal/imsas"
	"github.com/pion/ice/v2"
	"github.com/pion/webrtc/v3"
)


func (ue *UESimulator) startCallerCall() {
	ue.mu.Lock()
	if ue.callStarted {
		ue.mu.Unlock()
		return
	}
	ue.callStarted = true
	ue.mu.Unlock()


	log.Printf("[UE-%s] Starting outgoing call to %s", ue.username, ue.target)

	ue.mu.Lock()
	if ue.bdcPC != nil {
		_ = ue.bdcPC.Close()
	}
	ue.mu.Unlock()

	pc, err := createPeerConnectionWithKeyLog("/app/config/bdc_ssl_keys.log", ue.localIP)
	if err != nil {
		log.Printf("Failed to create PeerConnection: %v", err)
		return
	}
	ue.bdcPC = pc
	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		log.Printf("[UE-%s] BdcPC connection state changed to: %s", ue.username, state.String())
	})

	ue.startDummyMediaLoop()

	ue.mu.Lock()
	ue.bdcChannels = make(map[uint16]*webrtc.DataChannel)
	ue.mu.Unlock()

	if !ue.LegacyMTSI {
		bdcNegotiated := true
		bdcProtocol := "http"
		bdcStreams := []uint16{0, 10, 100, 110}
		for _, streamID := range bdcStreams {
			sid := streamID
			dc, err := pc.CreateDataChannel(fmt.Sprintf("bdc-%d", sid), &webrtc.DataChannelInit{
				ID:         &sid,
				Negotiated: &bdcNegotiated,
				Protocol:   &bdcProtocol,
			})
			if err == nil {
				ue.mu.Lock()
				ue.bdcChannels[sid] = dc
				if sid == 0 {
					ue.bdcChannel = dc
				}
				ue.mu.Unlock()
			} else {
				log.Printf("[UE-%s] Failed to create BDC Stream %d: %v", ue.username, sid, err)
			}
		}

		ue.setupDataChannelHandlers()
	}

	offer, _ := pc.CreateOffer(nil)
	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(offer); err != nil {
		log.Printf("[UE-%s] SetLocalDescription error: %v", ue.username, err)
	}
	select {
	case <-gatherComplete:
	case <-time.After(1500 * time.Millisecond):
		log.Printf("[UE-%s] BDC ICE gathering timed out", ue.username)
	}

	localDesc := pc.LocalDescription()
	if localDesc == nil {
		log.Printf("[UE-%s] LocalDescription is nil, aborting call setup", ue.username)
		return
	}
	localSDP := reorderSDPMedia(localDesc.SDP, ue.localIP, ue.localAudioPort, ue.localVideoPort)

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("INVITE sip:%s@%s SIP/2.0\r\n", ue.target, ue.cfg.Domains.Ims))
	sb.WriteString(fmt.Sprintf("Via: SIP/2.0/UDP %s:%d;branch=z9hG4bK-inv-%d\r\n", ue.localIP, ue.localPort, time.Now().UnixNano()))
	sb.WriteString("Max-Forwards: 70\r\n")
	sb.WriteString(fmt.Sprintf("From: <sip:%s@%s>;tag=%s\r\n", ue.username, ue.cfg.Domains.Ims, ue.fromTag))
	sb.WriteString(fmt.Sprintf("To: <sip:%s@%s>\r\n", ue.target, ue.cfg.Domains.Ims))
	sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", ue.callID))
	sb.WriteString("CSeq: 1 INVITE\r\n")
	if ue.LegacyMTSI {
		sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>\r\n", ue.username, ue.localIP, ue.localPort))
	} else {
		sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>;+sip.app-subtype=\"webrtc-datachannel\"\r\n", ue.username, ue.localIP, ue.localPort))
	}
	sb.WriteString("Content-Type: application/sdp\r\n")
	sb.WriteString(fmt.Sprintf("Content-Length: %d\r\n\r\n", len(localSDP)))
	sb.WriteString(localSDP)

	ue.sendUDP(sb.String())
	log.Printf("[UE-%s] Outgoing INVITE Offer sent with plain RTP", ue.username)
}

func (ue *UESimulator) handleInviteAnswer(msg *imsas.SIPMessage) {
	log.Printf("[UE-%s] Processing SDP Answer:\n%s", ue.username, msg.Body)
	mediaInfo := parseSDPMedia(msg.Body)
	toHeader := msg.GetHeader("To")
	toTag := ""
	if toHeader != "" {
		parts := strings.Split(toHeader, ";")
		for _, p := range parts {
			p = strings.TrimSpace(p)
			if strings.HasPrefix(p, "tag=") {
				toTag = strings.TrimPrefix(p, "tag=")
				break
			}
		}
	}

	ue.mu.Lock()
	ue.RtpIP = mediaInfo.IP
	if ue.RtpIP == "" {
		ue.RtpIP = "127.0.0.1"
	}
	ue.RtpAudioPort = mediaInfo.AudioPort
	ue.RtpVideoPort = mediaInfo.VideoPort
	if toTag != "" {
		ue.toTag = toTag
	}
	bdcPC := ue.bdcPC
	adcPC := ue.adcPC
	ue.mu.Unlock()

	bdcAnswer, adcAnswer := splitSDPAnswer(msg.Body)

	if bdcPC != nil && bdcPC.SignalingState() != webrtc.SignalingStateStable {
		cleanAnswer := stripAudioVideoFromSDP(bdcAnswer)
		translatedAnswer := ue.translateSipOfferToWebRtcOffer(cleanAnswer)
		answer := webrtc.SessionDescription{
			Type: webrtc.SDPTypeAnswer,
			SDP:  translatedAnswer,
		}
		if err := bdcPC.SetRemoteDescription(answer); err != nil {
			log.Printf("[UE-%s] Failed to set remote description on BdcPC: %v", ue.username, err)
		} else {
			log.Printf("[UE-%s] BdcPC remote description set successfully.", ue.username)
		}
	}

	if adcPC != nil && adcPC.SignalingState() != webrtc.SignalingStateStable && adcAnswer != "" {
		cleanAnswer := stripAudioVideoFromSDP(adcAnswer)
		translatedAnswer := ue.translateSipOfferToWebRtcOffer(cleanAnswer)
		translatedAnswer = rewriteMidToZero(translatedAnswer)
		answer := webrtc.SessionDescription{
			Type: webrtc.SDPTypeAnswer,
			SDP:  translatedAnswer,
		}
		if err := adcPC.SetRemoteDescription(answer); err != nil {
			log.Printf("[UE-%s] Failed to set remote description on AdcPC: %v", ue.username, err)
		} else {
			log.Printf("[UE-%s] AdcPC remote description set successfully.", ue.username)
		}
	}

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("ACK sip:%s@%s SIP/2.0\r\n", ue.target, ue.cfg.Domains.Ims))
	sb.WriteString(fmt.Sprintf("Via: SIP/2.0/UDP %s:%d;branch=z9hG4bK-ack-%d\r\n", ue.localIP, ue.localPort, time.Now().UnixNano()))
	sb.WriteString(fmt.Sprintf("From: <sip:%s@%s>;tag=%s\r\n", ue.username, ue.cfg.Domains.Ims, ue.fromTag))
	toStr := fmt.Sprintf("<sip:%s@%s>", ue.target, ue.cfg.Domains.Ims)
	if ue.toTag != "" {
		toStr += ";tag=" + ue.toTag
	}
	sb.WriteString(fmt.Sprintf("To: %s\r\n", toStr))
	sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", ue.callID))
	cseqVal, _ := msg.GetCSeq()
	if cseqVal == 0 {
		cseqVal = 1
	}
	sb.WriteString(fmt.Sprintf("CSeq: %d ACK\r\n", cseqVal))
	sb.WriteString("Content-Length: 0\r\n\r\n")

	ue.sendUDP(sb.String())
	ue.mu.Lock()
	ue.isCallOK = true
	ue.mu.Unlock()
}

func (ue *UESimulator) handleIncomingInvite(msg *imsas.SIPMessage) {
	incomingCallID := msg.GetHeader("Call-ID")

	ue.mu.Lock()
	bdcPC := ue.bdcPC
	adcPC := ue.adcPC
	var ringingCallID string
	if ue.lastSipInvite != nil {
		ringingCallID = ue.lastSipInvite.GetHeader("Call-ID")
	}
	activeCallID := ue.callID
	ue.mu.Unlock()

	if bdcPC != nil {
		if incomingCallID != activeCallID {
			log.Printf("[UE-%s] Busy (active call). Rejecting incoming Call-ID: %s (Active: %s)", ue.username, incomingCallID, activeCallID)
			ue.rejectIncomingCallWithMsg(msg)
			return
		}
		log.Printf("[UE-%s] Received incoming Re-INVITE Call. Body:\n%s", ue.username, msg.Body)
		log.Printf("[UE-%s] Received incoming Re-INVITE Call. Updating SDP...", ue.username)
		mediaInfo := parseSDPMedia(msg.Body)
		ue.mu.Lock()
		ue.RtpIP = mediaInfo.IP
		if ue.RtpIP == "" {
			ue.RtpIP = "127.0.0.1"
		}
		ue.RtpAudioPort = mediaInfo.AudioPort
		ue.RtpVideoPort = mediaInfo.VideoPort
		ue.mu.Unlock()

		log.Printf("[UE-%s] Re-INVITE: Splitting SDP answer...", ue.username)
		_ = parseReqApp(msg.Body)
		bdcOffer, adcOffer := splitSDPAnswer(msg.Body)
		log.Printf("[UE-%s] Re-INVITE: bdcOffer len: %d, adcOffer len: %d", ue.username, len(bdcOffer), len(adcOffer))

		if bdcPC.SignalingState() != webrtc.SignalingStateStable {
			log.Printf("[UE-%s] Re-INVITE: bdcPC is not stable. Setting remote description...", ue.username)
			cleanOffer := stripAudioVideoFromSDP(bdcOffer)
			translatedOffer := ue.translateSipOfferToWebRtcOffer(cleanOffer)
			offer := webrtc.SessionDescription{
				Type: webrtc.SDPTypeOffer,
				SDP:  translatedOffer,
			}
			if err := bdcPC.SetRemoteDescription(offer); err != nil {
				log.Printf("Failed to set remote offer on BDC PC: %v", err)
			}
			log.Printf("[UE-%s] Re-INVITE: bdcPC remote description set.", ue.username)
		} else {
			log.Printf("[UE-%s] Re-INVITE: bdcPC is stable. Skipping SetRemoteDescription.", ue.username)
		}

		ue.mu.Lock()
		if ue.adcPC == nil {
			log.Printf("[UE-%s] Re-INVITE: Creating adcPC...", ue.username)
			adcPCNew, err := createPeerConnectionWithKeyLog("/app/config/adc_ssl_keys.log", ue.localIP)
			if err == nil {
				ue.adcPC = adcPCNew
				adcPCNew.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
					log.Printf("[UE-%s] AdcPC connection state changed to: %s", ue.username, state.String())
				})
				adcID := uint16(1000)
				adcNegotiated := true
				protoWhiteboard := "whiteboard"
				adc, _ := adcPCNew.CreateDataChannel("app-dc", &webrtc.DataChannelInit{
					ID:         &adcID,
					Negotiated: &adcNegotiated,
					Protocol:   &protoWhiteboard,
				})
				ue.adcChannel = adc

				adcID2 := uint16(1001)
				protoEmoji := "emoji"
				adc2, _ := adcPCNew.CreateDataChannel("app-dc-2", &webrtc.DataChannelInit{
					ID:         &adcID2,
					Negotiated: &adcNegotiated,
					Protocol:   &protoEmoji,
				})
				ue.adcChannel2 = adc2
				log.Printf("[UE-%s] Re-INVITE: adcPC created and channels configured.", ue.username)
			} else {
				log.Printf("[UE-%s] Failed to create ADC PeerConnection: %v", ue.username, err)
			}
		}
		adcPC = ue.adcPC
		ue.mu.Unlock()

		if adcPC != nil {
			ue.setupDataChannelHandlers()
		}

		if adcPC != nil && adcOffer != "" {
			log.Printf("[UE-%s] Re-INVITE: Setting remote description on adcPC...", ue.username)
			cleanOffer := stripAudioVideoFromSDP(adcOffer)
			translatedOffer := ue.translateSipOfferToWebRtcOffer(cleanOffer)
			translatedOffer = rewriteMidToZero(translatedOffer)
			offer := webrtc.SessionDescription{
				Type: webrtc.SDPTypeOffer,
				SDP:  translatedOffer,
			}
			if err := adcPC.SetRemoteDescription(offer); err != nil {
				log.Printf("Failed to set remote offer on ADC PC: %v\nSDP content:\n%s", err, offer.SDP)
			}
			log.Printf("[UE-%s] Re-INVITE: adcPC remote description set.", ue.username)
		}

		var bdcAnsSdp, adcAnsSdp string
		if bdcPC.SignalingState() != webrtc.SignalingStateStable {
			log.Printf("[UE-%s] Re-INVITE: Creating BDC answer...", ue.username)
			answer, _ := bdcPC.CreateAnswer(nil)
			gatherComplete := webrtc.GatheringCompletePromise(bdcPC)
			if err := bdcPC.SetLocalDescription(answer); err != nil {
				log.Printf("[UE-%s] BDC SetLocalDescription error: %v", ue.username, err)
			}
			log.Printf("[UE-%s] Re-INVITE: Waiting for BDC ICE gathering...", ue.username)
			select {
			case <-gatherComplete:
				log.Printf("[UE-%s] Re-INVITE: BDC ICE gathering complete.", ue.username)
			case <-time.After(1500 * time.Millisecond):
				log.Printf("[UE-%s] Re-INVITE: BDC ICE gathering timed out", ue.username)
			}
			bdcAnsSdp = bdcPC.LocalDescription().SDP
		} else {
			log.Printf("[UE-%s] Re-INVITE: BDC stable, using existing SDP.", ue.username)
			bdcAnsSdp = bdcPC.LocalDescription().SDP
		}

		if adcPC != nil && adcPC.SignalingState() != webrtc.SignalingStateStable {
			log.Printf("[UE-%s] Re-INVITE: Creating ADC answer...", ue.username)
			answer, _ := adcPC.CreateAnswer(nil)
			gatherComplete := webrtc.GatheringCompletePromise(adcPC)
			if err := adcPC.SetLocalDescription(answer); err != nil {
				log.Printf("[UE-%s] ADC SetLocalDescription error: %v", ue.username, err)
			}
			log.Printf("[UE-%s] Re-INVITE: Waiting for ADC ICE gathering...", ue.username)
			select {
			case <-gatherComplete:
				log.Printf("[UE-%s] Re-INVITE: ADC ICE gathering complete.", ue.username)
			case <-time.After(1500 * time.Millisecond):
				log.Printf("[UE-%s] Re-INVITE: ADC ICE gathering timed out", ue.username)
			}
			adcAnsSdp = adcPC.LocalDescription().SDP
			log.Printf("[UE-%s] Re-INVITE: adcAnsSdp:\n%s", ue.username, adcAnsSdp)
		}

		log.Printf("[UE-%s] Re-INVITE: Merging SDP offers...", ue.username)
		localSDP := MergeAdcOffer(bdcAnsSdp, adcAnsSdp, true)
		log.Printf("[UE-%s] Re-INVITE: Reordering SDP media...", ue.username)
		localSDP = reorderSDPMedia(localSDP, ue.localIP, ue.localAudioPort, ue.localVideoPort)

		var sb strings.Builder
		sb.WriteString("SIP/2.0 200 OK\r\n")
		if via := msg.GetHeader("Via"); via != "" {
			sb.WriteString(fmt.Sprintf("Via: %s\r\n", via))
		}
		if from := msg.GetHeader("From"); from != "" {
			sb.WriteString(fmt.Sprintf("From: %s\r\n", from))
		}
		if to := msg.GetHeader("To"); to != "" {
			sb.WriteString(fmt.Sprintf("To: %s\r\n", to))
		}
		sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", ue.callID))
		if cseq := msg.GetHeader("CSeq"); cseq != "" {
			sb.WriteString(fmt.Sprintf("CSeq: %s\r\n", cseq))
		}
		if ue.LegacyMTSI {
			sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>\r\n", ue.username, ue.localIP, ue.localPort))
		} else {
			sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>;+sip.app-subtype=\"webrtc-datachannel\"\r\n", ue.username, ue.localIP, ue.localPort))
		}
		sb.WriteString("Content-Type: application/sdp\r\n")
		sb.WriteString(fmt.Sprintf("Content-Length: %d\r\n\r\n", len(localSDP)))
		sb.WriteString(localSDP)

		ue.sendUDP(sb.String())
		log.Printf("[UE-%s] Re-INVITE 200 OK Answer sent", ue.username)
		return
	}

	if ringingCallID != "" {
		if incomingCallID != ringingCallID {
			log.Printf("[UE-%s] Busy (already ringing). Rejecting incoming Call-ID: %s (Ringing: %s)", ue.username, incomingCallID, ringingCallID)
			ue.rejectIncomingCallWithMsg(msg)
			return
		}
	}

	ue.mu.Lock()
	ue.lastSipInvite = msg
	ue.callID = msg.GetHeader("Call-ID")
	ue.target = msg.ExtractUsername("From")
	ue.mu.Unlock()

	log.Printf("[UE-%s] Received incoming INVITE Call. Auto-answering call...", ue.username)
	go ue.acceptIncomingCall()
}

func (ue *UESimulator) acceptIncomingCall() {
	ue.mu.Lock()
	msg := ue.lastSipInvite
	ue.mu.Unlock()

	if msg == nil {
		log.Printf("[UE-%s] No incoming call invitation to accept", ue.username)
		return
	}

	log.Printf("[UE-%s] Accepting incoming Call. Sending 200 OK Answer...", ue.username)
	ue.mu.Lock()
	if ue.bdcPC != nil {
		_ = ue.bdcPC.Close()
	}
	ue.mu.Unlock()

	pc, err := createPeerConnectionWithKeyLog("/app/config/bdc_ssl_keys.log", ue.localIP)
	if err != nil {
		log.Printf("Failed to create PeerConnection: %v", err)
		return
	}
	ue.bdcPC = pc
	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		log.Printf("[UE-%s] BdcPC connection state changed to: %s", ue.username, state.String())
	})

	ue.startDummyMediaLoop()

	ue.mu.Lock()
	ue.bdcChannels = make(map[uint16]*webrtc.DataChannel)
	ue.mu.Unlock()

	if !ue.LegacyMTSI {
		bdcNegotiated := true
		bdcProtocol := "http"
		bdcStreams := []uint16{0, 10, 100, 110}
		for _, streamID := range bdcStreams {
			sid := streamID
			dc, err := pc.CreateDataChannel(fmt.Sprintf("bdc-%d", sid), &webrtc.DataChannelInit{
				ID:         &sid,
				Negotiated: &bdcNegotiated,
				Protocol:   &bdcProtocol,
			})
			if err == nil {
				ue.mu.Lock()
				ue.bdcChannels[sid] = dc
				if sid == 0 {
					ue.bdcChannel = dc
				}
				ue.mu.Unlock()
			} else {
				log.Printf("[UE-%s] Failed to create BDC Stream %d: %v", ue.username, sid, err)
			}
		}

		ue.setupDataChannelHandlers()
	}

	mediaInfo := parseSDPMedia(msg.Body)
	ue.mu.Lock()
	ue.RtpIP = mediaInfo.IP
	if ue.RtpIP == "" {
		ue.RtpIP = "127.0.0.1"
	}
	ue.RtpAudioPort = mediaInfo.AudioPort
	ue.RtpVideoPort = mediaInfo.VideoPort
	ue.mu.Unlock()

	bdcOffer, _ := splitSDPAnswer(msg.Body)
	cleanOffer := stripAudioVideoFromSDP(bdcOffer)
	translatedOffer := ue.translateSipOfferToWebRtcOffer(cleanOffer)

	offer := webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer,
		SDP:  translatedOffer,
	}
	if err := pc.SetRemoteDescription(offer); err != nil {
		log.Printf("Failed to set remote offer: %v", err)
		pc.Close()
		ue.mu.Lock()
		ue.bdcPC = nil
		ue.mu.Unlock()
		return
	}

	answer, _ := pc.CreateAnswer(nil)
	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(answer); err != nil {
		log.Printf("[UE-%s] BDC SetLocalDescription answer error: %v", ue.username, err)
	}
	select {
	case <-gatherComplete:
	case <-time.After(1500 * time.Millisecond):
		log.Printf("[UE-%s] BDC Answer ICE gathering timed out", ue.username)
	}

	localDesc := pc.LocalDescription()
	if localDesc == nil {
		log.Printf("[UE-%s] BDC LocalDescription is nil, aborting incoming call answer", ue.username)
		return
	}
	localSDP := reorderSDPMedia(localDesc.SDP, ue.localIP, ue.localAudioPort, ue.localVideoPort)

	// Extract remote tag from From header
	fromHeader := msg.GetHeader("From")
	remoteTag := ""
	if fromHeader != "" {
		parts := strings.Split(fromHeader, ";")
		for _, p := range parts {
			p = strings.TrimSpace(p)
			if strings.HasPrefix(p, "tag=") {
				remoteTag = strings.TrimPrefix(p, "tag=")
				break
			}
		}
	}

	localTag := fmt.Sprintf("tag-callee-%s-%d", ue.username, time.Now().UnixNano())
	ue.mu.Lock()
	ue.fromTag = localTag
	if remoteTag != "" {
		ue.toTag = remoteTag
	}
	ue.mu.Unlock()

	var sb strings.Builder
	sb.WriteString("SIP/2.0 200 OK\r\n")
	if via := msg.GetHeader("Via"); via != "" {
		sb.WriteString(fmt.Sprintf("Via: %s\r\n", via))
	}
	if fromHeader != "" {
		sb.WriteString(fmt.Sprintf("From: %s\r\n", fromHeader))
	}
	toVal := msg.GetHeader("To")
	if toVal != "" {
		if !strings.Contains(toVal, "tag=") {
			toVal += ";tag=" + localTag
		}
		sb.WriteString(fmt.Sprintf("To: %s\r\n", toVal))
	} else {
		sb.WriteString(fmt.Sprintf("To: <sip:%s@%s>;tag=%s\r\n", ue.username, ue.cfg.Domains.Ims, localTag))
	}
	sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", ue.callID))
	if cseq := msg.GetHeader("CSeq"); cseq != "" {
		sb.WriteString(fmt.Sprintf("CSeq: %s\r\n", cseq))
	}
	if ue.LegacyMTSI {
		sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>\r\n", ue.username, ue.localIP, ue.localPort))
	} else {
		sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>;+sip.app-subtype=\"webrtc-datachannel\"\r\n", ue.username, ue.localIP, ue.localPort))
	}
	sb.WriteString("Content-Type: application/sdp\r\n")
	sb.WriteString(fmt.Sprintf("Content-Length: %d\r\n\r\n", len(localSDP)))
	sb.WriteString(localSDP)

	ue.sendUDP(sb.String())
	ue.mu.Lock()
	ue.isCallOK = true
	ue.mu.Unlock()
	if ue.OnSIPStateChange != nil {
		ue.OnSIPStateChange("connected")
	}
	log.Printf("[UE-%s] 200 OK Answer sent", ue.username)
}

func (ue *UESimulator) setupDataChannelHandlers() {
	ue.mu.Lock()
	bdcChans := ue.bdcChannels
	ue.mu.Unlock()

	for sid, dc := range bdcChans {
		streamID := sid
		dcChan := dc
		dcChan.OnOpen(func() {
			log.Printf("[UE-%s] BDC Stream %d OPEN.", ue.username, streamID)
			if ue.OnDataChannelOpen != nil {
				ue.OnDataChannelOpen(streamID)
			}
			if streamID == 0 {
				log.Printf("[UE-%s] Auto-requesting Root Menu on Stream 0...", ue.username)
				req := "GET / HTTP/1.1\r\nHost:\r\n\r\n"
				if err := dcChan.SendText(req); err != nil {
					log.Printf("[UE-%s] Failed to send Stream 0 request: %v", ue.username, err)
				}
			}
		})

		dcChan.OnMessage(func(msg webrtc.DataChannelMessage) {
			log.Printf("[UE-%s Log] BDC Stream %d successfully downloaded package. Size: %d bytes", ue.username, streamID, len(msg.Data))
			if ue.OnDataChannelMessage != nil {
				ue.OnDataChannelMessage(streamID, "http", string(msg.Data))
			}
		})
	}

	if ue.adcChannel != nil {
		ue.adcChannel.OnOpen(func() {
			log.Printf("[UE-%s] ADC Channel (1000) OPEN", ue.username)
			if ue.OnDataChannelOpen != nil {
				ue.OnDataChannelOpen(1000)
			}
		})
		if ue.adcChannel.ReadyState() == webrtc.DataChannelStateOpen {
			log.Printf("[UE-%s] ADC Channel (1000) ALREADY OPEN", ue.username)
			if ue.OnDataChannelOpen != nil {
				ue.OnDataChannelOpen(1000)
			}
		}

		ue.adcChannel.OnMessage(func(msg webrtc.DataChannelMessage) {
			if ue.OnDataChannelMessage != nil {
				ue.OnDataChannelMessage(1000, "whiteboard", string(msg.Data))
			}
			log.Printf("[UE Log] Smart Menu response received: %s", string(msg.Data))
		})
	}

	if ue.adcChannel2 != nil {
		ue.adcChannel2.OnOpen(func() {
			log.Printf("[UE-%s] ADC Channel (1001) OPEN", ue.username)
			if ue.OnDataChannelOpen != nil {
				ue.OnDataChannelOpen(1001)
			}
		})
		if ue.adcChannel2.ReadyState() == webrtc.DataChannelStateOpen {
			log.Printf("[UE-%s] ADC Channel (1001) ALREADY OPEN", ue.username)
			if ue.OnDataChannelOpen != nil {
				ue.OnDataChannelOpen(1001)
			}
		}

		ue.adcChannel2.OnMessage(func(msg webrtc.DataChannelMessage) {
			if ue.OnDataChannelMessage != nil {
				ue.OnDataChannelMessage(1001, "emoji", string(msg.Data))
			}
			var payload map[string]interface{}
			if err := json.Unmarshal(msg.Data, &payload); err == nil {
				service := payload["service"]
				action := payload["action"]
				emojiType := payload["type"]
				if service == "fun-calling" && (action == "render_emoji" || action == "send_emoji") {
					roleLabel := "A"
					if ue.username == "1002" {
						roleLabel = "B"
					}
					log.Printf("[UE-%s Log] RENDER EMOJI: %v floating on video canvas!", roleLabel, emojiType)
				}
			}
		})
	}
}

func (ue *UESimulator) runADCEventLoop() {
	log.Printf("[UE-%s] Starting interactive tick loop every 5 seconds...", ue.username)
	ticks := 0
	for {
		time.Sleep(5 * time.Second)
		ue.mu.Lock()
		ok := ue.isCallOK
		ue.mu.Unlock()
		if !ok {
			continue
		}

		ticks++
		if ticks > 4 {
			ue.sendBye()
			time.Sleep(1 * time.Second)
			return
		}

		if ue.target == "198" {
			// Smart Menu JSON format
			payload := `{"service":"smart-menu","action":"ask_time"}`
			log.Printf("[UE-%s] Hotline Interaction Tick: sending ask_time to ADC", ue.username)
			if err := ue.adcChannel.SendText(payload); err != nil {
				log.Printf("ADC write error: %v", err)
			}
		} else {
			// Fun Calling JSON format
			payload := `{"service":"fun-calling","action":"send_emoji","type":"heart"}`
			log.Printf("[UE-%s] User Interaction Tick: sending send_emoji to ADC", ue.username)
			if err := ue.adcChannel2.SendText(payload); err != nil {
				log.Printf("ADC2 write error: %v", err)
			}
		}
	}
}

var (
	mfContainerIP string
	resolveOnce   sync.Once
)

func (ue *UESimulator) translateSipOfferToWebRtcOffer(sdp string) string {
	resolveOnce.Do(func() {
		host := "mf"
		if ue.cfg != nil && ue.cfg.Services.Mf.Host != "" {
			host = ue.cfg.Services.Mf.Host
		}
		if net.ParseIP(host) == nil && !strings.HasSuffix(host, ".") {
			host = host + "."
		}
		ips, err := net.LookupIP(host)
		if err == nil && len(ips) > 0 {
			mfContainerIP = ips[0].String()
			log.Printf("[UESimulator] Lazy DNS lookup for '%s' succeeded: %s", host, mfContainerIP)
		} else {
			log.Printf("[UESimulator] Lazy DNS lookup for '%s' failed: %v", host, err)
		}
	})

	if mfContainerIP != "" {
		re := regexp.MustCompile(`(?m)^c=IN IP4 .*$`)
		sdp = re.ReplaceAllString(sdp, "c=IN IP4 "+mfContainerIP)
	}
	normalized := strings.ReplaceAll(sdp, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	var cleaned []string
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if strings.HasPrefix(trimmed, "m=audio ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 3 && parts[2] == "RTP/AVP" {
				trimmed = fmt.Sprintf("%s %s UDP/TLS/RTP/SAVPF 8", parts[0], parts[1])
			}
		} else if strings.HasPrefix(trimmed, "m=video ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 3 && (parts[2] == "RTP/AVP" || parts[2] == "RTP/AVPF") {
				trimmed = fmt.Sprintf("%s %s UDP/TLS/RTP/SAVPF 97", parts[0], parts[1])
			}
		}
		cleaned = append(cleaned, trimmed)
	}
	return strings.Join(cleaned, "\r\n") + "\r\n"
}

func translateWebRtcAnswerToSipAnswer(sdp string) string {
	normalized := strings.ReplaceAll(sdp, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	var cleaned []string
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if strings.HasPrefix(trimmed, "m=audio ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 3 {
				trimmed = fmt.Sprintf("%s %s RTP/AVP 8", parts[0], parts[1])
			}
		} else if strings.HasPrefix(trimmed, "m=video ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 3 {
				trimmed = fmt.Sprintf("%s %s RTP/AVP 97", parts[0], parts[1])
			}
		}
		cleaned = append(cleaned, trimmed)
		if strings.HasPrefix(trimmed, "m=application ") {
			cleaned = append(cleaned, "a=dcmap:10 subprotocol=\"http\"")
			cleaned = append(cleaned, "a=3gpp-bdc-used-by:sender")
		}
	}
	return strings.Join(cleaned, "\r\n") + "\r\n"
}

func stripAudioVideoFromSDP(sdpText string) string {
	normalized := strings.ReplaceAll(sdpText, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	var newLines []string
	skip := false
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if strings.HasPrefix(trimmed, "m=audio ") || strings.HasPrefix(trimmed, "m=video ") {
			skip = true
		} else if strings.HasPrefix(trimmed, "m=application ") {
			skip = false
		}
		if !skip {
			newLines = append(newLines, trimmed)
		}
	}
	return strings.Join(newLines, "\r\n") + "\r\n"
}

func parseSDPMedia(sdpText string) MediaInfo {
	var info MediaInfo
	normalized := strings.ReplaceAll(sdpText, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "c=IN IP4 ") {
			ip := strings.TrimPrefix(trimmed, "c=IN IP4 ")
			info.IP = ip
		} else if strings.HasPrefix(trimmed, "m=audio ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 2 {
				fmt.Sscanf(parts[1], "%d", &info.AudioPort)
			}
		} else if strings.HasPrefix(trimmed, "m=video ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 2 {
				fmt.Sscanf(parts[1], "%d", &info.VideoPort)
			}
		}
	}
	return info
}

type MediaInfo struct {
	IP        string
	AudioPort int
	VideoPort int
}

func reorderSDPMedia(webrtcSDP string, localIP string, audioPort, videoPort int) string {
	normalized := strings.ReplaceAll(webrtcSDP, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	
	var sessionLines []string
	var appLines []string
	
	inApp := false
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if strings.HasPrefix(trimmed, "a=extmap:") ||
			strings.HasPrefix(trimmed, "a=rtcp-fb:") ||
			strings.HasPrefix(trimmed, "a=rtcp-mux") ||
			strings.HasPrefix(trimmed, "a=rtcp:") {
			continue
		}
		if strings.HasPrefix(trimmed, "m=application ") {
			inApp = true
		}
		
		if inApp {
			if strings.HasPrefix(trimmed, "a=sendonly") || strings.HasPrefix(trimmed, "a=recvonly") {
				appLines = append(appLines, "a=sendrecv")
			} else {
				appLines = append(appLines, trimmed)
			}
		} else {
			sessionLines = append(sessionLines, trimmed)
		}
	}
	
	var sb strings.Builder
	for _, line := range sessionLines {
		sb.WriteString(line + "\r\n")
	}
	
	aPort := 50002
	if audioPort != 0 {
		aPort = audioPort
	}
	vPort := 50004
	if videoPort != 0 {
		vPort = videoPort
	}
	
	sb.WriteString(fmt.Sprintf("m=audio %d RTP/AVP 8\r\nc=IN IP4 %s\r\na=rtpmap:8 PCMA/8000\r\n", aPort, localIP))
	sb.WriteString(fmt.Sprintf("m=video %d RTP/AVP 97\r\nc=IN IP4 %s\r\na=rtpmap:97 H264/90000\r\n", vPort, localIP))
	
	appBlockCount := 0
	for _, line := range appLines {
		sb.WriteString(line + "\r\n")
		// Inject BDC attributes immediately after the m=application line
		if strings.HasPrefix(line, "m=application ") {
			appBlockCount++
			if appBlockCount == 1 {
				sb.WriteString("a=dcmap:10 subprotocol=\"http\"\r\n")
				sb.WriteString("a=3gpp-bdc-used-by:sender\r\n")
			}
		}
	}
	return sb.String()
}

func appendReqAppToSDP(sdp string, appId string) string {
	normalized := strings.ReplaceAll(sdp, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	var newLines []string
	
	reqApp := appId
	if appId == "caro" {
		reqApp = "caro"
	} else if appId == "sketch-sync-app" || appId == "whiteboard" {
		reqApp = "whiteboard"
	} else if appId == "emoji" {
		reqApp = "emoji"
	}
	
	streamId := "1000"
	if reqApp == "emoji" {
		streamId = "1001"
	}
	
	appCount := 0
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		newLines = append(newLines, trimmed)
		if strings.HasPrefix(trimmed, "m=application ") {
			appCount++
			if appCount == 2 {
				newLines = append(newLines, fmt.Sprintf("a=dcmap:%s subprotocol=%q", streamId, reqApp))
				newLines = append(newLines, fmt.Sprintf("a=3gpp-req-app:%q;%s-UE", reqApp, streamId))
			}
		}
	}
	return strings.Join(newLines, "\r\n") + "\r\n"
}

func appendReqAppToSDPClose(sdp string, appId string) string {
	normalized := strings.ReplaceAll(sdp, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	var newLines []string
	
	reqApp := appId
	if appId == "caro" {
		reqApp = "caro"
	} else if appId == "sketch-sync-app" || appId == "whiteboard" {
		reqApp = "whiteboard"
	}

	totalApps := 0
	for _, line := range lines {
		if strings.HasPrefix(strings.TrimSpace(line), "m=application ") {
			totalApps++
		}
	}
	
	inAppMedia := false
	appCount := 0
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		
		if strings.HasPrefix(trimmed, "m=application ") {
			appCount++
			shouldDisable := (totalApps == 1 && appCount == 1) || (totalApps > 1 && appCount == 2)
			if shouldDisable {
				inAppMedia = true
				parts := strings.Split(trimmed, " ")
				if len(parts) >= 2 {
					parts[1] = "0"
					newLines = append(newLines, strings.Join(parts, " "))
				} else {
					newLines = append(newLines, "m=application 0 UDP/DTLS/SCTP webrtc-datachannel")
				}
				continue
			} else {
				inAppMedia = false
			}
		}
		
		if inAppMedia && (strings.HasPrefix(trimmed, "m=audio ") || strings.HasPrefix(trimmed, "m=video ")) {
			inAppMedia = false
		}
		
		if inAppMedia {
			if strings.Contains(trimmed, reqApp) {
				continue
			}
		}
		
		newLines = append(newLines, trimmed)
	}
	return strings.Join(newLines, "\r\n") + "\r\n"
}

func getInterfaceNameByIP(ipStr string) string {
	if ipStr == "" || ipStr == "127.0.0.1" {
		return ""
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return ""
	}
	for _, iface := range ifaces {
		addrs, _ := iface.Addrs()
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip != nil && ip.String() == ipStr {
				return iface.Name
			}
		}
	}
	return ""
}

func createPeerConnectionWithKeyLog(keyLogPath string, localIP string) (*webrtc.PeerConnection, error) {
	m := &webrtc.MediaEngine{}
	err := m.RegisterCodec(webrtc.RTPCodecParameters{
		RTPCodecCapability: webrtc.RTPCodecCapability{
			MimeType:     webrtc.MimeTypePCMA,
			ClockRate:    8000,
			Channels:     1,
			SDPFmtpLine:  "",
			RTCPFeedback: nil,
		},
		PayloadType: 8,
	}, webrtc.RTPCodecTypeAudio)
	if err != nil {
		return nil, err
	}

	err = m.RegisterCodec(webrtc.RTPCodecParameters{
		RTPCodecCapability: webrtc.RTPCodecCapability{
			MimeType:     webrtc.MimeTypeH264,
			ClockRate:    90000,
			Channels:     0,
			SDPFmtpLine:  "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
			RTCPFeedback: nil,
		},
		PayloadType: 97,
	}, webrtc.RTPCodecTypeVideo)
	if err != nil {
		return nil, err
	}

	s := webrtc.SettingEngine{}
	s.SetAnsweringDTLSRole(webrtc.DTLSRoleClient)
	s.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled)
	if keyLogPath != "" {
		if klw, err := os.OpenFile(keyLogPath, os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0666); err == nil {
			s.SetDTLSKeyLogWriter(klw)
		}
	}

	if ifaceName := getInterfaceNameByIP(localIP); ifaceName != "" {
		s.SetInterfaceFilter(func(name string) bool {
			return name == ifaceName || name == "lo" || strings.HasPrefix(name, "tailscale")
		})
	}

	api := webrtc.NewAPI(webrtc.WithMediaEngine(m), webrtc.WithSettingEngine(s))
	return api.NewPeerConnection(webrtc.Configuration{})
}

func splitSDPToMediaBlocks(sdp string) (sessionHeader string, audioBlock string, videoBlock string, appBlocks []string) {
	normalized := strings.ReplaceAll(sdp, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	var current []string
	mediaType := "" // "audio", "video", "application"

	flush := func() {
		if len(current) == 0 {
			return
		}
		blockStr := strings.Join(current, "\r\n") + "\r\n"
		switch mediaType {
		case "":
			sessionHeader = blockStr
		case "audio":
			audioBlock = blockStr
		case "video":
			videoBlock = blockStr
		case "application":
			appBlocks = append(appBlocks, blockStr)
		}
		current = nil
	}

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if strings.HasPrefix(trimmed, "m=") {
			flush()
			if strings.HasPrefix(trimmed, "m=audio ") {
				mediaType = "audio"
			} else if strings.HasPrefix(trimmed, "m=video ") {
				mediaType = "video"
			} else if strings.HasPrefix(trimmed, "m=application ") {
				mediaType = "application"
			} else {
				mediaType = "unknown"
			}
		}
		current = append(current, trimmed)
	}
	flush()
	return
}

func parseReqApp(sdpBody string) string {
	for _, line := range strings.Split(sdpBody, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "a=3gpp-req-app:") {
			val := strings.TrimPrefix(trimmed, "a=3gpp-req-app:")
			var parts []string
			if strings.Contains(val, ";") {
				parts = strings.SplitN(val, ";", 2)
			} else {
				parts = strings.SplitN(val, ":", 2)
			}
			return strings.Trim(parts[0], `"`)
		}
	}
	return ""
}

func SplitBdcAnswer(sessionSDP string) string {
	sessionHeader, audioBlock, videoBlock, appBlocks := splitSDPToMediaBlocks(sessionSDP)
	var bdcBlock string
	if len(appBlocks) > 0 {
		bdcBlock = appBlocks[0]
	}

	var bdcMid string
	if bdcBlock != "" {
		for _, line := range strings.Split(bdcBlock, "\n") {
			trimmed := strings.TrimSpace(line)
			if strings.HasPrefix(trimmed, "a=mid:") {
				bdcMid = strings.TrimPrefix(trimmed, "a=mid:")
				break
			}
		}
	}

	var cleanHeader strings.Builder
	for _, line := range strings.Split(sessionHeader, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed != "" && !strings.HasPrefix(trimmed, "a=group:BUNDLE") {
			cleanHeader.WriteString(trimmed + "\r\n")
		}
	}
	if bdcMid != "" {
		cleanHeader.WriteString("a=group:BUNDLE " + bdcMid + "\r\n")
	}

	var bdcSb strings.Builder
	bdcSb.WriteString(cleanHeader.String())
	if audioBlock != "" {
		bdcSb.WriteString(audioBlock)
	}
	if videoBlock != "" {
		bdcSb.WriteString(videoBlock)
	}
	if bdcBlock != "" {
		bdcSb.WriteString(bdcBlock)
	}
	return bdcSb.String()
}

func SplitAdcAnswer(sessionSDP string, appId string) string {
	sessionHeader, _, _, appBlocks := splitSDPToMediaBlocks(sessionSDP)
	var matchedBlock string
	for _, block := range appBlocks {
		for _, line := range strings.Split(block, "\n") {
			trimmed := strings.TrimSpace(line)
			if strings.HasPrefix(trimmed, "a=3gpp-req-app:") {
				val := strings.TrimPrefix(trimmed, "a=3gpp-req-app:")
				var parts []string
				if strings.Contains(val, ";") {
					parts = strings.SplitN(val, ";", 2)
				} else {
					parts = strings.SplitN(val, ":", 2)
				}
				extractedAppId := strings.Trim(parts[0], `"`)
				if extractedAppId == appId {
					matchedBlock = block
					break
				}
			}
		}
		if matchedBlock != "" {
			break
		}
	}
	if matchedBlock == "" && len(appBlocks) > 1 {
		matchedBlock = appBlocks[1]
	}
	if matchedBlock == "" {
		return ""
	}

	var adcMid string
	for _, line := range strings.Split(matchedBlock, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "a=mid:") {
			adcMid = strings.TrimPrefix(trimmed, "a=mid:")
			break
		}
	}
	if adcMid == "" {
		adcMid = "3" // fallback
	}

	var adcFingerprints []string
	var adcTlsIds []string
	// Search first in matchedBlock (media level)
	for _, line := range strings.Split(matchedBlock, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "a=fingerprint:") {
			adcFingerprints = append(adcFingerprints, trimmed)
		} else if strings.HasPrefix(trimmed, "a=tls-id:") {
			adcTlsIds = append(adcTlsIds, trimmed)
		}
	}
	// Fallback to session level if not in media level
	if len(adcFingerprints) == 0 {
		for _, line := range strings.Split(sessionHeader, "\n") {
			trimmed := strings.TrimSpace(line)
			if strings.HasPrefix(trimmed, "a=fingerprint:") {
				adcFingerprints = append(adcFingerprints, trimmed)
			}
		}
	}
	if len(adcTlsIds) == 0 {
		for _, line := range strings.Split(sessionHeader, "\n") {
			trimmed := strings.TrimSpace(line)
			if strings.HasPrefix(trimmed, "a=tls-id:") {
				adcTlsIds = append(adcTlsIds, trimmed)
			}
		}
	}

	var cleanMatchedBlock strings.Builder
	for _, line := range strings.Split(matchedBlock, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "a=mid:") {
			cleanMatchedBlock.WriteString("a=mid:0\r\n")
		} else if trimmed != "" && !strings.HasPrefix(trimmed, "a=bundle-only") {
			cleanMatchedBlock.WriteString(trimmed + "\r\n")
		}
	}

	var adcSessionHeader strings.Builder
	for _, line := range strings.Split(sessionHeader, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed != "" && !strings.HasPrefix(trimmed, "a=group:BUNDLE") && !strings.HasPrefix(trimmed, "a=fingerprint:") && !strings.HasPrefix(trimmed, "a=tls-id:") {
			adcSessionHeader.WriteString(trimmed + "\r\n")
		}
	}
	adcSessionHeader.WriteString("a=group:BUNDLE 0\r\n")
	for _, fp := range adcFingerprints {
		adcSessionHeader.WriteString(fp + "\r\n")
	}
	for _, tid := range adcTlsIds {
		adcSessionHeader.WriteString(tid + "\r\n")
	}

	var adcSb strings.Builder
	adcSb.WriteString(adcSessionHeader.String())
	adcSb.WriteString(cleanMatchedBlock.String())
	return adcSb.String()
}

func MergeAdcOffer(sessionSDP string, adcFragment string, isAnswer bool) string {
	sessionHeader, audioBlock, videoBlock, appBlocks := splitSDPToMediaBlocks(sessionSDP)
	adcHeader, _, _, adcAppBlocks := splitSDPToMediaBlocks(adcFragment)

	var adcFingerprints []string
	var adcTlsIds []string
	for _, line := range strings.Split(adcHeader, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "a=fingerprint:") {
			adcFingerprints = append(adcFingerprints, trimmed)
		} else if strings.HasPrefix(trimmed, "a=tls-id:") {
			adcTlsIds = append(adcTlsIds, trimmed)
		}
	}

	var sb strings.Builder
	sb.WriteString(sessionHeader)
	if audioBlock != "" {
		sb.WriteString(audioBlock)
	}
	if videoBlock != "" {
		sb.WriteString(videoBlock)
	}
	if len(appBlocks) > 0 {
		sb.WriteString(appBlocks[0])
	}
	if len(adcAppBlocks) > 0 {
		adcBlock := adcAppBlocks[0]
		var rewrittenBlock strings.Builder
		for _, line := range strings.Split(adcBlock, "\n") {
			trimmed := strings.TrimSpace(line)
			if trimmed == "" {
				continue
			}
			if strings.HasPrefix(trimmed, "a=mid:") {
				if isAnswer {
					rewrittenBlock.WriteString(trimmed + "\r\n")
				} else {
					rewrittenBlock.WriteString("a=mid:3\r\n")
				}
			} else {
				rewrittenBlock.WriteString(trimmed + "\r\n")
			}
		}
		for _, fp := range adcFingerprints {
			rewrittenBlock.WriteString(fp + "\r\n")
		}
		for _, tid := range adcTlsIds {
			rewrittenBlock.WriteString(tid + "\r\n")
		}
		sb.WriteString(rewrittenBlock.String())
	}
	return sb.String()
}

func splitSDPAnswer(sdpAnswer string) (bdcAnswer string, adcAnswer string) {
	appId := parseReqApp(sdpAnswer)
	bdcAnswer = SplitBdcAnswer(sdpAnswer)
	adcAnswer = SplitAdcAnswer(sdpAnswer, appId)
	return
}

// StartCallerCall starts the SIP and WebRTC outgoing call setup.
func (ue *UESimulator) StartCallerCall() {
	go ue.startCallerCall()
}

// SendDataChannelMessage sends text over the designated Stream ID's data channel.
func (ue *UESimulator) SendDataChannelMessage(streamID uint16, payload string) error {
	var targetChan *webrtc.DataChannel

	ue.mu.Lock()
	if streamID == 1000 {
		targetChan = ue.adcChannel
	} else if streamID == 1001 {
		targetChan = ue.adcChannel2
	} else if ue.bdcChannels != nil {
		targetChan = ue.bdcChannels[streamID]
	}
	if targetChan == nil && streamID == 0 {
		targetChan = ue.bdcChannel
	}
	ue.mu.Unlock()

	if targetChan == nil {
		return fmt.Errorf("stream id %d data channel not initialized", streamID)
	}

	// Wait up to 5 seconds for channel to reach Open state
	deadline := time.Now().Add(5 * time.Second)
	for targetChan.ReadyState() != webrtc.DataChannelStateOpen && time.Now().Before(deadline) {
		time.Sleep(100 * time.Millisecond)
	}

	return targetChan.SendText(payload)
}

// SendBye triggers a SIP BYE request to terminate the active call.
func (ue *UESimulator) SendBye() {
	ue.sendBye()
}

// SendReInvite triggers a SIP re-INVITE to launch the specified mini-app.
func (ue *UESimulator) SendReInvite(appId string) {
	ue.sendReInvite(appId)
}

// SendReInviteClose triggers a SIP re-INVITE with port 0 to close the specified mini-app ADC.
func (ue *UESimulator) SendReInviteClose(appId string) {
	ue.sendReInviteClose(appId)
}

// SendCancel triggers a SIP CANCEL request to terminate call setup.
func (ue *UESimulator) SendCancel() {
	ue.sendCancel()
}

func rewriteMidToZero(sdp string) string {
	var rewritten strings.Builder
	for _, line := range strings.Split(sdp, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		if strings.HasPrefix(trimmed, "a=mid:") {
			rewritten.WriteString("a=mid:0\r\n")
		} else if strings.HasPrefix(trimmed, "a=group:BUNDLE ") {
			rewritten.WriteString("a=group:BUNDLE 0\r\n")
		} else {
			rewritten.WriteString(trimmed + "\r\n")
		}
	}
	return rewritten.String()
}

// RejectIncomingCall rejects the current incoming call.
func (ue *UESimulator) RejectIncomingCall() {
	ue.rejectIncomingCall()
}

// IsDataChannelOpen reports if the specified stream ID is currently open.
func (ue *UESimulator) IsDataChannelOpen(streamID uint16) bool {
	ue.mu.Lock()
	defer ue.mu.Unlock()
	if streamID == 1000 && ue.adcChannel != nil {
		return ue.adcChannel.ReadyState() == webrtc.DataChannelStateOpen
	}
	if streamID == 1001 && ue.adcChannel2 != nil {
		return ue.adcChannel2.ReadyState() == webrtc.DataChannelStateOpen
	}
	if ue.bdcChannels != nil {
		if ch, ok := ue.bdcChannels[streamID]; ok && ch != nil {
			return ch.ReadyState() == webrtc.DataChannelStateOpen
		}
	}
	if streamID == 0 && ue.bdcChannel != nil {
		return ue.bdcChannel.ReadyState() == webrtc.DataChannelStateOpen
	}
	return false
}


