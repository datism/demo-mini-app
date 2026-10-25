package mf

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net"
	"net/http"
	"os"
	"time"
)

// LinearToAlaw converts a 16-bit linear PCM sample to 8-bit G.711 A-law.
func LinearToAlaw(sample int16) uint8 {
	const clip = 32635
	var sign uint8 = 0
	if sample < 0 {
		sample = -sample
		sign = 0x80
	}
	if sample > clip {
		sample = clip
	}

	var exponent uint8 = 7
	for expMask := int16(0x4000); (sample&expMask) == 0 && exponent > 0; expMask >>= 1 {
		exponent--
	}

	var mantissa uint8
	if exponent == 0 {
		mantissa = uint8((sample >> 4) & 0x0F)
	} else {
		mantissa = uint8((sample >> (exponent + 3)) & 0x0F)
	}

	alaw := sign | (exponent << 4) | mantissa
	return alaw ^ 0xD5
}

// GenerateBeep generates PCMA samples for a given frequency and duration.
func GenerateBeep(freq float64, duration time.Duration) []byte {
	sampleRate := 8000
	numSamples := int(float64(sampleRate) * duration.Seconds())
	data := make([]byte, numSamples)

	for i := 0; i < numSamples; i++ {
		t := float64(i) / float64(sampleRate)
		// Sine wave
		val := int16(28000.0 * math.Sin(2.0*math.Pi*freq*t))
		data[i] = LinearToAlaw(val)
	}
	return data
}

// buildRTPPacket builds a standard 12-byte RTP header and appends the payload.
func buildRTPPacket(payloadType uint8, seqNum uint16, timestamp uint32, ssrc uint32, payload []byte) []byte {
	packet := make([]byte, 12+len(payload))
	// Version: 2, Padding: 0, Extension: 0, CSRC count: 0 => 0x80
	packet[0] = 0x80
	// Marker: 0, Payload Type
	packet[1] = payloadType & 0x7F
	// Sequence Number
	packet[2] = uint8(seqNum >> 8)
	packet[3] = uint8(seqNum & 0xFF)
	// Timestamp
	packet[4] = uint8(timestamp >> 24)
	packet[5] = uint8((timestamp >> 16) & 0xFF)
	packet[6] = uint8((timestamp >> 8) & 0xFF)
	packet[7] = uint8(timestamp & 0xFF)
	// SSRC
	packet[8] = uint8(ssrc >> 24)
	packet[9] = uint8((ssrc >> 16) & 0xFF)
	packet[10] = uint8((ssrc >> 8) & 0xFF)
	packet[11] = uint8(ssrc & 0xFF)

	// Copy payload
	copy(packet[12:], payload)
	return packet
}

// PlayTone plays a synthesized PCMA audio buffer as plain RTP over UDP directly to the client.
// Returns true if playback completed naturally, or false if cancelled.
func (m *MediaFunction) PlayTone(session *SessionInfo, data []byte) bool {
	session.mu.Lock()
	destIP := session.RtpIP
	destPort := session.RtpAudioPort
	conn := session.AudioConn
	session.mu.Unlock()

	if conn == nil || destIP == "" || destPort == 0 {
		log.Printf("[MF] ⚠️ Cannot play tone: socket or remote RTP address not set (IP=%s, Port=%d)", destIP, destPort)
		return false
	}

	destAddr := &net.UDPAddr{IP: net.ParseIP(destIP), Port: destPort}

	session.audioMu.Lock()
	if session.audioCancel != nil {
		close(session.audioCancel)
		session.audioCancel = nil
		time.Sleep(50 * time.Millisecond) // Let previous playback exit
	}

	cancelChan := make(chan struct{})
	session.audioCancel = cancelChan
	session.audioMu.Unlock()

	defer func() {
		session.audioMu.Lock()
		if session.audioCancel == cancelChan {
			session.audioCancel = nil
		}
		session.audioMu.Unlock()
	}()

	packetSize := 160 // 20ms at 8kHz PCMA
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()

	seqNum := uint16(1234)
	timestamp := uint32(5678)
	ssrc := uint32(987654)

	for idx := 0; idx < len(data); idx += packetSize {
		select {
		case <-cancelChan:
			return false
		default:
		}

		end := idx + packetSize
		if end > len(data) {
			end = len(data)
		}

		// Pad with silence if short
		packet := make([]byte, packetSize)
		copy(packet, data[idx:end])
		if end-idx < packetSize {
			for i := end - idx; i < packetSize; i++ {
				packet[i] = 0x55 // PCMA silence
			}
		}

		select {
		case <-cancelChan:
			return false
		case <-ticker.C:
		}

		rtpPacket := buildRTPPacket(8, seqNum, timestamp, ssrc, packet)
		_, _ = conn.WriteTo(rtpPacket, destAddr)
		seqNum++
		timestamp += 160
	}
	return true
}

// PlayAnnouncement plays a named audio file (e.g. unreachable.pcma) and then invokes callbackURL if provided.
func (m *MediaFunction) PlayAnnouncement(session *SessionInfo, fileName string, callbackURL string) {
	// Try multiple possible paths
	paths := []string{
		fileName,
		"/app/" + fileName + ".pcma",
		"/app/" + fileName,
		"/app/static/" + fileName + ".pcma",
		"/app/static/" + fileName,
		"./static/" + fileName + ".pcma",
		"./static/" + fileName,
		"static/" + fileName + ".pcma",
		fileName + ".pcma",
	}

	var audioData []byte
	var err error
	for _, p := range paths {
		audioData, err = os.ReadFile(p)
		if err == nil && len(audioData) > 0 {
			log.Printf("[MF] Loaded announcement audio from %s (%d bytes)", p, len(audioData))
			break
		}
	}

	if err != nil || len(audioData) == 0 {
		log.Printf("[MF] ⚠️ Announcement audio %s not found. Generating fallback beep...", fileName)
		audioData = make([]byte, 0)
		beep := GenerateBeep(440, 500*time.Millisecond)
		silence := make([]byte, 4000)
		for i := range silence {
			silence[i] = 0x55
		}
		for i := 0; i < 4; i++ {
			audioData = append(audioData, beep...)
			audioData = append(audioData, silence...)
		}
	}

	log.Printf("[MF] Playing announcement for session %s (callID=%s, %d bytes)", session.ID, session.CallID, len(audioData))
	completed := m.PlayTone(session, audioData)
	log.Printf("[MF] PlayAnnouncement for session %s (callID=%s) finished. CompletedNaturally=%v", session.ID, session.CallID, completed)

	if completed && callbackURL != "" {
		go func() {
			reqBody, _ := json.Marshal(map[string]string{
				"call_id": session.CallID,
				"status":  "completed",
			})
			resp, err := http.Post(callbackURL, "application/json", bytes.NewReader(reqBody))
			if err != nil {
				log.Printf("[MF] Announcement callback to %s failed: %v", callbackURL, err)
			} else {
				resp.Body.Close()
				log.Printf("[MF] Announcement callback to %s succeeded (status %d)", callbackURL, resp.StatusCode)
			}
		}()
	}
}

// PlayHotlineAction plays a distinct tone pattern based on the IVR action.
func (m *MediaFunction) PlayHotlineAction(session *SessionInfo, action string) {
	var data []byte

	switch action {
	case "welcome":
		// Welcome: A chord or ascending tones representing welcome (440Hz, 554Hz, 659Hz, 880Hz)
		d1 := GenerateBeep(440, 200*time.Millisecond)
		d2 := GenerateBeep(554, 200*time.Millisecond)
		d3 := GenerateBeep(659, 200*time.Millisecond)
		d4 := GenerateBeep(880, 400*time.Millisecond)
		data = append(data, d1...)
		data = append(data, d2...)
		data = append(data, d3...)
		data = append(data, d4...)

	case "lock_card", "time":
		// Lock Card: A warning double beep (800Hz, 150ms beep, 100ms pause, 800Hz, 400ms beep)
		d1 := GenerateBeep(800, 150*time.Millisecond)
		silence := make([]byte, 800) // 100ms silence at 8kHz is 800 samples
		for i := range silence {
			silence[i] = 0x55
		}
		d2 := GenerateBeep(800, 400*time.Millisecond)
		data = append(data, d1...)
		data = append(data, silence...)
		data = append(data, d2...)

	case "query_balance", "story":
		// Query Balance: Multi-tone coin register sound (500Hz 100ms, 1000Hz 100ms, 1500Hz 300ms)
		d1 := GenerateBeep(500, 100*time.Millisecond)
		d2 := GenerateBeep(1000, 100*time.Millisecond)
		d3 := GenerateBeep(1500, 300*time.Millisecond)
		data = append(data, d1...)
		data = append(data, d2...)
		data = append(data, d3...)

	case "meet_agent", "agent":
		// Meet Agent: Try reading real welcome audio if exists, otherwise fallback to mixed tone
		agentAudio, err := os.ReadFile("/app/agent.pcma")
		if err == nil {
			log.Printf("[MF] Found real welcome audio at /app/agent.pcma. Streaming to session...")
			data = agentAudio
		} else {
			log.Printf("[MF] /app/agent.pcma not found (%v). Falling back to telephone ringtone...", err)
			sampleRate := 8000
			ringDuration := 1 * time.Second
			numSamples := int(float64(sampleRate) * ringDuration.Seconds())
			ring := make([]byte, numSamples)
			for i := 0; i < numSamples; i++ {
				t := float64(i) / float64(sampleRate)
				v1 := math.Sin(2.0 * math.Pi * 400.0 * t)
				v2 := math.Sin(2.0 * math.Pi * 450.0 * t)
				val := int16(14000.0 * (v1 + v2))
				ring[i] = LinearToAlaw(val)
			}
			silence := make([]byte, sampleRate)
			for i := range silence {
				silence[i] = 0x55
			}
			data = append(data, ring...)
			data = append(data, silence...)
			data = append(data, ring...)
		}

	default:
		return
	}

	m.PlayTone(session, data)
}

// PlayVideoAction searches for a hotline session by CallID and triggers video playback.
func (m *MediaFunction) PlayVideoAction(callID string, action string) error {
	m.mu.Lock()
	var targetSession *SessionInfo
	for _, session := range m.sessions {
		if session.CallID == callID && session.IsHotline {
			targetSession = session
			break
		}
	}
	m.mu.Unlock()

	if targetSession == nil {
		return fmt.Errorf("session not found for call %s", callID)
	}

	log.Printf("[MF] Found session %s for call %s. Starting H.264 video streaming over plain RTP...", targetSession.ID, callID)
	go m.PlayVideo(targetSession, action)
	return nil
}

func readNALUs(filename string) ([][]byte, error) {
	data, err := os.ReadFile(filename)
	if err != nil {
		return nil, err
	}
	var nalus [][]byte
	n := len(data)
	i := 0
	for i < n {
		if i+3 < n && data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1 {
			start := i + 4
			i += 4
			for i < n {
				if i+3 < n && data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1 {
					break
				}
				if i+2 < n && data[i] == 0 && data[i+1] == 0 && data[i+2] == 1 {
					break
				}
				i++
			}
			nalus = append(nalus, data[start:i])
		} else if i+2 < n && data[i] == 0 && data[i+1] == 0 && data[i+2] == 1 {
			start := i + 3
			i += 3
			for i < n {
				if i+3 < n && data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1 {
					break
				}
				if i+2 < n && data[i] == 0 && data[i+1] == 0 && data[i+2] == 1 {
					break
				}
				i++
			}
			nalus = append(nalus, data[start:i])
		} else {
			i++
		}
	}
	return nalus, nil
}

// PlayVideo streams a 10-second H.264 video and PCMA audio file as plain RTP over UDP.
func (m *MediaFunction) PlayVideo(session *SessionInfo, action string) {
	session.mu.Lock()
	destIP := session.RtpIP
	destPort := session.RtpVideoPort
	conn := session.VideoConn
	session.mu.Unlock()

	if conn == nil || destIP == "" || destPort == 0 {
		log.Printf("[MF] ⚠️ Cannot play video: socket or remote RTP address not set (IP=%s, Port=%d)", destIP, destPort)
		return
	}

	destAddr := &net.UDPAddr{IP: net.ParseIP(destIP), Port: destPort}

	// 1. Play real PCMA audio from file if exists, otherwise fallback to mock beep sound
	go func() {
		audioData, err := os.ReadFile("/app/video.pcma")
		if err == nil {
			log.Printf("[MF] Streaming real PCMA audio from /app/video.pcma...")
			m.PlayTone(session, audioData)
		} else {
			log.Printf("[MF] /app/video.pcma not found (%v). Falling back to mock beep audio...", err)
			funnySound := make([]byte, 0)
			beep := GenerateBeep(600, 500*time.Millisecond)
			silence := make([]byte, 4000) // 500ms silence at 8kHz PCMA
			for i := range silence {
				silence[i] = 0x55
			}
			for i := 0; i < 10; i++ {
				funnySound = append(funnySound, beep...)
				funnySound = append(funnySound, silence...)
			}
			m.PlayTone(session, funnySound)
		}
	}()

	// 2. Play real H.264 NAL units from file if exists, otherwise fallback to dummy video packets
	nalus, err := readNALUs("/app/video.h264")
	if err == nil {
		log.Printf("[MF] Found %d NAL units in /app/video.h264. Streaming real H.264 over RTP...", len(nalus))
		seqNum := uint16(5678)
		timestamp := uint32(123456)
		ssrc := uint32(112233)
		ticker := time.NewTicker(33 * time.Millisecond) // ~30fps
		defer ticker.Stop()

		for idx, nalu := range nalus {
			select {
			case <-ticker.C:
			}

			session.mu.Lock()
			activeConn := session.VideoConn
			session.mu.Unlock()
			if activeConn == nil {
				break
			}

			rtpP := buildRTPPacket(97, seqNum, timestamp, ssrc, nalu)
			_, err = conn.WriteTo(rtpP, destAddr)
			if err != nil {
				log.Printf("[MF] H.264 RTP send error: %v", err)
				break
			}
			seqNum++

			// Increase timestamp for slice NAL types (1 = non-IDR, 5 = IDR keyframe)
			naluType := nalu[0] & 0x1F
			if naluType == 1 || naluType == 5 {
				timestamp += 3000
			}

			if idx > 1500 { // limit total sent frames
				break
			}
		}
	} else {
		log.Printf("[MF] /app/video.h264 not found (%v). Falling back to mock video...", err)
		seqNum := uint16(5678)
		timestamp := uint32(123456)
		ssrc := uint32(112233)
		ticker := time.NewTicker(33 * time.Millisecond)
		defer ticker.Stop()
		dummyH264 := []byte{0x00, 0x00, 0x00, 0x01, 0x09, 0xf0}
		for frameIdx := 0; frameIdx < 300; frameIdx++ {
			select {
			case <-ticker.C:
			}
			rtpP := buildRTPPacket(97, seqNum, timestamp, ssrc, dummyH264)
			_, _ = conn.WriteTo(rtpP, destAddr)
			seqNum++
			timestamp += 3000
		}
	}

	log.Printf("[MF] Finished H.264 video streaming on session %s", session.ID)
}

// streamTranscodedFrame simulates transcoding incoming SCTP coordinate data (Whiteboard/Caro)
// to standard RTP H.264 video stream packets towards the legacy MTSI UE.
func (m *MediaFunction) streamTranscodedFrame(session *SessionInfo, rawSctpData []byte) {
	session.mu.Lock()
	destIP := session.RtpIP
	destPort := session.RtpVideoPort
	conn := session.VideoConn
	session.mu.Unlock()

	if conn == nil || destIP == "" || destPort == 0 {
		return
	}

	destAddr := &net.UDPAddr{IP: net.ParseIP(destIP), Port: destPort}
	log.Printf("[MF User Plane Gateway] Decapsulated SCTP data chunk (size %d). Transcoding drawing event to H.264 video RTP packet towards legacy callee...", len(rawSctpData))

	// Load one H.264 slice from the local file
	nalus, err := readNALUs("/app/video.h264")
	var nalu []byte
	if err == nil && len(nalus) > 0 {
		// Use a random or simple index NAL unit
		nalu = nalus[time.Now().UnixNano()%int64(len(nalus))]
	} else {
		// Fallback dummy H.264 slice
		nalu = []byte{0x00, 0x00, 0x00, 0x01, 0x09, 0xf0}
	}

	seqNum := uint16(9999 + time.Now().UnixNano()%1000)
	timestamp := uint32(time.Now().UnixNano() / 1000000)
	ssrc := uint32(998877)

	rtpPacket := buildRTPPacket(97, seqNum, timestamp, ssrc, nalu)
	_, _ = conn.WriteTo(rtpPacket, destAddr)
	log.Printf("[MF User Plane Gateway] Transcoded H.264 video packet successfully written to UDP/RTP socket")
}
