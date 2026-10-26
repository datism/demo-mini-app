package imsas

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"strings"
	"time"

	"github.com/demo/ims-dc-mf/internal/sba"
)

func (b *B2BUA) handleInvite(msg *SIPMessage, remoteAddr *net.UDPAddr) {
	callID := msg.GetHeader("Call-ID")
	// Normalizing callID: Strip b2b_ prefix to lookup the correct original session
	if strings.HasPrefix(callID, "b2b_") {
		callID = strings.TrimPrefix(callID, "b2b_")
	}
	fromUser := msg.ExtractUsername("From")
	toUser := msg.ExtractUsername("To")
	cseqVal, _ := msg.GetCSeq()

	log.Printf("[IMS-AS] Received INVITE leg for call %s (CSeq: %d): %s -> %s", callID, cseqVal, fromUser, toUser)

	dcsfHost := resolveAndHashHost(b.cfg.Services.Dcsf.Host, callID)
	dcsfURL := fmt.Sprintf("http://%s:%d", dcsfHost, b.cfg.Services.Dcsf.Port)

	session, exists := b.getSession(callID)
	if exists {
		// 0. Callee-initiated re-INVITE: the B-side uses its OWN independent CSeq counter,
		// so its CSeq may be <= session.LastCSeq (the caller's last CSeq). It must NOT be
		// mistaken for the caller's terminating leg. Route to handleReInvite, which already
		// handles the symmetric (isCallerInitiator=false) direction.
		if fromUser == session.Callee && session.TermAnswered && cseqVal > session.LastCalleeCSeq {
			log.Printf("[IMS-AS] Detected callee-initiated re-INVITE for call %s (From=%s, CSeq %d). Routing...", callID, fromUser, cseqVal)
			if session.LastCalleeCSeq == 0 {
				session.InitialCalleeCSeq = cseqVal
			}
			session.LastCalleeCSeq = cseqVal
			b.saveSession(session)
			b.handleReInvite(session, msg, remoteAddr)
			return
		}

		// 1. Check if it is a caller-initiated re-INVITE
		if cseqVal > session.LastCSeq {
			log.Printf("[IMS-AS] Detected re-INVITE for call %s (CSeq %d > %d). Routing...", callID, cseqVal, session.LastCSeq)
			session.LastCSeq = cseqVal
			b.saveSession(session)
			b.handleReInvite(session, msg, remoteAddr)
			return
		}

		// 2. Check if it is the terminating leg of the caller re-INVITE
		// (From must be the caller — a callee-originated INVITE is handled in step 0)
		if cseqVal == session.LastCSeq && cseqVal != session.LastTermCSeq && fromUser != session.Callee {
			session.LastTermCSeq = cseqVal
			b.saveSession(session)
			log.Printf("[IMS-AS] Forwarding terminating leg re-INVITE for call %s (CSeq %d) transparently", callID, cseqVal)
			headers := map[string]string{
				"Content-Type": "application/sdp",
			}
			forwardedInvite := b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, msg.Body)
			b.forwardToSCSCF(forwardedInvite)
			return
		}

		if msg.GetHeader("Via") == session.TopVia {
			log.Printf("[IMS-AS] Ignoring duplicate/retransmitted INVITE for call %s", callID)
			return
		}
		if session.TermForwarded {
			log.Printf("[IMS-AS] Ignoring re-routed terminating INVITE for call %s (already forwarded)", callID)
			return
		}
		session.TermForwarded = true
		b.saveSession(session)
		log.Printf("[IMS-AS] Forwarding terminating leg INVITE transparently for call %s", callID)
		headers := map[string]string{
			"Content-Type": "application/sdp",
		}
		forwardedInvite := b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, msg.Body)
		b.forwardToSCSCF(forwardedInvite)
		return
	}

	// Resolve and hash target MF host at session initialization
	mfHost := resolveAndHashHost(b.cfg.Services.Mf.Host, callID)

	contact := msg.GetHeader("Contact")
	isCallerDC := strings.Contains(contact, "webrtc-datachannel")
	isCalledHotline := (toUser == "198")
	isOriginatingMTSIInterworking := !isCallerDC && isCalledHotline

	psDataOffStatus := "INACTIVE"
	if strings.Contains(strings.ToLower(contact), "ps-data-off=active") || strings.Contains(strings.ToLower(contact), "ps-data-off") {
		psDataOffStatus = "ACTIVE"
	}

	// B2BUA: Generate new Call-ID for terminating leg by prefixing OrigCallID
	termCallID := "b2b_" + callID
	// RFC 7989: Generate Session-ID for end-to-end tracking
	sessionUUID := generateSIPTag() + generateSIPTag()

	// Create call session with channels
	session = &CallSession{
		CallID:          callID,
		OrigCallID:      callID,
		TermCallID:      termCallID,
		SessionID:       sessionUUID,
		Caller:          fromUser,
		Callee:          toUser,
		OrigMsg:         msg,
		ScscfAddr:       remoteAddr,
		TopVia:          msg.GetHeader("Via"),
		InstructionChan: make(chan *sba.MediaInstructionData, 1),
		ResponseChan:    make(chan *sba.MediaInstructionData, 1),
		MfHost:          mfHost,
		LastCSeq:        cseqVal,
		InitialCSeq:     cseqVal,
		IsInterworking:  isOriginatingMTSIInterworking,
		PsDataOff:       psDataOffStatus,
		ActiveAppID:     "operator-menu",
	}
	b.saveSession(session)
	log.Printf("[IMS-AS] B2BUA Call-ID split: OrigCallID=%s -> TermCallID=%s, Session-ID=%s", callID, termCallID, sessionUUID)

	// Notify DCSF via DC1 Nimsas_SessionEventControl
	notification := sba.SessionEventNotification{
		SessionID:      callID,
		IsInterworking: isOriginatingMTSIInterworking,
		NotificationEvent: sba.NotificationEvent{
			ReferenceID: 10,
			EventType:   "SESSION_ESTABLISHMENT_REQUEST",
		},
		DcMediaPara: &sba.DcMediaPara{
			DcType: "BDC",
		},
		PsDataOff: psDataOffStatus,
		SessionInfo: sba.SessionInfo{
			CallingIdentity: "sip:" + fromUser + "@" + b.cfg.Domains.Ims,
			CalledIdentity:  "sip:" + toUser + "@" + b.cfg.Domains.Ims,
			SessionCase:     "ORIGINATING_IMS_SESSION",
		},
		MediaInfoList: map[string]sba.MediaInfo{
			"3": {
				MediaID:   "3",
				MediaType: "DC",
				DcMediaSpec: &sba.DcMediaSpec{
					Streams: map[string]sba.Stream{
						"10": {
							StreamID:    10,
							Subprotocol: "http",
							Order:       true,
						},
					},
					MaxMessageSize: 65536,
					ReceivedDcEndpoint: &sba.DcEndpoint{
						SctpPort:      5000,
						Fingerprint:   "SHA-256 XX:XX:XX...",
						SecuritySetup: "actpass",
					},
					BdcUsedBy: "sender",
				},
			},
		},
	}

	go func() {
		log.Printf("[IMS-AS] Sending Nimsas_SessionEventControl Notification to DCSF for call %s", callID)
		if err := b.CallNimsasSessionEventNotify(dcsfURL, notification); err != nil {
			log.Printf("[IMS-AS] Nimsas Notify failed: %v", err)
		}
	}()

	// Drain any stale instructions
	for len(session.InstructionChan) > 0 {
		<-session.InstructionChan
	}

	// Wait for media instruction from DCSF
	select {
	case instruction := <-session.InstructionChan:
		log.Printf("[IMS-AS] Applying media instructions for call %s", callID)
		b.processMediaInstruction(session, instruction, remoteAddr)
	case <-time.After(5 * time.Second):
		log.Printf("[IMS-AS] [TS 24.186 Clause 9.4.4] Timeout waiting for media instruction for call %s. Disabling data channel (port 0) and continuing voice/video procedure...", callID)
		rewrittenSDP := ExcludeADCFromSDP(msg.Body)
		headers := map[string]string{
			"Content-Type": "application/sdp",
			"Call-ID":      session.TermCallID,
			"Session-ID":   session.SessionID + ";remote=00000000000000000000000000000000",
		}
		forwardedInvite := b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, rewrittenSDP)
		b.forwardToSCSCF(forwardedInvite)
	}
}

func (b *B2BUA) handleReInvite(session *CallSession, msg *SIPMessage, remoteAddr *net.UDPAddr) {
	session.OrigMsg = msg
	log.Printf("[IMS-AS] Processing re-INVITE for session %s (caller=%s callee=%s)", session.CallID, session.Caller, session.Callee)

	// Parse a=3gpp-req-app from SDP body (AC.5 Step 2)
	appBinding := parseReqApp(msg.Body)
	var reqAppId string
	var streamID int
	var endpointType string
	if appBinding != nil {
		reqAppId = appBinding.ReqAppId
		log.Printf("[IMS-AS] Extracted a=3gpp-req-app: reqAppId=%s, appDcInfo=%s", appBinding.ReqAppId, appBinding.AppDcInfo)
		if appBinding.AppDcInfo != "" {
			parts := strings.Split(appBinding.AppDcInfo, "-")
			if len(parts) == 2 {
				fmt.Sscanf(parts[0], "%d", &streamID)
				endpointType = parts[1]
				log.Printf("[IMS-AS] Parsed adc-stream-id-endpoint: streamID=%d, endpointType=%s", streamID, endpointType)
			}
		}
	}
	if appBinding == nil || reqAppId == "" {
		if isReleaseSdp(msg.Body) {
			reqAppId = "operator-menu"
			log.Printf("[IMS-AS] No a=3gpp-req-app found, but SDP is release SDP. Setting reqAppId to operator-menu")
		}
	}

	// ADC Release: if operator-menu Re-INVITE arrives while an ADC context already exists,
	// this is the caller signalling ADC teardown. Forward the Re-INVITE unchanged so the
	// callee receives the a=3gpp-req-app:"operator-menu" cue and exits the whiteboard.
	// Do NOT attempt to create a new MF context — it already exists and would return 403.
	if reqAppId == "operator-menu" && session.IsAnchorCtxCreated {
		log.Printf("[IMS-AS] ADC Release detected (operator-menu Re-INVITE, existing ADC ctx). Forwarding unchanged to callee.")
		session.IsADCReleasing = true
		session.ActiveAppID = "operator-menu"
		targetCallID := session.TermCallID
		if msg.ExtractUsername("From") != session.Caller {
			targetCallID = session.OrigCallID
		}
		headers := map[string]string{
			"Content-Type": "application/sdp",
			"Call-ID":      targetCallID,
		}
		forwardedInvite := b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, msg.Body)
		b.forwardToSCSCF(forwardedInvite)
		return
	}

	isAppSwitch := (session.IsAnchorCtxCreated && reqAppId != "" && reqAppId != "operator-menu")

	if session.IsAnchorCtxCreated && !isAppSwitch {
		log.Printf("[IMS-AS] ADC context already created for app '%s'.", session.ActiveAppID)
		if session.Callee == "198" {
			headers := map[string]string{
				"Content-Type": "application/sdp",
			}
			resp := b.BuildSIPResponse(msg, 200, "OK", headers, msg.Body)
			b.sendUDP(resp, remoteAddr)
			log.Printf("[IMS-AS] Replied 200 OK for Hotline 198 re-INVITE directly (existing ctx)")
			return
		}
		targetCallID := session.TermCallID
		if msg.ExtractUsername("From") != session.Caller {
			targetCallID = session.OrigCallID
		}
		headers := map[string]string{
			"Content-Type": "application/sdp",
			"Call-ID":      targetCallID,
		}
		forwardedInvite := b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, msg.Body)
		b.forwardToSCSCF(forwardedInvite)
		return
	}

	// 1. Notify DCSF via DC1 (MEDIA_CHANGE_REQUEST)
	dcsfHost := resolveAndHashHost(b.cfg.Services.Dcsf.Host, session.CallID)
	dcsfURL := fmt.Sprintf("http://%s:%d", dcsfHost, b.cfg.Services.Dcsf.Port)

	adcType := "P2P"
	if reqAppId != "" {
		if reqAppId == "fun-calling-app" || reqAppId == "caro" || reqAppId == "tic-tac-toe-app" {
			adcType = "P2A2P"
		} else if reqAppId == "operator-menu" || reqAppId == "smart-menu" || reqAppId == "enterprise-callerid" || reqAppId == "flight-booking" {
			adcType = "P2A"
		}
	}

	var dcMediaPara *sba.DcMediaPara
	if appBinding != nil {
		dcMediaPara = &sba.DcMediaPara{
			DcType:         "ADC",
			AdcType:        adcType,
			AppBindingInfo: appBinding,
		}
	}

	notification := sba.SessionEventNotification{
		SessionID: session.CallID,
		NotificationEvent: sba.NotificationEvent{
			ReferenceID:    11,
			EventType:      "MEDIA_CHANGE_REQUEST",
			EventInitiator: "served IMS subscriber",
		},
		AppBindingInfo: appBinding, // backwards compatibility
		DcMediaPara:    dcMediaPara,
		PsDataOff:      session.PsDataOff,
		SessionInfo: sba.SessionInfo{
			CallingIdentity: "sip:" + session.Caller + "@" + b.cfg.Domains.Ims,
			CalledIdentity:  "sip:" + session.Callee + "@" + b.cfg.Domains.Ims,
			SessionCase:     "ORIGINATING_IMS_SESSION",
		},
	}

	go func() {
		log.Printf("[IMS-AS] Sending Nimsas Nimsas_SessionEventControl_Notify (MEDIA_CHANGE_REQUEST) to DCSF")
		_ = b.CallNimsasSessionEventNotify(dcsfURL, notification)
	}()

	// Drain any stale instructions
	for len(session.InstructionChan) > 0 {
		<-session.InstructionChan
	}

	// Wait for media instruction from DCSF
	select {
	case instruction := <-session.InstructionChan:
		log.Printf("[IMS-AS] Applying media instructions for re-INVITE: %+v", instruction)

		var mSpec *sba.DcMediaSpecification
		for _, mi := range instruction.MediaInstructionSet {
			if mi.MediaResourceType == "DC" {
				mSpec = mi.DcMediaSpecification
				break
			}
		}
		if mSpec != nil {
			log.Printf("[IMS-AS] DEBUG: mSpec MediaProxyConfig='%s', AdcType='%s', QosHint='%s'", mSpec.MediaProxyConfig, mSpec.AdcType, mSpec.QosHint)
		} else {
			log.Printf("[IMS-AS] DEBUG: mSpec is nil")
		}

		// Respond back to DCSF via ResponseChan to unblock HandleMediaInstruction
		select {
		case session.ResponseChan <- instruction:
		default:
		}

		if mSpec == nil || mSpec.AdcType == "P2P" {
			// No MF anchoring required!
			log.Printf("[IMS-AS] P2P Setup: No MF anchoring required for re-INVITE. Forwarding SDP unchanged.")
			session.IsP2P = true
			session.AdcType = "P2P"

			// Forward the original SIP re-INVITE unchanged
			targetCallID := session.TermCallID
			if msg.ExtractUsername("From") != session.Caller {
				targetCallID = session.OrigCallID
			}
			headers := map[string]string{
				"Content-Type": "application/sdp",
				"Call-ID":      targetCallID,
			}
			forwardedInvite := b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, msg.Body)
			b.forwardToSCSCF(forwardedInvite)
			return
		}

		fromUser := msg.ExtractUsername("From")
		isCallerInitiator := (fromUser == session.Caller)
		if isCallerInitiator {
			session.LastCallerReInviteMsg = msg
		} else {
			session.LastCalleeReInviteMsg = msg
		}

		if session.IsAnchorCtxCreated {
			proxyMode := ""
			if mSpec != nil {
				proxyMode = mSpec.MediaProxyConfig
			}
			log.Printf("[IMS-AS] App Switch: patching existing ADC context %s in-place to ProxyMode='%s'", session.CallID, proxyMode)
			session.IsP2P = (mSpec == nil || mSpec.AdcType == "P2P")
			if mSpec != nil {
				session.AdcType = mSpec.AdcType
			}
			session.ActiveAppID = reqAppId
			session.IsAnchorPatched = false

			mfHost := session.MfHost
			if mfHost == "" {
				mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, session.CallID)
				session.MfHost = mfHost
			}
			mfURL := fmt.Sprintf("http://%s:%d", mfHost, b.cfg.Services.Mf.Port)

			targetTermPath := "/terminations/0/sdp"
			if !isCallerInitiator {
				targetTermPath = "/terminations/1/sdp"
			}
			proxyModeVal, _ := json.Marshal(proxyMode)
			patches := []sba.PatchDocument{
				{
					Op:    "replace",
					Path:  "/terminations/0/proxyMode",
					Value: proxyModeVal,
				},
				{
					Op:    "replace",
					Path:  targetTermPath,
					Value: json.RawMessage(fmt.Sprintf("%q", msg.Body)),
				},
			}
			if mSpec != nil && mSpec.Mdc2EndpointInfo != nil {
				mdc2InfoVal, _ := json.Marshal(sba.Mdc2Info{
					Mdc2Protocol:       mSpec.Mdc2EndpointInfo.Mdc2Protocol,
					RemoteMdc2Endpoint: mSpec.Mdc2EndpointInfo.Mdc2EndpointDcAs,
					LocalMdc2Endpoint:  mSpec.Mdc2EndpointInfo.Mdc2EndpointMf,
				})
				patches = append(patches, sba.PatchDocument{
					Op:    "replace",
					Path:  "/terminations/0/dcMedia/mdc2Info",
					Value: mdc2InfoVal,
				})
			}
			err := b.CallNmfUpdateMediaContext(mfURL, session.CallID, patches)
			if err != nil {
				log.Printf("[IMS-AS] Nmf Context Patch failed for app switch: %v", err)
			}

			// Forward the rewritten SIP re-INVITE to the peer pointing to the target MF
			var targetMfInfo *MFAllocResponse
			if isCallerInitiator {
				targetMfInfo = session.MfInfoB
			} else {
				targetMfInfo = session.MfInfoA
			}

			rewrittenSDP := msg.Body
			if targetMfInfo != nil {
				targetMfInfo.AdcSetupRole = "actpass"
				rewrittenSDP = RewriteSDP(msg.Body, targetMfInfo, session.ActiveAppID)
				log.Printf("[IMS-AS] App Switch: Rewrote terminating ADC SDP Offer pointing to MF (AdcSetupRole=actpass)")
			} else {
				log.Printf("[IMS-AS] App Switch: targetMfInfo is nil, forwarding SDP unchanged")
			}

			targetCallID := session.TermCallID
			if msg.ExtractUsername("From") != session.Caller {
				targetCallID = session.OrigCallID
			}
			headers := map[string]string{
				"Content-Type": "application/sdp",
				"Call-ID":      targetCallID,
			}
			forwardedInvite := b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, rewrittenSDP)
			b.forwardToSCSCF(forwardedInvite)
			return
		}

		session.IsP2P = false
		session.AdcType = mSpec.AdcType
		log.Printf("[IMS-AS] MF anchoring required (%s, AdcType=%s) for re-INVITE.", mSpec.MediaProxyConfig, mSpec.AdcType)

		mfHost := session.MfHost
		if mfHost == "" {
			mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, session.CallID)
			session.MfHost = mfHost
		}
		mfURL := fmt.Sprintf("http://%s:%d", mfHost, b.cfg.Services.Mf.Port)

		// Create ADC media context Request on MF
		isCore := true
		if session.OrigMsg != nil {
			if session.OrigMsg.GetHeader("x-real-ip") != "" {
				isCore = false
			} else if strings.Contains(session.OrigMsg.Body, "c=IN IP4 100.") || strings.Contains(session.OrigMsg.Raw, "100.") {
				// Fallback check
				isCore = false
			}
		}
		contextReq := sba.MediaContext{
			ContextID:     session.CallID,
			IsCoreNetwork: isCore,
		}

		dcMedia := &sba.DcMedia{
			MediaProxyConfig: mSpec.MediaProxyConfig,
			Streams:          mSpec.Streams,
			ReplaceHttpUrls:  mSpec.ReplaceHttpUrls,
		}
		if mSpec.Mdc2EndpointInfo != nil {
			dcMedia.Mdc2Info = &sba.Mdc2Info{
				Mdc2Protocol:       mSpec.Mdc2EndpointInfo.Mdc2Protocol,
				RemoteMdc2Endpoint: mSpec.Mdc2EndpointInfo.Mdc2EndpointDcAs,
				LocalMdc2Endpoint:  mSpec.Mdc2EndpointInfo.Mdc2EndpointMf,
			}
		}

		fromUser = msg.ExtractUsername("From")
		isCallerInitiator = (fromUser == session.Caller)

		var sdpA, sdpB string
		if isCallerInitiator {
			sdpA = msg.Body
		} else {
			sdpB = msg.Body
		}

		if mSpec.AdcType == "P2A" {
			if session.IsInterworking {
				// Interworking Mode: include legacy callee RTP video termination
				var videoSDP string
				if isCallerInitiator {
					videoSDP = session.OrigMsg.Body
				} else {
					videoSDP = msg.Body
				}
				contextReq.Terminations = []sba.Termination{
					{
						TerminationID: "term-caller-a",
						Medias: []sba.MediaItem{
							{
								MediaID:           "adc-media",
								MediaResourceType: "DC",
								SDP:               sdpA,
								DcMedia:           dcMedia,
							},
						},
					},
					{
						TerminationID: "term-callee-b",
						Medias: []sba.MediaItem{
							{
								MediaID:           "video-media",
								MediaResourceType: "RTP",
								SDP:               videoSDP,
							},
						},
					},
				}
			} else {
				contextReq.Terminations = []sba.Termination{
					{
						TerminationID: "term-caller-a",
						Medias: []sba.MediaItem{
							{
								MediaID:           "adc-media",
								MediaResourceType: "DC",
								SDP:               sdpA,
								DcMedia:           dcMedia,
							},
						},
					},
					{
						TerminationID: "term-callee-b",
						Medias: []sba.MediaItem{
							{
								MediaID:           "adc-media",
								MediaResourceType: "DC",
								SDP:               sdpB,
								DcMedia:           dcMedia,
							},
						},
					},
				}
			}
		} else { // P2A2P
			contextReq.Terminations = []sba.Termination{
				{
					TerminationID: "term-caller-a",
					Medias: []sba.MediaItem{
						{
							MediaID:           "adc-media",
							MediaResourceType: "DC",
							SDP:               sdpA,
							DcMedia:           dcMedia,
						},
					},
				},
				{
					TerminationID: "term-callee-b",
					Medias: []sba.MediaItem{
						{
							MediaID:           "adc-media",
							MediaResourceType: "DC",
							SDP:               sdpB,
							DcMedia:           dcMedia,
						},
					},
				},
			}
		}

		allocatedCtx, err := b.CallNmfCreateMediaContext(mfURL, contextReq)
		if err != nil {
			log.Printf("[IMS-AS] Nmf Context Create failed for re-invite ADC: %v", err)
			b.sendError(msg, 503, "Service Unavailable", remoteAddr)
			return
		}

		session.IsAnchorCtxCreated = true
		session.ActiveAppID = reqAppId

		var mfInfoA = session.MfInfoA
		var mfInfoB = session.MfInfoB
		for _, term := range allocatedCtx.Terminations {
			for _, mItem := range term.Medias {
				isADC := mItem.MediaID == "adc-media" || (mItem.MediaResourceType == "DC" && mItem.MediaID != "1" && mItem.MediaID != "3")
				if term.TerminationID == "term-caller-a" {
					if mfInfoA == nil {
						mfInfoA = &MFAllocResponse{
							SessionID: allocatedCtx.ContextID + "-A",
						}
					}
					if isADC {
						mfInfoA.IP = mItem.LocalMbEndpoint.IP
						mfInfoA.AdcPort = mItem.LocalMbEndpoint.Port
						if mItem.LocalDcEndpoint != nil {
							mfInfoA.AdcDtlsFingerprint = mItem.LocalDcEndpoint.Fingerprint
							mfInfoA.AdcIceUfrag = mItem.LocalDcEndpoint.IceUfrag
							mfInfoA.AdcIcePwd = mItem.LocalDcEndpoint.IcePwd
							mfInfoA.AdcSetupRole = mItem.LocalDcEndpoint.SecuritySetup
						}
					}
				} else if term.TerminationID == "term-callee-b" {
					if mfInfoB == nil {
						mfInfoB = &MFAllocResponse{
							SessionID: allocatedCtx.ContextID + "-B",
						}
					}
					if isADC {
						mfInfoB.IP = mItem.LocalMbEndpoint.IP
						mfInfoB.AdcPort = mItem.LocalMbEndpoint.Port
						if mItem.LocalDcEndpoint != nil {
							mfInfoB.AdcDtlsFingerprint = mItem.LocalDcEndpoint.Fingerprint
							mfInfoB.AdcIceUfrag = mItem.LocalDcEndpoint.IceUfrag
							mfInfoB.AdcIcePwd = mItem.LocalDcEndpoint.IcePwd
							mfInfoB.AdcSetupRole = mItem.LocalDcEndpoint.SecuritySetup
						}
					}
				}
			}
		}
		log.Printf("[PORT_LOG] mfInfoA: %+v", mfInfoA)
		log.Printf("[PORT_LOG] mfInfoB: %+v", mfInfoB)

		var rewrittenSDP string
		if mSpec.AdcType == "P2A" {
			// Exclude ADC from B's offer
			rewrittenSDP = ExcludeADCFromSDP(msg.Body)
			log.Printf("[IMS-AS] P2A: Excluded ADC from SDP offer to callee")
		} else { // P2A2P
			var targetMfInfo *MFAllocResponse
			if isCallerInitiator {
				targetMfInfo = mfInfoB
			} else {
				targetMfInfo = mfInfoA
			}

			if targetMfInfo != nil {
				targetMfInfo.AdcSetupRole = "actpass"
				rewrittenSDP = RewriteSDP(msg.Body, targetMfInfo, session.ActiveAppID)
				log.Printf("[IMS-AS] P2A2P: Rewrote terminating ADC SDP Offer pointing to MF (AdcSetupRole=actpass). Result:\n%s", rewrittenSDP)
			} else {
				rewrittenSDP = msg.Body
				log.Printf("[IMS-AS] P2A2P: targetMfInfo is nil, skipping SDP rewrite")
			}
		}

		// Keep mfInfoA and mfInfoB in session for 200 OK handling
		session.MfInfoA = mfInfoA
		session.MfInfoB = mfInfoB

		if session.Callee == "198" {
			headers := map[string]string{
				"Content-Type": "application/sdp",
			}
			resp := b.BuildSIPResponse(msg, 200, "OK", headers, rewrittenSDP)
			b.sendUDP(resp, remoteAddr)
			log.Printf("[IMS-AS] Replied 200 OK for Hotline 198 re-INVITE directly")
			return
		}

		targetCallID := session.TermCallID
		if msg.ExtractUsername("From") != session.Caller {
			targetCallID = session.OrigCallID
		}
		headers := map[string]string{
			"Content-Type": "application/sdp",
			"Call-ID":      targetCallID,
		}
		forwardedInvite := b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, rewrittenSDP)
		b.forwardToSCSCF(forwardedInvite)
		log.Printf("[IMS-AS] Forwarded re-INVITE offer to S-CSCF")

	case <-time.After(5 * time.Second):
		log.Printf("[IMS-AS] [TS 24.186 Clause 9.4.4] Timeout waiting for media instruction for re-INVITE. Disabling data channel (port 0) and continuing voice/video procedure...")
		rewrittenSDP := ExcludeADCFromSDP(msg.Body)
		if session.Callee == "198" {
			headers := map[string]string{
				"Content-Type": "application/sdp",
			}
			resp := b.BuildSIPResponse(msg, 200, "OK", headers, rewrittenSDP)
			b.sendUDP(resp, remoteAddr)
			log.Printf("[IMS-AS] Replied 200 OK for Hotline 198 re-INVITE timeout directly")
			return
		}
		targetCallID := session.TermCallID
		if msg.ExtractUsername("From") != session.Caller {
			targetCallID = session.OrigCallID
		}
		headers := map[string]string{
			"Content-Type": "application/sdp",
			"Call-ID":      targetCallID,
		}
		forwardedInvite := b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, rewrittenSDP)
		b.forwardToSCSCF(forwardedInvite)
	}
}

func (b *B2BUA) processMediaInstruction(session *CallSession, instruction *sba.MediaInstructionData, remoteAddr *net.UDPAddr) {
	// Check for standard 3GPP audioVideoReNegotiationInd (TS 29.175 clause 5.3)
	for _, mi := range instruction.MediaInstructionSet {
		if mi.AudioVideoReNegotiationInd != nil && mi.AudioVideoReNegotiationInd.ReNegotiation {
			log.Printf("[IMS-AS] 3GPP AudioVideoReNegotiationInd received for session %s (side=%s). Triggering MMTel SDP renegotiation to anchor voice/video on the MF.",
				session.CallID, mi.AudioVideoReNegotiationInd.MediaConnSide)
		}
	}

	mfHost := session.MfHost
	if mfHost == "" {
		mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, session.CallID)
		session.MfHost = mfHost
	}
	mfURL := fmt.Sprintf("http://%s:%d", mfHost, b.cfg.Services.Mf.Port)

	// Build context creation payload
	isCore := true
	if session.OrigMsg != nil {
		if session.OrigMsg.GetHeader("x-real-ip") != "" {
			isCore = false
		} else if strings.Contains(session.OrigMsg.Body, "c=IN IP4 100.") || strings.Contains(session.OrigMsg.Raw, "100.") {
			// Fallback check
			isCore = false
		}
	}
	contextReq := sba.MediaContext{
		ContextID:     session.CallID,
		IsCoreNetwork: isCore,
	}
	var mSpec *sba.DcMediaSpecification
	isHotline := (session.Callee == "198")

	if isHotline {
		if session.IsInterworking {
			contextReq.Terminations = []sba.Termination{
				{
					TerminationID: "term-caller-a",
					Medias: []sba.MediaItem{
						{
							MediaID:           "audio-video",
							MediaResourceType: "RTP",
							SDP:               session.OrigMsg.Body,
						},
					},
				},
				{
					TerminationID: "term-callee-b",
					Medias: []sba.MediaItem{
						{
							MediaID:           "3",
							MediaResourceType: "DC",
							DcMedia: &sba.DcMedia{
								MediaProxyConfig: "HTTP_PROXY",
							},
						},
					},
				},
			}
		} else {
			contextReq.Terminations = []sba.Termination{
				{
					TerminationID: "term-caller-a",
					Medias: []sba.MediaItem{
						{
							MediaID:           "3",
							MediaResourceType: "DC",
							SDP:               session.OrigMsg.Body,
							DcMedia: &sba.DcMedia{
								MediaProxyConfig: "HTTP_PROXY",
							},
						},
					},
				},
				{
					TerminationID: "term-callee-b",
					Medias: []sba.MediaItem{
						{
							MediaID:           "3",
							MediaResourceType: "DC",
							DcMedia: &sba.DcMedia{
								MediaProxyConfig: "HTTP_PROXY",
							},
						},
					},
				},
			}
		}
	} else {
		for _, mi := range instruction.MediaInstructionSet {
			if mi.MediaResourceType == "DC" && mi.DcMediaSpecification != nil {
				mSpec = mi.DcMediaSpecification
				break
			}
		}

		var dcMedia *sba.DcMedia
		if mSpec != nil {
			dcMedia = &sba.DcMedia{
				MediaProxyConfig: mSpec.MediaProxyConfig,
				Streams:          mSpec.Streams,
				ReplaceHttpUrls:  mSpec.ReplaceHttpUrls,
			}
		}

		fromUser := session.OrigMsg.ExtractUsername("From")
		isCallerInitiator := (fromUser == session.Caller)

		var sdpA, sdpB string
		if isCallerInitiator {
			sdpA = session.OrigMsg.Body
		} else {
			sdpB = session.OrigMsg.Body
		}

		contextReq.Terminations = []sba.Termination{
			{
				TerminationID: "term-caller-a",
				Medias: []sba.MediaItem{
					{
						MediaID:           "3",
						MediaResourceType: "DC",
						SDP:               sdpA,
						DcMedia:           dcMedia,
					},
				},
			},
			{
				TerminationID: "term-callee-b",
				Medias: []sba.MediaItem{
					{
						MediaID:           "3",
						MediaResourceType: "DC",
						SDP:               sdpB,
						DcMedia:           dcMedia,
					},
				},
			},
		}
	}

	log.Printf("[IMS-AS] Allocating Nmf Media Context on MF for session %s", session.CallID)
	allocatedCtx, err := b.CallNmfCreateMediaContext(mfURL, contextReq)
	if err != nil {
		if session.IsInterworking {
			log.Printf("[3GPP AC.7.9.4][FALLBACK] Interworking context allocation failed: %v. Downgrading to standard Hotline call...", err)
			session.IsInterworking = false

			contextReq.Terminations = []sba.Termination{
				{
					TerminationID: "term-caller-a",
					Medias: []sba.MediaItem{
						{
							MediaID:           "3",
							MediaResourceType: "DC",
							SDP:               session.OrigMsg.Body,
							DcMedia: &sba.DcMedia{
								MediaProxyConfig: "HTTP_PROXY",
							},
						},
					},
				},
				{
					TerminationID: "term-callee-b",
					Medias: []sba.MediaItem{
						{
							MediaID:           "3",
							MediaResourceType: "DC",
							DcMedia: &sba.DcMedia{
								MediaProxyConfig: "HTTP_PROXY",
							},
						},
					},
				},
			}
			allocatedCtx, err = b.CallNmfCreateMediaContext(mfURL, contextReq)
		}

		if err != nil {
			log.Printf("[IMS-AS] [3GPP TS 24.186 Fallback] MF Context Create failed: %v. Continuing session establishment by disabling data channel (port=0)", err)
			fallbackSDP := ExcludeADCFromSDP(session.OrigMsg.Body)
			if isHotline {
				headers := map[string]string{
					"Content-Type": "application/sdp",
				}
				resp := b.BuildSIPResponse(session.OrigMsg, 200, "OK", headers, fallbackSDP)
				b.sendUDP(resp, remoteAddr)
				log.Printf("[IMS-AS] Fallback: Replied 200 OK with disabled data channel SDP")
			} else {
				headers := map[string]string{
					"Content-Type": "application/sdp",
					"Call-ID":      session.TermCallID,
					"Session-ID":   session.SessionID + ";remote=00000000000000000000000000000000",
				}
				forwardedInvite := b.BuildSIPRequest("INVITE", session.OrigMsg.RURI, session.OrigMsg, headers, fallbackSDP)
				b.forwardToSCSCF(forwardedInvite)
				log.Printf("[IMS-AS] Fallback: Forwarded INVITE (port=0, TermCallID=%s) to S-CSCF", session.TermCallID)
			}
			session.ResponseChan <- instruction
			return
		}
	}

	session.ContextID = allocatedCtx.ContextID

	// Map allocations to MfInfo structures
	var mfInfoA, mfInfoB *MFAllocResponse
	qosHint := ""
	if mSpec != nil {
		qosHint = mSpec.QosHint
	}

	for _, term := range allocatedCtx.Terminations {
		var mfInfo *MFAllocResponse
		for _, mItem := range term.Medias {
			if mfInfo == nil {
				mfInfo = &MFAllocResponse{
					SessionID: allocatedCtx.ContextID + "-" + term.TerminationID,
					IP:        mItem.LocalMbEndpoint.IP,
					Port:      mItem.LocalMbEndpoint.Port,
					BdcRole:   "sender",
					QosHint:   qosHint,
				}
				if term.TerminationID == "term-callee-b" {
					mfInfo.BdcRole = "receiver"
				}
			}

			if mItem.MediaID == "audio" {
				mfInfo.AudioPort = mItem.LocalMbEndpoint.Port
			} else if mItem.MediaID == "video" {
				mfInfo.VideoPort = mItem.LocalMbEndpoint.Port
			} else if mItem.MediaID == "1" || mItem.MediaID == "3" {
				mfInfo.Port = mItem.LocalMbEndpoint.Port
				if mItem.LocalDcEndpoint != nil {
					mfInfo.DtlsFingerprint = mItem.LocalDcEndpoint.Fingerprint
					mfInfo.SetupRole = mItem.LocalDcEndpoint.SecuritySetup
					mfInfo.IceUfrag = mItem.LocalDcEndpoint.IceUfrag
					mfInfo.IcePwd = mItem.LocalDcEndpoint.IcePwd
				}
			}
		}

		if term.TerminationID == "term-caller-a" {
			mfInfoA = mfInfo
		} else if term.TerminationID == "term-callee-b" {
			mfInfoB = mfInfo
		}
	}

	session.MfInfoA = mfInfoA
	session.MfInfoB = mfInfoB

	// Generate and update the return instruction data
	updatedInstruction := sba.MediaInstructionData{
		SessionID:           instruction.SessionID,
		MediaInstructionSet: make([]sba.MediaInstructions, len(instruction.MediaInstructionSet)),
	}
	for i, v := range instruction.MediaInstructionSet {
		mInst := v
		if mInst.DcMediaSpecification != nil {
			mInst.DcMediaSpecification = &sba.DcMediaSpecification{
				MediaProxyConfig: v.DcMediaSpecification.MediaProxyConfig,
				Streams:          v.DcMediaSpecification.Streams,
				ReplaceHttpUrls:  v.DcMediaSpecification.ReplaceHttpUrls,
			}
			if mfInfoA != nil {
				mInst.DcMediaSpecification.Mdc1EndpointDcsf = &sba.MdcEndpoint{
					IP:   mfInfoA.IP,
					Port: mfInfoA.Port,
				}
			}
		}
		updatedInstruction.MediaInstructionSet[i] = mInst
	}

	// Notify HTTP thread
	select {
	case session.ResponseChan <- &updatedInstruction:
	default:
	}

	if isHotline {
		// Symmetrically reply 200 OK carrying rewritten SDP
		headers := map[string]string{
			"Content-Type": "application/sdp",
		}
		rewrittenSDP := RewriteSDP(session.OrigMsg.Body, mfInfoA, session.ActiveAppID)
		if session.IsInterworking {
			rewrittenSDP = ExcludeADCFromSDP(rewrittenSDP)
			log.Printf("[IMS-AS] Originating MTSI Interworking (AC.7.9.4): stripped BDC/ADC from caller 200 OK SDP answer")
		}
		resp := b.BuildSIPResponse(session.OrigMsg, 200, "OK", headers, rewrittenSDP)
		b.sendUDP(resp, remoteAddr)
		log.Printf("[IMS-AS] Symmetrically replied 200 OK with rewritten SDP for Hotline 198")
	} else {
		// P2P: Forward INVITE to callee B with B2BUA Call-ID separation
		rewrittenSDP := RewriteSDP(session.OrigMsg.Body, mfInfoB, session.ActiveAppID)
		headers := map[string]string{
			"Content-Type": "application/sdp",
			"Call-ID":      session.TermCallID,
			"Session-ID":   session.SessionID + ";remote=00000000000000000000000000000000",
		}
		forwardedInvite := b.BuildSIPRequest("INVITE", session.OrigMsg.RURI, session.OrigMsg, headers, rewrittenSDP)
		b.forwardToSCSCF(forwardedInvite)
		log.Printf("[IMS-AS] Forwarded INVITE (SDP rewritten, TermCallID=%s) to S-CSCF", session.TermCallID)
	}
}
