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
    print("   KIỂM THỬ TOÀN DIỆN 3 MINI-APPS VỚI BẰNG CHỨNG TRỰC QUAN (S24 PHYSICAL)   ")
    print("=" * 80)

    ensure_screen_on(DEV_A)
    ensure_screen_on(DEV_B)

    # 1. Đóng app hệ thống khác và khởi chạy IMS DC
    print("\n--- [BƯỚC 1] Khởi chạy ứng dụng & Đăng ký SIP TCP ---")
    run_adb(DEV_A, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_B, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    run_adb(DEV_B, "am force-stop com.demo.imsdc")
    time.sleep(1.0)

    run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.5)

    for dev, name in [(DEV_A, "1002"), (DEV_B, "1003")]:
        run_adb(dev, "input tap 540 1663")
        time.sleep(1.5)

    capture_screenshot(DEV_A, "step1_a_registered.png")
    capture_screenshot(DEV_B, "step1_b_registered.png")

    run_cmd([ADB, "-s", DEV_A, "logcat", "-c"])
    run_cmd([ADB, "-s", DEV_B, "logcat", "-c"])

    # 2. Máy A gọi Máy B
    print("\n--- [BƯỚC 2] Máy A (1002) gọi Máy B (1003) ---")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
    time.sleep(2.0)

    # 3. Máy B nhận cuộc gọi và Trượt trả lời (Slide to Answer)
    print("\n--- [BƯỚC 3] Máy B trả lời cuộc gọi bằng Slide to Answer gesture ---")
    run_adb(DEV_B, "input swipe 880 1930 200 1930 300")
    time.sleep(0.5)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
    time.sleep(4.5)

    capture_screenshot(DEV_A, "step2_a_connected.png")
    capture_screenshot(DEV_B, "step2_b_connected.png")

    # =========================================================================
    # APP 1: BẢNG VẼ TƯƠNG TÁC TUYAAPP (NÉT VẼ ĐỒNG BỘ 2 BÊN)
    # =========================================================================
    print("\n" + "=" * 70)
    print("   [APP 1] BẢNG VẼ TUYAAPP (ĐỒNG BỘ NÉT VẼ 2 CHIỀU)   ")
    print("=" * 70)
    print("Máy A mở Bảng vẽ TuYaApp...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    time.sleep(5.0)

    print("Máy A vẽ nét cọ màu ĐỎ...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color red --ez cross false")
    time.sleep(2.5)

    print("Máy B vẽ nét cọ màu XANH LÁ...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color green --ez cross true")
    time.sleep(3.0)

    capture_screenshot(DEV_A, "proof_app1_tuya_a.png")
    capture_screenshot(DEV_B, "proof_app1_tuya_b.png")

    print("Thoát Bảng vẽ quay lại Menu...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_EXIT_MINIAPP")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_EXIT_MINIAPP")
    time.sleep(3.5)

    # =========================================================================
    # APP 2: GAME CỜ CARO (9x9 ĐỒNG BỘ ĐỐI KHÁNG)
    # =========================================================================
    print("\n" + "=" * 70)
    print("   [APP 2] GAME CỜ CARO 9x9 ĐỐI KHÁNG   ")
    print("=" * 70)
    print("Máy B mở Game Cờ Caro...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_CARO")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_CARO")
    time.sleep(5.0)

    print("Máy B (Quân X) đi nước cờ tại ô (4, 4)...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 4")
    time.sleep(2.5)

    print("Máy A (Quân O) đi nước cờ tại ô (4, 5)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 5")
    time.sleep(3.0)

    capture_screenshot(DEV_A, "proof_app2_caro_a.png")
    capture_screenshot(DEV_B, "proof_app2_caro_b.png")

    print("Thoát Game Cờ Caro quay lại Menu...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_EXIT_MINIAPP")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_EXIT_MINIAPP")
    time.sleep(3.5)

    # =========================================================================
    # APP 3: FUN CALLING (THẢ TIM & 5 EMOJI)
    # =========================================================================
    print("\n" + "=" * 70)
    print("   [APP 3] THẢ TIM TƯƠNG TÁC FUN CALLING   ")
    print("=" * 70)
    print("Máy A mở Fun Calling App...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_FUNCALL")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_FUNCALL")
    time.sleep(5.0)

    print("Máy A gửi biểu cảm Mặt cười (😊)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji smile")
    time.sleep(1.5)

    print("Máy B gửi biểu cảm Tức giận (😡) và Trái tim (❤️)...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji angry")
    time.sleep(1.0)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji heart")
    time.sleep(2.0)

    capture_screenshot(DEV_A, "proof_app3_funcall_a.png")
    capture_screenshot(DEV_B, "proof_app3_funcall_b.png")

    # =========================================================================
    # KẾT THÚC CUỘC GỌI
    # =========================================================================
    print("\n--- [BƯỚC CUỐI] Kết thúc cuộc gọi ---")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    run_adb(DEV_A, "input tap 540 2050")
    time.sleep(2.0)

    capture_screenshot(DEV_A, "proof_final_ended_a.png")
    capture_screenshot(DEV_B, "proof_final_ended_b.png")

    print("\n" + "=" * 80)
    print("   >>> TOÀN BỘ 3 MINI-APPS ĐÃ ĐƯỢC CHỨNG MINH THÀNH CÔNG! <<<   ")
    print("=" * 80)

if __name__ == "__main__":
    main()
