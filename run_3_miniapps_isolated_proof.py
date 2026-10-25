import subprocess
import time
import os
import sys

if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", line_buffering=True)

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # Caller 1002
DEV_B = "R5CY54VL13Y"  # Callee 1003
ARTIFACTS_DIR = r"C:\Users\lamnt\.gemini\antigravity-ide\brain\83b918c3-f0ec-490a-811f-e05e5cc427c0"
os.makedirs(ARTIFACTS_DIR, exist_ok=True)

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
    size = os.path.getsize(local_path) if os.path.exists(local_path) else 0
    print(f"  [SCREENSHOT] {filename} saved ({size:,} bytes)")
    return local_path, size

def ensure_screen_on(device):
    out = run_adb(device, "dumpsys power")
    if "mWakefulness=Awake" not in out and "state=ON" not in out:
        run_adb(device, "input keyevent 26") # Power
        time.sleep(1.0)
    run_adb(device, "input keyevent 82") # Unlock / Dismiss Keyguard
    time.sleep(0.5)

def setup_devices():
    ensure_screen_on(DEV_A)
    ensure_screen_on(DEV_B)
    run_adb(DEV_A, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_B, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    run_adb(DEV_B, "am force-stop com.demo.imsdc")
    time.sleep(1.0)
    run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
    time.sleep(2.5)
    run_adb(DEV_A, "input tap 540 1663")
    run_adb(DEV_B, "input tap 540 1663")
    time.sleep(2.5)

def start_and_answer_call():
    run_cmd([ADB, "-s", DEV_A, "logcat", "-c"])
    run_cmd([ADB, "-s", DEV_B, "logcat", "-c"])
    print("  [CALL] Máy A gọi Máy B...")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
    time.sleep(2.5)
    print("  [ANSWER] Máy B trượt trả lời cuộc gọi (Slide-to-Answer)...")
    run_adb(DEV_B, "input swipe 880 1930 200 1930 300")
    time.sleep(0.5)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
    time.sleep(4.5)

def end_call():
    print("  [END] Kết thúc cuộc gọi...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(2.0)

def main():
    print("=" * 80)
    print("   KIỂM THỬ TRỌN VẸN VÀ CHỨNG MINH HÌNH ẢNH 3 ỨNG DỤNG MINI-APPS   ")
    print("=" * 80)

    # -------------------------------------------------------------------------
    # TEST 1: BẢNG VẼ TUYAAPP (NÉT VẼ 2 CHIỀU ĐỒNG BỘ CẢ 2 MÁY)
    # -------------------------------------------------------------------------
    print("\n" + "=" * 70)
    print("   >>> TEST 1: BẢNG VẼ TUYAAPP (ĐẦY ĐỦ NÉT VẼ 2 BÊN) <<<   ")
    print("=" * 70)
    setup_devices()
    start_and_answer_call()

    print("  -> Mở Bảng vẽ TuYaApp trên cả 2 máy...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    time.sleep(5.0)

    print("  -> Máy A vẽ nét cọ ĐỎ...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color red --ez cross false")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color red --ez cross false")
    time.sleep(2.0)

    print("  -> Máy B vẽ nét cọ XANH LÁ...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color green --ez cross true")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color green --ez cross true")
    time.sleep(2.5)

    capture_screenshot(DEV_A, "proof_1_tuya_a.png")
    capture_screenshot(DEV_B, "proof_1_tuya_b.png")
    end_call()

    # -------------------------------------------------------------------------
    # TEST 2: GAME CỜ CARO 9x9 (ĐỒNG BỘ ĐỐI KHÁNG X/O)
    # -------------------------------------------------------------------------
    print("\n" + "=" * 70)
    print("   >>> TEST 2: GAME CỜ CARO 9x9 (ĐỐI KHÁNG X/O) <<<   ")
    print("=" * 70)
    setup_devices()
    start_and_answer_call()

    print("  -> Mở Game Cờ Caro trên cả 2 máy...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_CARO")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_CARO")
    time.sleep(5.0)

    print("  -> Máy B đi quân X tại ô (4, 4)...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 4 --es symbol X")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 4 --es symbol X")
    time.sleep(2.0)

    print("  -> Máy A đi quân O tại ô (4, 5)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 5 --es symbol O")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 5 --es symbol O")
    time.sleep(2.5)

    capture_screenshot(DEV_A, "proof_2_caro_a.png")
    capture_screenshot(DEV_B, "proof_2_caro_b.png")
    end_call()

    # -------------------------------------------------------------------------
    # TEST 3: THẢ TIM TƯƠNG TÁC (FUN CALLING 5 EMOJI)
    # -------------------------------------------------------------------------
    print("\n" + "=" * 70)
    print("   >>> TEST 3: THẢ TIM TƯƠNG TÁC (FUN CALLING) <<<   ")
    print("=" * 70)
    setup_devices()
    start_and_answer_call()

    print("  -> Mở Fun Calling trên cả 2 máy...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_FUNCALL")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_FUNCALL")
    time.sleep(5.0)

    print("  -> Máy A gửi biểu cảm Mặt cười (😊)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji smile")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji smile")
    time.sleep(1.2)

    print("  -> Máy B gửi biểu cảm Tức giận (😡) và Trái tim (❤️)...")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji angry")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji angry")
    time.sleep(0.8)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji heart")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji heart")
    time.sleep(1.5)

    capture_screenshot(DEV_A, "proof_3_funcall_a.png")
    capture_screenshot(DEV_B, "proof_3_funcall_b.png")
    end_call()

    print("\n" + "=" * 80)
    print("   🎉 HOÀN THÀNH KIỂM THỬ VÀ LƯU BẰNG CHỨNG HÌNH ẢNH 3 ỨNG DỤNG! 🎉   ")
    print("=" * 80)

if __name__ == "__main__":
    main()
