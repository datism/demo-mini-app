package mf

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/pion/webrtc/v3"
)

func filterSDPCandidates(sdpText, validIP string) string {
	lines := strings.Split(sdpText, "\n")
	var result []string
	for _, line := range lines {
		trimmed := strings.TrimRight(line, "\r")
		if strings.HasPrefix(trimmed, "a=candidate:") {
			if !strings.Contains(trimmed, validIP) {
				continue
			}
		}
		result = append(result, line)
	}
	return strings.Join(result, "\n")
}

func rewriteSDPPorts(sdpText string, bindPort, pubPort int) string {
	if bindPort == pubPort {
		return sdpText
	}
	oldPortStr := strconv.Itoa(bindPort)
	newPortStr := strconv.Itoa(pubPort)
	lines := strings.Split(sdpText, "\n")
	var result []string
	for _, line := range lines {
		trimmed := strings.TrimRight(line, "\r")
		// Rewrite candidate lines: a=candidate:... IP PORT typ host
		if strings.HasPrefix(trimmed, "a=candidate:") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 6 && parts[5] == oldPortStr {
				parts[5] = newPortStr
				result = append(result, strings.Join(parts, " "))
				continue
			}
		}
		// Rewrite m=application line: m=application PORT ...
		if strings.HasPrefix(trimmed, "m=application ") {
			parts := strings.SplitN(trimmed, " ", 3)
			if len(parts) >= 3 && parts[1] == oldPortStr {
				parts[1] = newPortStr
				result = append(result, strings.Join(parts, " "))
				continue
			}
		}
		result = append(result, line)
	}
	return strings.Join(result, "\n")
}

func extractICEDTLSFromSDP(sdp string) (webrtc.ICEParameters, webrtc.DTLSFingerprint, error) {
	var iceParams webrtc.ICEParameters
	var fingerprint webrtc.DTLSFingerprint

	for _, line := range strings.Split(sdp, "\n") {
		line = strings.TrimRight(line, "\r")
		switch {
		case strings.HasPrefix(line, "a=ice-ufrag:"):
			iceParams.UsernameFragment = strings.TrimPrefix(line, "a=ice-ufrag:")
		case strings.HasPrefix(line, "a=ice-pwd:"):
			iceParams.Password = strings.TrimPrefix(line, "a=ice-pwd:")
		case strings.HasPrefix(line, "a=fingerprint:"):
			parts := strings.SplitN(strings.TrimPrefix(line, "a=fingerprint:"), " ", 2)
			if len(parts) == 2 {
				fingerprint.Algorithm = parts[0]
				fingerprint.Value = parts[1]
			}
		}
	}
	return iceParams, fingerprint, nil
}

func extractSetupRoleFromSDP(sdp string) string {
	for _, line := range strings.Split(sdp, "\n") {
		line = strings.TrimRight(line, "\r")
		if strings.HasPrefix(line, "a=setup:") {
			return strings.TrimPrefix(line, "a=setup:")
		}
	}
	return "passive"
}

func rewriteMidToZero(sdp string) string {
	lines := strings.Split(sdp, "\n")
	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "a=mid:") {
			lines[i] = "a=mid:0"
		}
		if strings.HasPrefix(trimmed, "a=group:BUNDLE") {
			lines[i] = "a=group:BUNDLE 0"
		}
	}
	return strings.Join(lines, "\n")
}

func translateSipOfferToWebRtcOffer(sdp string) string {
	lines := strings.Split(sdp, "\n")
	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=audio ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 3 && parts[2] == "RTP/AVP" {
				lines[i] = fmt.Sprintf("%s %s UDP/TLS/RTP/SAVPF 8", parts[0], parts[1])
			}
		}
		if strings.HasPrefix(trimmed, "m=video ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 3 && (parts[2] == "RTP/AVP" || parts[2] == "RTP/AVPF") {
				lines[i] = fmt.Sprintf("%s %s UDP/TLS/RTP/SAVPF 97", parts[0], parts[1])
			}
		}
	}
	return strings.Join(lines, "\n")
}

func translateWebRtcAnswerToSipAnswer(sdp string) string {
	lines := strings.Split(sdp, "\n")
	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=audio ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 3 {
				lines[i] = fmt.Sprintf("%s %s RTP/AVP 8", parts[0], parts[1])
			}
		}
		if strings.HasPrefix(trimmed, "m=video ") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 3 {
				lines[i] = fmt.Sprintf("%s %s RTP/AVP 97", parts[0], parts[1])
			}
		}
	}
	return strings.Join(lines, "\n")
}

func stripAudioVideoFromSDP(sdpText string) string {
	normalized := strings.ReplaceAll(sdpText, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	var cleanLines []string
	skipSection := false
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=audio ") || strings.HasPrefix(trimmed, "m=video ") {
			skipSection = true
			continue
		}
		if strings.HasPrefix(trimmed, "m=application ") {
			skipSection = false
		}
		if skipSection {
			continue
		}
		cleanLines = append(cleanLines, line)
	}
	return strings.Join(cleanLines, "\r\n")
}

func parseSDPMedia(sdpText string) MediaInfo {
	var info MediaInfo
	normalized := strings.ReplaceAll(sdpText, "\r\n", "\n")
	lines := strings.Split(normalized, "\n")
	// Track which media section we're in to avoid picking up
	// c=IN IP4 0.0.0.0 from m=application (WebRTC/ICE) blocks.
	currentMedia := ""
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=audio ") {
			currentMedia = "audio"
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 2 {
				fmt.Sscanf(parts[1], "%d", &info.AudioPort)
			}
		} else if strings.HasPrefix(trimmed, "m=video ") {
			currentMedia = "video"
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 2 {
				fmt.Sscanf(parts[1], "%d", &info.VideoPort)
			}
		} else if strings.HasPrefix(trimmed, "m=") {
			currentMedia = "other"
		} else if strings.HasPrefix(trimmed, "c=IN IP4 ") {
			ip := strings.TrimPrefix(trimmed, "c=IN IP4 ")
			// Only set RtpIP from audio/video or session-level c= lines,
			// never from m=application which uses 0.0.0.0 for ICE.
			if currentMedia == "audio" || currentMedia == "video" || currentMedia == "" {
				if ip != "0.0.0.0" || info.IP == "" {
					info.IP = ip
				}
			}
		}
	}
	return info
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

func splitSDPAnswer(sdpAnswer string) (bdcAnswer string, adcAnswer string) {
	sessionHeader, audioBlock, videoBlock, appBlocks := splitSDPToMediaBlocks(sdpAnswer)

	var bdcMid string
	if len(appBlocks) > 0 {
		for _, line := range strings.Split(appBlocks[0], "\n") {
			trimmed := strings.TrimSpace(line)
			if strings.HasPrefix(trimmed, "a=mid:") {
				bdcMid = strings.TrimPrefix(trimmed, "a=mid:")
				break
			}
		}
	}

	var cleanBdcHeader strings.Builder
	for _, line := range strings.Split(sessionHeader, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed != "" && !strings.HasPrefix(trimmed, "a=group:BUNDLE") {
			cleanBdcHeader.WriteString(trimmed + "\r\n")
		}
	}
	if bdcMid != "" {
		cleanBdcHeader.WriteString("a=group:BUNDLE " + bdcMid + "\r\n")
	}

	// BDC Answer
	var bdcSb strings.Builder
	bdcSb.WriteString(cleanBdcHeader.String())
	if audioBlock != "" {
		bdcSb.WriteString(audioBlock)
	}
	if videoBlock != "" {
		bdcSb.WriteString(videoBlock)
	}
	if len(appBlocks) > 0 {
		bdcSb.WriteString(appBlocks[0])
	}
	bdcAnswer = bdcSb.String()

	// ADC Answer
	if len(appBlocks) > 1 {
		matchedBlock := appBlocks[1]
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

		// Strip BUNDLE from sessionHeader for ADC PC compatibility
		var adcSessionHeader strings.Builder
		for _, line := range strings.Split(sessionHeader, "\n") {
			trimmed := strings.TrimSpace(line)
			if trimmed != "" && !strings.HasPrefix(trimmed, "a=group:BUNDLE") && !strings.HasPrefix(trimmed, "a=fingerprint:") && !strings.HasPrefix(trimmed, "a=tls-id:") {
				adcSessionHeader.WriteString(trimmed + "\r\n")
			}
		}
		adcSessionHeader.WriteString("a=group:BUNDLE " + adcMid + "\r\n")
		for _, fp := range adcFingerprints {
			adcSessionHeader.WriteString(fp + "\r\n")
		}
		for _, tid := range adcTlsIds {
			adcSessionHeader.WriteString(tid + "\r\n")
		}
		hasSetup := false
		for _, line := range strings.Split(matchedBlock, "\n") {
			if strings.HasPrefix(strings.TrimSpace(line), "a=setup:") {
				hasSetup = true
				break
			}
		}
		if !hasSetup {
			for _, line := range strings.Split(sessionHeader, "\n") {
				if strings.HasPrefix(strings.TrimSpace(line), "a=setup:") {
					adcSessionHeader.WriteString(strings.TrimSpace(line) + "\r\n")
					hasSetup = true
					break
				}
			}
		}
		if !hasSetup {
			adcSessionHeader.WriteString("a=setup:actpass\r\n")
		}

		var adcSb strings.Builder
		adcSb.WriteString(adcSessionHeader.String())
		adcSb.WriteString(matchedBlock)
		adcAnswer = adcSb.String()
	}
	return
}

func mergeSDPOffers(bdcSdp string, adcSdp string) string {
	bdcSession, bdcAudio, bdcVideo, bdcApps := splitSDPToMediaBlocks(bdcSdp)
	adcSdpHeader, _, _, adcApps := splitSDPToMediaBlocks(adcSdp)

	var adcFingerprints []string
	var adcTlsIds []string
	for _, line := range strings.Split(adcSdpHeader, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "a=fingerprint:") {
			adcFingerprints = append(adcFingerprints, trimmed)
		} else if strings.HasPrefix(trimmed, "a=tls-id:") {
			adcTlsIds = append(adcTlsIds, trimmed)
		}
	}

	var sb strings.Builder
	sb.WriteString(bdcSession)
	if bdcAudio != "" {
		sb.WriteString(bdcAudio)
	}
	if bdcVideo != "" {
		sb.WriteString(bdcVideo)
	}
	if len(bdcApps) > 0 {
		sb.WriteString(bdcApps[0])
	}
	if len(adcApps) > 0 {
		adcBlock := adcApps[0]
		var rewrittenBlock strings.Builder
		for _, line := range strings.Split(adcBlock, "\n") {
			trimmed := strings.TrimSpace(line)
			if trimmed == "" {
				continue
			}
			rewrittenBlock.WriteString(trimmed + "\r\n")
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

func extractPortFromSDP(sdp string, targetIP string) int {
	var fallbackPort int
	for _, line := range strings.Split(sdp, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "a=candidate:") {
			parts := strings.Split(trimmed, " ")
			if len(parts) >= 6 {
				var port int
				if _, err := fmt.Sscanf(parts[5], "%d", &port); err == nil {
					if fallbackPort == 0 {
						fallbackPort = port
					}
					if targetIP != "" && parts[4] == targetIP {
						return port
					}
				}
			}
		}
	}
	return fallbackPort
}

func rewriteAdcPortInSDP(sdp string, ip string, port int) string {
	lines := strings.Split(sdp, "\n")
	var newLines []string
	appCount := 0
	inAdcBlock := false
	portStr := strconv.Itoa(port)
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=") {
			if strings.HasPrefix(trimmed, "m=application ") {
				appCount++
				if appCount >= 2 {
					inAdcBlock = true
					parts := strings.Split(trimmed, " ")
					if len(parts) >= 2 {
						parts[1] = portStr
						newLines = append(newLines, strings.Join(parts, " "))
						continue
					}
				} else {
					inAdcBlock = false
				}
			} else {
				inAdcBlock = false
			}
		}

		if inAdcBlock {
			if strings.HasPrefix(trimmed, "a=candidate:") {
				parts := strings.Split(trimmed, " ")
				if len(parts) >= 6 {
					parts[4] = ip
					parts[5] = portStr
					newLines = append(newLines, strings.Join(parts, " "))
					continue
				}
			}
			if strings.HasPrefix(trimmed, "c=IN IP4 ") {
				newLines = append(newLines, "c=IN IP4 "+ip)
				continue
			}
		}
		newLines = append(newLines, trimmed)
	}
	return strings.Join(newLines, "\r\n") + "\r\n"
}
