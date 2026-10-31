package com.demo.imsdc.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.demo.imsdc.R
import com.demo.imsdc.databinding.ActivityCallBinding
import com.demo.imsdc.sip.SipManager
import com.demo.imsdc.webrtc.AndroidBridgeInterface
import com.demo.imsdc.webrtc.DataChannelManager
import com.demo.imsdc.webrtc.LatestRequestQueue
import com.demo.imsdc.webrtc.reInviteRetryDelayMs
import com.demo.imsdc.webrtc.WebRtcManager
import com.demo.imsdc.media.RtpMediaManager
import android.app.KeyguardManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.view.SurfaceHolder
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.withContext
import org.webrtc.PeerConnection
import java.net.InetAddress

class MiniAppManager(val activity: CallActivity) {
    private val TAG = "MiniAppManager"

    fun setupWebView() {
        val wv = activity.binding.webviewMiniapp
        wv.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Cho phép JS gọi file:// và http://
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Cache
            cacheMode = WebSettings.LOAD_NO_CACHE
            // WebView transparent — nhìn xuyên xuống video bên dưới
            domStorageEnabled = true
        }

        wv.isClickable = true
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true

        // Background trong suốt nhìn xuyên xuống video (bật Hardware acceleration cho Canvas)
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: ""
                Log.i(TAG, "shouldOverrideUrlLoading: $url")
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val url = request?.url ?: return null
                val host = url.host
                val path = url.path ?: ""

                if (host == "miniapp") {
                    // Return empty response for favicon.ico to avoid WebView errors
                    if (path == "/favicon.ico") {
                        return android.webkit.WebResourceResponse(
                            "image/x-icon", "UTF-8", 204, "No Content",
                            emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    val rawAppId = activity.currentAppId
                    val appId = when {
                        rawAppId.contains("0003210000000038") || rawAppId.contains("tuyaapp") || rawAppId.contains("sketch-sync-app") -> "sketch-sync-app"
                        rawAppId.contains("operator-menu") -> "operator-menu"
                        else -> rawAppId
                    }
                    val cachedAppDir = java.io.File(activity.cacheDir, "miniapps/$appId")

                    // ─── Photo-share media serving (trước mọi resolve app file) ───
                    // Ảnh nằm trong cacheDir/photostore — NGOÀI miniapps/ để không bị
                    // deleteRecursively khi ZIP app mới được giải nén.
                    if (path.startsWith("/media/")) {
                        val mediaName = path.removePrefix("/media/").replace("..", "").replace("/", "")
                        val mediaFile = java.io.File(java.io.File(activity.cacheDir, "photostore"), mediaName)
                        val mime = when {
                            mediaName.endsWith(".jpg") || mediaName.endsWith(".jpeg") -> "image/jpeg"
                            mediaName.endsWith(".png") -> "image/png"
                            mediaName.endsWith(".mp4") -> "video/mp4"
                            else -> "application/octet-stream"
                        }
                        if (mediaFile.exists() && mediaFile.isFile) {
                            Log.d(TAG, "Serving photostore media: ${mediaFile.absolutePath}")
                            return android.webkit.WebResourceResponse(mime, "UTF-8", java.io.FileInputStream(mediaFile))
                        }
                        Log.w(TAG, "photostore media not found: $mediaName → 404")
                        return android.webkit.WebResourceResponse(mime, "UTF-8", 404, "Not Found",
                            emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }

                    if (path.startsWith("/dcar/")) {
                        // Proxy API request to DCSF backend server (DC1/DC3 interface)
                        val sipServerIp = activity.sipManager.sipServerHost
                        val queryStr = url.query?.let { "?$it" } ?: ""
                        val targetUrlStr = "http://$sipServerIp:8080$path$queryStr"
                        Log.i(TAG, "Proxying WebView API request to: $targetUrlStr")
                        try {
                            val conn = java.net.URL(targetUrlStr).openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = request.method ?: "GET"
                            request.requestHeaders?.forEach { (key, value) ->
                                conn.setRequestProperty(key, value)
                            }
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000

                            val responseCode = conn.responseCode
                            val mimeType = conn.contentType?.split(";")?.firstOrNull()?.trim() ?: "application/json"
                            val encoding = conn.contentEncoding ?: "UTF-8"

                            val headers = conn.headerFields.entries.filter { it.key != null }.associate { it.key to it.value.joinToString(",") }
                            val stream = if (responseCode >= 400) conn.errorStream else conn.inputStream
                            Log.i(TAG, "Proxy response code: $responseCode, mimeType: $mimeType")
                            return android.webkit.WebResourceResponse(mimeType, encoding, responseCode, conn.responseMessage ?: "OK", headers, stream)
                        } catch (e: Exception) {
                            Log.w(TAG, "Proxy to backend unavailable ($path): ${e.message}")
                            // Return 503 Service Unavailable instead of letting WebView show ERR_PROXY_CONNECTION_FAILED
                            return android.webkit.WebResourceResponse(
                                "application/json", "UTF-8", 503, "Service Unavailable",
                                emptyMap(), java.io.ByteArrayInputStream("{\"error\":\"backend_unavailable\"}".toByteArray())
                            )
                        }
                    }

                    val relPath = if (path.startsWith("/")) path.substring(1) else path
                    val file = if (relPath.isEmpty() || relPath == "index.html") {
                        java.io.File(cachedAppDir, "index.html")
                    } else {
                        java.io.File(cachedAppDir, relPath)
                    }

                    if (file.exists() && file.isFile) {
                        // Strict MIME verification for intercepted resources (GSMA TS.66)
                        if (file.name.endsWith(".js") || file.name.endsWith(".css")) {
                            val previewBytes = ByteArray(128)
                            try {
                                java.io.FileInputStream(file).use { fis ->
                                    val read = fis.read(previewBytes)
                                    if (read > 0) {
                                        val previewStr = String(previewBytes, 0, read, Charsets.UTF_8).trim().lowercase()
                                        if (previewStr.startsWith("<!doctype html") || previewStr.startsWith("<html")) {
                                            val secEx = SecurityException("Strict MIME Check Failure: ${file.name} is served as asset but contains HTML markup")
                                            Log.e(TAG, "MIME type validation failed: ${secEx.message}")
                                            throw secEx
                                        }
                                    }
                                }
                            } catch (e: SecurityException) {
                                throw e
                            } catch (e: Exception) {
                                // Ignore standard read/IO issues
                            }
                        }

                        try {
                            val mimeType = when {
                                file.name.endsWith(".html") -> "text/html"
                                file.name.endsWith(".css") -> "text/css"
                                file.name.endsWith(".js") -> "application/javascript"
                                file.name.endsWith(".png") -> "image/png"
                                file.name.endsWith(".jpg") || file.name.endsWith(".jpeg") -> "image/jpeg"
                                file.name.endsWith(".ico") -> "image/x-icon"
                                file.name.endsWith(".svg") -> "image/svg+xml"
                                file.name.endsWith(".json") -> "application/json"
                                file.name.endsWith(".mp3") -> "audio/mpeg"
                                file.name.endsWith(".wav") -> "audio/wav"
                                file.name.endsWith(".ogg") -> "audio/ogg"
                                file.name.endsWith(".mp4") -> "video/mp4"
                                else -> "application/octet-stream"
                            }
                            val stream = java.io.FileInputStream(file)
                            Log.d(TAG, "Intercepted and serving local file: ${file.absolutePath} for $url")
                            return android.webkit.WebResourceResponse(mimeType, "UTF-8", stream)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error serving intercepted file: ${e.message}")
                        }
                    } else {
                        // Fallback to pre-bundled Android assets
                        try {
                            val assetSubPath = if (relPath.isEmpty() || relPath == "index.html") "index.html" else relPath
                            val assetPath = "miniapps/$appId/$assetSubPath"
                            val assetStream = activity.assets.open(assetPath)
                            val mimeType = when {
                                assetSubPath.endsWith(".html") -> "text/html"
                                assetSubPath.endsWith(".css") -> "text/css"
                                assetSubPath.endsWith(".js") -> "application/javascript"
                                assetSubPath.endsWith(".png") -> "image/png"
                                assetSubPath.endsWith(".jpg") || assetSubPath.endsWith(".jpeg") -> "image/jpeg"
                                assetSubPath.endsWith(".ico") -> "image/x-icon"
                                assetSubPath.endsWith(".svg") -> "image/svg+xml"
                                assetSubPath.endsWith(".json") -> "application/json"
                                assetSubPath.endsWith(".mp3") -> "audio/mpeg"
                                assetSubPath.endsWith(".wav") -> "audio/wav"
                                assetSubPath.endsWith(".ogg") -> "audio/ogg"
                                assetSubPath.endsWith(".mp4") -> "video/mp4"
                                else -> "application/octet-stream"
                            }
                            Log.d(TAG, "Serving fallback bundled asset: $assetPath for $url")
                            return android.webkit.WebResourceResponse(mimeType, "UTF-8", assetStream)
                        } catch (e: Exception) {
                            Log.w(TAG, "Intercepted file not found: ${file.absolutePath} for $url")
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                val urlStr = request?.url?.toString() ?: ""
                // Only log ERROR for main frame failures; sub-resources (favicon, APIs) are WARN
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "WebView Error (main frame): code=${error?.errorCode}, desc=${error?.description}, url=$urlStr")
                } else {
                    Log.w(TAG, "WebView sub-resource error: code=${error?.errorCode}, url=$urlStr")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "✅ Mini-app loaded in WebView: $url")
                activity.binding.webviewMiniapp.visibility = View.VISIBLE
                activity.binding.layoutMiddleDetails.visibility = View.GONE
                activity.binding.webviewMiniapp.bringToFront()
                val isEnterpriseOrSmart = activity.currentAppId == "enterprise-callerid" || activity.currentAppId == "smart-menu" || url?.contains("enterprise-callerid") == true || url?.contains("smart-menu") == true ||
                        activity.currentAppId == "flight-booking" || url?.contains("flight-booking") == true
                if (isEnterpriseOrSmart) {
                    activity.binding.localVideoView.visibility = View.GONE
                } else {
                    activity.binding.localVideoView.visibility = View.VISIBLE
                    activity.binding.localVideoView.bringToFront()
                }
                activity.binding.layoutTopBar.bringToFront()
                if (activity.binding.btnExitMiniapp.visibility == View.VISIBLE) {
                    activity.binding.btnExitMiniapp.bringToFront()
                }
                activity.binding.webviewMiniapp.requestFocus()

                // Fix race condition: dataChannelNotify may have fired before JS was loaded.
                // Only re-send for actual mini-app pages (TuYaApp), not operator-menu.
                val isTuYaApp = url?.contains("tuyaapp") == true ||
                        (activity.currentAppId.contains("0003210000000038") || activity.currentAppId.contains("tuyaapp") || activity.currentAppId.contains("sketch-sync"))
                if (isTuYaApp) {
                    Log.i(TAG, "TuYaApp page finished loading, scheduling dataChannelNotify re-send")
                    // Use longer delay (500ms) to ensure GSMA bridge JS is fully initialized
                    view?.postDelayed({
                        val adcState = activity.webRtcManager.adcChannel?.state()
                        Log.i(TAG, "Re-sending dataChannelNotify: ADC State=$adcState")
                        if (adcState == org.webrtc.DataChannel.State.OPEN) {
                            val dcLabel = activity.imsDcSdk.activeDcLabels.firstOrNull() ?: "local_${activity.currentAppId}_1_data"
                            activity.imsDcSdk.notifyDataChannelState(dcLabel, 1)
                        }
                    }, 500)
                }
            }
        }

        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.i("WebViewConsole", "[${it.messageLevel()}] ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                }
                return true
            }

            override fun onConsoleMessage(message: String?, lineNumber: Int, sourceID: String?) {
                Log.i("WebViewConsole", "[LEGACY] $message ($sourceID:$lineNumber)")
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: android.webkit.WebChromeClient.FileChooserParams?
            ): Boolean {
                filePathCallback ?: return false
                Log.i(TAG, "WebView file chooser requested (accept=${fileChooserParams?.acceptTypes?.joinToString()})")
                return activity.launchPhotoPicker(filePathCallback)
            }
        }

        // Enable WebView debugging (Android 4.4+)
        WebView.setWebContentsDebuggingEnabled(true)
    }

    // Flight-booking is a full-height sheet with its own header and call timer, so it gets
    // the whole screen above the call controls instead of the band under the top bar.
    private fun applyWebViewBounds(appId: String) {
        val b = activity.binding
        val full = appId == "flight-booking"
        val lp = b.webviewMiniapp.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        val videoTiles = appId == "caro"
        applyVideoTiles(videoTiles)
        if (videoTiles) {
            // Caro: both faces side by side at the top, the game in the rest of the screen above the controls.
            lp.topToTop = unset
            lp.topToBottom = b.localVideoView.id
            lp.topMargin = dp(TILE_TO_GAME_GAP_DP)
            lp.bottomToTop = b.layoutBottomControls.id
            lp.bottomToBottom = unset
        } else if (full) {
            // Leave room for the status bar, and for the exit button when it is shown.
            val topDp = if (CallActivity.hotlineApp(activity.remoteUser) != null) 40 else 72
            lp.topToBottom = unset
            lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            lp.topMargin = (topDp * activity.resources.displayMetrics.density).toInt()
            lp.bottomToTop = b.layoutBottomControls.id
        } else {
            lp.topToTop = unset
            lp.topToBottom = b.layoutTopBar.id
            lp.topMargin = 0
            lp.bottomToTop = b.guidelineWebviewBottom.id
        }
        b.webviewMiniapp.layoutParams = lp
        b.layoutTopBar.visibility = if (full) View.INVISIBLE else View.VISIBLE
    }

    private val TILE_GAP_DP = 6
    private val TILE_TO_GAME_GAP_DP = 12
    // Each tile's share of the screen width; the pair is centred. Keeps the board large on S24-class phones.
    private val TILE_WIDTH_FRACTION = 0.34f

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    // Video tiles (caro): my camera top-left, the other person top-right, both 3:4 (the remote stream is
    // 480x640 and the remote SurfaceView stretches to its size). Otherwise restore the defaults from
    // activity_call.xml: remote video full screen, my camera a 90x125dp box at the top left.
    private fun applyVideoTiles(enabled: Boolean) {
        val b = activity.binding
        val local = b.localVideoView
        val remote = b.remoteVideoView
        val localLp = local.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val remoteLp = remote.layoutParams as android.widget.FrameLayout.LayoutParams

        // Undo any drag of my camera box (the drag handler moves it with translation).
        local.translationX = 0f
        local.translationY = 0f

        if (enabled) {
            val screenW = (b.root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels)
            val tileW = (screenW * TILE_WIDTH_FRACTION).toInt()
            val tileH = tileW * 4 / 3
            val sideMargin = (screenW - 2 * tileW - dp(TILE_GAP_DP)) / 2
            localLp.width = tileW
            localLp.height = tileH
            localLp.setMargins(sideMargin, dp(TILE_GAP_DP), 0, 0)
            local.layoutParams = localLp
            local.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)

            // The remote view lives in a full-screen FrameLayout, so place it by margins: below the
            // top bar, level with my tile, to its right.
            fun placeRemote() {
                remoteLp.width = tileW
                remoteLp.height = tileH
                remoteLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                remoteLp.leftMargin = sideMargin + tileW + dp(TILE_GAP_DP)
                remoteLp.topMargin = b.layoutTopBar.bottom + dp(TILE_GAP_DP)
                remote.layoutParams = remoteLp
            }
            if (b.layoutTopBar.bottom > 0) placeRemote() else b.layoutTopBar.post { placeRemote() }
        } else {
            localLp.width = dp(90)
            localLp.height = dp(125)
            localLp.setMargins(dp(16), dp(16), dp(16), dp(16))
            local.layoutParams = localLp
            local.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_BALANCED)

            remoteLp.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            remoteLp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            remoteLp.gravity = android.view.Gravity.NO_GRAVITY
            remoteLp.leftMargin = 0
            remoteLp.topMargin = 0
            remote.layoutParams = remoteLp
        }
    }

    fun launchMiniApp(appId: String, sendRemoteTrigger: Boolean = true) {
        if (sendRemoteTrigger) {
            activity.isMiniAppInitiator = true
        } else {
            activity.isMiniAppInitiator = false
        }
        activity.imsDcSdk.resetLabels()
        activity.lifecycleScope.launch(Dispatchers.Main) {
            val targetId = when {
                appId.contains("0003210000000038") || appId.contains("tuyaapp") || appId.contains("sketch-sync-app") -> "sketch-sync-app"
                appId.contains("operator-menu") -> "operator-menu"
                else -> appId
            }
            activity.currentAppId = targetId
            activity.webRtcManager.foregroundIsHome = (targetId == HOME_APP || targetId == "operator-menu")
            Log.i(TAG, "launchMiniApp targetId=$targetId")
            applyWebViewBounds(targetId)

            if (targetId == "operator-menu") {
                Log.i(TAG, "Exiting mini-app and returning to operator-menu")
                activity.dcManager.cancelPendingDownload()
                activity.dcManager.targetAppId = "operator-menu"
                activity.binding.btnExitMiniapp.visibility = View.GONE
                activity.binding.tvCallStatus.text = "\ud83d\udfe2 Cu\u1ed9c g\u1ecdi tho\u1ea1i & video \u0111\u00e3 k\u1ebft n\u1ed1i"

                if (sendRemoteTrigger) {
                    triggerAppNegotiationReInvite(HOME_APP)
                }

                activity.binding.layoutMiddleDetails.visibility = View.GONE
                activity.binding.webviewMiniapp.visibility = View.VISIBLE
                activity.binding.webviewMiniapp.bringToFront()
                activity.binding.localVideoView.bringToFront()
                activity.binding.layoutTopBar.bringToFront()

                // Load operator-menu from local cache or bundled assets to load instantly.
                // BDC remains active throughout the call session to support offline and network fallbacks.
                activity.binding.webviewMiniapp.loadUrl("https://miniapp/index.html")
                val cachedMenuDir = java.io.File(activity.cacheDir, "miniapps/operator-menu")
                val cachedIndex = java.io.File(cachedMenuDir, "index.html")
                if (!cachedIndex.exists()) {
                    Log.i(TAG, "operator-menu not cached locally, requesting via BDC")
                    activity.dcManager.setupBDC()
                }
                return@launch
            }

            if (targetId == "sketch-sync-app") {
                activity.binding.tvCallStatus.text = "🎨 Bảng vẽ TuYaApp (GSMA Standard)"
            } else if (targetId == CallActivity.HOME_APP) {
                activity.binding.tvCallStatus.text = "\ud83d\udfe2 Cu\u1ed9c g\u1ecdi tho\u1ea1i & video \u0111\u00e3 k\u1ebft n\u1ed1i"
            } else if (targetId == "smart-menu") {
                activity.binding.tvCallStatus.text = "📞 Tổng đài 198 (Smart Menu)"
            } else if (targetId == "caro") {
                activity.binding.tvCallStatus.text = "♟️ Game Cờ Caro (5G New Calling)"
            } else if (targetId == "flight-booking") {
                activity.binding.tvCallStatus.text = if (CallActivity.hotlineApp(activity.remoteUser) != null)
                    "📞 Tổng đài SkyLotus Air ${activity.remoteUser}" else "✈️ Đặt vé SkyLotus Air (5G New Calling)"
            } else if (targetId == "enterprise-callerid") {
                activity.binding.tvCallStatus.text = "🏢 Doanh nghiệp đã xác thực (Enterprise Caller ID)"
            } else {
                activity.binding.tvCallStatus.text = "📱 Ứng dụng: $targetId"
            }
            if (targetId == "smart-menu" || targetId == CallActivity.HOME_APP || CallActivity.hotlineApp(activity.remoteUser) != null) {
                activity.binding.btnExitMiniapp.visibility = View.GONE
            } else {
                activity.binding.btnExitMiniapp.visibility = View.VISIBLE
                activity.binding.btnExitMiniapp.bringToFront()
            }
            activity.dcManager.targetAppId = targetId

            // Chuyển giao diện lập tức: Ẩn menu card, hiện WebView
            activity.binding.layoutMiddleDetails.visibility = View.GONE
            activity.binding.webviewMiniapp.visibility = View.VISIBLE
            activity.binding.webviewMiniapp.bringToFront()
            if (targetId == "enterprise-callerid" || targetId == "smart-menu" || targetId == "flight-booking") {
                activity.binding.localVideoView.visibility = View.GONE
            } else {
                activity.binding.localVideoView.visibility = View.VISIBLE
                activity.binding.localVideoView.bringToFront()
            }
            activity.binding.layoutTopBar.bringToFront()
            activity.binding.btnExitMiniapp.bringToFront()

            // 1. Gửi tin nhắn điều khiển P2P qua ADC (Stream 1000) để bảo đầu kia mở ứng dụng
            if (sendRemoteTrigger) {
                val adc = activity.webRtcManager.adcChannel
                if (adc != null && adc.state() == org.webrtc.DataChannel.State.OPEN) {
                    val msg = "{\"type\":\"draw\",\"action\":\"LAUNCH_APP\",\"appId\":\"$targetId\"}"
                    val bytes = msg.toByteArray(Charsets.UTF_8)
                    val buffer = org.webrtc.DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false)
                    adc.send(buffer)
                    Log.i(TAG, "Sent JSON LAUNCH_APP via ADC: $msg")
                }
            }

            // 2. Load cached app if available, otherwise asset (bundled in APK)
            val cachedAppDir = java.io.File(activity.cacheDir, "miniapps/$targetId")
            val localIndex = java.io.File(cachedAppDir, "index.html")

            Log.i(TAG, "Loading mini-app: https://miniapp/index.html (cached=${localIndex.exists()})")
            activity.binding.webviewMiniapp.loadUrl("https://miniapp/index.html")

            // 3. Khởi chạy qua GSMA SDK Interface (com.ct.ertclib.dc.core)
            activity.imsDcSdk.startMiniApp(activity.callId ?: "call1001", targetId)

            // 4. Send BDC request with ETag
            val prefs = activity.getSharedPreferences("miniapps_etag", android.content.Context.MODE_PRIVATE)
            val savedEtag = if (localIndex.exists()) prefs.getString(targetId, "") else ""

            Log.i(TAG, "Sending BDC request for appId=$targetId (If-None-Match: $savedEtag)")
            activity.dcManager.sendAppRequest(targetId, savedEtag ?: "")

            // Initiator triggers SIP Re-INVITE to negotiate ADC and tell callee to launch the app
            if (sendRemoteTrigger && targetId != "operator-menu") {
                Log.i(TAG, "Initiator launching $targetId -> triggering SIP Re-INVITE")
                triggerAppNegotiationReInvite(targetId)
            }
        }
    }

    companion object {
        private const val HOME_APP = CallActivity.HOME_APP
        private const val ANSWER_TIMEOUT_MS = 5000L
    }

    private val requests = LatestRequestQueue<String>()
    private var lastTarget: String? = null
    private var reInviteAnswer: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun onReInviteAnswered() {
        reInviteAnswer?.complete(Unit)
    }

    private fun normalizeTarget(appId: String): String {
        val id = when {
            appId.contains("0003210000000038") || appId.contains("tuyaapp") || appId.contains("sketch-sync-app") -> "sketch-sync-app"
            else -> appId
        }
        val homeOpen = activity.webRtcManager.homeAdcPC != null
        return if (id.contains("operator-menu") && homeOpen) HOME_APP else id
    }

    fun triggerAppNegotiationReInvite(appId: String) {
        val target = normalizeTarget(appId)
        if (target.contains("operator-menu")) return // Home not open: local menu, nothing to negotiate
        requests.submit(target)?.let { runRequest(it) }
            ?: Log.i(TAG, "Re-INVITE in flight, keeping only the latest request: $target")
    }

    private fun runRequest(target: String) {
        activity.lifecycleScope.launch(Dispatchers.Main) {
            try {
                if (target != HOME_APP && activity.webRtcManager.homeAdcPC == null) {
                    sendChange(HOME_APP)            // Home first (mid 3), see ruling
                }
                sendChange(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Re-INVITE coroutine cancelled for $target (call ended)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Re-INVITE for $target: ${e.message}")
            } finally {
                requests.complete()?.let { runRequest(it) }
            }
        }
    }

    /** Applies the local ADC change for [target] and sends exactly one Re-INVITE (or none if nothing changes). */
    private suspend fun sendChange(target: String) {
        lastTarget = target
        val web = activity.webRtcManager
        val sip = activity.sipManager
        if (target == HOME_APP) {
            val homeOpen = web.homeAdcPC != null
            val appOpen = sip.appReqApp != null
            if (homeOpen && !appOpen) return          // already on emotes: nothing to negotiate
            if (!homeOpen) {
                sip.homeReqApp = HOME_APP
                web.createADC(); activity.dcManager.setupADC()
                web.createADC2(); activity.dcManager.setupADC2()
            }
            if (appOpen) {
                sip.appReqApp = null                  // mid 4 -> port 0
                web.closeAppADC()
            }
        } else {
            if (sip.appReqApp == target && web.appAdcPC != null) return
            web.closeAppADC()                         // replace in place: new PC, new credentials, new tls-id
            sip.appReqApp = target
            sip.appSlotUsed = true
            web.createAppADC()
            activity.dcManager.setupAppADC()
        }
        val localOffer = web.createOffer()
        reInviteAnswer = kotlinx.coroutines.CompletableDeferred()
        Log.i(TAG, "Re-INVITE for $target (home=${sip.homeReqApp}, app=${sip.appReqApp})")
        withContext(Dispatchers.IO) { sip.sendReInviteWithSdp(localOffer) }
        val answered = kotlinx.coroutines.withTimeoutOrNull(ANSWER_TIMEOUT_MS) { reInviteAnswer?.await() }
        if (answered == null) onReInviteUnanswered(target) // Task 9
    }

    /** Drops queued work; call at call end because a cancelled scope can leave the queue busy. */
    fun resetRequests() {
        requests.reset()
        reInviteAnswer?.complete(Unit)
    }

    /** Roll back the App slot only. Home ADC and BDC are untouched. */
    private fun rollbackAppSlot(reason: String) {
        Log.w(TAG, "Rolling back App ADC: $reason")
        activity.webRtcManager.closeAppADC()
        activity.sipManager.appReqApp = null          // mid 4 -> port 0 locally; appSlotUsed stays true
        android.widget.Toast.makeText(activity, "Không mở được ứng dụng, quay về emote", android.widget.Toast.LENGTH_SHORT).show()
        launchMiniApp(HOME_APP, sendRemoteTrigger = false)
    }

    private fun onReInviteUnanswered(target: String) {
        if (target != HOME_APP) rollbackAppSlot("no 200 OK within ${ANSWER_TIMEOUT_MS} ms for $target")
        else Log.w(TAG, "No 200 OK for the Home Re-INVITE")
    }

    fun onReInviteFailed(code: Int) {
        val target = lastTarget ?: return
        reInviteAnswer?.complete(Unit)                // unblock the waiting request
        if (code == 491) {
            val delayMs = reInviteRetryDelayMs(isCallIdOwner = activity.sipManager.isCallIdOwner)
            Log.i(TAG, "491 glare for $target, retrying in $delayMs ms")
            activity.lifecycleScope.launch(Dispatchers.Main) {
                kotlinx.coroutines.delay(delayMs)
                triggerAppNegotiationReInvite(target)
            }
        } else if (target != HOME_APP) {
            rollbackAppSlot("Re-INVITE for $target failed with $code")
        }
    }
}
