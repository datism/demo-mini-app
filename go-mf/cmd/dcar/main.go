package main

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"regexp"
	"strings"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/dcar"
	"github.com/demo/ims-dc-mf/internal/imsas"
	"github.com/demo/ims-dc-mf/internal/logger"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
)

var (
	// appMetaRegex: matches /dcar/v1/applications/<appId>
	// Supports: alphanumeric, dash, underscore AND numeric-only long IDs (e.g. 0003210000000038)
	appMetaRegex = regexp.MustCompile(`^/dcar/v1/applications/([a-zA-Z0-9_\-\.]+)$`)
	appDownRegex = regexp.MustCompile(`^/dcar/v1/applications/([a-zA-Z0-9_\-\.]+)/download$`)
)

func main() {
	logger.InitLogger("dcar")
	log.Println("========================================")
	log.Println("  DCAR Node (App Repository Server)")
	log.Println("  3GPP TS 23.228 Annex AC PoC Demo")
	log.Println("========================================")

	cfg, err := config.LoadConfig()
	if err != nil {
		log.Fatalf("[DCAR] Failed to load configuration: %v", err)
	}

	appsDir := "./static/apps"
	log.Printf("[DCAR] Storage directory: %s", appsDir)

	repo := dcar.NewAppRepository(appsDir)

	mux := http.NewServeMux()

	// Giao diÃ¡Â»â€¡n DC5: Truy vÃ¡ÂºÂ¥n cÃ¡ÂºÂ¥u hÃƒÂ¬nh Ã¡Â»Â©ng dÃ¡Â»Â¥ng
	mux.HandleFunc("/dcar/v1/applications/", func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		if r.Method != http.MethodGet {
			http.Error(w, "Method Not Allowed", http.StatusMethodNotAllowed)
			return
		}

		// Check download match first (longer path)
		if matches := appDownRegex.FindStringSubmatch(path); len(matches) > 1 {
			appId := matches[1]
			log.Printf("[DCAR] Serving ZIP download request for AppID: %s", appId)
			var zipBytes []byte
			var err error
			switch {
			case appId == "tic-tac-toe-app":
				log.Printf("[DCAR] Serving caro package for AppID alias: %s", appId)
				zipBytes, err = repo.DownloadZipPackage("caro")
			case appId == "0003210000000038" || strings.EqualFold(appId, "tuyaapp") || strings.EqualFold(appId, "sketch-sync-app"):
				log.Printf("[DCAR] Serving official GSMA TuYaApp_1.0.9.zip for AppID: %s", appId)
				zipBytes, err = os.ReadFile("./static/assets/TuYaApp_1.0.9.zip")
				if err != nil {
					log.Printf("[DCAR] Failed to read ./static/assets/TuYaApp_1.0.9.zip: %v, falling back to repo package", err)
					zipBytes, err = repo.DownloadZipPackage("sketch-sync-app")
				}
			case appId == "0003210000000001" || strings.EqualFold(appId, "operator-menu"):
				log.Printf("[DCAR] Serving zip package for Operator Menu AppID: %s", appId)
				zipBytes, err = repo.DownloadZipPackage("operator-menu")
			case strings.EqualFold(appId, "gsma-demo") || appId == "0000000000000001":
				// GSMA IMS DC Mini-App Demo (official GSMA sample)
				log.Printf("[DCAR] Serving GSMA official demo mini-app for AppID: %s", appId)
				zipBytes, err = os.ReadFile("./static/assets/IMS_DC_Mini_app_demo.zip")
			default:
				zipBytes, err = repo.DownloadZipPackage(appId)
			}
			if err != nil {
				log.Printf("[DCAR] Error packaging app %s: %v", appId, err)
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			}

			// ETag from the package content: an edit that keeps the ZIP the same size must still reach the phones.
			sum := sha256.Sum256(zipBytes)
			etag := fmt.Sprintf(`"%x"`, sum[:12])

			ifNoneMatch := r.Header.Get("If-None-Match")
			if ifNoneMatch == etag {
				log.Printf("[DCAR] ETag matched (%s), returning 304 Not Modified for AppID: %s", etag, appId)
				w.Header().Set("ETag", etag)
				w.WriteHeader(http.StatusNotModified)
				return
			}

			w.Header().Set("Access-Control-Allow-Origin", "*")
			w.Header().Set("Content-Type", "application/zip")
			w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%s.zip", appId))
			w.Header().Set("Content-Length", fmt.Sprintf("%d", len(zipBytes)))
			w.Header().Set("ETag", etag)
			w.Write(zipBytes)
			return
		}

		// Check metadata match
		if matches := appMetaRegex.FindStringSubmatch(path); len(matches) > 1 {
			appId := matches[1]
			log.Printf("[DCAR] Serving metadata query for AppID: %s", appId)
			switch {
			case appId == "tic-tac-toe-app":
				log.Printf("[DCAR] Serving caro metadata for AppID alias: %s", appId)
				meta, err := repo.LoadAndValidateApp("caro")
				if err != nil {
					http.Error(w, err.Error(), http.StatusNotFound)
					return
				}
				w.Header().Set("Content-Type", "application/json")
				json.NewEncoder(w).Encode(meta)
				return
			case appId == "0003210000000038" || strings.EqualFold(appId, "tuyaapp") || strings.EqualFold(appId, "sketch-sync-app"):
				meta := map[string]interface{}{
					"appId": appId, "appName": "TuYaApp", "version": "1.0.9",
					"connectionPolicy": "P2P",
					"qosHint":          "latency=100;loss=0.01",
				}
				w.Header().Set("Content-Type", "application/json")
				json.NewEncoder(w).Encode(meta)
				return
			case appId == "0003210000000001" || strings.EqualFold(appId, "operator-menu"):
				meta := map[string]interface{}{
					"appId": appId, "appName": "Operator Menu", "version": "1.0.0",
					"connectionPolicy": "P2A",
					"qosHint":          "latency=200;loss=0.05",
				}
				w.Header().Set("Content-Type", "application/json")
				json.NewEncoder(w).Encode(meta)
				return
			case strings.EqualFold(appId, "gsma-demo") || appId == "0000000000000001":
				// GSMA official IMS DC Mini-App Demo
				meta := map[string]interface{}{
					"appId": appId, "appName": "IMS DC Mini-App Demo", "version": "1.0.0",
					"provider": "GSMA", "spec": "GSMA TS.66 Release 26",
					"connectionPolicy": "P2A",
					"qosHint":          "latency=50;loss=0.005",
				}
				w.Header().Set("Content-Type", "application/json")
				json.NewEncoder(w).Encode(meta)
				return
			}
			meta, err := repo.LoadAndValidateApp(appId)
			if err != nil {
				log.Printf("[DCAR] App metadata not found for %s: %v", appId, err)
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(meta)
			return
		}

		http.Error(w, "Not Found", http.StatusNotFound)
	})

	// Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ Route: /static/ Ã¢â‚¬â€ Generic static file server Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
	// Cho phÃƒÂ©p serve bÃ¡ÂºÂ¥t kÃ¡Â»Â³ file nÃƒÂ o trong thÃ†Â° mÃ¡Â»Â¥c ./static/ vÃ¡Â»â€ºi CORS headers
	staticFs := http.FileServer(http.Dir("./static/"))
	mux.Handle("/static/", http.StripPrefix("/static/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "*")
		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusOK)
			return
		}
		staticFs.ServeHTTP(w, r)
	})))

	addr := fmt.Sprintf("0.0.0.0:%d", cfg.Services.Dcar.Port)
	log.Printf("[DCAR] Serving static assets on HTTP/2 http://%s", addr)

	h2s := &http2.Server{}
	if err := http.ListenAndServe(addr, h2c.NewHandler(imsas.HEPMiddleware(mux), h2s)); err != nil {
		log.Fatalf("[DCAR] Server failed: %v", err)
	}
}
