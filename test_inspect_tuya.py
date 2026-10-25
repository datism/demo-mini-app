import subprocess
import time
import os

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"
DEV_B = "R5CY54VL13Y"

def run_adb(dev, cmd):
    res = subprocess.run([ADB, "-s", dev, "shell"] + cmd.split(), capture_output=True, text=True, errors="ignore")
    return res.stdout.strip()

print("Launching and registering...")
run_adb(DEV_A, "am force-stop com.demo.imsdc")
run_adb(DEV_B, "am force-stop com.demo.imsdc")
time.sleep(1.0)

run_adb(DEV_A, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
run_adb(DEV_B, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.demo.imsdc/.ui.MainActivity")
time.sleep(2.5)

run_adb(DEV_A, "input tap 540 1663")
run_adb(DEV_B, "input tap 540 1663")
time.sleep(2.0)

# Clear logcat
subprocess.run([ADB, "-s", DEV_A, "logcat", "-c"])

# A calls B
print("A calls B...")
run_adb(DEV_A, "am start -n com.demo.imsdc/.ui.MainActivity --es call 1003")
time.sleep(2.0)

# B answers via slide
print("B answers...")
run_adb(DEV_B, "input swipe 850 2050 250 2050 350")
time.sleep(0.5)
run_adb(DEV_B, "am broadcast -a com.demo.imsdc.ANSWER")
time.sleep(4.0)

# A launches Whiteboard
print("Launching Whiteboard...")
run_adb(DEV_A, "am broadcast -a com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
time.sleep(4.5)

# Inspect DOM
inspect_js = "(function(){ var els = Array.from(document.querySelectorAll('*')).map(e => e.tagName + (e.className ? '.' + (typeof e.className === 'string' ? e.className.split(' ').join('.') : 'svg') : '') + (e.id ? '#'+e.id : '')); return JSON.stringify(els.slice(0, 50)); })()"
print("Sending EVAL_JS on A...")
subprocess.run([ADB, "-s", DEV_A, "shell", "am", "broadcast", "-a", "com.demo.imsdc.ACTION_EVAL_JS", "--es", "js", inspect_js])
time.sleep(1.5)

# Grab logcat
res = subprocess.run([ADB, "-s", DEV_A, "logcat", "-d"], capture_output=True, text=True, errors="ignore")
for line in res.stdout.splitlines():
    if "JS eval result" in line or "Evaluating JS" in line:
        print("DOM ON A:", line)
