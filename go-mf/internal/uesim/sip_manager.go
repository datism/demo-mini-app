package uesim

import (
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/imsas"
	"github.com/pion/webrtc/v3"
)

type UESimulator struct {
	cfg         *config.Config
	role        string // "caller" or "callee"
	username    string // "userA" or "userB"
	password    string // "passwordA" or "passwordB"
	target      string // "userB" or "198"
	sipConn     *net.UDPConn
	pcscfAddr   *net.UDPAddr
	localIP     string
	localPort   int
	callID      string
	bdcPC       *webrtc.PeerConnection
	adcPC       *webrtc.PeerConnection
	bdcChannel  *webrtc.DataChannel
	bdcChannels map[uint16]*webrtc.DataChannel
	adcChannel  *webrtc.DataChannel
	adcChannel2 *webrtc.DataChannel // ADC Stream 1001
	mu          sync.Mutex
	isCallOK    bool
	registered  bool
	regTimer    *time.Timer
	toTag       string
	fromTag     string
	cseq        int
	LegacyMTSI  bool
	callStarted bool
	Expires     int

	// Plain RTP UDP connections/addresses for client
	RtpIP        string
	RtpAudioPort int
	RtpVideoPort int
	AudioConn    *net.UDPConn
	VideoConn    *net.UDPConn

	lastSipInvite *imsas.SIPMessage

	// Test Callback Hooks
	OnSIPStateChange     func(state string)
	OnDataChannelOpen    func(streamID uint16)
	OnDataChannelMessage func(streamID uint16, subprotocol string, payload string)
	OnRtpActivity        func(mediaType string, direction string) // mediaType: "audio"/"video", direction: "sent"/"received"

	// RTP Packet Counters (atomic)
	RtpAudioSent     int64
	RtpAudioReceived int64
	RtpVideoSent     int64
	RtpVideoReceived int64

	// Port Configuration & Overrides
	LocalIPOverride        string
	LocalPortOverride      int
	LocalAudioPortOverride int
	LocalVideoPortOverride int
	localAudioPort         int
	localVideoPort         int
}

func NewUESimulator(cfg *config.Config, role, username, password, target string) *UESimulator {
	expiresVal := 7200
	if envExp := os.Getenv("UE_REGISTER_EXPIRES"); envExp != "" {
		if val, err := strconv.Atoi(envExp); err == nil && val > 0 {
			expiresVal = val
		}
	}

	return &UESimulator{
		cfg:        cfg,
		role:       role,
		username:   username,
		password:   password,
		target:     target,
		callID:     fmt.Sprintf("call-uuid-%s-%d", username, time.Now().Unix()),
		fromTag:    fmt.Sprintf("tag-caller-%s-%d", username, time.Now().Unix()),
		cseq:       1,
		LegacyMTSI: os.Getenv("UE_LEGACY_MTSI") == "true",
		Expires:    expiresVal,
	}
}

// Mu returns a reference to the internal mutex for safe external access to RTP fields.
func (ue *UESimulator) Mu() *sync.Mutex {
	return &ue.mu
}

func (ue *UESimulator) Start() error {
	pcscfHost := ue.cfg.ImsCore.Pcscf.Host
	pcscfPort := ue.cfg.ImsCore.Pcscf.Port
	pAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("%s:%d", pcscfHost, pcscfPort))
	if err != nil {
		return err
	}
	ue.pcscfAddr = pAddr

	// Use LocalIPOverride if provided, otherwise config LocalMediaIP, otherwise dynamic discovery
	localIP := ue.LocalIPOverride
	if localIP == "" {
		localIP = ue.cfg.LocalMediaIP
	}
	if localIP == "" {
		localIP, err = getContainerLocalIP()
		if err != nil {
			localIP = "127.0.0.1"
		}
	}
	ue.localIP = localIP

	// Use config LocalSipPort if defined, default to 5060
	sipPort := ue.LocalPortOverride
	if sipPort == 0 {
		sipPort = ue.cfg.LocalSipPort
	}
	if sipPort == 0 {
		sipPort = 5060
	}
	ue.localPort = sipPort

	lAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("%s:%d", ue.localIP, sipPort))
	if err != nil {
		return err
	}
	conn, err := net.ListenUDP("udp", lAddr)
	if err != nil {
		return err
	}
	ue.sipConn = conn

	// Bind plain RTP sockets
	audioPort := 50002
	if ue.LocalAudioPortOverride != 0 {
		audioPort = ue.LocalAudioPortOverride
	}
	bindIP := net.IPv4zero
	audioConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: bindIP, Port: audioPort})
	if err != nil {
		conn.Close()
		return fmt.Errorf("listen audio udp on %s:%d: %w", ue.localIP, audioPort, err)
	}
	ue.AudioConn = audioConn
	ue.localAudioPort = audioConn.LocalAddr().(*net.UDPAddr).Port

	videoPort := 50004
	if ue.LocalVideoPortOverride != 0 {
		videoPort = ue.LocalVideoPortOverride
	}
	videoConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: bindIP, Port: videoPort})
	if err != nil {
		conn.Close()
		audioConn.Close()
		return fmt.Errorf("listen video udp on %s:%d: %w", ue.localIP, videoPort, err)
	}
	ue.VideoConn = videoConn
	ue.localVideoPort = videoConn.LocalAddr().(*net.UDPAddr).Port

	log.Printf("[UE-%s] SIP socket listening on %s:%d, RTP on ports %d/%d", ue.username, ue.localIP, sipPort, ue.localAudioPort, ue.localVideoPort)

	go ue.listenSIPLoop()
	go ue.listenPlainRTP(ue.AudioConn, "audio")
	go ue.listenPlainRTP(ue.VideoConn, "video")
	go ue.runLifecycle()

	return nil
}

func (ue *UESimulator) runLifecycle() {
	for {
		ue.mu.Lock()
		reg := ue.registered
		ue.mu.Unlock()
		if reg {
			break
		}
		log.Printf("[UE-%s] Sending REGISTER challenge request...", ue.username)
		ue.sendRegister(nil)
		time.Sleep(3 * time.Second)
	}

	for {
		time.Sleep(30 * time.Second)
	}
}

func (ue *UESimulator) listenSIPLoop() {
	buf := make([]byte, 65535)
	for {
		n, _, err := ue.sipConn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("[UE-%s] SIP socket error: %v", ue.username, err)
			return
		}
		data := string(buf[:n])
		ue.handleIncomingSIP(data)
	}
}

func (ue *UESimulator) handleIncomingSIP(data string) {
	msg, err := imsas.ParseSIPMessage(data)
	if err != nil {
		return
	}

	cseq := msg.GetHeader("CSeq")
	msgCallID := msg.GetHeader("Call-ID")
	isRegister := strings.Contains(cseq, "REGISTER") || msg.Method == "REGISTER"

	if !isRegister && msgCallID != "" {
		ue.mu.Lock()
		activeCallID := ue.callID
		ue.mu.Unlock()

		if msg.Method != "INVITE" && msgCallID != activeCallID {
			log.Printf("[UE-%s] Ignoring stale/mismatched SIP message (Method: %s, Status: %d) with Call-ID: %s (Active Call-ID: %s)",
				ue.username, msg.Method, msg.StatusCode, msgCallID, activeCallID)
			return
		}
	}

	if msg.IsResponse {
		switch msg.StatusCode {
		case 401:
			authHeader := msg.GetHeader("WWW-Authenticate")
			if authHeader != "" {
				params := parseAuthHeader(authHeader)
				ue.sendRegister(params)
			}
		case 200:
			cseq := msg.GetHeader("CSeq")
			if strings.Contains(cseq, "REGISTER") {
				expires := 3600
				if expStr := msg.GetHeader("Expires"); expStr != "" {
					if val, err := strconv.Atoi(expStr); err == nil && val > 0 {
						expires = val
					}
				}
				if contactStr := msg.GetHeader("Contact"); contactStr != "" {
					if idx := strings.Index(contactStr, "expires="); idx != -1 {
						paramStr := contactStr[idx+8:]
						if endIdx := strings.IndexAny(paramStr, ";, \r\n"); endIdx != -1 {
							paramStr = paramStr[:endIdx]
						}
						if val, err := strconv.Atoi(strings.Trim(paramStr, `"`)); err == nil && val > 0 {
							expires = val
						}
					}
				}

				refreshSec := int(float64(expires) * 0.8)
				if refreshSec < 5 {
					refreshSec = 5
				}

				ue.mu.Lock()
				if ue.regTimer != nil {
					ue.regTimer.Stop()
				}
				ue.regTimer = time.AfterFunc(time.Duration(refreshSec)*time.Second, func() {
					log.Printf("[UE-%s] re-REGISTER timer fired (%ds since last reg), refreshing registration...", ue.username, refreshSec)
					ue.sendRegister(nil)
				})

				if !ue.registered {
					ue.registered = true
					log.Printf("[UE-%s] ✅ REGISTER successful (200 OK), scheduled re-REGISTER in %ds", ue.username, refreshSec)
					if ue.OnSIPStateChange != nil {
						ue.OnSIPStateChange("registered")
					}
					if ue.role == "caller" {
						go ue.startCallerCall()
					}
				} else {
					log.Printf("[UE-%s] ✅ re-REGISTER successful (200 OK), next refresh in %ds", ue.username, refreshSec)
				}
				ue.mu.Unlock()
			} else if strings.Contains(cseq, "INVITE") {
				log.Printf("[UE-%s] ✅ Call Answer received (200 OK for INVITE)", ue.username)
					ue.handleInviteAnswer(msg)
					if ue.OnSIPStateChange != nil {
						ue.OnSIPStateChange("connected")
					}
				}
		default:
			if (msg.StatusCode == 180 || msg.StatusCode == 183) && len(msg.Body) > 0 {
				log.Printf("[UE-%s] ℹ️ Received provisional response %d with SDP. Starting early WebRTC handshake...", ue.username, msg.StatusCode)
				ue.handleInviteAnswer(msg)
			} else if msg.StatusCode >= 300 {
				log.Printf("[UE-%s] ❌ Call failed/rejected: StatusCode=%d, Reason=%s", ue.username, msg.StatusCode, msg.ReasonPhrase)
				ue.releaseSession()
			}
		}
	} else {
		switch msg.Method {
		case "INVITE":
			ue.handleIncomingInvite(msg)
		case "BYE":
			log.Printf("[UE-%s] Received BYE from network. Call ended.", ue.username)
			ue.sendResponse(msg, 200, "OK")
			ue.releaseSession()
		case "CANCEL":
			ue.handleIncomingCancel(msg)
		}
	}
}

func (ue *UESimulator) sendRegister(challengeParams map[string]string) {
	var authHeader string
	if challengeParams != nil {
		nonce := challengeParams["nonce"]
		realm := challengeParams["realm"]
		uri := fmt.Sprintf("sip:%s:5060", ue.cfg.ImsCore.Pcscf.Host)
		response := calcDigestResponse(ue.username, ue.password, realm, "REGISTER", uri, nonce)
		authHeader = fmt.Sprintf(`Digest username="%s", realm="%s", nonce="%s", uri="%s", response="%s", algorithm=MD5`,
			ue.username, realm, nonce, uri, response)
	}

	regTag := fmt.Sprintf("tag-reg-%s-%d", ue.username, time.Now().UnixNano())
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("REGISTER sip:%s SIP/2.0\r\n", ue.cfg.Domains.Ims))
	sb.WriteString(fmt.Sprintf("Via: SIP/2.0/UDP %s:%d;branch=z9hG4bK-reg-%d\r\n", ue.localIP, ue.localPort, time.Now().UnixNano()))
	sb.WriteString("Max-Forwards: 70\r\n")
	sb.WriteString(fmt.Sprintf("From: <sip:%s@%s>;tag=%s\r\n", ue.username, ue.cfg.Domains.Ims, regTag))
	sb.WriteString(fmt.Sprintf("To: <sip:%s@%s>\r\n", ue.username, ue.cfg.Domains.Ims))
	sb.WriteString(fmt.Sprintf("Call-ID: reg-call-%s\r\n", ue.username))
	sb.WriteString("CSeq: 1 REGISTER\r\n")
	sb.WriteString(fmt.Sprintf("Expires: %d\r\n", ue.Expires))
	if ue.LegacyMTSI {
		sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>\r\n", ue.username, ue.localIP, ue.localPort))
	} else {
		sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>;+sip.app-subtype=\"webrtc-datachannel\"\r\n", ue.username, ue.localIP, ue.localPort))
	}
	if authHeader != "" {
		sb.WriteString(fmt.Sprintf("Authorization: %s\r\n", authHeader))
	}
	sb.WriteString("Content-Length: 0\r\n\r\n")

	ue.sendUDP(sb.String())
}

func (ue *UESimulator) sendBye() {
	log.Printf("[UE-%s] Sending SIP BYE request to terminate call", ue.username)
	ue.releaseSession()

	ue.mu.Lock()
	fromTag := ue.fromTag
	toTag := ue.toTag
	ue.mu.Unlock()

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("BYE sip:%s@%s SIP/2.0\r\n", ue.target, ue.cfg.Domains.Ims))
	sb.WriteString(fmt.Sprintf("Via: SIP/2.0/UDP %s:%d;branch=z9hG4bK-bye-%d\r\n", ue.localIP, ue.localPort, time.Now().UnixNano()))
	sb.WriteString("Max-Forwards: 70\r\n")
	sb.WriteString(fmt.Sprintf("From: <sip:%s@%s>;tag=%s\r\n", ue.username, ue.cfg.Domains.Ims, fromTag))
	
	toHeader := fmt.Sprintf("<sip:%s@%s>", ue.target, ue.cfg.Domains.Ims)
	if toTag != "" {
		toHeader += ";tag=" + toTag
	}
	sb.WriteString(fmt.Sprintf("To: %s\r\n", toHeader))
	sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", ue.callID))
	sb.WriteString("CSeq: 2 BYE\r\n")
	sb.WriteString("Content-Length: 0\r\n\r\n")

	ue.sendUDP(sb.String())
}

func (ue *UESimulator) sendCancel() {
	log.Printf("[UE-%s] Sending SIP CANCEL request to terminate call setup", ue.username)
	ue.releaseSession()

	ue.mu.Lock()
	fromTag := ue.fromTag
	ue.mu.Unlock()

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("CANCEL sip:%s@%s SIP/2.0\r\n", ue.target, ue.cfg.Domains.Ims))
	sb.WriteString(fmt.Sprintf("Via: SIP/2.0/UDP %s:%d;branch=z9hG4bK-inv-%d\r\n", ue.localIP, ue.localPort, time.Now().UnixNano()))
	sb.WriteString("Max-Forwards: 70\r\n")
	sb.WriteString(fmt.Sprintf("From: <sip:%s@%s>;tag=%s\r\n", ue.username, ue.cfg.Domains.Ims, fromTag))
	sb.WriteString(fmt.Sprintf("To: <sip:%s@%s>\r\n", ue.target, ue.cfg.Domains.Ims))
	sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", ue.callID))
	sb.WriteString("CSeq: 1 CANCEL\r\n")
	sb.WriteString("Content-Length: 0\r\n\r\n")

	ue.sendUDP(sb.String())
}

func (ue *UESimulator) rejectIncomingCall() {
	ue.mu.Lock()
	msg := ue.lastSipInvite
	ue.lastSipInvite = nil
	ue.mu.Unlock()

	if msg == nil {
		log.Printf("[UE-%s] No incoming call to reject", ue.username)
		return
	}

	log.Printf("[UE-%s] Rejecting incoming call (486 Busy Here)...", ue.username)
	
	var sb strings.Builder
	sb.WriteString("SIP/2.0 486 Busy Here\r\n")
	if via := msg.GetHeader("Via"); via != "" {
		sb.WriteString(fmt.Sprintf("Via: %s\r\n", via))
	}
	if from := msg.GetHeader("From"); from != "" {
		sb.WriteString(fmt.Sprintf("From: %s\r\n", from))
	}
	if to := msg.GetHeader("To"); to != "" {
		sb.WriteString(fmt.Sprintf("To: %s\r\n", to))
	}
	sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", msg.GetHeader("Call-ID")))
	if cseq := msg.GetHeader("CSeq"); cseq != "" {
		sb.WriteString(fmt.Sprintf("CSeq: %s\r\n", cseq))
	}
	sb.WriteString("Content-Length: 0\r\n\r\n")

	ue.sendUDP(sb.String())
	ue.releaseSession()
}

func (ue *UESimulator) rejectIncomingCallWithMsg(msg *imsas.SIPMessage) {
	if msg == nil {
		return
	}

	log.Printf("[UE-%s] Rejecting busy incoming call (486 Busy Here) for Call-ID: %s", ue.username, msg.GetHeader("Call-ID"))
	
	var sb strings.Builder
	sb.WriteString("SIP/2.0 486 Busy Here\r\n")
	if via := msg.GetHeader("Via"); via != "" {
		sb.WriteString(fmt.Sprintf("Via: %s\r\n", via))
	}
	if from := msg.GetHeader("From"); from != "" {
		sb.WriteString(fmt.Sprintf("From: %s\r\n", from))
	}
	if to := msg.GetHeader("To"); to != "" {
		sb.WriteString(fmt.Sprintf("To: %s\r\n", to))
	}
	sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", msg.GetHeader("Call-ID")))
	if cseq := msg.GetHeader("CSeq"); cseq != "" {
		sb.WriteString(fmt.Sprintf("CSeq: %s\r\n", cseq))
	}
	sb.WriteString("Content-Length: 0\r\n\r\n")

	ue.sendUDP(sb.String())
}

func (ue *UESimulator) releaseSession() {
	ue.mu.Lock()
	ue.isCallOK = false
	ue.RtpIP = ""
	ue.RtpAudioPort = 0
	ue.RtpVideoPort = 0
	if ue.bdcPC != nil {
		ue.bdcPC.Close()
		ue.bdcPC = nil
	}
	if ue.adcPC != nil {
		ue.adcPC.Close()
		ue.adcPC = nil
	}
	ue.lastSipInvite = nil
	ue.mu.Unlock()

	if ue.OnSIPStateChange != nil {
		ue.OnSIPStateChange("disconnected")
	}
	log.Printf("[UE Log] Session torn down cleanly. All network resources released.")
}

func (ue *UESimulator) sendResponse(orig *imsas.SIPMessage, code int, reason string) {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("SIP/2.0 %d %s\r\n", code, reason))
	if via := orig.GetHeader("Via"); via != "" {
		sb.WriteString(fmt.Sprintf("Via: %s\r\n", via))
	}
	if from := orig.GetHeader("From"); from != "" {
		sb.WriteString(fmt.Sprintf("From: %s\r\n", from))
	}
	if to := orig.GetHeader("To"); to != "" {
		sb.WriteString(fmt.Sprintf("To: %s\r\n", to))
	}
	sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", orig.GetHeader("Call-ID")))
	if cseq := orig.GetHeader("CSeq"); cseq != "" {
		sb.WriteString(fmt.Sprintf("CSeq: %s\r\n", cseq))
	}
	sb.WriteString("Content-Length: 0\r\n\r\n")

	ue.sendUDP(sb.String())
}

func (ue *UESimulator) sendUDP(data string) {
	ue.mu.Lock()
	conn := ue.sipConn
	ue.mu.Unlock()
	if conn == nil {
		log.Printf("[UE-%s] SIP connection is closed, dropping packet", ue.username)
		return
	}
	_, err := conn.WriteTo([]byte(data), ue.pcscfAddr)
	if err != nil {
		log.Printf("[UE-%s] UDP send error: %v", ue.username, err)
	}
}

func getContainerLocalIP() (string, error) {
	prefIface := os.Getenv("INTERFACE_NAME")
	ifaces, err := net.Interfaces()
	if err != nil {
		return "", err
	}

	if prefIface != "" {
		for _, iface := range ifaces {
			if iface.Name == prefIface {
				if iface.Flags&net.FlagUp == 0 {
					continue
				}
				addrs, _ := iface.Addrs()
				for _, addr := range addrs {
					var ip net.IP
					switch v := addr.(type) {
					case *net.IPNet:
						ip = v.IP
					case *net.IPAddr:
						ip = v.IP
					}
					if ip != nil && !ip.IsLoopback() && ip.To4() != nil {
						return ip.String(), nil
					}
				}
			}
		}
	}

	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip != nil && !ip.IsLoopback() && ip.To4() != nil {
				return ip.String(), nil
			}
		}
	}
	return "", fmt.Errorf("no address")
}

func parseAuthHeader(header string) map[string]string {
	res := make(map[string]string)
	if !strings.HasPrefix(strings.ToLower(header), "digest ") {
		return res
	}
	parts := strings.Split(header[7:], ",")
	for _, p := range parts {
		kv := strings.SplitN(strings.TrimSpace(p), "=", 2)
		if len(kv) == 2 {
			k := strings.ToLower(kv[0])
			v := strings.Trim(kv[1], `"`+" ")
			res[k] = v
		}
	}
	return res
}

func calcDigestResponse(username, password, realm, method, uri, nonce string) string {
	a1 := md5Hex(username + ":" + realm + ":" + password)
	a2 := md5Hex(method + ":" + uri)
	return md5Hex(a1 + ":" + nonce + ":" + a2)
}

func md5Hex(data string) string {
	h := md5.New()
	h.Write([]byte(data))
	return hex.EncodeToString(h.Sum(nil))
}

func (ue *UESimulator) sendReInvite(appId string) {
	ue.mu.Lock()
	if !ue.isCallOK {
		ue.mu.Unlock()
		log.Printf("[UE-%s] Cannot send Re-INVITE: call is not active yet", ue.username)
		return
	}
	ue.cseq++
	cseqVal := ue.cseq
	callID := ue.callID
	toTag := ue.toTag
	fromTag := ue.fromTag
	bdcPC := ue.bdcPC
	
	if ue.adcPC != nil {
		ue.adcPC.Close()
	}
	adcPC, err := createPeerConnectionWithKeyLog("/app/config/adc_ssl_keys.log", ue.localIP)
	if err != nil {
		ue.mu.Unlock()
		log.Printf("[UE-%s] Failed to create ADC PeerConnection: %v", ue.username, err)
		return
	}
	ue.adcPC = adcPC
	adcPC.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		log.Printf("[UE-%s] AdcPC connection state changed to: %s", ue.username, state.String())
	})

	adcID := uint16(1000)
	adcNegotiated := true
	protoWhiteboard := "whiteboard"
	adcChannel, err2 := adcPC.CreateDataChannel("app-dc", &webrtc.DataChannelInit{
		ID:         &adcID,
		Negotiated: &adcNegotiated,
		Protocol:   &protoWhiteboard,
	})
	if err2 == nil {
		ue.adcChannel = adcChannel
	}
	adcID2 := uint16(1001)
	protoEmoji := "emoji"
	adcChannel2, err3 := adcPC.CreateDataChannel("app-dc-2", &webrtc.DataChannelInit{
		ID:         &adcID2,
		Negotiated: &adcNegotiated,
		Protocol:   &protoEmoji,
	})
	if err3 == nil {
		ue.adcChannel2 = adcChannel2
	}
	ue.mu.Unlock()
	ue.setupDataChannelHandlers()

	if bdcPC == nil {
		log.Printf("[UE-%s] Cannot send Re-INVITE: BDC PeerConnection is nil", ue.username)
		return
	}

	offer, err := adcPC.CreateOffer(nil)
	if err != nil {
		log.Printf("[UESimulator] Failed to create ADC Re-INVITE offer: %v", err)
		return
	}
	gatherComplete := webrtc.GatheringCompletePromise(adcPC)
	if err := adcPC.SetLocalDescription(offer); err != nil {
		log.Printf("[UE-%s] ADC SetLocalDescription offer error: %v", ue.username, err)
	}
	select {
	case <-gatherComplete:
	case <-time.After(1500 * time.Millisecond):
		log.Printf("[UE-%s] ADC Offer ICE gathering timed out", ue.username)
	}

	localSDP := MergeAdcOffer(bdcPC.LocalDescription().SDP, adcPC.LocalDescription().SDP, false)
	localSDP = reorderSDPMedia(localSDP, ue.localIP, ue.localAudioPort, ue.localVideoPort)
	if !isHomeSlotApp(appId) && countAppSections(localSDP) >= 3 {
		localSDP = setAppSlotReqApp(localSDP, appId, true)
	} else {
		localSDP = appendReqAppToSDP(localSDP, appId)
	}
	log.Printf("[UE-%s] Generated Re-INVITE SDP offer to send:\n%s", ue.username, localSDP)

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("INVITE sip:%s@%s SIP/2.0\r\n", ue.target, ue.cfg.Domains.Ims))
	sb.WriteString(fmt.Sprintf("Via: SIP/2.0/UDP %s:%d;branch=z9hG4bK-reinv-%d\r\n", ue.localIP, ue.localPort, time.Now().UnixNano()))
	sb.WriteString("Max-Forwards: 70\r\n")
	sb.WriteString(fmt.Sprintf("From: <sip:%s@%s>;tag=%s\r\n", ue.username, ue.cfg.Domains.Ims, fromTag))
	
	toHeader := fmt.Sprintf("<sip:%s@%s>", ue.target, ue.cfg.Domains.Ims)
	if toTag != "" {
		toHeader += ";tag=" + toTag
	}
	sb.WriteString(fmt.Sprintf("To: %s\r\n", toHeader))
	sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", callID))
	sb.WriteString(fmt.Sprintf("CSeq: %d INVITE\r\n", cseqVal))
	if ue.LegacyMTSI {
		sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>\r\n", ue.username, ue.localIP, ue.localPort))
	} else {
		sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>;+sip.app-subtype=\"webrtc-datachannel\"\r\n", ue.username, ue.localIP, ue.localPort))
	}
	sb.WriteString("Content-Type: application/sdp\r\n")
	sb.WriteString(fmt.Sprintf("Content-Length: %d\r\n\r\n", len(localSDP)))
	sb.WriteString(localSDP)

	ue.sendUDP(sb.String())
	log.Printf("[UE-%s] Sent SIP Re-INVITE (ADC Stream 1001) with a=3gpp-req-app", ue.username)
}

func (ue *UESimulator) sendReInviteClose(appId string) {
	ue.mu.Lock()
	if !ue.isCallOK {
		ue.mu.Unlock()
		log.Printf("[UE-%s] Cannot send Re-INVITE to close: call is not active yet", ue.username)
		return
	}
	ue.cseq++
	cseqVal := ue.cseq
	callID := ue.callID
	toTag := ue.toTag
	fromTag := ue.fromTag
	bdcPC := ue.bdcPC
	
	var mergedSDP string
	if ue.adcPC != nil && ue.adcPC.LocalDescription() != nil {
		mergedSDP = MergeAdcOffer(bdcPC.LocalDescription().SDP, ue.adcPC.LocalDescription().SDP, false)
	} else {
		// Reconstruct dummy ADC block if nil or not set
		var dsb strings.Builder
		dsb.WriteString(bdcPC.LocalDescription().SDP)
		dsb.WriteString("m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\n")
		dsb.WriteString(fmt.Sprintf("c=IN IP4 %s\r\n", ue.localIP))
		dsb.WriteString("a=mid:3\r\n")
		mergedSDP = dsb.String()
	}

	if ue.adcPC != nil {
		ue.adcPC.Close()
		ue.adcPC = nil
	}
	ue.mu.Unlock()

	if bdcPC == nil {
		log.Printf("[UE-%s] Cannot send Re-INVITE to close: BDC PeerConnection is nil", ue.username)
		return
	}

	var localSDP string
	if !isHomeSlotApp(appId) && countAppSections(mergedSDP) >= 3 {
		localSDP = setAppSlotReqApp(mergedSDP, appId, false)
	} else {
		localSDP = appendReqAppToSDPClose(mergedSDP, appId)
	}

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("INVITE sip:%s@%s SIP/2.0\r\n", ue.target, ue.cfg.Domains.Ims))
	sb.WriteString(fmt.Sprintf("Via: SIP/2.0/UDP %s:%d;branch=z9hG4bK-reinvclose-%d\r\n", ue.localIP, ue.localPort, time.Now().UnixNano()))
	sb.WriteString("Max-Forwards: 70\r\n")
	sb.WriteString(fmt.Sprintf("From: <sip:%s@%s>;tag=%s\r\n", ue.username, ue.cfg.Domains.Ims, fromTag))
	
	toHeader := fmt.Sprintf("<sip:%s@%s>", ue.target, ue.cfg.Domains.Ims)
	if toTag != "" {
		toHeader += ";tag=" + toTag
	}
	sb.WriteString(fmt.Sprintf("To: %s\r\n", toHeader))
	sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", callID))
	sb.WriteString(fmt.Sprintf("CSeq: %d INVITE\r\n", cseqVal))
	if ue.LegacyMTSI {
		sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>\r\n", ue.username, ue.localIP, ue.localPort))
	} else {
		sb.WriteString(fmt.Sprintf("Contact: <sip:%s@%s:%d>;+sip.app-subtype=\"webrtc-datachannel\"\r\n", ue.username, ue.localIP, ue.localPort))
	}
	sb.WriteString("Content-Type: application/sdp\r\n")
	sb.WriteString(fmt.Sprintf("Content-Length: %d\r\n\r\n", len(localSDP)))
	sb.WriteString(localSDP)

	ue.sendUDP(sb.String())
	log.Printf("[UE-%s] Sent SIP Re-INVITE to close ADC: %s", ue.username, appId)
}

func (ue *UESimulator) handleIncomingCancel(msg *imsas.SIPMessage) {
	log.Printf("[UE-%s] Received SIP CANCEL request from peer", ue.username)

	// 1. Send 200 OK for CANCEL
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
	sb.WriteString("Content-Length: 0\r\n\r\n")
	ue.sendUDP(sb.String())
	log.Printf("[UE-%s] SIP 200 OK sent for CANCEL", ue.username)

	// 2. Send 487 Request Terminated for the original INVITE
	ue.mu.Lock()
	invite := ue.lastSipInvite
	ue.mu.Unlock()
	if invite != nil {
		var sb2 strings.Builder
		sb2.WriteString("SIP/2.0 487 Request Terminated\r\n")
		if via := invite.GetHeader("Via"); via != "" {
			sb2.WriteString(fmt.Sprintf("Via: %s\r\n", via))
		}
		if from := invite.GetHeader("From"); from != "" {
			sb2.WriteString(fmt.Sprintf("From: %s\r\n", from))
		}
		if to := invite.GetHeader("To"); to != "" {
			sb2.WriteString(fmt.Sprintf("To: %s\r\n", to))
		}
		sb2.WriteString(fmt.Sprintf("Call-ID: %s\r\n", ue.callID))
		if cseq := invite.GetHeader("CSeq"); cseq != "" {
			sb2.WriteString(fmt.Sprintf("CSeq: %s\r\n", cseq))
		}
		sb2.WriteString("Content-Length: 0\r\n\r\n")
		ue.sendUDP(sb2.String())
		log.Printf("[UE-%s] SIP 487 Request Terminated sent for INVITE", ue.username)
	}

	// 3. Release session
	ue.releaseSession()
}

func (ue *UESimulator) HoldCall() {
	log.Printf("[UE-%s] Initiating Call Hold: sending re-INVITE SDP Offer with a=inactive under m=application", ue.username)
	// Mock re-INVITE sequence with a=inactive direction attribute
}

func (ue *UESimulator) ResumeCall() {
	log.Printf("[UE-%s] Initiating Call Resume: sending re-INVITE SDP Offer with a=sendrecv under m=application", ue.username)
	// Mock re-INVITE sequence with a=sendrecv direction attribute
}

// Stop closes all network resources and WebRTC connections.
func (ue *UESimulator) Stop() {
	ue.mu.Lock()
	defer ue.mu.Unlock()
	if ue.regTimer != nil {
		ue.regTimer.Stop()
		ue.regTimer = nil
	}
	if ue.sipConn != nil {
		_ = ue.sipConn.Close()
		ue.sipConn = nil
	}
	if ue.AudioConn != nil {
		_ = ue.AudioConn.Close()
		ue.AudioConn = nil
	}
	if ue.VideoConn != nil {
		_ = ue.VideoConn.Close()
		ue.VideoConn = nil
	}
	if ue.bdcPC != nil {
		_ = ue.bdcPC.Close()
		ue.bdcPC = nil
	}
	if ue.adcPC != nil {
		_ = ue.adcPC.Close()
		ue.adcPC = nil
	}
}
