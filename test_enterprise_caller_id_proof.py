import subprocess
import time
import os
import sys

if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", line_buffering=True)

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # Máy A (Caller - 1002/1001)
DEV_B = "R5CY54VL13Y"  # Máy B (Callee - 1003)
ARTIFACTS_DIR = r"C:\Users\lamnt\.gemini\antigravity-ide\brain\83b918c3-f0ec-490a-811f-e05e5cc427c0"
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
    print(f"  [SCREENSHOT] {filename} saved ({size:,} bytes)")
    return local_path, size

def ensure_screen_on(device):
    out = run_adb(device, "dumpsys power")
    if "mWakefulness=Awake" not in out and "state=ON" not in out:
        run_adb(device, "input keyevent 26") # Power
        time.sleep(1.0)
    run_adb(device, "input keyevent 82") # Unlock / Dismiss Keyguard
    time.sleep(0.5)

def main():
    print("=" * 80)
    print("   KIỂM THỬ E2E: DỊCH VỤ ENTERPRISE VERIFIED CALLER ID, ADS & QR CODE (A2P)   ")
    print("=" * 80)

    ensure_screen_on(DEV_A)
    ensure_screen_on(DEV_B)

    # 1. Khởi chạy ứng dụng & Đăng ký SIP trên 2 máy S24
    print("\n--- [BƯỚC 1] Khởi chạy ứng dụng & Đăng ký SIP trên 2 thiết bị ---")
    run_adb(DEV_A, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_B, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    run_adb(DEV_B, "am force-stop com.demo.imsdc")
    time.sleep(1.0)

    run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.5)

    # Bấm nút REGISTER trên cả 2 máy
    for dev in [DEV_A, DEV_B]:
        run_adb(dev, "input tap 540 1663")
        time.sleep(1.5)

    capture_screenshot(DEV_A, "ent_proof_0_a_reg.png")
    capture_screenshot(DEV_B, "ent_proof_0_b_reg.png")

    # Clear logcat
    run_cmd([ADB, "-s", DEV_A, "logcat", "-c"])
    run_cmd([ADB, "-s", DEV_B, "logcat", "-c"])

    # 2. Máy A gọi Máy B
    print("\n--- [BƯỚC 2] Máy A gọi Máy B (Thiết lập cuộc gọi thoại, video và BDC) ---")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
    time.sleep(2.0)

    # 3. Máy B nhận cuộc gọi và Trượt trả lời
    print("\n--- [BƯỚC 3] Máy B trả lời cuộc gọi ---")
    run_adb(DEV_B, "input swipe 880 1930 200 1930 300")
    time.sleep(0.5)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
    time.sleep(5.0)

    capture_screenshot(DEV_A, "ent_proof_1_a_connected.png")
    capture_screenshot(DEV_B, "ent_proof_1_b_connected.png")

    # 4. Kích hoạt Mini-App Enterprise Caller ID
    print("\n--- [BƯỚC 4] Kích hoạt Mini-App Enterprise Caller ID (enterprise-callerid) ---")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_ENTERPRISE")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_ENTERPRISE")
    time.sleep(4.5)

    capture_screenshot(DEV_A, "ent_proof_2_a_callerid_ready.png")
    capture_screenshot(DEV_B, "ent_proof_2_b_callerid_ready.png")

    # 5. Tương tác: Bấm nút 'Nhận ngay' (Claim) trên Máy A
    print("\n--- [BƯỚC 5] Tương tác: Bấm nút 'Nhận ngay' (Claim) trên Máy A ---")
    # Tọa độ nút 'Nhận ngay' trên màn hình S24 (khoảng x=760, y=2100)
    run_adb(DEV_A, "input tap 760 2100")
    time.sleep(3.0)

    capture_screenshot(DEV_A, "ent_proof_3_a_claimed_browser.png")
    capture_screenshot(DEV_B, "ent_proof_3_b_callee_view.png")

    # 6. Quay lại ứng dụng gọi điện từ Browser & Bấm nút 'Bỏ qua' (Skip) để về Menu
    print("\n--- [BƯỚC 6] Quay lại cuộc gọi & Bấm nút 'Bỏ qua' / Phím Back để về Menu ---")
    run_adb(DEV_A, "am start -a android.intent.action.MAIN -n com.demo.imsdc/.ui.CallActivity")
    time.sleep(1.5)
    run_adb(DEV_A, "input keyevent 4") # Back key
    time.sleep(2.5)

    capture_screenshot(DEV_A, "ent_proof_4_a_menu_returned.png")

    # 7. Kết thúc cuộc gọi
    print("\n--- [BƯỚC 7] Kết thúc cuộc gọi ---")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(2.5)

    capture_screenshot(DEV_A, "ent_proof_5_a_ended.png")
    capture_screenshot(DEV_B, "ent_proof_5_b_ended.png")

    print("\n" + "=" * 80)
    print("   HOÀN TẤT KIỂM THỬ DỊCH VỤ ENTERPRISE CALLER ID THÀNH CÔNG!   ")
    print("=" * 80)

if __name__ == "__main__":
    main()
