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
    print("=== TEST DIRECT CALL TO HOTLINE 198 ===")
    run_adb("shell am force-stop com.demo.imsdc")
    time.sleep(1)

    print("1. Launching CallActivity to 198...")
    run_adb("shell am start -n com.demo.imsdc/.ui.CallActivity --es sip_server 100.76.76.105 --es local_user 1002 --es remote_user 198 --es local_ip 100.119.80.116")
    time.sleep(5)

    print("2. Capturing 198 Smart Menu initial screen...")
    run_adb("shell screencap -p /sdcard/verify_198_init.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/verify_198_init.png ./verify_198_init.png', shell=True)

    # Tap "Gặp tổng đài viên" (Y around 950)
    print("3. Tapping 'Gặp tổng đài viên'...")
    run_adb("shell input tap 540 950")
    time.sleep(3)

    run_adb("shell screencap -p /sdcard/verify_198_agent.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/verify_198_agent.png ./verify_198_agent.png', shell=True)

    # Tap "Thời gian hệ thống" (Y around 1300)
    print("4. Tapping 'Thời gian hệ thống'...")
    run_adb("shell input tap 540 1300")
    time.sleep(2)

    run_adb("shell screencap -p /sdcard/verify_198_time.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/verify_198_time.png ./verify_198_time.png', shell=True)

    # Tap "Làm mới Menu Tổng đài 198" (bottom button, Y around 1880)
    print("5. Tapping 'Làm mới Menu Tổng đài 198'...")
    run_adb("shell input tap 540 1880")
    time.sleep(2)

    run_adb("shell screencap -p /sdcard/verify_198_refreshed.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/verify_198_refreshed.png ./verify_198_refreshed.png', shell=True)

    # Press Hardware Back Key
    print("6. Pressing Hardware Back Key...")
    run_adb("shell input keyevent 4")
    time.sleep(2)

    run_adb("shell screencap -p /sdcard/verify_198_after_back.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/verify_198_after_back.png ./verify_198_after_back.png', shell=True)

    # End Call
    print("7. Ending Call...")
    run_adb("shell input tap 540 2050")
    time.sleep(1)

    print("=== TEST COMPLETED ===")

if __name__ == "__main__":
    main()
