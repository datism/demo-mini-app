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
    print("=== TEST HOTLINE 198 MENU ISOLATION ===")
    # 1. Stop current app
    run_adb("shell am force-stop com.demo.imsdc")
    time.sleep(1)

    # 2. Launch Dialer
    print("1. Launching 5G New Calling dialer...")
    run_adb("shell am start -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2)

    # 3. Call 198
    print("2. Calling Hotline 198...")
    run_adb("shell am start -n com.demo.imsdc/.ui.CallActivity --es sip_server 100.111.73.110 --es local_user 1002 --es remote_user 198 --es local_ip 100.119.80.116")
    time.sleep(5)

    # 4. Take screenshot of 198 initial screen
    run_adb("shell screencap -p /sdcard/hotline198_init.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/hotline198_init.png ./hotline198_init.png', shell=True)
    print("-> Captured hotline198_init.png")

    # 5. Click "Gặp tổng đài viên" (Y around 1000)
    print("3. Tapping 'Gặp tổng đài viên'...")
    run_adb("shell input tap 540 980")
    time.sleep(2)

    run_adb("shell screencap -p /sdcard/hotline198_agent.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/hotline198_agent.png ./hotline198_agent.png', shell=True)
    print("-> Captured hotline198_agent.png")

    # 6. Click "Làm mới Menu Tổng đài 198" (bottom button, Y around 1850-1950)
    print("4. Tapping 'Làm mới Menu Tổng đài 198'...")
    run_adb("shell input tap 540 1880")
    time.sleep(2)

    run_adb("shell screencap -p /sdcard/hotline198_refreshed.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/hotline198_refreshed.png ./hotline198_refreshed.png', shell=True)
    print("-> Captured hotline198_refreshed.png")

    # 7. Press Hardware Back Key
    print("5. Pressing Hardware Back Key...")
    run_adb("shell input keyevent 4")
    time.sleep(2)

    run_adb("shell screencap -p /sdcard/hotline198_after_back.png")
    subprocess.run(f'"{ADB}" -s {PHONE_A} pull /sdcard/hotline198_after_back.png ./hotline198_after_back.png', shell=True)
    print("-> Captured hotline198_after_back.png")

    # 8. End Call
    print("6. Ending call...")
    run_adb("shell am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(1)

    print("=== TEST COMPLETED ===")

if __name__ == "__main__":
    main()
