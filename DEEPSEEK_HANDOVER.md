# DEEPSEEK HANDOVER DOCUMENT

Tài liệu này tổng hợp bối cảnh, nguyên nhân và chi tiết kế hoạch sửa 2 lỗi chính để DeepSeek có thể tiếp tục công việc ngay lập tức mà không cần đọc lại hàng nghìn dòng code cũ.

## 1. Bối cảnh dự án (Context)
- **Hệ thống**: Ứng dụng Android gọi điện WebRTC tích hợp SIP IMS (IMS-AS, MF viết bằng Go), thực hiện truyền dữ liệu tương tác qua **WebRTC Data Channel (ADC/BDC)** theo chuẩn GSMA/3GPP.
- **Client (Android)**: Các tệp trọng tâm nằm tại `android-client/app/src/main/java/com/demo/imsdc/` gồm: `CallActivity.kt`, `ui/MiniAppManager.kt`, `webrtc/WebRtcManager.kt`, `webrtc/DataChannelManager.kt`, `sip/SdpHelper.kt`.
- **Server (Go)**: `go-mf/internal/` bao gồm IMS-AS (`b2bua_invite.go`, `sdp.go`) và Media Function. 

## 2. Vấn đề 1: Màn hình Video Input bị kẹt (Không kéo thả được)
- **Triệu chứng**: Người dùng không thể chạm và kéo màn hình video nhỏ (`local_video_view`) để chuyển vị trí vì bị một View khác nuốt sự kiện touch.
- **Nguyên nhân**: `webview_miniapp` được cấu hình trải rộng toàn màn hình. Dù nó trong suốt, thứ tự Z-order của nó đang nằm trên lớp video, dẫn tới mọi sự kiện chạm vào video đều rơi vào WebView.
- **Cách khắc phục**: Mở file `CallActivity.kt`, tìm hàm `setupDraggableLocalVideo()`. Thêm lệnh `binding.localVideoView.bringToFront()` để đẩy lớp video lên trên cùng. Cần thiết thì cấu hình thêm trong XML hoặc layout cha để ưu tiên `touchEvent` cho video view này.

## 3. Vấn đề 2: Lỗi luồng 2 chiều (Thả tim tương tác Fun-Calling)
- **Triệu chứng**: User 1002 (Caller) gọi 1003 (Callee). Nếu 1002 chọn thả tim, hai bên hiển thị bình thường. **NHƯNG**, nếu 1003 (Callee) chọn menu thả tim, máy 1003 thì chuyển sang "Thả tim tương tác", còn máy 1002 thì không có hiện tượng gì, text giao diện vẫn kẹt ở trạng thái cũ "Cuộc gọi thoại & video đã kết nối". Tim không hiển thị trên màn hình 1002.
- **Nguyên nhân gốc (Sau khi đã trace log chi tiết)**:
  1. Khi 1003 chọn app, nó tạo Re-INVITE gửi sang 1002 với 2 khối `m=application` (BDC và ADC). Server Go xử lý và proxy bình thường.
  2. Khi 1002 nhận Re-INVITE trong hàm `onReInviteReceived` (của `CallActivity.kt`), quá trình bóc tách SDP (`SplitAdcAnswer` trong `WebRtcManager.kt`) để gán vào `setRemoteDescriptionRaw` bị lỗi.
  3. Lỗi này có thể do hàm `SplitAdcAnswer` lọc mất thẻ `a=group:BUNDLE`, hoặc logic kiểm tra port 0 (`hasAdcPort0`) bị nhận diện sai thành `true`, khiến biến `reqApp` bị ép về trạng thái fallback `"operator-menu"`.
  4. Hậu quả: Hàm parse ném ra Exception làm kẹt tiến trình (silent fail) HOẶC nó gọi `launchMiniApp("operator-menu")` nên text giao diện không đổi sang "Thả tim tương tác".
  5. **Ngoài ra (Lỗi render WebView)**: Do WebView trong suốt sử dụng Hardware Acceleration, nhiều khi có sự kiện thả tim truyền qua DataChannel thành công (chạy `window.receiveData`), WebView không tự động vẽ lại (re-draw) màn hình trừ khi user chạm tay vào màn hình.

- **Cách khắc phục (DeepSeek cần thực hiện)**:
  - **Trong `CallActivity.kt`**: 
    - Bọc `try-catch` nhỏ lẻ cho luồng `onReInviteReceived` để bắt chính xác dòng ném lỗi SDP.
    - Sửa logic tính toán `isSingleAppLine` và `hasAdcPort0` để đảm bảo khi SDP mạng trả về chuẩn 2 block `m=application` thì biến `reqApp` vẫn nhận chuẩn giá trị `"fun-calling-app"`.
  - **Trong `WebRtcManager.kt`**:
    - Sửa lại hàm `SplitAdcAnswer`: Đảm bảo không cắt nhầm thuộc tính quan trọng của Pion WebRTC (như BUNDLE group) khiến lệnh `createAnswer()` phía sau bị crash. Đảm bảo fallback an toàn nếu block rỗng.
  - **Trong `DataChannelManager.kt`**:
    - Tại hàm xử lý `evaluateJavascript` (nơi gọi hàm DOM hiển thị thả tim), phải gọi `activity.binding.webviewMiniapp.postInvalidate()` hoặc `invalidate()` để ép UI cập nhật ngay lập tức. Cứu cánh cho tình trạng WebView lười render.

## 4. Hành động tiếp theo cho DeepSeek
1. Đọc file `DEEPSEEK_HANDOVER.md` này để nắm bức tranh toàn cảnh.
2. Sửa trực tiếp 3 file Android nói trên (`CallActivity.kt`, `WebRtcManager.kt`, `DataChannelManager.kt`) theo hướng dẫn.
3. Chạy lệnh build, hoặc hướng dẫn user test lại quy trình: `1002 gọi 1003 -> 1003 bấm thả tim -> Kiểm tra 1002 có nhảy UI sang thả tim và có thấy tim bay lên hay không.`
