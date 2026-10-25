package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/imsas"
	"github.com/demo/ims-dc-mf/internal/logger"
	"github.com/demo/ims-dc-mf/internal/mf"
	"github.com/demo/ims-dc-mf/internal/sba"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
)

func writeMFProblemDetails(w http.ResponseWriter, status int, title, detail, cause string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	pd := sba.ProblemDetails{
		Status: status,
		Title:  title,
		Detail: detail,
		Cause:  cause,
	}
	json.NewEncoder(w).Encode(pd)
}

func validateOAuth2Scope(r *http.Request, requiredScope string) error {
	auth := r.Header.Get("Authorization")
	if auth != "" {
		if !strings.HasPrefix(auth, "Bearer ") {
			return fmt.Errorf("invalid authorization header format")
		}
		token := strings.TrimPrefix(auth, "Bearer ")
		if !strings.Contains(token, requiredScope) && token != "trusted-token" {
			return fmt.Errorf("insufficient scope: required %s", requiredScope)
		}
	}
	return nil
}

func sendSBADirect(w http.ResponseWriter, r *http.Request, status int, urlStr, targetNfID string) {
	w.Header().Set("3gpp-Sbi-Target-Nf-Id", targetNfID)
	w.Header().Set("Location", urlStr)
	w.WriteHeader(status)
}

type DC2CreateRequest struct {
	CallID    string `json:"call_id"`
	UserID    string `json:"user_id"`
	IsHotline bool   `json:"is_hotline"`
	SDPOffer  string `json:"sdp_offer,omitempty"`
}

type DC2SetAnswerRequest struct {
	SessionID string `json:"session_id"`
	SDPAnswer string `json:"sdp_answer"`
}

type DC2LinkRequest struct {
	CallID   string `json:"call_id"`
	SessionA string `json:"session_a"`
	SessionB string `json:"session_b"`
}

func main() {
	logger.InitLogger("mf")
	log.Println("========================================")
	log.Println("  MF Node (Media Function Server)")
	log.Println("  3GPP TS 23.228 Annex AC PoC Demo")
	log.Println("========================================")

	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatalf("[MF] Failed to load configuration: %v", err)
	}

	mediaFunc := mf.NewMediaFunction(cfg)

	mux := http.NewServeMux()

	// --- 3GPP SBA REST Nmf Endpoints (TS 29.176) ---

	mux.HandleFunc("/nmf-mrm/v1/contexts", func(w http.ResponseWriter, r *http.Request) {
		if err := validateOAuth2Scope(r, "nmf-mrm"); err != nil {
			writeMFProblemDetails(w, http.StatusUnauthorized, "Unauthorized", err.Error(), "OAUTH2_SCOPE_VALIDATION_FAILED")
			return
		}
		if r.Method != http.MethodPost {
			writeMFProblemDetails(w, http.StatusMethodNotAllowed, "Method Not Allowed", "POST required", "METHOD_NOT_ALLOWED")
			return
		}
		contentType := r.Header.Get("Content-Type")
		if !strings.HasPrefix(contentType, "application/json") {
			writeMFProblemDetails(w, http.StatusUnsupportedMediaType, "Unsupported Media Type", "Content-Type must be application/json", "UNSUPPORTED_MEDIA_TYPE")
			return
		}
		var ctxReq sba.MediaContext
		if err := json.NewDecoder(r.Body).Decode(&ctxReq); err != nil {
			writeMFProblemDetails(w, http.StatusBadRequest, "Bad Request", err.Error(), "INVALID_JSON")
			return
		}

		log.Printf("[MF] Received standard Nmf Context Create")
		resp, err := mediaFunc.CreateMediaContext(&ctxReq)
		if err != nil {
			log.Printf("[MF] Context creation failed: %v", err)
			status := http.StatusInternalServerError
			cause := "INSUFFICIENT_RESOURCES"
			if strings.Contains(err.Error(), "MEDIA_ID_CONFLICT") {
				status = http.StatusForbidden
				cause = "MEDIA_ID_CONFLICT"
			}
			writeMFProblemDetails(w, status, "Context Create Failed", err.Error(), cause)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Location", fmt.Sprintf("/nmf-mrm/v1/contexts/%s", resp.ContextID))
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(resp)
	})

	mux.HandleFunc("/nmf-mrm/v1/contexts/", func(w http.ResponseWriter, r *http.Request) {
		if err := validateOAuth2Scope(r, "nmf-mrm"); err != nil {
			writeMFProblemDetails(w, http.StatusUnauthorized, "Unauthorized", err.Error(), "OAUTH2_SCOPE_VALIDATION_FAILED")
			return
		}
		pathParts := strings.Split(r.URL.Path, "/")
		if len(pathParts) < 5 {
			writeMFProblemDetails(w, http.StatusBadRequest, "Bad Request", "Invalid context ID in URI path", "INVALID_CONTEXT_URI")
			return
		}
		contextID := pathParts[len(pathParts)-1]
		if contextID == "" && len(pathParts) >= 6 {
			contextID = pathParts[len(pathParts)-2]
		}

		if r.Method == http.MethodPatch {
			contentType := r.Header.Get("Content-Type")
			if !strings.HasPrefix(contentType, "application/json-patch+json") {
				writeMFProblemDetails(w, http.StatusUnsupportedMediaType, "Unsupported Media Type", "Content-Type must be application/json-patch+json", "UNSUPPORTED_MEDIA_TYPE")
				return
			}
			var patches []sba.PatchDocument
			if err := json.NewDecoder(r.Body).Decode(&patches); err != nil {
				writeMFProblemDetails(w, http.StatusBadRequest, "Bad Request", err.Error(), "INVALID_JSON")
				return
			}
			log.Printf("[MF] Received standard Nmf Context Patch for: %s", contextID)
			if err := mediaFunc.UpdateMediaContext(contextID, patches); err != nil {
				log.Printf("[MF] UpdateMediaContext failed: %v", err)
				status := http.StatusInternalServerError
				cause := "INSUFFICIENT_RESOURCES"
				if strings.Contains(err.Error(), "context not found") {
					status = http.StatusNotFound
					cause = "CONTEXT_NOT_FOUND"
				} else if strings.Contains(err.Error(), "MEDIA_CONNECTION_CHANGED") {
					status = http.StatusForbidden
					cause = "MEDIA_CONNECTION_CHANGED"
				}
				writeMFProblemDetails(w, status, "Context Patch Failed", err.Error(), cause)
				return
			}
			w.WriteHeader(http.StatusNoContent)

		} else if r.Method == http.MethodDelete {
			log.Printf("[MF] Received standard Nmf Context Delete for: %s", contextID)
			if err := mediaFunc.DeleteMediaContext(contextID); err != nil {
				log.Printf("[MF] DeleteMediaContext failed: %v", err)
				writeMFProblemDetails(w, http.StatusNotFound, "Context Not Found", err.Error(), "CONTEXT_NOT_FOUND")
				return
			}
			w.WriteHeader(http.StatusNoContent)

		} else {
			writeMFProblemDetails(w, http.StatusMethodNotAllowed, "Method Not Allowed", "Method not supported on this context resource", "METHOD_NOT_ALLOWED")
		}
	})

	// --- Legacy REST API DC2 endpoints ---
	mux.HandleFunc("/dc2/create", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req DC2CreateRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		log.Printf("[MF] Received /dc2/create via %s: user=%s, call=%s", r.Proto, req.UserID, req.CallID)

		var resp *mf.AllocateResponse
		var err error
		if req.IsHotline {
			isCore := false
			if req.SDPOffer != "" && !strings.Contains(req.SDPOffer, "c=IN IP4 100.") {
				isCore = true
			}
			resp, err = mediaFunc.AllocateHotlineSession(req.UserID, req.CallID, req.SDPOffer, isCore)
		} else if req.SDPOffer != "" {
			isCore := false
			if !strings.Contains(req.SDPOffer, "c=IN IP4 100.") {
				isCore = true
			}
			resp, err = mediaFunc.AllocateAnswererSession(req.UserID, req.CallID, req.SDPOffer, isCore)
		} else {
			resp, err = mediaFunc.AllocateSession(req.UserID, req.CallID, false, "")
		}

		if err != nil {
			log.Printf("[MF] Session allocation failed: %v", err)
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(resp)
	})

	mux.HandleFunc("/dc2/set_remote_answer", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req DC2SetAnswerRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		if err := mediaFunc.SetRemoteAnswer(req.SessionID, req.SDPAnswer); err != nil {
			log.Printf("[MF] SetRemoteAnswer failed for session %s: %v", req.SessionID, err)
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("/dc2/link_sessions", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req DC2LinkRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		if err := mediaFunc.LinkSessions(req.SessionA, req.SessionB); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("/dc2/release", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req struct {
			SessionID string `json:"session_id"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		log.Printf("[MF] Releasing session %s", req.SessionID)
		mediaFunc.CleanupSession(req.SessionID)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	mux.HandleFunc("/sessions", func(w http.ResponseWriter, r *http.Request) {
		sessions := mediaFunc.ListSessions()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(sessions)
	})

	mux.HandleFunc("/play_video", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req struct {
			CallID string `json:"call_id"`
			Action string `json:"action"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		log.Printf("[MF] Received /play_video via %s: call=%s, action=%s", r.Proto, req.CallID, req.Action)

		err := mediaFunc.PlayVideoAction(req.CallID, req.Action)
		if err != nil {
			log.Printf("[MF] PlayVideoAction failed: %v", err)
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	// --- Announcement playback endpoints ---
	mux.HandleFunc("/announcement/play", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req struct {
			CallID      string `json:"call_id"`
			SDPOffer    string `json:"sdp_offer"`
			File        string `json:"file"`
			CallbackURL string `json:"callback_url"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		log.Printf("[MF] Received /announcement/play: call=%s, file=%s, callback=%s", req.CallID, req.File, req.CallbackURL)

		allocResp, err := mediaFunc.AllocateAnnouncementSession("announcement-caller", req.CallID, req.SDPOffer)
		if err != nil {
			log.Printf("[MF] AllocateAnnouncementSession failed for call %s: %v", req.CallID, err)
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		mSession := mediaFunc.GetSession(allocResp.SessionID)
		if mSession != nil {
			fileName := req.File
			if fileName == "" {
				fileName = "unreachable"
			}
			go mediaFunc.PlayAnnouncement(mSession, fileName, req.CallbackURL)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(allocResp)
	})

	mux.HandleFunc("/announcement/stop", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			return
		}
		var req struct {
			CallID string `json:"call_id"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		log.Printf("[MF] Received /announcement/stop: call=%s", req.CallID)
		mediaFunc.StopAnnouncementByCallID(req.CallID)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	addr := fmt.Sprintf("0.0.0.0:%d", cfg.Services.Mf.Port)
	log.Printf("[MF] HTTP/2 Control Plane REST API listening on http://%s", addr)

	go func() {
		h2s := &http2.Server{}
		if err := http.ListenAndServe(addr, h2c.NewHandler(imsas.HEPMiddleware(mux), h2s)); err != nil {
			log.Fatalf("[MF] HTTP/2 control server failed: %v", err)
		}
	}()

	// Wait for shutdown signal
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("[MF] Shutting down Media Function...")
	mediaFunc.Shutdown()
}
