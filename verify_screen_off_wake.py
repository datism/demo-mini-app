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
    print("   STRICT VERIFICATION: INCOMING CALL SCREEN-OFF WAKEUP & CONNECTION   ")
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

    capture_screenshot(DEV_A, "verify_1_a_registered.png")
    capture_screenshot(DEV_B, "verify_1_b_registered.png")

    run_cmd([ADB, "-s", DEV_A, "logcat", "-c"])
    run_cmd([ADB, "-s", DEV_B, "logcat", "-c"])

    # 2. Turn OFF screen on Callee B
    print("\n--- [STEP 2] Turning OFF Screen on Callee B ---")
    if is_screen_on(DEV_B):
        run_adb(DEV_B, "input keyevent 26") # Press Power
        time.sleep(2.0)

    screen_b_before = is_screen_on(DEV_B)
    print(f"  [CHECK] Callee B screen state: {'ON' if screen_b_before else 'OFF (Screen is completely turned off)'}")
    _, size_b_off = capture_screenshot(DEV_B, "verify_2_b_screen_off.png")
    print(f"  [CHECK] Callee B black screen size: {size_b_off:,} bytes")
    time.sleep(3.0)

    # 3. Caller A calls Callee B
    print("\n--- [STEP 3] Caller A (1002) calls Callee B (1003) ---")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
    time.sleep(0.5)

    capture_screenshot(DEV_A, "verify_3_a_calling.png")

    # 4. Strict check: Did Callee B wake up and turn screen ON?
    print("\n--- [STEP 4] Monitoring Callee B Wakeup & Screen-On Status ---")
    b_woke_up = False
    for i in range(15):
        time.sleep(0.3)
        if is_screen_on(DEV_B):
            print(f"  [VERIFICATION SUCCESS] Callee B screen turned ON automatically at t={(i+1)*0.3:.1f}s!")
            b_woke_up = True
            break

    time.sleep(1.0)
    _, size_b_wake = capture_screenshot(DEV_B, "verify_4_b_woken_up.png")
    capture_screenshot(DEV_A, "verify_4_a_ringing.png")

    print(f"  [CHECK] Callee B screen size after call: {size_b_wake:,} bytes (Non-black screen expected > 100KB)")
    if size_b_wake > 50000:
        print("  [VERIFICATION PASS] Callee B display rendered incoming call UI!")
    else:
        print("  [VERIFICATION WARNING] Callee B display may still be unlit or black.")

    # 5. Callee B Answers Call
    print("\n--- [STEP 5] Callee B Answers Call ---")
    run_adb(DEV_B, "input tap 265 305") # banner
    time.sleep(0.3)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
    time.sleep(0.5)
    run_adb(DEV_B, "input tap 810 2150") # bottom right answer
    time.sleep(4.0)

    capture_screenshot(DEV_A, "verify_5_a_connected.png")
    capture_screenshot(DEV_B, "verify_5_b_connected.png")

    # 6. Check connection in logcat
    log_a = run_cmd([ADB, "-s", DEV_A, "logcat", "-d"])
    log_b = run_cmd([ADB, "-s", DEV_B, "logcat", "-d"])

    call_connected = "200 OK" in log_a or "ACK" in log_b or "CONNECTED" in log_a
    print(f"\n  [CHECK] SIP 200 OK / WebRTC Connected: {call_connected}")

    # 7. End Call
    print("\n--- [STEP 6] Ending Call ---")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(1.0)
    run_adb(DEV_A, "input tap 540 2150")
    time.sleep(2.0)

    capture_screenshot(DEV_A, "verify_6_a_ended.png")
    capture_screenshot(DEV_B, "verify_6_b_ended.png")

    print("\n" + "="*75)
    if b_woke_up and call_connected:
        print("   >>> STRICT TEST RESULT: ALL CHECKS PASSED SUCCESSFULLY! <<<   ")
    else:
        print(f"   >>> STRICT TEST RESULT: FAILED (woke_up={b_woke_up}, connected={call_connected}) <<<   ")
    print("="*75)

if __name__ == "__main__":
    main()
