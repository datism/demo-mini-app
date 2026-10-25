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

def main():
    print("=" * 80)
    print("   TEST CARTOON VIDEO PLAYBACK IN 198 SMART MENU   ")
    print("=" * 80)

    # 1. Start Call 198
    print("\n1. Launching and Calling 198...")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    time.sleep(1.0)
    run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.0)
    run_adb(DEV_A, "input tap 540 1663")
    time.sleep(2.0)
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 198")
    time.sleep(3.0)
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_SMARTMENU")
    time.sleep(3.0)
    capture_screenshot(DEV_A, "proof_cartoon_1_menu.png")

    # 2. Tap "Video hoạt hình vui nhộn" (Y around 1175)
    print("\n2. Tapping 'Video hoạt hình vui nhộn'...")
    run_adb(DEV_A, "input tap 540 1175")
    time.sleep(3.0)
    capture_screenshot(DEV_A, "proof_cartoon_2_playing.png")

    # 3. Wait 3s and capture video playing progress
    time.sleep(3.0)
    capture_screenshot(DEV_A, "proof_cartoon_3_progress.png")

    # 4. Close video modal by tapping close button at (890, 940)
    print("\n3. Closing video modal...")
    run_adb(DEV_A, "input tap 890 940")
    time.sleep(1.5)
    capture_screenshot(DEV_A, "proof_cartoon_4_closed.png")

    # 5. Tap Reset Smart Menu
    print("\n4. Resetting 198 Smart Menu...")
    run_adb(DEV_A, "input tap 540 1470")
    time.sleep(1.5)
    capture_screenshot(DEV_A, "proof_cartoon_5_reset.png")

    # 6. End Call
    print("\n5. Ending Call...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(1.5)

    print("\n" + "=" * 80)
    print("   TEST COMPLETED SUCCESSFULLY!   ")
    print("=" * 80)

if __name__ == "__main__":
    main()
