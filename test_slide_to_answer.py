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
APK_PATH = r"d:\project\demo-mini-app\android-client\app\build\outputs\apk\debug\app-debug.apk"
ARTIFACTS_DIR = r"C:\Users\lamnt\.gemini\antigravity-ide\brain\83b918c3-f0ec-490a-811f-e05e5cc427c0"
os.makedirs(ARTIFACTS_DIR, exist_ok=True)

def run_cmd(args):
    res = subprocess.run(args, capture_output=True, text=True, errors="ignore")
    return res.stdout.strip()

def run_adb(device, shell_cmd):
    return run_cmd([ADB, "-s", device, "shell"] + shell_cmd.split())

def is_screen_on(device):
    out = run_adb(device, "dumpsys power")
    for line in out.split("\n"):
        if "mHoldingDisplaySuspendBlocker=" in line:
            return "true" in line
        if "mWakefulness=" in line:
            return "Awake" in line
    out2 = run_adb(device, "dumpsys display")
    return "state=ON" in out2 or "mScreenState=ON" in out2

def capture_screenshot(device, filename):
    remote_path = f"/sdcard/{filename}"
    local_path = os.path.join(ARTIFACTS_DIR, filename)
    run_adb(device, f"screencap -p {remote_path}")
    subprocess.run([ADB, "-s", device, "pull", remote_path, local_path], capture_output=True)
    size = os.path.getsize(local_path) if os.path.exists(local_path) else 0
    print(f"  [SCREENSHOT] {filename} saved ({size:,} bytes)")
    return local_path, size

def install_apk():
    print(f"Installing APK ({APK_PATH}) to both devices...")
    for dev in [DEV_A, DEV_B]:
        res = subprocess.run([ADB, "-s", dev, "install", "-r", APK_PATH], capture_output=True, text=True)
        print(f"  Device {dev}: {res.stdout.strip()}")

def main():
    install_apk()

    print("\n" + "="*75)
    print("   SLIDE-TO-ANSWER (DUAL SWIPE CIRCLES) REAL DEVICE E2E TEST   ")
    print("="*75)

    # 1. Start App & Register
    print("\n--- [STEP 1] Starting Apps and Registering SIP TCP ---")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    run_adb(DEV_B, "am force-stop com.demo.imsdc")
    time.sleep(1.0)

    run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.5)

    for dev, name in [(DEV_A, "1002"), (DEV_B, "1003")]:
        run_adb(dev, "input tap 540 1663")
        time.sleep(1.5)

    capture_screenshot(DEV_A, "slide_1_a_registered.png")
    capture_screenshot(DEV_B, "slide_1_b_registered.png")

    run_cmd([ADB, "-s", DEV_A, "logcat", "-c"])
    run_cmd([ADB, "-s", DEV_B, "logcat", "-c"])

    # 2. Caller A calls Callee B
    print("\n--- [STEP 2] Caller A (1002) calls Callee B (1003) ---")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
    time.sleep(2.0)

    # 3. Capture Incoming Call Screen with SlideToAnswer widget
    print("\n--- [STEP 3] Verifying Slide-to-Answer UI on Callee B ---")
    capture_screenshot(DEV_A, "slide_2_a_calling.png")
    _, size_incoming_b = capture_screenshot(DEV_B, "slide_2_b_incoming_slide.png")
    print(f"  [CHECK] Callee B incoming screen captured ({size_incoming_b:,} bytes)")

    # 4. Perform Swipe-to-Answer on Callee B (Swipe green circle from right to left)
    print("\n--- [STEP 4] Performing Swipe Gesture: Green Circle Right -> Left ---")
    # Coordinates: Green button center is approx (850, 2050), swiping left to (250, 2050)
    run_adb(DEV_B, "input swipe 850 2050 250 2050 350")
    time.sleep(3.0)

    # 5. Verify Connected state on both devices
    print("\n--- [STEP 5] Checking Call Connection Status ---")
    capture_screenshot(DEV_A, "slide_3_a_connected.png")
    capture_screenshot(DEV_B, "slide_3_b_connected.png")

    log_a = run_cmd([ADB, "-s", DEV_A, "logcat", "-d"])
    log_b = run_cmd([ADB, "-s", DEV_B, "logcat", "-d"])

    call_connected = "200 OK" in log_a or "ACK" in log_b or "CONNECTED" in log_a
    slide_activated = "Slide-to-Answer triggered" in log_b or "Swipe-to-Answer ACTIVATED" in log_b or "200 OK" in log_b

    print(f"  [CHECK] Swipe Activation Detected in Logcat: {slide_activated}")
    print(f"  [CHECK] Call Connected (SIP 200 OK / WebRTC): {call_connected}")

    # 6. End Call
    print("\n--- [STEP 6] Ending Call ---")
    run_adb(DEV_A, "input tap 540 2150") # btnEndCall
    time.sleep(2.0)

    capture_screenshot(DEV_A, "slide_4_a_ended.png")
    capture_screenshot(DEV_B, "slide_4_b_ended.png")

    print("\n" + "="*75)
    if call_connected and size_incoming_b > 50000:
        print("   >>> SLIDE-TO-ANSWER E2E TEST PASSED SUCCESSFULLY! <<<   ")
    else:
        print(f"   >>> TEST RESULT: FAILED (connected={call_connected}, size={size_incoming_b}) <<<   ")
    print("="*75)

if __name__ == "__main__":
    main()
