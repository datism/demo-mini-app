package mf

import (
	"log"
	"net"
	"strconv"
	"time"

	"github.com/demo/ims-dc-mf/internal/imsas"
	"github.com/pion/webrtc/v3"
)

func (m *MediaFunction) forwardTrackRTP(session *SessionInfo, track *webrtc.TrackRemote) {
	var linkedSession *SessionInfo
	for i := 0; i < 30; i++ {
		session.mu.Lock()
		linkedSession = session.LinkedSession
		session.mu.Unlock()
		if linkedSession != nil {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}

	if linkedSession == nil {
		log.Printf("[MF] forwardTrackRTP: Timeout waiting for linked session of %s", session.ID)
		return
	}

	log.Printf("[MF] forwardTrackRTP: Relay track %s (%s) from %s -> %s",
		track.ID(), track.Kind().String(), session.UserID, linkedSession.UserID)

	for {
		pkt, _, err := track.ReadRTP()
		if err != nil {
			break
		}

		linkedSession.mu.Lock()
		var targetTrack *webrtc.TrackLocalStaticRTP
		var plainConn *net.UDPConn
		var plainIP string
		var plainPort int
		if track.Kind() == webrtc.RTPCodecTypeAudio {
			targetTrack = linkedSession.AudioTrack
			plainConn = linkedSession.AudioConn
			plainIP = linkedSession.RtpIP
			plainPort = linkedSession.RtpAudioPort
			pkt.Header.PayloadType = 8
		} else if track.Kind() == webrtc.RTPCodecTypeVideo {
			targetTrack = linkedSession.VideoTrack
			plainConn = linkedSession.VideoConn
			plainIP = linkedSession.RtpIP
			plainPort = linkedSession.RtpVideoPort
			pkt.Header.PayloadType = 97
		}
		linkedSession.mu.Unlock()

		// Write via WebRTC track (for WebRTC-connected peers)
		if targetTrack != nil {
			_ = targetTrack.WriteRTP(pkt)
		}

		// Also write via plain RTP UDP (for SIP/RTP phones like Android)
		if plainConn != nil && plainIP != "" && plainPort > 0 {
			rawPkt, err := pkt.Marshal()
			if err == nil {
				targetAddr := &net.UDPAddr{IP: net.ParseIP(plainIP), Port: plainPort}
				_, _ = plainConn.WriteToUDP(rawPkt, targetAddr)
			}
		}
	}
}

func (m *MediaFunction) listenPlainRTP(session *SessionInfo, conn *net.UDPConn, mediaType string) {
	buf := make([]byte, 2048)
	for {
		n, addr, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if addr != nil {
			session.mu.Lock()
			session.RtpIP = addr.IP.String()
			if mediaType == "audio" {
				session.RtpAudioPort = addr.Port
			} else {
				session.RtpVideoPort = addr.Port
			}
			linked := session.LinkedSession
			session.mu.Unlock()

			if linked != nil {
				linked.mu.Lock()
				linkedIP := linked.RtpIP
				var linkedPort int
				if mediaType == "audio" {
					linkedPort = linked.RtpAudioPort
				} else {
					linkedPort = linked.RtpVideoPort
				}
				var linkedConn *net.UDPConn
				if mediaType == "audio" {
					linkedConn = linked.AudioConn
				} else {
					linkedConn = linked.VideoConn
				}
				linked.mu.Unlock()

				if linkedIP != "" && linkedPort > 0 && linkedConn != nil {
					targetAddr := &net.UDPAddr{IP: net.ParseIP(linkedIP), Port: linkedPort}
					_, _ = linkedConn.WriteToUDP(buf[:n], targetAddr)
				}
			}
		}
	}
}

func (m *MediaFunction) runGlobalRtpForwarder(conn *net.UDPConn, peerConn *net.UDPConn, mediaType string, targetMap map[string]*SessionInfo) {
	buf := make([]byte, 65535)
	packetCount := 0

	localPort := 0
	if udpAddr, ok := conn.LocalAddr().(*net.UDPAddr); ok {
		localPort = udpAddr.Port
	}

	log.Printf("[MF] Global %s RTP forwarder started, listening on local port: %d", mediaType, localPort)

	for {
		n, addr, err := conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("[MF] Global %s RTP forwarder exit: %v", mediaType, err)
			return
		}

		senderAddrStr := addr.String()

		m.mu.Lock()

		activeSession := targetMap[senderAddrStr]
		if activeSession != nil {
			stillActive := false
			for _, s := range m.sessions {
				if s.ID == activeSession.ID {
					stillActive = true
					break
				}
			}
			if !stillActive {
				log.Printf("[MF] Global %s RTP: Removing stale session %s from latched cache", mediaType, activeSession.ID)
				delete(targetMap, senderAddrStr)
				activeSession = nil
			}
		}

		if activeSession == nil {
			senderIP := addr.IP.String()
			for _, sess := range m.sessions {
				sess.mu.Lock()
				sessIP := sess.RtpIP
				sessAudioPort := sess.LocalAudioPort
				sessVideoPort := sess.LocalVideoPort
				sess.mu.Unlock()

				if mediaType == "audio" && sessAudioPort != localPort {
					continue
				}
				if mediaType == "video" && sessVideoPort != localPort {
					continue
				}
				if sessIP != "" && sessIP != senderIP {
					continue
				}

				alreadyLatched := false
				for _, s := range targetMap {
					if s.ID == sess.ID {
						alreadyLatched = true
						break
					}
				}
				if !alreadyLatched {
					activeSession = sess
					targetMap[senderAddrStr] = sess
					log.Printf("[MF] Global RTP: Latched session %s on address %s (%s, port %d)", sess.ID, senderAddrStr, mediaType, localPort)
					break
				}
			}
		}
		m.mu.Unlock()

		if activeSession != nil {
			activeSession.mu.Lock()
			linked := activeSession.LinkedSession
			activeSession.mu.Unlock()

			if linked != nil {
				linked.mu.Lock()
				linkedIP := linked.RtpIP
				var linkedPort int
				if mediaType == "audio" {
					linkedPort = linked.RtpAudioPort
				} else {
					linkedPort = linked.RtpVideoPort
				}
				linked.mu.Unlock()

				if linkedIP != "" && linkedPort > 0 {
					var targetAddr *net.UDPAddr
					var foundLatched bool

					m.mu.RLock()
					for addrStr, sess := range targetMap {
						if sess.ID == linked.ID {
							parsedAddr, err := net.ResolveUDPAddr("udp", addrStr)
							if err == nil {
								targetAddr = parsedAddr
								foundLatched = true
								break
							}
						}
					}
					m.mu.RUnlock()

					if !foundLatched {
						targetAddr = &net.UDPAddr{IP: net.ParseIP(linkedIP), Port: linkedPort}
					}

					var sendConn *net.UDPConn
					if mediaType == "audio" {
						if linked.LocalAudioPort == m.publicPort+2 {
							sendConn = m.audioConn
						} else if m.audioCalleeConn != nil {
							sendConn = m.audioCalleeConn
						} else {
							sendConn = conn
						}
					} else {
						if linked.LocalVideoPort == m.publicPort+4 {
							sendConn = m.videoConn
						} else if m.videoCalleeConn != nil {
							sendConn = m.videoCalleeConn
						} else {
							sendConn = conn
						}
					}

					isRTCP := n >= 4 && buf[1] >= 192 && buf[1] <= 223
					if isRTCP && targetAddr != nil {
						srcIP, srcPortStr, _ := net.SplitHostPort(senderAddrStr)
						srcPort, _ := strconv.Atoi(srcPortStr)
						imsas.SendHEPRTCP(buf[:n], srcIP, srcPort, targetAddr.IP.String(), targetAddr.Port, activeSession.CallID)
					}
					sendConn = peerConn
					if sendConn == nil {
						sendConn = conn
					}

					if targetAddr != nil {
						sendConn.WriteToUDP(buf[:n], targetAddr)
					}

					packetCount++
					if packetCount%200 == 0 {
						destStr := targetAddr.String()
						if foundLatched {
							destStr += " (latched)"
						}
						log.Printf("[MF] Global RTP Relay: forwarded %s packet from %s -> %s (len=%d)",
							mediaType, senderAddrStr, destStr, n)
					}
				}
			}
		}
	}
}
