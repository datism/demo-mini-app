# CONTEXT.md — Universal Project Handoff for ALL AI Models

> **Mục đích**: File duy nhất mọi AI model (Roo Code, Cursor, Windsurf, Copilot, Cline, Codex...) đọc khi bắt đầu phiên làm việc.
> **Cập nhật**: Model hiện tại PHẢI cập nhật file này trước khi kết thúc phiên.

---

## 1. Dự án: IMS Data Channel (IMS DC) PoC

Hệ thống **IMS (IP Multimedia Subsystem)** triển khai Data Channel theo chuẩn 3GPP/GSMA, chạy trên K8s offline (air-gapped).

### Kiến trúc 3 lớp

| Lớp | Thư mục | Ngôn ngữ | Vai trò |
|------|---------|----------|--------|
| **SIP Core** | `erlang-sip/` | Erlang | P-CSCF, I-CSCF, S-CSCF (ETS tables, HEPv3) |
| **Go Backend** | `go-mf/` | Go 1.21 | IMS-AS, DCSF, DCAR, DCAS, PCF, MF |
| **Android Client** | `android-client/` | Kotlin/Java | TCP SIP, WebRTC dual PC, GSMA bridge |

### Luồng dữ liệu chính
```
Android Client ←→ P-CSCF (Erlang) ←→ IMS-AS (Go B2BUA) ←→ MF (WebRTC relay)
                                         ↕ DC1 (h2c)         ↕ DC2 (h2c)
                                       DCSF (session)      DCAS (game logic)
```

### Giao thức bắt buộc (TUYỆT ĐỐI tuân thủ)
- **SIP**: Plain TCP/UDP, KHÔNG TLS/SIPS
- **HTTP/2**: h2c cleartext, KHÔNG HTTPS
- **RTP**: PCMA audio + H264 video, plain UDP, KHÔNG SRTP
- **Data Channel**: 1-1 SCTP, KHÔNG dc-mux multiplexing

---

## 2. Trạng thái hiện tại (Cập nhật: 2026-08-14 — Tag v15.0.1)

### Đã hoàn thành 100% (Verified trên 2 máy Samsung S24 thật)
- [x] **Đăng ký SIP & Cuộc gọi MMTel**: SIP TCP keep-alive, PCMA audio (PT 8) và H.264 video (PT 97) 2 chiều hoàn hảo trên 2 máy Samsung S24 thật.
- [x] **Truyền nhận Video 2 chiều (H.264 RTP)**: MediaCodec AVC Encoder & Decoder render trực tiếp lên SurfaceView với `SO_REUSEADDR` UDP socket và dynamic Surface callback.
- [x] **Mini-App Fun-Calling (Thả tim tương tác)**: Hoạt động 2 chiều cả khi Caller hoặc Callee mở trước (Sửa lỗi B2BUA Response routing & Passive DTLS role).
- [x] **Giao diện nhận cuộc gọi (Incoming Call UI)**: Tinh gọn 2 nút Reject/Answer, loại bỏ hoàn toàn chữ thừa và khung video đen khi chuông reo.
- [x] **Mini-App Caro game & Operator Menu**: Đồng bộ trạng thái bàn cờ qua MDC2 TCP frame và hiển thị đầy đủ font chữ.
- [x] **Video Hoạt hình Hài hước Full HD 1080p Tổng đài 198**: Nâng cấp video hoạt hình Full HD 1080p và HD 720p ngẫu nhiên, tối ưu giao diện modal khớp chuẩn màn hình điện thoại Samsung Galaxy S24.

### Bug đã fix (đã deploy & test trực tiếp trên thiết bị)
| Bug | File | Trạng thái |
|-----|------|-----------|
| Callee-initiated Fun-Calling ADC | `b2bua_response.go`, `sba_handler.go` | ✅ Fixed & Deployed |
| 2-Way Video RTP Socket EADDRINUSE & Surface Binding | `RtpMediaManager.kt`, `CallActivity.kt` | ✅ Fixed & Deployed |
| Incoming Call Overlapping Buttons & Black Video Box | `activity_call.xml`, `CallActivity.kt` | ✅ Fixed & Deployed |
| Caro Game State Sync & Font Mojibake | `caro/index.html`, `operator-menu/index.html` | ✅ Fixed & Deployed |
| Ringing Timeout BDC ICE Disconnected (Caller 8.2s timeout) | `media_function.go`, `proxy_handler.go`, `sys_config.json` | ✅ Fixed & Deployed |
| Tool-test Stale Registration Overwrite | `k8s-deployment.yaml` (scaled down) | ✅ Resolved |

### Bug đã biết (low priority)
- S-CSCF terminating iFC side effect: ADC release triggers unnecessary iFC

---

## 3. Hạ tầng & Thiết bị

### Cụm K8s (namespace: `demo-mini-app`)
| Component | IP (Macvlan net1) | Port |
|-----------|-------------------|------|
| P-CSCF (Erlang) | `10.202.0.230` | `5060` TCP/UDP |
| MF (Go) | `10.202.0.229` | `40000-40010` UDP |
| Tailscale Router | **`100.93.28.107`** | DNAT → P-CSCF + MF |

### Thiết bị Android Test (2× Samsung Galaxy S24)
| Vai trò | ADB Serial | Tailscale IP | User |
|---------|-----------|-------------|------|
| Caller | `R5CY54VL09X` | `100.127.196.108` | 1001 |
| Callee | `R5CY54VL13Y` | `100.119.80.116` | 1002 |

### Build Environment
- **JDK 17**: `C:\Users\lamnt\.jdks\jdk-17.0.10+7`
- **APK build**: `cd android-client && gradlew.bat assembleDebug`
- **Docker build**: `cd go-mf && docker build -t go-mf:local .`
- **Go test**: `cd go-mf && go test ./...`

---

## 4. Quy tắc làm việc cho MỌI model

### Khi bắt đầu phiên mới:
1. **Đọc file này** (`CONTEXT.md`) — nắm kiến trúc, trạng thái, thiết bị
2. **Đọc** `.antigravity/MEMORY.md` — chi tiết bug, file đã sửa, lịch sử
3. **Đọc** `.agents/HANDOFF.md` — topology, lệnh test nhanh
4. **Không cần mô tả lại** — mọi thông tin đã có trong 3 file trên

### Khi kết thúc phiên:
1. **Cập nhật `MEMORY.md`** — ghi lại những gì đã làm, file đã sửa, bug mới phát hiện
2. **Cập nhật `CONTEXT.md`** (file này) — section 2 "Trạng thái hiện tại"
3. **Cập nhật `HANDOFF.md`** — nếu topology/IP thay đổi

### Quy tắc code:
- **KHÔNG hardcode** IP/port — luôn đọc từ config hoặc network interface
- **KHÔNG tự ý git commit/push** — chỉ làm khi được yêu cầu
- **KHÔNG placeholder** — code phải hoàn chỉnh, chạy được
- **Giữ nguyên** logs, comments, error-handling hiện có

---

## 5. Cấu trúc thư mục quan trọng

```
demo-mini-app/
├── CONTEXT.md              ← FILE NÀY (universal entry point)
├── .roorules               ← Rules cho Roo Code (DeepSeek)
├── .agents/
│   ├── AGENTS.md           ← Rules chi tiết 3GPP/GSMA (156 dòng)
│   └── HANDOFF.md          ← Topology, lệnh test nhanh
├── .antigravity/
│   └── MEMORY.md           ← Trạng thái + lịch sử bug/fix
├── android-client/         ← Android App (Kotlin)
│   └── app/src/main/
│       ├── java/.../ui/    ← Activities (CallActivity, MainActivity, MiniAppManager)
│       ├── java/.../webrtc/← WebRtcManager, DataChannelManager, ImsDcSdk
│       ├── java/.../sip/   ← SipManager, SdpHelper
│       ├── java/.../media/ ← RtpMediaManager
│       └── assets/         ← Mini-app HTML (caro, operator-menu, ...)
├── go-mf/                  ← Go Backend
│   ├── internal/
│   │   ├── mf/             ← Media Function (media_function.go, sdp_utils.go, ...)
│   │   ├── imsas/          ← IMS-AS B2BUA (b2bua.go, b2bua_invite.go, ...)
│   │   ├── dcsf/           ← DC1 Session Event Control
│   │   ├── dcas/           ← DC Application Server (game logic)
│   │   ├── dcar/           ← DC Application Repository
│   │   └── sba/            ← SBA type definitions
│   └── static/apps/        ← Mini-app HTML (server copies)
├── erlang-sip/             ← SIP Core (Erlang/OTP)
├── deploy/                 ← Docker Compose + K8s manifests
│   └── config/sys_config.json
└── plans/                  ← Implementation plans + audit reports
```

---

## 6. Câu lệnh thường dùng

```bash
# === Android ===
# Build APK
cd android-client && gradlew.bat assembleDebug

# Cài APK lên cả 2 máy
adb -s R5CY54VL09X install -r app/build/outputs/apk/debug/app-debug.apk
adb -s R5CY54VL13Y install -r app/build/outputs/apk/debug/app-debug.apk

# Khởi động app + đăng ký SIP
adb -s R5CY54VL09X shell am start -S -n com.demo.imsdc/.ui.MainActivity --es sip_server 100.93.28.107 --es username 1001 --es remote_user 1002 --es local_ip 100.127.196.108
adb -s R5CY54VL13Y shell am start -S -n com.demo.imsdc/.ui.MainActivity --es sip_server 100.93.28.107 --es username 1002 --es remote_user 1001 --es local_ip 100.119.80.116

# Gọi + trả lời
adb -s R5CY54VL09X shell am broadcast -a com.demo.imsdc.CALL --es target 1002
adb -s R5CY54VL13Y shell am broadcast -a com.demo.imsdc.ANSWER

# === K8s ===
# IP Tailscale router hiện tại
kubectl exec -n demo-mini-app $(kubectl get pods -n demo-mini-app -l app=tailscale -o jsonpath='{.items[0].metadata.name}') -- tailscale ip -4

# Log các service
kubectl logs -n demo-mini-app deployment/dcas --tail=100
kubectl logs -n demo-mini-app deployment/ims-as --tail=100
kubectl logs -n demo-mini-app deployment/mf --tail=100

# === Docker ===
# Build Go image
cd go-mf && docker build -t go-mf:local .
# Load vào Kind cluster
kind load docker-image go-mf:local --name ims-dc-cluster

# === Go Tests ===
cd go-mf && go test ./...
```

### 2.10. Phiên bản v15.0.1 (2026-08-14): Chuẩn hóa Biểu tượng Ứng dụng Samsung One UI 6.1 & BDC Caching
* **Hoàn thiện Icon Samsung Galaxy S24 One UI 6.1**:
  - Trích xuất hình dạng ống nghe chữ `C` trực tiếp từ thiết bị thật Galaxy S24, xử lý đường cong mượt mà bằng Gaussian Circular Smoothing trên không gian 2048x2048.
  - Cập nhật trọn bộ icon mipmap (`mdpi` 48px, `hdpi` 72px, `xhdpi` 96px, `xxhdpi` 144px, `xxxhdpi` 192px, `drawable` 512px) và Adaptive Icon (`ic_launcher_phone.xml`).
* **Xác thực Cơ chế BDC Caching & Conditional 304 Not Modified**:
  - Xác thực quá trình tải BDC tải trọn vẹn 292 KB (19 chunks SCTP) từ máy chủ DCSF xuống cache bộ nhớ đĩa cục bộ.
  - Các cuộc gọi tiếp theo mở app tức thì (< 50ms) từ Local Cache và kiểm tra phiên bản qua `If-None-Match` trả về `304 Not Modified`.



## 2026-10-30 — Photo/video sharing source changes (not deployed)
- Corrected viewer geometry, persistent full-file references, EXIF rotation/reflection, high-DPI drawing and synchronized navigation/ink/clear. Server and APK pages/menu are synchronized; PhotoShare manifest is 1.1.0.
- Added native asynchronous URI import and photo/video streaming (wire v2 full IDs, disk reception, SHA-256, <=50 MiB, retry/backpressure), video MIME/range serving and muted inline playback. Both UEs need the updated APK; video controls are local.
- Added picker lifecycle recovery: avoid explicit camera pause for an active picker, force capture recovery on return, restore call audio mode/playback, rebind decoder surface and request keyframes. Actual freeze recovery is NOT verified on devices.
- Verification: 34 standalone Kotlin assertions and 36 two-UE browser assertions passed; git diff --check passed. Full Android Gradle compilation blocked offline by uncached org.gradle.kotlin.kotlin-dsl plugin 4.1.2. No APK produced, no device/cluster deployment, no commit/push.
- Test entry points and remaining physical-device checklist: go-mf/test/photo_share/README.md.
