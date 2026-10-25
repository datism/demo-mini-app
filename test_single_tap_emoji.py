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

def ensure_screen_on(device):
    out = run_adb(device, "dumpsys power")
    if "mWakefulness=Awake" not in out and "state=ON" not in out:
        run_adb(device, "input keyevent 26")
        time.sleep(1.0)
    run_adb(device, "input keyevent 82")
    time.sleep(0.5)

def clear_logcat(device):
    run_cmd([ADB, "-s", device, "logcat", "-c"])

def get_logcat(device, last_n=300):
    res = subprocess.run([ADB, "-s", device, "logcat", "-d"], capture_output=True, text=True, errors="ignore")
    lines = res.stdout.strip().split("\n")
    return "\n".join(lines[-last_n:])

def capture_screenshot(device, filename):
    run_adb(device, f"screencap -p /sdcard/{filename}")
    run_cmd([ADB, "-s", device, "pull", f"/sdcard/{filename}", f"D:\\project\\demo-mini-app\\{filename}"])
    size = os.path.getsize(f"D:\\project\\demo-mini-app\\{filename}") if os.path.exists(f"D:\\project\\demo-mini-app\\{filename}") else 0
    print(f"  📸 [SCREENSHOT] {filename} saved ({size:,} bytes)")

def main():
    print("=" * 80)
    print("   TEST THẢ EMOJI ĐƠN LẺ (1 CLICK = ĐÚNG 1 EMOJI DUY NHẤT)   ")
    print("=" * 80)

    ensure_screen_on(DEV_A)
    ensure_screen_on(DEV_B)

    # 1. Reset ứng dụng
    print("\n--- [BƯỚC 1] Khởi chạy ứng dụng & Đăng ký SIP ---")
    run_adb(DEV_A, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_B, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    run_adb(DEV_B, "am force-stop com.demo.imsdc")
    time.sleep(1.0)

    run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.5)

    for dev in [DEV_A, DEV_B]:
        run_adb(dev, "input tap 540 1663")
        time.sleep(1.0)

    clear_logcat(DEV_A)
    clear_logcat(DEV_B)

    # 2. Thiết lập cuộc gọi
    print("\n--- [BƯỚC 2] Thiết lập cuộc gọi P2P (Máy A gọi Máy B) ---")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
    time.sleep(2.0)

    run_adb(DEV_B, "input swipe 880 1930 200 1930 300")
    time.sleep(0.5)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
    time.sleep(5.0)

    # 3. Mở Fun-Calling mini-app
    print("\n--- [BƯỚC 3] Máy A chọn Fun-Calling (Thả tim tương tác) ---")
    run_adb(DEV_A, "input tap 540 1363")
    time.sleep(4.0)

    # Clear logcat trước khi bấm 1 emoji
    clear_logcat(DEV_A)
    clear_logcat(DEV_B)

    # 4. Máy A bấm biểu cảm Trái tim (❤️) ĐÚNG 1 LẦN (X=205, Y=1660)
    print("\n--- [BƯỚC 4] Máy A bấm Trái tim (❤️) ĐÚNG 1 LẦN DUY NHẤT ---")
    run_adb(DEV_A, "input tap 205 1660")
    time.sleep(2.0)

    capture_screenshot(DEV_A, "single_tap_1_heart_a.png")
    capture_screenshot(DEV_B, "single_tap_1_heart_b.png")

    time.sleep(0.5)

    log_a_heart = get_logcat(DEV_A, 100)
    log_b_heart = get_logcat(DEV_B, 100)

    print("\n[Log phát từ Máy A]:")
    a_sends = []
    for line in log_a_heart.split("\n"):
        if any(k in line for k in ["JS → Native", "sendOrQueueAdc", "ADC (stream 1000) sent", "render_emoji", "send_emoji"]):
            a_sends.append(line)
            print("  A ->", line)

    print("\n[Log nhận tại Máy B]:")
    b_receives = []
    for line in log_b_heart.split("\n"):
        if any(k in line for k in ["ADC → WebView JS", "Heart received", "Received payload", "render_emoji"]):
            b_receives.append(line)
            print("  B <-", line)

    print(f"\n>> Số lần phát đi từ Máy A: {len(a_sends)}")
    print(f">> Số sự kiện nhận tại Máy B: {len(b_receives)}")

    # 5. Máy B bấm biểu cảm Thích (👍) ĐÚNG 1 LẦN (X=350, Y=1660)
    print("\n--- [BƯỚC 5] Máy B bấm Thích (👍) ĐÚNG 1 LẦN DUY NHẤT ---")
    clear_logcat(DEV_A)
    clear_logcat(DEV_B)

    run_adb(DEV_B, "input tap 350 1660")
    time.sleep(1.0)

    capture_screenshot(DEV_B, "single_tap_2_like_b.png")
    capture_screenshot(DEV_A, "single_tap_2_like_a.png")

    time.sleep(0.5)

    log_b_like = get_logcat(DEV_B, 100)
    log_a_like = get_logcat(DEV_A, 100)

    print("\n[Log phát từ Máy B]:")
    b_sends = []
    for line in log_b_like.split("\n"):
        if any(k in line for k in ["JS → Native", "sendOrQueueAdc", "ADC (stream 1000) sent", "render_emoji", "send_emoji"]):
            b_sends.append(line)
            print("  B ->", line)

    print("\n[Log nhận tại Máy A]:")
    a_receives = []
    for line in log_a_like.split("\n"):
        if any(k in line for k in ["ADC → WebView JS", "Received payload", "render_emoji"]):
            a_receives.append(line)
            print("  A <-", line)

    print(f"\n>> Số lần phát đi từ Máy B: {len(b_sends)}")
    print(f">> Số sự kiện nhận tại Máy A: {len(a_receives)}")

    # 6. Kết thúc cuộc gọi
    print("\n--- [BƯỚC 6] Kết thúc cuộc gọi ---")
    run_adb(DEV_A, "input tap 250 1934")
    time.sleep(1.5)

    print("\n" + "=" * 80)
    # 1 send call should produce exactly 1 ADC receive on peer
    success = (len(a_sends) == 2 and len(b_receives) >= 1 and len(b_receives) <= 2) # JS log + sendOrQueueAdc log = 2 log lines per 1 send
    print(f"KẾT QUẢ KIỂM THỬ: 1 Click = Đúng 1 Gói Tin / 1 Emoji: {success}")
    print("=" * 80)

if __name__ == "__main__":
    main()
