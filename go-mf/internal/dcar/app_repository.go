package dcar

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"sync"
)

// QosHint định nghĩa yêu cầu chất lượng mạng mong muốn
type QosHint struct {
	LatencyMs      int     `json:"latencyMs"`
	PacketLossRate float64 `json:"packetLossRate"`
}

// AppMetadata chứa thông tin cấu hình từ app.json
type AppMetadata struct {
	AppID   string  `json:"appId"`
	QosHint QosHint `json:"qosHint"`
}

// AppRepository quản lý và cung cấp nội dung Mini-app
type AppRepository struct {
	appsDir string
	mu      sync.RWMutex
}

// NewAppRepository khởi tạo DCAR trỏ tới thư mục chứa các Mini-app
func NewAppRepository(appsDir string) *AppRepository {
	// Tạo thư mục nếu chưa tồn tại
	if err := os.MkdirAll(appsDir, 0755); err != nil {
		log.Printf("[DCAR] Failed to create apps directory %s: %v", appsDir, err)
	}

	return &AppRepository{
		appsDir: appsDir,
	}
}

// GetAppsDir returns the configured applications storage directory
func (r *AppRepository) GetAppsDir() string {
	return r.appsDir
}

// LoadAndValidateApp đọc và kiểm định cấu hình app.json của một appId
func (r *AppRepository) LoadAndValidateApp(appId string) (*AppMetadata, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	appJsonPath := filepath.Join(r.appsDir, appId, "app.json")
	data, err := os.ReadFile(appJsonPath)
	if err != nil {
		return nil, fmt.Errorf("read app.json failed: %w", err)
	}

	var meta AppMetadata
	if err := json.Unmarshal(data, &meta); err != nil {
		return nil, fmt.Errorf("parse app.json failed: %w", err)
	}

	// Validate fields
	if meta.AppID == "" {
		return nil, fmt.Errorf("invalid app.json: appId is required")
	}
	if meta.AppID != appId {
		return nil, fmt.Errorf("appId mismatch: expected %q, got %q", appId, meta.AppID)
	}
	if meta.QosHint.LatencyMs <= 0 {
		return nil, fmt.Errorf("invalid qosHint: latencyMs must be positive")
	}

	return &meta, nil
}

// DownloadZipPackage nén toàn bộ thư mục của appId thành một file ZIP dạng binary stream
func (r *AppRepository) DownloadZipPackage(appId string) ([]byte, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	appPath := filepath.Join(r.appsDir, appId)
	info, err := os.Stat(appPath)
	if err != nil || !info.IsDir() {
		return nil, fmt.Errorf("application directory %q not found", appPath)
	}

	var buf bytes.Buffer
	zipWriter := zip.NewWriter(&buf)

	err = filepath.Walk(appPath, func(path string, fileInfo os.FileInfo, err error) error {
		if err != nil {
			return err
		}

		// Tính toán relative path để lưu trong ZIP
		relPath, err := filepath.Rel(appPath, path)
		if err != nil {
			return err
		}

		if relPath == "." {
			return nil
		}

		header, err := zip.FileInfoHeader(fileInfo)
		if err != nil {
			return err
		}

		header.Name = filepath.ToSlash(relPath)

		if fileInfo.IsDir() {
			header.Name += "/"
		} else {
			header.Method = zip.Deflate
		}

		writer, err := zipWriter.CreateHeader(header)
		if err != nil {
			return err
		}

		if fileInfo.IsDir() {
			return nil
		}

		file, err := os.Open(path)
		if err != nil {
			return err
		}
		defer file.Close()

		_, err = io.Copy(writer, file)
		return err
	})

	if err != nil {
		return nil, fmt.Errorf("zip generation failed: %w", err)
	}

	if err := zipWriter.Close(); err != nil {
		return nil, fmt.Errorf("close zip writer failed: %w", err)
	}

	log.Printf("[DCAR] Successfully packaged %s to ZIP (%d bytes)", appId, buf.Len())
	return buf.Bytes(), nil
}
