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
    print("=== STEP 1: Wake up, launch apps on both devices ===")
    for dev in [DEV_A, DEV_B]:
        adb(dev, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb(dev, "shell", "wm", "dismiss-keyguard")
        adb(dev, "shell", "am", "force-stop", "com.demo.imsdc")
    time.sleep(1)

    print("Starting Phone B (1003)...")
    adb(DEV_B, "shell", "am", "start", "-n", "com.demo.imsdc/.ui.MainActivity", "-f", "0x10008000")
    time.sleep(2)

    print("Starting Phone A (1002)...")
    adb(DEV_A, "shell", "am", "start", "-n", "com.demo.imsdc/.ui.MainActivity", "-f", "0x10008000")
    time.sleep(2)

    print("=== STEP 2: Phone A dials 1003 ===")
    # Clear digits
    for _ in range(5):
        adb(DEV_A, "shell", "input", "tap", "866", "1856")
        time.sleep(0.1)
    
    # Dial 1-0-0-3
    adb(DEV_A, "shell", "input", "tap", "247", "908")   # 1
    time.sleep(0.2)
    adb(DEV_A, "shell", "input", "tap", "540", "1607")  # 0
    time.sleep(0.2)
    adb(DEV_A, "shell", "input", "tap", "540", "1607")  # 0
    time.sleep(0.2)
    adb(DEV_A, "shell", "input", "tap", "832", "908")   # 3
    time.sleep(0.4)
    
    # Tap Call button
    adb(DEV_A, "shell", "input", "tap", "540", "1856")
    print("Call initiated. Waiting 2s for Phone B to ring...")
    time.sleep(2)

    print("=== STEP 3: Phone B answers call ===")
    adb(DEV_B, "shell", "input", "tap", "260", "130")
    adb(DEV_B, "shell", "input", "swipe", "800", "1900", "200", "1900", "300")
    print("Waiting 6s for call connection & BDC menu...")
    time.sleep(6)

    print("=== STEP 4: Phone A selects 'Bảng vẽ tương tác' ===")
    adb(DEV_A, "shell", "input", "tap", "540", "1120")
    print("Waiting 7s for Whiteboard loading on BOTH phones...")
    time.sleep(7)

    capture(DEV_A, "clean_x_empty_a.png")
    capture(DEV_B, "clean_x_empty_b.png")

    print("=== STEP 5: Phone A draws ONLY a clean letter 'X' (2 strokes) ===")
    # Stroke 1: Top-Left to Bottom-Right (350, 950) -> (650, 1250)
    adb(DEV_A, "shell", "input", "swipe", "350", "950", "650", "1250", "300")
    time.sleep(0.6)
    
    # Stroke 2: Top-Right to Bottom-Left (650, 950) -> (350, 1250)
    adb(DEV_A, "shell", "input", "swipe", "650", "950", "350", "1250", "300")
    time.sleep(2)

    capture(DEV_A, "clean_x_drawn_a.png")
    capture(DEV_B, "clean_x_drawn_b.png")

    print("=== STEP 6: Hang up and save battery ===")
    for dev in [DEV_A, DEV_B]:
        adb(dev, "shell", "input", "tap", "500", "1850")
        time.sleep(0.3)
        adb(dev, "shell", "am", "force-stop", "com.demo.imsdc")
        adb(dev, "shell", "input", "keyevent", "KEYCODE_SLEEP")

    print("=== Test finished successfully ===")

if __name__ == "__main__":
    main()
