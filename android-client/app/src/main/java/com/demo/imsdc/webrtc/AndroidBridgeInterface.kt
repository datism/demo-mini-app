package com.demo.imsdc.webrtc

import android.webkit.JavascriptInterface
import android.util.Log

private const val TAG = "AndroidBridge"

/**
 * AndroidBridgeInterface — JavaScript Interface cho WebView
 *
 * Đây là "cầu nối" giữa JavaScript trong Mini-app và Native Android.
 * Được inject vào WebView với tên "AndroidBridge":
 *   webView.addJavascriptInterface(AndroidBridgeInterface(...), "AndroidBridge")
 *
 * Mini-app HTML có thể gọi:
 *   AndroidBridge.sendData('{"action":"heart"}')
 *
 * Và Android inject data ngược lên JS qua:
 *   webView.evaluateJavascript("window.receiveData('...')", null)
 *   (Việc này được thực hiện trong DataChannelManager.handleAdcMessage())
 *
 * Theo 3GPP TS 26.114:
 *   - sendData() tương ứng với "DC_SEND" primitive
 *   - window.receiveData() tương ứng với "DC_RECEIVE" event
 */
class AndroidBridgeInterface(
    private val dataChannelManager: DataChannelManager,
    private val onStartMiniApp: (String) -> Unit
) {

    var onJsRequestedAdc: ((String) -> Unit)? = null

    /**
     * Được gọi bởi JavaScript để kích hoạt khởi chạy Mini-app mới.
     * Ví dụ: AndroidBridge.startMiniApp('0003210000000038')
     */
    @JavascriptInterface
    fun startMiniApp(appId: String) {
        Log.i(TAG, "JS requested startMiniApp: $appId")
        onStartMiniApp(appId)
    }

    @JavascriptInterface
    fun createDataChannel(appId: String) {
        Log.i(TAG, "JS requested createDataChannel for app: $appId")
        onJsRequestedAdc?.invoke(appId)
    }

    /**
     * Được gọi bởi JavaScript trong WebView:
     *   AndroidBridge.sendData('{"action":"heart"}')
     *
     * @param jsonData: JSON string từ Mini-app
     */
    @JavascriptInterface
    fun sendData(jsonData: String) {
        Log.i(TAG, "JS → Native: $jsonData")
        if (dataChannelManager.imsDcSdk != null) {
            dataChannelManager.imsDcSdk?.sendOrQueueAdc(jsonData.toByteArray(Charsets.UTF_8))
        } else {
            dataChannelManager.sendViaADC(jsonData)
        }
    }

    @JavascriptInterface
    fun send(streamId: Int, jsonData: String) {
        Log.i(TAG, "JS IMSDataChannelBridge.send(streamId=$streamId) → Native: $jsonData")
        if (streamId == 1001) {
            dataChannelManager.sendViaADC2(jsonData, streamId)
        } else {
            if (dataChannelManager.imsDcSdk != null) {
                dataChannelManager.imsDcSdk?.sendOrQueueAdc(jsonData.toByteArray(Charsets.UTF_8))
            } else {
                dataChannelManager.sendViaADC(jsonData, streamId)
            }
        }
    }

    @JavascriptInterface
    fun send(jsonData: String) {
        Log.i(TAG, "JS IMSDataChannelBridge.send → Native: $jsonData")
        if (dataChannelManager.imsDcSdk != null) {
            dataChannelManager.imsDcSdk?.sendOrQueueAdc(jsonData.toByteArray(Charsets.UTF_8))
        } else {
            dataChannelManager.sendViaADC(jsonData)
        }
    }

    @JavascriptInterface
    fun sendDataMessage(jsonData: String) {
        Log.i(TAG, "JS sendDataMessage → Native: $jsonData")
        if (dataChannelManager.imsDcSdk != null) {
            dataChannelManager.imsDcSdk?.sendOrQueueAdc(jsonData.toByteArray(Charsets.UTF_8))
        } else {
            dataChannelManager.sendViaADC(jsonData)
        }
    }

    /**
     * Debug: JavaScript có thể gọi để lấy thông tin trạng thái
     */
    @JavascriptInterface
    fun getStatus(): String {
        return """{"status":"connected","bridge":"AndroidBridgeInterface"}"""
    }

    /**
     * Debug: JavaScript gọi để log message lên Logcat
     */
    @JavascriptInterface
    fun log(message: String) {
        Log.d(TAG, "[JS] $message")
    }

    /**
     * Mở liên kết Web trên trình duyệt ngoài (Web-to-Action per GSMA TS.66 / 3GPP)
     */
    @JavascriptInterface
    fun openUrl(url: String) {
        Log.i(TAG, "JS requested openUrl: $url")
        try {
            val context = dataChannelManager.webView?.context
            if (context != null) {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "Successfully started ACTION_VIEW intent for URL: $url")
            } else {
                Log.e(TAG, "Context is null, cannot open URL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL $url: ${e.message}", e)
        }
    }

    /**
     * Phát âm thanh giọng nói của Tổng đài viên qua Native MediaPlayer
     */
    @JavascriptInterface
    fun playAgentAudio() {
        Log.i(TAG, "JS requested playAgentAudio")
        try {
            val activity = dataChannelManager.webView?.context as? com.demo.imsdc.ui.CallActivity
            activity?.runOnUiThread {
                activity.playAgentVoiceAudio()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute playAgentAudio: ${e.message}", e)
        }
    }
}

