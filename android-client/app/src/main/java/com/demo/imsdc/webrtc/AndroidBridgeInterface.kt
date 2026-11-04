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

    /** Content Sharing: what the page can ask of the SIP side. Set by CallActivity. */
    interface ShareHandler {
        fun requestShare(kind: String)
        fun cancelShare()
        fun answerShare(accept: Boolean)
        fun stopShare()
        fun pickVideo()
        fun startScreen()
        fun pauseVideo(paused: Boolean)
        fun muteShareAudio(muted: Boolean)
        fun peerNumber(): String
        fun setFullScreen(on: Boolean)
        fun showCallControls()
    }

    var shareHandler: ShareHandler? = null

    /** Page: the user chose Ảnh or Video. Native sends the Re-INVITE; the page hears back via window.shareEvent. */
    @JavascriptInterface
    fun requestShare(kind: String) { shareHandler?.requestShare(kind) }

    /** Page: Hủy while waiting for the other person. */
    @JavascriptInterface
    fun cancelShare() { shareHandler?.cancelShare() }

    /** Page, receiver: Chấp nhận (true) or Từ chối (false). */
    @JavascriptInterface
    fun answerShare(accept: Boolean) { shareHandler?.answerShare(accept) }

    /** Page: leave the session; a Re-INVITE closes the channel for both people. */
    @JavascriptInterface
    fun stopShare() { shareHandler?.stopShare() }

    /** Page, sender: open the gallery for the video to stream (after the other person accepted). */
    @JavascriptInterface
    fun pickVideo() { shareHandler?.pickVideo() }

    /** Page, sender: share this phone's screen (after the other person accepted); asks the system for consent. */
    @JavascriptInterface
    fun startScreen() { shareHandler?.startScreen() }

    /** Page, sender: pause (true) or resume (false) the stream for both people. */
    @JavascriptInterface
    fun pauseVideo(paused: Boolean) { shareHandler?.pauseVideo(paused) }

    /** Page, receiver: mute or unmute this phone's speaker for the shared video. */
    @JavascriptInterface
    fun muteShareAudio(muted: Boolean) { shareHandler?.muteShareAudio(muted) }

    @JavascriptInterface
    fun peerNumber(): String = shareHandler?.peerNumber() ?: ""

    /** Page: full screen on (photo viewer, video) or off. Hides the call bar and buttons, keeps my camera. */
    @JavascriptInterface
    fun setFullScreen(on: Boolean) { shareHandler?.setFullScreen(on) }

    /** Page: the user tapped the content in full screen; show the call buttons for a few seconds. */
    @JavascriptInterface
    fun showCallControls() { shareHandler?.showCallControls() }

    /**
     * Saves a photo to the gallery (Pictures/Content Sharing).
     * @param payload JSON: {"id":"p1","data":"<base64>","type":"image/jpeg"}
     */
    @JavascriptInterface
    fun savePhotoB64(payload: String): String {
        val context = dataChannelManager.webView?.context ?: return """{"ok":false,"error":"no_context"}"""
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return """{"ok":false,"error":"needs_android_10"}"""
        }
        return try {
            val bytes = android.util.Base64.decode(org.json.JSONObject(payload).optString("data"), android.util.Base64.NO_WRAP)
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "ContentSharing_${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Content Sharing")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return """{"ok":false,"error":"insert_failed"}"""
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return """{"ok":false,"error":"open_failed"}"""
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            """{"ok":true}"""
        } catch (e: Exception) {
            Log.e(TAG, "savePhotoB64 failed: ${e.message}", e)
            """{"ok":false,"error":"${e.message ?: "exception"}"}"""
        }
    }

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
                // 1900: "Gặp tổng đài viên" rings on the verified screen and times out; 198 plays its greeting.
                if (activity.currentAppId == "flight-booking") activity.startAgentCall()
                else activity.playAgentVoiceAudio()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute playAgentAudio: ${e.message}", e)
        }
    }

    // ═══════════════ PHOTO-SHARE BRIDGE ═══════════════

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

    /** Stream một video MP4 đã import tới đầu xa qua cùng chunk transport. */
    @JavascriptInterface
    fun sendVideo(payload: String): String {
        val transport = photoShare ?: return """{"ok":false,"error":"transport_not_ready"}"""
        val context = dataChannelManager.webView?.context
            ?: return """{"ok":false,"error":"no_context"}"""
        return try {
            val id = org.json.JSONObject(payload).optString("id")
            if (id.isEmpty()) return """{"ok":false,"error":"id_missing"}"""
            val total = transport.enqueueVideoFile(context, id)
            if (total == null) """{"ok":false,"error":"file_not_found"}"""
            else {
                val bytes = transport.fileForMedia(context, id, true).length()
                """{"ok":true,"chunks":$total,"bytes":$bytes}"""
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendVideo failed: ${e.message}", e)
            """{"ok":false,"error":"${e.message ?: "exception"}"}"""
        }
    }

    /** URL để <video> phát video MP4 local trong WebView. */
    @JavascriptInterface
    fun videoUrl(id: String): String = "https://miniapp/media/$id.mp4"

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

    /** Import video MP4 nhỏ từ file input WebView. Payload tối đa 6 MiB. */
    @JavascriptInterface
    fun importVideoB64(payload: String): String {
        val transport = photoShare ?: return """{"ok":false,"error":"transport_not_ready"}"""
        val context = dataChannelManager.webView?.context
            ?: return """{"ok":false,"error":"no_context"}"""
        return try {
            val obj = org.json.JSONObject(payload)
            val id = obj.optString("id")
            val type = obj.optString("type")
            val data = obj.optString("data")
            if (id.isEmpty() || data.isEmpty()) return """{"ok":false,"error":"id_or_data_missing"}"""
            if (type != "video/mp4") return """{"ok":false,"error":"unsupported_video_type"}"""
            val bytes = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
            if (bytes.size > 6 * 1024 * 1024) return """{"ok":false,"error":"video_too_large"}"""
            val file = transport.importVideoBytes(context, id, bytes)
                ?: return """{"ok":false,"error":"video_import_failed"}"""
            """{"ok":true,"id":"$id","bytes":${file.length()}}"""
        } catch (e: Exception) {
            Log.e(TAG, "importVideoB64 failed: ${e.message}", e)
            """{"ok":false,"error":"${e.message ?: "exception"}"}"""
        }
    }

    /**
     * Lưu bảng vẽ (sketch-sync-app: ảnh nền + nét, đã composite phía JS) vào
     * Thư viện ảnh qua MediaStore — không cần quyền WRITE_EXTERNAL_STORAGE trên API 29+.
     *
     * @param payload JSON: {"data":"<base64 JPEG>"}
     * @return JSON: {"ok":true,"uri":"content://..."} hoặc {"ok":false,"error":"..."}
     */
    @JavascriptInterface
    fun saveCanvasToGallery(payload: String): String {
        val context = dataChannelManager.webView?.context
            ?: return """{"ok":false,"error":"no_context"}"""
        return try {
            val data = org.json.JSONObject(payload).optString("data")
            if (data.isEmpty()) return """{"ok":false,"error":"data_missing"}"""
            val bytes = android.util.Base64.decode(data, android.util.Base64.NO_WRAP)

            val resolver = context.contentResolver
            val name = "bang_ve_${System.currentTimeMillis()}.jpg"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/5GNewCalling")
                }
            }
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return """{"ok":false,"error":"insert_failed"}"""
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return """{"ok":false,"error":"stream_failed"}"""
            Log.i(TAG, "Saved draw canvas to gallery: $name ($bytes bytes)")
            """{"ok":true,"uri":"$uri"}"""
        } catch (e: Exception) {
            Log.e(TAG, "saveCanvasToGallery failed: ${e.message}", e)
            """{"ok":false,"error":"${e.message ?: "exception"}"}"""
        }
    }

}

