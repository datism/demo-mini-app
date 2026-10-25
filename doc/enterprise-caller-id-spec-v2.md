# TÀI LIỆU KHẢO SÁT, THIẾT KẾ & HƯỚNG DẪN DEMO "ENTERPRISE CALLER ID, ADS & INTERACTIVE QR CODE" (A2P)
## Phiên bản: v2.0 (Cập nhật toàn diện kiến trúc mạng lõi Hop-by-Hop và Kịch bản Demo thực tế)
## Dành cho AI Code Generator (Antigravity IDE)

Tài liệu này cung cấp thiết kế kiến trúc chi tiết, đặc tả luồng báo hiệu (Call Flow) mức mạng lõi, quy ước tham số bản tin (SIP/SDP/REST APIs), hướng dẫn cấu hình và 3 Prompt chuyên biệt để nạp vào AI thực hiện sinh mã nguồn (Erlang/Go/JavaScript) cho dự án IMS Data Channel PoC.

---

## I. MÔ TẢ KỊCH BẢN DỊCH VỤ MỞ RỘNG (A2P CALLER ID, ADS & QR CODE)

1. **Khái niệm**: Doanh nghiệp (Enterprise/Brand) sử dụng hệ thống gọi tự động (Auto-dialer) thực hiện cuộc gọi tiếp thị hoặc chăm sóc khách hàng (A2P) tới người dùng thông thường (UE). Trong giai đoạn điện thoại của khách hàng đang đổ chuông (Ringing), hệ thống tự động đẩy dữ liệu định danh thương hiệu và nội dung quảng cáo tương tác trực tiếp lên màn hình cuộc gọi.
2. **Các thành phần nội dung hiển thị trên WebView**:
   * **Logo Doanh nghiệp đã xác thực**: Kèm huy hiệu màu xanh lục **"✔ Certified"** nhằm khẳng định uy tín, giúp chống lại cuộc gọi rác hoặc cuộc gọi giả mạo thương hiệu.
   * **Thông điệp/Mục đích cuộc gọi (Call Intent)**: Hiển thị ngắn gọn lý do cuộc gọi (Ví dụ: "Nhắc lịch hẹn khám bệnh", "Chương trình tri ân khách hàng").
   * **Quảng cáo động (Promo Banner/Text)**: Đưa ra thông tin chương trình khuyến mãi hấp dẫn (Ví dụ: "Cơ hội trúng thẻ nạp 500.000đ khi quét mã hoặc click nhận ngay").
   * **Mã QR Code tương tác**: QR Code được tạo và hiển thị trực tiếp trên màn hình cuộc gọi của khách hàng. Người dùng có thể:
     * Sử dụng thiết bị khác để quét trực tiếp mã QR Code đang hiển thị trên màn hình cuộc gọi của chiếc điện thoại Samsung Galaxy S24.
     * Chạm trực tiếp vào nút hành động để chuyển hướng liên kết nhận quà ngoài cuộc gọi.
   * **Các nút tương tác nhanh (Call-to-Action)**: "Bỏ qua", "Nhận ngay" (Web-to-Action), hoặc "Nghe cuộc gọi" để tiếp tục kết nối đàm thoại với nhân viên tổng đài.

---

## II. THIẾT KẾ KIẾN TRÚC MẠNG LÕI HOP-BY-HOP CHUẨN 3GPP & GSMA

Để hệ thống hoạt động ổn định và tuân thủ các quy định viễn thông, luồng báo hiệu và đàm phán kênh dữ liệu của cuộc gọi A2P re-INVITE bắt buộc phải đi qua đầy đủ các nút mạng trung gian (P-CSCF, S-CSCF) để định tuyến và áp dụng chất lượng dịch vụ QoS ưu tiên (PCF) thay vì luồng đơn giản hóa trực tiếp AS <-> UE.

### 1. Sơ đồ Luồng Báo hiệu & Phương tiện (Signaling & Media Path)

```
                                        +---------------------------------------+
                                        |  Hệ thống Doanh nghiệp (Enterprise)   |
                                        |  - DC AS (Enterprise App Server)      |
                                        +---------------------------------------+
                                           │              ▲
                       (Nnef_ImsSession)   │              │ MDC2 (SCTP/DTLS)
                       Update Request      │              │ UDP Proxy
                                           ▼              ▼
                                        +---------------------------------------+
                                        |    NEF (Network Exposure Function)    |
                                        +---------------------------------------+
                                           │
                        (Nimsas_Session)   │ HTTP/2 Cleartext
                        Update Request     │ (h2c)
                                           ▼
                                        +---------------------------------------+
                     ┌─────────────────>|      IMS-AS (Application Server)      |
                     │                  +---------------------------------------+
                     │                     │                     │
                     │                     │ (1) SIP re-INVITE     │ DC2 (HTTP/2 h2c)
                     │                     ▼                       ▼
                     │                  +---------------------------------------+
                     │                  |            S-CSCF (Serving)           |
                     │                  +---------------------------------------+
                     │                     │
                     │                     │ (2) SIP re-INVITE
                     │                     ▼
                     │                  +---------------------------------------+
  DC1 (HTTP/2 h2c)   │                  |             P-CSCF (Proxy)            |
                     │                  +---------------------------------------+
                     │                     │                     │
                     │                     │ (3) SIP re-INVITE     │ Npcf (Rx/N5 Interface)
                     │                     │     (Gm Interface)    ▼
                     │                     │                  +-----------------+
                     │                     │                  |       PCF       |
                     │                     │                  | (Policy Control)|
                     │                     │                  +-----------------+
                     │                     ▼                           │
                     │                  +---------------------------------------+
                     │                  |          User Equipment (UE)          |
                     │                  +---------------------------------------+
                     │                     │                     ▲
                     │                     │                     │
                     │                     └─────────────────────┘
                     │                       Bootstrap Data Channel (BDC)
                     │                       & Application Data Channel (ADC)
                     │                       (SCTP/DTLS bọc UDP)
                     ▼
             +------------------------------------------------------------------+
             |             DCSF (Data Channel Signaling Function)               |
             |             - Brain ngoài luồng SIP (Off-SIP-Path)               |
             +------------------------------------------------------------------+
```

### 2. Vai trò của từng thực thể mạng trong luồng re-INVITE:

*   **S-CSCF (Serving-CSCF)**: Điểm neo dịch vụ và quản lý trạng thái đăng ký của thuê bao. Khi IMS-AS (chạy ở chế độ B2BUA) khởi tạo bản tin SIP re-INVITE để thiết lập kênh ADC Stream 1000, nó bắt buộc phải gửi qua giao diện **ISC/Nimsas** tới S-CSCF. S-CSCF dựa trên thông tin định tuyến đã lưu để chuyển tiếp bản tin tới P-CSCF đang phục vụ thiết bị của người dùng.
*   **P-CSCF (Proxy-CSCF)**: Cửa ngõ SIP đầu tiên tiếp xúc với thiết bị người dùng (UE) qua kết nối bảo mật Gm (IPsec/TLS). P-CSCF tiếp nhận bản tin re-INVITE từ S-CSCF, đồng thời thực hiện nhiệm vụ **Quản lý QoS (Chất lượng dịch vụ)**:
    *   Phân tích các dòng phương tiện `m=application` trong SDP Offer.
    *   Tương tác với **PCF (Policy Control Function)** qua giao diện **Npcf/Rx** để yêu cầu nhà mạng cấp phát luồng dữ liệu ưu tiên riêng biệt (QoS Flow cho Data Channel, thường là QCI-1/2 hoặc các profile dữ liệu tương tác 5G đặc thù) nhằm đảm bảo kênh dữ liệu không bị nghẽn trong quá trình đàm thoại.
    *   Sau khi nhận phản hồi từ PCF, P-CSCF chuyển tiếp SIP re-INVITE xuống UE.
*   **DCSF (Data Channel Signaling Function)**: Đóng vai trò là "bộ não" điều khiển logic dịch vụ. DCSF nằm hoàn toàn **ngoài luồng SIP (Off-SIP-Path)**. Khi IMS-AS nhận yêu cầu cập nhật phiên, nó sẽ gửi sự kiện HTTP/2 qua **giao diện DC1** sang DCSF. DCSF phân tích, tìm kiếm gói Mini-App tương ứng và trả về chỉ thị điều khiển phương tiện (Media Instruction) cho IMS-AS để định dạng SDP Offer.
*   **Media Function (MF)**: Điểm neo và xử lý luồng truyền tải thực tế (User Plane). Toàn bộ dữ liệu WebRTC (SCTP/DTLS/UDP) từ thiết bị người dùng không đi trực tiếp ra internet mà bắt buộc phải kết cuối (terminate) tại MF để đảm bảo an toàn bảo mật mạng viễn thông. MF sau đó đóng vai trò là **UDP Proxy** để chuyển tiếp luồng dữ liệu này sang mạng ngoài của Doanh nghiệp (DC AS) qua cổng giao diện **MDC2**.

---

## III. QUY ƯỚC CÁC THAM SỐ BẢN TIN QUAN TRỌNG CHUẨN HÓA

### 1. SIP Routing Headers (Mạng Core/AS định tuyến hop-by-hop)
Bản tin re-INVITE do IMS-AS khởi tạo bắt buộc phải chứa các thông tin định tuyến chuẩn xác:
*   **Route Header**: Trỏ tới địa chỉ S-CSCF phục vụ phiên cuộc gọi:
    ```http
    Route: <sip:s-cscf.viettel.3gppnetwork.org:5060;lr>
    ```
*   **Contact Header**: Phải khai báo thuộc tính đặc trưng hỗ trợ Data Channel theo chuẩn 3GPP/GSMA:
    ```http
    Contact: <sip:ims-as@10.0.0.10:5080>;+sip.app-subtype="webrtc-datachannel"
    ```

### 2. SDP Upgrade Offer (Bổ sung kênh ADC Stream 1000 cho Enterprise ID & Ads)
Bản tin SIP re-INVITE mang cấu hình SDP được bổ sung thêm dòng kênh ứng dụng đặc tả cho dịch vụ hiển thị doanh nghiệp:
```sdp
m=application 52718 UDP/DTLS/SCTP webrtc-datachannel
c=IN IP4 10.0.0.150  # Địa chỉ IP của Media Function (MF) đóng vai trò neo User Plane
a=sctp-port:5000
a=setup:actpass
a=fingerprint:SHA-256 4A:AD:B9:B1:3F:82:18:3B:54:02:12:DF:3E:5D:49:6B:19:E5:7C:AB
a=tls-id:abc3de65cddef001be82
a=dcmap:1000 subprotocol="json" ordered=true label="enterprise-callerid"
a=3gpp-req-app:"enterprise-callerid";1000-Server
```

---

## IV. KỊCH BẢN KIỂM THỬ & DEMO CHI TIẾT TRÊN HỆ THỐNG POC HIỆN TẠI

Để chứng minh đầy đủ tính năng hiển thị Logo, Quảng cáo, và mã QR Code trên môi trường lab thực tế của bạn, hãy áp dụng 3 kịch bản demo chi tiết sau:

### Kịch bản 1: Mock-A2P bằng dịch vụ Nhạc chờ Video (VRBT) - Tài khoản 1001 gọi 1002
Vì tài khoản **1002** trong cơ sở dữ liệu của bạn đã được cấu hình kích hoạt **Nhạc chờ hình ảnh (VRBT)** mặc định, đây là cách hoàn hảo nhất để mock demo cuộc gọi quảng cáo tự động đổ chuông:
1.  **Thiết lập thiết bị**: Sử dụng 2 điện thoại Samsung Galaxy S24 cài đặt app IMS DC và đăng ký SIP thành công:
    *   Điện thoại A: Tài khoản **1001** (Doanh nghiệp).
    *   Điện thoại B: Tài khoản **1002** (Khách hàng thông thường).
2.  **Thực hiện cuộc gọi**: Điện thoại A (1001) gọi điện cho điện thoại B (1002).
3.  **Quá trình xử lý mạng**:
    *   Kênh **Bootstrap Data Channel (BDC)** thiết lập thành công.
    *   Điện thoại B nhận diện cuộc gọi từ Doanh nghiệp, tự động kết nối tới máy chủ `dcar` để tải gói ứng dụng tương tác **`enterprise-ad.zip`** (đã đóng gói sẵn cấu trúc HTML, CSS, Logo, QR Code và nút bấm tương tác).
    *   Android Client đọc byte đầu tiên kiểm tra Magic Bytes `50 4B 03 04` của tệp ZIP, giải nén và nạp giao diện vào WebView an toàn.
4.  **Trải nghiệm Demo**:
    *   On điện thoại B đang đổ chuông, luồng video quảng cáo nền được phát đồng thời cùng giao diện tương tác WebView mượt mà.
    *   **Quét thử mã QR**: Người xem có thể dùng điện thoại cá nhân quét trực tiếp mã QR Code hiển thị trên WebView để truy cập liên kết quà tặng thực tế.
    *   **Tương tác chạm**: Nhấp vào nút *"Nhận ngay"* trên màn hình cuộc gọi, ứng dụng sẽ chuyển hướng mở trang khuyến mãi trên trình duyệt ngoài một cách bảo mật.

### Kịch bản 2: Demo tính năng Hotline CSKH thông minh 198 (Mô hình P2A UDP_PROXY)
Chứng minh khả năng tương tác trực tiếp qua kênh Application Data Channel (Stream ID 1000):
1.  Từ điện thoại Samsung Galaxy S24, bấm gọi đầu số **198**.
2.  **IMS-AS** đóng vai trò UAS đích tự động bắt máy gửi bản tin SIP 200 OK.
3.  Hệ thống tự động tải gói ứng dụng `smart-menu` và kích hoạt WebView tương tác thay thế cho menu IVR bấm phím truyền thống.
4.  Thực hiện chạm nút **"Gặp tổng đài viên"** để kích hoạt sóng âm động và nghe lời chào tiếng Việt (Web Speech TTS) được truyền tải thời gian thực. Chạm **"Xem video"** để mở trình phát HTML5 phát trực tiếp video từ server qua kênh ADC.

### Kịch bản 3: Giả lập tự động E2E bằng dòng lệnh CLI (`dc-test-tool`)
Dành cho trình diễn kỹ thuật chuyên sâu về giao thức:
1.  Chạy container hoặc truy cập thư mục của `dc-test-tool`.
2.  Thực thi lệnh giả lập cuộc gọi Scenario 1 (Whiteboard & Emoji - tài khoản 1003 gọi 1004):
    ```bash
    ./dc-test-tool -scenario 1
    ```
3.  Màn hình console hiển thị toàn bộ tiến trình bắt tay ICE, DTLS, SCTP Association và mô phỏng truyền nhận các gói tin JSON-RPC qua Stream 1000/1001.

### Kịch bản 4: Hướng dẫn Debug và Phân tích bản tin (Wireshark & Grafana)
Để chứng minh cho đối tác xem các luồng báo hiệu thực tế:
1.  **Bắt gói tin**: Gọi API tại endpoint `/pcap` của hệ thống để tải xuống file PCAP ghi gói tin báo hiệu của phiên cuộc gọi demo.
2.  **Cấu hình Wireshark**: Mở file PCAP, vào mục *Analyze -> Decode As...* -> Thêm cấu hình cho cổng **8080 (DCSF)** và **8081 (Media Function - go-mf)** giải mã dưới dạng **HTTP2**.
3.  **Giám sát Hệ thống**: Truy cập Grafana Dashboard (`http://localhost:3000`, tài khoản `admin/admin`) hiển thị trực quan đồ thị hiệu năng, tài nguyên RAM/CPU của hệ thống PoC trong quá trình thực hiện cuộc gọi.

---

## V. CẬP NHẬT BỘ 3 PROMPTS SẢN SINH MÃ NGUỒN CHO AI GENERATOR

Sao chép toàn bộ các prompt dưới đây nạp vào AI của bạn để sinh mã nguồn chính xác nhất:

---

### PROMPT 1: CODE CHO PHÂN HỆ IMS-AS (NGÔN NGỮ GO)
```text
Role: Bạn là Chuyên gia Lập trình Hệ thống Viễn thông 5G New Calling.
Task: Viết mã nguồn Go cho phân hệ "ims-as" để xử lý luồng báo hiệu SIP B2BUA và điều phối giao diện DC1/DC2 phục vụ kịch bản "Enterprise Caller ID & Intent" (A2P).

Yêu cầu Kỹ thuật:
1. SIP State Machine (Go):
   - Đọc và xử lý bản tin SIP INVITE và SIP re-INVITE từ Core network.
   - Nhận diện khi bản tin SDP chứa thuộc tính `a=3gpp-req-app` với app-id là "enterprise-callerid" và endpoint là "Server" trên Stream 1000.
   - Trích xuất các thuộc tính DTLS/SCTP: `a=tls-id`, `a=sctp-port`, `a=fingerprint`, `a=setup`.
   - Bổ sung header "Route" trỏ tới S-CSCF (`Route: <sip:s-cscf.viettel.3gppnetwork.org:5060;lr>`) và header "Contact" chứa thuộc tính `+sip.app-subtype="webrtc-datachannel"` trong SIP re-INVITE gửi đi.

2. Giao diện REST API HTTP/2 (Cleartext - h2c):
   - Tích hợp cổng DC1 (IMS-AS <-> DCSF): Khi nhận cuộc gọi A2P từ Enterprise, gửi HTTP POST tới DCSF qua endpoint `http://dcsf:8080/nimsas-sec/v1/session-event-subscriptions` để thông báo sự kiện `ExternalSessionUpdateEvent`.
   - Tích hợp cổng DC2 (IMS-AS <-> Media Function): Gửi HTTP POST yêu cầu cấu hình tài nguyên Media Function (MF) thông qua endpoint `http://go-mf:8081/nimsas-mc/v1/call-sessions/{sessionId}/media-instruction`.
   - Cấu hình body JSON cho DC2 chứa thông tin định tuyến MDC2 (Relay UDP_PROXY tới Enterprise Server IP "192.168.1.100" trên Port 9001).

3. Độc lập NAT và Quản lý Bộ nhớ:
   - Sử dụng sync.RWMutex để quản lý Thread-safety cho Call Context map lưu trữ thông tin SDP và trạng thái phiên cuộc gọi.
   - Tự động kích hoạt cơ chế giải phóng tài nguyên gửi bản tin HTTP DELETE tới DC1 và DC2 khi phiên cuộc gọi nhận bản tin SIP BYE hoặc CANCEL.

Đầu ra yêu cầu: Mã nguồn Golang hoàn chỉnh, sạch, chú thích tiếng Việt rõ ràng, xử lý lỗi chặt chẽ (defensive coding). Không sử dụng các thư viện ngoài phức tạp trừ thư viện chuẩn của Go và golang.org/x/net/http2.
```

---

### PROMPT 2: CODE CHO PHÂN HỆ MEDIA FUNCTION - MF (NGÔN NGỮ GO)
```text
Role: Bạn là Chuyên gia Lập trình User Plane Viễn thông và WebRTC.
Task: Viết mã nguồn Go cho phân hệ "go-mf" (Media Function) để terminate DTLS/SCTP kết nối từ thiết bị di động (UE) và đóng vai trò "UDP Proxy" chuyển tiếp dữ liệu đến Enterprise DC-AS.

Yêu cầu Kỹ thuật:
1. Kết cuối DTLS/SCTP (Sử dụng thư viện pion/dtls và pion/sctp):
   - Thực hiện bắt tay mTLS (DTLS 1.2) với UE dựa trên Fingerprint trao đổi qua SIP SDP. Đóng vai trò DTLS Server hoặc Client tùy thuộc vào thuộc tính `a=setup` (active/passive).
   - Thiết lập SCTP Association bọc trong DTLS qua luồng UDP.
   - Lắng nghe và mở Stream ID 1000 (Application Data Channel - ADC) dành riêng cho dịch vụ "enterprise-callerid".

2. Cơ chế UDP Proxy (MDC2):
   - Khi nhận được dữ liệu (JSON) từ UE trên Stream ID 1000, trích xuất payload và chuyển tiếp nguyên trạng (Forward) qua kết nối UDP Socket thô tới địa chỉ IP DC-AS đích ("192.168.1.100:9001").
   - Ngược lại, lắng nghe UDP Socket trên Port nội bộ dành riêng cho MDC2. Khi nhận gói tin JSON-RPC từ Enterprise DC-AS, đóng gói vào gói tin SCTP và gửi ngược lại UE qua Stream ID 1000.

3. Giải phóng Tài nguyên & Độc lập NAT & Tích hợp QoS:
   - Viết hàm cleanup giải phóng toàn bộ UDP Port, đóng kết nối SCTP/DTLS khi nhận chỉ thị HTTP DELETE từ IMS-AS hoặc khi phát hiện mất liên lạc kết nối ICE (ICE Connection Failed/Disconnected).

Đầu ra yêu cầu: Mã nguồn Golang hoàn chỉnh, tích hợp tốt với kiến trúc pion WebRTC, chú thích tiếng Việt chi tiết từng bước xử lý byte.
```

---

### PROMPT 3: CODE CHO MINI-APP TRÊN THIẾT BỊ DI ĐỘNG (WEBVIEW - HTML5/CSS/JAVASCRIPT)
```text
Role: Bạn là Kỹ sư Phát triển Ứng dụng Di động nhúng WebView (WebRTC/TS.66/GSMA).
Task: Viết mã nguồn HTML5/CSS/JavaScript hoàn chỉnh cho tệp `index.html` của Mini-App "enterprise-callerid" chạy trong WebView của Dialer di động Samsung Galaxy S24 nhằm hiển thị Thẻ doanh nghiệp xác thực (Certified Business Card), Banner quảng cáo, Mã QR Code và các nút tương tác.

Yêu cầu Kỹ thuật:
1. Tích hợp WebRTC Data Channel (Chuẩn GSMA TS.66):
   - Sử dụng đối tượng `RTCPeerConnection` và `RTCDataChannel` hệ thống có sẵn của thiết bị di động.
   - Thiết lập bắt tay và lắng nghe sự kiện trên Stream ID 1000:
     ```javascript
     const pc = new RTCPeerConnection(config);
     const dataChannel = pc.createDataChannel("enterprise-callerid", {
         id: 1000,
         negotiated: true,
         ordered: true
     });
     ```

2. Xử lý Dữ liệu JSON-RPC nhận về từ Stream 1000:
   - Viết hàm lắng nghe `dataChannel.onmessage` để phân tích cú pháp chuỗi JSON nhận được. Định dạng JSON mẫu mong đợi:
     ```json
     {
       "enterprise_name": "VIETTEL TELECOM",
       "verification_status": "certified",
       "logo_url": "assets/logo.png",
       "call_intent": "Cuộc gọi xác nhận chương trình tri ân khách hàng",
       "promo_text": "QUÉT MÃ TRÚNG THẺ 500K!",
       "qr_url": "assets/qr-coupon.png"
     }
     ```

3. Giao diện Đồ họa responsive đẹp mắt (HTML/CSS):
   - Thiết kế giao diện dọc tối ưu cho thiết bị di động (như Samsung Galaxy S24).
   - Nền WebView trong suốt/mờ (`rgba(0,0,0,0.4)`) để có thể quan sát luồng video quảng cáo nền (RTP video) phát phía sau.
   - Phần đầu hiển thị Logo thương hiệu, tên Doanh nghiệp kèm Huy hiệu xanh lục "✔ Certified" tạo lòng tin.
   - Hộp nội dung nổi bật (Container) ở trung tâm hiển thị:
     * Banner văn bản khuyến mãi nổi bật màu đỏ rực rỡ, kèm hiệu ứng chuyển động thu hút sự chú ý.
     * Hình ảnh mã QR Code rõ nét, kích thước tối ưu để người dùng có thể quét trực tiếp bằng thiết bị khác hoặc chạm vào nút bấm để mở trang web quà tặng của doanh nghiệp.
   - Thanh điều khiển dưới cùng gồm 3 nút bấm tương tác: "Bỏ qua", "Nhận ngay" (Web-to-Action chuyển hướng mở trình duyệt ngoài), và "Nghe cuộc gọi".

Đầu ra yêu cầu: Hãy tạo mã nguồn tệp `index.html` hoàn chỉnh có sẵn toàn bộ mã CSS nhúng và mã xử lý logic JS bên trong.

Dưới đây là khung mã nguồn HTML5 tham khảo để bạn triển khai:

```html
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <title>Enterprise Verified Caller ID & Ads</title>
    <style>
        body {
            margin: 0; padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            background: rgba(0, 0, 0, 0.4); /* Nền mờ cho phép hiển thị video phía sau */
            color: #fff;
            display: flex; flex-direction: column; justify-content: space-between;
            height: 100vh; overflow: hidden;
        }
        /* Header định danh doanh nghiệp */
        .verified-header {
            background: rgba(20, 20, 20, 0.85);
            padding: 20px; border-radius: 0 0 20px 20px;
            display: flex; align-items: center; gap: 15px;
            box-shadow: 0 4px 15px rgba(0,0,0,0.3);
        }
        .logo { width: 50px; height: 50px; border-radius: 50%; border: 2px solid #00E676; }
        .brand-info { flex-grow: 1; }
        .brand-name { font-size: 18px; font-weight: bold; display: flex; align-items: center; gap: 5px; }
        .badge { color: #00E676; font-size: 14px; }
        .intent { font-size: 14px; color: #ccc; margin-top: 4px; }

        /* Hộp hiển thị QR Code và Quảng cáo */
        .promo-container {
            align-self: center;
            background: rgba(255, 255, 255, 0.95);
            color: #333; padding: 25px; border-radius: 20px;
            text-align: center; width: 80%; max-width: 300px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.5);
            animation: popUp 0.5s ease-out;
        }
        .qr-code { width: 180px; height: 180px; margin: 15px auto; display: block; }
        .promo-text { font-size: 15px; font-weight: bold; color: #d32f2f; }

        /* Nút tương tác */
        .action-bar {
            padding: 30px; display: flex; justify-content: space-around; gap: 15px;
        }
        .btn {
            padding: 12px 30px; border-radius: 25px; border: none;
            font-size: 15px; font-weight: bold; cursor: pointer; transition: 0.2s;
        }
        .btn-claim { background: #00E676; color: #000; }
        .btn-skip { background: rgba(255, 255, 255, 0.2); color: #fff; }

        @keyframes popUp {
            from { transform: scale(0.8); opacity: 0; }
            to { transform: scale(1); opacity: 1; }
        }
    </style>
</head>
<body>

    <!-- Header định danh -->
    <div class="verified-header">
        <img id="logo" src="assets/logo.png" class="logo" alt="Logo">
        <div class="brand-info">
            <div class="brand-name"><span id="brandName">VIETTEL TELECOM</span> <span class="badge">✔ Certified</span></div>
            <div id="callIntent" class="intent">Cuộc gọi từ hệ thống chăm sóc khách hàng</div>
        </div>
    </div>

    <!-- Banner và QR Code -->
    <div class="promo-container">
        <div id="promoText" class="promo-text">QUÉT MÃ NHẬN KHUYẾN MÃI!</div>
        <img id="qrCode" src="assets/qr-coupon.png" class="qr-code" alt="QR Code">
        <div style="font-size: 12px; color: #666;">Quét mã QR hoặc nhấn nút nhận ngay bên dưới</div>
    </div>

    <!-- Thanh nút tương tác nhanh -->
    <div class="action-bar">
        <button class="btn btn-skip" onclick="handleSkip()">Bỏ qua</button>
        <button class="btn btn-claim" onclick="handleClaim()">Nhận ngay</button>
    </div>

    <script>
        // JS logic tích hợp WebRTC và xử lý sự kiện
        // [AI sẽ bổ sung logic kết nối WebRTC Stream 1000 và binding dữ liệu động]
        function handleSkip() {
            console.log("User skipped");
            window.close();
        }
        function handleClaim() {
            window.open('https://viettel.vn/khuyen-mai', '_blank');
        }
    </script>
</body>
</html>
```
```
---
