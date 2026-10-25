import subprocess
import time
import os
import sys

if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", line_buffering=True)

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # Caller 1002
DEV_B = "R5CY54VL13Y"  # Callee 1003
SIP_SERVER = "100.76.76.105"
IP_A = "100.119.80.116"
IP_B = "100.127.196.108"

ARTIFACTS_DIR = r"C:\Users\lamnt\.gemini\antigravity-ide\brain\b93511c7-0f55-4051-83f8-964a3715f4a6"
os.makedirs(ARTIFACTS_DIR, exist_ok=True)

def run_cmd(args):
    res = subprocess.run(args, capture_output=True, text=True, errors="ignore")
    return res.stdout.strip()

def run_adb(device, shell_cmd):
    return run_cmd([ADB, "-s", device, "shell"] + shell_cmd.split())

def capture_screenshot(device, filename):
    remote_path = f"/sdcard/{filename}"
    local_path = os.path.join(ARTIFACTS_DIR, filename)
    run_adb(device, f"screencap -p {remote_path}")
    subprocess.run([ADB, "-s", device, "pull", remote_path, local_path], capture_output=True)
    size = os.path.getsize(local_path) if os.path.exists(local_path) else 0
    print(f"  📸 [SCREENSHOT] {device} -> {filename} ({size:,} bytes)")
    return local_path, size

def wake_device(device):
    out = run_adb(device, "dumpsys power")
    if "mWakefulness=Awake" not in out and "state=ON" not in out:
        run_adb(device, "input keyevent 26") # Power
        time.sleep(0.5)
    run_adb(device, "input keyevent 82") # Unlock
    time.sleep(0.3)

def main():
    print("=" * 80)
    print("   TEST CHẨN ĐOÁN CUỘC GỌI 1002 GỌI 1003 VÀ 1003 TRẢ LỜI (ANSWER)   ")
    print("=" * 80)

    # 1. Đánh thức cả 2 thiết bị
    print("\n1. Đánh thức 2 thiết bị Samsung S24...")
    wake_device(DEV_A)
    wake_device(DEV_B)

    # 2. Xóa logcat cũ và khởi động app trên cả 2 máy
    print("\n2. Đăng ký SIP trên 2 thiết bị...")
    for dev in [DEV_A, DEV_B]:
        run_adb(dev, "am force-stop com.samsung.android.dialer")
        run_adb(dev, "am force-stop com.demo.imsdc")
        run_adb(dev, "logcat -c")

    # Mở MainActivity trên B (1003)
    run_adb(DEV_B, "am start -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.5)

    # Mở MainActivity trên A (1002)
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.5)

    # 3. Khởi tạo cuộc gọi từ A (1002) sang B (1003)
    print("\n3. Thiết bị A (1002) gọi tới B (1003)...")
    call_cmd_A = (
        f"am start -n com.demo.imsdc/.ui.CallActivity "
        f"--es sip_server {SIP_SERVER} "
        f"--es local_user 1002 "
        f"--es remote_user 1003 "
        f"--es local_ip {IP_A} "
        f"--ei local_port 0 "
        f"--ei sip_port 5060 "
        f"--ez is_incoming false"
    )
    run_adb(DEV_A, call_cmd_A)
    time.sleep(3.0)

    # Chụp ảnh chuông đang reo trên cả 2 máy
    capture_screenshot(DEV_A, "proof_p2p_1_caller_ringing.png")
    capture_screenshot(DEV_B, "proof_p2p_2_callee_incoming.png")

    # 4. Thiết bị B (1003) bấm TRẢ LỜI (ANSWER)
    print("\n4. Thiết bị B (1003) bấm Trả lời cuộc gọi (ANSWER)...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
    time.sleep(4.0)

    # 5. Chụp ảnh trạng thái kết nối đàm thoại trên cả 2 máy
    print("\n5. Kiểm tra trạng thái kết nối đàm thoại...")
    capture_screenshot(DEV_A, "proof_p2p_3_caller_connected.png")
    capture_screenshot(DEV_B, "proof_p2p_4_callee_connected.png")

    # 6. Lấy log SIP & WebRTC từ cả 2 thiết bị
    print("\n6. Thu thập logcat từ cả 2 thiết bị...")
    log_A = run_adb(DEV_A, "logcat -d")
    log_B = run_adb(DEV_B, "logcat -d")

    print("\n--- LOG TÓM TẮT MÁY A (1002) ---")
    for line in log_A.splitlines():
        if any(k in line for k in ["SipManager", "CallActivity", "WebRtcManager", "200 OK", "ACK", "BYE", "ICE connection state"]):
            if "keep-alive" not in line and "double CRLF" not in line:
                print("  [A]", line)

    print("\n--- LOG TÓM TẮT MÁY B (1003) ---")
    for line in log_B.splitlines():
        if any(k in line for k in ["SipManager", "CallActivity", "WebRtcManager", "200 OK", "ACK", "BYE", "ICE connection state", "handleIncomingCall"]):
            if "keep-alive" not in line and "double CRLF" not in line:
                print("  [B]", line)

    # 7. Kết thúc cuộc gọi
    print("\n7. Kết thúc cuộc gọi và chuyển màn hình về chế độ nghỉ...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(1.0)
    run_adb(DEV_A, "input keyevent 3")
    run_adb(DEV_B, "input keyevent 3")
    run_adb(DEV_A, "input keyevent 26")
    run_adb(DEV_B, "input keyevent 26")

    print("\n" + "=" * 80)
    print("   HOÀN TẤT CHẨN ĐOÁN!   ")
    print("=" * 80)

if __name__ == "__main__":
    main()
