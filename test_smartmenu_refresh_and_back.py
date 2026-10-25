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
    print("   TEST REFRESH & BACK KEY IN 198 SMART MENU   ")
    print("=" * 80)

    # 1. Start Call 198
    print("\n1. Calling 198...")
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

    # 2. Tap Agent
    print("\n2. Tapping 'Gặp tổng đài viên'...")
    run_adb(DEV_A, "input tap 540 1000")
    time.sleep(2.0)
    capture_screenshot(DEV_A, "proof_198_agent_active.png")

    # 3. Tap "Làm mới Menu Tổng đài 198" (tap at 540 1650)
    print("\n3. Tapping 'Làm mới Menu Tổng đài 198' button...")
    run_adb(DEV_A, "input tap 540 1650")
    time.sleep(2.0)
    capture_screenshot(DEV_A, "proof_198_after_refresh_click.png")

    # 4. Press Hardware Back Key
    print("\n4. Pressing Back key...")
    run_adb(DEV_A, "input keyevent 4")
    time.sleep(2.0)
    capture_screenshot(DEV_A, "proof_198_after_back_key.png")

    # 5. End Call
    print("\n5. Ending Call...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(1.5)

    print("\n" + "=" * 80)
    print("   DONE   ")
    print("=" * 80)

if __name__ == "__main__":
    main()
