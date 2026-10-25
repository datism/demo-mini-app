package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/imsas"
	"github.com/demo/ims-dc-mf/internal/logger"
)

// --- 5QI Mapping Table (TS 23.501 Table 5.7.4-1) ---
// Maps QoS hints to standardized 5QI values
var qosTo5QI = map[string]int{
	"conversational": 1,  // GBR, 100ms, 10^-2
	"streaming":      4,  // GBR, 300ms, 10^-6
	"interactive":    5,  // Non-GBR, 100ms, 10^-6
	"background":     9,  // Non-GBR, 300ms, 10^-6
	"dc-low-latency": 65, // Non-GBR, 75ms, 10^-2 (Mission Critical)
	"dc-default":     69, // Non-GBR, 60ms, 10^-6 (IMS Data Channel default)
}

// PolicySession represents an active Npcf_PolicyAuthorization session
type PolicySession struct {
	SessionID    string    `json:"sessionId"`
	SUPI         string    `json:"supi,omitempty"`
	CallerID     string    `json:"callerId"`
	CalledID     string    `json:"calledId"`
	MediaType    string    `json:"mediaType"` // "DC", "AUDIO", "VIDEO"
	QosHint      string    `json:"qosHint"`
	Mapped5QI    int       `json:"mapped5qi"`
	FlowStatus   string    `json:"flowStatus"` // "ENABLED", "DISABLED", "REMOVED"
	CreatedAt    time.Time `json:"createdAt"`
	LastModified time.Time `json:"lastModified"`
}

// PCFServer implements the Npcf_PolicyAuthorization service
type PCFServer struct {
	addr       string
	cfg        *config.Config
	sessions   map[string]*PolicySession
	sessionsMu sync.RWMutex
}

func main() {
	logger.InitLogger("pcf")
	log.Println("========================================")
	log.Println("  PCF Node (Policy Control Function)")
	log.Println("  3GPP TS 29.514 / TS 23.501 PoC Demo")
	log.Println("  N5 Interface: Npcf_PolicyAuthorization")
	log.Println("========================================")

	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatalf("[PCF] Failed to load configuration: %v", err)
	}

	port := cfg.Services.Pcf.Port
	if port == 0 {
		port = 8085
	}
	addr := fmt.Sprintf(":%d", port)

	pcf := &PCFServer{
		addr:     addr,
		cfg:      cfg,
		sessions: make(map[string]*PolicySession),
	}

	mux := http.NewServeMux()

	// Npcf_PolicyAuthorization API (TS 29.514)
	// POST /npcf-policyauthorization/v1/app-sessions — Create
	// GET/PATCH/DELETE /npcf-policyauthorization/v1/app-sessions/{appSessionId} — Read/Modify/Delete
	mux.HandleFunc("/npcf-policyauthorization/v1/app-sessions", pcf.handleAppSessions)
	mux.HandleFunc("/npcf-policyauthorization/v1/app-sessions/", pcf.handleAppSession)

	// 5QI mapping query (non-standard convenience API)
	mux.HandleFunc("/pcf/v1/qos-mapping", pcf.handleQosMapping)

	// Health check
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status": "healthy",
			"node":   "PCF",
			"activeSessions": len(pcf.sessions),
		})
	})

	log.Printf("[PCF] Starting Npcf_PolicyAuthorization service on %s", addr)
	if err := http.ListenAndServe(addr, imsas.HEPMiddleware(mux)); err != nil {
		log.Fatalf("[PCF] Server failed: %v", err)
	}
}

// handleAppSessions handles POST /npcf-policyauthorization/v1/app-sessions
// This is the N5 interface for P-CSCF to request QoS policy for IMS sessions
func (p *PCFServer) handleAppSessions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		CallerID    string `json:"callerId"`
		CalledID    string `json:"calledId"`
		MediaType   string `json:"mediaType"`
		QosHint     string `json:"qosHint"`
		MediaFlowIP string `json:"mediaFlowIp,omitempty"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	log.Printf("[PCF] N5 AppSession Create: caller=%s, called=%s, mediaType=%s, qosHint=%s",
		req.CallerID, req.CalledID, req.MediaType, req.QosHint)

	// Map QoS hint to 5QI value
	mapped5QI := mapQosTo5QI(req.QosHint)
	log.Printf("[PCF] 5QI Mapping: qosHint=%s -> 5QI=%d", req.QosHint, mapped5QI)

	sessionID := fmt.Sprintf("pcf-sess-%d", time.Now().UnixNano())
	session := &PolicySession{
		SessionID:    sessionID,
		CallerID:     req.CallerID,
		CalledID:     req.CalledID,
		MediaType:    req.MediaType,
		QosHint:      req.QosHint,
		Mapped5QI:    mapped5QI,
		FlowStatus:   "ENABLED",
		CreatedAt:    time.Now(),
		LastModified: time.Now(),
	}

	p.sessionsMu.Lock()
	p.sessions[sessionID] = session
	p.sessionsMu.Unlock()

	log.Printf("[PCF] N5 Policy session created: id=%s, 5QI=%d, flowStatus=ENABLED", sessionID, mapped5QI)

	// Return 201 Created with policy decision
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Location", fmt.Sprintf("/npcf-policyauthorization/v1/app-sessions/%s", sessionID))
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]interface{}{
		"appSessionId": sessionID,
		"authProfId":   "ims-dc-profile",
		"servAuthInfo": map[string]interface{}{
			"servAuthDecision": "PERMITTED",
		},
		"ascReqData": map[string]interface{}{
			"callerId":   req.CallerID,
			"calledId":   req.CalledID,
			"mediaType":  req.MediaType,
			"flowStatus": "ENABLED",
		},
		"qosData": map[string]interface{}{
			"qosId":          fmt.Sprintf("qos-%s", sessionID),
			"fiveQi":         mapped5QI,
			"priorityLevel":  getPriorityLevel(mapped5QI),
			"maxBrUl":        "10 Mbps",
			"maxBrDl":        "10 Mbps",
			"gbrUl":          getGBR(mapped5QI, "ul"),
			"gbrDl":          getGBR(mapped5QI, "dl"),
		},
	})
}

// handleAppSession handles GET/PATCH/DELETE on individual policy sessions
func (p *PCFServer) handleAppSession(w http.ResponseWriter, r *http.Request) {
	sessionID := strings.TrimPrefix(r.URL.Path, "/npcf-policyauthorization/v1/app-sessions/")
	sessionID = strings.TrimSuffix(sessionID, "/")

	switch r.Method {
	case http.MethodGet:
		p.sessionsMu.RLock()
		session, exists := p.sessions[sessionID]
		p.sessionsMu.RUnlock()

		if !exists {
			http.Error(w, "Session not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(session)

	case http.MethodPatch:
		p.sessionsMu.Lock()
		session, exists := p.sessions[sessionID]
		if !exists {
			p.sessionsMu.Unlock()
			http.Error(w, "Session not found", http.StatusNotFound)
			return
		}

		var patch struct {
			FlowStatus string `json:"flowStatus,omitempty"`
			QosHint    string `json:"qosHint,omitempty"`
		}
		if err := json.NewDecoder(r.Body).Decode(&patch); err != nil {
			p.sessionsMu.Unlock()
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		if patch.FlowStatus != "" {
			session.FlowStatus = patch.FlowStatus
		}
		if patch.QosHint != "" {
			session.QosHint = patch.QosHint
			session.Mapped5QI = mapQosTo5QI(patch.QosHint)
		}
		session.LastModified = time.Now()
		p.sessionsMu.Unlock()

		log.Printf("[PCF] N5 Policy session updated: id=%s, flowStatus=%s, 5QI=%d",
			sessionID, session.FlowStatus, session.Mapped5QI)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(session)

	case http.MethodDelete:
		p.sessionsMu.Lock()
		_, exists := p.sessions[sessionID]
		if exists {
			delete(p.sessions, sessionID)
		}
		p.sessionsMu.Unlock()

		if !exists {
			http.Error(w, "Session not found", http.StatusNotFound)
			return
		}
		log.Printf("[PCF] N5 Policy session deleted: id=%s", sessionID)
		w.WriteHeader(http.StatusNoContent)

	default:
		http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
	}
}

// handleQosMapping is a convenience endpoint to query 5QI mapping
func (p *PCFServer) handleQosMapping(w http.ResponseWriter, r *http.Request) {
	qosHint := r.URL.Query().Get("qos_hint")
	if qosHint == "" {
		// Return full mapping table
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(qosTo5QI)
		return
	}

	mapped5QI := mapQosTo5QI(qosHint)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"qosHint":  qosHint,
		"fiveQi":   mapped5QI,
		"priority": getPriorityLevel(mapped5QI),
	})
}

// mapQosTo5QI maps a QoS hint string to a 5QI value per TS 23.501 Table 5.7.4-1
func mapQosTo5QI(qosHint string) int {
	// Direct lookup
	if qi, ok := qosTo5QI[strings.ToLower(qosHint)]; ok {
		return qi
	}

	// Parse latency from hint string (e.g., "latency=200;loss=0.05")
	latencyMs := 0
	for _, part := range strings.Split(qosHint, ";") {
		kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
		if len(kv) == 2 && strings.TrimSpace(kv[0]) == "latency" {
			fmt.Sscanf(kv[1], "%d", &latencyMs)
		}
	}

	// Map latency to 5QI based on TS 23.501 Table 5.7.4-1
	switch {
	case latencyMs > 0 && latencyMs <= 50:
		return 65 // Non-GBR, 75ms budget, Mission Critical Data
	case latencyMs > 50 && latencyMs <= 100:
		return 69 // Non-GBR, 60ms, IMS DC optimized
	case latencyMs > 100 && latencyMs <= 200:
		return 5  // Non-GBR, 100ms, Interactive
	case latencyMs > 200:
		return 9  // Non-GBR, 300ms, Background
	default:
		return 69 // Default: IMS Data Channel
	}
}

// getPriorityLevel returns the priority level for a given 5QI
func getPriorityLevel(qi int) int {
	switch qi {
	case 1:
		return 20
	case 4:
		return 50
	case 5:
		return 10
	case 9:
		return 90
	case 65:
		return 7
	case 69:
		return 15
	default:
		return 50
	}
}

// getGBR returns guaranteed bit rate for GBR QoS flows
func getGBR(qi int, direction string) string {
	switch qi {
	case 1: // Conversational Voice
		return "64 kbps"
	case 4: // Streaming
		return "256 kbps"
	default:
		return "0 kbps" // Non-GBR
	}
}
