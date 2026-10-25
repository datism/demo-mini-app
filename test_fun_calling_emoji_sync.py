import subprocess
import time
import os
import sys

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # 1002 (Caller)
DEV_B = "R5CY54VL13Y"  # 1003 (Callee)

def run_cmd(args):
    res = subprocess.run(args, capture_output=True, text=True, errors="ignore")
    return res.stdout.strip()

def run_adb(device, shell_cmd):
    return run_cmd([ADB, "-s", device, "shell"] + shell_cmd.split())

def clear_logcat(device):
    run_cmd([ADB, "-s", device, "logcat", "-c"])

def get_logcat(device, last_n=300):
    res = subprocess.run([ADB, "-s", device, "logcat", "-d"], capture_output=True, text=True, errors="ignore")
    lines = res.stdout.strip().split("\n")
    return "\n".join(lines[-last_n:])

def take_screenshot(device, filename):
    run_adb(device, f"screencap -p /sdcard/{filename}")
    run_cmd([ADB, "-s", device, "pull", f"/sdcard/{filename}", f"D:\\project\\demo-mini-app\\{filename}"])
    print(f"Captured screenshot {filename} from {device}")

def main():
    print("=== STEP 1: INITIALIZING BOTH PHONES ===")
    run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2)

    # Check registration
    for dev, name in [(DEV_A, "1002"), (DEV_B, "1003")]:
        run_adb(dev, "uiautomator dump /sdcard/ui.xml")
        ui = run_adb(dev, "cat /sdcard/ui.xml")
        if "REGISTER" in ui or "Chưa đăng ký" in ui:
            print(f"Device {name} tapping REGISTER at (540, 1663)...")
            run_adb(dev, "input tap 540 1663")
            time.sleep(1.5)
        else:
            print(f"Device {name} is registered.")

    clear_logcat(DEV_A)
    clear_logcat(DEV_B)

    print("\n=== STEP 2: PHONE A (1002) CALLS PHONE B (1003) ===")
    run_adb(DEV_A, "input tap 540 1856")
    time.sleep(1.5)

    print("\n=== STEP 3: PHONE B ANSWERS CALL ===")
    answered = False
    for i in range(20):
        log_b = get_logcat(DEV_B, 30)
        if "Incoming INVITE" in log_b or "180 Ringing" in log_b or "RINGING" in log_b or "CallActivity" in log_b:
            print(f"Phone B received call, answering at iteration {i}...")
            time.sleep(1.0)
            run_adb(DEV_B, "input tap 820 1934")
            answered = True
            break
        time.sleep(0.5)

    if not answered:
        print("Fallback tap to answer on Phone B...")
        run_adb(DEV_B, "input tap 820 1934")

    # Wait for call establishment
    time.sleep(3.5)
    print("Call connected. BDC & Operator menu loaded.")

    print("\n=== STEP 4: PHONE A SELECTS FUN-CALLING MINI-APP ===")
    print("Phone A tapping 'Thả tim tương tác' at (540, 1363)...")
    run_adb(DEV_A, "input tap 540 1363")
    time.sleep(3)

    # Clear logcat for clean emoji verification
    clear_logcat(DEV_A)
    clear_logcat(DEV_B)

    print("\n=== STEP 5: PHONE A TAPS HEART STICKER (❤️) ===")
    for _ in range(3):
        run_adb(DEV_A, "input tap 450 1661")
        time.sleep(0.3)
    
    time.sleep(1.0)
    take_screenshot(DEV_A, "screenshot_phone_a_heart.png")
    take_screenshot(DEV_B, "screenshot_phone_b_heart.png")

    # Check Phone A and Phone B logs for Heart
    log_a_heart = get_logcat(DEV_A, 200)
    log_b_heart = get_logcat(DEV_B, 200)

    print("\n--- Phone A Logs (Heart Sender) ---")
    heart_sent = False
    for line in log_a_heart.split("\n"):
        if any(k in line for k in ["fun-calling", "send_emoji", "heart", "ADC (stream 1000) sent", "ADC2 (stream 1001) sent", "IMSDataChannelBridge"]):
            print("  [Phone A]", line)
            if "heart" in line.lower() or "send_emoji" in line:
                heart_sent = True

    print("\n--- Phone B Logs (Heart Receiver) ---")
    heart_received = False
    for line in log_b_heart.split("\n"):
        if any(k in line for k in ["fun-calling", "render_emoji", "heart", "receiveData", "ADC → WebView JS", "Heart received"]):
            print("  [Phone B]", line)
            if "heart" in line.lower() or "render_emoji" in line or "receivedata" in line.lower():
                heart_received = True

    print(f"\n>> Heart Sent by Phone A: {heart_sent}")
    print(f">> Heart Received by Phone B: {heart_received}")

    print("\n=== STEP 6: PHONE B TAPS LIKE STICKER (👍) ===")
    clear_logcat(DEV_A)
    clear_logcat(DEV_B)

    for _ in range(3):
        run_adb(DEV_B, "input tap 540 1661")
        time.sleep(0.3)

    time.sleep(1.0)
    take_screenshot(DEV_B, "screenshot_phone_b_like.png")
    take_screenshot(DEV_A, "screenshot_phone_a_like.png")

    log_b_like = get_logcat(DEV_B, 200)
    log_a_like = get_logcat(DEV_A, 200)

    print("\n--- Phone B Logs (Like Sender) ---")
    like_sent = False
    for line in log_b_like.split("\n"):
        if any(k in line for k in ["fun-calling", "send_emoji", "like", "ADC (stream 1000) sent", "ADC2 (stream 1001) sent", "IMSDataChannelBridge"]):
            print("  [Phone B]", line)
            if "like" in line.lower() or "send_emoji" in line:
                like_sent = True

    print("\n--- Phone A Logs (Like Receiver) ---")
    like_received = False
    for line in log_a_like.split("\n"):
        if any(k in line for k in ["fun-calling", "render_emoji", "like", "receiveData", "ADC → WebView JS"]):
            print("  [Phone A]", line)
            if "like" in line.lower() or "render_emoji" in line or "receivedata" in line.lower():
                like_received = True

    print(f"\n>> Like Sent by Phone B: {like_sent}")
    print(f">> Like Received by Phone A: {like_received}")

    print("\n=== STEP 7: HANG UP ===")
    run_adb(DEV_A, "input tap 250 1934")
    time.sleep(1)

    print("\n==========================================")
    print(f"FINAL RESULT: 2-Way Emoji Sync Working: {heart_received and like_received}")
    print("==========================================")

if __name__ == "__main__":
    main()
