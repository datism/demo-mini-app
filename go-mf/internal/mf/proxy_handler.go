package mf

import (
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strings"
	"time"

	"github.com/demo/ims-dc-mf/internal/imsas"
	"github.com/pion/webrtc/v3"
)

func (m *MediaFunction) GetOrCreateDcasConn(session *SessionInfo, chanID uint16) (net.Conn, error) {
	session.mu.Lock()
	if session.tcpConns == nil {
		session.tcpConns = make(map[uint16]net.Conn)
	}
	conn, exists := session.tcpConns[chanID]
	session.mu.Unlock()
	if exists {
		return conn, nil
	}

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
		return nil, fmt.Errorf("failed to dial DCAS: %v", err)
	}

	// Send handshake header
	handshake := fmt.Sprintf("CALL_ID:%s\nSESSION_ID:%s\nSTREAM_ID:%d\n\n",
		callIDWithRoute, session.ID, chanID)
	if _, err := tcpConn.Write([]byte(handshake)); err != nil {
		tcpConn.Close()
		return nil, fmt.Errorf("failed to send handshake: %v", err)
	}
	log.Printf("[MF] MDC2 TCP handshake sent to DCAS for stream %d (session %s)", chanID, session.ID)

	session.mu.Lock()
	session.tcpConns[chanID] = tcpConn
	session.mu.Unlock()

	// Read loop: DCAS TCP -> Client WebRTC ADC
	go func(conn net.Conn) {
		defer func() {
			session.mu.Lock()
			if session.tcpConns != nil {
				if c, ok := session.tcpConns[chanID]; ok && c == conn {
					c.Close()
					delete(session.tcpConns, chanID)
				}
			}
			session.mu.Unlock()
		}()

		lenBuf := make([]byte, 4)
		for {
			if _, err := io.ReadFull(conn, lenBuf); err != nil {
				break
			}
			dataLen := uint32(lenBuf[0])<<24 | uint32(lenBuf[1])<<16 | uint32(lenBuf[2])<<8 | uint32(lenBuf[3])
			payloadBuf := make([]byte, dataLen)
			if _, err := io.ReadFull(conn, payloadBuf); err != nil {
				break
			}
			log.Printf("[MF] TCP -> ADC relay for session %s (stream %d): %s", session.ID, chanID, string(payloadBuf))

			// Locate WebRTC data channel dynamically
			var dc *webrtc.DataChannel
			session.mu.Lock()
			if chanID == 1000 {
				dc = session.ADC
			} else if chanID == 1001 {
				dc = session.ADC2
			}
			if dc == nil {
				if session.ADC != nil {
					dc = session.ADC
				} else {
					dc = session.ADC2
				}
			}
			session.mu.Unlock()

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

			if dc != nil && dc.ReadyState() == webrtc.DataChannelStateOpen {
				if err := dc.SendText(string(payloadBuf)); err != nil {
					log.Printf("[MF] ADC send error: %v", err)
				}
			} else {
				log.Printf("[MF] ADC data channel for stream %d not ready yet (dc=%v)", chanID, dc)
			}
		}
	}(tcpConn)

	return tcpConn, nil
}

func (m *MediaFunction) initSessionAdcPC(session *SessionInfo, isAnswerer bool) error {
	s := webrtc.SettingEngine{}
	if klw, err := os.OpenFile("/app/config/adc_ssl_keys.log", os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0666); err == nil {
		s.SetDTLSKeyLogWriter(klw)
	}
	s.SetInterfaceFilter(func(name string) bool {
		return strings.HasPrefix(name, "tailscale") || strings.HasPrefix(name, "eth") || strings.HasPrefix(name, "en") || name == "net1"
	})
	s.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})

	ringingTimeout := m.getRingingTimeout()
	failedTimeout := 90 * time.Second
	if ringingTimeout >= failedTimeout {
		failedTimeout = ringingTimeout + 30*time.Second
	}
	s.SetICETimeouts(ringingTimeout, failedTimeout, 2*time.Second)
	s.SetICEMulticastDNSMode(0)
	s.SetLite(true)

	// IMPORTANT: Use shared ADC UDPMux on port 40005 to ensure connectivity through NodePort and Tailscale DNAT.
	// Previous issues with UDPMux were actually caused by SIP routing loops in b2bua_invite.go, which have now been fixed.
	if m.adcUdpMux != nil {
		s.SetICEUDPMux(m.adcUdpMux)
	}

	if isAnswerer {
		_ = s.SetAnsweringDTLSRole(webrtc.DTLSRoleServer)
	}

	mediaEngine, err := createCustomMediaEngine()
	if err != nil {
		return err
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
	adcPC, err := api.NewPeerConnection(config)
	if err != nil {
		return err
	}

	session.AdcPC = adcPC

	adcID := uint16(1000)
	adcNegotiated := true
	protoWhiteboard := "whiteboard"
	adcChannel, err := adcPC.CreateDataChannel("app-dc", &webrtc.DataChannelInit{
		ID:         &adcID,
		Negotiated: &adcNegotiated,
		Protocol:   &protoWhiteboard,
	})
	if err != nil {
		adcPC.Close()
		return err
	}

	adcID2 := uint16(1001)
	protoEmoji := "emoji"
	adcChannel2, err := adcPC.CreateDataChannel("app-dc-2", &webrtc.DataChannelInit{
		ID:         &adcID2,
		Negotiated: &adcNegotiated,
		Protocol:   &protoEmoji,
	})
	if err != nil {
		adcPC.Close()
		return err
	}

	session.ADC = adcChannel
	session.ADC2 = adcChannel2

	m.registerAdcHandlers(session)
	m.handleADC(session, adcChannel)
	m.handleADC(session, adcChannel2)

	adcPC.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		log.Printf("[MF] Session %s AdcPC ICE state: %s", session.ID, state.String())
	})

	return nil
}

func (m *MediaFunction) registerBdcHandlers(session *SessionInfo) {
	session.BdcPC.OnTrack(func(track *webrtc.TrackRemote, receiver *webrtc.RTPReceiver) {
		log.Printf("[MF] BdcPC OnTrack received from user %s: kind=%s, id=%s", session.UserID, track.Kind().String(), track.ID())
		go m.forwardTrackRTP(session, track)
	})

	session.BdcPC.OnDataChannel(func(dc *webrtc.DataChannel) {
		id := uint16(0)
		if dc.ID() != nil {
			id = *dc.ID()
		}
		proto := dc.Protocol()

		isADCContext := strings.HasSuffix(session.CallID, "-adc")
		if isADCContext {
			if id == 1000 || id == 1001 {
				session.mu.Lock()
				if id == 1000 {
					session.ADC = dc
				} else {
					session.ADC2 = dc
				}
				session.mu.Unlock()
				m.handleADC(session, dc)
			}
		} else {
			if id == 10 || proto == "http" || id < 1000 {
				session.mu.Lock()
				session.BDC = dc
				session.mu.Unlock()
				m.handleBDC(session, dc)
			}
		}
	})
}

func (m *MediaFunction) registerAdcHandlers(session *SessionInfo) {
	session.AdcPC.OnDataChannel(func(dc *webrtc.DataChannel) {
		id := uint16(0)
		if dc.ID() != nil {
			id = *dc.ID()
		}
		label := dc.Label()

		if id == 1000 || label == "app-dc" {
			session.mu.Lock()
			session.ADC = dc
			session.mu.Unlock()
			m.handleADC(session, dc)
		} else if id == 1001 || label == "app-dc-2" {
			session.mu.Lock()
			session.ADC2 = dc
			session.mu.Unlock()
			m.handleADC(session, dc)
		}
	})
}
