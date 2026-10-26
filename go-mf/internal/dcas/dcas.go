// Package dcas implements the 3GPP Data Channel Application Server (DCAS).
// It powers the interactive real-time applications delivered over IMS Data Channels,
// including Caro Game (Gomoku), Whiteboard Drawing Sync, FunCalling Emoji Reactions,
// Hotline 198 CSKH Smart Menu, and Enterprise Caller ID & Promotion.
//
// Key Responsibilities:
// 1. WebRTC DataChannel Message Processing: Consumes JSON payloads from Stream 1000/1001.
// 2. Interactive Game State Engine: Coordinates turns, board states, scoring, and win conditions.
// 3. Redis State Synchronization: Persists multiplayer game and call sessions across replicas.
package dcas

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/demo/ims-dc-mf/internal/imsas"
	"context"
	"github.com/gorilla/websocket"
	"github.com/redis/go-redis/v9"
	"golang.org/x/net/http2"
)

var (
	ctx         = context.Background()
	redisClient redis.UniversalClient
)

func newH2CClient(timeout time.Duration) *http.Client {
	return &http.Client{
		Transport: &imsas.HEPRoundTripper{
			Proxied: &http2.Transport{
				AllowHTTP: true,
				DialTLS: func(network, addr string, cfg *tls.Config) (net.Conn, error) {
					return net.Dial(network, addr)
				},
			},
		},
		Timeout: timeout,
	}
}



type DCASServer struct {
	httpAddr             string
	tcpAddr              string
	dcsfAddr             string
	upgrader             websocket.Upgrader
	tcpConns             map[string]net.Conn        // session_id-streamID -> plain TCP conn (MDC2 per TS 29.176)
	connections          map[string]*websocket.Conn // session_id-streamID -> ws conn
	sessionCalls         map[string]string          // session_id -> call_id
	callSessions         map[string][]string        // call_id -> list of session_ids
	games                map[string]*CaroGame       // call_id -> game state
	interworkingSessions map[string]string          // session_id -> legacy peer CalledIdentity
	mu                   sync.RWMutex
	caroMu               sync.Mutex                   // dedicated mutex for caro state race conditions
	caroTimers           map[string]*time.Timer       // call_id -> pending turn timer, guarded by caroMu
	caroTimeoutFn        func(callID string, seq int) // nil = s.caroOnTimeout; overridden in tests
}

func NewDCASServer(httpAddr, tcpAddr, dcsfAddr string) *DCASServer {
	if redisClient == nil {
		redisClient = redis.NewFailoverClient(&redis.FailoverOptions{
			MasterName:    "mymaster",
			SentinelAddrs: []string{"redis-sentinel-service.demo-mini-app.svc.cluster.local:26379"},
		})
		if err := redisClient.Ping(ctx).Err(); err != nil {
			log.Printf("[DCAS-REDIS] Sentinel ping failed: %v. Falling back to direct Redis Master", err)
			redisClient = redis.NewClient(&redis.Options{
				Addr: "redis-service.demo-mini-app.svc.cluster.local:6379",
			})
		} else {
			log.Printf("[DCAS-REDIS] Connected to Redis via Sentinel (go-redis pool)")
		}
	}

	return &DCASServer{
		httpAddr: httpAddr,
		tcpAddr:  tcpAddr,
		dcsfAddr: dcsfAddr,
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool { return true },
		},
		tcpConns:             make(map[string]net.Conn),
		connections:          make(map[string]*websocket.Conn),
		sessionCalls:         make(map[string]string),
		callSessions:         make(map[string][]string),
		games:                make(map[string]*CaroGame),
		interworkingSessions: make(map[string]string),
		caroTimers:           make(map[string]*time.Timer),
	}
}

type MenuAction struct {
	Action string `json:"action"`
}

type MenuResponse struct {
	Response string `json:"response"`
}

type EnterpriseCallerIDRequest struct {
	Service string `json:"service"`
	Action  string `json:"action"`
}

type EnterpriseCallerIDResponse struct {
	Service            string `json:"service"`
	Action             string `json:"action,omitempty"`
	Status             string `json:"status,omitempty"`
	StatusMsg          string `json:"status_msg,omitempty"`
	EnterpriseName     string `json:"enterprise_name"`
	VerificationStatus string `json:"verification_status"`
	LogoURL            string `json:"logo_url"`
	CallIntent         string `json:"call_intent"`
	PromoText          string `json:"promo_text"`
	QrURL              string `json:"qr_url"`
	RedirectURL        string `json:"redirect_url,omitempty"`
}

func (s *DCASServer) Start() error {
	go s.subscribeToDCSF()
	go s.StartMdc2TcpServer(s.tcpAddr)
	go s.StartRedisPubSubListener()

	mux := http.NewServeMux()
	mux.HandleFunc("/mdc2", s.HandleWebSocket)
	mux.HandleFunc("/dcas-imsee/v1/notifications", s.HandleImsEENotification)

	log.Printf("[DCAS] HTTP REST server listening on http://%s, MDC2 Plain TCP server listening on %s per 3GPP TS 29.176", s.httpAddr, s.tcpAddr)
	return http.ListenAndServe(s.httpAddr, imsas.HEPMiddleware(mux))
}

func (s *DCASServer) StartMdc2TcpServer(tcpAddr string) {
	listener, err := net.Listen("tcp", tcpAddr)
	if err != nil {
		log.Printf("[DCAS] Failed to start MDC2 TCP server on %s: %v", tcpAddr, err)
		return
	}
	defer listener.Close()
	log.Printf("[DCAS] MDC2 Plain TCP Server listening on %s per 3GPP TS 29.176", tcpAddr)

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("[DCAS] MDC2 TCP accept error: %v", err)
			continue
		}
		go s.handleTcpMdc2Connection(conn)
	}
}

func (s *DCASServer) handleTcpMdc2Connection(conn net.Conn) {
	defer conn.Close()
	reader := bufio.NewReader(conn)

	// Read handshake header: CALL_ID:...\nSESSION_ID:...\nSTREAM_ID:...\n\n
	var callID, sessionID, streamID string
	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimSpace(line)
		if line == "" {
			break
		}
		if strings.HasPrefix(line, "CALL_ID:") {
			callID = strings.TrimPrefix(line, "CALL_ID:")
			// Strip route info if present e.g. callid@localip
			if strings.Contains(callID, "@") {
				callID = strings.Split(callID, "@")[0]
			}
		} else if strings.HasPrefix(line, "SESSION_ID:") {
			sessionID = strings.TrimPrefix(line, "SESSION_ID:")
		} else if strings.HasPrefix(line, "STREAM_ID:") {
			streamID = strings.TrimPrefix(line, "STREAM_ID:")
		}
	}

	if sessionID == "" {
		log.Printf("[DCAS] TCP connection missing sessionID, closing")
		return
	}

	key := sessionID + "-" + streamID
	s.mu.Lock()
	s.tcpConns[key] = conn
	if callID != "" {
		s.sessionCalls[sessionID] = callID
		if !containsString(s.callSessions[callID], sessionID) {
			s.callSessions[callID] = append(s.callSessions[callID], sessionID)
		}
		registerSessionInRedis(callID, sessionID)
		if streamID == "1000" {
			go s.checkLateJoinCaro(callID, sessionID)
		}
	}
	s.mu.Unlock()

	log.Printf("[DCAS] MF connected MDC2 TCP: CallID=%s, SessionID=%s, StreamID=%s", callID, sessionID, streamID)

	defer func() {
		s.mu.Lock()
		delete(s.tcpConns, key)
		s.cleanupSessionLocked(sessionID)
		s.mu.Unlock()
		log.Printf("[DCAS] MDC2 TCP connection closed for SessionID=%s StreamID=%s", sessionID, streamID)
	}()

	// 4-byte length header + payload loop
	lenBuf := make([]byte, 4)
	for {
		if _, err := io.ReadFull(reader, lenBuf); err != nil {
			break
		}
		msgLen := binary.BigEndian.Uint32(lenBuf)
		if msgLen == 0 || msgLen > 1000000 {
			break
		}
		payload := make([]byte, msgLen)
		if _, err := io.ReadFull(reader, payload); err != nil {
			break
		}

		s.processMdc2Payload(sessionID, streamID, payload)
	}
}

func (s *DCASServer) processMdc2Payload(sessionID, streamID string, payload []byte) {
	log.Printf("[DCAS] Received MDC2 payload from session %s (stream %s): %s", sessionID, streamID, string(payload))
	var data map[string]interface{}
	if err := json.Unmarshal(payload, &data); err != nil {
		s.handleSmartMenuMessage(sessionID, payload, nil)
		return
	}

	if service, ok := data["service"].(string); ok {
		switch service {
		case "caro":
			s.handleCaroMessage(sessionID, payload, nil)
		case "fun-calling":
			s.mu.RLock()
			callID := s.sessionCalls[sessionID]
			s.mu.RUnlock()
			s.handleFunCallingMessage(sessionID, callID, payload)
		case "smart-menu":
			s.handleSmartMenuMessage(sessionID, payload, nil)
		case "enterprise-callerid":
			s.handleEnterpriseCallerIDMessage(sessionID, payload)
		case "flight-booking":
			s.handleFlightBookingMessage(sessionID, payload)
		default:
			s.handleSmartMenuMessage(sessionID, payload, nil)
		}
	} else {
		s.handleSmartMenuMessage(sessionID, payload, nil)
	}
}

func (s *DCASServer) subscribeToDCSF() {
	time.Sleep(3 * time.Second)
	client := newH2CClient(5 * time.Second)
	subReq := map[string]interface{}{
		"eventId": "DC_INTERWORKING_REQUIRED",
		"dcInterworkingInfo": map[string]interface{}{
			"appIds": []string{"caro", "whiteboard"},
		},
		"notificationUri": fmt.Sprintf("http://dcas:8081/dcas-imsee/v1/notifications"),
	}
	bodyBytes, _ := json.Marshal(subReq)
	url := fmt.Sprintf("http://%s/nimsas-imsee/v1/subscriptions", s.dcsfAddr)
	log.Printf("[DCAS] Sending Nimsas_ImsEE_Subscribe to: %s", url)
	resp, err := client.Post(url, "application/json", bytes.NewBuffer(bodyBytes))
	if err != nil {
		log.Printf("[DCAS] Subscription failed: %v", err)
		return
	}
	defer resp.Body.Close()
	log.Printf("[DCAS] Subscription success: HTTP %d", resp.StatusCode)
}

func (s *DCASServer) HandleImsEENotification(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var notify struct {
		EventId        string `json:"eventId"`
		SessionId      string `json:"sessionId"`
		Ue2Information struct {
			CalledIdentity string `json:"calledIdentity"`
		} `json:"ue2Information"`
	}
	if err := json.NewDecoder(r.Body).Decode(&notify); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	log.Printf("[DCAS-ImsEE] Received Notification: EventId=%s, SessionId=%s, legacyPeer=%s",
		notify.EventId, notify.SessionId, notify.Ue2Information.CalledIdentity)

	s.mu.Lock()
	s.interworkingSessions[notify.SessionId] = notify.Ue2Information.CalledIdentity
	s.mu.Unlock()

	w.WriteHeader(http.StatusNoContent)
}

func (s *DCASServer) HandleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("[DCAS] Failed to upgrade WebSocket: %v", err)
		return
	}

	callID := r.URL.Query().Get("call_id")
	sessionID := r.URL.Query().Get("session_id")
	streamIDStr := r.URL.Query().Get("stream_id")

	if sessionID == "" || streamIDStr == "" {
		log.Printf("[DCAS] WebSocket connection missing parameters: session_id=%s, stream_id=%s", sessionID, streamIDStr)
		conn.Close()
		return
	}

	log.Printf("[DCAS] MF connected WebSocket: CallID=%s, SessionID=%s, StreamID=%s", callID, sessionID, streamIDStr)

	s.mu.Lock()
	s.connections[sessionID+"-"+streamIDStr] = conn
	if callID != "" {
		s.sessionCalls[sessionID] = callID
		// Add to callSessions grouping
		exists := false
		for _, sid := range s.callSessions[callID] {
			if sid == sessionID {
				exists = true
				break
			}
		}
		if !exists {
			s.callSessions[callID] = append(s.callSessions[callID], sessionID)
		}
		registerSessionInRedis(callID, sessionID)
		if streamIDStr == "1000" {
			go s.checkLateJoinCaro(callID, sessionID)
		}
	}
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.connections, sessionID+"-"+streamIDStr)
		s.cleanupSessionLocked(sessionID)
		s.mu.Unlock()
		conn.Close()
		log.Printf("[DCAS] WebSocket closed for SessionID=%s", sessionID)
	}()

	for {
		_, p, err := conn.ReadMessage()
		if err != nil {
			log.Printf("[DCAS] Read error for session %s: %v", sessionID, err)
			break
		}

		// Handle incoming message
		if streamIDStr == "1000" {
			s.handleSmartMenuMessage(sessionID, p, conn)
		} else if streamIDStr == "1001" {
			s.handleFunCallingMessage(sessionID, callID, p)
		}
	}
}

type SmartMenuResponse struct {
	Service  string `json:"service"`
	Action   string `json:"action"`
	Response string `json:"response"`
	Greeting string `json:"greeting,omitempty"`
	AudioURL string `json:"audioUrl,omitempty"`
	VideoURL string `json:"videoUrl,omitempty"`
	Time     string `json:"time,omitempty"`
}

func (s *DCASServer) handleEnterpriseCallerIDMessage(sessionID string, data []byte) {
	log.Printf("[DCAS] Enterprise Caller ID Event from Session %s: %s", sessionID, string(data))

	var req EnterpriseCallerIDRequest
	if err := json.Unmarshal(data, &req); err != nil {
		log.Printf("[DCAS] Failed to parse Enterprise Caller ID JSON: %v", err)
	}

	resp := EnterpriseCallerIDResponse{
		Service:            "enterprise-callerid",
		Action:             req.Action,
		EnterpriseName:     "VIETTEL TELECOM",
		VerificationStatus: "certified",
		LogoURL:            "assets/logo.png",
		CallIntent:         "Cuộc gọi chăm sóc khách hàng doanh nghiệp",
		PromoText:          "QUÉT MÃ TRÚNG THẺ 500K!",
		QrURL:              "assets/qr-coupon.png",
		RedirectURL:        "https://viettel.vn/khuyen-mai",
	}

	switch req.Action {
	case "claim":
		resp.Status = "claimed"
		resp.StatusMsg = "Đã xác nhận yêu cầu nhận khuyến mãi thành công!"
		log.Printf("[DCAS] User in Session %s claimed promotion voucher", sessionID)
	case "skip":
		resp.Status = "skipped"
		resp.StatusMsg = "Đã bỏ qua quảng cáo"
		log.Printf("[DCAS] User in Session %s skipped promotion", sessionID)
	default:
		resp.Status = "success"
		resp.StatusMsg = "Kênh 5G New Calling đã kết nối"
	}

	s.sendToSession(sessionID, "1000", resp)
}

func (s *DCASServer) handleSmartMenuMessage(sessionID string, data []byte, conn *websocket.Conn) {
	// 0. Check if this is a Caro game message or Enterprise Caller ID message
	var rawMsg map[string]interface{}
	if err := json.Unmarshal(data, &rawMsg); err == nil {
		service, _ := rawMsg["service"].(string)
		if service == "caro" {
			s.handleCaroMessage(sessionID, data, conn)
			return
		}
		if service == "enterprise-callerid" {
			s.handleEnterpriseCallerIDMessage(sessionID, data)
			return
		}
		if service == "flight-booking" {
			s.handleFlightBookingMessage(sessionID, data)
			return
		}

		// 1. Check if this is a draw or clear event from mini-app, and relay to the peer phone
		msgType, _ := rawMsg["type"].(string)
		if msgType == "draw" || msgType == "clear" {
			log.Printf("[DCAS] Relay Draw/Clear message from Session %s to peer", sessionID)

			s.mu.RLock()
			legacyPeer, isInterwork := s.interworkingSessions[sessionID]
			s.mu.RUnlock()
			if isInterwork {
				log.Printf("[DCAS Interworking Engine] Interworking active: translating drawing coordinates from Session %s to out-of-band legacy SMS delivery...", sessionID)
				link := fmt.Sprintf("http://%s/view-whiteboard?session=%s", s.dcsfAddr, sessionID)
				log.Printf("[DCAS Interworking Engine] SMS SENT to %s: \"Ban nhan duoc hinh ve tu peer. Xem hinh ve truc tiep tai day: %s\"", legacyPeer, link)
			}

			callID := s.sessionCalls[sessionID]
			sessions := getSessionsFromRedis(callID)
			var targetSessions []string
			for _, sid := range sessions {
				if sid != sessionID {
					targetSessions = append(targetSessions, sid)
				}
			}

			for _, targetSid := range targetSessions {
				s.sendToSession(targetSid, "1000", rawMsg)
			}
			return
		}
	}

	var action MenuAction
	if err := json.Unmarshal(data, &action); err != nil {
		log.Printf("[DCAS] Failed to parse Smart Menu JSON: %v", err)
		return
	}

	log.Printf("[DCAS] Smart Menu Event from Session %s: Action=%s", sessionID, action.Action)
	var respObj SmartMenuResponse
	respObj.Service = "smart-menu"
	respObj.Action = action.Action

	switch action.Action {
	case "video":
		sampleVideos := []string{
			"cartoon_video.mp4",
			"sample_video.mp4",
		}
		randomIdx := time.Now().UnixNano() % int64(len(sampleVideos))
		videoUrl := sampleVideos[randomIdx]
		respObj.Response = "Đang phát video hoạt hình vui nhộn từ hệ thống tổng đài..."
		respObj.VideoURL = videoUrl
		s.mu.RLock()
		callID := s.sessionCalls[sessionID]
		s.mu.RUnlock()
		if callID != "" {
			go s.triggerVideo(callID)
		}
	case "story":
		respObj.Response = "Ngày xửa ngày xưa, ở một tổng đài 5G New Calling nọ, các Data Channel truyền tin nhanh như chớp..."
	case "time":
		timeFormatted := fmt.Sprintf("Thời gian hệ thống: %s (GMT+7)", time.Now().Format("15:04:05 - 02/01/2006"))
		respObj.Response = timeFormatted
		respObj.Time = timeFormatted
	case "agent":
		greeting := "Xin chào quý khách! Tôi là tổng đài viên chăm sóc khách hàng 198. Tôi có thể hỗ trợ gì cho quý khách ạ?"
		respObj.Response = greeting
		respObj.Greeting = greeting
		respObj.AudioURL = "agent_greeting.mp3"
	case "balance":
		respObj.Response = "Số dư tài khoản trả trước của bạn là 150.000đ. Cảm ơn bạn đã lựa chọn dịch vụ của chúng tôi!"
	default:
		respObj.Response = fmt.Sprintf("Hệ thống đã nhận sự kiện: %s", action.Action)
	}

	respBytes, _ := json.Marshal(respObj)
	if conn != nil {
		if err := conn.WriteMessage(websocket.TextMessage, respBytes); err != nil {
			log.Printf("[DCAS] Write error: %v", err)
		}
	} else {
		s.sendToSession(sessionID, "1000", respBytes)
	}
}

func (s *DCASServer) triggerVideo(callID string) {
	log.Printf("[DCAS] Triggering video playback for CallID %s to DCSF on %s", callID, s.dcsfAddr)

	url := fmt.Sprintf("http://%s/play_video", s.dcsfAddr)
	payload := map[string]string{
		"call_id": callID,
		"action":  "funny_video",
	}
	bytesPayload, _ := json.Marshal(payload)

	req, err := http.NewRequest("POST", url, bytes.NewReader(bytesPayload))
	if err != nil {
		log.Printf("[DCAS] Failed to create HTTP request for video play: %v", err)
		return
	}
	req.Header.Set("Content-Type", "application/json")

	client := newH2CClient(5 * time.Second)
	resp, err := client.Do(req)
	if err != nil {
		log.Printf("[DCAS] Failed to invoke play_video to DCSF: %v", err)
		return
	}
	defer resp.Body.Close()
	log.Printf("[DCAS] play_video request sent to DCSF. Response: %s", resp.Status)
}

func (s *DCASServer) handleFunCallingMessage(sessionID, callID string, data []byte) {
	log.Printf("[DCAS] Fun Calling Event from Session %s (Call %s): %s", sessionID, callID, string(data))

	if callID == "" {
		s.mu.RLock()
		callID = s.sessionCalls[sessionID]
		s.mu.RUnlock()
	}

	// Translate action from send_emoji to render_emoji
	var payload map[string]interface{}
	if err := json.Unmarshal(data, &payload); err == nil {
		if service, ok := payload["service"].(string); ok && service == "fun-calling" {
			if action, ok := payload["action"].(string); ok && action == "send_emoji" {
				payload["action"] = "render_emoji"
				if modifiedData, err := json.Marshal(payload); err == nil {
					data = modifiedData
				}
			}
		}
	}

	sessions := getSessionsFromRedis(callID)
	var targetSessions []string
	for _, sid := range sessions {
		if sid != sessionID && !containsString(targetSessions, sid) {
			targetSessions = append(targetSessions, sid)
		}
	}

	// Fallback to in-memory callSessions
	s.mu.RLock()
	for _, sid := range s.callSessions[callID] {
		if sid != sessionID && !containsString(targetSessions, sid) {
			targetSessions = append(targetSessions, sid)
		}
	}
	for sid, cid := range s.sessionCalls {
		if cid == callID && sid != sessionID && !containsString(targetSessions, sid) {
			targetSessions = append(targetSessions, sid)
		}
	}
	s.mu.RUnlock()

	// Forward message (P2A2P) to all other sessions in the same call
	for _, targetSid := range targetSessions {
		log.Printf("[DCAS] Relaying Fun Calling Action to linked Session %s: %s", targetSid, string(data))
		s.sendToSession(targetSid, "1000", payload)
	}
}

// â”€â”€â”€ Caro Game Handler â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

// checkCaroWin checks 4 directions from (row, col) for CaroWinCount consecutive same symbols

func (s *DCASServer) sendToSession(sessionID, streamID string, payload interface{}) {
	s.sendToSessionInternal(sessionID, streamID, payload, true)
}

func (s *DCASServer) sendToSessionInternal(sessionID, streamID string, payload interface{}, publish bool) {
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}

	s.mu.RLock()
	key := sessionID + "-" + streamID
	tcpConn := s.tcpConns[key]

	if tcpConn == nil {
		log.Printf("[DCAS] CRITICAL: tcpConns[%s] is nil! Available keys for this session:", key)
		for k := range s.tcpConns {
			if strings.HasPrefix(k, sessionID) {
				log.Printf("   -> %s", k)
			}
		}

		// Fallbacks
		tcpConn = s.tcpConns[sessionID+"-1001"]
		if tcpConn == nil {
			tcpConn = s.tcpConns[sessionID+"-1000"]
		}
		if tcpConn == nil {
			for k, conn := range s.tcpConns {
				if strings.HasPrefix(k, sessionID+"-") {
					tcpConn = conn
					break
				}
			}
		}
	} else {
		log.Printf("[DCAS] SUCCESS: Found connection for key %s", key)
	}

	wsConn := s.connections[key]
	if wsConn == nil {
		wsConn = s.connections[sessionID+"-1001"]
	}
	if wsConn == nil {
		wsConn = s.connections[sessionID+"-1000"]
	}
	s.mu.RUnlock()

	if tcpConn != nil {
		dataLen := uint32(len(data))
		frame := make([]byte, 4+len(data))
		frame[0] = byte(dataLen >> 24)
		frame[1] = byte(dataLen >> 16)
		frame[2] = byte(dataLen >> 8)
		frame[3] = byte(dataLen)
		copy(frame[4:], data)
		if _, err := tcpConn.Write(frame); err != nil {
			log.Printf("[DCAS] TCP write failed for session %s: %v", sessionID, err)
		} else {
			log.Printf("[DCAS] Sent MDC2 TCP frame (%d bytes) to session %s (stream %s) payload: %s", len(data), sessionID, streamID, string(data))
		}
	} else if wsConn != nil {
		if err := wsConn.WriteMessage(websocket.TextMessage, data); err != nil {
			log.Printf("[DCAS] WS write failed for session %s: %v", sessionID, err)
		}
	} else {
		log.Printf("[DCAS] No TCP/WS connection for session %s stream %s", sessionID, streamID)
		if publish {
			msg := map[string]interface{}{
				"session_id": sessionID,
				"stream_id":  streamID,
				"payload":    payload,
			}
			msgBytes, _ := json.Marshal(msg)
			publishToRedis("dcas:broadcast", string(msgBytes))
		}
	}
}

func (s *DCASServer) cleanupSessionLocked(sessionID string) {
	hasActive := false
	for k := range s.tcpConns {
		if strings.HasPrefix(k, sessionID+"-") {
			hasActive = true
			break
		}
	}
	for k := range s.connections {
		if strings.HasPrefix(k, sessionID+"-") {
			hasActive = true
			break
		}
	}

	if !hasActive {
		callID := s.sessionCalls[sessionID]
		delete(s.sessionCalls, sessionID)
		if callID != "" {
			sids := s.callSessions[callID]
			for i, sid := range sids {
				if sid == sessionID {
					s.callSessions[callID] = append(sids[:i], sids[i+1:]...)
					break
				}
			}
			if len(s.callSessions[callID]) == 0 {
				delete(s.callSessions, callID)
				delete(s.games, callID)
			}
			removeSessionFromRedis(callID, sessionID)
		}
		log.Printf("[DCAS] Cleaned up session %s from call session lists", sessionID)
	}
}

func containsString(slice []string, val string) bool {
	for _, item := range slice {
		if item == val {
			return true
		}
	}
	return false
}


// ─── Redis Client (go-redis) ───

func getFromRedis(key string) (string, error) {
	val, err := redisClient.Get(ctx, key).Result()
	if err == redis.Nil {
		return "", nil // key not found
	}
	return val, err
}

func setToRedis(key string, value string) error {
	if value == "" {
		return redisClient.Del(ctx, key).Err()
	}
	return redisClient.Set(ctx, key, value, 0).Err()
}

func publishToRedis(channel, message string) error {
	return redisClient.Publish(ctx, channel, message).Err()
}

func registerSessionInRedis(callID, sessionID string) {
	key := "caro:sessions:" + callID
	redisClient.SAdd(ctx, key, sessionID)
}

func getSessionsFromRedis(callID string) []string {
	key := "caro:sessions:" + callID
	sessions, err := redisClient.SMembers(ctx, key).Result()
	if err != nil {
		return []string{}
	}
	return sessions
}

func removeSessionFromRedis(callID, sessionID string) {
	key := "caro:sessions:" + callID
	redisClient.SRem(ctx, key, sessionID)

	count, _ := redisClient.SCard(ctx, key).Result()
	if count == 0 {
		redisClient.Del(ctx, key)
		redisClient.Del(ctx, "caro:game:"+callID)
	}
}

func (s *DCASServer) StartRedisPubSubListener() {
	pubsub := redisClient.Subscribe(ctx, "dcas:broadcast")
	defer pubsub.Close()
	ch := pubsub.Channel()

	log.Printf("[DCAS-REDIS] Subscribed successfully to channel 'dcas:broadcast' via go-redis pool")

	for msg := range ch {
		var data struct {
			SessionID string      `json:"session_id"`
			StreamID  string      `json:"stream_id"`
			Payload   interface{} `json:"payload"`
		}
		if err := json.Unmarshal([]byte(msg.Payload), &data); err == nil {
			s.sendToSessionInternal(data.SessionID, data.StreamID, data.Payload, false)
		}
	}
}
