// Package imsas implements the 3GPP Rel-18/19 IMS Application Server (B2BUA).
// It acts as the central signaling orchestrator between the IMS Core (S-CSCF),
// the Data Channel Signaling Function (DCSF via DC1 interface - TS 29.175),
// and the Media Function (MF via DC2 interface - TS 29.176).
//
// Key Responsibilities:
// 1. RFC 3261 B2BUA SIP Session Management: Separates Originating Leg (UE A) and Terminating Leg (UE B) dialogs.
// 2. 3GPP TS 26.114 Multi-m-line SDP Orchestration: Audio (PCMA), Video (H.264), BDC and ADC negotiation.
// 3. SBA DC1 / DC2 Interworking: Dispatches session events to DCSF and media control patches to MF.
// 4. Early Media Announcements: Plays PCMA audio announcements for unreachable/unregistered subscribers (3GPP TS 24.186).
// 5. Clause 9.4.4 Fallback: Falls back to voice/video only if DC1/DCSF fails.
package imsas

import (
	"bytes"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/redisclient"
	"github.com/demo/ims-dc-mf/internal/sba"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
)

// CallSession represents an active end-to-end B2BUA call session in IMS-AS.
// It maintains dual-leg dialog mappings, media endpoints on Media Function, and SBA DC1/DC2 contexts.
type CallSession struct {
	// mu guards the App-slot fields (AppSlot, AppMfA, AppMfB, AppAnswerPending, SlottedAnswerPending)
	// and the Home snapshot taken from MfInfoA/MfInfoB. Never hold it across SBA calls.
	mu sync.Mutex

	CallID    string
	Caller    string
	Callee    string
	MfInfoA   *MFAllocResponse
	MfInfoB   *MFAllocResponse
	OrigMsg   *SIPMessage
	ScscfAddr *net.UDPAddr

	// B2BUA Call-ID separation (RFC 3261)
	OrigCallID string // Call-ID from originating leg (UE A)
	TermCallID string // New Call-ID generated for terminating leg (UE B)
	SessionID  string // RFC 7989 Session-ID for end-to-end tracking

	// SBA DC1 fields
	ContextID       string
	TopVia          string
	InstructionChan chan *sba.MediaInstructionData
	ResponseChan    chan *sba.MediaInstructionData
	MfHost          string

	// DC2 session IDs for cleanup on BYE/CANCEL
	SessionIDA string // session ID của Caller A
	SessionIDB string // session ID của Callee B

	// Anti-loop: đánh dấu đã forward terminating leg INVITE
	TermForwarded  bool
	TermAnswered   bool // true after first 200 OK on terminating leg is processed
	LastCSeq        int
	LastTermCSeq    int
	InitialCSeq     int
	LastCalleeCSeq  int // last CSeq of callee-initiated re-INVITEs (per-leg CSeq, independent from caller)
	InitialCalleeCSeq int // CSeq of the first callee-initiated re-INVITE
	IsADCReleasing  bool // true when operator-menu passthrough Re-INVITE is in-flight

	// 3GPP AC.7.2 ADC establishing fields
	IsP2P                  bool
	AdcType                string
	IsAnchorCtxCreated     bool
	IsAnchorPatched        bool
	IsInterworking         bool
	PsDataOff              string
	ActiveAppID            string
	AppSlot          slotState // App ADC slot; the Home slot is ActiveAppID + IsAnchorCtxCreated
	AppMfA, AppMfB   *SlotMF   // MF endpoints for the App slot, toward caller / callee
	AppAnswerPending bool      // a Re-INVITE that changed the App slot awaits its 200 OK
	// SlottedAnswerPending: a Re-INVITE handled by handleSlottedReInvite awaits its 200 OK.
	SlottedAnswerPending bool
	LastCallerReInviteMsg  *SIPMessage
	LastCalleeReInviteMsg  *SIPMessage

	// Unregistered / unavailable announcement fields
	IsPlayingAnnouncement bool
	AnnouncementCallID    string
}

type SerializableCallSession struct {
	CallID                string           `json:"callID"`
	Caller                string           `json:"caller"`
	Callee                string           `json:"callee"`
	MfInfoA               *MFAllocResponse `json:"mfInfoA"`
	MfInfoB               *MFAllocResponse `json:"mfInfoB"`
	ScscfAddrIP           string           `json:"scscfAddrIp"`
	ScscfAddrPort         int              `json:"scscfAddrPort"`
	OrigCallID            string           `json:"origCallID"`
	TermCallID            string           `json:"termCallID"`
	SessionID             string           `json:"sessionID"`
	ContextID             string           `json:"contextID"`
	TopVia                string           `json:"topVia"`
	MfHost                string           `json:"mfHost"`
	SessionIDA            string           `json:"sessionIDA"`
	SessionIDB            string           `json:"sessionIDB"`
	TermForwarded         bool             `json:"termForwarded"`
	TermAnswered          bool             `json:"termAnswered"`
	LastCSeq              int              `json:"lastCSeq"`
	LastTermCSeq          int              `json:"lastTermCSeq"`
	InitialCSeq           int              `json:"initialCSeq"`
	LastCalleeCSeq        int              `json:"lastCalleeCSeq"`
	InitialCalleeCSeq     int              `json:"initialCalleeCSeq"`
	IsADCReleasing        bool             `json:"isADCReleasing"`
	IsP2P                 bool             `json:"isP2P"`
	AdcType               string           `json:"adcType"`
	IsAnchorCtxCreated    bool             `json:"isAnchorCtxCreated"`
	IsAnchorPatched       bool             `json:"isAnchorPatched"`
	IsInterworking        bool             `json:"isInterworking"`
	PsDataOff             string           `json:"psDataOff"`
	ActiveAppID           string           `json:"activeAppId"`
	AppSlotAppID          string           `json:"appSlotAppId"`
	AppSlotCtx            bool             `json:"appSlotCtx"`
	AppSlotAdcType        string           `json:"appSlotAdcType,omitempty"`
	AppMfA                *SlotMF          `json:"appMfA"`
	AppMfB                *SlotMF          `json:"appMfB"`
	OrigMsgRaw            string           `json:"origMsgRaw"`
	IsPlayingAnnouncement bool             `json:"isPlayingAnnouncement"`
	AnnouncementCallID    string           `json:"announcementCallId"`
}

func (s *CallSession) ToSerializable() SerializableCallSession {
	scs := SerializableCallSession{
		CallID:                s.CallID,
		Caller:                s.Caller,
		Callee:                s.Callee,
		MfInfoA:               s.MfInfoA,
		MfInfoB:               s.MfInfoB,
		OrigCallID:            s.OrigCallID,
		TermCallID:            s.TermCallID,
		SessionID:             s.SessionID,
		ContextID:             s.ContextID,
		TopVia:                s.TopVia,
		MfHost:                s.MfHost,
		SessionIDA:            s.SessionIDA,
		SessionIDB:            s.SessionIDB,
		TermForwarded:         s.TermForwarded,
		TermAnswered:          s.TermAnswered,
		LastCSeq:              s.LastCSeq,
		LastTermCSeq:          s.LastTermCSeq,
		InitialCSeq:           s.InitialCSeq,
		LastCalleeCSeq:        s.LastCalleeCSeq,
		InitialCalleeCSeq:     s.InitialCalleeCSeq,
		IsADCReleasing:        s.IsADCReleasing,
		IsP2P:                 s.IsP2P,
		AdcType:               s.AdcType,
		IsAnchorCtxCreated:    s.IsAnchorCtxCreated,
		IsAnchorPatched:       s.IsAnchorPatched,
		IsInterworking:        s.IsInterworking,
		PsDataOff:             s.PsDataOff,
		ActiveAppID:           s.ActiveAppID,
		AppSlotAppID:          s.AppSlot.AppID,
		AppSlotCtx:            s.AppSlot.CtxCreated,
		AppSlotAdcType:        s.AppSlot.AdcType,
		AppMfA:                s.AppMfA,
		AppMfB:                s.AppMfB,
		IsPlayingAnnouncement: s.IsPlayingAnnouncement,
		AnnouncementCallID:    s.AnnouncementCallID,
	}
	if s.ScscfAddr != nil {
		scs.ScscfAddrIP = s.ScscfAddr.IP.String()
		scs.ScscfAddrPort = s.ScscfAddr.Port
	}
	if s.OrigMsg != nil {
		scs.OrigMsgRaw = s.OrigMsg.Raw
	}
	return scs
}

func (scs *SerializableCallSession) ToCallSession() *CallSession {
	s := &CallSession{
		CallID:                scs.CallID,
		Caller:                scs.Caller,
		Callee:                scs.Callee,
		MfInfoA:               scs.MfInfoA,
		MfInfoB:               scs.MfInfoB,
		OrigCallID:            scs.OrigCallID,
		TermCallID:            scs.TermCallID,
		SessionID:             scs.SessionID,
		ContextID:             scs.ContextID,
		TopVia:                scs.TopVia,
		MfHost:                scs.MfHost,
		SessionIDA:            scs.SessionIDA,
		SessionIDB:            scs.SessionIDB,
		TermForwarded:         scs.TermForwarded,
		TermAnswered:          scs.TermAnswered,
		LastCSeq:              scs.LastCSeq,
		LastTermCSeq:          scs.LastTermCSeq,
		InitialCSeq:           scs.InitialCSeq,
		LastCalleeCSeq:        scs.LastCalleeCSeq,
		InitialCalleeCSeq:     scs.InitialCalleeCSeq,
		IsADCReleasing:        scs.IsADCReleasing,
		IsP2P:                 scs.IsP2P,
		AdcType:               scs.AdcType,
		IsAnchorCtxCreated:    scs.IsAnchorCtxCreated,
		IsAnchorPatched:       scs.IsAnchorPatched,
		IsInterworking:        scs.IsInterworking,
		PsDataOff:             scs.PsDataOff,
		ActiveAppID:           scs.ActiveAppID,
		AppSlot:               slotState{AppID: scs.AppSlotAppID, CtxCreated: scs.AppSlotCtx, AdcType: scs.AppSlotAdcType},
		AppMfA:                scs.AppMfA,
		AppMfB:                scs.AppMfB,
		IsPlayingAnnouncement: scs.IsPlayingAnnouncement,
		AnnouncementCallID:    scs.AnnouncementCallID,
		InstructionChan:       make(chan *sba.MediaInstructionData),
		ResponseChan:          make(chan *sba.MediaInstructionData),
	}
	if scs.ScscfAddrIP != "" {
		s.ScscfAddr = &net.UDPAddr{
			IP:   net.ParseIP(scs.ScscfAddrIP),
			Port: scs.ScscfAddrPort,
		}
	}
	if scs.OrigMsgRaw != "" {
		if msg, err := ParseSIPMessage(scs.OrigMsgRaw); err == nil {
			s.OrigMsg = msg
		}
	}
	return s
}

type B2BUA struct {
	cfg            *config.Config
	conn           *net.UDPConn
	httpClient     *http.Client
	scscfAddr      *net.UDPAddr
	sessions       map[string]*CallSession
	mu             sync.RWMutex
	httpServer     *http.Server
	localIP        string
	localClusterIP string
}

func NewB2BUA(cfg *config.Config) *B2BUA {
	return &B2BUA{
		cfg: cfg,
		httpClient: &http.Client{
			Transport: &HEPRoundTripper{
				Proxied: &http2.Transport{
					AllowHTTP: true,
					DialTLS: func(network, addr string, cfg *tls.Config) (net.Conn, error) {
						return net.Dial(network, addr)
					},
				},
			},
		},
		sessions:       make(map[string]*CallSession),
		localIP:        getContainerLocalIP(),
		localClusterIP: getClusterIP(),
	}
}

func (b *B2BUA) Start() error {
	// Resolve S-CSCF Address
	scscfHost := b.cfg.ImsCore.Scscf.Host
	scscfPort := b.cfg.ImsCore.Scscf.Port
	addrStr := fmt.Sprintf("%s:%d", scscfHost, scscfPort)
	rAddr, err := net.ResolveUDPAddr("udp", addrStr)
	if err != nil {
		return fmt.Errorf("failed to resolve S-CSCF address %s: %w", addrStr, err)
	}
	b.scscfAddr = rAddr

	addr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("0.0.0.0:%d", b.cfg.Services.ImsAs.SipPort))
	if err != nil {
		return err
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return err
	}
	b.conn = conn

	log.Printf("[IMS-AS] B2BUA SIP listener started on UDP 0.0.0.0:%d, S-CSCF target: %s", b.cfg.Services.ImsAs.SipPort, addrStr)
	go b.listenLoop()

	// Expose HTTP/2 Control Plane REST Server on port 5070
	mux := http.NewServeMux()
	mux.HandleFunc("/nimsas-mc/v1/call-sessions/", b.HandleMediaInstruction)
	mux.HandleFunc("/announcement/done", b.HandleAnnouncementDone)

	httpAddr := fmt.Sprintf("0.0.0.0:%d", b.cfg.Services.ImsAs.Port)
	log.Printf("[IMS-AS] HTTP/2 SBA REST Server listening on http://%s", httpAddr)

	b.httpServer = &http.Server{
		Addr:    httpAddr,
		Handler: h2c.NewHandler(HEPMiddleware(mux), &http2.Server{}),
	}
	go func() {
		if err := b.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("[IMS-AS] HTTP/2 REST server failed: %v", err)
		}
	}()

	return nil
}

func writeProblemDetails(w http.ResponseWriter, status int, title, detail, cause string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(sba.ProblemDetails{
		Type:   "about:blank",
		Title:  title,
		Status: status,
		Detail: detail,
		Cause:  cause,
	})
}

func (b *B2BUA) HandleMediaInstruction(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeProblemDetails(w, http.StatusMethodNotAllowed, "Method Not Allowed", "Method not allowed", "")
		return
	}

	pathParts := strings.Split(r.URL.Path, "/")
	if len(pathParts) < 5 {
		writeProblemDetails(w, http.StatusBadRequest, "Malformed URI", "Bad request URI path", "")
		return
	}
	sessionId := pathParts[len(pathParts)-2]

	var req sba.MediaInstructionData
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeProblemDetails(w, http.StatusBadRequest, "Malformed Request", err.Error(), "")
		return
	}

	log.Printf("[IMS-AS] Received Nimsas_MediaControl Instruction from DCSF for session: %s", sessionId)

	session, exists := b.getSession(sessionId)

	if !exists {
		log.Printf("[IMS-AS] Call session %s not found for instruction", sessionId)
		writeProblemDetails(w, http.StatusNotFound, "Session Not Found", "Call session not found", "SESSION_NOT_FOUND")
		return
	}

	// Forward DCSF instructions to the SIP thread
	select {
	case session.InstructionChan <- &req:
	default:
	}

	// Block until the context is created on the MF
	select {
	case response := <-session.ResponseChan:
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(response)
	case <-time.After(4 * time.Second):
		log.Printf("[IMS-AS] Timeout processing instruction for session: %s", sessionId)
		writeProblemDetails(w, http.StatusInternalServerError, "Internal Server Error", "Internal timeout", "INTERNAL_TIMEOUT")
	}
}

func (b *B2BUA) getSession(callID string) (*CallSession, bool) {
	b.mu.RLock()
	session, exists := b.sessions[callID]
	b.mu.RUnlock()
	if exists {
		return session, true
	}
	// Try Redis
	data, err := redisclient.Get("imsas:session:" + callID)
	if err != nil || data == "" {
		return nil, false
	}
	var scs SerializableCallSession
	if err := json.Unmarshal([]byte(data), &scs); err != nil {
		return nil, false
	}
	session = scs.ToCallSession()
	b.mu.Lock()
	b.sessions[callID] = session
	b.mu.Unlock()
	log.Printf("[IMS-AS] Lazy-loaded session %s from Redis", callID)
	return session, true
}

func (b *B2BUA) saveSession(session *CallSession) {
	b.mu.Lock()
	b.sessions[session.OrigCallID] = session
	b.mu.Unlock()
	scs := session.ToSerializable()
	if data, err := json.Marshal(scs); err == nil {
		redisclient.Set("imsas:session:"+session.OrigCallID, string(data), 7200)
	}
}

func (b *B2BUA) deleteSession(callID string) {
	b.mu.Lock()
	delete(b.sessions, callID)
	b.mu.Unlock()
	redisclient.Del("imsas:session:" + callID)
}

func extractSIPCallID(data string) string {
	for _, line := range strings.Split(data, "\n") {
		line = strings.TrimSpace(line)
		lower := strings.ToLower(line)
		if strings.HasPrefix(lower, "call-id:") || strings.HasPrefix(lower, "i:") {
			parts := strings.SplitN(line, ":", 2)
			if len(parts) == 2 {
				return strings.TrimSpace(parts[1])
			}
		}
	}
	return ""
}

func (b *B2BUA) listenLoop() {
	buf := make([]byte, 65535)
	for {
		n, remoteAddr, err := b.conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("[IMS-AS] Error reading UDP: %v", err)
			return
		}
		data := string(buf[:n])

		// Mirror to Homer via HEPv3
		if localAddr, ok := b.conn.LocalAddr().(*net.UDPAddr); ok {
			SendHEP(data, remoteAddr.IP.String(), remoteAddr.Port, localAddr.IP.String(), localAddr.Port, extractSIPCallID(data))
		}

		go b.handleSIP(data, remoteAddr)
	}
}

func (b *B2BUA) handleSIP(data string, remoteAddr *net.UDPAddr) {
	msg, err := ParseSIPMessage(data)
	if err != nil {
		log.Printf("[IMS-AS] Failed to parse SIP message: %v", err)
		return
	}

	isForwarded := msg.GetHeader("X-Forwarded") == "true"
	isFromReplica := isForwarded || b.isReplicaIP(remoteAddr.IP.String())
	if isFromReplica {
		remoteAddr = b.scscfAddr
	}

	callID := msg.GetHeader("Call-ID")
	if callID == "" {
		log.Printf("[IMS-AS] Missing Call-ID header, ignoring message")
		return
	}

	// B2BUA: Remap TermCallID back to OrigCallID for correct hash routing
	if strings.HasPrefix(callID, "b2b_") {
		callID = strings.TrimPrefix(callID, "b2b_")
	}

	methodOrStatus := msg.Method
	if msg.IsResponse {
		methodOrStatus = fmt.Sprintf("Response %d %s", msg.StatusCode, msg.ReasonPhrase)
	}
	log.Printf("[IMS-AS] [DEBUG-SIP] Recv: %s | CallID: %s | isFromReplica: %v | remote: %s",
		methodOrStatus, callID, isFromReplica, remoteAddr.String())

	// Session affinity hash routing for multiple replicas
	targetPodIP := resolveAndHashHost(b.cfg.Services.ImsAs.Host, callID)
	if !isFromReplica && targetPodIP != b.cfg.Services.ImsAs.Host && !b.isLocalIP(targetPodIP) {
		log.Printf("[IMS-AS] [Dialog Routing] CallID %s hashed to pod %s. Forwarding UDP packet.", callID, targetPodIP)
		destAddr, err := net.ResolveUDPAddr("udp", fmt.Sprintf("%s:%d", targetPodIP, b.cfg.Services.ImsAs.SipPort))
		if err == nil {
			forwardedData := data
			if !isForwarded {
				// Inject X-Forwarded header
				parts := strings.SplitN(data, "\r\n\r\n", 2)
				if len(parts) == 2 {
					forwardedData = parts[0] + "\r\nX-Forwarded: true\r\n\r\n" + parts[1]
				}
			}
			_, _ = b.conn.WriteToUDP([]byte(forwardedData), destAddr)
			return
		}
		log.Printf("[IMS-AS] [Dialog Routing] Failed to resolve target pod address: %v", err)
	}

	if !msg.IsResponse {
		switch msg.Method {
		case "INVITE":
			b.handleInvite(msg, remoteAddr)
		case "BYE":
			b.handleBye(msg, remoteAddr)
		case "ACK":
			b.handleAck(msg, remoteAddr)
		case "CANCEL":
			b.handleCancel(msg, remoteAddr)
		default:
			log.Printf("[IMS-AS] Unhandled SIP method %s", msg.Method)
		}
	} else {
		if msg.StatusCode == 200 {
			cseqMethod := msg.GetHeader("CSeq")
			log.Printf("[IMS-AS] [DEBUG] 200 OK: CallID=%s, CSeq=%q, headers=%v", callID, cseqMethod, msg.Headers)
			if strings.Contains(cseqMethod, "INVITE") {
				b.handle200OKInvite(msg, remoteAddr)
			} else if strings.Contains(cseqMethod, "REGISTER") {
				log.Printf("[IMS-AS] Received 200 OK response for REGISTER for call %s, consuming locally", callID)
			} else {
				b.forwardToSCSCF(data)
			}
		} else {
			b.handleErrorResponse(msg, callID, data)
		}
	}
}

// handleErrorResponse relays a non-200 response. A final failure of the initial
// INVITE tears the call down; a failed re-INVITE keeps it.
func (b *B2BUA) handleErrorResponse(msg *SIPMessage, callID, data string) {
	if msg.StatusCode >= 300 {
		// A failed in-dialog re-INVITE (491 glare, 4xx/5xx) does not end the call:
		// only a pending App-slot change is rolled back [spec §5].
		if session, ok := b.getSession(callID); ok && isReInviteFailure(session, msg) {
			log.Printf("[IMS-AS] Re-INVITE for call %s failed with %d; keeping the call", callID, msg.StatusCode)
			b.rollbackAppSlotChange(session)
			b.forwardToSCSCF(data)
			return
		}

		log.Printf("[IMS-AS] Call %s ended with status %d, cleaning up sessions", callID, msg.StatusCode)

		// ★ Intercept 480/404 for Unregistered/Unavailable Subscriber Announcement
		if msg.StatusCode == 480 || msg.StatusCode == 404 {
			b.mu.Lock()
			session, ok := b.sessions[callID]
			b.mu.Unlock()
			if ok && session != nil && !session.IsPlayingAnnouncement && !session.TermAnswered && session.OrigMsg != nil && !config.IsHotline(session.Callee) {
				log.Printf("[IMS-AS] Intercepted %d for call %s: triggering unreachable announcement", msg.StatusCode, callID)
				session.IsPlayingAnnouncement = true
				session.AnnouncementCallID = callID
				b.saveSession(session)

				go b.handleUnreachableAnnouncement(session)
				return // DO NOT forward 480 now, DO NOT delete session now
			}
		}

		b.mu.Lock()
		session, ok := b.sessions[callID]
		delete(b.sessions, callID)
		b.mu.Unlock()

		if ok && session != nil && session.ContextID != "" {
			mfHost := session.MfHost
			if mfHost == "" {
				mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, callID)
			}
			mfURL := fmt.Sprintf("http://%s:%d", mfHost, b.cfg.Services.Mf.Port)
			b.closeAppSlot(session)
			log.Printf("[IMS-AS] Releasing MF Context %s", session.ContextID)
			_ = b.CallNmfDeleteMediaContext(mfURL, session.ContextID)
		}

		if ok && session != nil {
			dcsfHost := resolveAndHashHost(b.cfg.Services.Dcsf.Host, callID)
			dcsfURL := fmt.Sprintf("http://%s:%d", dcsfHost, b.cfg.Services.Dcsf.Port)
			log.Printf("[IMS-AS] Releasing DCSF Event Subscription for call %s", callID)
			_ = b.CallNimsasSessionEventRelease(dcsfURL, callID)
		}

		// Release dc2 sessions
		if ok && session != nil {
			mfHost := session.MfHost
			if mfHost == "" {
				mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, callID)
			}
			if session.SessionIDA != "" {
				go b.callDc2Release(mfHost, session.SessionIDA)
			}
			if session.SessionIDB != "" {
				go b.callDc2Release(mfHost, session.SessionIDB)
			}
		}
	}
	b.forwardToSCSCF(data)
}

func (b *B2BUA) HandleAnnouncementDone(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		CallID string `json:"call_id"`
		Status string `json:"status"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	log.Printf("[IMS-AS] Announcement completed callback for call %s", req.CallID)
	b.handleAnnouncementCompleted(req.CallID)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func (b *B2BUA) handleAnnouncementCompleted(callID string) {
	b.mu.Lock()
	session, ok := b.sessions[callID]
	if ok && session != nil {
		delete(b.sessions, callID)
	}
	b.mu.Unlock()

	if !ok || session == nil {
		log.Printf("[IMS-AS] handleAnnouncementCompleted: session %s not found (may be already cancelled)", callID)
		return
	}

	// 1. Tell MF to stop & cleanup announcement socket
	go b.callAnnouncementStop(session)

	// 2. Send 487 Request Terminated to end the call on UE side
	if session.OrigMsg != nil {
		log.Printf("[IMS-AS] Sending 487 Request Terminated to Caller A for call %s after announcement", callID)
		resp487 := b.BuildSIPResponse(session.OrigMsg, 487, "Request Terminated", nil, "")
		b.sendUDP(resp487, session.ScscfAddr)
	}
}

func (b *B2BUA) callAnnouncementStop(session *CallSession) {
	mfHost := session.MfHost
	if mfHost == "" {
		mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, session.CallID)
	}
	mfURL := fmt.Sprintf("http://%s:%d/announcement/stop", mfHost, b.cfg.Services.Mf.Port)
	stopReq := map[string]string{"call_id": session.CallID}
	reqData, _ := json.Marshal(stopReq)
	resp, err := b.httpClient.Post(mfURL, "application/json", bytes.NewReader(reqData))
	if err != nil {
		log.Printf("[IMS-AS] Failed to stop announcement on MF for call %s: %v", session.CallID, err)
		return
	}
	resp.Body.Close()
	log.Printf("[IMS-AS] Announcement stopped and resources released for call %s", session.CallID)
}

func (b *B2BUA) handleUnreachableAnnouncement(session *CallSession) {
	callID := session.CallID
	log.Printf("[IMS-AS] Starting unreachable announcement workflow for call %s", callID)

	// Clean up initial MF Context if any
	if session.ContextID != "" {
		mfHost := session.MfHost
		if mfHost == "" {
			mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, callID)
		}
		mfURL := fmt.Sprintf("http://%s:%d", mfHost, b.cfg.Services.Mf.Port)
		b.closeAppSlot(session)
		log.Printf("[IMS-AS] Releasing initial unused MF Context %s before announcement", session.ContextID)
		_ = b.CallNmfDeleteMediaContext(mfURL, session.ContextID)
	}

	// Release DCSF Event Subscription
	dcsfHost := resolveAndHashHost(b.cfg.Services.Dcsf.Host, callID)
	dcsfURL := fmt.Sprintf("http://%s:%d", dcsfHost, b.cfg.Services.Dcsf.Port)
	log.Printf("[IMS-AS] Releasing initial DCSF Event Subscription for call %s", callID)
	_ = b.CallNimsasSessionEventRelease(dcsfURL, callID)

	// Release dc2 sessions
	mfHost := session.MfHost
	if mfHost == "" {
		mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, callID)
	}
	if session.SessionIDA != "" {
		go b.callDc2Release(mfHost, session.SessionIDA)
	}
	if session.SessionIDB != "" {
		go b.callDc2Release(mfHost, session.SessionIDB)
	}

	// Determine callback URL
	imsasHost := b.localIP
	if imsasHost == "" {
		imsasHost = b.cfg.Services.ImsAs.Host
	}
	callbackURL := fmt.Sprintf("http://%s:%d/announcement/done", imsasHost, b.cfg.Services.ImsAs.Port)

	mfURL := fmt.Sprintf("http://%s:%d/announcement/play", mfHost, b.cfg.Services.Mf.Port)
	playReq := map[string]string{
		"call_id":      callID,
		"sdp_offer":    session.OrigMsg.Body,
		"file":         "unreachable",
		"callback_url": callbackURL,
	}
	reqData, _ := json.Marshal(playReq)

	resp, err := b.httpClient.Post(mfURL, "application/json", bytes.NewReader(reqData))
	if err != nil {
		log.Printf("[IMS-AS] Failed to trigger announcement on MF: %v. Falling back to 480.", err)
		b.mu.Lock()
		delete(b.sessions, callID)
		b.mu.Unlock()
		if session.OrigMsg != nil {
			errResp := b.BuildSIPResponse(session.OrigMsg, 480, "Temporarily Unavailable", nil, "")
			b.sendUDP(errResp, session.ScscfAddr)
		}
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		log.Printf("[IMS-AS] MF /announcement/play returned %d. Falling back to 480.", resp.StatusCode)
		b.mu.Lock()
		delete(b.sessions, callID)
		b.mu.Unlock()
		if session.OrigMsg != nil {
			errResp := b.BuildSIPResponse(session.OrigMsg, 480, "Temporarily Unavailable", nil, "")
			b.sendUDP(errResp, session.ScscfAddr)
		}
		return
	}

	var allocResp struct {
		SessionID string `json:"session_id"`
		LocalIP   string `json:"local_ip"`
		AudioPort int    `json:"audio_port"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&allocResp); err != nil || allocResp.AudioPort == 0 {
		log.Printf("[IMS-AS] Invalid allocation response from MF: %v. Falling back to 480.", err)
		b.mu.Lock()
		delete(b.sessions, callID)
		b.mu.Unlock()
		if session.OrigMsg != nil {
			errResp := b.BuildSIPResponse(session.OrigMsg, 480, "Temporarily Unavailable", nil, "")
			b.sendUDP(errResp, session.ScscfAddr)
		}
		return
	}

	log.Printf("[IMS-AS] Announcement allocated on MF for call %s: IP=%s, AudioPort=%d", callID, allocResp.LocalIP, allocResp.AudioPort)

	origSDP := ""
	if session.OrigMsg != nil {
		origSDP = session.OrigMsg.Body
	}
	sdpAnswer := BuildEarlyMediaSDPAnswer(origSDP, allocResp.LocalIP, allocResp.AudioPort)

	headers := map[string]string{
		"Content-Type":  "application/sdp",
		"P-Early-Media": "sendrecv",
		"Allow":         "INVITE, ACK, CANCEL, BYE, NOTIFY, REFER, MESSAGE, OPTIONS, INFO",
	}
	resp183 := b.BuildSIPResponse(session.OrigMsg, 183, "Session Progress", headers, sdpAnswer)
	log.Printf("[IMS-AS] Sending 183 Session Progress (Early Media) to Caller A for call %s", callID)
	b.sendUDP(resp183, session.ScscfAddr)
}

// BuildEarlyMediaSDPAnswer constructs an RFC 3264-compliant SDP answer for Early Media.
// It sets m=audio to the MF announcement port (PCMA 8kHz sendonly) and rejects/disables
// any additional media streams present in the caller's SDP offer (e.g. video, application) by setting their port to 0.
func BuildEarlyMediaSDPAnswer(origOffer string, localIP string, audioPort int) string {
	var sb strings.Builder
	sb.WriteString("v=0\r\n")
	sb.WriteString(fmt.Sprintf("o=- %d 1 IN IP4 %s\r\n", time.Now().Unix(), localIP))
	sb.WriteString("s=-\r\n")
	sb.WriteString(fmt.Sprintf("c=IN IP4 %s\r\n", localIP))
	sb.WriteString("t=0 0\r\n")

	hasVideo := false
	hasApp := false
	audioMid := ""
	videoMid := ""
	appMid := ""

	currentSection := ""
	lines := strings.Split(origOffer, "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=audio") {
			currentSection = "audio"
		} else if strings.HasPrefix(trimmed, "m=video") {
			hasVideo = true
			currentSection = "video"
		} else if strings.HasPrefix(trimmed, "m=application") {
			hasApp = true
			currentSection = "app"
		} else if strings.HasPrefix(trimmed, "a=mid:") {
			midVal := strings.TrimPrefix(trimmed, "a=mid:")
			if currentSection == "audio" {
				audioMid = midVal
			} else if currentSection == "video" {
				videoMid = midVal
			} else if currentSection == "app" {
				appMid = midVal
			}
		}
	}

	// 1. Audio stream for Early Media announcement
	sb.WriteString(fmt.Sprintf("m=audio %d RTP/AVP 8\r\n", audioPort))
	sb.WriteString("a=rtpmap:8 PCMA/8000\r\n")
	sb.WriteString("a=sendonly\r\n")
	if audioMid != "" {
		sb.WriteString(fmt.Sprintf("a=mid:%s\r\n", audioMid))
	}

	// 2. Video stream (disabled if present in offer)
	if hasVideo {
		sb.WriteString("m=video 0 RTP/AVP 97\r\n")
		if videoMid != "" {
			sb.WriteString(fmt.Sprintf("a=mid:%s\r\n", videoMid))
		}
	}

	// 3. Application / Data channel stream (disabled if present in offer)
	if hasApp {
		sb.WriteString("m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\n")
		if appMid != "" {
			sb.WriteString(fmt.Sprintf("a=mid:%s\r\n", appMid))
		}
	}

	return sb.String()
}

func (b *B2BUA) forwardToSCSCF(data string) {
	addrStr := fmt.Sprintf("%s:%d", b.cfg.ImsCore.Scscf.Host, b.cfg.ImsCore.Scscf.Port)
	rAddr, err := net.ResolveUDPAddr("udp", addrStr)
	if err == nil {
		b.sendUDP(data, rAddr)
	} else {
		log.Printf("[IMS-AS] Failed to resolve S-CSCF address %s: %v", addrStr, err)
		b.sendUDP(data, b.scscfAddr) // fallback to cached
	}
}

// callDc2Release gửi POST /dc2/release đến MF để đóng SCTP ports và giải phóng UDP sockets.
// Tránh rò rỉ tài nguyên sau khi cuộc gọi kết thúc (BYE/CANCEL).
func (b *B2BUA) callDc2Release(mfHost, sessionID string) {
	if mfHost == "" || sessionID == "" {
		return
	}
	url := fmt.Sprintf("http://%s:%d/dc2/release", mfHost, b.cfg.Services.Mf.Port)
	body := fmt.Sprintf(`{"session_id":"%s"}`, sessionID)
	req, err := http.NewRequest(http.MethodPost, url, strings.NewReader(body))
	if err != nil {
		log.Printf("[IMS-AS] dc2/release: failed to build request for session %s: %v", sessionID, err)
		return
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := b.httpClient.Do(req)
	if err != nil {
		log.Printf("[IMS-AS] dc2/release: HTTP error for session %s: %v", sessionID, err)
		return
	}
	defer resp.Body.Close()
	log.Printf("[IMS-AS] dc2/release: session %s released (status=%s)", sessionID, resp.Status)
}

func (b *B2BUA) sendUDP(data string, dest *net.UDPAddr) {
	_, err := b.conn.WriteToUDP([]byte(data), dest)
	if err != nil {
		log.Printf("[IMS-AS] Error sending UDP to %v: %v", dest, err)
	} else {
		// Mirror to Homer via HEPv3
		if localAddr, ok := b.conn.LocalAddr().(*net.UDPAddr); ok {
			SendHEP(data, localAddr.IP.String(), localAddr.Port, dest.IP.String(), dest.Port, extractSIPCallID(data))
		}
	}
}

func (b *B2BUA) sendError(orig *SIPMessage, code int, reason string, dest *net.UDPAddr) {
	resp := b.BuildSIPResponse(orig, code, reason, nil, "")
	b.sendUDP(resp, dest)
}

var (
	dnsCache     = make(map[string][]string)
	dnsCacheTime = make(map[string]time.Time)
	dnsCacheMu   sync.RWMutex
	dnsCacheTTL  = 5 * time.Second
)

// resolveAndHashHost resolves host via DNS, sorts the IPs, and returns a single IP based on hashing the callID.
// If DNS lookup fails or returns no IPs, it falls back to the original host.

// parseReqApp extracts a=3gpp-req-app from SDP body per TS 24.186 clause 6.2.5
func parseReqApp(sdpBody string) *sba.AppBindingInfo {
	for _, line := range strings.Split(sdpBody, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "a=3gpp-req-app:") {
			val := strings.TrimPrefix(trimmed, "a=3gpp-req-app:")
			// Handle both ; and : separators for interoperability
			var parts []string
			if strings.Contains(val, ";") {
				parts = strings.SplitN(val, ";", 2)
			} else {
				parts = strings.SplitN(val, ":", 2)
			}
			appId := strings.Trim(parts[0], `"`)
			dcInfo := ""
			if len(parts) > 1 {
				dcInfo = strings.TrimSpace(parts[1])
			}

			// Strict compliance: a=3gpp-req-app line MUST NOT be included in BDCs (Stream ID < 1000)
			if dcInfo != "" {
				subparts := strings.Split(dcInfo, "-")
				if len(subparts) > 0 {
					streamId, err := strconv.Atoi(subparts[0])
					if err == nil && streamId < 1000 {
						log.Printf("[IMS-AS] Compliance violation: a=3gpp-req-app contains BDC stream ID %d. Discarding.", streamId)
						continue
					}
				}
			}

			return &sba.AppBindingInfo{ReqAppId: appId, AppDcInfo: dcInfo}
		}
	}
	return nil
}

func isReleaseSdp(sdp string) bool {
	lines := strings.Split(sdp, "\n")
	appLineCount := 0
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "m=application") {
			appLineCount++
			if appLineCount == 2 {
				parts := strings.Fields(line)
				if len(parts) >= 2 && parts[1] == "0" {
					return true
				}
			}
		}
	}
	return appLineCount < 2
}

var (
	globalReplicaIPs  []string
	globalReplicaMu   sync.RWMutex
	globalReplicaOnce sync.Once
)
