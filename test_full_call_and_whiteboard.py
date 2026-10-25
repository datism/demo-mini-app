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
    print("=== STEP 1: Dial 1003 on Phone A and start call ===")
    # Clear digits first
    for _ in range(6):
        adb(DEV_A, "shell", "input", "tap", "866", "1856")
        time.sleep(0.1)
    time.sleep(0.3)

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
    print("Call initiated on Phone A. Waiting 2s for Phone B to ring...")
    time.sleep(2)
    
    print("=== STEP 2: Phone B answers incoming call ===")
    # Tap 'Trả lời' on heads-up notification and slide answer
    adb(DEV_B, "shell", "input", "tap", "260", "130")
    adb(DEV_B, "shell", "input", "swipe", "800", "1900", "200", "1900", "300")
    
    print("Waiting 6 seconds for call connection & BDC operator menu...")
    time.sleep(6)
    
    capture(DEV_A, "verified_menu_a.png")
    capture(DEV_B, "verified_menu_b.png")
    
    print("=== STEP 3: Phone A taps 'Bảng vẽ tương tác' (sketch-sync-app) ===")
    adb(DEV_A, "shell", "input", "tap", "540", "1120")
    
    print("Waiting 7 seconds for Re-INVITE negotiation & Whiteboard loading on BOTH phones...")
    time.sleep(7)
    
    capture(DEV_A, "verified_whiteboard_a.png")
    capture(DEV_B, "verified_whiteboard_b.png")
    
    print("=== STEP 4: Phone A draws on the Whiteboard ===")
    adb(DEV_A, "shell", "input", "swipe", "300", "900", "750", "1100", "300")
    time.sleep(0.5)
    adb(DEV_A, "shell", "input", "swipe", "750", "1100", "300", "1300", "300")
    time.sleep(0.5)
    adb(DEV_A, "shell", "input", "swipe", "300", "1300", "300", "900", "300")
    time.sleep(2)
    
    capture(DEV_A, "verified_drawing_a.png")
    capture(DEV_B, "verified_drawing_b.png")
    
    print("=== Complete! ===")

if __name__ == "__main__":
    main()
