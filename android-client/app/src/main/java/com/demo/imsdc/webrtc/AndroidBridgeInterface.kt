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
     * Photo-share transport (chunk 16KB + flow control + transcode album).
     * Khởi tạo bởi CallActivity cùng lúc với DataChannelManager — nullable an toàn.
     */
    var photoShare: PhotoShareTransport? = null

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

    // ═══════════════ PHOTO-SHARE BRIDGE ═══════════════

    @JavascriptInterface
    fun pickMedia() {
        val activity = dataChannelManager.webView?.context as? com.demo.imsdc.ui.CallActivity ?: return
        activity.runOnUiThread { activity.launchSharedMediaPicker() }
    }

    /**
     * Đưa 1 ảnh album (content:// URI) vào photostore sau transcode
     * (EXIF rotate → scale 1920 → JPEG q80 + thumbnail 256px).
     *
     * @param payload JSON: {"uri":"content://...","id":"p1"}
     * @return JSON kết quả: {"ok":true,"id":"p1","bytes":412000,"thumbBytes":9800,
     *                        "hash":"<sha256-hex>"} hoặc {"ok":false,"error":"..."}
     */
    @JavascriptInterface
    fun importPhoto(payload: String): String {
        val transport = photoShare ?: return """{"ok":false,"error":"transport_not_ready"}"""
        val context = dataChannelManager.webView?.context
            ?: return """{"ok":false,"error":"no_context"}"""
        return try {
            val obj = org.json.JSONObject(payload)
            val uri = obj.optString("uri")
            val id = obj.optString("id")
            if (uri.isEmpty() || id.isEmpty()) {
                return """{"ok":false,"error":"uri_or_id_missing"}"""
            }
            val result = kotlinx.coroutines.runBlocking {
                transport.importFromUri(context, android.net.Uri.parse(uri), id)
            }
            if (result == null) {
                """{"ok":false,"error":"decode_failed"}"""
            } else {
                val (fullBytes, thumbBytes) = result
                val hash = transport.fileFor(context, id).let { f ->
                    if (f.exists()) transport.sha256Hex(f.readBytes()) else ""
                }
                """{"ok":true,"id":"$id","bytes":$fullBytes,"thumbBytes":$thumbBytes,"hash":"$hash"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "importPhoto failed: ${e.message}", e)
            """{"ok":false,"error":"${e.message ?: "exception"}"}"""
        }
    }

    /**
     * Bắt đầu stream 1 ảnh đã import tới đầu xa (binary chunks qua ADC 1000).
     * JS phải gửi msg JSON {"t":"begin",...} riêng qua sendData().
     *
     * @param payload JSON: {"id":"p1"}
     * @return JSON: {"ok":true,"chunks":N,"bytes":M} — N tổng chunk sẽ đi trên dây.
     */
    @JavascriptInterface
    fun sendPhoto(payload: String): String {
        val transport = photoShare ?: return """{"ok":false,"error":"transport_not_ready"}"""
        val context = dataChannelManager.webView?.context
            ?: return """{"ok":false,"error":"no_context"}"""
        return try {
            val obj = org.json.JSONObject(payload)
            val id = obj.optString("id")
            if (id.isEmpty()) return """{"ok":false,"error":"id_missing"}"""
            val total = transport.enqueueFile(context, id)
            if (total == null) {
                """{"ok":false,"error":"file_not_found"}"""
            } else {
                val bytes = transport.fileFor(context, id).length().toInt()
                """{"ok":true,"chunks":$total,"bytes":$bytes}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendPhoto failed: ${e.message}", e)
            """{"ok":false,"error":"${e.message ?: "exception"}"}"""
        }
    }

    /**
     * URL để <img> hiển thị ảnh local trong WebView (serve bằng shouldInterceptRequest).
     * @param id photoId; @param thumb true nếu muốn bản thumbnail
     */
    @JavascriptInterface
    fun mediaUrl(id: String, thumb: Boolean): String {
        return if (thumb) "https://miniapp/media/${id}_t.jpg" else "https://miniapp/media/$id.jpg"
    }

    /**
     * Nhận ảnh dạng base64 từ WebView (từ <input type=file> — WebView chỉ trả
     * File/blob, không truy cập được content:// URI của Photo Picker native).
     * Native transcode (EXIF xoay nếu decode được, scale, JPEG) rồi lưu photostore.
     *
     * @param payload JSON: {"id":"p1","data":"<base64>","type":"image/jpeg"}
     */
    @JavascriptInterface
    fun importPhotoB64(payload: String): String {
        val transport = photoShare ?: return """{"ok":false,"error":"transport_not_ready"}"""
        val context = dataChannelManager.webView?.context
            ?: return """{"ok":false,"error":"no_context"}"""
        return try {
            val obj = org.json.JSONObject(payload)
            val id = obj.optString("id")
            val data = obj.optString("data")
            if (id.isEmpty() || data.isEmpty()) {
                return """{"ok":false,"error":"id_or_data_missing"}"""
            }
            val bytes = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
            val result = kotlinx.coroutines.runBlocking {
                transport.importFromBytes(context, bytes, id)
            }
            if (result == null) {
                """{"ok":false,"error":"decode_failed"}"""
            } else {
                val (fullBytes, thumbBytes) = result
                val hash = transport.fileFor(context, id).let { f ->
                    if (f.exists()) transport.sha256Hex(f.readBytes()) else ""
                }
                """{"ok":true,"id":"$id","bytes":$fullBytes,"thumbBytes":$thumbBytes,"hash":"$hash"}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "importPhotoB64 failed: ${e.message}", e)
            """{"ok":false,"error":"${e.message ?: "exception"}"}"""
        }
    }
}

