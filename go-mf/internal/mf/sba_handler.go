package mf

import (
	"encoding/json"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/demo/ims-dc-mf/internal/sba"
	"github.com/google/uuid"
	"github.com/pion/webrtc/v3"
)

func (m *MediaFunction) CreateMediaContext(ctx *sba.MediaContext) (*sba.MediaContext, error) {
	contextID := ctx.ContextID
	if contextID == "" {
		contextID = "ctx-" + uuid.New().String()
	}

	m.mu.Lock()
	existingCtx, exists := m.contexts[contextID]
	m.mu.Unlock()

	if exists {
		log.Printf("[MF] CreateMediaContext: context %s already exists. Separating ADC transport...", contextID)

		m.mu.Lock()
		sessA := m.sessions[existingCtx.SessionA]
		sessB := m.sessions[existingCtx.SessionB]
		m.mu.Unlock()

		var sdpOfferA, sdpOfferB string
		var dcMedia *sba.DcMedia

		termA := ctx.Terminations[0]
		if len(termA.Medias) > 0 {
			sdpOfferA = termA.Medias[0].SDP
			for _, m := range termA.Medias {
				if m.DcMedia != nil {
					if m.DcMedia.MediaProxyConfig == "UDP_PROXY" || dcMedia == nil {
						dcMedia = m.DcMedia
					}
				}
			}
		}

		if len(ctx.Terminations) > 1 {
			termB := ctx.Terminations[1]
			if len(termB.Medias) > 0 {
				sdpOfferB = termB.Medias[0].SDP
				for _, m := range termB.Medias {
					if m.DcMedia != nil {
						if m.DcMedia.MediaProxyConfig == "UDP_PROXY" || dcMedia == nil {
							dcMedia = m.DcMedia
						}
					}
				}
			}
		}

		if dcMedia != nil {
			if sessA != nil {
				sessA.mu.Lock()
				sessA.ProxyMode = dcMedia.MediaProxyConfig
				if dcMedia.Mdc2Info != nil {
					sessA.Mdc2Protocol = dcMedia.Mdc2Info.Mdc2Protocol
					if dcMedia.Mdc2Info.RemoteMdc2Endpoint != nil {
						sessA.DcasIP = dcMedia.Mdc2Info.RemoteMdc2Endpoint.IP
						sessA.DcasPort = dcMedia.Mdc2Info.RemoteMdc2Endpoint.Port
					}
				}
				sessA.mu.Unlock()
			}
			if sessB != nil {
				sessB.mu.Lock()
				sessB.ProxyMode = dcMedia.MediaProxyConfig
				if dcMedia.Mdc2Info != nil {
					sessB.Mdc2Protocol = dcMedia.Mdc2Info.Mdc2Protocol
					if dcMedia.Mdc2Info.RemoteMdc2Endpoint != nil {
						sessB.DcasIP = dcMedia.Mdc2Info.RemoteMdc2Endpoint.IP
						sessB.DcasPort = dcMedia.Mdc2Info.RemoteMdc2Endpoint.Port
					}
				}
				sessB.mu.Unlock()
			}
		}

		if sessA != nil {
			sessA.mu.Lock()
			if sessA.AdcPC != nil {
				log.Printf("[MF] Recreating AdcPC for session A: closing old PeerConnection")
				_ = sessA.AdcPC.Close()
				sessA.AdcPC = nil
				sessA.mu.Unlock()
				// Allow UDPMux to fully release old ICE ufrag/DTLS state before re-creating
				time.Sleep(500 * time.Millisecond)
				sessA.mu.Lock()
			}
			isAnswererA := (sdpOfferA != "")
			if err := m.initSessionAdcPC(sessA, isAnswererA); err != nil {
				log.Printf("[MF] Failed to initialize AdcPC for session A: %v", err)
			}
			if sessA.AdcPC != nil {
				if sdpOfferA != "" {
					_, adcOffer := splitSDPAnswer(sdpOfferA)
					if adcOffer != "" {
						cleanOffer := stripAudioVideoFromSDP(adcOffer)
						translatedOffer := translateSipOfferToWebRtcOffer(cleanOffer)
						translatedOffer = rewriteMidToZero(translatedOffer)
						offer := webrtc.SessionDescription{
							Type: webrtc.SDPTypeOffer,
							SDP:  translatedOffer,
						}
						if err := sessA.AdcPC.SetRemoteDescription(offer); err == nil {
							answer, err2 := sessA.AdcPC.CreateAnswer(nil)
							if err2 == nil {
								gatherComplete := webrtc.GatheringCompletePromise(sessA.AdcPC)
								_ = sessA.AdcPC.SetLocalDescription(answer)
								<-gatherComplete

								sessA.AdcLocalPort = extractPortFromSDP(sessA.AdcPC.LocalDescription().SDP, sessA.LocalIP)
								cleanSDP := strings.ReplaceAll(sessA.AdcPC.LocalDescription().SDP, "a=setup:active", "a=setup:passive")
								log.Printf("[MF] AdcPC Local SDP for Session A (LocalPort=%d):\n%s", sessA.AdcLocalPort, cleanSDP)
								iceParams, dtlsFingerprint, err3 := extractICEDTLSFromSDP(cleanSDP)
								if err3 == nil {
									sessA.AdcICEParams = iceParams
									sessA.AdcDTLSParams = cleanSDP
									sessA.AdcFingerprint = dtlsFingerprint
								}
							}
						} else {
							log.Printf("[MF] Failed to set remote description on AdcPC for session A: %v", err)
						}
					}
				} else {
					offer, err := sessA.AdcPC.CreateOffer(nil)
					if err == nil {
						gatherComplete := webrtc.GatheringCompletePromise(sessA.AdcPC)
						_ = sessA.AdcPC.SetLocalDescription(offer)
						<-gatherComplete

						sessA.AdcLocalPort = extractPortFromSDP(sessA.AdcPC.LocalDescription().SDP, sessA.LocalIP)
						iceParams, dtlsFingerprint, err2 := extractICEDTLSFromSDP(sessA.AdcPC.LocalDescription().SDP)
						if err2 == nil {
							sessA.AdcICEParams = iceParams
							sessA.AdcDTLSParams = sessA.AdcPC.LocalDescription().SDP
							sessA.AdcFingerprint = dtlsFingerprint
						}
					}
				}
			}
			sessA.mu.Unlock()

			if sessA.ProxyMode == "UDP_PROXY" {
				go func(s *SessionInfo) {
					log.Printf("[MF] Pre-connecting MDC2 TCP to DCAS for session A %s", s.ID)
					_, _ = m.GetOrCreateDcasConn(s, 1000)
					_, _ = m.GetOrCreateDcasConn(s, 1001)
				}(sessA)
			}
		}

		if sessB != nil {
			sessB.mu.Lock()
			if sessB.AdcPC != nil {
				log.Printf("[MF] Recreating AdcPC for session B: closing old PeerConnection")
				_ = sessB.AdcPC.Close()
				sessB.AdcPC = nil
				sessB.mu.Unlock()
				// Allow UDPMux to fully release old ICE ufrag/DTLS state before re-creating
				time.Sleep(500 * time.Millisecond)
				sessB.mu.Lock()
			}
			isAnswererB := (sdpOfferB != "")
			if err := m.initSessionAdcPC(sessB, isAnswererB); err != nil {
				log.Printf("[MF] Failed to initialize AdcPC for session B: %v", err)
			}
			if sessB.AdcPC != nil {
				if sdpOfferB != "" {
					_, adcOffer := splitSDPAnswer(sdpOfferB)
					if adcOffer != "" {
						cleanOffer := stripAudioVideoFromSDP(adcOffer)
						translatedOffer := translateSipOfferToWebRtcOffer(cleanOffer)
						translatedOffer = rewriteMidToZero(translatedOffer)
						offer := webrtc.SessionDescription{
							Type: webrtc.SDPTypeOffer,
							SDP:  translatedOffer,
						}
						if err := sessB.AdcPC.SetRemoteDescription(offer); err == nil {
							answer, err2 := sessB.AdcPC.CreateAnswer(nil)
							if err2 == nil {
								gatherComplete := webrtc.GatheringCompletePromise(sessB.AdcPC)
								_ = sessB.AdcPC.SetLocalDescription(answer)
								<-gatherComplete

								sessB.AdcLocalPort = extractPortFromSDP(sessB.AdcPC.LocalDescription().SDP, sessB.LocalIP)
								cleanSDP := strings.ReplaceAll(sessB.AdcPC.LocalDescription().SDP, "a=setup:active", "a=setup:passive")
								log.Printf("[MF] AdcPC Local SDP for Session B (LocalPort=%d):\n%s", sessB.AdcLocalPort, cleanSDP)
								iceParams, dtlsFingerprint, err3 := extractICEDTLSFromSDP(cleanSDP)
								if err3 == nil {
									sessB.AdcICEParams = iceParams
									sessB.AdcDTLSParams = cleanSDP
									sessB.AdcFingerprint = dtlsFingerprint
								}
							}
						} else {
							log.Printf("[MF] Failed to set remote description on AdcPC for session B: %v", err)
						}
					}
				} else {
					offer, err := sessB.AdcPC.CreateOffer(nil)
					if err == nil {
						gatherComplete := webrtc.GatheringCompletePromise(sessB.AdcPC)
						_ = sessB.AdcPC.SetLocalDescription(offer)
						<-gatherComplete

						sessB.AdcLocalPort = extractPortFromSDP(sessB.AdcPC.LocalDescription().SDP, sessB.LocalIP)
						iceParams, dtlsFingerprint, err2 := extractICEDTLSFromSDP(sessB.AdcPC.LocalDescription().SDP)
						if err2 == nil {
							sessB.AdcICEParams = iceParams
							sessB.AdcDTLSParams = sessB.AdcPC.LocalDescription().SDP
							sessB.AdcFingerprint = dtlsFingerprint
						}
					}
				}
			}
			sessB.mu.Unlock()

			if sessB.ProxyMode == "UDP_PROXY" {
				go func(s *SessionInfo) {
					log.Printf("[MF] Pre-connecting MDC2 TCP to DCAS for session B %s", s.ID)
					_, _ = m.GetOrCreateDcasConn(s, 1000)
					_, _ = m.GetOrCreateDcasConn(s, 1001)
				}(sessB)
			}
		}

		// Reconstruct and return terminations with separate BDC and ADC MediaItems
		resTerminations := []sba.Termination{}
		if sessA != nil {
			setupA_BDC := "active"
			if sessA.BdcPC != nil && sessA.BdcPC.LocalDescription() != nil {
				setupA_BDC = extractSetupRoleFromSDP(sessA.BdcPC.LocalDescription().SDP)
			}
			setupA_ADC := "actpass"
			if sdpOfferA != "" {
				setupA_ADC = "passive"
			} else if sessA.AdcPC != nil && sessA.AdcPC.LocalDescription() != nil {
				setupA_ADC = extractSetupRoleFromSDP(sessA.AdcPC.LocalDescription().SDP)
			}

			medias := []sba.MediaItem{
				{
					MediaID:           "audio",
					MediaResourceType: "RTP",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessA.LocalIP,
						Port: sessA.LocalAudioPort,
					},
				},
				{
					MediaID:           "video",
					MediaResourceType: "RTP",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessA.LocalIP,
						Port: sessA.LocalVideoPort,
					},
				},
				{
					MediaID:           "1",
					MediaResourceType: "DC",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessA.LocalIP,
						Port: sessA.BdcLocalPort,
					},
					LocalDcEndpoint: &sba.DcEndpoint{
						SctpPort:      sessA.BdcLocalPort,
						Fingerprint:   fmt.Sprintf("%s %s", sessA.BdcFingerprint.Algorithm, sessA.BdcFingerprint.Value),
						SecuritySetup: setupA_BDC,
						IceUfrag:      sessA.BdcICEParams.UsernameFragment,
						IcePwd:        sessA.BdcICEParams.Password,
					},
				},
			}
			if sessA.AdcLocalPort != 0 {
				medias = append(medias, sba.MediaItem{
					MediaID:           "adc-media",
					MediaResourceType: "DC",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessA.LocalIP,
						Port: sessA.AdcLocalPort,
					},
					LocalDcEndpoint: &sba.DcEndpoint{
						SctpPort:      sessA.AdcLocalPort,
						Fingerprint:   fmt.Sprintf("%s %s", sessA.AdcFingerprint.Algorithm, sessA.AdcFingerprint.Value),
						SecuritySetup: setupA_ADC,
						IceUfrag:      sessA.AdcICEParams.UsernameFragment,
						IcePwd:        sessA.AdcICEParams.Password,
					},
				})
			}
			resTerminations = append(resTerminations, sba.Termination{
				TerminationID: "term-caller-a",
				Medias:        medias,
			})
		}
		if sessB != nil {
			setupB_BDC := "active"
			if sessB.BdcPC != nil && sessB.BdcPC.LocalDescription() != nil {
				setupB_BDC = extractSetupRoleFromSDP(sessB.BdcPC.LocalDescription().SDP)
			}
			setupB_ADC := "actpass"
			if sdpOfferB != "" {
				setupB_ADC = "passive"
			} else if sessB.AdcPC != nil && sessB.AdcPC.LocalDescription() != nil {
				setupB_ADC = extractSetupRoleFromSDP(sessB.AdcPC.LocalDescription().SDP)
			}

			medias := []sba.MediaItem{
				{
					MediaID:           "audio",
					MediaResourceType: "RTP",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessB.LocalIP,
						Port: sessB.LocalAudioPort,
					},
				},
				{
					MediaID:           "video",
					MediaResourceType: "RTP",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessB.LocalIP,
						Port: sessB.LocalVideoPort,
					},
				},
				{
					MediaID:           "3",
					MediaResourceType: "DC",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessB.LocalIP,
						Port: sessB.BdcLocalPort,
					},
					LocalDcEndpoint: &sba.DcEndpoint{
						SctpPort:      sessB.BdcLocalPort,
						Fingerprint:   fmt.Sprintf("%s %s", sessB.BdcFingerprint.Algorithm, sessB.BdcFingerprint.Value),
						SecuritySetup: setupB_BDC,
						IceUfrag:      sessB.BdcICEParams.UsernameFragment,
						IcePwd:        sessB.BdcICEParams.Password,
					},
				},
			}
			if sessB.AdcLocalPort != 0 {
				localSDP := mergeSDPOffers(sessB.BdcPC.LocalDescription().SDP, sessB.AdcPC.LocalDescription().SDP)
				localSDP = rewriteSDPPorts(localSDP, m.udpPort, m.publicPort)
				localSDP = rewriteAdcPortInSDP(localSDP, sessB.LocalIP, sessB.AdcLocalPort)

				medias = append(medias, sba.MediaItem{
					MediaID:           "adc-media",
					MediaResourceType: "DC",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessB.LocalIP,
						Port: sessB.AdcLocalPort,
					},
					LocalDcEndpoint: &sba.DcEndpoint{
						SctpPort:      sessB.AdcLocalPort,
						Fingerprint:   fmt.Sprintf("%s %s", sessB.AdcFingerprint.Algorithm, sessB.AdcFingerprint.Value),
						SecuritySetup: setupB_ADC,
						IceUfrag:      sessB.AdcICEParams.UsernameFragment,
						IcePwd:        sessB.AdcICEParams.Password,
					},
					SDP: localSDP,
				})
			}
			resTerminations = append(resTerminations, sba.Termination{
				TerminationID: "term-callee-b",
				Medias:        medias,
			})
		}

		return &sba.MediaContext{
			ContextID:    contextID,
			Terminations: resTerminations,
		}, nil
	}

	log.Printf("[MF] CreateMediaContext context=%s", contextID)

	cinfo := &ContextInfo{
		ContextID: contextID,
	}

	m.mu.Lock()
	m.contexts[contextID] = cinfo
	m.mu.Unlock()

	resTerminations := []sba.Termination{}

	isHotline := false
	if len(ctx.Terminations) == 1 {
		term := ctx.Terminations[0]
		if len(term.Medias) > 0 && term.Medias[0].DcMedia != nil && term.Medias[0].DcMedia.MediaProxyConfig == "HTTP_PROXY" {
			isHotline = true
		}
	}

	if isHotline {
		term := ctx.Terminations[0]
		mItem := term.Medias[0]

		log.Printf("[MF] Creating Hotline Context: context=%s", contextID)
		isCoreA := ctx.IsCoreNetwork
		resp, err := m.AllocateHotlineSession("hotline-caller", contextID, mItem.SDP, isCoreA)
		if err != nil {
			return nil, err
		}

		m.mu.Lock()
		cinfo.SessionA = resp.SessionID
		m.mu.Unlock()

		termID := term.TerminationID
		if termID == "" {
			termID = "term-" + uuid.New().String()
		}

		resTerm := sba.Termination{
			TerminationID: termID,
			Medias: []sba.MediaItem{
				{
					MediaID:           mItem.MediaID,
					MediaResourceType: "DC",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   resp.IP,
						Port: resp.Port,
					},
					LocalDcEndpoint: &sba.DcEndpoint{
						SctpPort:      resp.Port,
						Fingerprint:   resp.Fingerprint,
						SecuritySetup: resp.SetupRole,
						IceUfrag:      resp.ICEUfrag,
						IcePwd:        resp.ICEPwd,
					},
				},
			},
		}
		resTerminations = append(resTerminations, resTerm)

	} else if isAdcContext(contextID) {
		// ADC Sub-context: find parent BDC context, reuse existing sessions,
		// create separate AdcPC PeerConnections on ephemeral ports.
		parentContextID := strings.TrimSuffix(contextID, "-adc")
		log.Printf("[MF] Creating ADC Sub-context: context=%s, parent=%s", contextID, parentContextID)

		m.mu.Lock()
		parentCtx, parentExists := m.contexts[parentContextID]
		m.mu.Unlock()

		if !parentExists {
			return nil, fmt.Errorf("parent BDC context not found: %s", parentContextID)
		}

		m.mu.Lock()
		sessA := m.sessions[parentCtx.SessionA]
		sessB := m.sessions[parentCtx.SessionB]
		m.mu.Unlock()

		if sessA == nil {
			return nil, fmt.Errorf("parent session A not found for ADC context: %s", contextID)
		}

		// Point ADC context to parent's sessions (ADC reuses BDC sessions but adds AdcPC)
		m.mu.Lock()
		cinfo.SessionA = parentCtx.SessionA
		cinfo.SessionB = parentCtx.SessionB
		m.mu.Unlock()

		// Extract DcMedia and SDP offers from terminations
		var sdpOfferA, sdpOfferB string
		var dcMedia *sba.DcMedia

		termA := ctx.Terminations[0]
		if len(termA.Medias) > 0 {
			sdpOfferA = termA.Medias[0].SDP
			for _, m := range termA.Medias {
				if m.DcMedia != nil {
					if m.DcMedia.MediaProxyConfig == "UDP_PROXY" || dcMedia == nil {
						dcMedia = m.DcMedia
					}
				}
			}
		}

		if len(ctx.Terminations) > 1 {
			termB := ctx.Terminations[1]
			if len(termB.Medias) > 0 {
				sdpOfferB = termB.Medias[0].SDP
				for _, m := range termB.Medias {
					if m.DcMedia != nil {
						if m.DcMedia.MediaProxyConfig == "UDP_PROXY" || dcMedia == nil {
							dcMedia = m.DcMedia
						}
					}
				}
			}
		}

		// Set ProxyMode and DCAS info on existing sessions from DcMedia
		if dcMedia != nil {
			if sessA != nil {
				sessA.mu.Lock()
				sessA.ProxyMode = dcMedia.MediaProxyConfig
				if dcMedia.Mdc2Info != nil {
					sessA.Mdc2Protocol = dcMedia.Mdc2Info.Mdc2Protocol
					if dcMedia.Mdc2Info.RemoteMdc2Endpoint != nil {
						sessA.DcasIP = dcMedia.Mdc2Info.RemoteMdc2Endpoint.IP
						sessA.DcasPort = dcMedia.Mdc2Info.RemoteMdc2Endpoint.Port
					}
				} else if dcMedia.Mdc2EndpointInfo != nil {
					sessA.Mdc2Protocol = dcMedia.Mdc2EndpointInfo.Mdc2Protocol
					if dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs != nil {
						sessA.DcasIP = dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs.IP
						sessA.DcasPort = dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs.Port
					}
				}
				sessA.mu.Unlock()
			}
			if sessB != nil {
				sessB.mu.Lock()
				sessB.ProxyMode = dcMedia.MediaProxyConfig
				if dcMedia.Mdc2Info != nil {
					sessB.Mdc2Protocol = dcMedia.Mdc2Info.Mdc2Protocol
					if dcMedia.Mdc2Info.RemoteMdc2Endpoint != nil {
						sessB.DcasIP = dcMedia.Mdc2Info.RemoteMdc2Endpoint.IP
						sessB.DcasPort = dcMedia.Mdc2Info.RemoteMdc2Endpoint.Port
					}
				} else if dcMedia.Mdc2EndpointInfo != nil {
					sessB.Mdc2Protocol = dcMedia.Mdc2EndpointInfo.Mdc2Protocol
					if dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs != nil {
						sessB.DcasIP = dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs.IP
						sessB.DcasPort = dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs.Port
					}
				}
				sessB.mu.Unlock()
			}
		}

		// Initialize AdcPC on session A
		if sessA != nil {
			sessA.mu.Lock()
			isAnswererA := (sdpOfferA != "")
			if err := m.initSessionAdcPC(sessA, isAnswererA); err != nil {
				sessA.mu.Unlock()
				return nil, fmt.Errorf("failed to init AdcPC for session A: %w", err)
			}
			if sessA.AdcPC != nil {
				if sdpOfferA != "" {
					_, adcOffer := splitSDPAnswer(sdpOfferA)
					if adcOffer != "" {
						cleanOffer := stripAudioVideoFromSDP(adcOffer)
						translatedOffer := translateSipOfferToWebRtcOffer(cleanOffer)
						translatedOffer = rewriteMidToZero(translatedOffer)
						offer := webrtc.SessionDescription{
							Type: webrtc.SDPTypeOffer,
							SDP:  translatedOffer,
						}
						if err := sessA.AdcPC.SetRemoteDescription(offer); err == nil {
							answer, err2 := sessA.AdcPC.CreateAnswer(nil)
							if err2 == nil {
								gatherComplete := webrtc.GatheringCompletePromise(sessA.AdcPC)
								_ = sessA.AdcPC.SetLocalDescription(answer)
								<-gatherComplete

								sessA.AdcLocalPort = extractPortFromSDP(sessA.AdcPC.LocalDescription().SDP, sessA.LocalIP)
								log.Printf("[MF] AdcPC Local SDP for Session A (LocalPort=%d):\n%s", sessA.AdcLocalPort, sessA.AdcPC.LocalDescription().SDP)
								iceParams, dtlsFingerprint, err3 := extractICEDTLSFromSDP(sessA.AdcPC.LocalDescription().SDP)
								if err3 == nil {
									sessA.AdcICEParams = iceParams
									sessA.AdcDTLSParams = sessA.AdcPC.LocalDescription().SDP
									sessA.AdcFingerprint = dtlsFingerprint
								}
							}
						} else {
							log.Printf("[MF] Failed to set remote description on AdcPC for session A: %v", err)
						}
					}
				} else {
					offer, err := sessA.AdcPC.CreateOffer(nil)
					if err == nil {
						gatherComplete := webrtc.GatheringCompletePromise(sessA.AdcPC)
						_ = sessA.AdcPC.SetLocalDescription(offer)
						<-gatherComplete

						sessA.AdcLocalPort = extractPortFromSDP(sessA.AdcPC.LocalDescription().SDP, sessA.LocalIP)
						iceParams, dtlsFingerprint, err2 := extractICEDTLSFromSDP(sessA.AdcPC.LocalDescription().SDP)
						if err2 == nil {
							sessA.AdcICEParams = iceParams
							sessA.AdcDTLSParams = sessA.AdcPC.LocalDescription().SDP
							sessA.AdcFingerprint = dtlsFingerprint
						}
					}
				}
			}
			sessA.mu.Unlock()

			if sessA.ProxyMode == "UDP_PROXY" {
				go func(s *SessionInfo) {
					log.Printf("[MF] Pre-connecting MDC2 TCP to DCAS for session A %s (ADC new context)", s.ID)
					_, _ = m.GetOrCreateDcasConn(s, 1000)
					_, _ = m.GetOrCreateDcasConn(s, 1001)
				}(sessA)
			}
		}

		// Initialize AdcPC on session B
		if sessB != nil {
			sessB.mu.Lock()
			isAnswererB := (sdpOfferB != "")
			if err := m.initSessionAdcPC(sessB, isAnswererB); err != nil {
				sessB.mu.Unlock()
				return nil, fmt.Errorf("failed to init AdcPC for session B: %w", err)
			}
			if sessB.AdcPC != nil {
				if sdpOfferB != "" {
					_, adcOffer := splitSDPAnswer(sdpOfferB)
					if adcOffer != "" {
						cleanOffer := stripAudioVideoFromSDP(adcOffer)
						translatedOffer := translateSipOfferToWebRtcOffer(cleanOffer)
						translatedOffer = rewriteMidToZero(translatedOffer)
						offer := webrtc.SessionDescription{
							Type: webrtc.SDPTypeOffer,
							SDP:  translatedOffer,
						}
						if err := sessB.AdcPC.SetRemoteDescription(offer); err == nil {
							answer, err2 := sessB.AdcPC.CreateAnswer(nil)
							if err2 == nil {
								gatherComplete := webrtc.GatheringCompletePromise(sessB.AdcPC)
								_ = sessB.AdcPC.SetLocalDescription(answer)
								<-gatherComplete

								sessB.AdcLocalPort = extractPortFromSDP(sessB.AdcPC.LocalDescription().SDP, sessB.LocalIP)
								log.Printf("[MF] AdcPC Local SDP for Session B (LocalPort=%d):\n%s", sessB.AdcLocalPort, sessB.AdcPC.LocalDescription().SDP)
								iceParams, dtlsFingerprint, err3 := extractICEDTLSFromSDP(sessB.AdcPC.LocalDescription().SDP)
								if err3 == nil {
									sessB.AdcICEParams = iceParams
									sessB.AdcDTLSParams = sessB.AdcPC.LocalDescription().SDP
									sessB.AdcFingerprint = dtlsFingerprint
								}
							}
						} else {
							log.Printf("[MF] Failed to set remote description on AdcPC for session B: %v", err)
						}
					}
				} else {
					offer, err := sessB.AdcPC.CreateOffer(nil)
					if err == nil {
						gatherComplete := webrtc.GatheringCompletePromise(sessB.AdcPC)
						_ = sessB.AdcPC.SetLocalDescription(offer)
						<-gatherComplete

						sessB.AdcLocalPort = extractPortFromSDP(sessB.AdcPC.LocalDescription().SDP, sessB.LocalIP)
						iceParams, dtlsFingerprint, err2 := extractICEDTLSFromSDP(sessB.AdcPC.LocalDescription().SDP)
						if err2 == nil {
							sessB.AdcICEParams = iceParams
							sessB.AdcDTLSParams = sessB.AdcPC.LocalDescription().SDP
							sessB.AdcFingerprint = dtlsFingerprint
						}
					}
				}
			}
			sessB.mu.Unlock()

			if sessB.ProxyMode == "UDP_PROXY" {
				go func(s *SessionInfo) {
					log.Printf("[MF] Pre-connecting MDC2 TCP to DCAS for session B %s (ADC new context)", s.ID)
					_, _ = m.GetOrCreateDcasConn(s, 1000)
					_, _ = m.GetOrCreateDcasConn(s, 1001)
				}(sessB)
			}
		}

		// Build terminations with BDC and ADC MediaItems (same pattern as existing-context branch)
		resTerminations = make([]sba.Termination, 0)
		if sessA != nil {
			setupA_BDC := "active"
			if sessA.BdcPC != nil && sessA.BdcPC.LocalDescription() != nil {
				setupA_BDC = extractSetupRoleFromSDP(sessA.BdcPC.LocalDescription().SDP)
			}
			setupA_ADC := "active"
			if sessA.AdcPC != nil && sessA.AdcPC.LocalDescription() != nil {
				setupA_ADC = extractSetupRoleFromSDP(sessA.AdcPC.LocalDescription().SDP)
			}

			medias := []sba.MediaItem{
				{
					MediaID:           "audio",
					MediaResourceType: "RTP",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessA.LocalIP,
						Port: sessA.LocalAudioPort,
					},
				},
				{
					MediaID:           "video",
					MediaResourceType: "RTP",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessA.LocalIP,
						Port: sessA.LocalVideoPort,
					},
				},
				{
					MediaID:           "1",
					MediaResourceType: "DC",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessA.LocalIP,
						Port: sessA.BdcLocalPort,
					},
					LocalDcEndpoint: &sba.DcEndpoint{
						SctpPort:      sessA.BdcLocalPort,
						Fingerprint:   fmt.Sprintf("%s %s", sessA.BdcFingerprint.Algorithm, sessA.BdcFingerprint.Value),
						SecuritySetup: setupA_BDC,
						IceUfrag:      sessA.BdcICEParams.UsernameFragment,
						IcePwd:        sessA.BdcICEParams.Password,
					},
				},
			}
			if sessA.AdcLocalPort != 0 {
				medias = append(medias, sba.MediaItem{
					MediaID:           "adc-media",
					MediaResourceType: "DC",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessA.LocalIP,
						Port: sessA.AdcLocalPort,
					},
					LocalDcEndpoint: &sba.DcEndpoint{
						SctpPort:      sessA.AdcLocalPort,
						Fingerprint:   fmt.Sprintf("%s %s", sessA.AdcFingerprint.Algorithm, sessA.AdcFingerprint.Value),
						SecuritySetup: setupA_ADC,
						IceUfrag:      sessA.AdcICEParams.UsernameFragment,
						IcePwd:        sessA.AdcICEParams.Password,
					},
				})
			}
			resTerminations = append(resTerminations, sba.Termination{
				TerminationID: "term-caller-a",
				Medias:        medias,
			})
		}
		if sessB != nil {
			setupB_BDC := "active"
			if sessB.BdcPC != nil && sessB.BdcPC.LocalDescription() != nil {
				setupB_BDC = extractSetupRoleFromSDP(sessB.BdcPC.LocalDescription().SDP)
			}
			setupB_ADC := "active"
			if sessB.AdcPC != nil && sessB.AdcPC.LocalDescription() != nil {
				setupB_ADC = extractSetupRoleFromSDP(sessB.AdcPC.LocalDescription().SDP)
			}

			medias := []sba.MediaItem{
				{
					MediaID:           "audio",
					MediaResourceType: "RTP",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessB.LocalIP,
						Port: sessB.LocalAudioPort,
					},
				},
				{
					MediaID:           "video",
					MediaResourceType: "RTP",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessB.LocalIP,
						Port: sessB.LocalVideoPort,
					},
				},
				{
					MediaID:           "3",
					MediaResourceType: "DC",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessB.LocalIP,
						Port: sessB.BdcLocalPort,
					},
					LocalDcEndpoint: &sba.DcEndpoint{
						SctpPort:      sessB.BdcLocalPort,
						Fingerprint:   fmt.Sprintf("%s %s", sessB.BdcFingerprint.Algorithm, sessB.BdcFingerprint.Value),
						SecuritySetup: setupB_BDC,
						IceUfrag:      sessB.BdcICEParams.UsernameFragment,
						IcePwd:        sessB.BdcICEParams.Password,
					},
				},
			}
			if sessB.AdcLocalPort != 0 {
				localSDP := mergeSDPOffers(sessB.BdcPC.LocalDescription().SDP, sessB.AdcPC.LocalDescription().SDP)
				localSDP = rewriteSDPPorts(localSDP, m.udpPort, m.publicPort)
				localSDP = rewriteAdcPortInSDP(localSDP, sessB.LocalIP, sessB.AdcLocalPort)

				medias = append(medias, sba.MediaItem{
					MediaID:           "adc-media",
					MediaResourceType: "DC",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   sessB.LocalIP,
						Port: sessB.AdcLocalPort,
					},
					LocalDcEndpoint: &sba.DcEndpoint{
						SctpPort:      sessB.AdcLocalPort,
						Fingerprint:   fmt.Sprintf("%s %s", sessB.AdcFingerprint.Algorithm, sessB.AdcFingerprint.Value),
						SecuritySetup: setupB_ADC,
						IceUfrag:      sessB.AdcICEParams.UsernameFragment,
						IcePwd:        sessB.AdcICEParams.Password,
					},
					SDP: localSDP,
				})
			}
			resTerminations = append(resTerminations, sba.Termination{
				TerminationID: "term-callee-b",
				Medias:        medias,
			})
		}

		return &sba.MediaContext{
			ContextID:    contextID,
			Terminations: resTerminations,
		}, nil

	} else {
		// P2P or P2A Context
		log.Printf("[MF] Creating P2P or P2A Context: context=%s, terminationsCount=%d", contextID, len(ctx.Terminations))
		var respA, respB *AllocateResponse
		var err error

		termA := ctx.Terminations[0]
		mItemA := termA.Medias[0]

		// Determine if Term A is Core or Access based on its SDP
		isCoreA := ctx.IsCoreNetwork

		// Allocate session A (Answerer)
		respA, err = m.AllocateAnswererSession("peer-caller-a", contextID, mItemA.SDP, isCoreA)
		if err != nil {
			return nil, err
		}
		m.mu.Lock()
		cinfo.SessionA = respA.SessionID
		m.mu.Unlock()

		termIDA := termA.TerminationID
		if termIDA == "" {
			termIDA = "term-" + uuid.New().String()
		}

		resTermA := sba.Termination{
			TerminationID: termIDA,
			Medias: []sba.MediaItem{
				{
					MediaID:           mItemA.MediaID,
					MediaResourceType: "DC",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   respA.IP,
						Port: respA.Port,
					},
					LocalDcEndpoint: &sba.DcEndpoint{
						SctpPort:      respA.Port,
						Fingerprint:   respA.Fingerprint,
						SecuritySetup: respA.SetupRole,
						IceUfrag:      respA.ICEUfrag,
						IcePwd:        respA.ICEPwd,
					},
				},
				{
					MediaID: "audio",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   respA.IP,
						Port: respA.AudioPort,
					},
				},
				{
					MediaID: "video",
					LocalMbEndpoint: &sba.EndpointIPPort{
						IP:   respA.IP,
						Port: respA.VideoPort,
					},
				},
			},
		}
		resTerminations = append(resTerminations, resTermA)

		// Allocate session B (Offerer) only if there is a second termination
		if len(ctx.Terminations) > 1 {
			respB, err = m.AllocateSession("peer-callee-b", contextID, isCoreA, respA.IP)
			if err != nil {
				m.CleanupSession(respA.SessionID)
				return nil, err
			}
			m.mu.Lock()
			cinfo.SessionB = respB.SessionID
			m.mu.Unlock()

			// Link sessions automatically
			if err := m.LinkSessions(respA.SessionID, respB.SessionID); err != nil {
				m.CleanupSession(respA.SessionID)
				m.CleanupSession(respB.SessionID)
				return nil, err
			}

			// Termination B
			termB := ctx.Terminations[1]
			termIDB := termB.TerminationID
			if termIDB == "" {
				termIDB = "term-" + uuid.New().String()
			}
			mediaID := "3"
			if len(termB.Medias) > 0 && termB.Medias[0].MediaID != "" {
				mediaID = termB.Medias[0].MediaID
			}
			resTermB := sba.Termination{
				TerminationID: termIDB,
				Medias: []sba.MediaItem{
					{
						MediaID:           mediaID,
						MediaResourceType: "DC",
						LocalMbEndpoint: &sba.EndpointIPPort{
							IP:   respB.IP,
							Port: respB.Port,
						},
						LocalDcEndpoint: &sba.DcEndpoint{
							SctpPort:      respB.Port,
							Fingerprint:   respB.Fingerprint,
							SecuritySetup: respB.SetupRole,
							IceUfrag:      respB.ICEUfrag,
							IcePwd:        respB.ICEPwd,
						},
						SDP: respB.SDPOffer,
					},
					{
						MediaID: "audio",
						LocalMbEndpoint: &sba.EndpointIPPort{
							IP:   respB.IP,
							Port: respB.AudioPort,
						},
					},
					{
						MediaID: "video",
						LocalMbEndpoint: &sba.EndpointIPPort{
							IP:   respB.IP,
							Port: respB.VideoPort,
						},
					},
				},
			}
			resTerminations = append(resTerminations, resTermB)
		}
	}

	m.mu.Lock()
	m.contexts[contextID] = cinfo
	if isHotline {
		if sess := m.sessions[cinfo.SessionA]; sess != nil {
			term := ctx.Terminations[0]
			if len(term.Medias) > 0 && term.Medias[0].DcMedia != nil {
				if term.Medias[0].DcMedia.ReplaceHttpUrl != nil {
					sess.ReplaceHttpUrls = term.Medias[0].DcMedia.ReplaceHttpUrl
				} else {
					sess.ReplaceHttpUrls = term.Medias[0].DcMedia.ReplaceHttpUrls
				}
			}
		}
	} else {
		termA := ctx.Terminations[0]
		if sessA := m.sessions[cinfo.SessionA]; sessA != nil {
			var dcMedia *sba.DcMedia
			for _, m := range termA.Medias {
				if m.DcMedia != nil {
					if m.DcMedia.MediaProxyConfig == "UDP_PROXY" || dcMedia == nil {
						dcMedia = m.DcMedia
					}
				}
			}
			if dcMedia != nil {
				sessA.ProxyMode = dcMedia.MediaProxyConfig
				if dcMedia.Mdc2Info != nil {
					sessA.Mdc2Protocol = dcMedia.Mdc2Info.Mdc2Protocol
					if dcMedia.Mdc2Info.RemoteMdc2Endpoint != nil {
						sessA.DcasIP = dcMedia.Mdc2Info.RemoteMdc2Endpoint.IP
						sessA.DcasPort = dcMedia.Mdc2Info.RemoteMdc2Endpoint.Port
					}
				} else if dcMedia.Mdc2EndpointInfo != nil {
					sessA.Mdc2Protocol = dcMedia.Mdc2EndpointInfo.Mdc2Protocol
					if dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs != nil {
						sessA.DcasIP = dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs.IP
						sessA.DcasPort = dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs.Port
					}
				}
				if dcMedia.ReplaceHttpUrl != nil {
					sessA.ReplaceHttpUrls = dcMedia.ReplaceHttpUrl
				} else {
					sessA.ReplaceHttpUrls = dcMedia.ReplaceHttpUrls
				}
			}
		}

		if len(ctx.Terminations) > 1 && ctx.Terminations[1].Medias[0].MediaResourceType == "RTP" {
			log.Printf("[MF] Interworking Active: linking originating DC session A %s to terminating RTP session B %s", cinfo.SessionA, cinfo.SessionB)
			if sessA := m.sessions[cinfo.SessionA]; sessA != nil {
				sessA.IsInterworking = true
				if sessB := m.sessions[cinfo.SessionB]; sessB != nil {
					sessA.LinkedSession = sessB
				}
			}
		}
		if sessB := m.sessions[cinfo.SessionB]; sessB != nil {
			var dcMedia *sba.DcMedia
			if len(ctx.Terminations) > 1 && len(ctx.Terminations[1].Medias) > 0 {
				for _, m := range ctx.Terminations[1].Medias {
					if m.DcMedia != nil {
						if m.DcMedia.MediaProxyConfig == "UDP_PROXY" || dcMedia == nil {
							dcMedia = m.DcMedia
						}
					}
				}
			}
			if dcMedia == nil && len(termA.Medias) > 0 {
				for _, m := range termA.Medias {
					if m.DcMedia != nil {
						if m.DcMedia.MediaProxyConfig == "UDP_PROXY" || dcMedia == nil {
							dcMedia = m.DcMedia
						}
					}
				}
			}
			if dcMedia != nil {
				sessB.ProxyMode = dcMedia.MediaProxyConfig
				if dcMedia.Mdc2Info != nil {
					sessB.Mdc2Protocol = dcMedia.Mdc2Info.Mdc2Protocol
					if dcMedia.Mdc2Info.RemoteMdc2Endpoint != nil {
						sessB.DcasIP = dcMedia.Mdc2Info.RemoteMdc2Endpoint.IP
						sessB.DcasPort = dcMedia.Mdc2Info.RemoteMdc2Endpoint.Port
					}
				} else if dcMedia.Mdc2EndpointInfo != nil {
					sessB.Mdc2Protocol = dcMedia.Mdc2EndpointInfo.Mdc2Protocol
					if dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs != nil {
						sessB.DcasIP = dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs.IP
						sessB.DcasPort = dcMedia.Mdc2EndpointInfo.Mdc2EndpointDcAs.Port
					}
				}
				if dcMedia.ReplaceHttpUrl != nil {
					sessB.ReplaceHttpUrls = dcMedia.ReplaceHttpUrl
				} else {
					sessB.ReplaceHttpUrls = dcMedia.ReplaceHttpUrls
				}
			}
		}
	}
	m.mu.Unlock()

	if !isHotline {
		if sessA := m.sessions[cinfo.SessionA]; sessA != nil {
			if sessA.ProxyMode == "UDP_PROXY" {
				go func(s *SessionInfo) {
					log.Printf("[MF] Pre-connecting MDC2 TCP to DCAS for session A %s (new context)", s.ID)
					_, _ = m.GetOrCreateDcasConn(s, 1000)
					_, _ = m.GetOrCreateDcasConn(s, 1001)
				}(sessA)
			}
		}
		if sessB := m.sessions[cinfo.SessionB]; sessB != nil {
			if sessB.ProxyMode == "UDP_PROXY" {
				go func(s *SessionInfo) {
					log.Printf("[MF] Pre-connecting MDC2 TCP to DCAS for session B %s (new context)", s.ID)
					_, _ = m.GetOrCreateDcasConn(s, 1000)
					_, _ = m.GetOrCreateDcasConn(s, 1001)
				}(sessB)
			}
		}
	}

	return &sba.MediaContext{
		ContextID:    contextID,
		Terminations: resTerminations,
	}, nil
}

func (m *MediaFunction) UpdateMediaContext(contextID string, patches []sba.PatchDocument) error {
	var cinfo *ContextInfo
	var exists bool

	hasTerm1 := false
	for _, p := range patches {
		if strings.Contains(p.Path, "/terminations/1") {
			hasTerm1 = true
			break
		}
	}

	// Wait up to 1000ms for context creation and session initialization
	for i := 0; i < 100; i++ {
		m.mu.RLock()
		cinfo, exists = m.contexts[contextID]
		m.mu.RUnlock()

		if exists {
			m.mu.RLock()
			sessionAVal := cinfo.SessionA
			sessionBVal := cinfo.SessionB
			m.mu.RUnlock()

			if sessionAVal != "" && (!hasTerm1 || sessionBVal != "") {
				break
			}
		}
		time.Sleep(10 * time.Millisecond)
	}

	if !exists {
		return fmt.Errorf("context not found: %s", contextID)
	}
	m.mu.RLock()
	sessionAVal := cinfo.SessionA
	sessionBVal := cinfo.SessionB
	m.mu.RUnlock()

	if sessionAVal == "" {
		return fmt.Errorf("session A not initialized in context: %s", contextID)
	}
	if hasTerm1 && sessionBVal == "" {
		return fmt.Errorf("session B not initialized in context: %s", contextID)
	}

	for _, patch := range patches {
		if strings.Contains(patch.Path, "localMbEndpoint") || strings.Contains(patch.Path, "localDcEndpoint") || strings.Contains(patch.Path, "mediaProcessingURL") || strings.Contains(patch.Path, "mediaProcessingUrl") {
			return fmt.Errorf("MEDIA_CONNECTION_CHANGED: connection parameters cannot be modified")
		}
		if patch.Op == "replace" {
			if patch.Path == "/terminations/1/sdp" {
				var sdpAnswer string
				if err := json.Unmarshal(patch.Value, &sdpAnswer); err != nil {
					return fmt.Errorf("invalid patch value: %w", err)
				}
				log.Printf("[MF] Context %s: applying SDP Answer to session B %s", contextID, cinfo.SessionB)
				if err := m.SetRemoteAnswer(cinfo.SessionB, sdpAnswer); err != nil {
					return err
				}
			} else if patch.Path == "/terminations/0/sdp" {
				var sdpAnswer string
				if err := json.Unmarshal(patch.Value, &sdpAnswer); err != nil {
					return fmt.Errorf("invalid patch value: %w", err)
				}
				log.Printf("[MF] Context %s: applying SDP Answer to session A %s", contextID, cinfo.SessionA)
				if err := m.SetRemoteAnswer(cinfo.SessionA, sdpAnswer); err != nil {
					return err
				}
			} else if patch.Path == "/terminations/0/proxyMode" {
				var proxyMode string
				if err := json.Unmarshal(patch.Value, &proxyMode); err != nil {
					return fmt.Errorf("invalid patch value: %w", err)
				}
				log.Printf("[MF] Context %s: updating ProxyMode in-place to %s", contextID, proxyMode)
				m.mu.Lock()
				if sessA := m.sessions[cinfo.SessionA]; sessA != nil {
					sessA.mu.Lock()
					sessA.ProxyMode = proxyMode
					sessA.mu.Unlock()
				}
				if sessB := m.sessions[cinfo.SessionB]; sessB != nil {
					sessB.mu.Lock()
					sessB.ProxyMode = proxyMode
					sessB.mu.Unlock()
				}
				m.mu.Unlock()
			} else if strings.HasPrefix(patch.Path, "/terminations/0/dcMedia/mdc2Info") || strings.HasPrefix(patch.Path, "/terminations/0/medias/0/dcMedia/mdc2Info") {
				var mdc2Info sba.Mdc2Info
				if err := json.Unmarshal(patch.Value, &mdc2Info); err != nil {
					return fmt.Errorf("invalid patch value: %w", err)
				}
				log.Printf("[MF] Context %s: updating MDC2 info: %+v", contextID, mdc2Info)
				m.mu.Lock()
				if sessA := m.sessions[cinfo.SessionA]; sessA != nil {
					sessA.mu.Lock()
					sessA.Mdc2Protocol = mdc2Info.Mdc2Protocol
					if mdc2Info.RemoteMdc2Endpoint != nil {
						sessA.DcasIP = mdc2Info.RemoteMdc2Endpoint.IP
						sessA.DcasPort = mdc2Info.RemoteMdc2Endpoint.Port
					}
					sessA.mu.Unlock()
				}
				m.mu.Unlock()
			}
		} else if patch.Op == "remove" {
			if patch.Path == "/terminations/1" {
				log.Printf("[MF] Context %s: removing terminating session B %s (Interworking Mode)", contextID, cinfo.SessionB)
				sessionToCleanup := ""
				m.mu.Lock()
				if cinfo.SessionB != "" {
					sessionToCleanup = cinfo.SessionB
					cinfo.SessionB = ""
				}
				m.mu.Unlock()

				if sessionToCleanup != "" {
					m.CleanupSession(sessionToCleanup)
				}
			}
		}
	}
	return nil
}

func (m *MediaFunction) DeleteMediaContext(contextID string) error {
	m.mu.Lock()
	cinfo, exists := m.contexts[contextID]
	delete(m.contexts, contextID)
	m.mu.Unlock()

	if !exists {
		return fmt.Errorf("context not found: %s", contextID)
	}

	log.Printf("[MF] DeleteMediaContext context=%s", contextID)
	if cinfo.SessionA != "" {
		m.CleanupSession(cinfo.SessionA)
	}
	if cinfo.SessionB != "" {
		m.CleanupSession(cinfo.SessionB)
	}

	return nil
}
