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

print("1. Initializing MainActivity on both devices...")
run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
time.sleep(2.0)

# Tap REGISTER if needed
for dev, name in [(DEV_A, "1002"), (DEV_B, "1003")]:
    run_adb(dev, "uiautomator dump /sdcard/ui.xml")
    ui = run_adb(dev, "cat /sdcard/ui.xml")
    if "REGISTER" in ui or "Chưa đăng ký" in ui:
        print(f"  Registering {name}...")
        run_adb(dev, "input tap 540 1663")
        time.sleep(1.5)

run_cmd([ADB, "-s", DEV_A, "logcat", "-c"])
run_cmd([ADB, "-s", DEV_B, "logcat", "-c"])

# 2. Phone B to Home screen
print("2. Phone B pressing Home key...")
run_adb(DEV_B, "input keyevent 3")
time.sleep(1.5)
capture_screenshot(DEV_B, "test_flow_b_home.png")

# 3. Phone A calls 1003 using auto-call intent
print("3. Phone A initiating call to 1003...")
run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
time.sleep(2.5)

capture_screenshot(DEV_A, "test_flow_a_calling.png")
capture_screenshot(DEV_B, "test_flow_b_banner.png")

# 4. Phone B answers
print("4. Phone B answering call...")
run_adb(DEV_B, "input tap 265 305") # tap "Trả lời" on banner
time.sleep(0.5)
run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
time.sleep(1.0)
run_adb(DEV_B, "input tap 820 1934") # green button if in full screen

# Wait for connection
connected = False
for i in range(25):
    log_a = get_logcat(DEV_A, 50)
    log_b = get_logcat(DEV_B, 50)
    if "ACK" in log_b or "200 OK" in log_a or "CONNECTED" in log_a or "callState=CONNECTED" in log_b:
        print(f"  [STATUS] Call connected at {i*0.5}s!")
        connected = True
        break
    time.sleep(0.5)

time.sleep(3.0)
capture_screenshot(DEV_A, "test_flow_a_connected.png")
capture_screenshot(DEV_B, "test_flow_b_connected.png")

print("Ending call...")
run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
run_adb(DEV_A, "input tap 540 2050")
time.sleep(1.5)
