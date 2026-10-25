import subprocess
import time
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # 1002
DEV_B = "R5CY54VL13Y"  # 1003
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

def main():
    print("=== STEP 1: Launch apps on both devices ===")
    for dev in [DEV_A, DEV_B]:
        adb(dev, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb(dev, "shell", "wm", "dismiss-keyguard")
        adb(dev, "shell", "am", "force-stop", "com.demo.imsdc")
        adb(dev, "logcat", "-c")
    time.sleep(1)

    print("Starting Phone B (1003)...")
    adb(DEV_B, "shell", "am", "start", "-n", "com.demo.imsdc/.ui.MainActivity", "-f", "0x10008000")
    time.sleep(2)

    print("Starting Phone A (1002)...")
    adb(DEV_A, "shell", "am", "start", "-n", "com.demo.imsdc/.ui.MainActivity", "-f", "0x10008000")
    time.sleep(2)

    print("=== STEP 2: Phone A dials 1003 ===")
    for _ in range(5):
        adb(DEV_A, "shell", "input", "tap", "866", "1856")
        time.sleep(0.1)
    
    adb(DEV_A, "shell", "input", "tap", "247", "908")   # 1
    time.sleep(0.2)
    adb(DEV_A, "shell", "input", "tap", "540", "1607")  # 0
    time.sleep(0.2)
    adb(DEV_A, "shell", "input", "tap", "540", "1607")  # 0
    time.sleep(0.2)
    adb(DEV_A, "shell", "input", "tap", "832", "908")   # 3
    time.sleep(0.4)
    
    adb(DEV_A, "shell", "input", "tap", "540", "1856")  # Call
    time.sleep(2)

    print("=== STEP 3: Phone B answers call ===")
    adb(DEV_B, "shell", "input", "tap", "260", "130")
    adb(DEV_B, "shell", "input", "swipe", "800", "1900", "200", "1900", "300")
    print("Waiting 6s for call connection & BDC menu...")
    time.sleep(6)

    print("=== STEP 4: Phone A selects 'Thả tim tương tác' (Fun Calling) ===")
    # On Operator Menu: Item 3 is Fun Calling (Y coordinate around 1380)
    adb(DEV_A, "shell", "input", "tap", "540", "1380")
    print("Waiting 6s for Fun Calling loading on BOTH phones...")
    time.sleep(6)

    print("=== STEP 5: Phone B taps Emoji (Heart, Like, Smile, Angry, Wave) ===")
    # 1. Heart
    print("Phone B taps Heart...")
    adb(DEV_B, "shell", "input", "tap", "210", "1710")
    time.sleep(0.8)
    capture(DEV_A, "funcall_heart_received_a.png")
    capture(DEV_B, "funcall_heart_sent_b.png")

    # 2. Like
    print("Phone B taps Like...")
    adb(DEV_B, "shell", "input", "tap", "355", "1710")
    time.sleep(0.8)

    # 3. Smile
    print("Phone B taps Smile...")
    adb(DEV_B, "shell", "input", "tap", "500", "1710")
    time.sleep(0.8)

    capture(DEV_A, "funcall_emojis_final_a.png")
    capture(DEV_B, "funcall_emojis_final_b.png")

    print("=== STEP 6: Hang up ===")
    for dev in [DEV_A, DEV_B]:
        adb(dev, "shell", "input", "tap", "500", "1950")
        time.sleep(0.3)
        adb(dev, "shell", "am", "force-stop", "com.demo.imsdc")
        adb(dev, "shell", "input", "keyevent", "KEYCODE_SLEEP")

if __name__ == "__main__":
    main()
