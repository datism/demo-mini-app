// Package dcsf implements the 3GPP Rel-18/19 Data Channel Signaling Function (DCSF).
// It exposes the TS 29.175 DC1 SBA interface to the IMS Application Server (IMS-AS),
// manages Session Event Subscriptions, routes Data Channel applications to DCAS/DCAR,
// and coordinates ImsEE event notifications (TS 29.522).
//
// Key Responsibilities:
// 1. DC1 REST SBA Server: HTTP/2 Cleartext endpoints (/session-event-subscriptions).
// 2. Application Routing Engine: Matches incoming a=3gpp-req-app attributes to appropriate DCAS backend.
// 3. ProblemDetails Error Generation: Emits standardized RFC 7807 problem details in 3GPP format.
package dcsf

import (
	"archive/zip"
	"bytes"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"hash/fnv"
	"io"
	"log"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/imsas"
	"github.com/demo/ims-dc-mf/internal/redisclient"
	"github.com/demo/ims-dc-mf/internal/sba"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
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

type CallSessionState struct {
	SessionID      string `json:"sessionId"`
	Caller         string `json:"caller"`
	Callee         string `json:"callee"`
	SelectedApp    string `json:"selectedApp"`
	IsInterworking bool   `json:"isInterworking"`
}

type ImsEESubscription struct {
	EventId         string   `json:"eventId"`
	AppIds          []string `json:"appIds"`
	NotificationUri string   `json:"notificationUri"`
}

type DCSFServer struct {
	addr            string
	cfg             *config.Config
	sessions        map[string]*CallSessionState
	sessionsMu      sync.RWMutex
	httpClient      *http.Client
	subscriptions   []ImsEESubscription
	subscriptionsMu sync.RWMutex
}

func NewDCSFServer(addr string, cfg *config.Config) *DCSFServer {
	return &DCSFServer{
		addr:       addr,
		cfg:        cfg,
		sessions:   make(map[string]*CallSessionState),
		httpClient: newH2CClient(5 * time.Second),
	}
}

type NotifyRequest struct {
	CallID string `json:"call_id"`
	From   string `json:"from"`
	To     string `json:"to"`
}

type PlayVideoRequest struct {
	CallID string `json:"call_id"`
	Action string `json:"action"`
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

func (s *DCSFServer) HandleSessionEventSubscription(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodDelete {
		subscriptionId := strings.TrimPrefix(r.URL.Path, "/nimsas-sec/v1/session-event-subscriptions/")
		log.Printf("[DCSF] Received Session Event Unsubscribe (DELETE) from IMS-AS: SubscriptionID=%s", subscriptionId)
		
		s.sessionsMu.Lock()
		delete(s.sessions, subscriptionId)
		s.sessionsMu.Unlock()
		redisclient.Del("dcsf:session:" + subscriptionId)

		w.WriteHeader(http.StatusNoContent)
		return
	}

	if r.Method != http.MethodPost {
		writeProblemDetails(w, http.StatusMethodNotAllowed, "Method Not Allowed", "Method not allowed", "")
		return
	}

	var req sba.SessionEventNotification
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeProblemDetails(w, http.StatusBadRequest, "Malformed Request", err.Error(), "")
		return
	}

	log.Printf("[DCSF] Received Session Event Notification from IMS-AS: SessionID=%s, EventType=%s, Called=%s",
		req.SessionID, req.NotificationEvent.EventType, req.SessionInfo.CalledIdentity)

	// DC1 Event Type Dispatch (TS 29.175 clause 5.4.2)
	switch req.NotificationEvent.EventType {
	case "MediaChangeRequestEvent", "MEDIA_CHANGE_REQUEST":
		s.handleMediaChangeRequest(w, r, req)
		return
	case "MediaChangeSuccessEvent", "MediaChangeFailureEvent", "MEDIA_CHANGE_SUCCESS", "MEDIA_CHANGE_FAILURE":
		log.Printf("[DCSF] Received media change status event: %s for session %s, no action needed", req.NotificationEvent.EventType, req.SessionID)
		w.WriteHeader(http.StatusNoContent)
		return
	case "SessionTerminatedEvent":
		s.sessionsMu.Lock()
		delete(s.sessions, req.SessionID)
		s.sessionsMu.Unlock()
		redisclient.Del("dcsf:session:" + req.SessionID)
		log.Printf("[DCSF] Session %s terminated, cleaned up state", req.SessionID)
		w.WriteHeader(http.StatusNoContent)
		return
	}
	// Default: fall through to initial SessionSetupEvent handling

	callerUser := extractUsername(req.SessionInfo.CallingIdentity)
	calleeUser := extractUsername(req.SessionInfo.CalledIdentity)

	dcsfHost := "dcsf"
	if s.cfg != nil && s.cfg.Services.Dcsf.Host != "" {
		dcsfHost = s.cfg.Services.Dcsf.Host
	}

	// Save or update session state
	s.sessionsMu.Lock()
	var state *CallSessionState
	if sess, exists := s.sessions[req.SessionID]; exists {
		state = sess
		if req.IsInterworking {
			state.IsInterworking = true
			log.Printf("[DCSF] Updated session %s state: IsInterworking=true (DCMTSI-to-MTSI active)", req.SessionID)
		}
	} else {
		state = &CallSessionState{
			SessionID:      req.SessionID,
			Caller:         callerUser,
			Callee:         calleeUser,
			IsInterworking: req.IsInterworking,
		}
		s.sessions[req.SessionID] = state
		if req.IsInterworking {
			log.Printf("[DCSF] Saved new session %s state: IsInterworking=true", req.SessionID)
		}
	}
	s.sessionsMu.Unlock()
	
	// Write-Through Cache: Save to Redis
	if data, err := json.Marshal(state); err == nil {
		redisclient.Set("dcsf:session:"+req.SessionID, string(data), 7200)
	}

	vrbtEnabled := false
	if s.cfg != nil {
		for _, u := range s.cfg.Users {
			if u.Username == calleeUser {
				vrbtEnabled = u.VRBTEnabled
				break
			}
		}
	}
	if vrbtEnabled {
		log.Printf("[DCSF] Called user %s has VRBT Service enabled. Preparing VRBT media instructions...", calleeUser)
	}

	// Formulate standard 3GPP MediaInstructionData
	instruction := sba.MediaInstructionData{
		SessionID: req.SessionID,
	}

	// Query DCAR DC5 for connection policy and QoS (DEF-05, DEF-10)
	dcarHost := s.cfg.Services.Dcar.Host
	dcarPort := s.cfg.Services.Dcar.Port
	qosHint := "latency=200;loss=0.05" // default fallback
	connectionPolicy := "P2P"           // default fallback

	isHotline := strings.Contains(req.SessionInfo.CalledIdentity, "198")
	if isHotline {
		connectionPolicy = "P2A"
	}

	// Try to load profile from DCAR for non-hotline calls
	appProfile := s.queryDcarAppProfile(dcarHost, dcarPort, "operator-menu")
	if appProfile != nil {
		if cp, ok := appProfile["connectionPolicy"].(string); ok && cp != "" {
			connectionPolicy = cp
		}
		if qh, ok := appProfile["qosHint"].(string); ok && qh != "" {
			qosHint = qh
		}
	}
	log.Printf("[DCSF] Session policy: connectionPolicy=%s, qosHint=%s", connectionPolicy, qosHint)

	// Resolve DCAS endpoint for ADC UDP_PROXY Mdc2EndpointInfo
	dcasHost := s.cfg.Services.Dcas.Host
	dcasPort := s.cfg.Services.Dcas.Port

	instruction.MediaInstructionSet = []sba.MediaInstructions{}

	if isHotline {
		instruction.MediaInstructionSet = append(instruction.MediaInstructionSet, sba.MediaInstructions{
			MediaID:           "3",
			MediaResourceType: "DC",
			MediaInstruction:  "OriginateMedia",
			DcMediaSpecification: &sba.DcMediaSpecification{
				MediaProxyConfig: "HTTP_PROXY",
				QosHint:          qosHint,
				Streams: map[string]sba.Stream{
					"10": {
						StreamID:    10,
						Subprotocol: "http",
					},
				},
				ReplaceHttpUrls: map[string]sba.ReplaceUrl{
					"10": {
						StreamID:           10,
						ReplacementHttpUrl: fmt.Sprintf("http://%s:8080/dcar/v1/applications/198-app?user=%s&session_id=%s&stream_id=10", dcsfHost, callerUser, req.SessionID),
					},
				},
			},
		})
		// ADC instruction for Hotline (DEF-07: UDP_PROXY for stream ID >= 1000)
		instruction.MediaInstructionSet = append(instruction.MediaInstructionSet, sba.MediaInstructions{
			MediaID:           "adc-1000",
			MediaResourceType: "DC",
			MediaInstruction:  "OriginateMedia",
			DcMediaSpecification: &sba.DcMediaSpecification{
				MediaProxyConfig: "UDP_PROXY",
				QosHint:          qosHint,
				Mdc2EndpointInfo: &sba.Mdc2EndpointInfo{
					Mdc2Protocol: "TCP",
					Mdc2EndpointDcAs: &sba.MdcEndpoint{
						IP:   dcasHost,
						Port: dcasPort,
					},
				},
			},
		})
	} else {
		callerStream10Url := fmt.Sprintf("http://%s:8080/dcar/v1/menu?user=%s&session_id=%s&role=caller", dcsfHost, callerUser, req.SessionID)
		if vrbtEnabled {
			callerStream10Url = fmt.Sprintf("http://%s:8080/dcar/v1/applications/enterprise-callerid?user=%s&session_id=%s&stream_id=10", dcsfHost, callerUser, req.SessionID)
		}

		// P2P: Map personalized URLs for streams 0, 10, 100, 110
		instruction.MediaInstructionSet = append(instruction.MediaInstructionSet, sba.MediaInstructions{
			MediaID:           "3",
			MediaResourceType: "DC",
			MediaInstruction:  "OriginateMedia",
			DcMediaSpecification: &sba.DcMediaSpecification{
				MediaProxyConfig: "HTTP_PROXY",
				QosHint:          qosHint,
				Streams: map[string]sba.Stream{
					"0": {
						StreamID:    0,
						Subprotocol: "http",
					},
					"10": {
						StreamID:    10,
						Subprotocol: "http",
					},
					"100": {
						StreamID:    100,
						Subprotocol: "http",
					},
					"110": {
						StreamID:    110,
						Subprotocol: "http",
					},
				},
				ReplaceHttpUrls: map[string]sba.ReplaceUrl{
					"0": {
						StreamID:           0,
						ReplacementHttpUrl: fmt.Sprintf("http://%s:8080/dcar/v1/menu?user=%s&session_id=%s&role=caller", dcsfHost, callerUser, req.SessionID),
					},
					"10": {
						StreamID:           10,
						ReplacementHttpUrl: callerStream10Url,
					},
					"100": {
						StreamID:           100,
						ReplacementHttpUrl: fmt.Sprintf("http://%s:8080/dcar/v1/menu?user=%s&session_id=%s&role=callee", dcsfHost, calleeUser, req.SessionID),
					},
					"110": {
						StreamID:           110,
						ReplacementHttpUrl: fmt.Sprintf("http://%s:8080/dcar/v1/menu?user=%s&session_id=%s&role=callee", dcsfHost, calleeUser, req.SessionID),
					},
				},
			},
		})
		// ADC instruction for P2P (DEF-07: UDP_PROXY for stream ID >= 1000)
		instruction.MediaInstructionSet = append(instruction.MediaInstructionSet, sba.MediaInstructions{
			MediaID:           "adc-1000",
			MediaResourceType: "DC",
			MediaInstruction:  "OriginateMedia",
			DcMediaSpecification: &sba.DcMediaSpecification{
				MediaProxyConfig: "UDP_PROXY",
				QosHint:          qosHint,
				Mdc2EndpointInfo: &sba.Mdc2EndpointInfo{
					Mdc2Protocol: "TCP",
					Mdc2EndpointDcAs: &sba.MdcEndpoint{
						IP:   dcasHost,
						Port: dcasPort,
					},
				},
			},
		})
	}

	remoteIP, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		remoteIP = s.cfg.Services.ImsAs.Host
	}
	imsAsURL := fmt.Sprintf("http://%s:%d", remoteIP, s.cfg.Services.ImsAs.Port)
	targetURL := fmt.Sprintf("%s/nimsas-mc/v1/call-sessions/%s/media-instruction", imsAsURL, req.SessionID)

	log.Printf("[DCSF] Invoking Nimsas_MediaControl instructions to IMS-AS on: %s", targetURL)
	bytesPayload, _ := json.Marshal(instruction)

	imsReq, err := http.NewRequest(http.MethodPost, targetURL, bytes.NewReader(bytesPayload))
	if err != nil {
		log.Printf("[DCSF] Failed to create instruction request: %v", err)
		writeProblemDetails(w, http.StatusInternalServerError, "Internal Server Error", err.Error(), "INTERNAL_SERVER_ERROR")
		return
	}
	imsReq.Header.Set("Content-Type", "application/json")

	resp, err := s.httpClient.Do(imsReq)
	if err != nil {
		log.Printf("[DCSF] Failed to send instruction to IMS-AS: %v", err)
		writeProblemDetails(w, http.StatusBadGateway, "Bad Gateway", err.Error(), "BAD_GATEWAY")
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		log.Printf("[DCSF] IMS-AS instruction failed with status: %d", resp.StatusCode)
		writeProblemDetails(w, http.StatusBadGateway, "Bad Gateway", "IMS-AS instruction failed", "IMS_AS_FAILED")
		return
	}

	var updatedInstructions sba.MediaInstructionData
	if resp.StatusCode == http.StatusOK {
		_ = json.NewDecoder(resp.Body).Decode(&updatedInstructions)
		log.Printf("[DCSF] IMS-AS successfully applied media instructions. Allocated endpoints: %+v", updatedInstructions)
	}

	w.WriteHeader(http.StatusNoContent)
}

// handleMediaChangeRequest processes DC1 MediaChangeRequestEvent (DEF-04)
// This is triggered by UE re-INVITE containing a=3gpp-req-app
func (s *DCSFServer) handleMediaChangeRequest(w http.ResponseWriter, r *http.Request, req sba.SessionEventNotification) {
	s.sessionsMu.RLock()
	_, exists := s.sessions[req.SessionID]
	s.sessionsMu.RUnlock()

	// Lazy-Load from Redis if missing in RAM (pod rotation/crash scenario)
	if !exists {
		data, err := redisclient.Get("dcsf:session:" + req.SessionID)
		if err == nil && data != "" {
			var state CallSessionState
			if err := json.Unmarshal([]byte(data), &state); err == nil {
				s.sessionsMu.Lock()
				s.sessions[req.SessionID] = &state
				s.sessionsMu.Unlock()
				exists = true
				log.Printf("[DCSF] Lazy-loaded session %s from Redis", req.SessionID)
			}
		}
	}

	log.Printf("[DCSF] Processing MediaChangeRequestEvent for session %s", req.SessionID)

	// Extract appBindingInfo from the notification (AC.5 Step 3)
	var reqAppId string
	if req.AppBindingInfo != nil {
		reqAppId = req.AppBindingInfo.ReqAppId
		log.Printf("[DCSF] Application binding request: reqAppId=%s, appDcInfo=%s",
			req.AppBindingInfo.ReqAppId, req.AppBindingInfo.AppDcInfo)
	} else if req.DcMediaPara != nil && req.DcMediaPara.AppBindingInfo != nil {
		reqAppId = req.DcMediaPara.AppBindingInfo.ReqAppId
		log.Printf("[DCSF] Application binding request (from DcMediaPara): reqAppId=%s, appDcInfo=%s",
			req.DcMediaPara.AppBindingInfo.ReqAppId, req.DcMediaPara.AppBindingInfo.AppDcInfo)
	} else {
		log.Printf("[DCSF] Warning: MediaChangeRequestEvent without appBindingInfo")
		reqAppId = "unknown"
	}

	// Query DCAR DC5 for the DC Application Profile
	dcarHost := s.cfg.Services.Dcar.Host
	dcarPort := s.cfg.Services.Dcar.Port
	appProfile := s.queryDcarAppProfile(dcarHost, dcarPort, reqAppId)

	connectionPolicy := "P2P" // default
	qosHint := "latency=200;loss=0.05"
	if appProfile != nil {
		if cp, ok := appProfile["connectionPolicy"].(string); ok && cp != "" {
			connectionPolicy = cp
		}
		if qh, ok := appProfile["qosHint"].(string); ok && qh != "" {
			qosHint = qh
		}
	}

	// For specific apps, if connectionPolicy is P2P (like caro, whiteboard) we use P2P.
	// For fun-calling-app, it requires symmetric P2A2P setup.
	// For operator-menu, it's P2A (HTTP_PROXY).
	if reqAppId == "whiteboard" || reqAppId == "sketch-sync-app" || reqAppId == "0003210000000038" || reqAppId == "tuyaapp" {
		connectionPolicy = "P2P"
	} else if reqAppId == "caro" || reqAppId == "tic-tac-toe-app" || reqAppId == "fun-calling-app" || reqAppId == "smart-menu" || reqAppId == "enterprise-callerid" || reqAppId == "flight-booking" {
		connectionPolicy = "P2A" // Anchored via DCAS server
	}

	// Interworking override: If terminating UE is legacy (MTSI UE), force proxying (P2A)
	s.sessionsMu.RLock()
	sessState, exists := s.sessions[req.SessionID]
	s.sessionsMu.RUnlock()
	if exists && sessState.IsInterworking {
		connectionPolicy = "P2A"
		log.Printf("[DCSF] Interworking Active for session %s. Overriding policy to P2A to anchor traffic locally.", req.SessionID)

		// Search for active NEF/ImsEE subscription matching the app ID
		s.subscriptionsMu.RLock()
		var notificationUri string
		for _, sub := range s.subscriptions {
			if sub.EventId == "DC_INTERWORKING_REQUIRED" {
				for _, appId := range sub.AppIds {
					if appId == reqAppId {
						notificationUri = sub.NotificationUri
						break
					}
				}
			}
		}
		s.subscriptionsMu.RUnlock()

		if notificationUri != "" {
			go func() {
				client := newH2CClient(5 * time.Second)
				notifyReq := map[string]interface{}{
					"eventId": "DC_INTERWORKING_REQUIRED",
					"sessionId": req.SessionID,
					"ue2Information": map[string]interface{}{
						"calledIdentity": "sip:" + sessState.Callee + "@" + s.cfg.Domains.Ims,
					},
				}
				bodyBytes, _ := json.Marshal(notifyReq)
				log.Printf("[DCSF-NEF] Relaying interworking notification to DC AS: %s", notificationUri)
				resp, err := client.Post(notificationUri, "application/json", bytes.NewBuffer(bodyBytes))
				if err == nil {
					resp.Body.Close()
				} else {
					log.Printf("[DCSF-NEF] Failed to notify DC AS: %v", err)
				}
			}()
		}
	}

	adcType := "P2A2P"
	mediaProxyConfig := ""
	if reqAppId == "whiteboard" || reqAppId == "sketch-sync-app" || reqAppId == "0003210000000038" || reqAppId == "tuyaapp" {
		adcType = "P2A2P"
		mediaProxyConfig = ""
	} else if reqAppId == "caro" || reqAppId == "tic-tac-toe-app" || reqAppId == "fun-calling-app" || reqAppId == "smart-menu" || reqAppId == "enterprise-callerid" || reqAppId == "flight-booking" {
		adcType = "P2A2P"
		mediaProxyConfig = "UDP_PROXY"
	} else if reqAppId == "operator-menu" {
		adcType = "P2A"
		mediaProxyConfig = "HTTP_PROXY"
	} else {
		adcType = "P2P"
		mediaProxyConfig = ""
	}

	log.Printf("[DCSF] App profile for %s: connectionPolicy=%s, adcType=%s, qosHint=%s", reqAppId, connectionPolicy, adcType, qosHint)

	// Build ADC-specific media instructions
	dcasHost := s.cfg.Services.Dcas.Host
	dcasPort := s.cfg.Services.Dcas.Port

	var mdc2EndpointInfo *sba.Mdc2EndpointInfo
	if mediaProxyConfig != "" {
		mdc2EndpointInfo = &sba.Mdc2EndpointInfo{
			Mdc2Protocol: "TCP",
			Mdc2EndpointDcAs: &sba.MdcEndpoint{
				IP:   dcasHost,
				Port: dcasPort,
			},
		}
	}

	instruction := sba.MediaInstructionData{
		SessionID: req.SessionID,
		MediaInstructionSet: []sba.MediaInstructions{
			{
				MediaID:           "adc-change",
				MediaResourceType: "DC",
				MediaInstruction:  "ModifyMedia",
				DcMediaSpecification: &sba.DcMediaSpecification{
					MediaProxyConfig: mediaProxyConfig,
					QosHint:          qosHint,
					AdcType:          adcType,
					Mdc2EndpointInfo: mdc2EndpointInfo,
				},
			},
		},
	}

	// Send instruction to IMS-AS via Nimsas_MediaControl
	remoteIP, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		remoteIP = s.cfg.Services.ImsAs.Host
	}
	imsAsURL := fmt.Sprintf("http://%s:%d", remoteIP, s.cfg.Services.ImsAs.Port)
	targetURL := fmt.Sprintf("%s/nimsas-mc/v1/call-sessions/%s/media-instruction", imsAsURL, req.SessionID)

	log.Printf("[DCSF] Sending MediaChange instructions to IMS-AS on: %s", targetURL)
	bytesPayload, _ := json.Marshal(instruction)

	imsReq, err := http.NewRequest(http.MethodPost, targetURL, bytes.NewReader(bytesPayload))
	if err != nil {
		log.Printf("[DCSF] Failed to create MediaChange request: %v", err)
		writeProblemDetails(w, http.StatusInternalServerError, "Internal Server Error", err.Error(), "INTERNAL_SERVER_ERROR")
		return
	}
	imsReq.Header.Set("Content-Type", "application/json")

	resp, err := s.httpClient.Do(imsReq)
	if err != nil {
		log.Printf("[DCSF] Failed to send MediaChange to IMS-AS: %v", err)
		writeProblemDetails(w, http.StatusBadGateway, "Bad Gateway", err.Error(), "BAD_GATEWAY")
		return
	}
	defer resp.Body.Close()

	log.Printf("[DCSF] IMS-AS responded with status %d to MediaChange", resp.StatusCode)
	w.WriteHeader(http.StatusNoContent)
}

// queryDcarAppProfile queries DCAR DC5 for application profile metadata
func (s *DCSFServer) queryDcarAppProfile(dcarHost string, dcarPort int, appId string) map[string]interface{} {
	dcarURL := fmt.Sprintf("http://%s:%d/dcar/v1/applications/%s", dcarHost, dcarPort, appId)
	log.Printf("[DCSF] Querying DCAR DC5 for app profile: %s", dcarURL)

	var resp *http.Response
	var err error
	for attempt := 1; attempt <= 3; attempt++ {
		resp, err = s.httpClient.Get(dcarURL)
		if err == nil {
			break
		}
		log.Printf("[DCSF] Dcar query attempt %d timeout/err: %v. Retrying in 1s...", attempt, err)
		time.Sleep(1 * time.Second)
	}
	if err != nil {
		log.Printf("[DCSF] Failed to query DCAR app profile: %v", err)
		return nil
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		log.Printf("[DCSF] DCAR returned %d for app profile %s", resp.StatusCode, appId)
		return nil
	}

	var profile map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&profile); err != nil {
		log.Printf("[DCSF] Failed to decode DCAR app profile: %v", err)
		return nil
	}
	log.Printf("[DCSF] DCAR app profile loaded: %+v", profile)
	return profile
}

// Helper to inject session JS into index.html inside the operator-menu ZIP
func injectSessionToZip(zipBytes []byte, sessionID, user, role string) ([]byte, error) {
	r, err := zip.NewReader(bytes.NewReader(zipBytes), int64(len(zipBytes)))
	if err != nil {
		return nil, err
	}

	var buf bytes.Buffer
	w := zip.NewWriter(&buf)

	sessionScript := fmt.Sprintf(`<script>
    // Session context injected by DCSF (DC3 interface)
    var __dcsfSession = %q;
    var __dcsfUser = %q;
    var __dcsfRole = %q;

    window.addEventListener('DOMContentLoaded', function() {
      if (__dcsfRole === 'callee') {
        var statusEl = document.getElementById('status');
          if (statusEl) statusEl.textContent = 'Waiting for caller to launch app...';
        }
      });
    </script>`, sessionID, user, role)

	for _, f := range r.File {
		fw, err := w.Create(f.Name)
		if err != nil {
			return nil, err
		}
		rc, err := f.Open()
		if err != nil {
			return nil, err
		}
		data, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			return nil, err
		}

		if f.Name == "index.html" {
			htmlContent := string(data)
			if idx := strings.LastIndex(htmlContent, "</body>"); idx >= 0 {
				htmlContent = htmlContent[:idx] + sessionScript + "\n" + htmlContent[idx:]
			} else {
				htmlContent += sessionScript
			}
			data = []byte(htmlContent)
		}

		_, err = fw.Write(data)
		if err != nil {
			return nil, err
		}
	}

	if err := w.Close(); err != nil {
		return nil, err
	}

	return buf.Bytes(), nil
}

// Serve premium subscriber menu as a ZIP package with injected session scripts
func (s *DCSFServer) HandleMenu(w http.ResponseWriter, r *http.Request) {
	user := r.URL.Query().Get("user")
	sessionID := r.URL.Query().Get("session_id")
	role := r.URL.Query().Get("role")

	log.Printf("[DCSF] HandleMenu: Fetching operator-menu from DCAR for user=%s (session=%s, role=%s)", user, sessionID, role)

	// ── DC5: Fetch menu ZIP from DCAR ──────────────────────────────────────
	dcarHost := s.cfg.Services.Dcar.Host
	dcarPort := s.cfg.Services.Dcar.Port
	dcarURL := fmt.Sprintf("http://%s:%d/dcar/v1/applications/operator-menu/download", dcarHost, dcarPort)

	req, _ := http.NewRequest("GET", dcarURL, nil)
	ifNoneMatch := r.Header.Get("If-None-Match")
	if ifNoneMatch != "" {
		req.Header.Set("If-None-Match", ifNoneMatch)
	}

	resp, err := s.httpClient.Do(req)
	if err != nil {
		log.Printf("[DCSF] Failed to fetch operator-menu from DCAR (%s): %v", dcarURL, err)
		writeProblemDetails(w, http.StatusBadGateway, "Bad Gateway", "Failed to fetch menu from DCAR", "DCAR_FETCH_FAILED")
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		log.Printf("[DCSF] DCAR returned status %d for operator-menu", resp.StatusCode)
		writeProblemDetails(w, http.StatusNotFound, "Not Found", "DCAR operator-menu not found", "MENU_NOT_FOUND")
		return
	}

	zipBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		writeProblemDetails(w, http.StatusInternalServerError, "Internal Server Error", err.Error(), "INTERNAL_SERVER_ERROR")
		return
	}

	// Inject the dynamic session details directly into the ZIP package's index.html
	modifiedZip, err := injectSessionToZip(zipBytes, sessionID, user, role)
	if err != nil {
		log.Printf("[DCSF] Failed to process/inject operator-menu ZIP: %v", err)
		writeProblemDetails(w, http.StatusInternalServerError, "Internal Server Error", "Failed to process menu ZIP package", "ZIP_INJECTION_FAILED")
		return
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", "attachment; filename=operator-menu.zip")
	w.Header().Set("Content-Length", fmt.Sprintf("%d", len(modifiedZip)))
	w.Write(modifiedZip)
	log.Printf("[DCSF] Successfully served operator-menu ZIP with injected session (%d bytes)", len(modifiedZip))
}

func (s *DCSFServer) HandleApplication(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path
	appId := strings.TrimPrefix(path, "/dcar/v1/applications/")
	appId = strings.TrimSuffix(appId, "/download")
	if idx := strings.Index(appId, "?"); idx != -1 {
		appId = appId[:idx]
	}

	log.Printf("[DCSF] Fetching application package for AppID: %s via DC5 from DCAR", appId)

	dcarHost := s.cfg.Services.Dcar.Host
	dcarPort := s.cfg.Services.Dcar.Port
	dcarURL := fmt.Sprintf("http://%s:%d/dcar/v1/applications/%s/download", dcarHost, dcarPort, appId)

	req, _ := http.NewRequest("GET", dcarURL, nil)
	ifNoneMatch := r.Header.Get("If-None-Match")
	if ifNoneMatch != "" {
		req.Header.Set("If-None-Match", ifNoneMatch)
	}

	resp, err := s.httpClient.Do(req)
	if err != nil {
		log.Printf("[DCSF] Failed to fetch app %s from DCAR: %v", appId, err)
		writeProblemDetails(w, http.StatusNotFound, "Not Found", "App not found on DCAR", "APP_NOT_FOUND")
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotModified {
		log.Printf("[DCSF] DCAR returned 304 Not Modified for app %s", appId)
		etag := resp.Header.Get("ETag")
		if etag != "" {
			w.Header().Set("ETag", etag)
		}
		w.WriteHeader(http.StatusNotModified)
		return
	}

	if resp.StatusCode != http.StatusOK {
		log.Printf("[DCSF] DCAR returned status %d for app %s", resp.StatusCode, appId)
		writeProblemDetails(w, http.StatusNotFound, "Not Found", "App not found on DCAR", "APP_NOT_FOUND")
		return
	}

	zipBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		writeProblemDetails(w, http.StatusInternalServerError, "Internal Server Error", err.Error(), "INTERNAL_SERVER_ERROR")
		return
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s.zip\"", appId))
	etag := resp.Header.Get("ETag")
	if etag != "" {
		w.Header().Set("ETag", etag)
	}
	w.Write(zipBytes)
}



func (s *DCSFServer) HandleNotify(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeProblemDetails(w, http.StatusMethodNotAllowed, "Method Not Allowed", "Method not allowed", "METHOD_NOT_ALLOWED")
		return
	}

	var req NotifyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeProblemDetails(w, http.StatusBadRequest, "Bad Request", err.Error(), "BAD_REQUEST")
		return
	}

	log.Printf("[DCSF] Received Session Event Notification from IMS-AS via %s: CallID=%s, From=%s, To=%s", r.Proto, req.CallID, req.From, req.To)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func (s *DCSFServer) HandlePlayVideo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeProblemDetails(w, http.StatusMethodNotAllowed, "Method Not Allowed", "Method not allowed", "METHOD_NOT_ALLOWED")
		return
	}

	var req PlayVideoRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeProblemDetails(w, http.StatusBadRequest, "Bad Request", err.Error(), "BAD_REQUEST")
		return
	}

	log.Printf("[DCSF] Received PlayVideo Command: CallID=%s, Action=%s", req.CallID, req.Action)

	var mfHost string
	if strings.Contains(req.CallID, "@") {
		parts := strings.Split(req.CallID, "@")
		mfHost = parts[1]
		log.Printf("[DCSF] Extracted stateless sticky routing target for MF: %s", mfHost)
	} else {
		mfHost = resolveAndHashHost(s.cfg.Services.Mf.Host, req.CallID)
		log.Printf("[DCSF] Hashed CallID %s -> Selected Media Function Host: %s", req.CallID, mfHost)
	}

	url := fmt.Sprintf("http://%s:%d/play_video", mfHost, s.cfg.Services.Mf.Port)
	bytesPayload, _ := json.Marshal(req)

	proxyReq, err := http.NewRequest("POST", url, bytes.NewReader(bytesPayload))
	if err != nil {
		log.Printf("[DCSF] Failed to create proxy request to MF: %v", err)
		writeProblemDetails(w, http.StatusInternalServerError, "Internal Server Error", err.Error(), "INTERNAL_SERVER_ERROR")
		return
	}
	proxyReq.Header.Set("Content-Type", "application/json")

	resp, err := s.httpClient.Do(proxyReq)
	if err != nil {
		log.Printf("[DCSF] Failed to invoke play_video to MF: %v", err)
		writeProblemDetails(w, http.StatusBadGateway, "Bad Gateway", err.Error(), "BAD_GATEWAY")
		return
	}
	defer resp.Body.Close()

	log.Printf("[DCSF] Forwarded play_video to MF successfully. Response: %s", resp.Status)
	w.WriteHeader(resp.StatusCode)
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func (s *DCSFServer) Start() error {
	mux := http.NewServeMux()
	mux.HandleFunc("/dc1/notify", s.HandleNotify)
	mux.HandleFunc("/nimsas-sec/v1/session-event-subscriptions", s.HandleSessionEventSubscription)
	mux.HandleFunc("/nimsas-sec/v1/session-event-subscriptions/", s.HandleSessionEventSubscription)
	mux.HandleFunc("/play_video", s.HandlePlayVideo)

	// New standard h2c endpoints for subscriber menus and static app download
	mux.HandleFunc("/dcar/v1/menu", s.HandleMenu)
	mux.HandleFunc("/dcar/v1/applications/", s.HandleApplication)
	mux.HandleFunc("/nimsas-imsee/v1/subscriptions", s.HandleImsEESubscribe)

	log.Printf("[DCSF] HTTP/2 Server listening on http://%s", s.addr)
	h2s := &http2.Server{}
	return http.ListenAndServe(s.addr, h2c.NewHandler(imsas.HEPMiddleware(mux), h2s))
}

func (s *DCSFServer) HandleImsEESubscribe(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeProblemDetails(w, http.StatusMethodNotAllowed, "Method Not Allowed", "Method not allowed", "")
		return
	}
	var sub struct {
		EventId            string `json:"eventId"`
		DcInterworkingInfo struct {
			AppIds []string `json:"appIds"`
		} `json:"dcInterworkingInfo"`
		NotificationUri string `json:"notificationUri"`
	}
	if err := json.NewDecoder(r.Body).Decode(&sub); err != nil {
		writeProblemDetails(w, http.StatusBadRequest, "Malformed Request", err.Error(), "")
		return
	}

	log.Printf("[DCSF-NEF] Received Nimsas_ImsEE_Subscribe: EventId=%s, AppIds=%v, NotificationUri=%s",
		sub.EventId, sub.DcInterworkingInfo.AppIds, sub.NotificationUri)

	s.subscriptionsMu.Lock()
	s.subscriptions = append(s.subscriptions, ImsEESubscription{
		EventId:         sub.EventId,
		AppIds:          sub.DcInterworkingInfo.AppIds,
		NotificationUri: sub.NotificationUri,
	})
	s.subscriptionsMu.Unlock()

	w.WriteHeader(http.StatusCreated)
}

func resolveAndHashHost(host, callID string) string {
	if host == "" {
		return "127.0.0.1"
	}
	lookupHost := host
	if net.ParseIP(host) == nil && !strings.HasSuffix(host, ".") {
		lookupHost = host + "."
	}
	ips, err := net.LookupIP(lookupHost)
	if err != nil || len(ips) == 0 {
		return host
	}
	var ipStrings []string
	for _, ip := range ips {
		if ip.To4() != nil {
			ipStrings = append(ipStrings, ip.String())
		}
	}
	if len(ipStrings) <= 1 {
		return host
	}
	sort.Strings(ipStrings)
	h := fnv.New32a()
	_, _ = h.Write([]byte(callID))
	hashVal := h.Sum32()
	chosenIP := ipStrings[hashVal%uint32(len(ipStrings))]
	log.Printf("[DCSF] Resolved '%s' to %v. Picked '%s' for CallID '%s' (hash=%d)", host, ipStrings, chosenIP, callID, hashVal)
	return chosenIP
}

func extractUsername(identity string) string {
	username := strings.TrimPrefix(identity, "sip:")
	if idx := strings.Index(username, "@"); idx != -1 {
		username = username[:idx]
	}
	return username
}
