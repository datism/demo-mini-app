import subprocess
import time

ADB = r"C:\Users\lamnt\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEV_A = "R5CY54VL09X"

# Launch sketch-sync-app
subprocess.run([ADB, "-s", DEV_A, "shell", "am", "broadcast", "-a", "com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD"])
time.sleep(3.0)

# Check elements via logcat
subprocess.run([ADB, "-s", DEV_A, "logcat", "-c"])

# Inspect DOM
js_inspect = """(function() {
    var info = [];
    info.push('TAGS: ' + Array.from(document.querySelectorAll('*')).map(e => e.tagName + (e.className ? '.'+e.className : '')).filter(s => s.includes('CANVAS') || s.includes('board') || s.includes('app')).join(', '));
    var canvases = document.querySelectorAll('canvas');
    info.push('CANVAS COUNT: ' + canvases.length);
    canvases.forEach((c, idx) => {
        info.push('CANVAS ' + idx + ': w=' + c.width + ', h=' + c.height + ', cw=' + c.clientWidth + ', ch=' + c.clientHeight + ', style=' + c.getAttribute('style') + ', cls=' + c.className);
    });
    console.log('[DOM_INSPECT] ' + info.join(' | '));
})();"""

# We can evaluate this by sending a broadcast or injecting via MiniAppManager
print("Inspecting DOM...")
