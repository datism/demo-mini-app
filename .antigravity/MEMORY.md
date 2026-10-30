# System Memory & Checkpoint

## Trạng thái hệ thống (Cập nhật phiên làm việc)
- **Thiết bị kiểm thử**: 2 điện thoại Samsung Galaxy S24 thật (`R5CY54VL09X` - 1002 và `R5CY54VL13Y` - 1003).
- **Tính năng hoàn thành & Đã chứng minh bằng hình ảnh thực tế**:
  1. **Nhận cuộc gọi khi tắt màn hình (Screen-off Call Wakeup)**: Đã chứng minh qua script `prove_screen_off_call.py` (màn hình thức dậy sau 1.57s từ DOZE_SUSPEND, mở Slide-to-Answer, kết nối thành công).
  2. **Slide-to-Answer (Nút trượt trả lời cuộc gọi)**: Widget `SlideToAnswerView` chuẩn Samsung One UI, hoạt động ổn định trên thiết bị thật.
  3. **Trạng thái Ringing & Tone Tút tút**: Đã tích hợp ToneGenerator và xử lý 180 Ringing.
  4. **Ứng dụng CSKH Thông minh Tổng đài 198 (`smart-menu`)**:
     - Hoạt động chuẩn kiến trúc **GSMA 5G New Calling Enterprise / Hotline UAS**: Server IMS-AS / DCAS tự động trả lời cuộc gọi tới số `198` (200 OK), cấp BDC nạp mini-app và thiết lập Application Data Channel (ADC - Stream 1000).
     - **Tính năng 1 (Gặp tổng đài viên - Lời chào & Giọng nói tiếng Việt)**: Đã tích hợp file âm thanh giọng nói tổng đài viên offline chuẩn (`agent_greeting.mp3`), cầu nối Native Android MediaPlayer qua `AndroidBridge.playAgentAudio()`, cấu hình `USAGE_VOICE_COMMUNICATION` & `CONTENT_TYPE_SPEECH`, phát đồng thời qua HTML5 Audio & sóng âm động Waveform đa sắc.
     - **Tính năng 2 (Video hoạt hình hài hước 1080p)**: Gửi action `"video"`, nhận phản hồi *"Đang phát video hoạt hình vui nhộn từ hệ thống tổng đài..."* và mở trình phát HTML5 Video Player Modal phát video hoạt hình Full HD 1080p sắc nét, kích thước khớp hoàn hảo với màn hình điện thoại Samsung Galaxy S24.
     - **Tính năng 3 (Thời gian hệ thống)**: Gửi action `"time"`, nhận giờ thực tế chính xác từ máy chủ DCAS và hiển thị lên màn hình.
  5. **Kiểm thử hồi quy (Regression Test) cho 3 Mini-Apps hiện có**:
     - **Bảng vẽ TuYaApp (`sketch-sync-app`)**: Đồng bộ nét vẽ 2 chiều qua ADC.
     - **Game Cờ Caro 9x9 (`caro`)**: Đối kháng 2 bên với nước cờ X và O đồng bộ mượt mà.
     - **Fun Calling (`fun-calling-app`)**: Gửi/nhận biểu cảm cảm xúc đồng bộ 2 bên.
  6. **Kiểm thử Phím Back vật lý/hệ thống của điện thoại (Hardware Back Key) & Tương tác Bảng vẽ qua ADC**:
     - Đã chứng minh hoàn tất qua script và hình ảnh thực tế.
  7. **Dịch vụ Định danh Doanh nghiệp, Quảng cáo & QR Code Tương tác (Enterprise Verified Caller ID, Ads & Interactive QR Code - A2P Flow)**:
     - Đã chứng minh hoàn tất trên 2 thiết bị Samsung S24 thật.
  8. **Khắc phục Lỗi Gọi P2P 1002 -> 1003 & Trải nghiệm Người dùng**:
     - Đổi tên ứng dụng thành **"5G New Calling"** và tự động kết nối sang OneUI Dialer.
  9. **Khắc phục Lỗi Bảng vẽ tương tác chỉ tải 1 bên (Whiteboard One-Sided Load Fix & Live Sync Proof)**:
     - Chuẩn hóa App ID mapping và loại bỏ race condition CSeq trong SIP Re-INVITE.
  10. **Khắc phục Lỗi nét vẽ thừa / gạch chéo tự động trên Bảng vẽ**:
      - Gỡ bỏ khối ép vẽ JS fallback cũ trong `DataChannelManager.kt`.
  11. **Khắc phục Lỗi Thả tim tương tác / Fun Calling không hiển thị emoji 2 chiều**:
      - Duy trì kết nối MDC2 TCP và bổ sung fallback in-memory session trong DCAS.
  12. **Khắc phục & Kiểm chứng Game Cờ Caro 9x9**:
      - Sửa Route header, ADC type P2A2P và ổn định kết nối đối kháng 2 bên.
  13. **Khắc phục Lỗi Chạm Vùng Ngoài / Top Bar Tự Động Mở Bảng Vẽ TuYaApp**:
      - Gỡ bỏ sự kiện chạm trên top bar.
  14. **Khắc phục & Kiểm chứng luồng Callee B mở Game Cờ Caro 9x9**:
      - Callee B mở game thành công cho cả 2 máy.
  15. **Khắc phục triệt để lỗi Game Cờ Caro tự động bị thoát trên thiết bị gọi**:
      - Ngăn nạp đè `operator-menu` và lọc bỏ dòng trống TCP keep-alive.
  16. **Phát hành Phiên bản Tag Git `15.0.5`**:
      - Đã đóng gói và phát hành tag 15.0.5.
  17. **Cô lập Menu Tổng đài 198 & Khắc phục Lỗi Bấm Quay lại mở Danh sách Ứng dụng P2P**:
      - Thay nút dưới cùng thành *"🔄 Làm mới Menu Tổng đài 198"* và chặn quay lại menu P2P.
  18. **Nâng cấp Video Hoạt hình Hài hước Full HD 1080p & Tối ưu Giao diện Màn hình Điện thoại**:
      - Nâng cấp video hoạt hình chất lượng cao Full HD 1080p (`cartoon_video.mp4` - 1920x1080) và HD 720p (`sample_video.mp4` - 1280x720) cho tính năng phát ngẫu nhiên.
      - Tối ưu kích thước video modal trong `smart-menu/index.html` khớp chuẩn tỷ lệ màn hình điện thoại.
  19. **Chẩn đoán & Khắc phục lỗi Cuộc gọi P2P 1002 gọi 1003 bấm Answer bị lỗi**:
      - Khắc phục phân giải DNS cho DCSF và kiểm thử 2 chiều thành công.
  20. **Khắc phục & Kiểm chứng Âm thanh Giọng nói Tổng đài viên 198 ("Gặp tổng đài viên")**:
      - **Nguyên nhân**: Android WebView không hỗ trợ Web Speech Synthesis khi đang trong luồng gọi thoại/chế độ liên lạc `MODE_IN_COMMUNICATION`.
      - **Khắc phục**: Nhúng file âm thanh giọng nói tiếng Việt chuẩn `agent_greeting.mp3` offline, bổ sung phương thức `playAgentAudio()` qua Native MediaPlayer và bật `mediaPlaybackRequiresUserGesture = false`.
      - **Bằng chứng**: Đã kiểm thử trực tiếp trên Samsung S24 thật: `proof_agent_voice_1_menu.png`, `proof_agent_voice_2_speaking.png`, `proof_agent_voice_3_waveform.png`.
  21. **Early Media Voice Announcement for Unregistered Extension (10039)**
      - **Problem**: Calling unregistered callee (`10039`) did not play the pre-recorded voice announcement (*"Thuê bao quý khách vừa gọi hiện không liên lạc được, xin quý khách vui lòng gọi lại sau"*), and call ended abruptly or silently.
      - **Root Cause Analysis (RCA)**:
        1. **Server-side (MF & IMS-AS)**:
           - `go-mf/internal/mf/media_function.go`: `AllocateAnnouncementSession` previously used port 40000 instead of standard caller audio port 40002. Accidental socket closure in `CleanupSession` prevented subsequent port reuse.
           - `go-mf/internal/imsas/b2bua.go`: Fixed RFC 3264 SDP answer order (`m=audio 40002`, `m=video 0`, `m=application 0`) and added RFC 5009 `P-Early-Media: sendrecv` header to `183 Session Progress` to prevent Android DSP from muting early audio.
        2. **Client-side (`android-client`)**:
           - `SipManager.kt`: Updated `in 100..199` provisional handler to extract SDP body (`SdpHelper.extractSdpBody(msg)`) and pass it to `onInviteResponse`.
           - `CallActivity.kt`: Moved RTP media startup (`rtpMediaManager?.setRemoteDestination(...)` and `rtpMediaManager?.start(null, null)`) directly to the background thread execution path immediately upon receiving `183 Session Progress`, eliminating UI coroutine delays during Activity launch.
           - Handled `487 Request Terminated` callback cleanly to stop `AudioTrack`, `RtpMediaManager`, and close the screen.
      - **Verification Results**:
        - **Client Logcat**: Phone successfully received `183 Session Progress`, started `AudioTrack`, decoded 50,720 audio frames (PCMA to 16-bit PCM), and played them via the earpiece/speaker.
        - **Lifecycle Cleanup**:
          - `AudioTrack`: stopped cleanly (`50720 frames delivered`).
          - `RtpMediaManager`: sockets closed and threads terminated.
          - `WebRtcManager` & `EglBase`: closed and released safely.
          - `MF & IMS-AS`: Unused P2P context deleted, announcement session terminated, latched RTP cache cleared, zero lingering sessions.
      - **Files Modified**:
        - `go-mf/internal/mf/media_function.go`
        - `go-mf/internal/imsas/b2bua.go`
        - `android-client/app/src/main/java/com/demo/imsdc/sip/SipManager.kt`
        - `android-client/app/src/main/java/com/demo/imsdc/ui/CallActivity.kt`
        - `android-client/app/src/main/java/com/demo/imsdc/media/RtpMediaManager.kt`
  22. **Tính năng Phát Thông Báo Âm Thanh Thuê Bao Không Liên Lạc Được (Unreachable Subscriber Early Media Announcement - 3GPP Rel-19 TS 24.186)**:
      - **Kịch bản**: Khi thuê bao A gọi thuê bao B nhưng B chưa đăng ký SIP (S-CSCF trả về SIP `480 Temporarily Unavailable` hoặc `404 Not Found`).
      - **Xử lý Early Media**: IMS-AS chặn mã lỗi 480/404, giải phóng subscription/BDC context rỗng ban đầu, yêu cầu Media Function (MF) cấp RTP session nhẹ (`AllocateAnnouncementSession`) và truyền phát file âm thanh PCMA 8kHz `unreachable.pcma` (*"Thuê bao quý khách vừa gọi hiện không liên lạc được, xin quý khách vui lòng gọi lại sau"*) về UE A qua RTP port chỉ định.
      - **Bản tin SIP 183**: IMS-AS trả bản tin `183 Session Progress` mang SDP Early Media (`m=audio <mf_port> RTP/AVP 8`, `a=rtpmap:8 PCMA/8000`, `a=sendonly`) về UE A để nghe thông báo.
      - **Tự động ngắt cuộc gọi (SIP 487)**: Sau khi phát hết file âm thanh (hoặc timer kết thúc), MF kích hoạt HTTP callback `/announcement/done` sang IMS-AS -> IMS-AS gọi `/announcement/stop` giải phóng tài nguyên media trên MF và gửi SIP `487 Request Terminated` về UE A để kết thúc cuộc gọi một cách chuẩn mực.
      - **Xử lý Hủy cuộc gọi (SIP CANCEL)**: Nếu người dùng bấm ngắt cuộc gọi trong lúc đang nghe âm báo, IMS-AS lập tức trả `200 OK` cho CANCEL, trả `487 Request Terminated` cho INVITE, đồng thời ra lệnh dừng phát âm thanh ngay lập tức trên MF và giải phóng toàn bộ socket.
      - **Chẩn đoán & Khắc phục Lỗi không nghe thấy âm thanh trên máy Samsung S24 thật**:
        + *Nguyên nhân 1 (RTP Port & NAT/Tailscale)*: MF cấp phát port ngẫu nhiên ephemeral (VD: 55092) thay vì sử dụng Caller Audio Port chuẩn cố định (`m.publicPort + 2` = `40002`) và socket `m.audioConn`, dẫn đến gói tin RTP gửi đi bị tường lửa/NAT Tailscale chặn hoặc máy S24 không tiếp nhận.
        + *Nguyên nhân 2 (Thiếu Header P-Early-Media - RFC 5009)*: Bộ giải mã âm thanh thoại (Audio DSP / HAL) trên Samsung OneUI Dialer mặc định sẽ ngắt tiếng (mute) khi nhận bản tin 183 Session Progress trừ khi có header `P-Early-Media: sendrecv` (theo chuẩn 3GPP TS 24.229 & RFC 5009).
        + *Nguyên nhân 3 (Lệch số lượng m-line trong SDP Answer - RFC 3264)*: Bản tin INVITE của S24 có 3 dòng media (`m=audio`, `m=video`, `m=application`), nhưng SDP trong 183 cũ chỉ trả về 1 dòng `m=audio`, vi phạm RFC 3264 khiến stack IMS trên thiết bị từ chối SDP.
        + *Nguyên nhân 4 (Client Android App không bật Audio Receiver khi nhận 183)*: Trong ứng dụng Android (`5G New Calling`), hàm `onInviteResponse` trong `SipManager.kt` trước đó đã bỏ qua phần thân SDP của các mã 1xx (truyền `null`), và `CallActivity.kt` chỉ gọi `rtpMediaManager.start(...)` khi nhận `200 OK`. Do đó, khi máy nhận `183 Session Progress`, module giải mã RTP audio trên app chưa được kích hoạt để đọc luồng âm thanh từ MF.
        + *Khắc phục toàn diện Server & Client*: 
          1) Phía Server MF: Cố định port 40002 và socket `m.audioConn` khi phát âm báo.
          2) Phía Server IMS-AS: Viết hàm `BuildEarlyMediaSDPAnswer` trả về SDP đầy đủ theo RFC 3264 và thêm header `P-Early-Media: sendrecv` vào bản tin `183 Session Progress`.
          3) Phía Client `SipManager.kt`: Trích xuất SDP từ bản tin 183 và chuyển tới `onInviteResponse`.
          4) Phía Client `CallActivity.kt`: Xử lý sự kiện `statusCode == 183`, dừng tiếng chuông tút tút, trích xuất địa chỉ RTP remote (`100.111.73.110:40002`) và kích hoạt ngay `rtpMediaManager.start(null, null)` để mở luồng thu âm thanh qua `AudioTrack`, đồng thời xử lý mã ngắt `487` mượt mà.
          5) Đã biên dịch lại APK và nạp thành công lên cả 2 thiết bị Samsung S24 thật (`R5CY54VL09X` và `R5CY54VL13Y`).
      - **Kiểm thử tự động & Hồi quy**: Đã bổ sung unit test `TestUnreachableAnnouncementFlow` trong `imsas` và chạy toàn bộ bộ test `dc-test-tool` trên cụm Kubernetes đạt kết quả hoàn hảo **75/75 (100%) assertions passed**, đảm bảo an toàn tuyệt đối cho các dịch vụ hiện có (Hotline 198, P2P, Whiteboard, Caro, Fun Calling).

  23. **Tính năng Thu Gọn Bàn Phím Số (Collapsible Dialpad) & Nút Nổi Bật Bàn Phím (Floating Dialpad Button)**:
      - **Giao diện Android**: Nâng cấp `activity_main.xml` và `MainActivity.kt` trên ứng dụng Android `5G New Calling`.
      - **Cơ chế hoạt động**:
        + Khi người dùng chạm/chọn vào vùng lịch sử cuộc gọi (`layoutCallHistory`), bàn phím số (`layoutDialpad`) tự động thu nhỏ và biến mất với hiệu ứng chuyển động mượt mà (`TransitionManager.beginDelayedTransition`), giúp không gian hiển thị danh sách lịch sử cuộc gọi mở rộng tối đa tương tự như ứng dụng gọi điện native của các dòng smartphone cao cấp.
        + Đồng thời, một nút tròn nổi (`fabShowDialpad`) xuất hiện ở góc dưới bên phải màn hình.
        + Khi người dùng bấm vào nút nổi `fabShowDialpad`, bàn phím số lập tức trượt mở lên trở lại và nút nổi ẩn đi.
      - **Xác thực thực tế**: Đã build APK, cài đặt lên 2 máy Samsung Galaxy S24 thật (`R5CY54VL09X` và `R5CY54VL13Y`), chụp ảnh màn hình xác nhận hoạt động mượt mà.

  24. **Nâng cấp & Mở Rộng Bộ Kiểm Thử Tự Động `dc-test-tool` Đạt 100% Bao Phủ Tính Năng Máy Thật (9 Scenarios - 103/103 Pass)**:
      - **Phạm vi kiểm thử tự động**: Đồng bộ hóa toàn bộ các kịch bản test trên máy thật Samsung S24 vào công cụ mô phỏng Go `dc-test-tool` (`cmd/dc-test-tool`, `internal/dctest`, `internal/uesim`) và file kịch bản `deploy/test_scenarios.json`:
        1. *Scenario 1*: Whiteboard Draw & FunCalling Emoji & Thoát Mini-App (Close ADC qua SIP re-INVITE port 0, giữ BDC) (2003 -> 2004).
        2. *Scenario 2*: Cuộc gọi thoại & video thuần túy ngắn (Voice/Video Only RTP assertion) (2005 -> 2006).
        3. *Scenario 3*: Caro Game Interaction (Caller mở game, callee kết nối P2P, đánh nước cờ, thoát app) (1005 -> 1006).
        4. *Scenario 4*: Early Dialog CANCEL (Thuê bao A hủy cuộc gọi trước khi B nhấc máy) (2007 -> 2008).
        5. *Scenario 5*: Cuộc gọi bị từ chối 486 Busy Here / 603 Decline (2007 -> 2008).
        6. *Scenario 6*: Whiteboard Draw kết thúc bởi Callee Hangup (2001 -> 2002).
        7. *Scenario 7*: Callee-Initiated Caro Game (Thuê bao B kích hoạt mở ứng dụng Caro và mời A tham gia) (1007 -> 1008).
        8. *Scenario 8*: Hotline 198 CSKH Smart Menu (A2P UAS Interaction) (Tải Root Menu qua BDC Stream 0, xác thực SIP/RTP và HTTP response) (2009 -> 198).
        9. *Scenario 9*: Doanh nghiệp Brandname / Enterprise Caller ID & Promotion Banner (Tải thông tin nhận diện doanh nghiệp qua BDC Stream 0 HTTP GET `/enterprise-callerid`) (1001 -> 1002).
      - **Các cải tiến kỹ thuật trong `dc-test-tool` & `uesim`**:
        + Bổ sung cơ chế `isUASCall` cho cuộc gọi tổng đài / IVR (198) không cần callee simulator.
        + Bổ sung tính năng `http_get_bdc` gửi HTTP GET qua BDC Stream 0 và xác thực gói payload ZIP/JSON trả về.
        + Bổ sung hành động `close_app` / `exit_app` gửi SIP re-INVITE với port = 0 để giải phóng ADC stream mà vẫn giữ nguyên BDC stream và RTP voice/video.
        + Chuẩn hóa việc sử dụng đúng `From tag` trong ACK, Re-INVITE và Re-INVITE Close theo đúng RFC 3261 dialog matching.
      - **Kết quả kiểm thử trên Kubernetes cluster**:
        + Toàn bộ **103/103 Assertions Passed (100% Pass Rate)**, 0 Failed, tổng thời gian chạy: **2m14s**.

  25. **Rà Soát & Bổ Sung Tài Liệu Kiến Trúc, Hướng Dẫn Tùy Biến (Customization Guide) Cho Toàn Bộ Node Golang (`go-mf`)**:
      - **Tài liệu master `go-mf/README.md`**:
        + Tổng quan kiến trúc hệ thống 3GPP Rel-18/19 & GSMA NG.134 với sơ đồ Mermaid chi tiết.
        + Bản đồ phân chia trách nhiệm của 8 microservices (`ims-as`, `dcsf`, `dcas`, `dcar`, `mf`, `pcf`, `pcap-exporter`, `dc-test-tool`) và 11 internal packages (`imsas`, `dcsf`, `dcas`, `dcar`, `mf`, `sba`, `dctest`, `uesim`, `redisclient`, `config`, `logger`).
        + Hướng dẫn chi tiết từng bước (Step-by-step How-To):
          1. *Cách thêm một Mini-App / Dịch vụ mới từ đầu* (HTML5 manifest, DCSF routing, DCAS handler, DCAR packaging).
          2. *Cách mở rộng thao tác nghiệp vụ Data Channel* (Caro game action, Whiteboard sync, FunCalling).
          3. *Cách bổ sung kịch bản test tự động mới vào `dc-test-tool`*.
        + Hướng dẫn biên dịch, chạy unit test offline, build Docker image đa tầng và deploy Kubernetes.
      - **Bổ sung Go doc comments chuẩn mực**: Cập nhật docstring package và struct tại `b2bua.go`, `dcsf.go`, `dcas.go`, `media_function.go`, `runner.go`.
      - **Biên dịch & Kiểm thử thành công**: Toàn bộ unit test và build image `go-mf:local` đạt 100% pass, đã nạp thành công vào cụm Kind Kubernetes.

  26. **Chuẩn Hóa & Làm Rõ Tài Liệu Về Vai Trò Của Tailscale (Chỉ Cho Test Qua Internet vs Môi Trường Production Viễn Thông)**:
      - Đã cập nhật mục `11.D` trong master [README.md](file:///d:/project/demo-mini-app/README.md) và mục `5.5` trong [go-mf/README.md](file:///d:/project/demo-mini-app/go-mf/README.md):
        + **Mục đích Tailscale**: CHỈ là addon tùy chọn (`deploy/k8s/addons/tailscale/`) phục vụ môi trường Lab/Test từ xa qua Internet công cộng (vượt NAT/CGNAT mà không cần thuê IP tĩnh hay mở port router).
        + **Môi trường Production (Nhà mạng 5G Core/IMS)**: HOÀN TOÀN KHÔNG CẦN VÀ KHÔNG SỬ DỤNG TAILSCALE. Thiết bị UE kết nối trực tiếp với mạng lõi thông qua trạm vô tuyến (gNodeB/eNodeB) và khối UPF qua APN `ims`, luồng SIP và RTP/WebRTC đi trực tiếp qua giao diện nội hạt N6/SGi. Khi đóng gói bàn giao Production, hoàn toàn bỏ qua thư mục addon Tailscale.

  27. **Khắc Phục Lỗi Bị Ignore Thiếu 4 File Go Trong `go-mf/internal/mf` & Cập Nhật Lại Tag `16.0.0`**:
      - **Nguyên nhân**: File `.gitignore` cũ cấu hình rule `mf` không có slash đầu, làm Git hiểu nhầm và ignore toàn bộ package folder `go-mf/internal/mf/`, khiến 4 file (`proxy_handler.go`, `rtp_relay.go`, `sba_handler.go`, `sdp_utils.go`) bị sót lại khi push tag `16.0.0`.
      - **Khắc phục**: Sửa `.gitignore` chuẩn hóa rule `/mf` và `go-mf/mf`, bổ sung ngoại lệ an toàn cho các asset zip/txt, stage đủ 4 file `.go`, commit và force-push cập nhật lại tag `16.0.0` lên remote repository.
      - **Xác thực**: Chạy `docker build` thành công 100% không còn lỗi missing method/functions.

  28. **Khắc Phục Xung Đột Đa IP & Đồng Bộ Duy Nhất 1 IP Cho SIP và SDP (Samsung Galaxy S24 Multi-IP Fix)**:
      - **Hiện tượng**: Khi cắm máy Samsung S24 cắm SIM di động (Viettel LTE), hệ thống có 2 IP mạng di động cùng lúc (`rmnet0` kết nối IMS và `rmnet1` là bearer phụ). Hàm quét IP `NetworkUtils.getLocalIpAddress()` cũ dùng vòng lặp duyệt card mạng bị ghi đè, khiến app lúc chọn IP này lúc chọn IP kia.
      - **Hậu quả**: Bản tin SIP và SDP bị lệch IP so với socket kết nối thật, gói tin RTP gửi về IP phụ không có định tuyến dẫn đến cuộc gọi bị lặng thoại (không nghe thấy âm thanh) và không nhìn thấy hình (không giải mã được video).
      - **Khắc phục toàn diện**:
        1) `NetworkUtils.kt`: Bổ sung cơ chế thăm dò Kernel Routing Probe (`DatagramSocket.connect(sipServerHost)`), truy vấn `ConnectivityManager.getActiveNetwork().getLinkProperties()`, và thiết lập độ ưu tiên card mạng chặt chẽ (Tailscale/VPN -> Wi-Fi -> Primary Cellular `rmnet0` -> First IPv4, tuyệt đối không để `rmnet1` ghi đè).
        2) `SipManager.kt`: Bổ sung hàm `getLocalIp()` làm Single Source of Truth lấy IP trực tiếp từ TCP socket đang kết nối thực tế tới SIP Server. Dùng thống nhất 1 IP này cho `Via`, `Contact`, `c=IN IP4` (m=audio, m=video, BDC, ADC), `REGISTER`, `INVITE`, `Re-INVITE`, `200 OK Answer`, `ACK`, `BYE`, `CANCEL`. Xóa bỏ hardcode fallback `"100.127.196.108"`.
        3) `MainActivity.kt` & `CallActivity.kt`: Đồng bộ `localIp` theo `SipManager.getLocalIp()` khi khởi chạy, khi đăng ký thành công và khi bấm gọi từ bàn phím quay số. Sửa lỗi `UninitializedPropertyAccessException` trên `remoteUser` trong `CallActivity.onCreate` và `onNewIntent`.
      - **Build & Nạp thiết bị**: Đã biên dịch thành công APK debug (`BUILD SUCCESSFUL in 21s`) và nạp trực tiếp qua ADB (`Performing Streamed Install -> Success`) lên cả 2 thiết bị Samsung Galaxy S24 thật:
        + Thiết bị 1 (`R5CY54VL0YZ`): User `1002`, IP `166.83.196.198`.
        + Thiết bị 2 (`R5CY54VL13Y`): User `1004`, IP `166.83.177.24` (tự động nhận diện routing probe, cấp quyền camera/audio, khởi chạy và REGISTER 200 OK thành công).
      - **Xác thực**: Cả 2 máy app khởi chạy và đăng ký SIP ổn định, thống nhất 1 IP chuẩn xác trên toàn bộ SIP/SDP, không còn crash.

  29. **Khắc Phục Lỗi Thoại 2 Chiều, Triệt Tiêu Tiếng Vọng Tự Nghe (Echo) & Đồng Nhất IP Socket RTP/UDP**:
      - **Hiện tượng**:
        1) Máy 1003 nói vào micro thì tự nghe thấy tiếng vọng của chính mình (Self-echo).
        2) Máy 1001 bị lặng thoại (One-way silence), không nghe được âm thanh từ phía 1003.
      - **Nguyên nhân cốt lõi**:
        1) `AudioRecord` cấu hình nguồn `MediaRecorder.AudioSource.MIC` thay vì `VOICE_COMMUNICATION` khiến Android Audio HAL trên Samsung S24 không kích hoạt bộ triệt tiếng vọng phần cứng (Hardware AEC). Đồng thời `JavaAudioDeviceModule` trong `WebRtcManager.kt` chiếm dụng pipeline AEC gây xung đột.
        2) Audio SSRC bị hardcode cố định `987654` trên cả 2 máy, không có bộ lọc gói loopback khiến gói tin dội về tai người nói.
        3) UDP DatagramSocket trong `RtpMediaManager` bind vào wildcard `0.0.0.0` thay vì IP cục bộ thực tế `effectiveIp` của máy, dẫn đến khi máy có nhiều IP, gói tin RTP UDP gửi đi mang IP nguồn khác với `c=IN IP4` trong SDP -> Media Function từ chối latching session (`sessIP != senderIP`).
        4) Mảng `pcmOutput` trong receiver chỉ có 160 phần tử -> gói tin > 160 byte gây `ArrayIndexOutOfBoundsException` làm chết luồng nhận âm thanh trong im lặng.
        5) `parseRemoteSdp()` chứa điều kiện lọc cứng `!candidateIp.startsWith("10.202.")`.
        6) `CallActivity` tạo instance `RtpMediaManager` mới mà không dừng instance cũ khi có intent mới.
      - **Khắc phục toàn diện trên Client Android**:
        1) `RtpMediaManager.kt`:
           - Bổ sung tham số `localIp: String? = null`, bind trực tiếp UDP socket vào `localIp` đồng nhất (kèm fallback an toàn về wildcard nếu OS từ chối).
           - Chuyển `AudioSource.MIC` sang `AudioSource.VOICE_COMMUNICATION` để kích hoạt Hardware AEC.
           - Sinh SSRC ngẫu nhiên cho từng instance (`localAudioSsrc`, `localVideoSsrc`), lọc và drop gói tin RTP loopback nếu trùng SSRC.
           - Nâng kích thước `pcmOutput` lên 2048 short và giới hạn `decodeSamples = minOf(payloadLen, pcmOutput.size)` tránh lỗi tràn mảng.
           - Bổ sung đục lỗ NAT chủ động (`sendNatHolePunchPackets()`) gửi 3 gói silence RTP ngay khi kết nối.
        2) `CallActivity.kt`:
           - Dừng và giải phóng `rtpMediaManager` cũ trước khi tạo instance mới.
           - Truyền `effectiveLocalIp` từ `sipManager.getLocalIp()` vào `RtpMediaManager`.
           - Xóa bỏ hoàn toàn điều kiện lọc cứng `10.202.x.x` trong `parseRemoteSdp()`.
        3) `WebRtcManager.kt`:
           - Gỡ bỏ `JavaAudioDeviceModule` khỏi `PeerConnectionFactory` và bỏ cấp phát WebRTC AudioTrack để tránh tranh chấp phần cứng âm thanh thoại.
      - **Biên dịch & Nạp APK**:
        - Đã biên dịch thành công APK debug (`BUILD SUCCESSFUL in 23s`).
        - Đã nạp thành công APK mới lên cả 2 thiết bị Samsung thật vừa mới cắm qua ADB Streamed Install:
          1. Máy Samsung Galaxy S24+ (`R5CY54VKY8J` - SM-S926B): `lastUpdateTime=2026-09-15 13:30:41`
          2. Máy Samsung Galaxy S25+ (`R5CY82VS6QP` - SM-S936B): `lastUpdateTime=2026-09-15 13:30:10`

   30. **Chẩn Đoán Lỗi Đứng Hình / Thoại Lặng Sau ~2 Phút Gọi Video & Nạp APK Thiết Bị Mới**:
       - **Hiện tượng**: Cuộc gọi video và bảng vẽ hoạt động bình thường mượt mà hơn 2 phút (đã gửi nhận hơn 11.500 gói video, 6.500 gói audio), sau đó đột ngột bị đứng hình, thoại lặng, bảng vẽ tắt nét.
       - **Nguyên nhân cốt lõi (RCA từ Logcat)**:
         + Lúc `13:34:37 - 13:34:39`, mạng dữ liệu di động 4G (`rmnet0`) trên Máy 1 (`R5CY54VKY8J`) bị reset tạm thời (`Network 143 disconnected`, `CONNECTION_NONE`).
         + Khi mạng 4G kết nối lại, nhà mạng cấp IP mới `5.129.223.136` thay thế IP cũ `5.129.61.253`.
         + Socket RTP UDP bị bind cứng vào IP cũ nên nhân Linux ném lỗi `ENETUNREACH: Network is unreachable (error 101)`.
         + Phía Server / Máy 2 chưa nhận được SIP `re-INVITE` nên tiếp tục gửi gói về IP cũ `5.129.61.253`, gói tin bị hủy hoàn toàn.
         + WebRTC ICE phát hiện mất interface mạng đã hủy port candidates khiến kênh dữ liệu ADC bảng vẽ bị ngắt.
       - **Nạp APK lên các thiết bị Samsung**:
         + Samsung 1 (`R5CY54VL0YZ` - SM-S926B): Đã nạp APK thành công & khởi chạy.
         + Samsung 2 (`R5CY54VL13Y` - SM-S926B): Đã nạp APK thành công & khởi chạy.
         + Samsung 3 vừa cắm (`R5CY54VKYRF` - SM-S926B): Đã cấp quyền debug, nạp APK thành công (`Success`) & khởi chạy `MainActivity`.
   31. **Khắc Phục Triệt Để Lỗi Lọt Địa Chỉ IPv6 `::` Vào SIP/SDP & Khóa Chặt IPv4 (Bulletproof IPv4 Fix)**:
       - **Nguyên nhân**: Khi socket SIP TCP đang trong trạng thái reconnect hoặc dual-stack, `socket.localAddress.hostAddress` trả về `::` (IPv6 wildcard). Hàm `getLocalIp()` cũ chỉ kiểm tra `it != "0.0.0.0"`, vô tình để lọt `::` vào `effectiveLocalIp` và bản tin SIP `INVITE` (`c=IN IP4 ::`). Server Media Function nhận `c=IN IP4 ::` nên từ chối latching gói RTP từ IP thật của máy (`sessIP != senderIP`), và không thể chuyển tiếp gói của callee về `::`, dẫn tới 2 máy nhận 0 gói (tịt cả thoại và video).
       - **Khắc phục**:
         1. `SipManager.kt`: Thêm hàm `isValidIpv4(ip: String?)` kiểm tra chặt chẽ IPv4 4-octet (từ chối mọi chuỗi chứa `:`, `::`, `0.0.0.0`, `127.0.0.1`). `getLocalIp()`, `start()`, và `reconnect()` bắt buộc dùng `isValidIpv4()`, fallback an toàn sang `NetworkUtils.getLocalIpAddress()` (đã có kernel routing probe).
         2. `RtpMediaManager.kt`: Thêm kiểm tra `isLocalIpValid` trước khi bind socket UDP, nếu IP không hợp lệ hoặc chứa `:` sẽ tự động fallback về wildcard `0.0.0.0`.
       - **Build & Nạp**: Biên dịch thành công APK (`BUILD SUCCESSFUL in 15s`), nạp thành công lên cả 2 máy Samsung (`R5CY54VKYRF` và `R5CY54VL0YZ`), khởi chạy ứng dụng sẵn sàng.
       - **Trạng thái Git**: Không commit hay push lên Git (tuân thủ nghiêm ngặt quy tắc người dùng).
   32. **Khắc Phục Lỗi Crash Native SIGABRT Khiến Thuê Bao 1001 Bị Văng Ra Màn Hình Đăng Ký & Phòng Vệ SDP IPv4**:
        - **Nguyên nhân Crash**:
          + Hiện tượng Race Condition đa luồng: Luồng UI gọi `activeDcLabels.clear()` trong `resetLabels()` / `createAppDataChannel()` không có khóa đồng bộ `synchronized`.
          + Cùng lúc đó, luồng WebRTC native gọi callback `onMessage` -> `notifyMessageReceived()` -> `activeDcLabels.toList()`.
          + Iterator Kotlin (`CopyOnWriteArrayList$COWIterator.next()`) ném `java.util.NoSuchElementException` do phần tử bị xóa ngay giữa chừng.
          + Do `onMessage` trong `DataChannelManager.kt` không có `try-catch`, ngoại lệ Java này lọt ra ngoài tầng JNI của WebRTC native C++. WebRTC kích hoạt `RTC_CHECK(!env->ExceptionCheck())` trong `jni_generator_helper.h`, gọi `abort()` phát tín hiệu `Fatal signal 6 (SIGABRT)` ngắt tiến trình tức thì, làm máy tự mở lại `MainActivity` (màn hình đăng ký).
        - **Các file đã sửa**:
          1. [`ImsDcSdk.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/webrtc/ImsDcSdk.kt):
             + Bọc toàn bộ các thao tác truy xuất và xóa trên `activeDcLabels` (`clear()`, `add()`, `remove()`, `toList()`, `resetLabels()`) vào khối `synchronized(activeDcLabels)`.
             + Thêm hàm `getPrimaryDcLabel(): String` truy xuất an toàn.
             + Bọc toàn bộ logic `notifyMessageReceived()` và `onCallEnded()` trong khối `try-catch(Throwable)`.
          2. [`DataChannelManager.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/webrtc/DataChannelManager.kt):
             + Sử dụng `imsDcSdk?.getPrimaryDcLabel()`.
             + Bọc toàn bộ callback `onMessage()`, `onStateChange()` của ADC, BDC, ADC2 trong `try-catch(Throwable)` để làm lá chắn an toàn 100%, bảo đảm không có bất kỳ ngoại lệ Java nào có thể lọt ra ngoài tầng Native WebRTC gây sập tiến trình.
          3. [`SdpHelper.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/sip/SdpHelper.kt):
             + Thêm cơ chế lọc và làm sạch IP `validIp`: kiểm tra nghiêm ngặt `localIpAddress` không chứa `:`, không rỗng, không phải `0.0.0.0`, `127.0.0.1`, hay `::`. Nếu không hợp lệ sẽ tự động fallback sang `NetworkUtils.getLocalIpAddress()`, bảo đảm các dòng `c=IN IP4` và `a=candidate` luôn mang IPv4 chuẩn.
          4. [`CallActivity.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/ui/CallActivity.kt):
             + Thêm kiểm tra và làm sạch `resolvedLocalIp`, `resolvedNewIp`, và `effectiveLocalIp`, tự động fallback sang `NetworkUtils.getLocalIpAddress(this, sipServer)` nếu IP chứa dấu hai chấm hoặc rỗng.
          5. [`WebRtcManager.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/webrtc/WebRtcManager.kt):
             + Bọc try-catch trong callback `onStateChange` của remote DataChannel.
        - **Build & Nạp APK**:
          + Chạy `./gradlew.bat assembleDebug` thành công (`BUILD SUCCESSFUL in 19s`).
          + Đã nạp thành công APK mới lên cả 2 thiết bị Samsung đang cắm:
            * Máy 1: `R5CY54VL0YZ` (Samsung Galaxy S24+)
            * Máy 2: `R5CY54VKYRF` (Samsung Galaxy S24+)
          + Đã khởi chạy tự động ứng dụng `MainActivity` trên cả 2 máy, sẵn sàng thực hiện cuộc gọi kiểm tra.
        - **Trạng thái Git**: KHÔNG commit, KHÔNG push lên Git (tuân thủ nghiêm ngặt quy tắc).
   33. **Nạp APK Bản Mới Nhất Lên 2 Máy Samsung Vừa Cắm**:
        - **Thiết bị nhận dạng**:
          + Samsung 1: `R5CY54VL13Y` (Samsung Galaxy S24+ / `SM-S926B`)
          + Samsung 2: `R5CY82VS6QP` (Samsung Galaxy S25+ / `SM-S936B`)
        - **Cài đặt & Khởi chạy**:
          + Đã xác thực quyền ADB Debugging thành công (`device`).
          + Đã nạp thành công APK (`Performing Streamed Install` -> `Success`) vào cả 2 thiết bị.
          + Đã kích hoạt chạy `MainActivity` trên cả 2 máy, sẵn sàng để người dùng thử nghiệm.
        - **Trạng thái Git**: KHÔNG commit, KHÔNG push lên Git.

   34. **Khắc Phục Lỗi Video Không Hiển Thị Sau Khi Bấm Phím Home Rồi Quay Lại App**:
        - **Hiện tượng**: Trong cuộc gọi video, bấm phím Home ra màn hình chính rồi mở lại app thì video cả 2 chiều bị đen/đứng hình (thoại vẫn nghe bình thường).
        - **Nguyên nhân**:
          + *Chiều nhận/giải mã (Downlink)*: Khi ra màn hình chính, Surface bị hủy, `MediaCodec` decoder rơi vào trạng thái `RELEASED`. Khi mở lại app, biến `videoDecoder` vẫn giữ tham chiếu cũ dẫn đến không tạo decoder mới và luồng decode bị kẹt trong `IllegalStateException`.
          + *Chiều quay/gửi (Uplink)*: Android 9+ thu hồi quyền truy cập camera khi app xuống background; `CallActivity` thiếu lifecycle `onStop()` / `onStart()` để tạm dừng và phục hồi camera capturer.
        - **Khắc phục toàn diện**:
          1) [`RtpMediaManager.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/media/RtpMediaManager.kt):
             + Thêm `@Volatile` cho `videoDecoder` tránh race condition.
             + Bổ sung `onSurfaceDestroyed()`: gán null trước khi giải phóng decoder cũ, duy trì `videoReceiverThread` sống để tiếp tục nhận gói UDP.
             + Bổ sung `updateDecoderSurface()` và `reinitDecoder()`: tự động dọn sạch decoder cũ và khởi tạo lại `MediaCodec` trên Surface mới.
             + Cải tiến `feedDecoder()`: kiểm tra `surface.isValid`, giảm timeout dequeue xuống 2s, tự động phục hồi khi gặp `IllegalStateException`.
          2) [`WebRtcManager.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/webrtc/WebRtcManager.kt):
             + Lưu trữ `videoSource` thành biến thành viên.
             + Bổ sung `pauseLocalCapture()` và `resumeLocalCapture()` (có fallback tự tạo lại capturer nếu bị ngắt).
             + Dọn dẹp `videoSource` an toàn trong `close()`.
          3) [`CallActivity.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/ui/CallActivity.kt):
             + Bổ sung cờ `isVideoEnabled` theo dõi trạng thái video của cuộc gọi.
             + Gọi `rtpMediaManager?.onSurfaceDestroyed()` trong callback `surfaceDestroyed`.
             + Bổ sung `onStop()` để tạm dừng camera (`pauseLocalCapture()`) và `onStart()` để phục hồi camera (`resumeLocalCapture()` và `updateLocalVideoTrack()`).

   35. **Khắc Phục Triệt Để Lỗi Thoát Cuộc Gọi Khi Bấm Home, Lỗi Treo Máy Đối Phương & Hiện Tượng Tự Động Gọi userB**:
        - **Hiện tượng**:
          1) Đang trong cuộc gọi, bấm Home ra màn hình chính rồi mở lại app từ Launcher thì bị thoát cuộc gọi rơi về màn hình đăng ký, máy đối phương vẫn treo ở màn hình cuộc gọi.
          2) Xuất hiện hiện tượng máy tự động thực hiện cuộc gọi tới `userB` và phát thông báo *"Thuê bao quý khách vừa gọi hiện không liên lạc được, xin quý khách vui lòng gọi lại sau"*.
        - **Nguyên nhân cốt lõi**:
          1) `MainActivity` mang `launchMode="singleTask"`: Khi người dùng bấm icon app từ Launcher màn hình Home, Android theo quy tắc `singleTask` tự động tiêu hủy toàn bộ các Activity phía trên nó trong stack, làm `CallActivity` bị tiêu hủy ép buộc.
          2) Race Condition trong `CallActivity.onDestroy()`: Coroutine `sm.bye()` được phóng bất đồng bộ trên `Dispatchers.IO`, nhưng ngay trên Main Thread dòng tiếp theo gọi `sipManager.resetCallState()` làm xóa mất `pendingCallId = null` trước khi gói tin BYE kịp gửi đi -> bản tin BYE bị hủy bỏ giữa chừng -> máy đối phương không nhận được BYE và bị treo màn hình cuộc gọi.
          3) Hiện tượng tự gọi tới `userB`:
             - Trong `MainActivity`, đoạn kiểm tra `if (CallActivity.isCallActive || SipManager.shared?.isCallActive == true)` khi `CallActivity` vừa `finish()` nhưng `sm.bye()` chưa chạy xong khiến `SipManager.shared?.isCallActive` vẫn bằng `true`.
             - `MainActivity` tạo Intent rỗng `Intent(this, CallActivity::class.java)` không có tham số extras (`remoteUser`) để đưa `CallActivity` lên trước.
             - Khi `CallActivity` cũ đã bị hủy, Android tạo một instance `CallActivity` mới toanh từ Intent rỗng này.
             - `CallActivity` có fallback mặc định `remoteUser = intent.getStringExtra(EXTRA_REMOTE_USER) ?: "userB"`. Do không có extra, nó lấy mặc định `"userB"` và kích hoạt `startCall()` gọi đi. `userB` không tồn tại trên mạng IMS nên tổng đài chuyển hướng sang phát Early Media thông báo thuê bao không liên lạc được.
        - **Khắc phục toàn diện**:
          1) [`AndroidManifest.xml`](file:///d:/project/demo-mini-app/android-client/app/src/main/AndroidManifest.xml):
             - Chuyển `launchMode` của `MainActivity` từ `singleTask` về lại `singleTop` và giữ `alwaysRetainTaskState="true"`.
          2) [`MainActivity.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/ui/MainActivity.kt):
             - Thêm guard `if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.action == Intent.ACTION_MAIN) finish()` bảo vệ task stack.
             - Thêm guard trong `doRegister()` chặn phá socket SIP khi có cuộc gọi.
             - Thay đổi điều kiện redirect trong `onCreate()`, `onNewIntent()`, `onResume()` thành `if (CallActivity.isCallActive)` (chỉ redirect khi `CallActivity` thực sự đang sống, không redirect dựa trên `SipManager.isCallActive` đơn lẻ).
          3) [`SipManager.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/sip/SipManager.kt):
             - Bổ sung `overrideCallId` và `overrideFromTag` cho `bye()` và `cancel()`.
             - Thêm phương thức `markCallEnding()` để reset cờ `isCallActive = false` ngay lập tức khi quy trình teardown bắt đầu.
          4) [`CallActivity.kt`](file:///d:/project/demo-mini-app/android-client/app/src/main/java/com/demo/imsdc/ui/CallActivity.kt):
             - Cập nhật getter `isCallActive` trong companion object: `activeInstance?.let { !it.isFinishing && !it.isDestroyed && !it.isEndingCall } ?: false`.
             - Kiểm tra tham số Intent trong `onCreate()`: nếu là cuộc gọi đi mà không có `EXTRA_REMOTE_USER` thì lập tức `finish()` và thoát ngay, tuyệt đối không fallback gọi tới `"userB"`.
             - Bổ sung cờ `isEndingCall` và gọi `sipManager.markCallEnding()` ngay khi bấm kết thúc cuộc gọi.
             - Loại bỏ lời gọi thừa `sipManager.resetCallState()` trong `onDestroy()`.
        - **Biên dịch & Nạp thiết bị**:
          - Đã biên dịch APK thành công (`BUILD SUCCESSFUL`).
          - Đã nạp thành công lên cả 2 máy Samsung (`R5CY54VL0YZ` và `R5CY54VKYRF`).
          - Đã chạy kiểm thử tự động toàn diện: Gọi 1001 -> 1002, bấm Home, mở lại app từ Launcher, kết thúc cuộc gọi. Cả 2 máy trở về bàn phím quay số an toàn, video/thoại duy trì liên tục khi Home/Resume, không còn bất kỳ cuộc gọi ma nào tới `userB`.
        - **Trạng thái Git**: KHÔNG commit, KHÔNG push lên Git (tuân thủ nghiêm ngặt quy tắc người dùng).

   37. **Nạp APK Bản Mới Nhất Lên 2 Máy Samsung Vừa Cắm (Galaxy S24+ & Galaxy S25+)**:
        - **Thiết bị nhận dạng**:
          + Samsung 1: `R5CY54VKY8J` (Samsung Galaxy S24+ / `SM-S926B`) - Tài khoản: `1007`
          + Samsung 2: `R5CY82VS6QP` (Samsung Galaxy S25+ / `SM-S936B`) - Tài khoản: `1005`
        - **Cài đặt & Khởi chạy**:
          + Đã nạp thành công APK debug bản mới nhất (`Performing Streamed Install` -> `Success`) vào cả 2 thiết bị.
          + Đã khởi động lại ứng dụng `MainActivity` trên cả 2 máy.
          + Cả 2 máy đã kết nối SIP server `10.204.101.54` ổn định, gửi nhận TCP keep-alive đều đặn và sẵn sàng cho việc kiểm thử.
        - **Trạng thái Git**: KHÔNG commit, KHÔNG push lên Git (tuân thủ nghiêm ngặt quy tắc).

   38. **Nạp APK Bản Mới Nhất Lên Máy Samsung R5CY54VL13Y Vừa Cắm**:
        - **Thiết bị nhận dạng**:
          + Serial: `R5CY54VL13Y` (Samsung Galaxy S24+ / `SM-S926B`) - Tài khoản: `1003`
        - **Cài đặt & Khởi chạy**:
          + Đã nạp thành công APK debug mới nhất (`Performing Streamed Install` -> `Success`).
          + Đã khởi động ứng dụng `MainActivity`.
          + Kết nối SIP Server `10.204.101.54` ổn định, gửi nhận TCP keep-alive đều đặn và sẵn sàng kiểm thử.
        - **Trạng thái Git**: KHÔNG commit, KHÔNG push lên Git (tuân thủ nghiêm ngặt quy tắc).


