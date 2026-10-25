import subprocess
import time
import os
import sys
import io

if sys.platform == "win32":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", line_buffering=True)

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # Physical Samsung S24
ARTIFACTS_DIR = r"C:\Users\lamnt\.gemini\antigravity-ide\brain\83b918c3-f0ec-490a-811f-e05e5cc427c0"

def run_adb(device, shell_args):
    cmd = [ADB, "-s", device, "shell"] + shell_args
    res = subprocess.run(cmd, capture_output=True, text=True, errors="ignore")
    return res.stdout.strip()

def capture_screenshot(device, filename):
    remote_path = f"/sdcard/{filename}"
    local_path = os.path.join(ARTIFACTS_DIR, filename)
    run_adb(device, ["screencap", "-p", remote_path])
    subprocess.run([ADB, "-s", device, "pull", remote_path, local_path], capture_output=True)
    size = os.path.getsize(local_path) if os.path.exists(local_path) else 0
    print(f"  📸 [SCREENSHOT] {filename} saved ({size:,} bytes)")
    return local_path, size

def main():
    print("--- Khởi chạy và Đăng ký 1002 ---")
    run_adb(DEV_A, ["am", "force-stop", "com.demo.imsdc"])
    time.sleep(1.0)
    run_adb(DEV_A, ["am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", "com.demo.imsdc/.ui.MainActivity"])
    time.sleep(2.5)

    # Đăng ký
    run_adb(DEV_A, ["input", "tap", "540", "1663"])
    time.sleep(2.0)

    # Gọi 198
    print("--- Gọi Hotline 198 ---")
    run_adb(DEV_A, ["am", "start", "-n", "com.demo.imsdc/.ui.MainActivity", "--es", "call", "198"])
    time.sleep(3.5)

    # Nạp Smart Menu
    run_adb(DEV_A, ["am", "broadcast", "-a", "com.demo.imsdc.ACTION_LAUNCH_SMARTMENU"])
    time.sleep(3.5)
    capture_screenshot(DEV_A, "proof_smartmenu_1_connected.png")

    # Tap Button 1: Gặp tổng đài viên (X=540, Y=1080)
    print("--- Tap Button 1: Gặp tổng đài viên ---")
    run_adb(DEV_A, ["input", "tap", "540", "1080"])
    time.sleep(2.5)
    capture_screenshot(DEV_A, "proof_smartmenu_2_agent.png")

    # Tap Button 3: Thời gian hệ thống (X=540, Y=1440)
    print("--- Tap Button 3: Thời gian hệ thống ---")
    run_adb(DEV_A, ["input", "tap", "540", "1440"])
    time.sleep(2.5)
    capture_screenshot(DEV_A, "proof_smartmenu_4_time.png")

    # Tap Button 2: Xem 1 video (X=540, Y=1260)
    print("--- Tap Button 2: Xem 1 video ---")
    run_adb(DEV_A, ["input", "tap", "540", "1260"])
    time.sleep(3.5)
    capture_screenshot(DEV_A, "proof_smartmenu_3_video.png")

    # Close video
    run_adb(DEV_A, ["input", "tap", "930", "1090"])
    time.sleep(1.0)

    # Kết thúc cuộc gọi
    print("--- Kết thúc cuộc gọi ---")
    run_adb(DEV_A, ["am", "broadcast", "-a", "com.demo.imsdc.END_CALL"])
    time.sleep(2.0)
    capture_screenshot(DEV_A, "proof_smartmenu_5_ended.png")

if __name__ == "__main__":
    main()
