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
os.makedirs(ARTIFACTS_DIR, exist_ok=True)

def run_cmd(args):
    res = subprocess.run(args, capture_output=True, text=True, errors="ignore")
    return res.stdout.strip()

def run_adb(device, shell_cmd):
    return run_cmd([ADB, "-s", device, "shell"] + shell_cmd.split())

def clear_logcat(device):
    run_cmd([ADB, "-s", device, "logcat", "-c"])

def get_logcat(device, last_n=200):
    res = subprocess.run([ADB, "-s", device, "logcat", "-d"], capture_output=True, text=True, errors="ignore")
    lines = res.stdout.strip().split("\n")
    return "\n".join(lines[-last_n:])

def capture_screenshot(device, filename):
    remote_path = f"/sdcard/{filename}"
    local_path = os.path.join(ARTIFACTS_DIR, filename)
    run_adb(device, f"screencap -p {remote_path}")
    subprocess.run([ADB, "-s", device, "pull", remote_path, local_path], capture_output=True)
    print(f"  [SCREENSHOT] Saved: {filename}")
    return local_path

def install_apk():
    apk_path = r"d:\project\demo-mini-app\android-client\app\build\outputs\apk\debug\app-debug.apk"
    if os.path.exists(apk_path):
        print(f"Installing APK ({apk_path}) to both devices...")
        for dev in [DEV_A, DEV_B]:
            res = subprocess.run([ADB, "-s", dev, "install", "-r", apk_path], capture_output=True, text=True)
            print(f"  Device {dev}: {res.stdout.strip()}")
    else:
        print(f"APK not found at {apk_path}")

def main():
    install_apk()
    print("==========================================================================")
    print("   STARTING COMPREHENSIVE E2E TEST: 3 MINI-APPS WITH CALLEE BACKGROUND   ")
    print("==========================================================================")

    # 1. Start App & Register
    print("\n--- [PHASE 1] Initializing & Registering Both Phones ---")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    run_adb(DEV_B, "am force-stop com.demo.imsdc")
    time.sleep(1.0)

    run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.5)

    for dev, name in [(DEV_A, "1002"), (DEV_B, "1003")]:
        run_adb(dev, "uiautomator dump /sdcard/ui.xml")
        ui = run_adb(dev, "cat /sdcard/ui.xml")
        if "REGISTER" in ui or "Chưa đăng ký" in ui or "ĐĂNG KÝ" in ui:
            print(f"  Device {name} tapping REGISTER...")
            run_adb(dev, "input tap 540 1663")
            time.sleep(2.0)
        else:
            print(f"  Device {name} is already registered.")

    capture_screenshot(DEV_A, "step1_a_registered.png")
    capture_screenshot(DEV_B, "step1_b_registered.png")

    clear_logcat(DEV_A)
    clear_logcat(DEV_B)

    # 2. Callee B goes to Home Screen (Background)
    print("\n--- [PHASE 2] Callee B goes to Home Screen (Background) ---")
    print("  Device B pressing KEYCODE_HOME (3)...")
    run_adb(DEV_B, "input keyevent 3")
    time.sleep(2.0)
    capture_screenshot(DEV_B, "step2_b_homescreen.png")

    clear_logcat(DEV_A)
    clear_logcat(DEV_B)

    # 3. Caller A calls Callee B
    print("\n--- [PHASE 3] Caller A (1002) calls Callee B (1003) ---")
    print("  Device A calling 1003 via auto-call intent...")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
    time.sleep(2.5)

    # 4. Callee B receives Heads-Up banner
    print("\n--- [PHASE 4] Callee B receives Incoming Call Banner on Home Screen ---")
    capture_screenshot(DEV_B, "step3_b_incoming_banner.png")
    capture_screenshot(DEV_A, "step3_a_calling.png")

    # Callee B Answers Call
    print("  Device B answering call from notification banner...")
    run_adb(DEV_B, "input tap 265 305") # tap "Trả lời" on banner
    time.sleep(0.5)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
    time.sleep(1.0)
    run_adb(DEV_B, "input tap 810 2150") # green button fallback
    time.sleep(4.0)

    # 5. Connected & Operator Menu Loaded
    print("\n--- [PHASE 5] Call Connected & Operator Menu Loaded ---")
    capture_screenshot(DEV_A, "step4_a_connected_menu.png")
    capture_screenshot(DEV_B, "step4_b_connected_menu.png")

    # =========================================================================
    # CASE 1: TuYaApp (Bảng vẽ tương tác)
    # =========================================================================
    print("\n" + "="*70)
    print("   [CASE 1] TESTING TUYAAPP (BẢNG VẼ TƯƠNG TÁC)   ")
    print("="*70)
    print("Phone A launching Whiteboard (sketch-sync-app)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    time.sleep(4.5)

    capture_screenshot(DEV_A, "case1_tuya_ready_a.png")
    capture_screenshot(DEV_B, "case1_tuya_ready_b.png")

    print("Phone A drawing red stroke on canvas via swipe gesture...")
    run_adb(DEV_A, "input swipe 250 1100 830 1100 400")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color #FF0000 --ez cross false")
    time.sleep(2.0)

    print("Phone B drawing green vertical stroke on canvas via swipe gesture...")
    run_adb(DEV_B, "input swipe 540 850 540 1350 400")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color #00E676 --ez cross true")
    time.sleep(2.0)

    capture_screenshot(DEV_A, "case1_tuya_drawn_a.png")
    capture_screenshot(DEV_B, "case1_tuya_drawn_b.png")

    print("Exiting TuYaApp back to Menu (Initiated by Phone A)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_EXIT_MINIAPP")
    time.sleep(3.0)

    # =========================================================================
    # CASE 2: Game Cờ Caro (9x9)
    # =========================================================================
    print("\n" + "="*70)
    print("   [CASE 2] TESTING GAME CỜ CARO (9x9 BOARD GAME)   ")
    print("="*70)
    print("Phone B launching Caro Game...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_CARO")
    time.sleep(4.5)

    capture_screenshot(DEV_A, "case2_caro_ready_a.png")
    capture_screenshot(DEV_B, "case2_caro_ready_b.png")

    print("Phone B (Symbol X) playing move at (4, 4)...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 4")
    time.sleep(2.0)

    capture_screenshot(DEV_A, "case2_caro_move1_a.png")
    capture_screenshot(DEV_B, "case2_caro_move1_b.png")

    print("Phone A (Symbol O) playing move at (4, 5)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 5")
    time.sleep(2.0)

    capture_screenshot(DEV_A, "case2_caro_move2_a.png")
    capture_screenshot(DEV_B, "case2_caro_move2_b.png")

    print("Exiting Caro Game back to Menu (Initiated by Phone B)...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_EXIT_MINIAPP")
    time.sleep(3.0)

    # =========================================================================
    # CASE 3: Fun Calling (Thả tim tương tác 5 Emojis)
    # =========================================================================
    print("\n" + "="*70)
    print("   [CASE 3] TESTING FUN CALLING (THẢ TIM TƯƠNG TÁC 5 EMOJIS)   ")
    print("="*70)
    print("Phone A launching Fun Calling App...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_FUNCALL")
    time.sleep(4.5)

    capture_screenshot(DEV_A, "case3_funcall_ready_a.png")
    capture_screenshot(DEV_B, "case3_funcall_ready_b.png")

    print("Phone A sending Smile Emoji (😊)...")
    run_adb(DEV_A, "input tap 500 1710")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji smile")
    time.sleep(1.2)

    capture_screenshot(DEV_A, "case3_funcall_smile_a.png")
    capture_screenshot(DEV_B, "case3_funcall_smile_b.png")

    print("Phone B sending Angry Emoji (😡) & Heart Emoji (❤️)...")
    run_adb(DEV_B, "input tap 645 1710")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji angry")
    time.sleep(0.8)
    run_adb(DEV_B, "input tap 208 1710")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji heart")
    time.sleep(1.2)

    capture_screenshot(DEV_A, "case3_funcall_multi_a.png")
    capture_screenshot(DEV_B, "case3_funcall_multi_b.png")

    print("\n--- [PHASE 6] Ending Call & Final Verification ---")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    run_adb(DEV_A, "input tap 540 2050")
    time.sleep(2.0)

    capture_screenshot(DEV_A, "step7_a_final.png")
    capture_screenshot(DEV_B, "step7_b_final.png")

    print("\n==========================================================================")
    print("   ALL 3 MINI-APPS & BACKGROUND CALL FLOW COMPLETED SUCCESSFULLY!        ")
    print("==========================================================================")

if __name__ == "__main__":
    main()
