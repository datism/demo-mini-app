package imsas

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

type HEPClient struct {
	serverAddr string
	enabled    bool
}

var globalHEPClient *HEPClient

func InitHEP() {
	enabledEnv := os.Getenv("ENABLE_HEP_SELF_LOGGING")
	if enabledEnv != "true" {
		globalHEPClient = &HEPClient{enabled: false}
		return
	}
	host := os.Getenv("HEP_SERVER_HOST")
	port := os.Getenv("HEP_SERVER_PORT")
	if host == "" {
		log.Println("[HEP] No HEP_SERVER_HOST configured. HEP tracing disabled.")
		globalHEPClient = &HEPClient{enabled: false}
		return
	}
	if port == "" {
		port = "9060"
	}
	addr := fmt.Sprintf("%s:%s", host, port)
	log.Printf("[HEP] Initialized HEP Client targeting %s", addr)
	globalHEPClient = &HEPClient{
		serverAddr: addr,
		enabled:    true,
	}
}

func SendHEP(payload string, srcIP string, srcPort int, dstIP string, dstPort int, callID string) {
	SendHEPProto(payload, srcIP, srcPort, dstIP, dstPort, 1, callID)
}

func SendHEPHTTP(payload string, srcIP string, srcPort int, dstIP string, dstPort int, callID string) {
	SendHEPProto(payload, srcIP, srcPort, dstIP, dstPort, 100, callID)
}

func SendHEPRTCP(payload []byte, srcIP string, srcPort int, dstIP string, dstPort int, callID string) {
	SendHEPProtoBytes(payload, srcIP, srcPort, dstIP, dstPort, 5, callID)
}

func SendHEPDataChannel(payload string, srcIP string, srcPort int, dstIP string, dstPort int, callID string) {
	SendHEPProto(payload, srcIP, srcPort, dstIP, dstPort, 100, callID)
}

func SendHEPProto(payload string, srcIP string, srcPort int, dstIP string, dstPort int, protoID byte, callID string) {
	SendHEPProtoBytes([]byte(payload), srcIP, srcPort, dstIP, dstPort, protoID, callID)
}

func SendHEPProtoBytes(payload []byte, srcIP string, srcPort int, dstIP string, dstPort int, protoID byte, callID string) {
	if globalHEPClient == nil {
		InitHEP()
	}
	if !globalHEPClient.enabled {
		return
	}

	go func() {
		packet, err := buildHEPPacket(payload, srcIP, srcPort, dstIP, dstPort, protoID, callID)
		if err != nil {
			log.Printf("[HEP] Error building HEP packet: %v", err)
			return
		}

		// Skip oversized UDP packets silently (max UDP datagram = 65535 bytes)
		if len(packet) > 65000 {
			log.Printf("[HEP] Skipping oversized HEP packet (%d bytes) for call %s", len(packet), callID)
			return
		}

		conn, err := net.Dial("udp", globalHEPClient.serverAddr)
		if err != nil {
			log.Printf("[HEP] Error dialing UDP socket to %s: %v", globalHEPClient.serverAddr, err)
			return
		}
		defer conn.Close()
		_, err = conn.Write(packet)
		if err != nil {
			if strings.Contains(err.Error(), "message too long") {
				log.Printf("[HEP] Packet too large for UDP (%d bytes), skipping", len(packet))
			} else {
				log.Printf("[HEP] Error writing HEP packet to socket: %v", err)
			}
		}
	}()
}

func buildHEPPacket(payload []byte, srcIP string, srcPort int, dstIP string, dstPort int, protoID byte, callID string) ([]byte, error) {
	var chunks [][]byte

	// 1. IP Protocol Family (IPv4 = 2)
	chunks = append(chunks, makeChunk(0x0001, []byte{2}))

	// 2. IP Protocol ID (UDP = 17)
	chunks = append(chunks, makeChunk(0x0002, []byte{17}))

	// 3. Source IP
	sip := net.ParseIP(srcIP).To4()
	if sip == nil {
		sip = net.IPv4(127, 0, 0, 1).To4()
	}
	chunks = append(chunks, makeChunk(0x0003, sip))

	// 4. Destination IP
	dip := net.ParseIP(dstIP).To4()
	if dip == nil {
		dip = net.IPv4(127, 0, 0, 1).To4()
	}
	chunks = append(chunks, makeChunk(0x0004, dip))

	// 5. Source Port
	spBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(spBytes, uint16(srcPort))
	chunks = append(chunks, makeChunk(0x0007, spBytes))

	// 6. Destination Port
	dpBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(dpBytes, uint16(dstPort))
	chunks = append(chunks, makeChunk(0x0008, dpBytes))

	// 7. Timestamp Seconds & Microseconds
	now := time.Now()
	secBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(secBytes, uint32(now.Unix()))
	chunks = append(chunks, makeChunk(0x0009, secBytes))

	usecBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(usecBytes, uint32(now.Nanosecond()/1000))
	chunks = append(chunks, makeChunk(0x000a, usecBytes))

	// 8. Protocol Type
	chunks = append(chunks, makeChunk(0x000b, []byte{protoID}))

	// 9. Capture Agent ID (4 bytes)
	agentBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(agentBytes, 2026) // Arbitrary agent ID
	chunks = append(chunks, makeChunk(0x000c, agentBytes))

	// 11. Correlation ID (Call-ID)
	if callID != "" {
		chunks = append(chunks, makeChunk(0x0011, []byte(callID)))
	}

	// 10. Payload
	chunks = append(chunks, makeChunk(0x000f, payload))

	// Total length calculation
	totalLen := 6 // "HEP3" + length (2 bytes)
	for _, chunk := range chunks {
		totalLen += len(chunk)
	}

	packet := make([]byte, totalLen)
	copy(packet[0:4], "HEP3")
	binary.BigEndian.PutUint16(packet[4:6], uint16(totalLen))

	offset := 6
	for _, chunk := range chunks {
		copy(packet[offset:], chunk)
		offset += len(chunk)
	}

	return packet, nil
}

func makeChunk(chunkType uint16, value []byte) []byte {
	chunkLen := 6 + len(value)
	b := make([]byte, chunkLen)
	// Vendor ID (0x0000)
	binary.BigEndian.PutUint16(b[0:2], 0)
	// Chunk Type
	binary.BigEndian.PutUint16(b[2:4], chunkType)
	// Chunk Length
	binary.BigEndian.PutUint16(b[4:6], uint16(chunkLen))
	// Chunk Value
	copy(b[6:], value)
	return b
}

func extractCallIDFromHTTP(req *http.Request, body string) string {
	if cid := req.Header.Get("X-Call-ID"); cid != "" {
		return cid
	}
	if body != "" {
		var temp map[string]interface{}
		if err := json.Unmarshal([]byte(body), &temp); err == nil {
			for _, key := range []string{"sessionId", "call_id", "contextId", "eventId"} {
				if val, ok := temp[key].(string); ok && val != "" {
					return val
				}
			}
			if notificationEvent, ok := temp["notificationEvent"].(map[string]interface{}); ok {
				if cid, ok := notificationEvent["sessionId"].(string); ok && cid != "" {
					return cid
				}
			}
		}
	}
	if cid := req.URL.Query().Get("callid"); cid != "" {
		return cid
	}
	if cid := req.URL.Query().Get("session_id"); cid != "" {
		return cid
	}
	parts := strings.Split(req.URL.Path, "/")
	for i, p := range parts {
		if (p == "call-sessions" || p == "contexts" || p == "session-event-subscriptions") && i+1 < len(parts) {
			if parts[i+1] != "" && !strings.Contains(parts[i+1], "/") {
				return parts[i+1]
			}
		}
	}
	return ""
}

// HEPRoundTripper logs outgoing HTTP/2 requests and responses to Homer
type HEPRoundTripper struct {
	Proxied http.RoundTripper
}

func (h *HEPRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	// Client-side HTTP logging is disabled to eliminate duplicate trace logging.
	// Keep server-side logging active (via HEPMiddleware) to log the API requests.
	return h.Proxied.RoundTrip(req)
}

// HEPMiddleware logs incoming HTTP/2 requests to Homer
type hepResponseWriter struct {
	http.ResponseWriter
	statusCode int
	body       *bytes.Buffer
}

func (w *hepResponseWriter) WriteHeader(statusCode int) {
	w.statusCode = statusCode
	w.ResponseWriter.WriteHeader(statusCode)
}

func (w *hepResponseWriter) Write(b []byte) (int, error) {
	w.body.Write(b)
	return w.ResponseWriter.Write(b)
}

func (w *hepResponseWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (w *hepResponseWriter) CloseNotify() <-chan bool {
	if cn, ok := w.ResponseWriter.(http.CloseNotifier); ok {
		return cn.CloseNotify()
	}
	return nil
}

// HEPMiddleware logs incoming HTTP/2 requests and responses to Homer
func HEPMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host := os.Getenv("HEP_SERVER_HOST")
		if host == "" {
			next.ServeHTTP(w, r)
			return
		}

		// Read Request Body safely
		reqBody := ""
		if r.Body != nil {
			bodyBytes, err := io.ReadAll(r.Body)
			if err == nil {
				reqBody = string(bodyBytes)
				r.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))
			}
		}
		callID := extractCallIDFromHTTP(r, reqBody)

		// Prepare HTTP request dump
		reqDump := fmt.Sprintf("%s %s %s\r\n", r.Method, r.URL.RequestURI(), r.Proto)
		if callID != "" && r.Header.Get("X-Call-ID") == "" {
			reqDump += fmt.Sprintf("X-Call-ID: %s\r\n", callID)
		}
		for k, v := range r.Header {
			reqDump += fmt.Sprintf("%s: %s\r\n", k, strings.Join(v, ", "))
		}
		reqDump += "\r\n" + reqBody

		srcIP, srcPortStr, _ := net.SplitHostPort(r.RemoteAddr)
		srcPort, _ := strconv.Atoi(srcPortStr)
		dstIP := "127.0.0.1"
		dstPort := 80
		if localAddr, ok := r.Context().Value(http.LocalAddrContextKey).(net.Addr); ok {
			if tcpAddr, ok := localAddr.(*net.TCPAddr); ok {
				dstIP = tcpAddr.IP.String()
				dstPort = tcpAddr.Port
			}
		}

		SendHEPHTTP(reqDump, srcIP, srcPort, dstIP, dstPort, callID)

		// Wrap ResponseWriter to capture Response
		wrapper := &hepResponseWriter{
			ResponseWriter: w,
			statusCode:     http.StatusOK,
			body:           bytes.NewBuffer(nil),
		}

		next.ServeHTTP(wrapper, r)

		// Prepare HTTP response dump
		respDump := fmt.Sprintf("%s %d %s\r\n", r.Proto, wrapper.statusCode, http.StatusText(wrapper.statusCode))
		if callID != "" {
			respDump += fmt.Sprintf("X-Call-ID: %s\r\n", callID)
		}
		for k, v := range wrapper.Header() {
			respDump += fmt.Sprintf("%s: %s\r\n", k, strings.Join(v, ", "))
		}
		respDump += "\r\n" + wrapper.body.String()

		SendHEPHTTP(respDump, dstIP, dstPort, srcIP, srcPort, callID)
	})
}
