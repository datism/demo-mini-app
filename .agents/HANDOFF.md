# BÁO CÁO LƯU BỐI CẢNH DỰ ÁN (HANDOFF CONTEXT REPORT)
**Thời gian khởi tạo:** 2026-08-04T21:03:00+07:00
**Mục đích:** Đóng gói toàn bộ bối cảnh hệ thống, mã nguồn local, hạ tầng K8s và lịch sử lỗi để tiếp tục làm việc trong phiên chat mới.

---

## 1. Môi trường & Hạ tầng Hóa

### A. Thiết bị Android Test (2 máy Samsung Galaxy S24)
- **Thiết bị 1 (Caller 1001)**:
  - ADB Serial ID: `R5CY54VL09X`
  - Tailscale IP: `100.127.196.108`
- **Thiết bị 2 (Callee 1002)**:
  - ADB Serial ID: `R5CY54VL13Y`
  - Tailscale IP: `100.119.80.116`
- **Môi trường Java**: JDK 17 (`C:\Users\lamnt\.jdks\jdk-17.0.10+7`)
- **Lệnh build APK**: `cd android-client; ./gradlew assembleDebug; cd ..`

### B. Cụm K8s Server & Tailscale Network Topology
- **P-CSCF (Erlang SIP Server)**:
  - Container IP (Macvlan `net1`): `10.202.0.230`
  - SIP Port: `5060` (TCP & UDP)
- **Media Function (Go MF)**:
  - Container IP (Macvlan `net1`): `10.202.0.229`
  - RTP/DTLS Port range: `40000:40010` (UDP)
- **Tailscale Router Pod (`tailscale-router`)**:
  - IP Tailscale động hiện tại: **`100.93.28.107`**
  - **Cấu hình DNAT & MASQUERADE trong Pod Router**:
    - `100.93.28.107:5060` (TCP/UDP) -> `10.202.0.230:5060` (P-CSCF)
    - `100.93.28.107:40000-40010` (UDP) -> `10.202.0.229` (Media Function)
    - Postrouting rule: `iptables -t nat -A POSTROUTING -j MASQUERADE`

---

## 2. Trạng thái Mã nguồn Local hiện tại (Chưa Push Git)

Toàn bộ code đang nằm ở **Local (chưa commit hay push lên Git trong 2 ngày qua)**:
- **`android-client/`**:
  - `ImsDcSdk.kt`: Đã cập nhật cơ chế phát tin đa ứng dụng (multi-label broadcast) cho `notifyDataChannelState` và `notifyMessageReceived` hỗ trợ `fun-calling-app`, `tic-tac-toe-app`, `sketch-sync-app`, `control_0003210000000038_0`, `local_0003210000000038_1_data`.
  - `CallActivity.kt`: Đã cập nhật xử lý SDP answer/offer, chuyển đổi camera, bề mặt hiển thị video.
  - `RtpMediaManager.kt`: Quản lý luồng RTP mã hóa H264 (Video) & PCMA (Audio) gửi nhận qua UDP socket.
  - `SipManager.kt`: Quản lý kết nối SIP TCP 5060, duy trì Keep-Alive (double CRLF), xử lý REGISTER & INVITE.
  - `gradle.properties`: Đã cấu hình `org.gradle.java.home=C:\\Users\\lamnt\\.jdks\\jdk-17.0.10+7`.
  - `network_security_config.xml`: Đã sửa chuẩn định dạng XML.
- **`erlang-sip/`**: Quản lý SIP Registration, Proxy, SCSCF/P-CSCF routing.
- **`go-mf/`**: Quản lý Media Function, B2BUA, SDP rewriting và NAT latching rtp/dtls proxy.

---

## 3. Lịch sử Đã Kiểm tra & Kết quả Thực tế

1. **Đăng ký SIP (SIP Registration)**:
   - **KẾT QUẢ**: **THÀNH CÔNG 100%** (`✅ REGISTER OK`).
   - Cả 2 máy S24 kết nối đến IP Tailscale `100.93.28.107:5060` qua TCP và nhận response `200 OK` từ Erlang SIP Server.

2. **Khởi tạo cuộc gọi (SIP INVITE & 200 OK)**:
   - Lệnh gọi 1001 -> 1002:
     `adb -s R5CY54VL09X shell am broadcast -a com.demo.imsdc.CALL --es target 1002`
   - Lệnh trả lời trên 1002:
     `adb -s R5CY54VL13Y shell am broadcast -a com.demo.imsdc.ANSWER`

3. **Vấn đề Cần Chẩn đoán Tiếp theo ở Phiên Mới**:
   - **Hiện tượng**: Trong cuộc gọi video, packet video H264 RTP đã được mã hóa và gửi đi (`Video Packets Sent: >30000`), tuy nhiên hình ảnh remote trên 2 màn hình chưa hiển thị / bị đen.
   - **Nguyên nhân chính đã xác minh**: 
     - Hàm `parseRemoteSdp()` trong `CallActivity.kt` khi lọc IP candidate SDP (`c=IN IP4 10.202.x.x`) đã fallback về IP SIP Server thay vì map đúng IP Tailscale Router (`100.93.28.107`) hoặc IP Media Function (`100.97.188.78`).
     - Cần tinh chỉnh IP destination trong `RtpMediaManager.kt` để gói tin RTP Video đẩy thẳng về Media Function UDP Proxy hoặc Tailscale IP của máy đối phương.

---

## 4. Lệnh Khởi tạo Nhanh cho Phiên Mới

Khi bắt đầu box chat mới, chỉ cần chạy các lệnh sau để kiểm tra hệ thống:
```powershell
# 1. Kiểm tra IP Tailscale Router mới nhất
kubectl exec -n demo-mini-app (kubectl get pods -n demo-mini-app -l app=tailscale -o jsonpath='{.items[0].metadata.name}') -- tailscale ip -4

# 2. Khởi động App & Đăng ký SIP trên 2 máy (thay IP_TAILSCALE bằng IP thu được ở lệnh 1)
& $env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe -s R5CY54VL09X shell am start -S -n com.demo.imsdc/.ui.MainActivity --es sip_server <IP_TAILSCALE> --es username 1001 --es remote_user 1002 --es local_ip 100.127.196.108
& $env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe -s R5CY54VL13Y shell am start -S -n com.demo.imsdc/.ui.MainActivity --es sip_server <IP_TAILSCALE> --es username 1002 --es remote_user 1001 --es local_ip 100.119.80.116
```
