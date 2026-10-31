// Package mf implements the 3GPP Rel-18/19 Media Function (MF) node.
// It serves as the User Plane media anchor and provides the TS 29.176 DC2 Nmf_MRM
// control interface to the IMS Application Server (IMS-AS).
//
// Key Responsibilities:
// 1. DTLS/SCTP Termination: Implements SCTP user-plane engine with RFC 4960 Castagnoli CRC32c checksums.
// 2. BDC & ADC WebRTC Proxying: Supports HTTP Proxy (BDC Stream 0/10) and TCP/UDP Proxy (ADC Stream >= 1000).
// 3. Plain RTP Relaying: Relays unencrypted PCMA Audio (8000Hz) and H.264 Video without transcoding overhead.
// 4. Early Media Playback: Broadcasts announcement audio files (unreachable.pcma, agent.pcma) directly to UEs.
// 5. Immutability & Security Guardrails: Enforces immutability of WebRTC endpoints once handshake completes.
package mf

import (
	"bytes"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"hash/fnv"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/http2"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/imsas"
	"github.com/demo/ims-dc-mf/internal/sba"
	"github.com/google/uuid"
	"github.com/pion/ice/v2"
	"github.com/pion/sdp/v3"
	"github.com/pion/webrtc/v3"
)

// SessionInfo lưu trữ thông tin của một WebRTC session (phân tách độc lập BDC và ADC)
type SessionInfo struct {
	ID     string
	CallID string
	UserID string
	BdcPC  *webrtc.PeerConnection
	AdcPC  *webrtc.PeerConnection

	// DataChannels
	BDC  *webrtc.DataChannel // Bootstrap DC - Stream ID 10
	ADC  *webrtc.DataChannel // Application DC - Stream ID 1000
	ADC2 *webrtc.DataChannel // Application DC - Stream ID 1001

	// TCP connections to DCAS (MDC2 interface via plain TCP per TS 29.176) mapped by stream ID
	tcpConns map[uint16]net.Conn

	// Proxy mode determines ADC routing behavior (DEF-08)
	// "HTTP_PROXY" = BDC mode, "UDP_PROXY" = relay to DCAS only, "" = P2P relay only
	ProxyMode    string
	Mdc2Protocol string // "TCP", "UDP_DTLS_SCTP", etc.
	DcasIP       string
	DcasPort     int

	// ICE/DTLS params BDC
	ICEParams      webrtc.ICEParameters
	DTLSParams     webrtc.DTLSParameters
	Fingerprint    webrtc.DTLSFingerprint
	BdcICEParams   webrtc.ICEParameters
	BdcFingerprint webrtc.DTLSFingerprint
	LocalPort      int // BDC port (shared UDP MUX)
	BdcLocalPort   int // BDC port (shared UDP MUX)

	// ICE/DTLS params ADC
	AdcICEParams   webrtc.ICEParameters
	AdcDTLSParams  string
	AdcFingerprint webrtc.DTLSFingerprint
	AdcLocalPort   int // ADC port (separate UDP socket)
	AdcBlock       int // index of the m=application block carrying this session's ADC; 0 means 1 (Home)

	LocalIP string

	// LinkedSession: session của đầu kia trong cuộc gọi
	LinkedSession *SessionInfo

	// Hotline fields
	IsHotline       bool
	ReplaceHttpUrls map[string]sba.ReplaceUrl
	AudioTrack      *webrtc.TrackLocalStaticRTP
	VideoTrack      *webrtc.TrackLocalStaticRTP
	audioCancel     chan struct{}
	audioMu         sync.Mutex
	IsInterworking  bool

	// Plain RTP UDP connections/addresses for client
	RtpIP          string
	RtpAudioPort   int
	RtpVideoPort   int
	LocalAudioPort int
	LocalVideoPort int
	AudioConn      *net.UDPConn
	VideoConn      *net.UDPConn

	CreatedAt time.Time
	mu        sync.Mutex
}

// AllocateResponse là JSON response trả về sau khi cấp phát session
type AllocateResponse struct {
	SessionID   string `json:"session_id"`
	ICEUfrag    string `json:"ice_ufrag"`
	ICEPwd      string `json:"ice_pwd"`
	Fingerprint string `json:"dtls_fingerprint"` // format: "sha-256 AA:BB:..."
	IP          string `json:"ip"`
	Port        int    `json:"port"`
	AudioPort   int    `json:"audio_port,omitempty"`
	VideoPort   int    `json:"video_port,omitempty"`
	SetupRole   string `json:"setup_role"`
	SDPAnswer   string `json:"sdp_answer,omitempty"` // For hotline calls
	SDPOffer    string `json:"sdp_offer,omitempty"`  // For peer calls
}

type ContextInfo struct {
	ContextID string
	SessionA  string
	SessionB  string
}

// MediaFunction quản lý tất cả PeerConnection
type MediaFunction struct {
	sessions   map[string]*SessionInfo // key: session_id
	contexts   map[string]*ContextInfo // key: context_id
	mu         sync.RWMutex
	udpMux     ice.UDPMux
	udpPort    int
	publicPort int        // Port quảng bá ra bên ngoài (NodePort), dùng khi điện thoại thật kết nối qua NAT/K8s
	adcUdpMux  ice.UDPMux // Dedicated UDP Mux for ADC PeerConnections (avoids DTLS state collision on reconnect)
	adcUdpPort int
	cfg        *config.Config
	httpClient *http.Client

	// Global plain RTP sockets
	audioConn       *net.UDPConn
	videoConn       *net.UDPConn
	audioCalleeConn *net.UDPConn
	videoCalleeConn *net.UDPConn

	latchedAudioSessions map[string]*SessionInfo
	latchedVideoSessions map[string]*SessionInfo
}

func createCustomMediaEngine() (*webrtc.MediaEngine, error) {
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

	// Register header extensions for mid matching in BUNDLE
	err = m.RegisterHeaderExtension(webrtc.RTPHeaderExtensionCapability{
		URI: sdp.SDESMidURI,
	}, webrtc.RTPCodecTypeAudio)
	if err != nil {
		return nil, err
	}
	err = m.RegisterHeaderExtension(webrtc.RTPHeaderExtensionCapability{
		URI: sdp.SDESMidURI,
	}, webrtc.RTPCodecTypeVideo)
	if err != nil {
		return nil, err
	}

	return m, nil
}

// NewMediaFunction tạo một MediaFunction instance và khởi động shared UDP MUX
func NewMediaFunction(cfg *config.Config) *MediaFunction {
	port := cfg.Services.Mf.WebRtcPort

	// Xác định port quảng bá ra bên ngoài (NodePort cho K8s)
	// Nếu không set PUBLIC_PORT, dùng port nội bộ (đúng khi chạy trên mạng phẳng/Macvlan)
	pubPort := port
	if envPort := os.Getenv("PUBLIC_PORT"); envPort != "" {
		if p, err := strconv.Atoi(envPort); err == nil && p > 0 {
			pubPort = p
			log.Printf("[MF] PUBLIC_PORT set: SDP will advertise port %d (internal bind: %d)", pubPort, port)
		}
	}

	// Tạo UDP socket bind trên 0.0.0.0
	udpAddr := &net.UDPAddr{IP: net.IPv4zero, Port: port}
	udpConn, err := net.ListenUDP("udp4", udpAddr)
	if err != nil {
		log.Fatalf("[MF] Cannot bind UDP port %d: %v", port, err)
	}
	log.Printf("[MF] Shared UDP MUX listening on 0.0.0.0:%d", port)

	udpMux := ice.NewUDPMuxDefault(ice.UDPMuxParams{
		UDPConn: udpConn,
	})

	// Dedicated ADC UDP Mux on port 40005 — shared by all ADC PeerConnections
	// This avoids DTLS state collision when ADC is closed and reopened (e.g. exit/re-enter whiteboard)
	adcPort := 40005
	if envAdcPort := os.Getenv("MF_ADC_UDP_PORT"); envAdcPort != "" {
		if p, err := strconv.Atoi(envAdcPort); err == nil && p > 0 {
			adcPort = p
		}
	}
	adcUdpAddr := &net.UDPAddr{IP: net.IPv4zero, Port: adcPort}
	adcUdpConn, err := net.ListenUDP("udp4", adcUdpAddr)
	if err != nil {
		log.Printf("[MF] WARNING: Cannot bind ADC UDP port %d: %v — ADC will use ephemeral ports", adcPort, err)
	} else {
		log.Printf("[MF] ADC UDP MUX listening on 0.0.0.0:%d", adcPort)
	}
	var adcUdpMux ice.UDPMux
	if adcUdpConn != nil {
		adcUdpMux = ice.NewUDPMuxDefault(ice.UDPMuxParams{
			UDPConn: adcUdpConn,
		})
	}

	// Bind global audio plain RTP socket on port 40002 (Caller)
	globalAudioAddr := &net.UDPAddr{IP: net.IPv4zero, Port: 40002}
	gAudioConn, err := net.ListenUDP("udp4", globalAudioAddr)
	if err != nil {
		log.Printf("[MF] WARNING: Cannot bind global Audio RTP port 40002: %v", err)
	} else {
		log.Printf("[MF] Global Audio RTP socket listening on 0.0.0.0:40002")
	}

	// Bind global video plain RTP socket on port 40004 (Caller)
	globalVideoAddr := &net.UDPAddr{IP: net.IPv4zero, Port: 40004}
	gVideoConn, err := net.ListenUDP("udp4", globalVideoAddr)
	if err != nil {
		log.Printf("[MF] WARNING: Cannot bind global Video RTP port 40004: %v", err)
	} else {
		log.Printf("[MF] Global Video RTP socket listening on 0.0.0.0:40004")
	}

	// Bind global audio plain RTP socket on port 40006 (Callee)
	globalAudioCalleeAddr := &net.UDPAddr{IP: net.IPv4zero, Port: 40006}
	gAudioCalleeConn, err := net.ListenUDP("udp4", globalAudioCalleeAddr)
	if err != nil {
		log.Printf("[MF] WARNING: Cannot bind global Audio RTP port 40006: %v", err)
	} else {
		log.Printf("[MF] Global Audio Callee RTP socket listening on 0.0.0.0:40006")
	}

	// Bind global video plain RTP socket on port 40008 (Callee)
	globalVideoCalleeAddr := &net.UDPAddr{IP: net.IPv4zero, Port: 40008}
	gVideoCalleeConn, err := net.ListenUDP("udp4", globalVideoCalleeAddr)
	if err != nil {
		log.Printf("[MF] WARNING: Cannot bind global Video RTP port 40008: %v", err)
	} else {
		log.Printf("[MF] Global Video Callee RTP socket listening on 0.0.0.0:40008")
	}

	mf := &MediaFunction{
		sessions:   make(map[string]*SessionInfo),
		contexts:   make(map[string]*ContextInfo),
		udpMux:     udpMux,
		udpPort:    port,
		publicPort: pubPort,
		adcUdpMux:  adcUdpMux,
		adcUdpPort: adcPort,
		cfg:        cfg,
		httpClient: &http.Client{
			Timeout: 5 * time.Second,
			Transport: &imsas.HEPRoundTripper{
				Proxied: &http2.Transport{
					AllowHTTP: true,
					DialTLS: func(network, addr string, cfg *tls.Config) (net.Conn, error) {
						return net.Dial(network, addr)
					},
				},
			},
		},
		audioConn:            gAudioConn,
		videoConn:            gVideoConn,
		audioCalleeConn:      gAudioCalleeConn,
		videoCalleeConn:      gVideoCalleeConn,
		latchedAudioSessions: make(map[string]*SessionInfo),
		latchedVideoSessions: make(map[string]*SessionInfo),
	}

	if gAudioConn != nil {
		go mf.runGlobalRtpForwarder(gAudioConn, gAudioCalleeConn, "audio", mf.latchedAudioSessions)
	}
	if gVideoConn != nil {
		go mf.runGlobalRtpForwarder(gVideoConn, gVideoCalleeConn, "video", mf.latchedVideoSessions)
	}
	if gAudioCalleeConn != nil {
		go mf.runGlobalRtpForwarder(gAudioCalleeConn, gAudioConn, "audio", mf.latchedAudioSessions)
	}
	if gVideoCalleeConn != nil {
		go mf.runGlobalRtpForwarder(gVideoCalleeConn, gVideoConn, "video", mf.latchedVideoSessions)
	}

	return mf
}

func (m *MediaFunction) getRingingTimeout() time.Duration {
	if envVal := os.Getenv("MF_RINGING_TIMEOUT_SEC"); envVal != "" {
		if sec, err := strconv.Atoi(envVal); err == nil && sec > 0 {
			return time.Duration(sec) * time.Second
		}
	}
	if envVal := os.Getenv("RINGING_TIMEOUT_SEC"); envVal != "" {
		if sec, err := strconv.Atoi(envVal); err == nil && sec > 0 {
			return time.Duration(sec) * time.Second
		}
	}
	if m != nil && m.cfg != nil && m.cfg.Services.Mf.RingingTimeoutSec > 0 {
		return time.Duration(m.cfg.Services.Mf.RingingTimeoutSec) * time.Second
	}
	return 45 * time.Second
}

// AllocateSession cấp phát 1 WebRTC PeerConnection cho 1 Client
func (m *MediaFunction) AllocateSession(userID, callID string, isCore bool, forceIP string) (*AllocateResponse, error) {
	sessionID := uuid.New().String()
	log.Printf("[MF] AllocateSession: user=%s call=%s session=%s isCore=%v forceIP=%s", userID, callID, sessionID, isCore, forceIP)

	var localIP string
	var err error
	if forceIP != "" {
		localIP = forceIP
	} else if isCore {
		localIP, err = getCoreIP()
	} else {
		localIP, err = getAccessIP()
	}
	if err != nil && localIP == "" {
		localIP = "127.0.0.1"
	}

	expectedAudioPort := m.publicPort + 2
	expectedVideoPort := m.publicPort + 4
	if strings.Contains(strings.ToLower(userID), "callee") {
		expectedAudioPort = m.publicPort + 6
		expectedVideoPort = m.publicPort + 8
	}

	// Bind plain RTP sockets
	audioConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil, fmt.Errorf("listen audio udp: %w", err)
	}
	audioPort := expectedAudioPort

	videoConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		audioConn.Close()
		return nil, fmt.Errorf("listen video udp: %w", err)
	}
	videoPort := expectedVideoPort

	s := webrtc.SettingEngine{}
	if klw, err := os.OpenFile("/app/config/bdc_ssl_keys.log", os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0666); err == nil {
		s.SetDTLSKeyLogWriter(klw)
	}
	s.SetInterfaceFilter(func(name string) bool {
		return strings.HasPrefix(name, "tailscale") || strings.HasPrefix(name, "eth") || strings.HasPrefix(name, "en") || name == "net1"
	})
	s.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	s.SetICEUDPMux(m.udpMux)

	ringingTimeout := m.getRingingTimeout()
	failedTimeout := 90 * time.Second
	if ringingTimeout >= failedTimeout {
		failedTimeout = ringingTimeout + 30*time.Second
	}
	s.SetICETimeouts(ringingTimeout, failedTimeout, 2*time.Second)
	s.SetICEMulticastDNSMode(0)
	s.SetLite(true)

	mediaEngine, err := createCustomMediaEngine()
	if err != nil {
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("create custom media engine: %w", err)
	}
	api := webrtc.NewAPI(
		webrtc.WithSettingEngine(s),
		webrtc.WithMediaEngine(mediaEngine),
	)

	config := webrtc.Configuration{
		ICEServers:    []webrtc.ICEServer{},
		BundlePolicy:  webrtc.BundlePolicyMaxBundle,
		RTCPMuxPolicy: webrtc.RTCPMuxPolicyRequire,
		SDPSemantics:  webrtc.SDPSemanticsUnifiedPlanWithFallback,
	}
	bdcPC, err := api.NewPeerConnection(config)
	if err != nil {
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("create peer connection: %w", err)
	}

	session := &SessionInfo{
		ID:             sessionID,
		CallID:         callID,
		UserID:         userID,
		BdcPC:          bdcPC,
		LocalPort:      m.udpPort,
		BdcLocalPort:   m.udpPort,
		LocalIP:        localIP,
		LocalAudioPort: audioPort,
		LocalVideoPort: videoPort,
		AudioConn:      audioConn,
		VideoConn:      videoConn,
		CreatedAt:      time.Now(),
	}

	audioTrack, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypePCMA, ClockRate: 8000}, "audio", "stream1")
	if err == nil {
		if _, err := bdcPC.AddTransceiverFromTrack(audioTrack, webrtc.RTPTransceiverInit{
			Direction: webrtc.RTPTransceiverDirectionSendrecv,
		}); err == nil {
			session.AudioTrack = audioTrack
		}
	}

	videoTrack, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264, ClockRate: 90000}, "video", "stream1")
	if err == nil {
		if _, err := bdcPC.AddTransceiverFromTrack(videoTrack, webrtc.RTPTransceiverInit{
			Direction: webrtc.RTPTransceiverDirectionSendrecv,
		}); err == nil {
			session.VideoTrack = videoTrack
		}
	}

	bdcPC.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		log.Printf("[MF] Session %s (user=%s) ICE state: %s", sessionID, userID, state.String())
		if state == webrtc.ICEConnectionStateConnected {
			log.Printf("[MF] ✅ Session %s CONNECTED!", sessionID)
		} else if state == webrtc.ICEConnectionStateFailed {
			log.Printf("[MF] ❌ Session %s connection lost: %s", sessionID, state)
			m.CleanupSession(sessionID)
		}
	})

	isADC := isAdcContext(callID)
	var bdcStreams []uint16
	if isADC {
		bdcStreams = []uint16{1000, 1001}
	} else {
		bdcStreams = []uint16{0, 10, 100, 110}
	}
	bdcNegotiated := true
	var bdcChannels []*webrtc.DataChannel
	for _, streamID := range bdcStreams {
		sid := streamID
		var protocol string
		var label string
		if isADC {
			if sid == 1000 {
				protocol = "whiteboard"
				label = "app-dc"
			} else {
				protocol = "emoji"
				label = "app-dc-2"
			}
		} else {
			protocol = "http"
			label = fmt.Sprintf("bdc-%d", sid)
		}
		dc, err := bdcPC.CreateDataChannel(label, &webrtc.DataChannelInit{
			ID:         &sid,
			Negotiated: &bdcNegotiated,
			Protocol:   &protocol,
		})
		if err != nil {
			bdcPC.Close()
			audioConn.Close()
			videoConn.Close()
			return nil, fmt.Errorf("create Stream %d: %w", sid, err)
		}
		bdcChannels = append(bdcChannels, dc)
	}

	session.mu.Lock()
	if isADC {
		if len(bdcChannels) > 0 {
			session.ADC = bdcChannels[0]
		}
		if len(bdcChannels) > 1 {
			session.ADC2 = bdcChannels[1]
		}
	} else {
		if len(bdcChannels) > 0 {
			session.BDC = bdcChannels[0]
		}
	}
	session.mu.Unlock()

	m.registerBdcHandlers(session)

	for _, dc := range bdcChannels {
		if isADC {
			m.handleADC(session, dc)
		} else {
			m.handleBDC(session, dc)
		}
	}

	offer, err := bdcPC.CreateOffer(nil)
	if err != nil {
		bdcPC.Close()
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("create offer: %w", err)
	}

	gatherComplete := webrtc.GatheringCompletePromise(bdcPC)
	if err = bdcPC.SetLocalDescription(offer); err != nil {
		bdcPC.Close()
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("set local description: %w", err)
	}

	select {
	case <-gatherComplete:
		log.Printf("[MF] ICE gathering complete for session %s", sessionID)
	case <-time.After(5 * time.Second):
		log.Printf("[MF] Warning: ICE gathering timeout for session %s", sessionID)
	}

	localDesc := bdcPC.LocalDescription()
	sdpText := localDesc.SDP

	if localIP != "" {
		containerIP, err2 := getCoreIP()
		if err2 == nil && containerIP != localIP {
			sdpText = strings.ReplaceAll(sdpText, " "+containerIP+" ", " "+localIP+" ")
		}
		sdpText = filterSDPCandidates(sdpText, localIP)
	}
	sdpText = rewriteSDPPorts(sdpText, m.udpPort, m.publicPort)

	iceParams, dtlsFingerprint, err := extractICEDTLSFromSDP(sdpText)
	if err != nil {
		log.Printf("[MF] Warning: cannot extract ICE/DTLS params: %v", err)
	}

	session.ICEParams = iceParams
	session.Fingerprint = dtlsFingerprint
	session.BdcICEParams = iceParams
	session.BdcFingerprint = dtlsFingerprint

	m.mu.Lock()
	m.sessions[sessionID] = session
	m.mu.Unlock()

	resp := &AllocateResponse{
		SessionID:   sessionID,
		ICEUfrag:    iceParams.UsernameFragment,
		ICEPwd:      iceParams.Password,
		Fingerprint: fmt.Sprintf("%s %s", dtlsFingerprint.Algorithm, dtlsFingerprint.Value),
		IP:          localIP,
		Port:        m.publicPort,
		AudioPort:   audioPort,
		VideoPort:   videoPort,
		SetupRole:   extractSetupRoleFromSDP(sdpText),
	}

	return resp, nil
}

func (m *MediaFunction) AllocateAnswererSession(userID, callID, sdpOffer string, isCore bool) (*AllocateResponse, error) {
	sessionID := uuid.New().String()
	log.Printf("[MF] AllocateAnswererSession: user=%s call=%s session=%s isCore=%v", userID, callID, sessionID, isCore)

	var localIP string
	var err error
	if isCore {
		localIP, err = getCoreIP()
	} else {
		mediaInfo := parseSDPMedia(sdpOffer)
		if mediaInfo.IP != "" && strings.HasPrefix(mediaInfo.IP, "100.") {
			localIP, err = getAccessIP()
		} else {
			localIP, err = getCoreIP()
		}
	}
	if err != nil && localIP == "" {
		localIP = "127.0.0.1"
	}

	expectedAudioPort := m.publicPort + 2
	expectedVideoPort := m.publicPort + 4
	if strings.Contains(strings.ToLower(userID), "callee") {
		expectedAudioPort = m.publicPort + 6
		expectedVideoPort = m.publicPort + 8
	}

	// Bind plain RTP sockets
	audioConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil, fmt.Errorf("listen audio udp: %w", err)
	}
	audioPort := expectedAudioPort

	videoConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		audioConn.Close()
		return nil, fmt.Errorf("listen video udp: %w", err)
	}
	videoPort := expectedVideoPort

	s := webrtc.SettingEngine{}
	if klw, err := os.OpenFile("/app/config/bdc_ssl_keys.log", os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0666); err == nil {
		s.SetDTLSKeyLogWriter(klw)
	}
	s.SetInterfaceFilter(func(name string) bool {
		return strings.HasPrefix(name, "tailscale") || strings.HasPrefix(name, "eth") || strings.HasPrefix(name, "en") || name == "net1"
	})
	s.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	s.SetICEUDPMux(m.udpMux)

	ringingTimeout := m.getRingingTimeout()
	failedTimeout := 90 * time.Second
	if ringingTimeout >= failedTimeout {
		failedTimeout = ringingTimeout + 30*time.Second
	}
	s.SetICETimeouts(ringingTimeout, failedTimeout, 2*time.Second)
	s.SetICEMulticastDNSMode(0)
	s.SetLite(true)
	_ = s.SetAnsweringDTLSRole(webrtc.DTLSRoleServer)

	mediaEngine, err := createCustomMediaEngine()
	if err != nil {
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("create custom media engine: %w", err)
	}
	api := webrtc.NewAPI(
		webrtc.WithSettingEngine(s),
		webrtc.WithMediaEngine(mediaEngine),
	)

	config := webrtc.Configuration{
		ICEServers:    []webrtc.ICEServer{},
		BundlePolicy:  webrtc.BundlePolicyMaxBundle,
		RTCPMuxPolicy: webrtc.RTCPMuxPolicyRequire,
		SDPSemantics:  webrtc.SDPSemanticsUnifiedPlanWithFallback,
	}
	bdcPC, err := api.NewPeerConnection(config)
	if err != nil {
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("create peer connection: %w", err)
	}

	session := &SessionInfo{
		ID:             sessionID,
		CallID:         callID,
		UserID:         userID,
		BdcPC:          bdcPC,
		LocalPort:      m.udpPort,
		BdcLocalPort:   m.udpPort,
		LocalIP:        localIP,
		LocalAudioPort: audioPort,
		LocalVideoPort: videoPort,
		AudioConn:      audioConn,
		VideoConn:      videoConn,
		CreatedAt:      time.Now(),
	}

	// Parse remote SDP info
	mediaInfo := parseSDPMedia(sdpOffer)
	session.RtpIP = mediaInfo.IP
	if session.RtpIP == "" {
		session.RtpIP = "127.0.0.1" // fallback
	}
	session.RtpAudioPort = mediaInfo.AudioPort
	session.RtpVideoPort = mediaInfo.VideoPort

	audioTrack, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypePCMA, ClockRate: 8000}, "audio", "stream1")
	if err == nil {
		if _, err := bdcPC.AddTransceiverFromTrack(audioTrack, webrtc.RTPTransceiverInit{
			Direction: webrtc.RTPTransceiverDirectionSendrecv,
		}); err == nil {
			session.AudioTrack = audioTrack
		}
	}

	videoTrack, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264, ClockRate: 90000}, "video", "stream1")
	if err == nil {
		if _, err := bdcPC.AddTransceiverFromTrack(videoTrack, webrtc.RTPTransceiverInit{
			Direction: webrtc.RTPTransceiverDirectionSendrecv,
		}); err == nil {
			session.VideoTrack = videoTrack
		}
	}

	bdcPC.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		log.Printf("[MF] Session %s (user=%s) ICE state: %s", sessionID, userID, state.String())
		if state == webrtc.ICEConnectionStateConnected {
			log.Printf("[MF] ✅ Session %s CONNECTED!", sessionID)
		} else if state == webrtc.ICEConnectionStateFailed {
			log.Printf("[MF] ❌ Session %s connection lost: %s", sessionID, state)
			m.CleanupSession(sessionID)
		}
	})

	isADC := isAdcContext(callID)
	var bdcStreams []uint16
	if isADC {
		bdcStreams = []uint16{1000, 1001}
	} else {
		bdcStreams = []uint16{0, 10, 100, 110}
	}
	bdcNegotiated := true
	var bdcChannels []*webrtc.DataChannel
	for _, streamID := range bdcStreams {
		sid := streamID
		var protocol string
		var label string
		if isADC {
			if sid == 1000 {
				protocol = "whiteboard"
				label = "app-dc"
			} else {
				protocol = "emoji"
				label = "app-dc-2"
			}
		} else {
			protocol = "http"
			label = fmt.Sprintf("bdc-%d", sid)
		}
		dc, err := bdcPC.CreateDataChannel(label, &webrtc.DataChannelInit{
			ID:         &sid,
			Negotiated: &bdcNegotiated,
			Protocol:   &protocol,
		})
		if err != nil {
			bdcPC.Close()
			audioConn.Close()
			videoConn.Close()
			return nil, fmt.Errorf("create Stream %d: %w", sid, err)
		}
		bdcChannels = append(bdcChannels, dc)
	}

	session.mu.Lock()
	if isADC {
		if len(bdcChannels) > 0 {
			session.ADC = bdcChannels[0]
		}
		if len(bdcChannels) > 1 {
			session.ADC2 = bdcChannels[1]
		}
	} else {
		if len(bdcChannels) > 0 {
			session.BDC = bdcChannels[0]
		}
	}
	session.mu.Unlock()

	m.registerBdcHandlers(session)

	for _, dc := range bdcChannels {
		if isADC {
			m.handleADC(session, dc)
		} else {
			m.handleBDC(session, dc)
		}
	}

	log.Printf("[MF] Incoming sdpOffer:\n%s", sdpOffer)
	bdcOffer, adcOffer := splitSDPAnswer(sdpOffer)
	targetOffer := bdcOffer
	if isADC && adcOffer != "" {
		targetOffer = adcOffer
	}
	cleanOffer := stripAudioVideoFromSDP(targetOffer)
	translatedOffer := translateSipOfferToWebRtcOffer(cleanOffer)
	offer := webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer,
		SDP:  translatedOffer,
	}
	if err = bdcPC.SetRemoteDescription(offer); err != nil {
		bdcPC.Close()
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("set remote description: %w", err)
	}

	answer, err := bdcPC.CreateAnswer(nil)
	if err != nil {
		bdcPC.Close()
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("create answer: %w", err)
	}

	gatherComplete := webrtc.GatheringCompletePromise(bdcPC)
	if err = bdcPC.SetLocalDescription(answer); err != nil {
		bdcPC.Close()
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("set local description: %w", err)
	}

	select {
	case <-gatherComplete:
		log.Printf("[MF] ICE gathering complete for session %s", sessionID)
	case <-time.After(5 * time.Second):
		log.Printf("[MF] Warning: ICE gathering timeout for session %s", sessionID)
	}

	localDesc := bdcPC.LocalDescription()
	sdpText := localDesc.SDP

	if localIP != "" {
		containerIP, err2 := getCoreIP()
		if err2 == nil && containerIP != localIP {
			sdpText = strings.ReplaceAll(sdpText, " "+containerIP+" ", " "+localIP+" ")
		}
		sdpText = filterSDPCandidates(sdpText, localIP)
	}
	sdpText = rewriteSDPPorts(sdpText, m.udpPort, m.publicPort)

	iceParams, dtlsFingerprint, err := extractICEDTLSFromSDP(sdpText)
	if err != nil {
		log.Printf("[MF] Warning: cannot extract ICE/DTLS params: %v", err)
	}

	session.ICEParams = iceParams
	session.Fingerprint = dtlsFingerprint
	session.BdcICEParams = iceParams
	session.BdcFingerprint = dtlsFingerprint

	m.mu.Lock()
	m.sessions[sessionID] = session
	m.mu.Unlock()

	resp := &AllocateResponse{
		SessionID:   sessionID,
		ICEUfrag:    iceParams.UsernameFragment,
		ICEPwd:      iceParams.Password,
		Fingerprint: fmt.Sprintf("%s %s", dtlsFingerprint.Algorithm, dtlsFingerprint.Value),
		IP:          localIP,
		Port:        m.publicPort,
		AudioPort:   audioPort,
		VideoPort:   videoPort,
		SetupRole:   extractSetupRoleFromSDP(sdpText),
		SDPOffer:    sdpText,
	}

	return resp, nil
}

// AllocateHotlineSession cấp phát session cho Hotline cuộc gọi 198
func (m *MediaFunction) AllocateHotlineSession(userID, callID, sdpOffer string, isCore bool) (*AllocateResponse, error) {
	sessionID := uuid.New().String()
	log.Printf("[MF] AllocateHotlineSession: user=%s call=%s session=%s isCore=%v", userID, callID, sessionID, isCore)

	var localIP string
	var err error
	if isCore {
		localIP, err = getCoreIP()
	} else {
		mediaInfo := parseSDPMedia(sdpOffer)
		if mediaInfo.IP != "" && strings.HasPrefix(mediaInfo.IP, "100.") {
			localIP, err = getAccessIP()
		} else {
			localIP, err = getCoreIP()
		}
	}
	if err != nil && localIP == "" {
		localIP = "127.0.0.1"
	}

	expectedAudioPort := m.publicPort + 2
	expectedVideoPort := m.publicPort + 4
	if strings.Contains(strings.ToLower(userID), "callee") {
		expectedAudioPort = m.publicPort + 6
		expectedVideoPort = m.publicPort + 8
	}

	// Bind plain RTP sockets
	audioConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil, fmt.Errorf("listen audio udp: %w", err)
	}
	audioPort := expectedAudioPort

	videoConn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		audioConn.Close()
		return nil, fmt.Errorf("listen video udp: %w", err)
	}
	videoPort := expectedVideoPort

	s := webrtc.SettingEngine{}
	if klw, err := os.OpenFile("/app/config/bdc_ssl_keys.log", os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0666); err == nil {
		s.SetDTLSKeyLogWriter(klw)
	}
	s.SetInterfaceFilter(func(name string) bool {
		return strings.HasPrefix(name, "tailscale") || strings.HasPrefix(name, "eth") || strings.HasPrefix(name, "en") || name == "net1"
	})
	s.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	s.SetICEUDPMux(m.udpMux)

	ringingTimeout := m.getRingingTimeout()
	failedTimeout := 90 * time.Second
	if ringingTimeout >= failedTimeout {
		failedTimeout = ringingTimeout + 30*time.Second
	}
	s.SetICETimeouts(ringingTimeout, failedTimeout, 2*time.Second)
	s.SetICEMulticastDNSMode(0)
	s.SetLite(true)
	_ = s.SetAnsweringDTLSRole(webrtc.DTLSRoleServer)

	mediaEngine, err := createCustomMediaEngine()
	if err != nil {
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("create custom media engine: %w", err)
	}
	api := webrtc.NewAPI(
		webrtc.WithSettingEngine(s),
		webrtc.WithMediaEngine(mediaEngine),
	)

	config := webrtc.Configuration{
		ICEServers:    []webrtc.ICEServer{},
		BundlePolicy:  webrtc.BundlePolicyMaxBundle,
		RTCPMuxPolicy: webrtc.RTCPMuxPolicyRequire,
		SDPSemantics:  webrtc.SDPSemanticsUnifiedPlanWithFallback,
	}
	pc, err := api.NewPeerConnection(config)
	if err != nil {
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("create peer connection: %w", err)
	}

	session := &SessionInfo{
		ID:             sessionID,
		CallID:         callID,
		UserID:         userID,
		BdcPC:          pc,
		LocalPort:      m.udpPort,
		BdcLocalPort:   m.udpPort,
		LocalIP:        localIP,
		IsHotline:      true,
		LocalAudioPort: audioPort,
		LocalVideoPort: videoPort,
		AudioConn:      audioConn,
		VideoConn:      videoConn,
		CreatedAt:      time.Now(),
	}

	// Parse remote SDP info
	mediaInfo := parseSDPMedia(sdpOffer)
	session.RtpIP = mediaInfo.IP
	if session.RtpIP == "" {
		session.RtpIP = "127.0.0.1" // fallback
	}
	session.RtpAudioPort = mediaInfo.AudioPort
	session.RtpVideoPort = mediaInfo.VideoPort

	go m.listenPlainRTP(session, audioConn, "audio")
	go m.listenPlainRTP(session, videoConn, "video")

	pc.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		log.Printf("[MF] Hotline session %s ICE state: %s", sessionID, state.String())
		if state == webrtc.ICEConnectionStateConnected {
			log.Printf("[MF] ✅ Hotline session connected. Streaming welcome audio...")
			go m.PlayHotlineAction(session, "welcome")
		} else if state == webrtc.ICEConnectionStateFailed {
			log.Printf("[MF] ❌ Hotline session disconnected: %s", state)
			m.CleanupSession(sessionID)
		}
	})

	// Pre-negotiate BDC channels
	bdcStreams := []uint16{0, 10, 100, 110}
	bdcNegotiated := true
	bdcProtocol := "http"
	var bdcChannels []*webrtc.DataChannel
	for _, streamID := range bdcStreams {
		sid := streamID
		dc, err := pc.CreateDataChannel(fmt.Sprintf("bdc-%d", sid), &webrtc.DataChannelInit{
			ID:         &sid,
			Negotiated: &bdcNegotiated,
			Protocol:   &bdcProtocol,
		})
		if err != nil {
			pc.Close()
			audioConn.Close()
			videoConn.Close()
			return nil, fmt.Errorf("create BDC Stream %d: %w", sid, err)
		}
		bdcChannels = append(bdcChannels, dc)
	}

	session.mu.Lock()
	if len(bdcChannels) > 0 {
		session.BDC = bdcChannels[0]
	}
	session.mu.Unlock()

	m.registerBdcHandlers(session)

	for _, dc := range bdcChannels {
		m.handleBDC(session, dc)
	}

	// Strip audio/video from SDP passed to Pion
	cleanOffer := stripAudioVideoFromSDP(sdpOffer)
	translatedOffer := translateSipOfferToWebRtcOffer(cleanOffer)
	offer := webrtc.SessionDescription{
		Type: webrtc.SDPTypeOffer,
		SDP:  translatedOffer,
	}
	if err = pc.SetRemoteDescription(offer); err != nil {
		pc.Close()
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("set remote description: %w", err)
	}

	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		pc.Close()
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("create answer: %w", err)
	}

	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err = pc.SetLocalDescription(answer); err != nil {
		pc.Close()
		audioConn.Close()
		videoConn.Close()
		return nil, fmt.Errorf("set local description: %w", err)
	}

	select {
	case <-gatherComplete:
		log.Printf("[MF] ICE gathering complete for hotline session %s", sessionID)
	case <-time.After(5 * time.Second):
		log.Printf("[MF] Warning: ICE gathering timeout for hotline session %s", sessionID)
	}

	localDesc := pc.LocalDescription()
	sdpText := localDesc.SDP

	if localIP != "" {
		containerIP, err2 := getCoreIP()
		if err2 == nil && containerIP != localIP {
			sdpText = strings.ReplaceAll(sdpText, " "+containerIP+" ", " "+localIP+" ")
		}
		sdpText = filterSDPCandidates(sdpText, localIP)
	}
	sdpText = rewriteSDPPorts(sdpText, m.udpPort, m.publicPort)

	iceParams, dtlsFingerprint, err := extractICEDTLSFromSDP(sdpText)
	if err != nil {
		log.Printf("[MF] Warning: cannot extract ICE/DTLS params: %v", err)
	}

	session.ICEParams = iceParams
	session.Fingerprint = dtlsFingerprint
	session.BdcICEParams = iceParams
	session.BdcFingerprint = dtlsFingerprint

	m.mu.Lock()
	m.sessions[sessionID] = session
	m.mu.Unlock()

	translatedAnswer := translateWebRtcAnswerToSipAnswer(sdpText)

	resp := &AllocateResponse{
		SessionID:   sessionID,
		ICEUfrag:    iceParams.UsernameFragment,
		ICEPwd:      iceParams.Password,
		Fingerprint: fmt.Sprintf("%s %s", dtlsFingerprint.Algorithm, dtlsFingerprint.Value),
		IP:          localIP,
		Port:        m.publicPort,
		AudioPort:   audioPort,
		VideoPort:   videoPort,
		SetupRole:   extractSetupRoleFromSDP(sdpText),
		SDPAnswer:   translatedAnswer,
	}

	return resp, nil
}

// AnnouncementSession lưu thông tin session phát âm thông báo qua plain RTP
type AnnouncementSession struct {
	SessionID string `json:"session_id"`
	LocalIP   string `json:"local_ip"`
	AudioPort int    `json:"audio_port"`
}

// AllocateAnnouncementSession cấp phát session nhẹ chỉ dùng cho việc phát âm thông báo qua plain RTP
func (m *MediaFunction) AllocateAnnouncementSession(userID, callID, sdpOffer string) (*AnnouncementSession, error) {
	sessionID := uuid.New().String()
	log.Printf("[MF] AllocateAnnouncementSession: user=%s call=%s session=%s", userID, callID, sessionID)

	mediaInfo := parseSDPMedia(sdpOffer)
	var localIP string
	var err error
	if mediaInfo.IP != "" && strings.HasPrefix(mediaInfo.IP, "100.") {
		localIP, err = getAccessIP()
	} else {
		localIP, err = getCoreIP()
	}
	if err != nil && localIP == "" {
		localIP = "127.0.0.1"
	}

	audioPort := m.publicPort + 2
	audioConn := m.audioConn
	if audioConn == nil {
		var err error
		audioConn, err = net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
		if err != nil {
			return nil, fmt.Errorf("listen audio udp: %w", err)
		}
		audioPort = audioConn.LocalAddr().(*net.UDPAddr).Port
	}

	session := &SessionInfo{
		ID:             sessionID,
		CallID:         callID,
		UserID:         userID,
		LocalIP:        localIP,
		IsHotline:      true,
		LocalAudioPort: audioPort,
		AudioConn:      audioConn,
		CreatedAt:      time.Now(),
	}

	session.RtpIP = mediaInfo.IP
	if session.RtpIP == "" {
		session.RtpIP = "127.0.0.1"
	}
	session.RtpAudioPort = mediaInfo.AudioPort
	session.RtpVideoPort = mediaInfo.VideoPort

	m.mu.Lock()
	m.sessions[sessionID] = session
	m.mu.Unlock()

	return &AnnouncementSession{
		SessionID: sessionID,
		LocalIP:   localIP,
		AudioPort: audioPort,
	}, nil
}

// BDC Handler: fetches HTML/ZIP from DCAR container (MDC1 API)
// The client sends: "GET /dcar/v1/applications/<appId> HTTP/1.1\r\nHost: dcsf\r\n\r\n"
// or the legacy: "GET / HTTP/1.1\r\nHost: dcsf\r\n\r\n"
func (m *MediaFunction) handleBDC(session *SessionInfo, dc *webrtc.DataChannel) {
	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		data := string(msg.Data)

		chanID := uint16(0)
		if dc.ID() != nil {
			chanID = *dc.ID()
		}

		srcIP := session.RtpIP
		if srcIP == "" { srcIP = "127.0.0.1" }
		dstIP := session.LocalIP
		if dstIP == "" { dstIP = "127.0.0.1" }

		log.Printf("[DATA-CHANNEL-TRACE] BDC Payload (Incoming): Session=%s, StreamID=%d, Msg=%s", session.ID, chanID, data)
		imsas.SendHEPHTTP(data, srcIP, int(chanID), dstIP, session.LocalPort, session.CallID)

		if !strings.HasPrefix(data, "GET ") && !strings.HasPrefix(data, "get ") {
			return
		}

		chanKey := fmt.Sprintf("%d", chanID)
		url := ""
		session.mu.Lock()
		if session.ReplaceHttpUrls != nil {
			if repl, ok := session.ReplaceHttpUrls[chanKey]; ok {
				url = repl.ReplacementHttpUrl
			}
		}
		session.mu.Unlock()

		// Parse requestedPath and headers (e.g. If-None-Match)
		requestedPath := "/"
		lines := strings.Split(strings.ReplaceAll(data, "\r\n", "\n"), "\n")
		var ifNoneMatch string
		if len(lines) > 0 {
			parts := strings.Fields(lines[0])
			if len(parts) >= 2 {
				requestedPath = parts[1]
			}
		}
		for _, line := range lines {
			lowerLine := strings.ToLower(line)
			if strings.HasPrefix(lowerLine, "if-none-match:") {
				ifNoneMatch = strings.TrimSpace(line[len("if-none-match:"):])
			}
		}

		dcarHost := m.cfg.Services.Dcar.Host
		dcarPort := m.cfg.Services.Dcar.Port

		if strings.HasPrefix(requestedPath, "/dcar/v1/applications/") {
			url = fmt.Sprintf("http://%s:%d%s", dcarHost, dcarPort, requestedPath)
		} else if url == "" {
			if session.IsHotline {
				url = fmt.Sprintf("http://%s:%d/198.html", dcarHost, dcarPort)
			} else {
				url = fmt.Sprintf("http://%s:%d/dcar/v1/applications/operator-menu/download", dcarHost, dcarPort)
			}
		}

		log.Printf("[MF] BDC StreamID: %d - Fetching mini-app from: %s (If-None-Match: %s)", chanID, url, ifNoneMatch)

		req, err := http.NewRequest("GET", url, nil)
		if err != nil {
			log.Printf("[MF] Error creating request: %v", err)
			return
		}
		if ifNoneMatch != "" {
			req.Header.Set("If-None-Match", ifNoneMatch)
		}

		resp, err := m.httpClient.Do(req)
		if err != nil {
			log.Printf("[MF] DCAR fetch error: %v, serving fallback", err)
			htmlContent := getFallbackHTML()
			httpResp := fmt.Sprintf("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", len(htmlContent), string(htmlContent))
			imsas.SendHEPHTTP(httpResp, session.LocalIP, session.LocalPort, session.RtpIP, int(chanID), session.CallID)
			_ = dc.SendText(httpResp)
			return
		}
		defer resp.Body.Close()

		if resp.StatusCode == http.StatusNotModified {
			log.Printf("[MF] BDC: DCAR returned 304 Not Modified")
			httpResp := "HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n"
			imsas.SendHEPHTTP(httpResp, session.LocalIP, session.LocalPort, session.RtpIP, int(chanID), session.CallID)
			_ = dc.SendText(httpResp)
			return
		}

		var bodyBuf bytes.Buffer
		_, _ = io.Copy(&bodyBuf, resp.Body)
		rawBytes := bodyBuf.Bytes()

		contentType := resp.Header.Get("Content-Type")
		etag := resp.Header.Get("ETag")
		etagHeader := ""
		if etag != "" {
			etagHeader = fmt.Sprintf("ETag: %s\r\n", etag)
		}

		isZipResponse := strings.Contains(contentType, "zip") || (len(rawBytes) >= 4 && rawBytes[0] == 0x50 && rawBytes[1] == 0x4B)

		if isZipResponse {
			log.Printf("[MF] BDC: DCAR returned ZIP (%d bytes), sending binary via BDC", len(rawBytes))
			httpHeader := fmt.Sprintf("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n", etagHeader, len(rawBytes))
			
			headerBytes := []byte(httpHeader)
			fullPayload := make([]byte, len(headerBytes)+len(rawBytes))
			copy(fullPayload, headerBytes)
			copy(fullPayload[len(headerBytes):], rawBytes)

			imsas.SendHEPHTTP(httpHeader+"[ZIP Binary Data]", session.LocalIP, session.LocalPort, session.RtpIP, int(chanID), session.CallID)

			sendChunked(dc, fullPayload)
		} else {
			rawContent := string(rawBytes)
			var htmlContent []byte
			if strings.HasPrefix(strings.TrimSpace(rawContent), "{") {
				appId := strings.TrimPrefix(url, fmt.Sprintf("http://%s:%d/dcar/v1/applications/", m.cfg.Services.Dcar.Host, m.cfg.Services.Dcar.Port))
				downloadUrl := fmt.Sprintf("http://%s:%d/dcar/v1/applications/%s/download", dcarHost, dcarPort, appId)
				log.Printf("[MF] BDC: Auto-fetching ZIP from %s", downloadUrl)
				
				dlReq, _ := http.NewRequest("GET", downloadUrl, nil)
				if ifNoneMatch != "" { dlReq.Header.Set("If-None-Match", ifNoneMatch) }
				dlResp, dlErr := m.httpClient.Do(dlReq)
				if dlErr == nil {
					defer dlResp.Body.Close()
					if dlResp.StatusCode == http.StatusNotModified {
						log.Printf("[MF] BDC: Auto-fetch returned 304 Not Modified")
						httpResp := "HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n"
						imsas.SendHEPHTTP(httpResp, session.LocalIP, session.LocalPort, session.RtpIP, int(chanID), session.CallID)
						_ = dc.SendText(httpResp)
						return
					}
					dlEtag := dlResp.Header.Get("ETag")
					dlEtagHeader := ""
					if dlEtag != "" { dlEtagHeader = fmt.Sprintf("ETag: %s\r\n", dlEtag) }

					var dlBuf bytes.Buffer
					_, _ = io.Copy(&dlBuf, dlResp.Body)
					dlBytes := dlBuf.Bytes()
					if len(dlBytes) >= 4 && dlBytes[0] == 0x50 && dlBytes[1] == 0x4B {
						httpHeader := fmt.Sprintf("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n", dlEtagHeader, len(dlBytes))
						headerBytes := []byte(httpHeader)
						fullPayload := make([]byte, len(headerBytes)+len(dlBytes))
						copy(fullPayload, headerBytes)
						copy(fullPayload[len(headerBytes):], dlBytes)
						imsas.SendHEPHTTP(httpHeader+"[ZIP Binary Data]", session.LocalIP, session.LocalPort, session.RtpIP, int(chanID), session.CallID)
						sendChunked(dc, fullPayload)
						return
					}
				}
			}

			htmlContent = rawBytes
			httpResp := fmt.Sprintf("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n%sContent-Length: %d\r\nConnection: close\r\n\r\n%s", etagHeader, len(htmlContent), string(htmlContent))
			imsas.SendHEPHTTP(httpResp, session.LocalIP, session.LocalPort, session.RtpIP, int(chanID), session.CallID)
			if err := dc.SendText(httpResp); err != nil {
				log.Printf("[MF] BDC: Error sending HTML: %v", err)
			}
		}
	})
}

// sendChunked sends a large payload over WebRTC DataChannel by chunking it into 60KB chunks.
func sendChunked(dc *webrtc.DataChannel, payload []byte) {
	chunkSize := 16000
	total := len(payload)
	log.Printf("[MF] BDC: Sending %d bytes in chunks of %d", total, chunkSize)
	for i := 0; i < total; i += chunkSize {
		end := i + chunkSize
		if end > total {
			end = total
		}
		if err := dc.Send(payload[i:end]); err != nil {
			log.Printf("[MF] BDC: Error sending chunk %d-%d: %v", i, end, err)
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	log.Printf("[MF] BDC: Successfully sent %d bytes in %d chunks", total, (total+chunkSize-1)/chunkSize)
}

func (m *MediaFunction) handleADC(session *SessionInfo, dc *webrtc.DataChannel) {
	chanID := uint16(0)
	if dc.ID() != nil {
		chanID = *dc.ID()
	}

	subproto := dc.Label()
	if subproto == "" {
		subproto = dc.Protocol()
	}

	checkUdpProxy := func() bool {
		if subproto == "caro" || subproto == "fun-calling" || subproto == "fun-calling-app" || subproto == "tic-tac-toe-app" {
			return true
		}
		session.mu.Lock()
		defer session.mu.Unlock()
		// UDP_PROXY to DCAS is only for P2A (Peer to AppServer)
		// If it's P2A2P (Peer to Peer via Anchor) or P2P, we just relay between peers, NOT to DCAS!
		return session.ProxyMode == "UDP_PROXY"
	}

	dc.OnOpen(func() {
		log.Printf("[MF] ADC opened for session %s (user=%s, proxyMode=%s, mdc2Protocol=%s, isUdpProxy=%t)",
			session.ID, session.UserID, session.ProxyMode, session.Mdc2Protocol, checkUdpProxy())

		// UDP_PROXY mode: Connect to DCAS via plain TCP (DEF-09: standard MDC2 transport)
		if checkUdpProxy() {
			dcasHost := session.DcasIP
			if dcasHost == "" {
				dcasHost = resolveAndHashHost(m.cfg.Services.Dcas.Host, session.CallID)
			}
			dcasPort := session.DcasPort
			if dcasPort == 0 || dcasPort == m.cfg.Services.Dcas.Port {
				dcasPort = m.cfg.Services.Dcas.WsPort
			}
			callIDWithRoute := fmt.Sprintf("%s@%s", session.CallID, session.LocalIP)

			tcpAddr := fmt.Sprintf("%s:%d", dcasHost, dcasPort)
			log.Printf("[MF] Establishing MDC2 TCP connection to DCAS: %s (stream %d)", tcpAddr, chanID)

			var tcpConn net.Conn
			var err error
			for attempt := 1; attempt <= 3; attempt++ {
				tcpConn, err = net.DialTimeout("tcp", tcpAddr, 5*time.Second)
				if err == nil {
					break
				}
				log.Printf("[MF] DCAS dial attempt %d failed: %v. Retrying in 1s...", attempt, err)
				time.Sleep(1 * time.Second)
			}
			if err != nil {
				log.Printf("[MF] Failed to dial DCAS TCP: %v (MDC2 per TS 29.176 — no fallback)", err)
				return
			}

			// Send handshake header: call_id, session_id, stream_id (newline-delimited)
			handshake := fmt.Sprintf("CALL_ID:%s\nSESSION_ID:%s\nSTREAM_ID:%d\n\n",
				callIDWithRoute, session.ID, chanID)
			if _, err := tcpConn.Write([]byte(handshake)); err != nil {
				log.Printf("[MF] Failed to send MDC2 TCP handshake: %v", err)
				tcpConn.Close()
				return
			}
			log.Printf("[MF] MDC2 TCP handshake sent to DCAS for stream %d", chanID)

			session.mu.Lock()
			if session.tcpConns == nil {
				session.tcpConns = make(map[uint16]net.Conn)
			}
			session.tcpConns[chanID] = tcpConn
			session.mu.Unlock()

			// Read loop: DCAS TCP -> Client WebRTC ADC
			go func() {
				defer func() {
					session.mu.Lock()
					if session.tcpConns != nil {
						if conn, ok := session.tcpConns[chanID]; ok {
							conn.Close()
							delete(session.tcpConns, chanID)
						}
					}
					session.mu.Unlock()
				}()
				lenBuf := make([]byte, 4)
				for {
					if _, err := io.ReadFull(tcpConn, lenBuf); err != nil {
						break
					}
					dataLen := uint32(lenBuf[0])<<24 | uint32(lenBuf[1])<<16 | uint32(lenBuf[2])<<8 | uint32(lenBuf[3])
					payloadBuf := make([]byte, dataLen)
					if _, err := io.ReadFull(tcpConn, payloadBuf); err != nil {
						break
					}
					log.Printf("[MF] TCP -> ADC relay for session %s (stream %d): %s", session.ID, chanID, string(payloadBuf))

					// Trace outgoing ADC message
					srcIP := session.LocalIP
					if srcIP == "" {
						srcIP = "127.0.0.1"
					}
					dstIP := session.RtpIP
					if dstIP == "" {
						dstIP = "127.0.0.1"
					}
					log.Printf("[DATA-CHANNEL-TRACE] ADC Payload (Outgoing/Relayed): Session=%s, StreamID=%d, Msg=%s", session.ID, chanID, string(payloadBuf))
					imsas.SendHEPDataChannel(string(payloadBuf), srcIP, session.LocalPort, dstIP, int(chanID), session.CallID)

					if err := dc.SendText(string(payloadBuf)); err != nil {
						log.Printf("[MF] ADC send closed: %v", err)
						break
					}
				}
			}()
			return
		}

		// P2P mode (default for ADC): no DCAS connection needed, relay handled in OnMessage
		log.Printf("[MF] ADC P2P mode: no DCAS connection for session %s (stream %d)", session.ID, chanID)
	})

	dc.OnMessage(func(msg webrtc.DataChannelMessage) {
		chanID := uint16(0)
		if dc.ID() != nil {
			chanID = *dc.ID()
		}

		// Trace incoming ADC request from Client
		srcIP := session.RtpIP
		if srcIP == "" {
			srcIP = "127.0.0.1"
		}
		dstIP := session.LocalIP
		if dstIP == "" {
			dstIP = "127.0.0.1"
		}
		log.Printf("[DATA-CHANNEL-TRACE] ADC Payload (Incoming): Session=%s, StreamID=%d, Msg=%s", session.ID, chanID, string(msg.Data))
		imsas.SendHEPDataChannel(string(msg.Data), srcIP, int(chanID), dstIP, session.LocalPort, session.CallID)

		// P2P Relay: only when NOT in UDP_PROXY mode (DEF-08)
		if !checkUdpProxy() {
			m.mu.Lock()
			var peerSession *SessionInfo
			for _, s := range m.sessions {
				if s.CallID == session.CallID && s.ID != session.ID {
					peerSession = s
					break
				}
			}
			m.mu.Unlock()

			if peerSession != nil {
				var peerADC *webrtc.DataChannel
				peerSession.mu.Lock()
				if chanID == 1001 && peerSession.ADC2 != nil {
					peerADC = peerSession.ADC2
				} else if peerSession.ADC != nil {
					peerADC = peerSession.ADC
				}
				peerSession.mu.Unlock()

				if peerADC != nil && peerADC.ReadyState() == webrtc.DataChannelStateOpen {
					var sendErr error

					// Trace outgoing P2P ADC message (sent to peerSession)
					peerLocalIP := peerSession.LocalIP
					if peerLocalIP == "" {
						peerLocalIP = "127.0.0.1"
					}
					peerRtpIP := peerSession.RtpIP
					if peerRtpIP == "" {
						peerRtpIP = "127.0.0.1"
					}
					log.Printf("[DATA-CHANNEL-TRACE] ADC Payload (Outgoing/Relayed): Session=%s, StreamID=%d, Msg=%s", peerSession.ID, chanID, string(msg.Data))
					imsas.SendHEPDataChannel(string(msg.Data), peerLocalIP, peerSession.LocalPort, peerRtpIP, int(chanID), peerSession.CallID)

					if msg.IsString {
						sendErr = peerADC.SendText(string(msg.Data))
					} else {
						sendErr = peerADC.Send(msg.Data)
					}
					if sendErr != nil {
						log.Printf("[MF] ❌ ADC P2P relay failed to session %s: %v", peerSession.ID, sendErr)
					} else {
						log.Printf("[MF] 🔄 ADC P2P Relayed %d bytes (binary=%v) from session %s -> %s (stream %d)",
							len(msg.Data), !msg.IsString, session.ID, peerSession.ID, chanID)
					}
				}
			}
		}

		// Simulating 3GPP AC.7.9.2 User Plane Transcoding: transcode incoming SCTP chunks to RTP video for legacy callee
		if session.IsInterworking && session.LinkedSession != nil {
			m.streamTranscodedFrame(session.LinkedSession, msg.Data)
		}

		// UDP_PROXY mode: relay to DCAS via TCP
		if checkUdpProxy() {
			tcpConn, err := m.GetOrCreateDcasConn(session, chanID)
			if err != nil {
				log.Printf("[MF] Failed to get/create DCAS connection: %v", err)
				return
			}

			if tcpConn != nil {
				log.Printf("[MF] ADC -> TCP relay for session %s (stream %d): %s", session.ID, chanID, string(msg.Data))
				// Length-prefix frame: 4-byte big-endian length + data
				dataLen := uint32(len(msg.Data))
				frame := make([]byte, 4+len(msg.Data))
				frame[0] = byte(dataLen >> 24)
				frame[1] = byte(dataLen >> 16)
				frame[2] = byte(dataLen >> 8)
				frame[3] = byte(dataLen)
				copy(frame[4:], msg.Data)
				if _, err := tcpConn.Write(frame); err != nil {
					log.Printf("[MF] ADC write failed to session %s: %v", session.ID, err)
					tcpConn.Close()
					session.mu.Lock()
					delete(session.tcpConns, chanID)
					session.mu.Unlock()
				}
			}
		}

		// Note: ADC never uses WebSocket relay to DCAS. WS relay is only for BDC.
		// ADC uses either TCP proxy (UDP_PROXY) or pure P2P relay (handled above).

		if session.IsHotline && chanID == 1000 {
			var payload struct {
				Action string `json:"action"`
			}
			if err := json.Unmarshal(msg.Data, &payload); err == nil && payload.Action != "" {
				log.Printf("[MF] Hotline session %s selected action: %s. Playing tone...", session.ID, payload.Action)
				go m.PlayHotlineAction(session, payload.Action)
			}
		}
	})

	dc.OnClose(func() {
		chanID := uint16(0)
		if dc.ID() != nil {
			chanID = *dc.ID()
		}
		log.Printf("[MF] ADC (stream %d) closed for session %s", chanID, session.ID)
		session.mu.Lock()
		if session.tcpConns != nil {
			if conn, ok := session.tcpConns[chanID]; ok {
				conn.Close()
				delete(session.tcpConns, chanID)
			}
		}
		if chanID == 1000 {
			session.ADC = nil
		} else if chanID == 1001 {
			session.ADC2 = nil
		}
		session.mu.Unlock()
	})
}

func (m *MediaFunction) LinkSessions(sessionA, sessionB string) error {
	m.mu.RLock()
	sA, okA := m.sessions[sessionA]
	sB, okB := m.sessions[sessionB]
	m.mu.RUnlock()

	if !okA {
		return fmt.Errorf("session A not found: %s", sessionA)
	}
	if !okB {
		return fmt.Errorf("session B not found: %s", sessionB)
	}

	sA.mu.Lock()
	sA.LinkedSession = sB
	sA.mu.Unlock()

	sB.mu.Lock()
	sB.LinkedSession = sA
	sB.mu.Unlock()

	log.Printf("[MF] ✅ Linked Peer Sessions: %s <-> %s", sessionA, sessionB)
	return nil
}

func (m *MediaFunction) SetRemoteAnswer(sessionID, sdpAnswer string) error {
	m.mu.RLock()
	session, ok := m.sessions[sessionID]
	m.mu.RUnlock()

	if !ok {
		return fmt.Errorf("session not found: %s", sessionID)
	}

	mediaInfo := parseSDPMedia(sdpAnswer)
	session.mu.Lock()
	session.RtpIP = mediaInfo.IP
	if session.RtpIP == "" {
		session.RtpIP = "127.0.0.1"
	}
	session.RtpAudioPort = mediaInfo.AudioPort
	session.RtpVideoPort = mediaInfo.VideoPort
	session.mu.Unlock()

	bdcAns, adcAns := splitSDPAnswerN(sdpAnswer, session.adcBlock())

	isADCContext := isAdcContext(session.CallID)
	log.Printf("[MF] SetRemoteAnswer: session=%s call=%s isADCContext=%v bdcAns len=%d adcAns len=%d",
		sessionID, session.CallID, isADCContext, len(bdcAns), len(adcAns))
	if isADCContext {
		if session.BdcPC != nil && session.BdcPC.SignalingState() != webrtc.SignalingStateStable && adcAns != "" {
			cleanAnswer := stripAudioVideoFromSDP(adcAns)
			translatedAnswer := translateSipOfferToWebRtcOffer(cleanAnswer)
			translatedAnswer = rewriteMidToZero(translatedAnswer)
			log.Printf("[MF] SetRemoteAnswer applying to BdcPC (ADC block):\n%s", translatedAnswer)
			answer := webrtc.SessionDescription{
				Type: webrtc.SDPTypeAnswer,
				SDP:  translatedAnswer,
			}
			if err := session.BdcPC.SetRemoteDescription(answer); err != nil {
				return fmt.Errorf("set remote description adc: %w", err)
			}
		}
	} else {
		if session.BdcPC != nil && session.BdcPC.SignalingState() != webrtc.SignalingStateStable {
			cleanAnswer := stripAudioVideoFromSDP(bdcAns)
			translatedAnswer := translateSipOfferToWebRtcOffer(cleanAnswer)
			answer := webrtc.SessionDescription{
				Type: webrtc.SDPTypeAnswer,
				SDP:  translatedAnswer,
			}
			if err := session.BdcPC.SetRemoteDescription(answer); err != nil {
				return fmt.Errorf("set remote description bdc: %w", err)
			}
		}
	}

	// Apply ADC answer to AdcPC when answer contains an ADC m-line and AdcPC is awaiting remote description.
	// This handles the P2A2P re-INVITE flow where the ADC sub-context (-adc) patches the SDP answer
	// into the parent BDC session, whose CallID lacks the -adc suffix (isADCContext=false above).
	if adcAns != "" && session.AdcPC != nil && session.AdcPC.SignalingState() != webrtc.SignalingStateStable {
		cleanAdcAnswer := stripAudioVideoFromSDP(adcAns)
		translatedAdcAnswer := translateSipOfferToWebRtcOffer(cleanAdcAnswer)
		translatedAdcAnswer = rewriteMidToZero(translatedAdcAnswer)
		log.Printf("[MF] SetRemoteAnswer applying to AdcPC:\n%s", translatedAdcAnswer)
		adcAnswer := webrtc.SessionDescription{
			Type: webrtc.SDPTypeAnswer,
			SDP:  translatedAdcAnswer,
		}
		if err := session.AdcPC.SetRemoteDescription(adcAnswer); err != nil {
			return fmt.Errorf("set remote description adc pc: %w", err)
		}
		log.Printf("[MF] ✅ AdcPC remote answer applied for session %s", sessionID)
	}

	log.Printf("[MF] ✅ Remote answer set successfully for session %s (RtpIP=%s, AudioPort=%d, VideoPort=%d)",
		sessionID, session.RtpIP, session.RtpAudioPort, session.RtpVideoPort)
	return nil
}

func (m *MediaFunction) Shutdown() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, s := range m.sessions {
		if s.BdcPC != nil {
			s.BdcPC.Close()
		}
		if s.AdcPC != nil {
			s.AdcPC.Close()
		}
		s.mu.Lock()
		for _, conn := range s.tcpConns {
			conn.Close()
		}
		s.tcpConns = nil
		s.mu.Unlock()
	}
}

// SessionStatus debug info struct
type SessionStatus struct {
	ID        string    `json:"id"`
	UserID    string    `json:"user_id"`
	CallID    string    `json:"call_id"`
	LocalIP   string    `json:"local_ip"`
	LocalPort int       `json:"local_port"`
	ICEState  string    `json:"ice_state"`
	HasBDC    bool      `json:"has_bdc"`
	HasADC    bool      `json:"has_adc"`
	HasLinked bool      `json:"has_linked_session"`
	CreatedAt time.Time `json:"created_at"`
}

func (m *MediaFunction) ListSessions() []SessionStatus {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var statuses []SessionStatus
	for _, s := range m.sessions {
		s.mu.Lock()
		iceState := "unknown"
		if s.BdcPC != nil {
			iceState = s.BdcPC.ICEConnectionState().String()
		}
		st := SessionStatus{
			ID:        s.ID,
			UserID:    s.UserID,
			CallID:    s.CallID,
			LocalIP:   s.LocalIP,
			LocalPort: s.LocalPort,
			ICEState:  iceState,
			HasBDC:    s.BDC != nil,
			HasADC:    s.ADC != nil,
			HasLinked: s.LinkedSession != nil,
			CreatedAt: s.CreatedAt,
		}
		s.mu.Unlock()
		statuses = append(statuses, st)
	}
	return statuses
}

func (m *MediaFunction) CleanupSession(sessionID string) {
	m.mu.Lock()
	session, ok := m.sessions[sessionID]
	if ok {
		delete(m.sessions, sessionID)
		for addr, s := range m.latchedAudioSessions {
			if s.ID == sessionID {
				delete(m.latchedAudioSessions, addr)
			}
		}
		for addr, s := range m.latchedVideoSessions {
			if s.ID == sessionID {
				delete(m.latchedVideoSessions, addr)
			}
		}
	}
	m.mu.Unlock()

	if ok && session != nil {
		session.audioMu.Lock()
		if session.audioCancel != nil {
			close(session.audioCancel)
			session.audioCancel = nil
		}
		session.audioMu.Unlock()

		if session.BdcPC != nil {
			session.BdcPC.Close()
		}
		if session.AdcPC != nil {
			session.AdcPC.Close()
		}
		session.mu.Lock()
		for _, conn := range session.tcpConns {
			conn.Close()
		}
		session.tcpConns = nil
		if session.AudioConn != nil && session.AudioConn != m.audioConn && session.AudioConn != m.audioCalleeConn {
			session.AudioConn.Close()
		}
		if session.VideoConn != nil {
			session.VideoConn.Close()
		}
		session.mu.Unlock()
		log.Printf("[MF] Cleaned up session %s", sessionID)
	}
}

func (m *MediaFunction) GetSession(sessionID string) *SessionInfo {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.sessions[sessionID]
}

func (m *MediaFunction) StopAnnouncementByCallID(callID string) {
	m.mu.Lock()
	var toClean []string
	for sid, s := range m.sessions {
		if s.CallID == callID {
			toClean = append(toClean, sid)
		}
	}
	m.mu.Unlock()

	for _, sid := range toClean {
		m.CleanupSession(sid)
	}
}

func ioCopy(dst *strings.Builder, src interface {
	Read(p []byte) (n int, err error)
}) (written int64, err error) {
	buf := make([]byte, 32*1024)
	for {
		nr, er := src.Read(buf)
		if nr > 0 {
			dst.WriteString(string(buf[:nr]))
			written += int64(nr)
		}
		if er != nil {
			break
		}
	}
	return written, nil
}

func getCoreIP() (string, error) {
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

func getAccessIP() (string, error) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return getCoreIP()
	}
	for _, iface := range ifaces {
		if strings.HasPrefix(iface.Name, "tailscale") {
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
	return getCoreIP()
}

// rewriteSDPPorts thay thế port nội bộ (bindPort) bằng port public (pubPort)
// trong SDP candidate lines và m=application line.
// Giúp điện thoại thật kết nối qua K8s NodePort thay vì port Pod nội bộ.

func getFallbackHTML() []byte {
	return []byte(`<!DOCTYPE html>
<html>
<head><title>IMS DC Mini-App</title></head>
<body style="background:transparent;color:white;font-family:sans-serif;">
  <p>Mini-app loaded (fallback mode)</p>
</body>
</html>`)
}

type dnsCacheEntry struct {
	ips       []string
	expiresAt time.Time
}

var (
	dnsCache   = make(map[string]dnsCacheEntry)
	dnsCacheMu sync.RWMutex
)

// resolveAndHashHost resolves host via DNS, sorts the IPs, and returns a single IP based on hashing the callID.
// If DNS lookup fails or returns no IPs, it falls back to the original host.
func resolveAndHashHost(host, callID string) string {
	var ipStrings []string

	// Check cache
	dnsCacheMu.RLock()
	entry, found := dnsCache[host]
	if found && time.Now().Before(entry.expiresAt) {
		ipStrings = entry.ips
	}
	dnsCacheMu.RUnlock()

	// If not found or expired, resolve DNS
	if ipStrings == nil {
		ips, err := net.LookupIP(host)
		if err == nil && len(ips) > 0 {
			tempIPStrings := make([]string, 0, len(ips))
			for _, ip := range ips {
				if ip.To4() != nil {
					tempIPStrings = append(tempIPStrings, ip.String())
				}
			}
			if len(tempIPStrings) == 0 {
				for _, ip := range ips {
					tempIPStrings = append(tempIPStrings, ip.String())
				}
			}
			sort.Strings(tempIPStrings)
			ipStrings = tempIPStrings

			// Save to cache (10 seconds TTL)
			dnsCacheMu.Lock()
			dnsCache[host] = dnsCacheEntry{
				ips:       ipStrings,
				expiresAt: time.Now().Add(10 * time.Second),
			}
			dnsCacheMu.Unlock()
		}
	}

	if len(ipStrings) == 0 {
		return host
	}

	// Hash the Call-ID using FNV-1a
	h := fnv.New32a()
	_, _ = h.Write([]byte(callID))
	hashVal := h.Sum32()

	chosenIP := ipStrings[hashVal%uint32(len(ipStrings))]
	log.Printf("[MF] Resolved '%s' to %v. Picked '%s' for CallID '%s' (hash=%d)", host, ipStrings, chosenIP, callID, hashVal)
	return chosenIP
}

type MediaInfo struct {
	IP        string
	AudioPort int
	VideoPort int
}

// --- 3GPP TS 29.176 Context Management APIs ---

func isCoreNetwork(sdpText string) bool {
	if sdpText == "" {
		return false
	}
	return !strings.Contains(sdpText, "c=IN IP4 100.")
}

// --- SDP Split & Merge Helpers and Independent Handlers ---
