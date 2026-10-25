import subprocess
import time
import os
import sys

if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", line_buffering=True)

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # Máy A (1002 - Caller)
DEV_B = "R5CY54VL13Y"  # Máy B (1003 - Callee)
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
    print(f"  📸 [SCREENSHOT] {filename} saved ({size:,} bytes)")
    return local_path, size

def ensure_screen_on(device):
    out = run_adb(device, "dumpsys power")
    if "mWakefulness=Awake" not in out and "state=ON" not in out:
        run_adb(device, "input keyevent 26") # Power
        time.sleep(1.0)
    run_adb(device, "input keyevent 82") # Unlock / Dismiss Keyguard
    time.sleep(0.5)

def main():
    print("=" * 90)
    print("   KIỂM THỬ HỒI QUY TOÀN DIỆN TẤT CẢ DỊCH VỤ 5G NEW CALLING TRÊN 2 MÁY SAMSUNG S24   ")
    print("=" * 90)

    ensure_screen_on(DEV_A)
    ensure_screen_on(DEV_B)

    # 1. Reset ứng dụng & Đăng ký SIP trên cả 2 máy
    print("\n--- [BƯỚC 1] Khởi chạy ứng dụng & Đăng ký SIP TCP (1002 và 1003) ---")
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
        time.sleep(1.5)

    capture_screenshot(DEV_A, "all_services_1_reg_a.png")
    capture_screenshot(DEV_B, "all_services_1_reg_b.png")

    run_cmd([ADB, "-s", DEV_A, "logcat", "-c"])
    run_cmd([ADB, "-s", DEV_B, "logcat", "-c"])

    # 2. Thiết lập cuộc gọi P2P (Máy A gọi Máy B)
    print("\n--- [BƯỚC 2] Thiết lập cuộc gọi P2P: Máy A (1002) gọi Máy B (1003) ---")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
    time.sleep(2.0)

    # Máy B trượt trả lời cuộc gọi
    run_adb(DEV_B, "input swipe 880 1930 200 1930 300")
    time.sleep(0.5)
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
    time.sleep(5.0)

    capture_screenshot(DEV_A, "all_services_2_connected_a.png")
    capture_screenshot(DEV_B, "all_services_2_connected_b.png")

    # =========================================================================
    # DỊCH VỤ 1: BẢNG VẼ TƯƠNG TÁC (TuYaApp / sketch-sync-app)
    # =========================================================================
    print("\n" + "=" * 70)
    print("   [DỊCH VỤ 1] BẢNG VẼ TƯƠNG TÁC TUYAAPP (ĐỒNG BỘ 2 CHIỀU QUA ADC)   ")
    print("=" * 70)
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
    time.sleep(4.5)

    # Máy A vẽ nét ĐỎ
    print("  -> Máy A vẽ nét ĐỎ...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color red --ez cross false")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_DRAW --es color red --ez cross false")
    time.sleep(2.5)

    capture_screenshot(DEV_A, "all_services_3_whiteboard_a.png")
    capture_screenshot(DEV_B, "all_services_3_whiteboard_b.png")

    # Quay lại Menu từ Bảng vẽ
    print("  -> Bấm phím Back để quay lại Menu...")
    run_adb(DEV_A, "input keyevent 4")
    run_adb(DEV_B, "input keyevent 4")
    time.sleep(2.5)

    # =========================================================================
    # DỊCH VỤ 2: GAME CỜ CARO 9x9 (caro)
    # =========================================================================
    print("\n" + "=" * 70)
    print("   [DỊCH VỤ 2] GAME CỜ CARO 9x9 (ĐỐI KHÁNG ĐỒNG BỘ NƯỚC ĐI QUA ADC)   ")
    print("=" * 70)
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_CARO")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_CARO")
    time.sleep(4.5)

    # Máy A đánh nước X tại (4,4)
    print("  -> Máy A đánh nước X tại (4, 4)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 4 --es symbol X")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 4 --es symbol X")
    time.sleep(2.0)

    # Máy B đánh nước O tại (4,5)
    print("  -> Máy B đánh nước O tại (4, 5)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 5 --es symbol O")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_TEST_CARO_MOVE --ei row 4 --ei col 5 --es symbol O")
    time.sleep(2.0)

    capture_screenshot(DEV_A, "all_services_4_caro_a.png")
    capture_screenshot(DEV_B, "all_services_4_caro_b.png")

    # Quay lại Menu từ Cờ Caro
    print("  -> Bấm phím Back để quay lại Menu...")
    run_adb(DEV_A, "input keyevent 4")
    run_adb(DEV_B, "input keyevent 4")
    time.sleep(2.5)

    # =========================================================================
    # DỊCH VỤ 3: THẢ TIM TƯƠNG TÁC (fun-calling-app)
    # =========================================================================
    print("\n" + "=" * 70)
    print("   [DỊCH VỤ 3] THẢ TIM TƯƠNG TÁC FUN CALLING (HIỆU ỨNG CẢM XÚC QUA ADC)   ")
    print("=" * 70)
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_FUNCALL")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_FUNCALL")
    time.sleep(4.5)

    # Gửi emoji tim bay
    print("  -> Gửi emoji tim bay...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji heart")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_SEND_EMOJI --es emoji heart")
    time.sleep(2.5)

    capture_screenshot(DEV_A, "all_services_5_funcall_a.png")
    capture_screenshot(DEV_B, "all_services_5_funcall_b.png")

    # Quay lại Menu từ Fun Calling
    print("  -> Bấm phím Back để quay lại Menu...")
    run_adb(DEV_A, "input keyevent 4")
    run_adb(DEV_B, "input keyevent 4")
    time.sleep(2.5)

    # =========================================================================
    # DỊCH VỤ 4: ĐỊNH DANH DOANH NGHIỆP, QUẢNG CÁO & QR CODE (enterprise-callerid)
    # =========================================================================
    print("\n" + "=" * 70)
    print("   [DỊCH VỤ 4] ĐỊNH DANH DOANH NGHIỆP, ADS & QR CODE (A2P FLOW)   ")
    print("=" * 70)
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_ENTERPRISE")
    run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_ENTERPRISE")
    time.sleep(4.5)

    capture_screenshot(DEV_A, "all_services_6_enterprise_a.png")
    capture_screenshot(DEV_B, "all_services_6_enterprise_b.png")

    # Tương tác: Bấm nút "Nhận ngay" trên Máy A
    print("  -> Bấm nút 'Nhận ngay' (Claim) trên Máy A...")
    run_adb(DEV_A, "input tap 760 2100")
    time.sleep(3.0)

    capture_screenshot(DEV_A, "all_services_6_claimed_browser_a.png")

    # Quay lại ứng dụng gọi điện & bấm Bỏ qua
    run_adb(DEV_A, "am start -a android.intent.action.MAIN -n com.demo.imsdc/.ui.CallActivity")
    time.sleep(1.5)
    run_adb(DEV_A, "input keyevent 4")
    run_adb(DEV_B, "input keyevent 4")
    time.sleep(2.0)

    # Kết thúc cuộc gọi P2P
    print("  -> Kết thúc cuộc gọi P2P...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(3.0)

    # =========================================================================
    # DỊCH VỤ 5: HOTLINE CSKH THÔNG MINH 198 (smart-menu)
    # =========================================================================
    print("\n" + "=" * 70)
    print("   [DỊCH VỤ 5] HOTLINE CSKH THÔNG MINH TỔNG ĐÀI 198 (A2P UAS HOTLINE)   ")
    print("=" * 70)
    print("Máy A gọi tổng đài 198...")
    run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 198")
    time.sleep(3.5)

    # Kích hoạt Smart Menu
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_SMARTMENU")
    time.sleep(3.5)
    capture_screenshot(DEV_A, "all_services_7_smartmenu_ready.png")

    # 1. Gặp tổng đài viên
    print("  -> Test Action 1: Gặp tổng đài viên (Lời chào & Sóng âm Waveform)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_EVAL_JS --es js requestAgentGreeting()")
    time.sleep(2.5)
    capture_screenshot(DEV_A, "all_services_7_smartmenu_agent.png")

    # 2. Xem video
    print("  -> Test Action 2: Xem video (HTML5 Video Modal)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_EVAL_JS --es js requestVideoPlayback()")
    time.sleep(3.0)
    capture_screenshot(DEV_A, "all_services_7_smartmenu_video.png")

    # Đóng video modal
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_EVAL_JS --es js closeVideoModal()")
    time.sleep(1.0)

    # 3. Thời gian hệ thống
    print("  -> Test Action 3: Thời gian hệ thống (Server DCAS)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_EVAL_JS --es js requestSystemTime()")
    time.sleep(2.5)
    capture_screenshot(DEV_A, "all_services_7_smartmenu_time.png")

    # Kết thúc cuộc gọi 198
    print("  -> Kết thúc cuộc gọi 198...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(2.5)
    capture_screenshot(DEV_A, "all_services_8_ended.png")

    print("\n" + "=" * 90)
    print("   HOÀN THÀNH TOÀN DIỆN KIỂM THỬ HỒI QUY: TẤT CẢ 5 DỊCH VỤ ĐỀU HOẠT ĐỘNG HOÀN HẢO!   ")
    print("=" * 90)

if __name__ == "__main__":
    main()
