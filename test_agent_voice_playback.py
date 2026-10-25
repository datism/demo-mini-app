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
        time.sleep(0.5)
    run_adb(device, "input keyevent 82") # Unlock
    time.sleep(0.3)

def main():
    print("=" * 80)
    print("   TEST PHÁT ÂM THANH GIỌNG NÓI TỔNG ĐÀI VIÊN TRÊN TỔNG ĐÀI 198   ")
    print("=" * 80)

    ensure_screen_on(DEV_A)

    # 1. Đóng app cũ & xóa log
    print("\n1. Khởi động môi trường...")
    run_adb(DEV_A, "am force-stop com.samsung.android.dialer")
    run_adb(DEV_A, "am force-stop com.demo.imsdc")
    run_adb(DEV_A, "logcat -c")
    time.sleep(1.0)

    # 2. Khởi chạy cuộc gọi tới Tổng đài 198
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
    capture_screenshot(DEV_A, "proof_agent_voice_1_menu.png")

    # 3. Bấm vào Card 1: 'Gặp tổng đài viên' (Toạ độ 540, 990 hoặc requestAgentGreeting)
    print("\n3. Chạm vào thẻ 'Gặp tổng đài viên'...")
    run_adb(DEV_A, "input tap 540 990")
    time.sleep(2.0)
    capture_screenshot(DEV_A, "proof_agent_voice_2_speaking.png")

    # 4. Kiểm tra logcat phát âm thanh giọng nói
    print("\n4. Kiểm tra logcat ghi nhận phát âm thanh...")
    log_out = run_adb(DEV_A, "logcat -d")
    for line in log_out.splitlines():
        if any(k in line for k in ["MediaPlayer", "playAgentAudio", "agent_greeting", "SpeechSynthesis", "SmartMenu", "Waveform"]):
            print("  [LOG]", line)

    time.sleep(2.0)
    capture_screenshot(DEV_A, "proof_agent_voice_3_waveform.png")

    # 5. Kết thúc cuộc gọi và tắt màn hình
    print("\n5. Kết thúc cuộc gọi và đưa máy về trạng thái nghỉ...")
    run_adb(DEV_A, "am broadcast -a com.demo.imsdc.END_CALL")
    time.sleep(1.0)
    run_adb(DEV_A, "input keyevent 3")
    run_adb(DEV_A, "input keyevent 26")

    print("\n" + "=" * 80)
    print("   🎉 HOÀN THÀNH KIỂM THỬ PHÁT ÂM THANH GIỌNG NÓI TỔNG ĐÀI VIÊN!   ")
    print("=" * 80)

if __name__ == "__main__":
    main()
