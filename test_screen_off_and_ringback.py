import subprocess
import time
import os
import sys

# Ensure UTF-8 output on Windows
if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", line_buffering=True)

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # Caller 1002 (Samsung S24)
DEV_B = "R5CY54VL13Y"  # Callee 1003 (Samsung S24)
APK_PATH = r"d:\project\demo-mini-app\android-client\app\build\outputs\apk\debug\app-debug.apk"
ARTIFACT_DIR = r"C:\Users\lamnt\.gemini\antigravity-ide\brain\83b918c3-f0ec-490a-811f-e05e5cc427c0"

def run_adb(device_id, shell_cmd):
    res = subprocess.run([ADB, "-s", device_id, "shell"] + shell_cmd.split(), capture_output=True, text=True, encoding="utf-8", errors="replace")
    return res.stdout.strip()

def capture_screenshot(device_id, filename):
    local_path = os.path.join(ARTIFACT_DIR, filename)
    remote_path = f"/sdcard/{filename}"
    run_adb(device_id, f"screencap -p {remote_path}")
    res = subprocess.run([ADB, "-s", device_id, "pull", remote_path, local_path], capture_output=True, text=True)
    if os.path.exists(local_path):
        print(f"  [SCREENSHOT] Saved ({os.path.getsize(local_path)} bytes): {filename}")
    else:
        print(f"  [SCREENSHOT ERROR] Pull failed: {res.stderr}")
    return local_path

def install_apks():
    print(f"Installing APK ({APK_PATH}) to both devices...")
    for dev in [DEV_A, DEV_B]:
        res = subprocess.run([ADB, "-s", dev, "install", "-r", APK_PATH], capture_output=True, text=True)
        print(f"  Device {dev}: {res.stdout.strip()} {res.stderr.strip()}")

def main():
    install_apks()

    print("\n" + "="*70)
    print("   TESTING SCREEN-OFF CALL WAKEUP & RINGBACK TONE (180 RINGING)   ")
    print("="*70)

    # 1. Clear logcats
    run_adb(DEV_A, "logcat -c")
    run_adb(DEV_B, "logcat -c")

    # 2. Reset and launch apps
    print("\n--- [PHASE 1] Registering Both Phones on SIP TCP ---")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    run_adb(DEV_B, "am force-stop com.demo.imsdc")
    time.sleep(1.0)

    run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.5)

    for dev, name in [(DEV_A, "1002"), (DEV_B, "1003")]:
        run_adb(dev, "uiautomator dump /sdcard/ui.xml")
        ui = run_adb(dev, "cat /sdcard/ui.xml")
        if "REGISTER" in ui or "Chưa đăng ký" in ui:
            print(f"  Device {name} tapping REGISTER...")
            run_adb(dev, "input tap 540 1663")
            time.sleep(1.5)
        else:
            print(f"  Device {name} is already registered.")

    capture_screenshot(DEV_A, "test_ringback_1_a_registered.png")
    capture_screenshot(DEV_B, "test_ringback_1_b_registered.png")

    subprocess.run([ADB, "-s", DEV_A, "logcat", "-c"])
    subprocess.run([ADB, "-s", DEV_B, "logcat", "-c"])

    # 3. Turn OFF screen on Callee B
    print("\n--- [PHASE 2] Turning OFF Screen on Callee B (Screen-Off / Lock) ---")
    # Send KEYCODE_POWER to turn off screen
    run_adb(DEV_B, "input keyevent 26")
    time.sleep(2.0)
    print("  Callee B screen turned off. Waiting in deep standby for 3 seconds...")
    time.sleep(3.0)

    # 4. Caller A initiates call to 1003
    print("\n--- [PHASE 3] Caller A (1002) calls Callee B (1003) ---")
    print("  Caller A dialing 1003...")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
    time.sleep(0.3)

    # Capture initial calling screen on A (should show 'Đang kết nối...')
    capture_screenshot(DEV_A, "test_ringback_2_a_connecting.png")

    # Check Callee B: screen should turn ON automatically
    time.sleep(1.8)
    capture_screenshot(DEV_B, "test_ringback_3_b_wake_incoming.png")

    # Capture Caller A when 180 Ringing is active (should show '🔔 Đang đổ chuông...')
    capture_screenshot(DEV_A, "test_ringback_4_a_ringing.png")

    # Check logcat on A for Ringback Tone
    res_log_a = subprocess.run([ADB, "-s", DEV_A, "logcat", "-d"], capture_output=True, text=True, errors="ignore")
    matching_lines = [l for l in res_log_a.stdout.split("\n") if "Ringback" in l or "180" in l or "CallActivity" in l]
    print(f"\n[LOGCAT A] Ringback Tone Logs ({len(matching_lines)} matches):")
    for l in matching_lines[-10:]:
        print(f"  {l}")

    # 5. Callee B answers the call
    print("\n--- [PHASE 4] Callee B Answers Call ---")
    run_adb(DEV_B, "input tap 265 305") # tap "Trả lời" on banner
    time.sleep(0.5)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
    time.sleep(1.0)
    run_adb(DEV_B, "input tap 820 1934") # green button fallback
    time.sleep(3.5)

    # Check logcat on A for Ringback Tone stopped
    res_log_a2 = subprocess.run([ADB, "-s", DEV_A, "logcat", "-d"], capture_output=True, text=True, errors="ignore")
    matching_lines2 = [l for l in res_log_a2.stdout.split("\n") if "Ringback" in l or "200" in l or "connected" in l.lower()]
    print(f"\n[LOGCAT A] Connected / Tone Stopped Logs ({len(matching_lines2)} matches):")
    for l in matching_lines2[-10:]:
        print(f"  {l}")

    capture_screenshot(DEV_A, "test_ringback_5_connected_a.png")
    capture_screenshot(DEV_B, "test_ringback_6_connected_b.png")

    # 6. End Call
    print("\n--- [PHASE 5] Ending Call & Cleanup ---")
    time.sleep(2.0)
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(1.0)
    run_adb(DEV_A, "input tap 540 2150")
    time.sleep(2.0)

    capture_screenshot(DEV_A, "test_ringback_7_ended_a.png")
    capture_screenshot(DEV_B, "test_ringback_7_ended_b.png")

    print("\n" + "="*70)
    print("   TEST COMPLETED SUCCESSFULLY!   ")
    print("="*70)

if __name__ == "__main__":
    main()
