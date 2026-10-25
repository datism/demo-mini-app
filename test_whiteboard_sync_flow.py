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
    print("=== STEP 1: Wake up, dismiss keyguard and launch app on both devices ===")
    for dev in [DEV_A, DEV_B]:
        adb(dev, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb(dev, "shell", "wm", "dismiss-keyguard")
        adb(dev, "shell", "am", "force-stop", "com.demo.imsdc")
    
    time.sleep(1)
    
    print("Starting Phone B (1003)...")
    adb(DEV_B, "shell", "am", "start", "-n", "com.demo.imsdc/.ui.MainActivity")
    time.sleep(2)
    # Tap REGISTER button on Phone B
    adb(DEV_B, "shell", "input", "tap", "540", "740")
    time.sleep(2)
    
    print("Starting Phone A (1002)...")
    adb(DEV_A, "shell", "am", "start", "-n", "com.demo.imsdc/.ui.MainActivity")
    time.sleep(2)
    # Tap REGISTER button on Phone A
    adb(DEV_A, "shell", "input", "tap", "540", "740")
    time.sleep(3)
    
    capture(DEV_A, "reg_screen_a.png")
    capture(DEV_B, "reg_screen_b.png")
    
    print("=== STEP 2: Phone A dials 1003 in IMS DC App ===")
    # Dial 1003 on IMS DC Dialer:
    # 1: (270, 1070)
    # 0: (540, 1430)
    # 3: (810, 1070)
    # Call button: (540, 1600)
    adb(DEV_A, "shell", "input", "tap", "270", "1070") # 1
    time.sleep(0.3)
    adb(DEV_A, "shell", "input", "tap", "540", "1430") # 0
    time.sleep(0.3)
    adb(DEV_A, "shell", "input", "tap", "540", "1430") # 0
    time.sleep(0.3)
    adb(DEV_A, "shell", "input", "tap", "810", "1070") # 3
    time.sleep(0.5)
    adb(DEV_A, "shell", "input", "tap", "540", "1600") # Call
    
    print("Waiting for incoming call on Phone B...")
    time.sleep(3)
    
    print("=== STEP 3: Phone B answers call ===")
    # Answer incoming call on Phone B (swipe right or tap Answer at 750, 1950)
    adb(DEV_B, "shell", "input", "swipe", "250", "1950", "850", "1950", "300")
    time.sleep(1)
    adb(DEV_B, "shell", "input", "tap", "750", "1950")
    
    print("Waiting for call establishment & operator menu loading...")
    time.sleep(6)
    
    capture(DEV_A, "call_menu_a.png")
    capture(DEV_B, "call_menu_b.png")
    
    print("=== STEP 4: Phone A taps 'Bang ve tuong tac' (sketch-sync-app) ===")
    # Tap 'Bang ve tuong tac' card (y=1120, x=540)
    adb(DEV_A, "shell", "input", "tap", "540", "1120")
    
    print("Waiting 6 seconds for 2-way ADC negotiation & Whiteboard loading on BOTH devices...")
    time.sleep(6)
    
    capture(DEV_A, "whiteboard_sync_a.png")
    capture(DEV_B, "whiteboard_sync_b.png")
    
    print("=== STEP 5: Phone A draws a test stroke on Whiteboard ===")
    # Draw stroke on canvas (swipe from 300, 900 to 750, 1100)
    adb(DEV_A, "shell", "input", "swipe", "300", "900", "750", "1100", "300")
    time.sleep(3)
    
    capture(DEV_A, "whiteboard_drawn_a.png")
    capture(DEV_B, "whiteboard_drawn_b.png")
    
    print("=== Flow Test Complete ===")

if __name__ == "__main__":
    main()
