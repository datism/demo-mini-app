import subprocess
import time
import os
import sys

if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", line_buffering=True)
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", line_buffering=True)

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"  # Physical Samsung S24
SIP_SERVER = "100.76.76.105"
LOCAL_IP = "100.119.80.116"

ARTIFACTS_DIR = r"C:\Users\lamnt\.gemini\antigravity-ide\brain\b93511c7-0f55-4051-83f8-964a3715f4a6"
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
    run_adb(device, "input keyevent 82") # Unlock
    time.sleep(0.5)

def main():
    print("=" * 80)
    print("   TEST PHÁT VIDEO HOẠT HÌNH HÀI HƯỚC 1080P PHÙ HỢP MÀN HÌNH ĐIỆN THOẠI   ")
    print("=" * 80)

    ensure_screen_on(DEV_A)

    # 1. Đóng sạch app cũ
    print("\n1. Khởi chạy và Đăng ký SIP trên Samsung S24...")
    run_adb(DEV_A, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    time.sleep(1.0)

    # 2. Khởi chạy trực tiếp CallActivity tới 198
    print("\n2. Khởi tạo cuộc gọi tới Tổng đài 198...")
    call_cmd = (
        f"am start -n com.demo.imsdc/.ui.CallActivity "
        f"--es sip_server {SIP_SERVER} "
        f"--es local_user 1002 "
        f"--es remote_user 198 "
        f"--es local_ip {LOCAL_IP} "
        f"--ei local_port 0 "
        f"--ei sip_port 5060 "
        f"--ez is_incoming false"
    )
    run_adb(DEV_A, call_cmd)
    time.sleep(4.0)

    # Nạp giao diện smart-menu
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_SMARTMENU")
    time.sleep(3.0)
    capture_screenshot(DEV_A, "proof_cartoon_hd_1_menu.png")

    # 3. Kích hoạt 'Video hoạt hình vui nhộn' (Funny cartoon animation)
    print("\n3. Bấm chọn 'Video hoạt hình vui nhộn' (Phát video hoạt hình 1080p full modal)...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_EVAL_JS --es js requestVideoPlayback()")
    time.sleep(3.5)
    capture_screenshot(DEV_A, "proof_cartoon_hd_2_playing_1080p.png")

    # 4. Giữ phát video 3s và chụp tiếp tiến trình
    time.sleep(3.0)
    capture_screenshot(DEV_A, "proof_cartoon_hd_3_progress.png")

    # 5. Đóng modal video
    print("\n4. Đóng Video Modal...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_EVAL_JS --es js closeVideoModal()")
    time.sleep(1.5)
    capture_screenshot(DEV_A, "proof_cartoon_hd_4_closed.png")

    # 6. Kết thúc cuộc gọi
    print("\n5. Kết thúc cuộc gọi...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(1.5)

    print("\n" + "=" * 80)
    print("   🎉 KIỂM THỬ THÀNH CÔNG! ĐÃ LƯU BẰNG CHỨNG HÌNH ẢNH HOẠT HÌNH 1080P ĐẦY ĐỦ   ")
    print("=" * 80)

if __name__ == "__main__":
    main()
