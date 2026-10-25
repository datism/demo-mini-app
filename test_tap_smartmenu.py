import subprocess
import time
import os
import sys

if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", line_buffering=True)

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL13Y"  # Physical Samsung S24 (User 1003)
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
    print(f"  📸 [SCREENSHOT] {filename} saved ({size:,} bytes)")
    return local_path, size

def ensure_screen_on(device):
    out = run_adb(device, "dumpsys power")
    if "mWakefulness=Awake" not in out and "state=ON" not in out:
        run_adb(device, "input keyevent 26") # Power
        time.sleep(1.0)
    run_adb(device, "input keyevent 82") # Unlock
    time.sleep(0.5)

def main():
    print("=" * 80)
    print("   KIỂM THỬ THỰC TẾ SMART CUSTOMER SERVICE 198 (GSMA 5G NEW CALLING)   ")
    print("=" * 80)

    ensure_screen_on(DEV_A)

    # 1. Khởi chạy và Đăng ký SIP
    print("\n--- [BƯỚC 1] Khởi chạy ứng dụng & Đăng ký SIP ---")
    run_adb(DEV_A, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    time.sleep(1.0)

    run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.5)

    # Bấm đăng ký 1002
    run_adb(DEV_A, "input tap 540 1663")
    time.sleep(2.0)
    capture_screenshot(DEV_A, "proof_smartmenu_0_registered.png")

    # 2. Quay số 198 (Hotline Tổng đài CSKH)
    print("\n--- [BƯỚC 2] Gọi số 198 - Server IMS-AS/DCAS tự động trả lời (200 OK) ---")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 198")
    time.sleep(3.5)

    # Nạp giao diện smart-menu
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_SMARTMENU")
    time.sleep(3.5)
    capture_screenshot(DEV_A, "proof_smartmenu_1_connected.png")

    # 3. Test Chức năng 1: Gặp tổng đài viên (Tap 540 1000)
    print("\n--- [BƯỚC 3] Test Chức năng 1: Gặp tổng đài viên (Tap 540 1000) ---")
    run_adb(DEV_A, "input tap 540 1000")
    time.sleep(2.5)
    capture_screenshot(DEV_A, "proof_smartmenu_2_agent.png")

    # 4. Test Chức năng 2: Xem 1 video ngẫu nhiên (Tap 540 1175)
    print("\n--- [BƯỚC 4] Test Chức năng 2: Xem 1 video ngẫu nhiên (Tap 540 1175) ---")
    run_adb(DEV_A, "input tap 540 1175")
    time.sleep(3.0)
    capture_screenshot(DEV_A, "proof_smartmenu_3_video.png")

    # Đóng modal video (Tap nút close X tại 950 1055)
    run_adb(DEV_A, "input tap 950 1055")
    time.sleep(1.5)

    # 5. Test Chức năng 3: Thời gian hệ thống (Tap 540 1355)
    print("\n--- [BƯỚC 5] Test Chức năng 3: Thời gian hệ thống (Tap 540 1355) ---")
    run_adb(DEV_A, "input tap 540 1355")
    time.sleep(2.5)
    capture_screenshot(DEV_A, "proof_smartmenu_4_time.png")

    # 6. Kết thúc cuộc gọi
    print("\n--- [BƯỚC 6] Kết thúc cuộc gọi ---")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(2.0)
    capture_screenshot(DEV_A, "proof_smartmenu_5_ended.png")

    print("\n" + "=" * 80)
    print("   🎉 BẰNG CHỨNG HÌNH ẢNH CHO SMART MENU 198 ĐÃ HOÀN TẤT VÀ LƯU THÀNH CÔNG!   ")
    print("=" * 80)

if __name__ == "__main__":
    main()
