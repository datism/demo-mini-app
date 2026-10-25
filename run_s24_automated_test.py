import subprocess
import time
import sys
import os

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X" # 1002 (Caller)
DEV_B = "R5CY54VL13Y" # 1003 (Callee)

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

print("=== STEP 1: INITIALIZING BOTH PHONES ===")
run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
time.sleep(1.5)

# Ensure both are registered
for dev, name in [(DEV_A, "1002"), (DEV_B, "1003")]:
    run_adb(dev, "uiautomator dump /sdcard/ui.xml")
    ui = run_adb(dev, "cat /sdcard/ui.xml")
    if "REGISTER" in ui or "Chưa đăng ký" in ui:
        print(f"Device {name} tapping REGISTER at (540, 1663)...")
        run_adb(dev, "input tap 540 1663")
        time.sleep(1.5)
    else:
        print(f"Device {name} is already registered on dialer screen.")

clear_logcat(DEV_A)
clear_logcat(DEV_B)
print(f"Device A ({DEV_A} - 1002) ready.")
print(f"Device B ({DEV_B} - 1003) ready.")

print("\n=== STEP 2: PHONE A (1002) CALLS PHONE B (1003) ===")
# Tap call button at (540, 1856)
run_adb(DEV_A, "input tap 540 1856")

print("\n=== STEP 3: PHONE B (1003) RECEIVES CALL & TAPS ANSWER ===")
answered = False
for i in range(20):
    log_b = get_logcat(DEV_B, 30)
    if "Incoming INVITE" in log_b or "180 Ringing" in log_b or "RINGING" in log_b or "CallActivity" in log_b:
        print(f"Phone B received incoming call at iteration {i}!")
        time.sleep(1.0)
        run_adb(DEV_B, "input tap 820 1934")
        answered = True
        break
    time.sleep(0.5)

if not answered:
    print("Sending fallback tap to answer...")
    run_adb(DEV_B, "input tap 820 1934")

# Wait for Call Connection
connected = False
for i in range(20):
    log_a = get_logcat(DEV_A, 50)
    log_b = get_logcat(DEV_B, 50)
    if "ACK" in log_b or "200 OK" in log_a or "CONNECTED" in log_a or "callState=CONNECTED" in log_b:
        print(f"Call fully connected (checked at {i*0.5}s)!")
        connected = True
        break
    time.sleep(0.5)

print("\n=== STEP 4: WAITING FOR BDC & OPERATOR MENU TO LOAD ===")
time.sleep(3.5)

print("\n=== STEP 5: PHONE B (CALLEE) SELECTS FUN-CALLING MINI-APP (THA TIM TUONG TAC) FIRST ===")
print("Phone B clicking 'Tha tim tuong tac' at (540, 1363)...")
run_adb(DEV_B, "input tap 540 1363")

print("\n=== STEP 6: WAITING FOR ADC (STREAM 1001) TO REACH OPEN STATE ===")
adc_open = False
for i in range(30):
    time.sleep(0.5)
    log_a = get_logcat(DEV_A, 30)
    log_b = get_logcat(DEV_B, 30)
    b_ready = ("ADC2 (stream 1001) state: OPEN" in log_b or "ADC2 OPEN" in log_b)
    a_ready = ("ADC2 (stream 1001) state: OPEN" in log_a or "ADC2 OPEN" in log_a)
    if b_ready and a_ready:
        print(f"ADC Stream 1001 is OPEN on BOTH phones! (took {i*0.5}s)")
        adc_open = True
        break

if not adc_open:
    print("Waiting extra 2s for ADC initialization...")
    time.sleep(2)

print("\n=== STEP 7: PHONE B SENDS EMOJI (HEART) ===")
print("Phone B tapping Heart sticker button at (450, 1661)...")
run_adb(DEV_B, "input tap 450 1661")
time.sleep(1.0)
run_adb(DEV_B, "input tap 450 1661")
time.sleep(2)

print("\n=== STEP 8: ASSERTING EMOJI RECEPTION ON PHONE A ===")
log_a = get_logcat(DEV_A, 250)
log_b = get_logcat(DEV_B, 250)

emoji_received = False
print("\n--- Relevant Logs on Phone B (Sender) ---")
for line in log_b.split("\n"):
    if any(k in line for k in ["fun-calling", "send_emoji", "heart", "ADC2 (stream 1001) sent", "ADC2 sent", "ADC2 (stream 1001)", "Re-INVITE", "200 OK"]):
        print("  [Phone B]", line)

print("\n--- Relevant Logs on Phone A (Receiver) ---")
for line in log_a.split("\n"):
    if any(k in line for k in ["fun-calling", "render_emoji", "heart", "Received payload", "Heart received", "ADC received", "Re-INVITE", "200 OK"]):
        print("  [Phone A]", line)
        if "heart" in line.lower() and ("received" in line.lower() or "render_emoji" in line.lower()):
            emoji_received = True

print("\n=== STEP 9: ASSERTING 2-WAY VIDEO & AUDIO STREAMING ===")
time.sleep(2)
log_a = get_logcat(DEV_A, 300)
log_b = get_logcat(DEV_B, 300)

video_a_sent = False
video_a_recv = False
video_b_sent = False
video_b_recv = False

print("\n--- Video / Audio Streaming Logs on Phone A ---")
for line in log_a.split("\n"):
    if any(k in line for k in ["Video Packets", "Video Frames", "Audio Packets", "RTP Sockets bound", "VideoSink hook", "H.264 Video"]):
        print("  [Phone A]", line)
        if "Video Packets Sent" in line: video_a_sent = True
        if "Video Packets Received" in line or "Video Frames Rendered" in line: video_a_recv = True

print("\n--- Video / Audio Streaming Logs on Phone B ---")
for line in log_b.split("\n"):
    if any(k in line for k in ["Video Packets", "Video Frames", "Audio Packets", "RTP Sockets bound", "VideoSink hook", "H.264 Video"]):
        print("  [Phone B]", line)
        if "Video Packets Sent" in line: video_b_sent = True
        if "Video Packets Received" in line or "Video Frames Rendered" in line: video_b_recv = True

print("\n" + "="*70)
if emoji_received:
    print(">>> 1. EMOJI FUN-CALLING TEST: PASSED! <<<")
else:
    print(">>> 1. EMOJI FUN-CALLING TEST: CHECK LOGS <<<")

print(f">>> 2. VIDEO STREAMING TEST: Phone A Sent={video_a_sent}, Phone A Recv={video_a_recv} | Phone B Sent={video_b_sent}, Phone B Recv={video_b_recv} <<<")
print("="*70 + "\n")

