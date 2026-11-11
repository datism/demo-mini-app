package com.demo.imsdc.webrtc

import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.SurfaceViewRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import android.util.Base64

private const val TAG = "DataChannelManager"

/**
 * DataChannelManager — Quản lý BDC và ADC DataChannel logic
 *
 * Hai luồng DataChannel:
 *
 *  1. BDC (Bootstrap DC — Stream ID 10, subprotocol="http"):
 *     - Gửi HTTP GET request tới MF
 *     - Nhận HTML response
 *     - Load HTML vào WebView
 *
 *  2. ADC (Application DC — Stream ID 1000, label="app-dc"):
 *     - Gửi {"action":"heart"} khi User bấm nút
 *     - Nhận {"action":"heart"} từ đối phương (forwarded bởi MF)
 *     - Gọi JavaScript trong WebView để hiển thị hiệu ứng
 *
 * Theo 3GPP TS 23.228 Annex AC:
 *   - BDC tương ứng với "Bootstrap Data Channel" phục vụ DCAR
 *   - ADC tương ứng với "Application Data Channel" phục vụ P2A2P
 */
/** Max size of one data channel message, as negotiated with the MF (a=max-message-size, go-mf MaxMessageSize). */
private const val MAX_DC_MESSAGE = 65_536

class DataChannelManager(
    private val webRtcManager: WebRtcManager,
    private val scope: CoroutineScope
) {

    // ─── Callbacks ──────────────────────────────────────────────────────────
    var onHtmlReceived: ((html: String) -> Unit)? = null
    var onHeartReceived: (() -> Unit)? = null
    var onLaunchAppReceived: ((appId: String) -> Unit)? = null
    /**
     * A mini app's package is settled: "304" (the cache is current), "zip" (a new package was installed),
     * "html" (an inline page was saved as the app's index.html) or "failed". The caller loads the page;
     * this class never loads the WebView itself, so one launch shows its page once.
     */
    var onAppReady: ((appId: String, source: String) -> Unit)? = null

    // ─── WebView ref (inject sau khi WebView được tạo) ───────────────────────
    var webView: WebView? = null
    var localVideoView: SurfaceViewRenderer? = null
    
    private val bdcBuffer = java.io.ByteArrayOutputStream()
    private var bdcExpectedBodySize = -1
    private var bdcHeadersSize = -1
    private var currentTargetEtag = ""

    // ─── Pending mini-app package request ────────────────────────────────────
    // targetAppId is overwritten by unrelated launches (e.g. back to operator-menu), so the app whose
    // package is being fetched is tracked separately:
    //  - downloadAppId: set until its response arrives; survives a BDC teardown and is re-sent when
    //    the channel reopens.
    //  - inFlightRequests: one entry per GET sent, so each response (BDC replies arrive in request
    //    order) is installed for the app that was actually requested.
    @Volatile private var downloadAppId: String? = null
    private var downloadDone: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val inFlightRequests = java.util.ArrayDeque<String>()
    private var bdcResponseAppId: String? = null
    private var bdcResponseFailed = false

    // ─── SDK ref để chuyển dữ liệu GSMA ──────────────────────────────────────
    var imsDcSdk: ImsDcSdk? = null

    // ─── Photo-share transport (chunk binary qua ADC 1000) ──────────────────
    var photoShare: PhotoShareTransport? = null

    // ─── AppId để tải mini-app cụ thể qua BDC ─────────────────────────────
    // Mặc định: operator-menu. Khi SDK gọi startMiniApp, đặt vào đây trước khi BDC mở.
    var targetAppId: String = "operator-menu"
    var sipServerIp: String = ""
    var role: String = "caller"

    // ─── Setup BDC ───────────────────────────────────────────────────────────

    /**
     * Thiết lập Bootstrap Data Channel.
     * Gọi sau khi PeerConnection đã CONNECTED.
     *
     * Flow:
     *   1. Đăng ký observer cho BDC
     *   2. Khi BDC mở, gửi HTTP GET request tới MF
     *   3. MF (DCAR) trả về HTML response
     *   4. Parse HTTP response, lấy body HTML
     *   5. Load vào WebView
     */
    fun setupBDC() {
        resetBdcBuffer()
        val channels = listOfNotNull(webRtcManager.bdcChannel)
        if (channels.isEmpty()) {
            Log.e(TAG, "BDC channels not created")
            return
        }

        for (bdc in channels) {
            bdc.registerObserver(object : DataChannel.Observer {
                override fun onStateChange() {
                    try {
                        val state = bdc.state()
                        Log.i(TAG, "BDC (id=${bdc.id()}) state: $state")
                        when (state) {
                            DataChannel.State.OPEN -> {
                                Log.i(TAG, "✅ BDC (id=${bdc.id()}) OPEN via observer — sending HTTP GET request")
                                sendHttpGetRequest(bdc)
                            }
                            DataChannel.State.CLOSING,
                            DataChannel.State.CLOSED -> {
                                Log.i(TAG, "BDC (id=${bdc.id()}) closed")
                                // Replies to requests sent on this channel will never arrive; the pending
                                // download (downloadAppId) is re-sent when the channel reopens.
                                val current = webRtcManager.bdcChannel
                                if (current == null || current === bdc) {
                                    synchronized(inFlightRequests) { inFlightRequests.clear() }
                                }
                            }
                            else -> {}
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Exception in BDC onStateChange: ${t.message}", t)
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer?) {
                    try {
                        buffer ?: return
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        Log.d(TAG, "BDC (id=${bdc.id()}) received message: ${bytes.size} bytes")
                        handleBdcResponse(bytes, bdc.id())
                    } catch (t: Throwable) {
                        Log.e(TAG, "Exception in BDC onMessage: ${t.message}", t)
                    }
                }

                override fun onBufferedAmountChange(previousAmount: Long) {}
            })
        }

        // If channel is ALREADY open, send request immediately!
        for (bdc in channels) {
            if (bdc.state() == DataChannel.State.OPEN) {
                Log.i(TAG, "✅ BDC (id=${bdc.id()}) already OPEN — sending HTTP GET request immediately")
                sendHttpGetRequest(bdc)
            }
        }

        // Coroutine polling to guarantee BDC request sent when channel opens
        scope.launch(Dispatchers.Default) {
            var attempts = 0
            while (attempts < 20) {
                for (ch in channels) {
                    val st = ch.state()
                    Log.i(TAG, "BDC (id=${ch.id()}) state poll #$attempts: $st")
                    if (st == DataChannel.State.OPEN) {
                        Log.i(TAG, "✅ BDC (id=${ch.id()}) is OPEN — sending HTTP GET request for targetAppId=$targetAppId")
                        sendHttpGetRequest(ch)
                        return@launch
                    }
                }
                kotlinx.coroutines.delay(500)
                attempts++
            }
        }
    }

    /**
     * Gửi HTTP GET request qua BDC.
     * Nếu targetAppId được đặt (khác operator-menu), dùng path DCAR chuẩn.
     * MF sẽ phân tích request này và trả về nội dung index.html của app.
     */
    private fun sendHttpGetRequest(bdc: DataChannel) {
        val rawAppId = downloadAppId ?: targetAppId
        // setupBDC has three senders (OPEN observer, already-open check, poll) and launchMiniApp a fourth:
        // one GET per app until its reply arrives. A BDC close clears inFlightRequests, so a re-send still works.
        if (synchronized(inFlightRequests) { inFlightRequests.contains(rawAppId) }) {
            Log.i(TAG, "BDC (id=${bdc.id()}): GET for $rawAppId already in flight, not sending again")
            return
        }
        val appId = when {
            rawAppId.contains("0003210000000038") || rawAppId.contains("tuyaapp") || rawAppId.contains("sketch-sync-app") -> "sketch-sync-app"
            rawAppId.contains("sketch-sync-app") || rawAppId.contains("tuyaapp") || rawAppId.contains("sketch-sync-app") -> "sketch-sync-app"
            rawAppId.contains("operator-menu") -> "operator-menu"
            else -> rawAppId
        }
        val path = when {
            // Theo GSMA SDK flow: tải ZIP từ DCAR qua /download endpoint
            // BDC gửi HTTP GET, MF proxy đến DCAR, trả về ZIP bytes
            appId == "operator-menu" || appId.isBlank() -> "/dcar/v1/applications/operator-menu/download"
            appId == "sketch-sync-app" -> "/dcar/v1/applications/sketch-sync-app/download"
            else -> "/dcar/v1/applications/$appId/download"
        }
        val etagHeader = if (currentTargetEtag.isNotEmpty()) "If-None-Match: $currentTargetEtag\r\n" else ""
        val request = "GET $path HTTP/1.1\r\nHost: dcsf\r\nAccept: application/zip,text/html\r\n$etagHeader\r\n"
        val bytes = request.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.wrap(bytes)
        if (com.demo.imsdc.call.CallHold.active) { Log.i(TAG, "Call on hold: BDC request not sent"); return }
        val success = bdc.send(DataChannel.Buffer(buf, false))
        if (success) synchronized(inFlightRequests) { inFlightRequests.addLast(rawAppId) }
        Log.i(TAG, "BDC (id=${bdc.id()}): HTTP GET sent ($success): $request")
    }

    /** Forget a pending download, e.g. when the user goes back to the operator-menu. */
    fun cancelPendingDownload() {
        synchronized(this) {
            val appId = downloadAppId ?: return
            Log.i(TAG, "Cancelling pending download of $appId")
            downloadAppId = null
            downloadDone?.complete(false)
            downloadDone = null
        }
    }

    private fun completeDownload(appId: String?, ok: Boolean) {
        synchronized(this) {
            if (appId != null && downloadAppId == appId) {
                downloadAppId = null
                downloadDone?.complete(ok)
                downloadDone = null
            }
        }
    }

    /**
     * Gửi request tải mini-app cụ thể qua BDC (gọi từ SDK sau khi startMiniApp).
     * Đặt targetAppId và gửi lại GET request nếu BDC đã mở.
     */
    fun sendAppRequest(appId: String, etag: String = "") {
        targetAppId = appId
        currentTargetEtag = etag
        synchronized(this) {
            downloadDone?.complete(false)
            downloadAppId = appId
            downloadDone = kotlinx.coroutines.CompletableDeferred()
        }
        val channels = listOfNotNull(webRtcManager.bdcChannel)
        if (channels.isEmpty()) {
            Log.w(TAG, "BDC channels chưa tạo, sẽ gửi request khi BDC mở (targetAppId=$appId)")
            return
        }
        var sent = false
        for (bdc in channels) {
            if (bdc.state() == DataChannel.State.OPEN) {
                Log.i(TAG, "BDC (id=${bdc.id()}) đã mở, gửi ngay GET request cho appId=$appId")
                sendHttpGetRequest(bdc)
                sent = true
            }
        }
        if (!sent) {
            Log.w(TAG, "Chưa có BDC channel nào OPEN, targetAppId=$appId sẽ được dùng khi BDC mở")
        }
    }

    /**
     * Parse HTTP response từ MF và load HTML vào WebView.
     * Format: "HTTP/1.1 200 OK\r\n...headers...\r\n\r\n<html>..."
     */
    private fun handleBdcResponse(rawBytes: ByteArray, channelId: Int) {
        bdcBuffer.write(rawBytes)
        val currentData = bdcBuffer.toByteArray()
        
        if (bdcHeadersSize == -1) {
            var separatorIdx = -1
            for (i in 0 until currentData.size - 3) {
                if (currentData[i] == 13.toByte() && currentData[i+1] == 10.toByte() && 
                    currentData[i+2] == 13.toByte() && currentData[i+3] == 10.toByte()) {
                    separatorIdx = i
                    break
                }
            }
            if (separatorIdx >= 0) {
                bdcHeadersSize = separatorIdx + 4
                val headers = String(currentData.copyOfRange(0, separatorIdx), Charsets.UTF_8)
                Log.d(TAG, "Parsed headers:\n$headers")
                val respAppId = synchronized(inFlightRequests) { inFlightRequests.pollFirst() } ?: (downloadAppId ?: targetAppId)
                bdcResponseAppId = respAppId

                // Error replies still carry a body; let it arrive so it cannot bleed into the next response.
                bdcResponseFailed = headers.startsWith("HTTP/1.1 4") || headers.startsWith("HTTP/1.1 5")
                if (bdcResponseFailed) Log.w(TAG, "BDC response for $respAppId failed: ${headers.lineSequence().first()}")

                if (headers.startsWith("HTTP/1.1 304")) {
                    // The ETag is only sent when the cached package exists, and launchMiniApp has
                    // already loaded that cache: nothing changed, so do not reload the page.
                    Log.i(TAG, "Received 304 Not Modified for $respAppId: the cached package is current")
                    resetBdcBuffer()
                    completeDownload(respAppId, true)
                    notifyAppReady(respAppId, "304")
                    return
                }
                
                val clMatch = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(headers)
                if (clMatch != null) {
                    bdcExpectedBodySize = clMatch.groupValues[1].toInt()
                } else {
                    bdcExpectedBodySize = currentData.size - bdcHeadersSize
                }
                
                val etagMatch = Regex("ETag:\\s*([^\\r\\n]+)", RegexOption.IGNORE_CASE).find(headers)
                if (etagMatch != null) {
                    val newEtag = etagMatch.groupValues[1].trim()
                    val prefs = webView?.context?.getSharedPreferences("miniapps_etag", android.content.Context.MODE_PRIVATE)
                    val etagAppId = bdcResponseAppId ?: targetAppId
                    prefs?.edit()?.putString(etagAppId, newEtag)?.apply()
                    Log.i(TAG, "Saved new ETag for $etagAppId: $newEtag")
                }
            }
        }
        
        if (bdcHeadersSize != -1) {
            val receivedBodySize = currentData.size - bdcHeadersSize
            Log.d(TAG, "BDC chunk received. Body progress: $receivedBodySize / $bdcExpectedBodySize")
            if (receivedBodySize >= bdcExpectedBodySize || bdcExpectedBodySize <= 0) {
                val bodyBytes = currentData.copyOfRange(bdcHeadersSize, currentData.size)
                val respAppId = bdcResponseAppId ?: targetAppId
                val failed = bdcResponseFailed
                resetBdcBuffer()

                if (failed) {
                    completeDownload(respAppId, false)
                    notifyAppReady(respAppId, "failed")
                    return
                }

                if (bodyBytes.isEmpty()) {
                    Log.e(TAG, "Body is empty after full reception!")
                    completeDownload(respAppId, false)
                    notifyAppReady(respAppId, "failed")
                    return
                }

                val isZip = bodyBytes.size > 4 && bodyBytes[0] == 0x50.toByte() && bodyBytes[1] == 0x4B.toByte()
                if (isZip) {
                    val cachedAppDir = java.io.File(webView?.context?.cacheDir, "miniapps/$respAppId")
                    cachedAppDir.deleteRecursively()
                    cachedAppDir.mkdirs()
                    try {
                        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bodyBytes)).use { zis ->
                            var ze = zis.nextEntry
                            while (ze != null) {
                                val normalizedName = ze.name.replace('\\', '/')
                                val outFile = java.io.File(cachedAppDir, normalizedName)
                                if (ze.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    java.io.FileOutputStream(outFile).use { fos ->
                                        val buffer = ByteArray(1024)
                                        var len: Int
                                        while (zis.read(buffer).also { len = it } > 0) {
                                            fos.write(buffer, 0, len)
                                        }
                                    }
                                }
                                ze = zis.nextEntry
                            }
                            zis.closeEntry()
                        }
                        Log.i(TAG, "Extracted ZIP for $respAppId successfully")
                        completeDownload(respAppId, true)
                        notifyAppReady(respAppId, "zip")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unzip: ${e.message}")
                        completeDownload(respAppId, false)
                        notifyAppReady(respAppId, "failed")
                    }
                } else {
                    val htmlStr = String(bodyBytes, Charsets.UTF_8)
                    var saved = false
                    try {
                        val dir = java.io.File(webView?.context?.cacheDir, "miniapps/$respAppId")
                        dir.deleteRecursively()
                        dir.mkdirs()
                        java.io.File(dir, "index.html").writeText(htmlStr, Charsets.UTF_8)
                        saved = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Error saving inline HTML for $respAppId: ${e.message}")
                    }
                    Log.i(TAG, "Received inline HTML for $respAppId (length=${htmlStr.length})")
                    completeDownload(respAppId, saved)
                    notifyAppReady(respAppId, if (saved) "html" else "failed")
                }
            }
        }
    }
    
    private fun resetBdcBuffer() {
        bdcBuffer.reset()
        bdcHeadersSize = -1
        bdcExpectedBodySize = -1
    }
    
    private fun notifyAppReady(appId: String, source: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onAppReady?.invoke(appId, source) }
    }

    /**
     * Giải nén toàn bộ ZIP package vào thư mục cục bộ để WebView có thể tải tài nguyên offline (CSS/JS).
     */
    private fun unzipApp(zipBytes: ByteArray, targetDir: java.io.File) {
        targetDir.deleteRecursively()
        targetDir.mkdirs()
        val zis = ZipInputStream(ByteArrayInputStream(zipBytes))
        var entry = zis.nextEntry
        while (entry != null) {
            // Normalize path separators to forward slashes for Android/Linux compatibility
            val normalizedName = entry.name.replace('\\', '/')
            val file = java.io.File(targetDir, normalizedName)
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()
                java.io.FileOutputStream(file).use { fos ->
                    val buf = ByteArray(4096)
                    var len = zis.read(buf)
                    while (len > 0) {
                        fos.write(buf, 0, len)
                        len = zis.read(buf)
                    }
                }
            }
            entry = zis.nextEntry
        }
        zis.close()
        Log.i(TAG, "Successfully extracted mini-app package to ${targetDir.absolutePath}")
    }

    // ─── Setup ADC ───────────────────────────────────────────────────────────

    /**
     * Thiết lập Application Data Channel.
     * Gọi sau khi PeerConnection đã CONNECTED.
     *
     * Flow (Receive):
     *   1. Nhận message JSON từ MF (forwarded từ đối phương)
     *   2. Parse action
     *   3. Gọi JavaScript window.receiveData() trong WebView
     */
    fun setupADC() = attachAdcObserver(webRtcManager.adcChannel, isHome = true)

    fun setupAppADC() = attachAdcObserver(webRtcManager.appAdcChannel, isHome = false)

    /** Receives photo frames on the content-share channel (stream 1002) and hands them to the transport. */
    private var observedShareChannel: DataChannel? = null

    fun setupShareChannel(ch: DataChannel?) {
        ch ?: return
        if (ch === observedShareChannel) return // reused across share sessions; observer already attached
        observedShareChannel = ch
        Log.i(TAG, "Share channel observer attached, state=${ch.state()}")
        ch.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() { Log.i(TAG, "Share channel state: ${ch.state()}") }
            private var rx = 0
            override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (com.demo.imsdc.call.CallHold.active) { holdIncoming(this, buffer); return }
                try {
                    buffer ?: return
                    val bb = buffer.data
                    val bytes = ByteArray(bb.remaining())
                    bb.get(bytes)
                    rx++
                    if (rx == 1 || rx % 10 == 0) Log.i(TAG, "Share channel received frame #$rx (${bytes.size} bytes)")
                    if (photoShare?.onIncomingBytes(bytes) != true) {
                        // Control text on the share channel is not expected; ignore it rather than guess.
                        Log.w(TAG, "Share channel: ignoring ${bytes.size} bytes that are not a photo frame")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Share channel onMessage failed: ${t.message}", t)
                }
            }
            override fun onBufferedAmountChange(previousAmount: Long) {}
        })
    }

    private fun attachAdcObserver(adc: DataChannel?, isHome: Boolean) {
        if (adc != null) {
            adc.registerObserver(object : DataChannel.Observer {
                override fun onStateChange() {
                    try {
                        val state = adc.state()
                        Log.i(TAG, "ADC state: $state")
                        if (state == DataChannel.State.OPEN) {
                            Log.i(TAG, "✅ ADC OPEN — P2A2P channel 1000 ready")
                            val dcLabel = imsDcSdk?.getPrimaryDcLabel() ?: "control_sketch-sync-app_0"
                            imsDcSdk?.notifyDataChannelState(dcLabel, 1)
                            imsDcSdk?.flushPendingAdc()
                            val target = targetAppId ?: ""
                            if (target.isNotEmpty() && target != "operator-menu") {
                                val msg = "{\"action\":\"LAUNCH_APP\",\"appId\":\"$target\"}"
                                val bytes = msg.toByteArray(Charsets.UTF_8)
                                val buffer = org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false)
                                try {
                                    adc.send(buffer)
                                    Log.i(TAG, "Sent sync LAUNCH_APP on ADC OPEN: $msg")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to send sync LAUNCH_APP on ADC OPEN: ${e.message}")
                                }
                            }
                            if (targetAppId?.contains("sketch") == true || targetAppId?.contains("whiteboard") == true || targetAppId?.contains("tuya") == true) {
                                val handshakeAck = org.json.JSONObject().apply {
                                    put("type", "handshake_ack")
                                }.toString()
                                imsDcSdk?.notifyMessageReceived(handshakeAck.toByteArray(Charsets.UTF_8))
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Exception in ADC onStateChange: ${t.message}", t)
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (com.demo.imsdc.call.CallHold.active) { holdIncoming(this, buffer); return }
                    try {
                        buffer ?: return
                        if (isHome && !webRtcManager.foregroundIsHome) {
                            Log.i(TAG, "Home ADC message dropped: an app is in front (${buffer.data.remaining()} bytes)")
                            return
                        }
                        val byteBuffer = buffer.data
                        val bytes = ByteArray(byteBuffer.remaining())
                        byteBuffer.get(bytes)
                        Log.i(TAG, "ADC received ${bytes.size} bytes (binary=${buffer.binary})")

                        // Photo-share binary DATA_CHUNK (byte đầu = 0x01): transport
                        // tự ghép chunks rồi đẩy lên JS khi đủ — KHÔNG đi đường
                        // notifyMessageReceived cho từng mảnh (tiết kiệm b64 khổng lồ).
                        if (photoShare?.onIncomingBytes(bytes) == true) {
                            return
                        }

                        imsDcSdk?.notifyMessageReceived(bytes)
                        try {
                            val data = String(bytes, Charsets.UTF_8)
                            if (data.startsWith("LAUNCH_APP:")) {
                                val appId = data.substringAfter(":")
                                Log.i(TAG, "Interpreted control command: LAUNCH_APP $appId")
                                onLaunchAppReceived?.invoke(appId)
                                return
                            } else if (data.contains("LAUNCH_APP")) {
                                val appId = data.substringAfter("\"appId\":\"").substringBefore("\"")
                                Log.i(TAG, "Interpreted JSON control command: LAUNCH_APP $appId")
                                onLaunchAppReceived?.invoke(appId)
                                return
                            }
                            handleAdcMessage(data)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse and route fallback ADC text message: ${e.message}")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Fatal uncaught exception in ADC onMessage caught: ${t.message}", t)
                    }
                }

                override fun onBufferedAmountChange(previousAmount: Long) {}
            })
        }
    }



    /**
     * Nhận message từ MF (P2A2P) và push lên WebView JS.
     * Ví dụ: {"action":"heart"} → window.receiveData('{"action":"heart"}')
     */
    fun handleAdcMessage(jsonData: String) {
        // Escape single quotes để tránh lỗi JS injection
        val escaped = jsonData.replace("'", "\\'").replace("\n", "\\n")

        scope.launch(Dispatchers.Main) {
            webView?.let { wv ->
                val js = "if (typeof window.receiveData === 'function') window.receiveData('$escaped');"
                Log.i(TAG, "ADC → WebView JS: $js")
                wv.evaluateJavascript(js) { result ->
                    Log.d(TAG, "JS result: $result")
                    forceWebViewRepaint(wv)
                }
            } ?: Log.e(TAG, "WebView is null, cannot inject receiveData")
        }

        if (jsonData.contains("\"heart\"")) {
            onHeartReceived?.invoke()
        }
    }

    /**
     * Ép WebView vẽ lại nội dung DOM đã thay đổi qua evaluateJavascript.
     *
     * WebView transparent + Hardware Acceleration thường KHÔNG tự re-draw
     * khi element được thêm/xoá qua JS injection (emoji không hiển thị cho
     * đến khi user chạm tay). Kết hợp nhiều cơ chế để đảm bảo compositor
     * invalidation trên mọi Android version:
     *  1. postInvalidate()/invalidate() — báo View cần vẽ lại
     *  2. requestLayout() — yêu cầu relayout, kích hoạt lại compositor
     *  3. Invalidate view cha (parent) — ép surface chứa WebView cũng vẽ lại
     */
    private fun forceWebViewRepaint(wv: WebView) {
        wv.postInvalidate()
        wv.invalidate()
        try {
            wv.requestLayout()
        } catch (_: Exception) {
        }
        try {
            wv.post {
                // Ép cả view cha vẽ lại (quan trọng khi WebView nằm trên
                // SurfaceView video — chỉ invalidate bản thân WebView không đủ).
                (wv.parent as? android.view.View)?.postInvalidate()
                wv.invalidate()
            }
        } catch (_: Exception) {
        }
    }

    fun handleAdc2Message(jsonData: String) {
        handleAdcMessage(jsonData)
    }

    fun setupADC2() {
        val adc2 = webRtcManager.adc2Channel
        if (adc2 != null) {
            adc2.registerObserver(object : DataChannel.Observer {
                override fun onStateChange() {
                    try {
                        val state = adc2.state()
                        Log.i(TAG, "ADC2 state: $state")
                        if (state == DataChannel.State.OPEN) {
                            Log.i(TAG, "✅ ADC2 OPEN - P2A2P channel 1001 ready")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Exception in ADC2 onStateChange: ${t.message}", t)
                    }
                }
                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (com.demo.imsdc.call.CallHold.active) { holdIncoming(this, buffer); return }
                    try {
                        if (buffer == null) return
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.get(bytes)
                        if (buffer.binary) {
                            Log.i(TAG, "ADC2 received binary data (${bytes.size} bytes)")
                            return
                        }
                        try {
                            val data = String(bytes, Charsets.UTF_8)
                            handleAdc2Message(data)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse ADC2 message: ${e.message}")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Fatal exception in ADC2 onMessage caught: ${t.message}", t)
                    }
                }
                override fun onBufferedAmountChange(previousAmount: Long) {}
            })
        }
    }

    // ─── Send via ADC (được gọi từ AndroidBridge) ────────────────────────────


    /**
     * Gửi message qua ADC tới MF.
     * MF sẽ forward tới session của đối phương (P2A2P).
     *
     * Được gọi bởi AndroidBridgeInterface khi JS gọi AndroidBridge.sendData().
     */
    // ─── Hold: data channels are suspended (a=inactive), nothing is lost ─────

    /** A message that arrived on hold, with the observer that would have handled it. */
    private class HeldIn(val observer: DataChannel.Observer, val bytes: ByteArray, val binary: Boolean)
    private val heldIn = HeldQueue<HeldIn>()
    private val heldOut = HeldQueue<() -> Unit>()

    /** On hold the page messages wait instead of being lost; [flushHeld] runs them through the same handler. */
    private fun holdIncoming(observer: DataChannel.Observer, buffer: DataChannel.Buffer?) {
        buffer ?: return
        val d = buffer.data.duplicate()
        val bytes = ByteArray(d.remaining()).also { d.get(it) }
        heldIn.add(HeldIn(observer, bytes, buffer.binary))
        if (heldIn.size == 1 || heldIn.size % 50 == 0) Log.i(TAG, "Call on hold: ${heldIn.size} incoming data channel message(s) waiting")
    }

    /**
     * Call resumed: messages that arrived on hold go to the page first (in order), then what the page sent on
     * hold goes out (in order), as if the hold had been a pause.
     */
    fun flushHeld() {
        val inDropped = heldIn.dropped
        val incoming = heldIn.drain()
        val outDropped = heldOut.dropped
        val outgoing = heldOut.drain()
        if (incoming.isEmpty() && outgoing.isEmpty()) return
        Log.i(TAG, "Call resumed: delivering ${incoming.size} held message(s), sending ${outgoing.size}" +
            (if (inDropped + outDropped > 0) " (${inDropped + outDropped} oldest dropped: hold too long)" else ""))
        incoming.forEach {
            try { it.observer.onMessage(DataChannel.Buffer(ByteBuffer.wrap(it.bytes), it.binary)) }
            catch (e: Exception) { Log.e(TAG, "Held message delivery failed: ${e.message}") }
        }
        outgoing.forEach { try { it() } catch (e: Exception) { Log.e(TAG, "Held message send failed: ${e.message}") } }
    }

    /** Call ended: nothing held is delivered any more. */
    fun dropHeld() { heldIn.clear(); heldOut.clear() }

    fun sendViaADC(jsonData: String, streamId: Int = 1000) {
        if (com.demo.imsdc.call.CallHold.active) { heldOut.add { sendViaADC(jsonData, streamId) }; return }
        val adc = webRtcManager.foregroundAdcChannel
        if (adc == null) {
            Log.e(TAG, "ADC (stream $streamId) is null")
            return
        }

        try {
            if (adc.state() != DataChannel.State.OPEN) {
                Log.e(TAG, "ADC (stream $streamId) not ready: ${adc.state()}")
                return
            }
            val bytes = jsonData.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_DC_MESSAGE) {
                // Over the negotiated max message size the send fails and WebRTC closes the channel: the page
                // would lose it for the rest of the call. Refuse this one message instead.
                Log.e(TAG, "ADC (stream $streamId) message too large (${bytes.size} bytes > $MAX_DC_MESSAGE): not sent, the channel would close")
                return
            }
            val buf = ByteBuffer.wrap(bytes)
            val success = adc.send(DataChannel.Buffer(buf, false))
            Log.i(TAG, "ADC (stream $streamId) sent ($success, ${bytes.size} B): ${jsonData.take(300)}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending via ADC (channel might be disposed): ${e.message}")
        }
    }

    fun sendViaADC2(jsonData: String, streamId: Int = 1001) {
        if (com.demo.imsdc.call.CallHold.active) { heldOut.add { sendViaADC2(jsonData, streamId) }; return }
        val adc2 = webRtcManager.adc2Channel
        if (adc2 == null) {
            Log.e(TAG, "ADC2 (stream $streamId) is null")
            return
        }

        try {
            if (adc2.state() != DataChannel.State.OPEN) {
                Log.e(TAG, "ADC2 (stream $streamId) not ready: ${adc2.state()}")
                return
            }
            val bytes = jsonData.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_DC_MESSAGE) {
                // Over the negotiated max message size the send fails and WebRTC closes the channel: the page
                // would lose it for the rest of the call. Refuse this one message instead.
                Log.e(TAG, "ADC2 (stream $streamId) message too large (${bytes.size} bytes > $MAX_DC_MESSAGE): not sent, the channel would close")
                return
            }
            val buf = ByteBuffer.wrap(bytes)
            val success = adc2.send(DataChannel.Buffer(buf, false))
            Log.i(TAG, "ADC2 (stream $streamId) sent ($success, ${bytes.size} B): ${jsonData.take(300)}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending via ADC2: ${e.message}")
        }
    }
}
