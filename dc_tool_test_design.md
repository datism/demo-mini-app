# Thiết kế Kiến trúc cho `dc-tool-test` (Golang)

Mục tiêu của `dc-tool-test` là mô phỏng chính xác 100% hành vi của ứng dụng Android client trên S24 (gồm cả SIP, WebRTC Data Channel và Media) để thực hiện end-to-end testing nghiệm thu hệ thống (MF, IMS-AS, DCSF) một cách tự động hóa mà không cần thiết bị thật.

Vì hệ thống MF backend đã dùng Golang, việc chọn Golang cho `dc-tool-test` là lựa chọn hoàn hảo nhờ tận dụng được thư viện `pion/webrtc`. Dưới đây là kiến trúc chi tiết để đạt được độ chính xác mô phỏng cao nhất.

## 1. Cấu trúc Module Cốt lõi (Core Engines)

Để giống y hệt S24, tool cần có 4 thành phần chạy độc lập nhưng giao tiếp với nhau (tương đương với các Manager trong file `.kt` của bạn):

### A. SIP Engine (tương đương `SipManager.kt`)
*   **Nhiệm vụ:** Quản lý vòng đời báo hiệu SIP (REGISTER, INVITE, Re-INVITE, ACK, BYE).
*   **Công nghệ:** Sử dụng thư viện `github.com/emiago/sipgo` hoặc tự viết TCP/UDP socket parser (vì bản tin SIP trong hệ thống của bạn khá đặc thù).
*   **Logic SDP (Quan trọng):** Phải implement chính xác hàm `MergeAdcOffer` và `SplitAdcAnswer` y hệt như app Android. Nó phải lấy SDP fragment 1-dòng từ `WebRTC Engine` và nhúng đúng thứ tự vào SIP SDP tổng (Audio -> Video -> BDC -> ADC).

### B. WebRTC Data Engine (tương đương `WebRtcManager.kt`)
*   **Nhiệm vụ:** Thiết lập Data Channel.
*   **Công nghệ:** `github.com/pion/webrtc/v4`
*   **Thiết kế Bắt buộc (Cách ly PC):**
    *   Tuyệt đối **không** dùng 1 PeerConnection cho cả BDC và ADC.
    *   Phải khởi tạo `bdcPC := webrtc.NewPeerConnection(...)` ngay khi bắt đầu cuộc gọi.
    *   Phải khởi tạo `adcPC := webrtc.NewPeerConnection(...)` khi có sự kiện mở Mini-app, và `adcPC.Close()` ngay khi đóng Mini-app (BDC phải được giữ nguyên).
    *   Xử lý đúng quy tắc `a=mid` như vừa sửa lỗi trên Android (không ghi đè `mid` khi là Answer).

### C. Media Dummy Engine (tương đương RTP/Audio/Video Manager)
*   **Nhiệm vụ:** Giữ kết nối Voice/Video sống.
*   **Mô phỏng:** Dù không cần truyền giọng nói thật, nhưng tool **bắt buộc phải mở socket UDP** và cứ mỗi 20ms gửi 1 gói tin RTP trắng (hoặc PCMA silence) vào port Audio của MF. Nếu không, các thiết bị mạng/MF có thể rớt cuộc gọi do Timeout (RTP Inactivity).

### D. Mini-App Simulator (tương đương `WebView` & Javascript Bridge)
*   **Nhiệm vụ:** Thay thế cho trình duyệt WebView của Android. Thay vì render HTML/JS, nó sẽ hoạt động theo kịch bản (Scripted Profiles).
*   **Cách hoạt động:**
    1.  Mở `bdcDataChannel`.
    2.  Gửi luồng byte giả lập HTTP Request: `GET / HTTP/1.1\r\nHost: bdc\r\n\r\n`.
    3.  Đọc HTTP Response.
    4.  Tự động kích hoạt sự kiện "Người dùng chọn Caro" -> Báo cho `SIP Engine` gửi Re-INVITE để tạo ADC.
    5.  Mở `adcDataChannel` -> Gửi JSON `{"action": "move", "x": 1, "y": 2}`.

---

## 2. Kịch bản Mô phỏng Điển hình (Test Scenarios YAML)

Thay vì hardcode, tool nên đọc một file kịch bản YAML để tự động hóa:

```yaml
# test_scenario.yaml
name: "Test Callee Initiated Caro App"
caller:
  id: "1001"
  action: "wait_for_call"
callee:
  id: "1002"
  action: "answer_and_trigger_app"
  app_id: "caro"
  adc_actions:
    - wait: 2s
    - send_json: '{"action":"move","x":1,"y":1}'
    - wait_for_json: '{"action":"move"}' # Đợi Caller đánh lại
    - exit_app: true # Kích hoạt Re-INVITE port=0 để đóng ADC
```

---

## 3. Kiến trúc Luồng Thực thi (Concurrency Model)

Mỗi `TestAgent` (đại diện cho 1 máy S24) sẽ chạy trong một Goroutine chính, với các channel (`chan`) để giao tiếp:

```go
type S24Agent struct {
    SipClient      *sipgo.Client
    BdcPC          *webrtc.PeerConnection
    AdcPC          *webrtc.PeerConnection
    State          State
    EventChannel   chan Event
}

func (a *S24Agent) Run() {
    for event := range a.EventChannel {
        switch e := event.(type) {
        case SipInviteEvent:
            a.handleIncomingCall(e)
        case TriggerMiniAppEvent:
            a.startAppNegotiation(e.AppId)
        case SipReInviteEvent:
            a.handleAppNegotiation(e)
        }
    }
}
```

---

## 4. Những "Cạm bẫy" cần tránh (Dựa trên lỗi thực tế)

Để tool giống hệt điện thoại thật, cần lưu ý:
1. **CSeq Tracking**: Tool thường hay tính sai `CSeq` khi gửi Re-INVITE, dẫn đến bị server từ chối. S24Agent phải tăng `CSeq` chuẩn xác.
2. **Dynamic Ports**: S24 cấp phát port ngẫu nhiên cho WebRTC. Tool cũng phải để `pion` chọn port UDP ngẫu nhiên, không bind cứng.
3. **DTLS Fingerprint**: Tool phải tự sinh Certificate cục bộ cho `bdcPC` và `adcPC` riêng biệt y như WebRTC trên điện thoại.
4. **SDP BUNDLE**: Phải tắt tính năng BUNDLE của `pion` (`webrtc.Configuration{BundlePolicy: webrtc.BundlePolicyMaxCompat}`) vì hệ thống của bạn yêu cầu Port độc lập cho từng Data Channel (theo chuẩn 3GPP).

## Kết luận
Bằng cách xây dựng `dc-tool-test` xoay quanh `pion/webrtc` và tách biệt hoàn toàn BDC / ADC thành 2 PeerConnections, đồng thời quản lý logic SDP Merging bằng code Go tĩnh, bạn có thể tạo ra hàng ngàn máy "S24 ảo" để stress test toàn bộ hệ thống MF và DCSF mà độ tin cậy tương đương 100% test tay.
