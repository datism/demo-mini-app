import subprocess
import time
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # 1002 (Caller)
DEV_B = "R5CY54VL13Y"  # 1003 (Callee)
APK_PATH = r"d:\project\demo-mini-app\android-client\app\build\outputs\apk\debug\app-debug.apk"
OUT_DIR = r"C:\Users\lamnt\.gemini\antigravity-ide\brain\83b918c3-f0ec-490a-811f-e05e5cc427c0"

def adb(dev, *args):
    cmd = [ADB, "-s", dev] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, errors="ignore").stdout

def capture(dev, filename):
    local_path = os.path.join(OUT_DIR, filename)
    subprocess.run([ADB, "-s", dev, "shell", "screencap", "-p", "/sdcard/screen.png"])
    subprocess.run([ADB, "-s", dev, "pull", "/sdcard/screen.png", local_path])
    print(f"Captured {filename}")
    return local_path

def install_apk():
    print(f"Installing APK ({APK_PATH}) to both devices...")
    for dev in [DEV_A, DEV_B]:
        res = subprocess.run([ADB, "-s", dev, "install", "-r", APK_PATH], capture_output=True, text=True)
        print(f"  Device {dev}: {res.stdout.strip()}")

def main():
    install_apk()

    print("=== STEP 1: Launch & Register apps on both devices ===")
    for dev in [DEV_A, DEV_B]:
        adb(dev, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb(dev, "shell", "wm", "dismiss-keyguard")
        adb(dev, "shell", "am", "force-stop", "com.demo.imsdc")
        adb(dev, "logcat", "-c")
    time.sleep(1)

    print("Starting Phone B (1003)...")
    adb(DEV_B, "shell", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", "com.demo.imsdc/.ui.MainActivity")
    time.sleep(2)

    print("Starting Phone A (1002)...")
    adb(DEV_A, "shell", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", "com.demo.imsdc/.ui.MainActivity")
    time.sleep(2)

    print("=== STEP 2: Phone A (Caller) dials 1003 ===")
    adb(DEV_A, "shell", "am", "start", "-n", "com.demo.imsdc/.ui.MainActivity", "--es", "call", "1003")
    print("Waiting 3s for Phone B to ring...")
    time.sleep(3)

    print("=== STEP 3: Phone B answers call ===")
    adb(DEV_B, "shell", "am", "broadcast", "-a", "com.demo.imsdc.ANSWER")
    adb(DEV_B, "shell", "input", "tap", "265", "305")
    adb(DEV_B, "shell", "input", "tap", "810", "2150")
    print("Waiting 7s for call connection & BDC operator menu...")
    time.sleep(7)

    print("=== STEP 4: Phone A (CALLER SIDE) launches 'Game Cờ Caro' ===")
    adb(DEV_A, "shell", "am", "broadcast", "-a", "com.demo.imsdc.ACTION_LAUNCH_CARO")
    print("Waiting 6s for Caro initialization...")
    time.sleep(6)

    capture(DEV_A, "caro_stability_caller_init_a.png")
    capture(DEV_B, "caro_stability_caller_init_b.png")

    print("=== STEP 5: Phone A (Player X) plays Move 1 at (row 4, col 4) ===")
    adb(DEV_A, "shell", "am", "broadcast", "-a", "com.demo.imsdc.ACTION_TEST_CARO_MOVE", "--ei", "row", "4", "--ei", "col", "4")
    time.sleep(2)
    capture(DEV_A, "caro_stability_move1_a.png")
    capture(DEV_B, "caro_stability_move1_b.png")

    print("=== STEP 6: Phone B (Player O) plays Move 2 at (row 4, col 5) ===")
    adb(DEV_B, "shell", "am", "broadcast", "-a", "com.demo.imsdc.ACTION_TEST_CARO_MOVE", "--ei", "row", "4", "--ei", "col", "5")
    time.sleep(2)
    capture(DEV_A, "caro_stability_move2_a.png")
    capture(DEV_B, "caro_stability_move2_b.png")

    print("=== STEP 7: LONG DURATION STABILITY CHECK: Waiting 15 seconds without user action... ===")
    time.sleep(15)

    print("=== STEP 8: Verify Caro is STILL OPEN on Caller Phone A (NOT AUTO-EXITED) ===")
    capture(DEV_A, "caro_stability_15s_a.png")
    capture(DEV_B, "caro_stability_15s_b.png")

    print("=== STEP 9: Hang up ===")
    for dev in [DEV_A, DEV_B]:
        adb(dev, "shell", "input", "tap", "500", "1950")
        time.sleep(0.3)
        adb(dev, "shell", "am", "force-stop", "com.demo.imsdc")

if __name__ == "__main__":
    main()
