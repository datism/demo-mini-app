# IMS Data Channel Golang Microservices Architecture & Developer Guide

Tài liệu này cung cấp cái nhìn toàn diện về kiến trúc, luồng xử lý bản tin, các giao diện chuẩn 3GPP/GSMA, và hướng dẫn chi tiết từng bước cho lập trình viên để **hiểu sâu mã nguồn, mở rộng tính năng (customization) và phát triển dịch vụ mới** trên hệ sinh thái Golang (`go-mf`).

---

## 1. Tổng Quan Kiến Trúc Hệ Thống (System Architecture)

Dự án hiện thực hóa trọn vẹn kiến trúc **3GPP Rel-18/19 & GSMA NG.134 / TS.66 IMS Data Channel**:

```mermaid
graph TD
    subgraph "Signaling Plane (Báo hiệu SIP)"
        UE_A["UE A (Android / dc-test-tool)"] <-->|Gm: SIP UDP/TCP| PCSCF["P-CSCF / S-CSCF"]
        PCSCF <-->|ISC: SIP UDP/TCP| IMSAS["IMS-AS (B2BUA)"]
        IMSAS <-->|Gm/ISC: SIP| PCSCF2["S-CSCF / P-CSCF"]
        PCSCF2 <-->|Gm: SIP| UE_B["UE B (Android / dc-test-tool)"]
    end

    subgraph "Control Plane (SBA HTTP/2 Cleartext - h2c)"
        IMSAS <-->|DC1: 3GPP TS 29.175| DCSF["DCSF (Data Channel Signaling Function)"]
        IMSAS <-->|DC2: 3GPP TS 29.176| MF["MF (Media Function Controller)"]
        DCSF <-->|DC3 / ImsEE: TS 29.522| DCAS["DCAS (App Server Backend)"]
        DCSF <-->|DC5: App Info Query| DCAR["DCAR (App Repository)"]
        IMSAS <-->|N5: TS 29.514| PCF["PCF (Policy Control Function)"]
    end

    subgraph "User Plane (Media & Data Channels)"
        UE_A <-->|RTP (PCMA/H264) + WebRTC BDC/ADC| MF
        MF <-->|RTP (PCMA/H264) + WebRTC BDC/ADC| UE_B
        MF <-->|WebRTC Data Channel (A2P)| DCAS
        DCAR -->|HTTP over BDC Stream 0| UE_A
        DCAR -->|HTTP over BDC Stream 0| UE_B
    end

    subgraph "State & Telemetry"
        IMSAS <--> Redis[("Redis Sentinel Cluster")]
        DCSF <--> Redis
        DCAS <--> Redis
        IMSAS -.->|HEP / PCAP| Homer["HOMER / Grafana Monitoring"]
    end
```

---

## 2. Bản Đồ Các Node Microservices (`cmd/` & `internal/`)

Mã nguồn trong thư mục `go-mf` được tổ chức thành 8 microservices độc lập và 11 thư viện nội bộ:

```text
go-mf/
├── cmd/
│   ├── ims-as/          # 1. IMS Application Server (B2BUA SIP Proxy & Media Orchestrator)
│   ├── dcsf/            # 2. Data Channel Signaling Function (SBA DC1 Server & App Router)
│   ├── dcas/            # 3. Data Channel Application Server (Interactive Games & Smart Menu)
│   ├── dcar/            # 4. Data Channel App Repository (Hosting & Packaging Mini-Apps)
│   ├── mf/              # 5. Media Function (SBA DC2 Server, DTLS/SCTP, Plain RTP Relaying)
│   ├── pcf/             # 6. Policy Control Function (QoS 5QI Mapping Engine)
│   ├── pcap-exporter/   # 7. PCAP Network Traffic Capturing & HEP Exporter
│   └── dc-test-tool/    # 8. Automated E2E Test Suite & Simulation Runner
│
├── internal/
│   ├── imsas/           # Logic B2BUA, SIP parser, SDP Rewriter, Early Media, Fallback
│   ├── dcsf/            # Logic DC1 SBA Router, Event Subscription, App Routing Rules
│   ├── dcas/            # Logic nghiệp vụ Caro Game, Whiteboard Sync, FunCalling, Hotline 198
│   ├── dcar/            # Logic nén Zip on-the-fly, manifest validation
│   ├── mf/              # Logic WebRTC PeerConnection, SCTP Castagnoli CRC32c, Audio Streamer
│   ├── sba/             # Khai báo cấu trúc dữ liệu OpenAPI 3.0 (ProblemDetails, PatchItem...)
│   ├── dctest/          # Framework kiểm thử tự động, điều phối kịch bản E2E
│   ├── uesim/           # Giả lập hoàn chỉnh thiết bị đầu cuối (SIP UA + WebRTC Client)
│   ├── redisclient/     # Module kết nối Redis Sentinel + Master Failover
│   ├── config/          # Đọc cấu hình tập trung (YAML + Env variables)
│   └── logger/          # Structured logger
└── static/              # Tài nguyên tĩnh: video.pcma, video.h264, unreachable.pcma, apps/
```

---

## 3. Chi Tiết Từng Node & Giao Thức Chuẩn

### 3.1. `ims-as` (IMS Application Server)
* **Vị trí**: `cmd/ims-as/main.go`, `internal/imsas/`
* **Vai trò**: Trọng tâm điều phối toàn bộ phiên cuộc gọi và dữ liệu.
* **Quy chuẩn tuân thủ**:
  - **B2BUA SIP Proxy (RFC 3261)**: Lắng nghe SIP UDP/TCP port `5060`, quản lý đối thoại Dialog 2 chân (Leg A & Leg B), sinh Call-ID `b2b_call-uuid-...`.
  - **DC1 SBA Client (3GPP TS 29.175)**: Giao tiếp với DCSF qua HTTP/2 Cleartext (`h2c`), tự động tạo Session Event Subscription (`POST /session-event-subscriptions/`) khi có INVITE, và giải phóng (`DELETE`) khi nhận BYE/CANCEL.
  - **DC2 SBA Client (3GPP TS 29.176)**: Gửi chỉ thị cấp phát Media Context (`POST /nmf-mrm/v1/contexts`) và cập nhật kênh dữ liệu qua HTTP PATCH (`application/json-patch+json`).
  - **Xử lý Early Media & Thuê Bao Không Liên Lạc Được (3GPP Rel-19 TS 24.186)**: Khi nhận SIP `480`/`404`, chặn lại và trả SIP `183 Session Progress` mang header `P-Early-Media: sendrecv` cùng SDP âm báo `unreachable.pcma`, tự động kết thúc bằng SIP `487` sau khi phát xong.
  - **Fallback Bão Lỗi DC1 (TS 24.186 Clause 9.4.4)**: Nếu DC1/DCSF gặp sự cố hoặc timeout, **không làm rớt cuộc gọi**, tự động đặt port Data Channel về `0` và tiếp tục duy trì thoại/video RTP bình thường.

### 3.2. `dcsf` (Data Channel Signaling Function)
* **Vị trí**: `cmd/dcsf/main.go`, `internal/dcsf/`
* **Vai trò**: Bộ não định tuyến ứng dụng Data Channel và quản lý sự kiện mạng.
* **Quy chuẩn tuân thủ**:
  - **SBA HTTP/2 Cleartext (TS 29.175)**: Lắng nghe port `8080`, hỗ trợ cả endpoint có và không có dấu gạch chéo cuối (`/session-event-subscriptions` và `/session-event-subscriptions/`).
  - **Định tuyến ứng dụng (App Routing Table)**: Phân tích attribute `a=3gpp-req-app` trong SDP để ánh xạ `appId` tới đúng `DCAS` backend (P2P hoặc A2P).
  - **Báo lỗi SBA chuẩn 3GPP**: Mọi phản hồi lỗi 4xx/5xx đều trả về `Content-Type: application/problem+json` đóng gói trong struct `sba.ProblemDetails`.

### 3.3. `dcas` (Data Channel Application Server)
* **Vị trí**: `cmd/dcas/main.go`, `internal/dcas/`
* **Vai trò**: Cung cấp logic nghiệp vụ cho các Mini-App tương tác thời gian thực:
  - **Caro Game Engine (`internal/dcas/caro_game.go`)**: Quản lý bàn cờ 9x9, kiểm tra logic thắng 5 ô liên tiếp (ngang, dọc, chéo), xử lý luân phiên lượt đi `X` / `O` và tính điểm.
  - **Whiteboard Sync**: Đồng bộ tọa độ nét vẽ (`start`, `move`, `end`) giữa 2 thiết bị.
  - **Fun Calling Emoji Relay**: Chuyển tiếp hiệu ứng emoji động (`heart`, `like`, `star`) hiển thị bay trên video stream.
  - **Hotline 198 CSKH Smart Menu**: Cung cấp API menu tương tác số hóa cho cuộc gọi tổng đài.
  - **Enterprise Caller ID & Promotion**: Cung cấp banner nhận diện thương hiệu doanh nghiệp.

### 3.4. `dcar` (Data Channel Application Repository)
* **Vị trí**: `cmd/dcar/main.go`, `internal/dcar/`
* **Vai trò**: Lưu trữ và đóng gói Mini-App để phân phối qua **BDC Stream 0**.
* **Đặc tính kỹ thuật**:
  - Hỗ trợ nén Zip on-the-fly từ thư mục mã nguồn `./static/apps/<appId>`.
  - Phục vụ gói cài đặt chuẩn GSMA TuYaApp (`TuYaApp_1.0.9.zip`) và IMS DC Mini-App Demo (`IMS_DC_Mini_app_demo.zip`).
  - Hỗ trợ HTTP caching với `ETag` và phản hồi `304 Not Modified` giúp tiết kiệm băng thông mạng di động.

### 3.5. `mf` (Media Function Server & Media Engine)
* **Vị trí**: `cmd/mf/main.go`, `internal/mf/`
* **Vai trò**: Xử lý toàn bộ User Plane (RTP Audio/Video & WebRTC Data Channels).
* **Đặc tính kỹ thuật**:
  - **SBA DC2 Interface (TS 29.176)**: Cung cấp API `Nmf_MRM` (`/nmf-mrm/v1/contexts`).
  - **Immutability Constraints**: Khóa cứng thông số kết nối (`403 Forbidden` với error code `"MEDIA_CONNECTION_CHANGED"`) khi WebRTC đã kết nối.
  - **User Plane Engine**:
    - Sử dụng chuẩn checksum **CRC32c (Castagnoli)** cho SCTP, loại bỏ hoàn toàn Adler32 cũ.
    - Cấp phát độc lập cổng UDP trên giao diện Mb cho từng phiên BDC và ADC riêng biệt (không dùng BUNDLE chung).
    - Bộ phát âm thanh tự động: Phát file âm thanh mẫu PCMA 8kHz (`video.pcma`, `agent.pcma`, `unreachable.pcma`) và luồng video H.264 NAL units (`video.h264`).

### 3.6. `pcf` (Policy Control Function)
* **Vị trí**: `cmd/pcf/main.go`
* **Vai trò**: Ánh xạ chất lượng dịch vụ QoS Hint từ SDP sang bảng giá trị 5QI tiêu chuẩn (3GPP TS 23.501 Table 5.7.4-1):
  - `conversational` $\rightarrow$ 5QI 1 (GBR 100ms)
  - `dc-low-latency` $\rightarrow$ 5QI 65 (Non-GBR 75ms)
  - `dc-default` $\rightarrow$ 5QI 69 (Non-GBR 60ms)

---

## 4. Hướng Dẫn Tùy Biến & Mở Rộng Tính Năng (Developer Customization Guide)

### 4.1. Hướng Dẫn 1: Thêm một Mini-App / Dịch Vụ Mới từ Đầu

Để thêm một Mini-App mới (ví dụ: `quiz-game` hoặc `video-sharing`):

#### Bước 1: Tạo thư mục giao diện Web trong DCAR
1. Tạo thư mục `go-mf/static/apps/quiz-game/`.
2. Tạo file manifest `app.json`:
   ```json
   {
     "app_id": "quiz-game",
     "name": "Live Call Quiz",
     "version": "1.0.0",
     "main": "index.html",
     "data_channels": [
       { "stream_id": 1000, "protocol": "quiz" }
     ]
   }
   ```
3. Tạo file `index.html` chứa mã nguồn giao diện HTML5/JS/CSS tương tác.

#### Bước 2: Khai báo định tuyến trong DCSF
Mở file `internal/dcsf/dcsf.go`, bổ sung quy tắc định tuyến cho `quiz-game`:
```go
// Trong hàm ResolveApplicationRoute:
case "quiz-game", "quiz":
    return AppRoute{
        AppId:      "quiz-game",
        TargetMode: "P2P", // Hoặc "A2P" nếu có server trung gian
        StreamId:   1000,
        Subprotocol: "quiz",
    }
```

#### Bước 3: Hiện thực logic xử lý dữ liệu trong DCAS (nếu là chế độ A2P hoặc cần Server Relay)
Mở file `internal/dcas/dcas.go`:
```go
// Bổ sung handler xử lý bản tin JSON gửi từ Data Channel:
case "quiz":
    s.handleQuizMessage(sessionID, msgBytes)
```

---

### 4.2. Hướng Dẫn 2: Mở Rộng Thao Tác Nghiệp Vụ Game / Data Channel

Nếu muốn thêm một action mới vào Mini-App Caro (ví dụ: `surrender` - đầu hàng hoặc `undo_move` - đi lại):

1. Mở file `internal/dcas/caro_game.go`.
2. Thêm trường vào struct `CaroMessage`:
   ```go
   type CaroMessage struct {
       Service string `json:"service"`
       Action  string `json:"action"` // "new_game", "move", "surrender"
       // ...
   }
   ```
3. Bổ sung nhánh xử lý trong `ProcessMessage`:
   ```go
   case "surrender":
       game.Winner = opponentOf(playerSymbol)
       game.Status = "game_over"
       return game.BuildStateResponse()
   ```

---

### 4.3. Hướng Dẫn 3: Thêm Kịch Bản Kiểm Thử Mới vào `dc-test-tool`

Để thêm một kịch bản test tự động mới (ví dụ: Scenario 10 - Quiz Game E2E):

1. Mở file cấu hình `deploy/test_scenarios.json`.
2. Thêm kịch bản mới vào mảng `scenarios`:
   ```json
   {
     "name": "Scenario 10: Live Quiz Game Interaction (1001 calling 1002)",
     "caller": { "username": "1001", "password": "password1001" },
     "callee": { "username": "1002", "password": "password1002" },
     "steps": [
       { "action": "wait_register", "timeout_ms": 15000 },
       { "action": "dial", "timeout_ms": 15000 },
       { "action": "request_app", "payload": "quiz-game", "delay_ms": 3000 },
       { "action": "wait_webrtc_connect", "stream_id": 1000, "timeout_ms": 30000 },
       { "action": "send_data", "stream_id": 1000, "payload": "{\"action\":\"answer\",\"q\":1,\"ans\":\"A\"}", "delay_ms": 1000 },
       { "action": "assert_rtp", "delay_ms": 3000 },
       { "action": "close_app", "payload": "quiz-game", "delay_ms": 2000 },
       { "action": "hangup", "payload": "caller", "delay_ms": 2000 }
     ]
   }
   ```
3. Cập nhật ConfigMap lên Kubernetes:
   ```bash
   kubectl create configmap test-scenarios-config --from-file=test_scenarios.json=deploy/test_scenarios.json -n demo-mini-app --dry-run=client -o yaml | kubectl apply -f -
   ```

---

## 5. Hướng Dẫn Biên Dịch, Kiểm Thử & Triển Khai (Build & Run)

### 5.1. Chạy Unit Test cục bộ
Chạy toàn bộ unit test với vendor dependencies (100% offline, không cần internet):
```bash
cd go-mf
go test -mod=vendor -v ./internal/...
```

### 5.2. Đóng gói Docker Image đa tầng
```bash
docker build -t go-mf:local ./go-mf
```

### 5.3. Nạp Image vào cụm Kind Kubernetes & Restart Pods
```bash
kind load docker-image go-mf:local --name kind
kubectl rollout restart deployment/ims-as -n demo-mini-app
kubectl rollout restart deployment/mf -n demo-mini-app
kubectl rollout restart deployment/dcsf -n demo-mini-app
kubectl rollout restart deployment/dcas -n demo-mini-app
kubectl rollout restart deployment/tool-test -n demo-mini-app
```

### 5.4. Theo dõi log kiểm thử tự động trực tiếp
```bash
kubectl logs -n demo-mini-app -l app=tool-test -c tool-test -f
```

### 5.5. Mô Hình Kết Nối Mạng: Tailscale (Test Internet) vs Mạng Lõi Viễn Thông (Production)

> [!IMPORTANT]
> **Quy tắc triển khai mạng**: Tailscale **CHỈ DÀNH CHO THỬ NGHIỆM TỪ XA QUA INTERNET**. Khi triển khai vào mạng **Production (Nhà mạng 5G Core / IMS)** thì **HOÀN TOÀN KHÔNG CẦN VÀ KHÔNG SỬ DỤNG TAILSCALE**.

* **Môi trường Lab / Remote Testing qua Internet**:
  - Dùng để kết nối điện thoại vật lý (Samsung S24) từ xa qua mạng Wi-Fi ngoài hoặc 4G/5G thương mại vào cụm K8s nội bộ.
  - Tailscale đóng vai trò Mesh VPN vượt NAT/CGNAT, gán IP nội bộ mà không cần thuê IP tĩnh hay mở Port Forwarding.
  - Nằm trong thư mục tùy chọn: `deploy/k8s/addons/tailscale/`.
* **Môi trường Production (Nhà mạng Viễn thông thật)**:
  - Thiết bị di động (UE) kết nối trực tiếp với mạng lõi qua sóng vô tuyến (gNodeB / eNodeB) $\rightarrow$ khối **UPF** qua APN `ims`.
  - P-CSCF và Media Function (MF) kết nối trực tiếp với UPF qua các subnet IP nội hạt viễn thông (Giao diện N6/SGi).
  - Luồng dữ liệu SIP (Port 5060) và RTP/WebRTC đi trực tiếp giữa `UE ↔ UPF ↔ P-CSCF / MF` thông qua mạng nội bộ, **hoàn toàn bỏ qua addon Tailscale**.

---


## 6. Tiêu Chuẩn & Quy Tắc Cần Tuân Thủ Khi Sửa Code (Code of Conduct)

1. **Protocol Security Policy**:
   - Giao thức **SIP**: Luôn chạy dạng UDP/TCP thường không mã hóa (no TLS/SIPS).
   - Giao thức **HTTP/2**: Luôn chạy dạng HTTP/2 Cleartext (`h2c`, no TLS/HTTPS).
   - Giao thức **RTP**: Luôn chạy Plain RTP qua UDP (no SRTP), Audio dùng **PCMA**, Video dùng **H.264**.
2. **JSEP / SDP Isolation Guardrails**:
   - Tuyệt đối không thêm media audio/video vào instance `adcPC`.
   - Luôn sử dụng hàm `MergeAdcOffer` khi gửi SIP re-INVITE và `SplitAdcAnswer` khi nhận SIP 200 OK.
3. **Persistent BDC**:
   - Không được phép xóa hoặc đặt port BDC về 0 trong suốt cuộc gọi. Chỉ đặt port = 0 đối với dòng `m=application` của ADC khi thoát Mini-App.
