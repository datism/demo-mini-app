import os
import sys
import time
import subprocess

sys.stdout.reconfigure(encoding='utf-8')

ADB = os.path.expandvars(r"$LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
PHONE_A = "R5CY54VL09X" # 1002

def run_adb(cmd, device=PHONE_A):
    full_cmd = f'"{ADB}" -s {device} {cmd}'
    res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True)
    return res.stdout.strip()

def main():
    print("=== TEST REGISTER & DIAL 198 ===")
    run_adb("shell am force-stop com.demo.imsdc")
    time.sleep(1)

    print("1. Launching MainActivity...")
    run_adb("shell am start -n com.demo.imsdc/.ui.MainActivity --es sip_server 100.76.76.105 --es username 1002 --es local_ip 100.119.80.116")
    time.sleep(2)

    # Click Register button if on setup screen (Y=1800)
    print("2. Tapping REGISTER button...")
    run_adb("shell input tap 540 1780")
    time.sleep(2)

    # Dial 1, 9, 8
    print("3. Typing 1-9-8 on keypad...")
    # Key 1
    run_adb("shell input tap 270 1450")
    time.sleep(0.3)
    # Key 9
    run_adb("shell input tap 810 1800")
    time.sleep(0.3)
    # Key 8
    run_adb("shell input tap 540 1800")
    time.sleep(0.5)

    # Click Call button
    print("4. Tapping Call button...")
    run_adb("shell input tap 540 2100")
    time.sleep(6)

    print("5. Capturing call screen with 198...")
    run_adb("shell screencap -p /sdcard/dial_198_connected.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/dial_198_connected.png ./dial_198_connected.png', shell=True)

    # Tap "Gặp tổng đài viên"
    print("6. Tapping 'Gặp tổng đài viên'...")
    run_adb("shell input tap 540 850")
    time.sleep(3)

    run_adb("shell screencap -p /sdcard/dial_198_agent_resp.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/dial_198_agent_resp.png ./dial_198_agent_resp.png', shell=True)

    # Tap "Làm mới Menu Tổng đài 198"
    print("7. Tapping 'Làm mới Menu Tổng đài 198'...")
    run_adb("shell input tap 540 1800")
    time.sleep(2)

    run_adb("shell screencap -p /sdcard/dial_198_refreshed.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/dial_198_refreshed.png ./dial_198_refreshed.png', shell=True)

    # Press Back key
    print("8. Testing Back key...")
    run_adb("shell input keyevent 4")
    time.sleep(2)

    run_adb("shell screencap -p /sdcard/dial_198_after_back.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/dial_198_after_back.png ./dial_198_after_back.png', shell=True)

    # End Call
    print("9. Ending Call...")
    run_adb("shell input tap 540 2050")
    time.sleep(1)

    print("=== TEST COMPLETED ===")

if __name__ == "__main__":
    main()
