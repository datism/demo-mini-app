package imsas

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"strings"
)

type MFAllocResponse struct {
	SessionID       string `json:"session_id"`
	IceUfrag        string `json:"ice_ufrag"`
	IcePwd          string `json:"ice_pwd"`
	DtlsFingerprint string `json:"dtls_fingerprint"`
	IP              string `json:"ip"`
	Port            int    `json:"port"`
	AudioPort       int    `json:"audio_port"`
	VideoPort       int    `json:"video_port"`
	SetupRole       string `json:"setup_role"`
	SDPAnswer       string `json:"sdp_answer"` // Populated for Hotline call answers
	BdcRole         string `json:"bdc_role,omitempty"`
	QosHint         string `json:"qos_hint,omitempty"`

	// Separate transport parameters for ADC
	AdcPort            int    `json:"adc_port,omitempty"`
	AdcIceUfrag        string `json:"adc_ice_ufrag,omitempty"`
	AdcIcePwd          string `json:"adc_ice_pwd,omitempty"`
	AdcDtlsFingerprint string `json:"adc_dtls_fingerprint,omitempty"`
	AdcSetupRole       string `json:"adc_setup_role,omitempty"`

	Slots map[AdcSlot]*SlotMF `json:"-"`
}

type SlotMF struct {
	Port        int
	IceUfrag    string
	IcePwd      string
	Fingerprint string
	Setup       string
}

func shouldDiscardLine(line string) bool {
	trimmed := strings.TrimSpace(line)
	discardPrefixes := []string{
		"a=fingerprint:",
		"a=ice-ufrag:",
		"a=ice-pwd:",
		"a=setup:",
		"a=candidate:",
		"a=rtpmap:",
		"a=fmtp:",
		"a=rtcp-fb:",
		"a=rtcp-mux",
		"a=rtcp:",
		"a=extmap:",
		"a=ssrc:",
		"a=ssrc-group:",
		"a=msid:",
		"a=msid-semantic:",
		"c=IN IP4 ",
		"a=tls-id:",
		"a=dcmap:",
		"a=3gpp-req-app:",
		"a=3gpp-bdc-used-by:",
		"a=sctp-port:",
	}
	for _, prefix := range discardPrefixes {
		if strings.HasPrefix(trimmed, prefix) {
			return true
		}
	}
	return false
}

func RewriteSDP(origSDP string, mfInfo *MFAllocResponse, activeAppId string) string {
	// MF runs inside Kind cluster (e.g. 172.19.0.2) which is unreachable from
	// physical S24 phones on the WiFi LAN. The ims-udp-relay container on the
	// host bridges port 40000. We advertise the host's LAN IP so S24 phones
	// send ICE/STUN/media to the relay, which forwards to MF.
	externalIP := mfInfo.IP
	if envIP := os.Getenv("MF_EXTERNAL_IP"); envIP != "" {
		externalIP = envIP
	}

	lines := strings.Split(origSDP, "\n")
	var newLines []string

	setupRole := mfInfo.SetupRole
	adcSetupRole := mfInfo.AdcSetupRole

	appCount := 0
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}

		if shouldDiscardLine(trimmed) {
			continue
		}

		if strings.HasPrefix(trimmed, "o=- ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 6 {
				parts[5] = externalIP
				newLines = append(newLines, strings.Join(parts, " "))
				continue
			}
		}

		// Add a=ice-lite at session level (after t= line, before first m= line)
		if strings.HasPrefix(trimmed, "t=") {
			newLines = append(newLines, trimmed)
			newLines = append(newLines, "a=ice-lite")
			continue
		}

		if strings.HasPrefix(trimmed, "m=audio ") {
			audioPort := mfInfo.AudioPort
			if audioPort == 0 {
				basePort := mfInfo.Port
				if basePort == 0 {
					basePort = mfInfo.AdcPort
				}
				audioPort = basePort + 2
			}
			newLines = append(newLines, fmt.Sprintf("m=audio %d RTP/AVP 8", audioPort))
			newLines = append(newLines, fmt.Sprintf("c=IN IP4 %s", externalIP))
			newLines = append(newLines, "a=rtpmap:8 PCMA/8000")
			continue
		}

		if strings.HasPrefix(trimmed, "m=video ") {
			videoPort := mfInfo.VideoPort
			if videoPort == 0 {
				basePort := mfInfo.Port
				if basePort == 0 {
					basePort = mfInfo.AdcPort
				}
				videoPort = basePort + 4
			}
			newLines = append(newLines, fmt.Sprintf("m=video %d RTP/AVP 97", videoPort))
			newLines = append(newLines, fmt.Sprintf("c=IN IP4 %s", externalIP))
			newLines = append(newLines, "a=rtpmap:97 H264/90000")
			newLines = append(newLines, "a=fmtp:97 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f")
			continue
		}

		if strings.HasPrefix(trimmed, "m=application ") {
			appCount++
			if appCount == 1 {
				// BDC media block
				newLines = append(newLines, fmt.Sprintf("m=application %d UDP/DTLS/SCTP webrtc-datachannel", mfInfo.Port))
				newLines = append(newLines, fmt.Sprintf("c=IN IP4 %s", externalIP))
				newLines = append(newLines, fmt.Sprintf("a=ice-ufrag:%s", mfInfo.IceUfrag))
				newLines = append(newLines, fmt.Sprintf("a=ice-pwd:%s", mfInfo.IcePwd))
				newLines = append(newLines, fmt.Sprintf("a=fingerprint:%s", mfInfo.DtlsFingerprint))
				newLines = append(newLines, fmt.Sprintf("a=setup:%s", setupRole))
				newLines = append(newLines, "a=connection:new")
				newLines = append(newLines, "a=sctp-port:5000")
				newLines = append(newLines, "a=dcmap:10 subprotocol=\"http\"")
				newLines = append(newLines, fmt.Sprintf("a=candidate:1 1 UDP 2122260991 %s %d typ host", externalIP, mfInfo.Port))
				newLines = append(newLines, fmt.Sprintf("a=tls-id:%s", getTLSId(mfInfo.SessionID+"-bdc")))
				if mfInfo.QosHint != "" {
					newLines = append(newLines, fmt.Sprintf("a=3gpp-qos-hint:%s", mfInfo.QosHint))
				}
				if mfInfo.BdcRole != "" {
					newLines = append(newLines, fmt.Sprintf("a=3gpp-bdc-used-by:%s", mfInfo.BdcRole))
				}
			} else {
				slot := SlotHome
				if appCount >= 3 {
					slot = SlotApp
				}
				sm := (*SlotMF)(nil)
				if mfInfo.Slots != nil {
					sm = mfInfo.Slots[slot]
				}
				origPort := 9
				if f := strings.Fields(trimmed); len(f) >= 2 {
					fmt.Sscanf(f[1], "%d", &origPort)
				}
				// An App line with no App-slot MF endpoint must not borrow the
				// Home credentials: send it as port 0.
				if slot == SlotApp && (origPort == 0 || (mfInfo.Slots != nil && sm == nil)) {
					// Inactive App slot: keep the line, port 0, no MF endpoint.
					newLines = append(newLines, "m=application 0 UDP/DTLS/SCTP webrtc-datachannel")
					newLines = append(newLines, fmt.Sprintf("c=IN IP4 %s", externalIP))
					continue
				}
				var adcPort int
				var adcIceUfrag, adcIcePwd, adcFingerprint, role, tlsKey string
				if sm != nil {
					adcPort, adcIceUfrag, adcIcePwd, adcFingerprint, role = sm.Port, sm.IceUfrag, sm.IcePwd, sm.Fingerprint, sm.Setup
					tlsKey = mfInfo.SessionID + "-" + string(slot)
				} else {
					adcPort = mfInfo.AdcPort
					if adcPort == 0 {
						adcPort = mfInfo.Port + 100 // fallback
					}
					adcIceUfrag = mfInfo.AdcIceUfrag
					if adcIceUfrag == "" {
						adcIceUfrag = mfInfo.IceUfrag
					}
					adcIcePwd = mfInfo.AdcIcePwd
					if adcIcePwd == "" {
						adcIcePwd = mfInfo.IcePwd
					}
					adcFingerprint = mfInfo.AdcDtlsFingerprint
					if adcFingerprint == "" {
						adcFingerprint = mfInfo.DtlsFingerprint
					}
					role = adcSetupRole
					tlsKey = mfInfo.SessionID + "-adc"
				}
				newLines = append(newLines, fmt.Sprintf("m=application %d UDP/DTLS/SCTP webrtc-datachannel", adcPort))
				newLines = append(newLines, fmt.Sprintf("c=IN IP4 %s", externalIP))
				newLines = append(newLines, fmt.Sprintf("a=ice-ufrag:%s", adcIceUfrag))
				newLines = append(newLines, fmt.Sprintf("a=ice-pwd:%s", adcIcePwd))
				newLines = append(newLines, fmt.Sprintf("a=fingerprint:%s", adcFingerprint))
				newLines = append(newLines, fmt.Sprintf("a=setup:%s", role))
				newLines = append(newLines, "a=connection:new")
				newLines = append(newLines, "a=sctp-port:5000")
				newLines = append(newLines, fmt.Sprintf("a=candidate:1 1 UDP 2122260991 %s %d typ host", externalIP, adcPort))
				newLines = append(newLines, fmt.Sprintf("a=tls-id:%s", getTLSId(tlsKey)))
				appId := activeAppId
				if slot == SlotHome && sm != nil {
					appId = "fun-calling-app"
				}
				if appId != "" {
					endpointType := "UE"
					if appId == "enterprise-callerid" {
						endpointType = "Server"
					}
					newLines = append(newLines, fmt.Sprintf("a=dcmap:1000 subprotocol=\"%s\"", appId))
					if slot == SlotHome && sm != nil {
						newLines = append(newLines, "a=dcmap:1001 subprotocol=\"emoji\"")
					}
					newLines = append(newLines, fmt.Sprintf("a=3gpp-req-app:\"%s\";1000-%s", appId, endpointType))
				}
			}
			continue
		}

		newLines = append(newLines, trimmed)
	}

	return strings.Join(newLines, "\r\n") + "\r\n"
}

// getTLSId is deterministic: an unchanged m-line must keep the same a=tls-id
// across rewrites (RFC 8842: a new value means a new DTLS association).
func getTLSId(key string) string {
	sum := sha256.Sum256([]byte(key))
	return hex.EncodeToString(sum[:16])
}

func ExcludeADCFromSDP(origSDP string) string {
	lines := strings.Split(origSDP, "\n")
	var newLines []string

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
			shouldDisable := (totalApps == 1 && appCount == 1) || (totalApps == 2 && appCount == 2) || (totalApps >= 3 && appCount == 3)
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
			// Keep critical DTLS/ICE attributes for WebRTC stack compliance, discard others
			keep := false
			prefixes := []string{
				"a=fingerprint:",
				"a=setup:",
				"a=ice-ufrag:",
				"a=ice-pwd:",
				"a=mid:",
				"a=sctp-port:",
				"c=",
			}
			for _, pref := range prefixes {
				if strings.HasPrefix(trimmed, pref) {
					keep = true
					break
				}
			}
			if !keep {
				continue
			}
		}

		newLines = append(newLines, trimmed)
	}

	return strings.Join(newLines, "\r\n") + "\r\n"
}

func RewriteSDPForADC(origSDP string, mfInfo *MFAllocResponse, activeAppId string) string {
	externalIP := mfInfo.IP
	if envIP := os.Getenv("MF_EXTERNAL_IP"); envIP != "" {
		externalIP = envIP
	}

	lines := strings.Split(origSDP, "\n")
	var newLines []string

	setupRole := mfInfo.SetupRole
	if setupRole == "" {
		setupRole = "passive"
	}

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}

		if shouldDiscardLine(trimmed) {
			continue
		}

		if strings.HasPrefix(trimmed, "m=application ") {
			newLines = append(newLines, fmt.Sprintf("m=application %d UDP/DTLS/SCTP webrtc-datachannel", mfInfo.Port))
			newLines = append(newLines, fmt.Sprintf("c=IN IP4 %s", externalIP))
			newLines = append(newLines, fmt.Sprintf("a=ice-ufrag:%s", mfInfo.IceUfrag))
			newLines = append(newLines, fmt.Sprintf("a=ice-pwd:%s", mfInfo.IcePwd))
			newLines = append(newLines, fmt.Sprintf("a=fingerprint:%s", mfInfo.DtlsFingerprint))
			newLines = append(newLines, fmt.Sprintf("a=setup:%s", setupRole))
			newLines = append(newLines, "a=connection:new")
			newLines = append(newLines, "a=sctp-port:5000")
			newLines = append(newLines, fmt.Sprintf("a=candidate:1 1 UDP 2122260991 %s %d typ host", externalIP, mfInfo.Port))
			newLines = append(newLines, fmt.Sprintf("a=tls-id:%s", getTLSId(mfInfo.SessionID)))
			if activeAppId != "" {
				endpointType := "UE"
				if activeAppId == "enterprise-callerid" {
					endpointType = "Server"
				}
				newLines = append(newLines, fmt.Sprintf("a=dcmap:1000 subprotocol=\"%s\"", activeAppId))
				newLines = append(newLines, fmt.Sprintf("a=3gpp-req-app:\"%s\";1000-%s", activeAppId, endpointType))
			}
			continue
		}

		newLines = append(newLines, trimmed)
	}

	return strings.Join(newLines, "\r\n") + "\r\n"
}
