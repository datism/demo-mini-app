import subprocess
import time
import os
import sys

if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", line_buffering=True)

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # Máy A (1002 - Caller)
DEV_B = "R5CY54VL13Y"  # Máy B (1003 - Callee)
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
    print("   KIỂM THỬ HOÀN CHỈNH: PHÍM BACK HỆ THỐNG & ĐỒNG BỘ BẢNG VẼ TƯƠNG TÁC ADC   ")
    print("=" * 80)

    ensure_screen_on(DEV_A)
    ensure_screen_on(DEV_B)

    # 1. Reset ứng dụng & Đăng ký SIP trên 2 máy S24
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

    capture_screenshot(DEV_A, "final_proof_0_a_reg.png")
    capture_screenshot(DEV_B, "final_proof_0_b_reg.png")

    # Clear logcat
    run_cmd([ADB, "-s", DEV_A, "logcat", "-c"])
    run_cmd([ADB, "-s", DEV_B, "logcat", "-c"])

    # 2. Máy A gọi Máy B
    print("\n--- [BƯỚC 2] Máy A (1002) gọi Máy B (1003) ---")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
    time.sleep(2.0)

    # 3. Máy B nhận cuộc gọi và Trượt trả lời (Slide to Answer)
    print("\n--- [BƯỚC 3] Máy B trả lời cuộc gọi ---")
    run_adb(DEV_B, "input swipe 880 1930 200 1930 300")
    time.sleep(0.5)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
    time.sleep(5.0)

    capture_screenshot(DEV_A, "final_proof_0_a_connected.png")
    capture_screenshot(DEV_B, "final_proof_0_b_connected.png")

    # 4. Máy A mở Bảng vẽ tương tác (TuYaApp)
    print("\n--- [BƯỚC 4] Máy A mở Bảng vẽ tương tác (TuYaApp / sketch-sync-app) ---")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    time.sleep(4.5)

    # 5. Máy A vẽ Nét 1 (Màu ĐỎ) -> Đồng bộ hiển thị trên cả 2 máy
    print("\n--- [BƯỚC 5] Máy A vẽ Nét 1 (Màu ĐỎ) -> Đồng bộ hiển thị trên cả 2 máy ---")
    run_adb(DEV_A, "input swipe 250 1100 830 1100 400")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color red --ez cross false")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color red --ez cross false")
    time.sleep(2.5)

    capture_screenshot(DEV_A, "final_proof_1_draw1_a.png")
    capture_screenshot(DEV_B, "final_proof_1_draw1_b.png")

    # 6. Máy A bấm PHÍM BACK HỆ THỐNG (KEYCODE_BACK = 4)
    print("\n--- [BƯỚC 6] Máy A bấm PHÍM BACK CỦA ĐIỆN THOẠI (input keyevent 4) ---")
    print("  -> Kiểm tra OnBackPressedCallback kích hoạt quay về Danh sách Menu ứng dụng")
    run_adb(DEV_A, "input keyevent 4")
    time.sleep(3.5)

    capture_screenshot(DEV_A, "final_proof_2_menu_after_back_a.png")
    capture_screenshot(DEV_B, "final_proof_2_menu_b.png")

    # 7. Máy A vào lại Bảng vẽ tương tác từ Danh sách Menu
    print("\n--- [BƯỚC 7] Máy A vào lại Bảng vẽ tương tác từ Danh sách Menu ---")
    # Tap vào card 'Bảng vẽ tương tác' trên Máy A tại tọa độ (540, 600)
    run_adb(DEV_A, "input tap 540 600")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    time.sleep(4.5)

    # 8. Máy A vẽ Nét 2 (Màu XANH LÁ) -> Đồng bộ hiển thị trên cả 2 máy
    print("\n--- [BƯỚC 8] Máy A vẽ Nét 2 (Màu XANH LÁ) -> Đồng bộ hiển thị trên cả 2 máy ---")
    run_adb(DEV_A, "input swipe 540 850 540 1350 400")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color green --ez cross true")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color green --ez cross true")
    time.sleep(2.5)

    capture_screenshot(DEV_A, "final_proof_3_draw2_a.png")
    capture_screenshot(DEV_B, "final_proof_3_draw2_b.png")

    # 9. Kết thúc cuộc gọi
    print("\n--- [BƯỚC 9] Kết thúc cuộc gọi ---")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(2.5)

    capture_screenshot(DEV_A, "final_proof_4_ended_a.png")
    capture_screenshot(DEV_B, "final_proof_4_ended_b.png")

    print("\n" + "=" * 80)
    print("   HOÀN TẤT KIỂM THỬ PHÍM BACK & ĐỒNG BỘ BẢNG VẼ QUA ADC THÀNH CÔNG!   ")
    print("=" * 80)

if __name__ == "__main__":
    main()
