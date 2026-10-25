import subprocess
import time
import sys
import os

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X" # 1002 (Caller)
DEV_B = "R5CY54VL13Y" # 1003 (Callee)

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
    print(f"  [SCREENSHOT] Saved: {filename}")
    return local_path

def get_logcat(device, last_n=100):
    res = subprocess.run([ADB, "-s", device, "logcat", "-d"], capture_output=True, text=True, errors="ignore")
    lines = res.stdout.strip().split("\n")
    return "\n".join(lines[-last_n:])

print("1. Launching MainActivity on both devices...")
run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
time.sleep(2.0)

# Tap register if needed
for dev, name in [(DEV_A, "1002"), (DEV_B, "1003")]:
    run_adb(dev, "uiautomator dump /sdcard/ui.xml")
    ui = run_adb(dev, "cat /sdcard/ui.xml")
    if "REGISTER" in ui or "Chưa đăng ký" in ui:
        print(f"  Tapping REGISTER on {name}...")
        run_adb(dev, "input tap 540 1663")
        time.sleep(1.5)

run_cmd([ADB, "-s", DEV_A, "logcat", "-c"])
run_cmd([ADB, "-s", DEV_B, "logcat", "-c"])

# 2. Phone B to Home screen
print("2. Phone B pressing Home key...")
run_adb(DEV_B, "input keyevent 3")
time.sleep(1.5)
capture_screenshot(DEV_B, "test_b_home.png")

# 3. Phone A calls Phone B
print("3. Phone A calling 1003...")
# Make sure Phone A has 1003 dialed or dialer ready
run_adb(DEV_A, "input tap 540 1856")
time.sleep(2.0)
capture_screenshot(DEV_B, "test_b_banner.png")

# 4. Phone B answers via tapping on notification banner "Trả lời" button at (265, 305)
print("4. Phone B tapping 'Trả lời' on banner at (265, 305)...")
run_adb(DEV_B, "input tap 265 305")
time.sleep(1.5)

# Also tap banner body if still visible
run_adb(DEV_B, "input tap 500 200")
time.sleep(1.0)
run_adb(DEV_B, "input tap 820 1934") # green answer button
time.sleep(3.0)

capture_screenshot(DEV_A, "test_a_connected.png")
capture_screenshot(DEV_B, "test_b_connected.png")

print("\n--- Recent Logs Phone B ---")
for line in get_logcat(DEV_B, 40).split("\n"):
    if any(k in line for k in ["CallActivity", "SipManager", "IncomingCall", "handleIncomingCall", "200 OK", "CONNECTED", "ICE"]):
        print("  [B]", line)
