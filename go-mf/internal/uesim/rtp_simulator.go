package uesim

import (
	"bytes"
	"log"
	"net"
	"os"
	"sync/atomic"
	"time"
)

func (ue *UESimulator) listenPlainRTP(conn *net.UDPConn, mediaType string) {
	buf := make([]byte, 2048)
	for {
		n, addr, err := conn.ReadFrom(buf)
		if err != nil {
			return
		}
		// Validate minimum RTP header (12 bytes) and version 2
		if n < 12 || (buf[0]>>6) != 2 {
			continue
		}
		if addr != nil {
			udpAddr := addr.(*net.UDPAddr)
			ue.mu.Lock()
			ue.RtpIP = udpAddr.IP.String()
			if mediaType == "audio" {
				ue.RtpAudioPort = udpAddr.Port
			} else {
				ue.RtpVideoPort = udpAddr.Port
			}
			ue.mu.Unlock()
		}
		// Count received RTP packets
		if mediaType == "audio" {
			atomic.AddInt64(&ue.RtpAudioReceived, 1)
		} else {
			atomic.AddInt64(&ue.RtpVideoReceived, 1)
		}
		if cb := ue.OnRtpActivity; cb != nil {
			cb(mediaType, "received")
		}
	}
}

func (ue *UESimulator) startDummyMediaLoop() {
	// 1. Audio Loop (PCMA)
	go func() {
		// Try various paths for video.pcma
		paths := []string{
			"./static/video.pcma",
			"../static/video.pcma",
			"./video.pcma",
			"/app/video.pcma",
			"/app/static/video.pcma",
		}
		var fileData []byte
		var err error
		for _, p := range paths {
			fileData, err = os.ReadFile(p)
			if err == nil {
				log.Printf("[UE-%s] Loaded audio file from path: %s (Size: %d bytes)", ue.username, p, len(fileData))
				break
			}
		}

		ticker := time.NewTicker(20 * time.Millisecond)
		defer ticker.Stop()

		dummyPCMA := make([]byte, 160)
		for i := range dummyPCMA {
			dummyPCMA[i] = 0x55
		}

		seqNum := uint16(2345)
		timestamp := uint32(6789)
		ssrc := uint32(98765)
		offset := 0

		for range ticker.C {
			ue.mu.Lock()
			destIP := ue.RtpIP
			destPort := ue.RtpAudioPort
			conn := ue.AudioConn
			pc := ue.bdcPC
			ue.mu.Unlock()

			if pc == nil || pc.ConnectionState() == 5 { // Closed
				return
			}

			if conn != nil && destIP != "" && destPort != 0 {
				destAddr := &net.UDPAddr{IP: net.ParseIP(destIP), Port: destPort}
				
				var audioFrame []byte
				if len(fileData) > 0 {
					audioFrame = make([]byte, 160)
					if offset+160 <= len(fileData) {
						copy(audioFrame, fileData[offset:offset+160])
						offset += 160
					} else {
						copy(audioFrame, fileData[offset:])
						offset = 0 // loop back
					}
				} else {
					audioFrame = dummyPCMA
				}

				rtpPacket := buildRTPPacket(8, seqNum, timestamp, ssrc, audioFrame)
				_, _ = conn.WriteTo(rtpPacket, destAddr)
				atomic.AddInt64(&ue.RtpAudioSent, 1)
				if cb := ue.OnRtpActivity; cb != nil {
					cb("audio", "sent")
				}
				seqNum++
				timestamp += 160
			}
		}
	}()

	// 2. Video Loop (H264 NAL Units Annex-B)
	go func() {
		// Try various paths for video.h264
		paths := []string{
			"./static/video.h264",
			"../static/video.h264",
			"./video.h264",
			"/app/video.h264",
			"/app/static/video.h264",
		}
		var fileData []byte
		var err error
		for _, p := range paths {
			fileData, err = os.ReadFile(p)
			if err == nil {
				log.Printf("[UE-%s] Loaded video file from path: %s (Size: %d bytes)", ue.username, p, len(fileData))
				break
			}
		}

		// Split into NAL units
		var nals [][]byte
		if len(fileData) > 0 {
			nals = splitAnnexBNALUnits(fileData)
			log.Printf("[UE-%s] Parsed %d NAL units from H264 file", ue.username, len(nals))
		}

		ticker := time.NewTicker(33 * time.Millisecond)
		defer ticker.Stop()

		dummyH264 := []byte{0x00, 0x00, 0x00, 0x01, 0x09, 0xf0}
		seqNum := uint16(6789)
		timestamp := uint32(23456)
		ssrc := uint32(12345)
		nalIndex := 0

		for range ticker.C {
			ue.mu.Lock()
			destIP := ue.RtpIP
			destPort := ue.RtpVideoPort
			conn := ue.VideoConn
			pc := ue.bdcPC
			ue.mu.Unlock()

			if pc == nil || pc.ConnectionState() == 5 { // Closed
				return
			}

			if conn != nil && destIP != "" && destPort != 0 {
				destAddr := &net.UDPAddr{IP: net.ParseIP(destIP), Port: destPort}
				
				if len(nals) > 0 {
					nal := nals[nalIndex]
					sendH264NALUnit(conn, destAddr, &seqNum, timestamp, ssrc, nal)
					nalIndex = (nalIndex + 1) % len(nals)
				} else {
					rtpPacket := buildRTPPacket(97, seqNum, timestamp, ssrc, dummyH264)
					_, _ = conn.WriteTo(rtpPacket, destAddr)
					seqNum++
				}
				atomic.AddInt64(&ue.RtpVideoSent, 1)
				if cb := ue.OnRtpActivity; cb != nil {
					cb("video", "sent")
				}
				timestamp += 3000
			}
		}
	}()
}

// GetRtpStats returns current RTP packet counters atomically
func (ue *UESimulator) GetRtpStats() (audioSent, audioRecv, videoSent, videoRecv int64) {
	audioSent = atomic.LoadInt64(&ue.RtpAudioSent)
	audioRecv = atomic.LoadInt64(&ue.RtpAudioReceived)
	videoSent = atomic.LoadInt64(&ue.RtpVideoSent)
	videoRecv = atomic.LoadInt64(&ue.RtpVideoReceived)
	return
}

// ResetRtpStats resets all RTP counters to zero
func (ue *UESimulator) ResetRtpStats() {
	atomic.StoreInt64(&ue.RtpAudioSent, 0)
	atomic.StoreInt64(&ue.RtpAudioReceived, 0)
	atomic.StoreInt64(&ue.RtpVideoSent, 0)
	atomic.StoreInt64(&ue.RtpVideoReceived, 0)
}

func splitAnnexBNALUnits(data []byte) [][]byte {
	var nals [][]byte
	idx := 0
	total := len(data)
	
	// Helper to find next start code index
	findStartCode := func(start int) (int, int) {
		for i := start; i < total-3; i++ {
			if data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1 {
				return i, 4
			}
			if data[i] == 0 && data[i+1] == 0 && data[i+2] == 1 {
				return i, 3
			}
		}
		return -1, 0
	}

	currIdx, codeLen := findStartCode(0)
	if currIdx == -1 {
		return [][]byte{data}
	}
	
	idx = currIdx + codeLen

	for {
		nextIdx, nextCodeLen := findStartCode(idx)
		if nextIdx == -1 {
			// Last NAL unit
			nals = append(nals, data[idx:])
			break
		}
		
		nals = append(nals, data[idx:nextIdx])
		idx = nextIdx + nextCodeLen
	}
	
	// Filter out empty NAL units or simple delimiters
	var filtered [][]byte
	for _, nal := range nals {
		cleaned := bytes.TrimSpace(nal)
		if len(cleaned) > 0 {
			filtered = append(filtered, cleaned)
		}
	}
	
	return filtered
}

func sendH264NALUnit(conn *net.UDPConn, destAddr *net.UDPAddr, seqNum *uint16, timestamp uint32, ssrc uint32, nal []byte) {
	if len(nal) == 0 {
		return
	}
	
	// If the NAL unit is small enough, send it in a single RTP packet
	if len(nal) <= 1200 {
		rtpPacket := buildRTPPacket(97, *seqNum, timestamp, ssrc, nal)
		_, _ = conn.WriteTo(rtpPacket, destAddr)
		*seqNum++
		return
	}
	
	// FU-A Fragmentation
	nalHeader := nal[0]
	nalType := nalHeader & 0x1F
	nalNRI := nalHeader & 0x60
	
	fuIndicator := nalNRI | 28
	payload := nal[1:]
	
	chunkSize := 1200
	offset := 0
	total := len(payload)
	
	for offset < total {
		chunkLen := chunkSize
		if offset+chunkLen > total {
			chunkLen = total - offset
		}
		
		var fuHeader byte
		if offset == 0 {
			fuHeader = 0x80 | nalType // Start bit
		} else if offset+chunkLen == total {
			fuHeader = 0x40 | nalType // End bit
		} else {
			fuHeader = nalType
		}
		
		fuPayload := make([]byte, 2+chunkLen)
		fuPayload[0] = fuIndicator
		fuPayload[1] = fuHeader
		copy(fuPayload[2:], payload[offset:offset+chunkLen])
		
		rtpPacket := buildRTPPacket(97, *seqNum, timestamp, ssrc, fuPayload)
		_, _ = conn.WriteTo(rtpPacket, destAddr)
		*seqNum++
		offset += chunkLen
	}
}

func buildRTPPacket(payloadType uint8, seqNum uint16, timestamp uint32, ssrc uint32, payload []byte) []byte {
	packet := make([]byte, 12+len(payload))
	packet[0] = 0x80
	packet[1] = payloadType & 0x7F
	packet[2] = uint8(seqNum >> 8)
	packet[3] = uint8(seqNum & 0xFF)
	packet[4] = uint8(timestamp >> 24)
	packet[5] = uint8((timestamp >> 16) & 0xFF)
	packet[6] = uint8((timestamp >> 8) & 0xFF)
	packet[7] = uint8(timestamp & 0xFF)
	packet[8] = uint8(ssrc >> 24)
	packet[9] = uint8((ssrc >> 16) & 0xFF)
	packet[10] = uint8((ssrc >> 8) & 0xFF)
	packet[11] = uint8(ssrc & 0xFF)
	copy(packet[12:], payload)
	return packet
}
