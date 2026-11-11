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
import com.demo.imsdc.sip.SdpHelper
import com.demo.imsdc.sip.SipManager
import com.demo.imsdc.sip.SlidesState
import com.demo.imsdc.media.ContentReceiver
import com.demo.imsdc.media.ContentStreamer
import com.demo.imsdc.media.RtpPorts
import com.demo.imsdc.webrtc.AndroidBridgeInterface
import com.demo.imsdc.webrtc.DataChannelManager
import com.demo.imsdc.webrtc.LatestRequestQueue
import com.demo.imsdc.webrtc.MAX_GLARE_ATTEMPTS
import com.demo.imsdc.webrtc.OutcomeAction
import com.demo.imsdc.webrtc.PendingReInvite
import com.demo.imsdc.webrtc.ReInviteOutcome
import com.demo.imsdc.webrtc.SlotPlan
import com.demo.imsdc.webrtc.SlotState
import com.demo.imsdc.webrtc.confirmedAfter
import com.demo.imsdc.webrtc.desiredSlots
import com.demo.imsdc.webrtc.needsSyncAfterRollback
import com.demo.imsdc.webrtc.pageToRelaunch
import com.demo.imsdc.webrtc.outcomeAction
import com.demo.imsdc.webrtc.planSlotChange
import com.demo.imsdc.webrtc.supersededDuringBackOff
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                activity.cancelMapLocationForNavigation()
                super.onPageStarted(view, url, favicon)
            }

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
                        // Pages are not bundled in the APK any more: only the DCAR package in the cache counts.
                        Log.w(TAG, "Intercepted file not found: ${file.absolutePath} for $url")
                        return android.webkit.WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
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
                activity.binding.layoutBottomControls.bringToFront() // holds the Menu button
                raiseFullScreenOverlays()
                activity.binding.webviewMiniapp.requestFocus()

                // Fix race condition: dataChannelNotify may have fired before JS was loaded.
                // Only re-send for actual mini-app pages (TuYaApp), not operator-menu.
                val isTuYaApp = url?.contains("tuyaapp") == true ||
                        (activity.currentAppId.contains("0003210000000038") || activity.currentAppId.contains("tuyaapp") || activity.currentAppId.contains("sketch-sync"))
                if (isTuYaApp) {
                    Log.i(TAG, "TuYaApp page finished loading, scheduling dataChannelNotify re-send")
                    // Use longer delay (500ms) to ensure GSMA bridge JS is fully initialized
                    view?.postDelayed({
                        val adcState = activity.webRtcManager.foregroundAdcChannel?.state()
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
        val fullScreen = isFullScreen(appId)
        val lp = b.webviewMiniapp.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        val parent = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        val videoTiles = appId == "caro"
        applyVideoTiles(videoTiles, fullScreen)
        lp.bottomToBottom = unset
        lp.bottomMargin = 0
        if (videoTiles) {
            // Caro: both faces side by side at the top, the game in the rest of the screen.
            lp.topToTop = unset
            lp.topToBottom = b.localVideoView.id
            lp.topMargin = dp(TILE_TO_GAME_GAP_DP)
            if (fullScreen) {
                // Full screen: the game runs to the bottom, above the call pill.
                lp.bottomToTop = unset
                lp.bottomToBottom = parent
                lp.bottomMargin = dp(CARO_PILL_SPACE_DP)
            } else {
                lp.bottomToTop = b.layoutBottomControls.id
            }
        } else if (fullScreen) {
            lp.topToBottom = unset
            lp.topToTop = parent
            lp.topMargin = 0
            lp.bottomToTop = unset
            lp.bottomToBottom = parent
        } else if (full) {
            // Leave room for the status bar, and for the exit button when it is shown.
            val topDp = if (CallActivity.hotlineApp(activity.remoteUser) != null) 40 else 72
            lp.topToBottom = unset
            lp.topToTop = parent
            lp.topMargin = (topDp * activity.resources.displayMetrics.density).toInt()
            lp.bottomToTop = b.layoutBottomControls.id
        } else {
            lp.topToTop = unset
            lp.topToBottom = b.layoutTopBar.id
            lp.topMargin = 0
            lp.bottomToTop = b.guidelineWebviewBottom.id
        }
        b.webviewMiniapp.layoutParams = lp
        b.layoutTopBar.visibility = if (full || fullScreen) View.INVISIBLE else View.VISIBLE
        applyFullScreenChrome(appId, fullScreen)
        applyRemoteFold()
    }

    private val TILE_GAP_DP = 6
    private val TILE_TO_GAME_GAP_DP = 12
    // Each tile's share of the screen width; the pair is centred. Keeps the board large on S24-class phones.
    private val TILE_WIDTH_FRACTION = 0.34f
    /** Full-screen Caro: the page's header (score pills, turn line) plus the board panel's top padding. */
    private val CARO_PAGE_CHROME_DP = 80
    /** The Caro page's board is 92% of its width (--board-size in caro/index.html). */
    private val CARO_BOARD_WIDTH_FRACTION = 0.92f
    /** Full-screen Caro: height left over after growing the tiles (the page centres the board in it). */
    private val CARO_SLACK_DP = 24
    /** Side margin of the tile pair when the tiles grow to fill a tall screen. */
    private val CARO_TILE_SIDE_DP = 12
    /** Full-screen Caro: tiles start this far from the top of the screen. */
    private val FULLSCREEN_TILE_TOP_DP = 12
    /** Full-screen Caro: room under the board for the call pill. */
    private val CARO_PILL_SPACE_DP = 52
    /** Full screen: my camera's distance from the top of the screen. */
    private val FULLSCREEN_SELF_TOP_DP = 12
    /** How long the call buttons stay up after a tap in full screen. */
    private val CALL_CONTROLS_SHOW_MS = 3500L
    /** Gap between my camera and the other person's tile (Vẽ, Chia sẻ). */
    private val REMOTE_TILE_GAP_DP = 8

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    /** Chia sẻ menu (full screen, the page asks): my camera and the other person side by side at the top, like Caro (no folding). */
    private fun shareRow(appId: String = activity.currentAppId) = appId == SHARE_APP && cameraRowRequested && isFullScreen(appId)

    /** Caro and full-screen Chia sẻ keep both tiles in a fixed row: no folding, no dragging. */
    fun fixedTiles(appId: String = activity.currentAppId) = appId == "caro" || shareRow(appId)

    /** My camera can be dragged only on the call screen itself (emote home, or no page shown); inside any app
     *  (Chia sẻ and everything in it, Vẽ, Caro, …) it stays where the app puts it. Tapping still folds it. */
    fun cameraDraggable(appId: String = activity.currentAppId) =
        appId == HOME_APP || activity.binding.webviewMiniapp.visibility != View.VISIBLE

    private fun screenW() = activity.binding.root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels

    /** Chia sẻ row: each tile's width and height, and the left margin that centres the pair. */
    private fun shareRowTile(): Triple<Int, Int, Int> {
        val w = (screenW() * TILE_WIDTH_FRACTION).toInt()
        return Triple(w, w * 4 / 3, (screenW() - 2 * w - dp(TILE_GAP_DP)) / 2)
    }

    // Video tiles (caro): my camera top-left, the other person top-right, both 3:4 (the remote stream is
    // 480x640 and the remote SurfaceView stretches to its size). Otherwise restore the defaults from
    // activity_call.xml: remote video full screen, my camera a 90x125dp box at the top left (folded into a
    // button when the user tapped it, see onCameraTileTapped).
    private fun applyVideoTiles(enabled: Boolean, fullScreen: Boolean = false) {
        val b = activity.binding
        val local = b.localVideoView
        val remote = b.remoteVideoView
        val localLp = local.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val remoteLp = remote.layoutParams as android.widget.FrameLayout.LayoutParams
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        val parent = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID

        // Undo any drag of my camera box (the drag handler moves it with translation).
        local.translationX = 0f
        local.translationY = 0f

        // Anchor: top-left, below the top bar, or at the top of the screen in full screen (no top bar).
        localLp.topToBottom = if (fullScreen) unset else b.layoutTopBar.id
        localLp.topToTop = if (fullScreen) parent else unset
        localLp.startToStart = parent
        localLp.endToEnd = unset
        localLp.bottomToBottom = unset
        // The folded camera button sits where the tile's corner is.
        val btnLp = b.btnCameraRestore.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        btnLp.topToBottom = localLp.topToBottom
        btnLp.topToTop = localLp.topToTop
        btnLp.setMargins(dp(16), if (fullScreen) dp(FULLSCREEN_SELF_TOP_DP) else dp(16), 0, 0)
        b.btnCameraRestore.layoutParams = btnLp

        if (enabled) {
            val screenW = (b.root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels)
            var tileW = (screenW * TILE_WIDTH_FRACTION).toInt()
            if (fullScreen) {
                // Tall screens left the bottom empty under the board: grow the tiles into that height,
                // up to the pair filling the width. What is left (CARO_SLACK_DP and up) the page centres.
                val screenH = b.root.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
                val pageNeed = dp(CARO_PAGE_CHROME_DP) + (screenW * CARO_BOARD_WIDTH_FRACTION).toInt()
                val spareH = screenH - dp(FULLSCREEN_TILE_TOP_DP) - dp(TILE_TO_GAME_GAP_DP) -
                    dp(CARO_PILL_SPACE_DP) - pageNeed - dp(CARO_SLACK_DP)
                val maxW = (screenW - dp(TILE_GAP_DP) - 2 * dp(CARO_TILE_SIDE_DP)) / 2
                tileW = (spareH * 3 / 4).coerceIn(tileW, maxOf(tileW, maxW))
            }
            val tileH = tileW * 4 / 3
            val sideMargin = (screenW - 2 * tileW - dp(TILE_GAP_DP)) / 2
            val topMargin = if (fullScreen) dp(FULLSCREEN_TILE_TOP_DP) else dp(TILE_GAP_DP)
            localLp.width = tileW
            localLp.height = tileH
            localLp.setMargins(sideMargin, topMargin, 0, 0)
            local.layoutParams = localLp
            local.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)

            // The remote view lives in a full-screen FrameLayout, so place it by margins: level with my
            // tile, to its right (below the top bar, or at the top of the screen in full screen).
            fun placeRemote() {
                remoteLp.width = tileW
                remoteLp.height = tileH
                remoteLp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                remoteLp.leftMargin = sideMargin + tileW + dp(TILE_GAP_DP)
                remoteLp.topMargin = if (fullScreen) topMargin else b.layoutTopBar.bottom + dp(TILE_GAP_DP)
                remote.layoutParams = remoteLp
            }
            if (fullScreen || b.layoutTopBar.bottom > 0) placeRemote() else b.layoutTopBar.post { placeRemote() }
        } else if (shareRow()) {
            // Chia sẻ: my camera on the left of the row; the other person's tile (applyRemoteFold) on the right.
            val (tileW, tileH, sideMargin) = shareRowTile()
            localLp.width = tileW
            localLp.height = tileH
            localLp.setMargins(sideMargin, dp(FULLSCREEN_TILE_TOP_DP), 0, 0)
            local.layoutParams = localLp
            local.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            fullScreenRemote(remote, remoteLp)
        } else {
            localLp.width = dp(90)
            localLp.height = dp(125)
            localLp.setMargins(dp(16), if (fullScreen) dp(FULLSCREEN_SELF_TOP_DP) else dp(16), dp(16), dp(16))
            local.layoutParams = localLp
            local.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_BALANCED)
            fullScreenRemote(remote, remoteLp)
        }
    }

    /** Full-screen call video (under the page in Vẽ / Chia sẻ, which show the tile instead). */
    private fun fullScreenRemote(remote: View, remoteLp: android.widget.FrameLayout.LayoutParams) {
        remoteLp.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        remoteLp.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        remoteLp.gravity = android.view.Gravity.NO_GRAVITY
        remoteLp.leftMargin = 0
        remoteLp.topMargin = 0
        remote.layoutParams = remoteLp
    }

    // ───────────────────────── Full screen ─────────────────────────
    // Caro is always full screen; Content Sharing asks for it while its photo viewer or video is showing.
    // Full screen hides the call bar, the call buttons, the exit button and Android's status and navigation
    // bars, and leaves a small call pill (green dot + timer) at the bottom centre. A tap on the pill brings
    // the call buttons back for a few seconds. My camera stays at the top left (or folded into a button).

    /** The page currently on screen asked for full screen (reset whenever another page is launched). */
    @Volatile private var fullScreenRequested = false
    /** Chia sẻ page: its menu shows the cameras in a row at the top; its photo / video views use the corner tiles. */
    @Volatile private var cameraRowRequested = false
    /** The user folded the camera tile into a button; kept for the rest of the call. */
    private var cameraFolded = false
    private var callControlsShown = false
    private val hideCallControls = Runnable { setCallControlsShown(false) }

    private fun isFullScreen(appId: String): Boolean =
        CallActivity.hotlineApp(activity.remoteUser) == null &&
            (appId == "caro" || (appId in FULLSCREEN_ON_REQUEST && fullScreenRequested))

    private fun exitButtonWanted(appId: String): Boolean =
        !(appId == "smart-menu" || appId == CallActivity.HOME_APP || appId == "operator-menu" ||
            CallActivity.hotlineApp(activity.remoteUser) != null)

    /** Page: enter (true) or leave (false) full screen. Content Sharing and the drawing board ask. */
    fun setFullScreen(on: Boolean) {
        activity.runOnUiThread {
            if (activity.currentAppId !in FULLSCREEN_ON_REQUEST || fullScreenRequested == on) return@runOnUiThread
            fullScreenRequested = on
            applyWebViewBounds(activity.currentAppId)
            applyCameraFold()
        }
    }

    /** Page (Chia sẻ): cameras in a row at the top for the menu (true), corner tiles for photo / video (false). */
    fun setCameraRow(on: Boolean) {
        activity.runOnUiThread {
            if (activity.currentAppId != SHARE_APP || cameraRowRequested == on) return@runOnUiThread
            cameraRowRequested = on
            applyWebViewBounds(activity.currentAppId)
            applyCameraFold() // the row cannot fold; the corner tiles take back their folded state
        }
    }

    /** Show the call buttons for a few seconds (full screen only). The pill is the only trigger now; kept for pages. */
    fun showCallControls() {
        activity.runOnUiThread { if (isFullScreen(activity.currentAppId)) setCallControlsShown(true) }
    }

    /** Call pill: show the call buttons, or hide them if they are up. */
    fun toggleCallControls() {
        if (!isFullScreen(activity.currentAppId)) return
        setCallControlsShown(!callControlsShown)
    }

    /** Tap on my camera tile (not dragged): fold it into a camera button. Caro keeps its two tiles. */
    fun onCameraTileTapped() {
        if (fixedTiles()) return
        cameraFolded = true
        applyCameraFold()
    }

    /** Tap on the camera button: open my camera tile again. */
    fun onCameraButtonTapped() {
        cameraFolded = false
        applyCameraFold()
    }

    /** The page on screen shows my camera (some apps and hotlines hide it). */
    private fun cameraWanted(appId: String): Boolean =
        CallActivity.hotlineApp(activity.remoteUser) == null &&
            appId != "enterprise-callerid" && appId != "smart-menu" && appId != "flight-booking"

    /** Shows my camera as the tile or as the folded button; Caro always shows the tile. */
    private fun applyCameraFold() {
        val b = activity.binding
        val appId = activity.currentAppId
        if (!cameraWanted(appId) || b.webviewMiniapp.visibility != View.VISIBLE) {
            b.btnCameraRestore.visibility = View.GONE
            applyRemoteFold()
            return
        }
        val folded = cameraFolded && !fixedTiles(appId)
        b.localVideoView.visibility = if (folded) View.GONE else View.VISIBLE
        b.btnCameraRestore.visibility = if (folded) View.VISIBLE else View.GONE
        if (folded) b.btnCameraRestore.bringToFront() else b.localVideoView.bringToFront()
        applyRemoteFold() // its tile sits under my camera (or under the folded button)
    }

    // ───────────────────────── The other person's tile (Vẽ, Chia sẻ) ─────────────────────────
    // These pages cover the call video, so the other person shows as a small tile under my camera. A tap
    // folds it into a round button with their initial; a tap on the button opens it again. Like my camera,
    // the choice is kept for the rest of the call.

    private fun remoteTileApp(appId: String) = appId == "sketch-sync-app" || appId == SHARE_APP

    private var remoteFolded = false
    /** The tile shows the call video; CallActivity.attachRemoteVideo then decodes onto it. */
    var remoteTileShown = false
        private set

    fun onRemoteTileTapped() { if (!fixedTiles()) { remoteFolded = true; applyRemoteFold() } }
    fun onRemoteButtonTapped() { remoteFolded = false; applyRemoteFold() }

    /**
     * Shows the other person as a tile or a folded button in Vẽ / Chia sẻ; hides both elsewhere. The tile is
     * its own SurfaceView in the call UI layer (remote_tile_view): the background call video lies under the
     * page, which paints over it.
     */
    private fun applyRemoteFold() {
        val b = activity.binding
        val tile = b.remoteTileView
        val appId = activity.currentAppId
        // Also over a shared video or screen: the tile is a media overlay, above the content's plain surface
        // layer, and it does not overlap my camera, so the two overlays never mix.
        val show = remoteTileApp(appId) && cameraWanted(appId) && b.webviewMiniapp.visibility == View.VISIBLE
        val row = shareRow(appId)
        val folded = remoteFolded && !row
        val tileWanted = show && !folded
        if (!tileWanted) {
            // Move the decoder back to the background before the tile's surface goes away.
            if (remoteTileShown) { remoteTileShown = false; activity.attachRemoteVideo() }
            tile.visibility = View.GONE
        }
        if (!show) {
            b.btnRemoteRestore.visibility = View.GONE
            return
        }
        val fullScreen = isFullScreen(appId)
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        val parent = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        // Under my camera: the tile (or its folded button) at the top left, then a small gap.
        val selfTop = if (fullScreen) dp(FULLSCREEN_SELF_TOP_DP) else dp(16)
        val top = selfTop + (if (cameraFolded) dp(44) else dp(125)) + dp(REMOTE_TILE_GAP_DP)
        val (rowW, rowH, rowSide) = shareRowTile()
        for (v in listOf<View>(tile, b.btnRemoteRestore)) {
            val lp = v.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            lp.topToBottom = if (fullScreen) unset else b.layoutTopBar.id
            lp.topToTop = if (fullScreen) parent else unset
            lp.startToStart = parent
            if (row) lp.setMargins(rowSide + rowW + dp(TILE_GAP_DP), dp(FULLSCREEN_TILE_TOP_DP), 0, 0)
            else lp.setMargins(dp(16), top, 0, 0)
            if (v === tile) { lp.width = if (row) rowW else dp(90); lp.height = if (row) rowH else dp(125) }
            v.layoutParams = lp
        }

        val name = activity.peerName
        b.btnRemoteRestore.text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        b.btnRemoteRestore.contentDescription = "Hiện video của $name"
        b.btnRemoteRestore.visibility = if (folded) View.VISIBLE else View.GONE
        if (folded) {
            b.btnRemoteRestore.bringToFront()
        } else {
            remoteTileShown = true
            tile.visibility = View.VISIBLE
            tile.bringToFront() // drawn after the page, like my camera
            activity.attachRemoteVideo() // its surface may already exist; otherwise surfaceCreated does it
        }
    }

    private fun applyFullScreenChrome(appId: String, on: Boolean) {
        val b = activity.binding
        val insets = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (on) {
            insets.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            // The pill sits at the bottom centre: my camera has the top-left corner.
            val pillLp = b.layoutCallPill.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            val parent = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            pillLp.topToTop = unset; pillLp.bottomToBottom = parent; pillLp.startToStart = parent; pillLp.endToEnd = parent
            pillLp.setMargins(0, 0, 0, dp(12))
            b.layoutCallPill.layoutParams = pillLp
            setCallControlsShown(false)
        } else {
            insets.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            b.layoutBottomControls.removeCallbacks(hideCallControls)
            callControlsShown = false
            b.layoutCallPill.visibility = View.GONE
            b.layoutBottomControls.visibility = View.VISIBLE
            b.btnExitMiniapp.visibility = if (exitButtonWanted(appId)) View.VISIBLE else View.GONE
        }
    }

    /**
     * Re-applies the full-screen state after code that raises the WebView, the top bar or the exit button
     * (page launch, page loaded): those would otherwise cover the pill or show the windowed chrome again.
     */
    private fun raiseFullScreenOverlays() {
        applyCameraFold()
        if (!isFullScreen(activity.currentAppId)) return
        setCallControlsShown(callControlsShown)
    }

    private fun setCallControlsShown(shown: Boolean) {
        val b = activity.binding
        b.layoutBottomControls.removeCallbacks(hideCallControls)
        if (!isFullScreen(activity.currentAppId)) return
        callControlsShown = shown
        b.layoutBottomControls.visibility = if (shown) View.VISIBLE else View.GONE
        b.btnExitMiniapp.visibility = if (shown && exitButtonWanted(activity.currentAppId)) View.VISIBLE else View.GONE
        b.layoutCallPill.visibility = if (shown) View.GONE else View.VISIBLE
        if (shown) {
            b.layoutBottomControls.bringToFront()
            b.layoutBottomControls.postDelayed(hideCallControls, CALL_CONTROLS_SHOW_MS)
        } else {
            b.layoutCallPill.bringToFront()
        }
        applyCameraFold()
    }

    fun launchMiniApp(appId: String, sendRemoteTrigger: Boolean = true, initiator: Boolean = sendRemoteTrigger) {
        activity.lifecycleScope.launch(Dispatchers.Main) {
            val targetId = when {
                appId.contains("0003210000000038") || appId.contains("tuyaapp") || appId.contains("sketch-sync-app") -> "sketch-sync-app"
                appId.contains("operator-menu") -> "operator-menu"
                else -> appId
            }
            // Opening an app on the App line asks the other phone first: stay on the current page until it
            // accepts (sendChange shows the page on 200 OK); a decline or no answer leaves nothing to undo.
            if (sendRemoteTrigger && opensWithPeer(targetId)) {
                Log.i(TAG, "launchMiniApp $targetId: waiting for ${activity.peerName} to accept before showing it")
                triggerAppNegotiationReInvite(targetId)
                return@launch
            }
            // A remote trigger (LAUNCH_APP, Re-INVITE) for the page already on screen must not reload it:
            // a reload resets the page and re-sends its startup messages (e.g. caro's sync_state).
            if (!sendRemoteTrigger && targetId == activity.currentAppId &&
                activity.binding.webviewMiniapp.visibility == View.VISIBLE) {
                Log.i(TAG, "launchMiniApp targetId=$targetId already shown, not reloading")
                return@launch
            }
            activity.cancelMapLocationForNavigation()
            // The page on screen changes from here on; until now the current page kept its channels.
            activity.isMiniAppInitiator = initiator
            activity.imsDcSdk.resetLabels()
            if (targetId != SHARE_APP) leaveShareForAppSwitch()
            activity.currentAppId = targetId
            // A hotline app lives on the Home slot (mid 3), so its traffic is on the Home channel.
            activity.webRtcManager.foregroundIsHome = (targetId == HOME_APP || targetId == "operator-menu" || hotlineApp != null)
            Log.i(TAG, "launchMiniApp targetId=$targetId")
            fullScreenRequested = false // a new page starts windowed; Content Sharing asks again
            cameraRowRequested = false
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

                // From the DCAR (or its cache), shown once the package is settled.
                beginLoad("operator-menu")
                return@launch
            }

            if (targetId == "sketch-sync-app") {
                activity.binding.tvCallStatus.text = "🎨 Bảng vẽ (Vẽ & ảnh)"
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
                activity.binding.layoutBottomControls.bringToFront()
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
            activity.binding.layoutBottomControls.bringToFront()
            raiseFullScreenOverlays()

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

            // 2. Khởi chạy qua GSMA SDK Interface (com.ct.ertclib.dc.core)
            activity.imsDcSdk.startMiniApp(activity.callId ?: "call1001", targetId)

            // 3. The page comes from the DCAR over the BDC (or the cache on 304), and is loaded once.
            beginLoad(targetId)

            // Initiator triggers SIP Re-INVITE to negotiate ADC and tell callee to launch the app
            if (sendRemoteTrigger && targetId != "operator-menu") {
                Log.i(TAG, "Initiator launching $targetId -> triggering SIP Re-INVITE")
                triggerAppNegotiationReInvite(targetId)
            }
        }
    }

    // ───────────────────────── Loading pages from the DCAR ─────────────────────────
    // Pages are not bundled in the APK: each launch asks the DCAR over the BDC (with the cached ETag) and
    // shows the page exactly once per launch: on 304 (cache), on the new package, or from the cache when
    // the network is slow. A later package for the same launch is only installed.

    private var loadToken = 0
    private var shownToken = -1
    private var loadTarget: String? = null
    private var loadTimers: kotlinx.coroutines.Job? = null

    private fun cachedIndex(appId: String) = java.io.File(activity.cacheDir, "miniapps/$appId/index.html")

    private fun beginLoad(appId: String) {
        val token = ++loadToken
        loadTarget = appId
        loadTimers?.cancel()
        val cached = cachedIndex(appId).exists()
        showStatusPage("Đang tải ứng dụng…", spinner = true)
        val etag = if (cached) activity.getSharedPreferences("miniapps_etag", Context.MODE_PRIVATE).getString(appId, "") ?: "" else ""
        Log.i(TAG, "Requesting $appId from the DCAR (cached=$cached, If-None-Match: $etag)")
        activity.dcManager.sendAppRequest(appId, etag)
        loadTimers = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(LOAD_CACHE_FALLBACK_MS)
            if (token != loadToken || shownToken == token) return@launch
            if (cachedIndex(appId).exists()) { showApp(appId, "timeout-cache"); return@launch }
            // Nothing cached and no package yet: keep waiting (large packages are slow), then ask again.
            repeat(LOAD_RETRIES) {
                delay(LOAD_RETRY_MS)
                if (token != loadToken || shownToken == token) return@launch
                Log.w(TAG, "No package for $appId yet, asking the DCAR again")
                showStatusPage("Mạng chậm, đang thử lại…", spinner = true)
                activity.dcManager.sendAppRequest(appId, "")
            }
            delay(LOAD_RETRY_MS)
            if (token == loadToken && shownToken != token) showStatusPage("Không tải được ứng dụng. Hãy quay về menu và thử lại.", spinner = false)
        }
    }

    /** DataChannelManager settled a package: show it if it belongs to the launch on screen and was not shown yet. */
    fun onAppReady(appId: String, source: String) {
        if (appId != loadTarget) { Log.i(TAG, "Package for $appId ($source) installed; $loadTarget is on screen"); return }
        if (shownToken == loadToken) { Log.i(TAG, "Package for $appId ($source): already shown for this launch, not reloading"); return }
        if (source == "failed") {
            if (cachedIndex(appId).exists()) showApp(appId, "failed-cache")
            else showStatusPage("Không tải được ứng dụng. Hãy quay về menu và thử lại.", spinner = false)
            return
        }
        showApp(appId, source)
    }

    private fun showApp(appId: String, why: String) {
        shownToken = loadToken
        loadTimers?.cancel()
        Log.i(TAG, "showApp $appId ($why)")
        activity.binding.webviewMiniapp.visibility = View.VISIBLE
        activity.binding.webviewMiniapp.loadUrl("https://miniapp/index.html")
    }

    private fun showStatusPage(text: String, spinner: Boolean) {
        val safe = android.text.TextUtils.htmlEncode(text)
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:transparent;font-family:sans-serif}
            .c{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;color:#fff;text-shadow:0 1px 3px #0009;font-size:15px;padding:24px;text-align:center}
            .s{width:34px;height:34px;border:3px solid rgba(255,255,255,.35);border-top-color:#fff;border-radius:50%;animation:r 0.9s linear infinite}
            @keyframes r{to{transform:rotate(360deg)}}</style></head>
            <body><div class="c">${if (spinner) "<div class=\"s\"></div>" else ""}<div>$safe</div></div></body></html>"""
        activity.binding.webviewMiniapp.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
    }

    /** Apps on the App line open on this phone only once the other phone accepts. */
    private fun opensWithPeer(appId: String): Boolean =
        appId != HOME_APP && appId != "operator-menu" && appId != "smart-menu" && hotlineApp == null

    companion object {
        private const val LOAD_CACHE_FALLBACK_MS = 8_000L
        private const val LOAD_RETRY_MS = 10_000L
        private const val LOAD_RETRIES = 2
        const val HOME_APP = CallActivity.HOME_APP
        /** After a 430, how long the re-registration gets before the Re-INVITE is sent again. */
        const val REREGISTER_WAIT_MS = 2000L
        private const val ANSWER_TIMEOUT_MS = 5000L
        /** A share request waits for a person, not for a stack: the far end rings (180) until its user decides. */
        private const val SHARE_ANSWER_TIMEOUT_MS = 35_000L
        /** The callee declines on its own after this long without an answer from its user. */
        private const val SHARE_DECISION_TIMEOUT_MS = 30_000L
        /** App id of the Content Sharing page. */
        const val SHARE_APP = "photo-share"
        /** Pages that go full screen when they ask (AndroidBridge.setFullScreen). */
        private val FULLSCREEN_ON_REQUEST = setOf(SHARE_APP, "sketch-sync-app")
    }

    /** One slot change. [sync]: re-offer the current local slots after a rejection; a sync is never re-synced. */
    private data class AdcRequest(val target: String, val sync: Boolean = false)

    private enum class ChangeResult { DONE, SUPERSEDED, REJECTED }

    private val requests = LatestRequestQueue<AdcRequest>()
    /** Serialises every Re-INVITE transaction: app changes, hold/resume and incoming Re-INVITEs. */
    private val negotiation = Mutex()
    private val pending = PendingReInvite()
    private var outcome: CompletableDeferred<ReInviteOutcome>? = null
    /** Slots the far end last agreed to; null = unknown (a Re-INVITE went unanswered). Guarded by [negotiation]. */
    @Volatile private var confirmed: SlotState? = SlotState(null, null)

    private val hotlineApp: String? get() = CallActivity.hotlineApp(activity.remoteUser)

    private fun localSlots(): SlotState {
        val web = activity.webRtcManager
        val sip = activity.sipManager
        return SlotState(
            home = if (web.homeAdcPC != null) sip.homeReqApp else null,
            app = if (web.appAdcPC != null) sip.appReqApp else null
        )
    }

    /** 200 OK for the Re-INVITE with [cseq]. Only the awaited CSeq counts: a late answer to an earlier one is dropped. */
    fun onReInviteAnswered(cseq: Int, sdpAnswer: String?, rawSdp: String? = null) {
        if (pending.finish(cseq)) { lastAnswerRawSdp = rawSdp; outcome?.complete(ReInviteOutcome.Answered(sdpAnswer)) }
        else Log.w(TAG, "Ignoring 200 OK for Re-INVITE CSeq $cseq: not the one awaited (late or unknown)")
    }

    fun onReInviteFailed(code: Int, cseq: Int) {
        if (pending.finish(cseq)) outcome?.complete(ReInviteOutcome.Failed(code))
        else Log.w(TAG, "Ignoring $code for Re-INVITE CSeq $cseq: not the one awaited (late or unknown)")
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
        submit(AdcRequest(target))
    }

    private fun submit(request: AdcRequest) {
        // Tapping the same app again while the other phone is deciding changes nothing.
        if (awaitingPeerDecision && currentRequest?.target == request.target) {
            Log.i(TAG, "Already asking the other phone about ${request.target}")
            return
        }
        requests.submit(request)?.let { runRequest(it) } ?: run {
            Log.i(TAG, "Re-INVITE in flight, keeping only the latest request: $request")
            // The other phone is still asking its user about the app we opened: withdraw it (CANCEL -> 487).
            if (awaitingPeerDecision) {
                val cs = pending.peek()
                if (cs > 0) activity.lifecycleScope.launch(Dispatchers.IO) { activity.sipManager.cancelReInvite(cs) }
            }
        }
    }

    /** An app-opening Re-INVITE of ours is waiting for the other phone's user (180 seen or expected). */
    @Volatile private var awaitingPeerDecision = false
    @Volatile private var ringingToastShown = false

    /** 180 for one of our Re-INVITEs: if it opens an app, the other phone is asking its user. */
    fun onReInviteProvisional(code: Int, cseq: Int) {
        if (code != 180 || !awaitingPeerDecision || ringingToastShown || cseq != pending.peek()) return
        ringingToastShown = true
        activity.runOnUiThread {
            Toast.makeText(activity, "Đang chờ ${activity.peerName} đồng ý…", Toast.LENGTH_SHORT).show()
        }
    }

    @Volatile private var currentRequest: AdcRequest? = null

    private fun runRequest(request: AdcRequest) {
        activity.lifecycleScope.launch(Dispatchers.Main) {
            currentRequest = request
            try {
                // Home first (mid 3): go-mf takes an App line only with a Home context. Not for hotlines (no mid 4).
                if (hotlineApp == null && request.target != HOME_APP && localSlots().home == null) {
                    val home = sendChange(AdcRequest(HOME_APP, request.sync), showsPage = false)
                    if (home != ChangeResult.DONE) {
                        if (home == ChangeResult.REJECTED) rollbackAppSlot("Home ADC could not be opened for ${request.target}")
                        return@launch
                    }
                }
                sendChange(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Re-INVITE coroutine cancelled for ${request.target} (call ended)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Re-INVITE for ${request.target}: ${e.message}")
            } finally {
                currentRequest = null
                requests.complete()?.let { runRequest(it) }
            }
        }
    }

    /**
     * Moves the slots to what [request] wants and negotiates it: one Re-INVITE, or none if the local slots
     * already match and the far end confirmed them. A 491 is re-offered after the RFC 3261 back-off: the
     * plan is recomputed against the unconfirmed local slots, so the same offer really goes out again.
     * [showsPage]: the page shows [request]'s target (false for the Home-first step of an App request).
     */
    private suspend fun sendChange(request: AdcRequest, showsPage: Boolean = true): ChangeResult {
        val desired = desiredSlots(request.target, HOME_APP, hotlineApp)
        var attempt = 1
        var confirmedAtGlare: SlotState? = null
        var lastFailedCode: Int? = null
        while (true) {
            val step = negotiation.withLock {
                // The far end's Re-INVITE, taken during the back-off, changed the slots: its offer won the glare.
                if (attempt > 1 && supersededDuringBackOff(confirmedAtGlare, confirmed)) {
                    Log.i(TAG, "Re-INVITE for ${request.target} superseded by the far end's offer: confirmed=$confirmed")
                    return@withLock OutcomeAction.SUPERSEDED to false
                }
                val plan = planSlotChange(desired, localSlots(), confirmed) ?: return@withLock null
                applyPlan(plan)
                val offered = localSlots()
                Log.i(TAG, "Re-INVITE for ${request.target} (attempt $attempt): offer=$offered confirmed=$confirmed")
                // Opening an app on the other phone waits for its user (180 ... 200 / 603), not only for its stack.
                val asksPeer = offered.app != null && offered.app != confirmed?.app
                awaitingPeerDecision = asksPeer; ringingToastShown = false
                val result = try {
                    if (asksPeer) transact(SHARE_ANSWER_TIMEOUT_MS, cancelOnTimeout = true) else transact()
                } finally { awaitingPeerDecision = false }
                confirmed = confirmedAfter(result, offered, confirmed)
                val action = outcomeAction(result, attempt, superseded = requests.hasPending)
                lastFailedCode = (result as? ReInviteOutcome.Failed)?.code
                Log.i(TAG, "Re-INVITE for ${request.target}: $result -> $action")
                when (action) {
                    OutcomeAction.CONFIRM -> applyAnswer((result as ReInviteOutcome.Answered).sdp)
                    OutcomeAction.ROLLBACK -> {
                        if (localSlots().app != null) {
                            if (result is ReInviteOutcome.Failed && result.code == 487 && requests.hasPending) {
                                // Withdrawn by a newer request of ours: drop the app quietly, the newer one shows its page.
                                activity.webRtcManager.closeAppADC()
                                activity.sipManager.appReqApp = null
                            } else rollbackAppSlot("Re-INVITE for ${request.target}: $result", rollbackMessage(result))
                        }
                        // Rejected Home offer: the far end has no Home line, so drop the local one too.
                        if (confirmed?.home == null && confirmed != null && localSlots().home != null) {
                            activity.webRtcManager.closeADC()
                            activity.sipManager.homeReqApp = null
                        }
                    }
                    OutcomeAction.RETRY -> confirmedAtGlare = confirmed
                    else -> Unit
                }
                // The far end holds other slots than this side now does (rejected switch rolled back locally,
                // or no answer in time): re-offer the local slots once.
                action to (action == OutcomeAction.ROLLBACK && needsSyncAfterRollback(request.sync, localSlots(), confirmed))
            } ?: return ChangeResult.DONE
            val (action, needsSync) = step
            when (action) {
                OutcomeAction.CONFIRM -> {
                    // The other phone accepted: show the page now (an app waits for this), or, after a glare
                    // retry, replace what an incoming Re-INVITE launched meanwhile.
                    if (showsPage && !request.sync && hotlineApp == null &&
                        (attempt > 1 || opensWithPeer(request.target))) {
                        pageToRelaunch(request.target, activity.currentAppId, HOME_APP)?.let {
                            Log.i(TAG, "${request.target} confirmed by the far end: showing it over ${activity.currentAppId}")
                            launchMiniApp(it, sendRemoteTrigger = false, initiator = true) // this side asked for it
                        }
                    }
                    return ChangeResult.DONE
                }
                OutcomeAction.SUPERSEDED -> return ChangeResult.SUPERSEDED
                OutcomeAction.ROLLBACK -> {
                    if (needsSync && !requests.hasPending) submit(AdcRequest(hotlineApp ?: HOME_APP, sync = true))
                    return ChangeResult.REJECTED
                }
                OutcomeAction.RETRY -> {
                    // 430: give the new REGISTER (with its digest round) time to land before re-offering.
                    val delayMs = if (lastFailedCode == 430) REREGISTER_WAIT_MS
                        else reInviteRetryDelayMs(isCallIdOwner = activity.sipManager.isCallIdOwner)
                    Log.i(TAG, "${lastFailedCode ?: 491} for ${request.target}, re-offering in $delayMs ms")
                    delay(delayMs)   // without the lock: the far end's Re-INVITE is handled meanwhile
                    if (requests.hasPending) return ChangeResult.SUPERSEDED
                    attempt++
                }
            }
        }
    }

    private fun applyPlan(plan: SlotPlan) {
        val web = activity.webRtcManager
        val sip = activity.sipManager
        val dc = activity.dcManager
        plan.openHome?.let { id ->
            sip.homeReqApp = id
            web.createADC(); dc.setupADC()
            if (id == HOME_APP) { web.createADC2(); dc.setupADC2() }
        }
        if (plan.closeApp) {
            sip.appReqApp = null                      // mid 4 -> port 0
            web.closeAppADC()
        }
        plan.openApp?.let { id ->
            web.closeAppADC()                         // replace in place: new PC, new credentials, new tls-id
            sip.appReqApp = id
            sip.appSlotUsed = true
            web.createAppADC()
            dc.setupAppADC()
        }
    }

    /** Sends one Re-INVITE with the current local slots and waits for its final response. Caller holds [negotiation]. */
    private suspend fun transact(timeoutMs: Long = ANSWER_TIMEOUT_MS, cancelOnTimeout: Boolean = false): ReInviteOutcome {
        val localOffer = activity.webRtcManager.createOffer()
        val result = CompletableDeferred<ReInviteOutcome>()
        outcome = result
        try {
            withContext(Dispatchers.IO) {
                activity.sipManager.sendReInviteWithSdp(localOffer) { cseq -> pending.begin(cseq) }
            }
            val answered = withTimeoutOrNull(timeoutMs) { result.await() }
            if (answered == null && cancelOnTimeout) {
                // Nobody decided in time: withdraw the request (the far end answers 487 and the call goes on).
                val cs = pending.peek()
                if (cs > 0) withContext(Dispatchers.IO) { activity.sipManager.cancelReInvite(cs) }
            }
            return answered ?: ReInviteOutcome.Timeout
        } finally {
            pending.clear()                           // a final response arriving after this is stale
            outcome = null
        }
    }

    private suspend fun applyAnswer(sdpAnswer: String?) {
        if (sdpAnswer.isNullOrEmpty()) return
        try {
            withContext(Dispatchers.IO) { activity.webRtcManager.setRemoteAnswer(sdpAnswer) }
            Log.i(TAG, "Re-INVITE answer set, ICE starting for the ADCs")
            activity.imsDcSdk.handleRemoteSdp(sdpAnswer, callId = activity.callId)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting Re-INVITE answer: ${e.message}", e)
        }
    }

    /** Hold/resume: one Re-INVITE with the current slots, serialised with the app changes, re-offered on 491. */
    fun sendHoldReInvite(onDone: (accepted: Boolean) -> Unit = {}) {
        activity.lifecycleScope.launch(Dispatchers.Main) {
            var attempt = 1
            try {
                while (true) {
                    val result = negotiation.withLock {
                        val offered = localSlots()
                        val r = transact()
                        confirmed = confirmedAfter(r, offered, confirmed)
                        if (r is ReInviteOutcome.Answered) applyAnswer(r.sdp)
                        r
                    }
                    if (result is ReInviteOutcome.Failed && result.code == 491 && attempt < MAX_GLARE_ATTEMPTS) {
                        delay(reInviteRetryDelayMs(isCallIdOwner = activity.sipManager.isCallIdOwner))
                        attempt++
                        continue
                    }
                    if (result !is ReInviteOutcome.Answered) Log.w(TAG, "Hold/resume Re-INVITE not accepted: $result")
                    onDone(result is ReInviteOutcome.Answered)
                    break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Hold/resume Re-INVITE cancelled (call ended)")
            } catch (e: Exception) {
                Log.e(TAG, "Hold/resume Re-INVITE failed: ${e.message}")
                onDone(false)
            }
        }
    }

    /**
     * Runs the handling of an incoming Re-INVITE under the negotiation lock. Returns false, without running
     * [block], while a Re-INVITE of this side is in progress: the caller answers 491 (RFC 3261 14.2).
     * [block] returns true once its 200 OK went out; the slots it left are then what the far end agreed to.
     */
    suspend fun handleIncomingReInvite(block: suspend () -> Boolean): Boolean {
        if (!negotiation.tryLock()) return false
        try {
            if (block()) confirmed = localSlots()
        } finally {
            negotiation.unlock()
        }
        return true
    }

    /** Drops queued work; call at call end because a cancelled scope can leave the queue busy. */
    fun resetRequests() {
        requests.reset()
        pending.clear()
        outcome?.cancel()                             // the waiting request ends as cancelled, no rollback
        confirmed = SlotState(null, null)
    }

    /** Roll back the App slot only. Home ADC and BDC are untouched. */
    private fun rollbackMessage(result: ReInviteOutcome): String = when {
        result is ReInviteOutcome.Failed && result.code == 603 -> "${activity.peerName} đã từ chối mở ứng dụng"
        result is ReInviteOutcome.Failed && result.code == 487 -> "${activity.peerName} không trả lời"
        result == ReInviteOutcome.Timeout -> "${activity.peerName} không trả lời"
        else -> "Không mở được ứng dụng, quay về emote"
    }

    private fun rollbackAppSlot(reason: String, message: String = "Không mở được ứng dụng, quay về emote") {
        Log.w(TAG, "Rolling back App ADC: $reason")
        activity.webRtcManager.closeAppADC()
        activity.sipManager.appReqApp = null          // mid 4 -> port 0 locally; appSlotUsed stays true
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
        launchMiniApp(HOME_APP, sendRemoteTrigger = false)
    }

    // ───────────────────────── Content Sharing ─────────────────────────
    // A share request is one Re-INVITE (photo: adds the content-share stream 1002 to the App line).
    // 200 OK = accepted, 603 = declined, CANCEL = withdrawn; closing is another Re-INVITE without the stream.

    private enum class ShareState { NONE, INVITING, ACTIVE }
    @Volatile private var shareState = ShareState.NONE
    @Volatile private var shareCancelRequested = false
    private var heldDecision: CompletableDeferred<Boolean>? = null
    @Volatile private var heldCancelled = false

    /** Calls window.shareEvent(name, json) of the Content Sharing page. */
    fun emitShareEvent(name: String, json: String = "{}") {
        Log.i(TAG, "share event -> page: $name $json")
        activity.runOnUiThread {
            val arg = org.json.JSONObject.quote(json)
            activity.binding.webviewMiniapp.evaluateJavascript("window.shareEvent && window.shareEvent('$name', $arg)", null)
        }
    }

    private fun openShareSession() {
        val web = activity.webRtcManager
        web.openShareChannel()?.let { activity.dcManager.setupShareChannel(it) }
        shareState = ShareState.ACTIVE
    }

    private fun clearShareSession() {
        activity.sipManager.shareStream = false
        // Once used, the shared-content lines stay in every SDP as port 0 so the m-line order never changes.
        if (activity.sipManager.slides.mode != SlidesState.Mode.OFF) activity.sipManager.slides = SlidesState(SlidesState.Mode.CLOSED)
        // Stream 1002 stays open until the App line closes (closeAppADC). Closing it resets the SCTP stream,
        // the MF then closes its own 1002 for this phone for good, and every later share drops the photo bytes.
        releaseVideo()
        shareState = ShareState.NONE
    }

    /** Sends the closing Re-INVITE for a share we opened but cannot use (the other phone did not take the lines). */
    private suspend fun closeShareOnPeer() {
        negotiation.withLock {
            val r = transact()
            if (r is ReInviteOutcome.Answered) applyAnswer(r.sdp)
        }
    }

    /** Sender: the user picked Ảnh/Video. Sends the Re-INVITE and tells the page how it ended. */
    fun requestShare(kind: String) {
        if (kind != "photo" && kind != "video" && kind != "screen") { emitShareEvent("failed", """{"code":501}"""); return }
        if (shareState != ShareState.NONE) { emitShareEvent("failed", """{"code":486}"""); return }
        val video = kind != "photo" // a screen goes over the same shared-content video line as a video file
        shareState = ShareState.INVITING
        shareCancelRequested = false
        activity.lifecycleScope.launch(Dispatchers.Main) {
            var attempt = 1
            try {
                while (true) {
                    val result = negotiation.withLock {
                        if (shareCancelRequested) return@withLock ReInviteOutcome.Failed(487)
                        if (activity.webRtcManager.appAdcPC == null) return@withLock ReInviteOutcome.Failed(488)
                        val sip = activity.sipManager
                        val previousSlides = sip.slides
                        if (video) {
                            val (a, v) = RtpPorts.allocateSlides()
                            sip.slides = SlidesState(SlidesState.Mode.SEND, a, v)
                        } else {
                            sip.shareStream = true
                        }
                        var r = transact(SHARE_ANSWER_TIMEOUT_MS, cancelOnTimeout = true)
                        if (r is ReInviteOutcome.Answered) {
                            applyAnswer(r.sdp)
                            if (video) {
                                // The answer's shared-content lines say where the MF wants our RTP; none = not accepted.
                                if (!openVideoSession(sender = true, rawSdp = lastAnswerRawSdp)) {
                                    sip.slides = SlidesState(SlidesState.Mode.CLOSED)
                                    r = ReInviteOutcome.Failed(488)
                                    activity.lifecycleScope.launch(Dispatchers.Main) { closeShareOnPeer() }
                                }
                            } else if (!SdpHelper.hasContentShare(lastAnswerRawSdp)) {
                                // 200 OK without the share stream: the other phone cannot do Content Sharing.
                                Log.w(TAG, "Share answer has no a=dcmap:1002; peer does not support photo sharing")
                                sip.shareStream = false
                                r = ReInviteOutcome.Failed(505)
                                activity.lifecycleScope.launch(Dispatchers.Main) { closeShareOnPeer() }
                            } else {
                                openShareSession()
                            }
                        } else {
                            sip.shareStream = false
                            sip.slides = previousSlides
                        }
                        r
                    }
                    when {
                        result is ReInviteOutcome.Answered -> emitShareEvent("accepted")
                        result is ReInviteOutcome.Failed && result.code == 603 -> { shareState = ShareState.NONE; emitShareEvent("declined") }
                        result is ReInviteOutcome.Failed && result.code == 487 && shareCancelRequested -> shareState = ShareState.NONE
                        result is ReInviteOutcome.Failed && result.code == 491 && attempt < MAX_GLARE_ATTEMPTS && !shareCancelRequested -> {
                            delay(reInviteRetryDelayMs(isCallIdOwner = activity.sipManager.isCallIdOwner))
                            attempt++
                            continue
                        }
                        else -> {
                            shareState = ShareState.NONE
                            val code = (result as? ReInviteOutcome.Failed)?.code ?: 408
                            Log.w(TAG, "Share request not accepted: $result")
                            emitShareEvent("failed", """{"code":$code}""")
                        }
                    }
                    break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Share request cancelled (call ended)")
                clearShareSession()
            } catch (e: Exception) {
                Log.e(TAG, "Share request failed: ${e.message}", e)
                clearShareSession()
                emitShareEvent("failed", """{"code":500}""")
            }
        }
    }

    /** Sender: Hủy while waiting. CANCEL goes out; the far end answers 487 and the request ends quietly. */
    fun cancelShare() {
        if (shareState == ShareState.ACTIVE) { stopShare(); return } // accepted, nothing sent yet: leave the session
        if (shareState != ShareState.INVITING) return
        shareCancelRequested = true
        val cs = pending.peek()
        if (cs > 0) activity.lifecycleScope.launch(Dispatchers.IO) { activity.sipManager.cancelReInvite(cs) }
    }

    /** Either side: leave the session. A Re-INVITE without the content-share stream closes it for both. */
    fun stopShare() {
        if (shareState == ShareState.NONE) return
        if (shareState == ShareState.INVITING) { cancelShare(); return }
        activity.lifecycleScope.launch(Dispatchers.Main) {
            try {
                negotiation.withLock {
                    clearShareSession()
                    val r = transact()
                    if (r is ReInviteOutcome.Answered) applyAnswer(r.sdp) else Log.w(TAG, "Closing share Re-INVITE not accepted: $r")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Share close cancelled (call ended)")
            } catch (e: Exception) {
                Log.e(TAG, "Share close failed: ${e.message}", e)
            }
        }
    }

    /** Page, receiver: Chấp nhận / Từ chối. Completes the decision the held Re-INVITE is waiting for. */
    fun answerShare(accept: Boolean) { heldDecision?.complete(accept) }

    /**
     * Receiver, called with the negotiation lock held for an incoming Re-INVITE that asks to open a share:
     * rings (180), asks the page and waits for the user. Returns true to accept; on a decline, a timeout or a
     * CANCEL it has already sent the final response (603 / 487) and returns false.
     */
    suspend fun awaitShareDecision(rawMsg: String, kind: String): Boolean = awaitHeldDecision(
        rawMsg,
        notice = if (kind == "photo") "muốn chia sẻ ảnh" else "muốn chia sẻ video",
        ask = { emitShareEvent("invite", org.json.JSONObject().put("kind", kind).put("peer", activity.peerName).toString()) },
        dismiss = { emitShareEvent("cancelled") }
    )

    /**
     * The other phone opens an app (not the emote app): ask our user before 200 OK. A native dialog, since the
     * app's page is not loaded yet. Same 180 / 200 / 603 / CANCEL flow as a share request.
     */
    suspend fun awaitAppDecision(rawMsg: String, appId: String): Boolean {
        var dialog: android.app.Dialog? = null
        return try {
            awaitHeldDecision(
                rawMsg,
                notice = "muốn mở " + listOfNotNull(appEmoji(appId), appLabel(appId)).joinToString(" "),
                ask = { activity.runOnUiThread { dialog = showAppRequestSheet(appId) } },
                dismiss = { activity.runOnUiThread { dialog?.dismiss() } }
            )
        } finally {
            activity.runOnUiThread { dialog?.dismiss() }
        }
    }

    /** Dark card in the middle of the screen, in the call screen's style; the buttons answer [answerShare]. Caller is on the UI thread. */
    private fun showAppRequestSheet(appId: String): android.app.Dialog {
        val sheet = android.app.Dialog(activity)
        sheet.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        sheet.setContentView(R.layout.sheet_app_request)
        sheet.setCancelable(false)
        sheet.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            // Centred card: the screen width less a 24dp margin each side, at most 400dp on large screens.
            val screenW = activity.resources.displayMetrics.widthPixels
            setLayout(minOf(screenW - dp(48), dp(400)), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        val name = activity.peerName
        sheet.findViewById<android.widget.TextView>(R.id.tv_req_avatar).text =
            name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        sheet.findViewById<android.widget.TextView>(R.id.tv_req_name).text = name
        sheet.findViewById<android.widget.TextView>(R.id.tv_req_app).text =
            "muốn mở " + listOfNotNull(appEmoji(appId), appLabel(appId)).joinToString(" ")
        val note = sheet.findViewById<android.widget.TextView>(R.id.tv_req_note)
        var left = (SHARE_DECISION_TIMEOUT_MS / 1000).toInt()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                note.text = "Đang trong cuộc gọi · ${left}s"
                if (left-- > 0) handler.postDelayed(this, 1000)
            }
        }
        tick.run()
        sheet.setOnDismissListener { handler.removeCallbacks(tick) }
        sheet.findViewById<View>(R.id.btn_req_decline).setOnClickListener { answerShare(false); sheet.dismiss() }
        sheet.findViewById<View>(R.id.btn_req_accept).setOnClickListener { answerShare(true); sheet.dismiss() }
        sheet.show()
        return sheet
    }

    /** Same emoji as the operator menu tray. */
    private fun appEmoji(appId: String): String? = when (appId) {
        "sketch-sync-app" -> "🎨"
        SHARE_APP -> "📲"
        "caro" -> "♟️"
        else -> null
    }

    private fun appLabel(appId: String): String = when (appId) {
        "sketch-sync-app" -> "Vẽ"
        SHARE_APP -> "Chia sẻ"
        "caro" -> "Caro"
        "whiteboard" -> "Bảng trắng"
        else -> appId
    }

    private var heldDismiss: (() -> Unit)? = null

    /** 180, then waits for our user: true = accepted (caller answers 200 OK); false = 603 / 487 already sent. */
    private suspend fun awaitHeldDecision(rawMsg: String, notice: String, ask: () -> Unit, dismiss: () -> Unit): Boolean {
        val decision = CompletableDeferred<Boolean>()
        heldCancelled = false
        heldDecision = decision
        heldDismiss = dismiss
        heldNotice = notice
        try {
            activity.sipManager.sendReInviteRinging(rawMsg)
            ask()
            // The sheet is on the call screen: if the user is elsewhere, a notification asks instead.
            activity.runOnUiThread {
                if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) onCallScreenHidden()
            }
            val accepted = withTimeoutOrNull(SHARE_DECISION_TIMEOUT_MS) { decision.await() }
            if (accepted == true) { activity.sipManager.clearHeldReInvite(); return true }
            if (heldCancelled) { heldCancelled = false; return false } // CANCEL: SipManager already answered 200 + 487
            activity.sipManager.sendReInviteReject(rawMsg, 603, "Decline")
            if (accepted == null) dismiss() // no answer: close the sheet / dialog
            return false
        } finally {
            heldDecision = null
            heldDismiss = null
            heldNotice = null
            com.demo.imsdc.notification.CallRequestNotification.cancel(activity)
        }
    }

    /** Text of the request being decided ("muốn mở 🎨 Vẽ"), null when none is. */
    @Volatile private var heldNotice: String? = null

    /** Call screen hidden (Home, another app): a pending request asks through a notification. */
    fun onCallScreenHidden() {
        val notice = heldNotice ?: return
        com.demo.imsdc.notification.CallRequestNotification.show(activity, activity.peerName, notice, SHARE_DECISION_TIMEOUT_MS)
    }

    /** Call screen back: its sheet asks again, so the notification goes. */
    fun onCallScreenShown() {
        if (heldNotice != null) com.demo.imsdc.notification.CallRequestNotification.cancel(activity)
    }

    /** SipManager saw CANCEL for the held Re-INVITE (487 already sent). Closes the sheet; the call goes on. */
    fun onHeldReInviteCancelled() {
        heldCancelled = true
        val dismiss = heldDismiss
        heldDecision?.complete(false)
        (dismiss ?: { emitShareEvent("cancelled") })()
    }

    /**
     * The user left the Content Sharing page for another page (menu, another app) without Thoát. The App line
     * change that follows carries no content-share stream, which closes the share on the other phone; here
     * only the local state is dropped. Left set, every later App Re-INVITE (e.g. reopening Content Sharing)
     * would still carry a=dcmap:1002 and the other phone would take it for a new share request.
     */
    private fun leaveShareForAppSwitch() {
        when (shareState) {
            ShareState.INVITING -> cancelShare()
            ShareState.ACTIVE -> { Log.i(TAG, "Leaving Content Sharing page: dropping the open share"); clearShareSession() }
            ShareState.NONE -> if (activity.sipManager.shareStream) clearShareSession()
        }
    }

    /** Receiver: the Re-INVITE was answered 200 OK with the stream; the session is open. */
    fun onShareAccepted() = openShareSession()

    /** Receiver: the far end closed the share (its Re-INVITE had no stream). */
    fun onShareClosedByPeer() {
        if (shareState == ShareState.NONE) return
        clearShareSession()
        emitShareEvent("closed")
    }

    // ───────────────────────── Content Sharing: video ─────────────────────────

    @Volatile var lastAnswerRawSdp: String? = null
    private var sharedEndpoint: SdpHelper.SlidesEndpoint? = null
    private var streamer: ContentStreamer? = null
    private var screen: com.demo.imsdc.media.ScreenStreamer? = null
    private var receiver: ContentReceiver? = null
    private var preview: android.media.MediaPlayer? = null
    /** The page's mute button: silences the shared video's sound on this phone (receiver's stream or sender's preview). */
    @Volatile private var shareMuted = false
    private var contentSurface: android.view.SurfaceView? = null
    private var remoteHiddenForContent = false
    private var videoTicker: kotlinx.coroutines.Job? = null

    /** The MF endpoint for the shared-content RTP of this phone's leg, from the SDP the far end answered/offered. */
    private fun openVideoSession(sender: Boolean, rawSdp: String?): Boolean {
        val ep = SdpHelper.slidesEndpoint(rawSdp) ?: return false
        sharedEndpoint = ep
        shareState = ShareState.ACTIVE
        if (!sender) startReceiver(ep)
        return true
    }

    /** Receiver: the Re-INVITE for a video share was answered 200 OK; start taking the stream. */
    fun onVideoShareAccepted(rawOfferSdp: String?) {
        if (!openVideoSession(sender = false, rawSdp = rawOfferSdp)) Log.w(TAG, "Video share accepted but its offer has no shared-content lines")
    }

    /** Runs [use] with the surface the shared video is drawn on: a SurfaceView between the call video and the page. */
    private fun withContentSurface(use: (android.view.Surface) -> Unit) {
        activity.runOnUiThread {
            val sv = contentSurface ?: android.view.SurfaceView(activity).also { view ->
                // Plain surface layer (not media overlay): my camera tile is the media overlay and two
                // overlays draw in no fixed order, so the camera mixed into the shared video. The call's
                // remote video is the other plain surface here; hide it while the content is shown.
                val remote = activity.binding.remoteVideoView
                remoteHiddenForContent = remote.visibility == View.VISIBLE
                if (remoteHiddenForContent) remote.visibility = View.GONE
                val lp = android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                (activity.binding.root as android.view.ViewGroup).addView(view, 1, lp)
                contentSurface = view
                applyRemoteFold() // keep the other person's tile above the new surface
            }
            val holder = sv.holder
            if (holder.surface.isValid) use(holder.surface)
            else holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) { h.removeCallback(this); use(h.surface) }
                override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
        }
    }

    /** Sizes the shared-video surface to the picture's shape, centred, instead of stretching it full screen. */
    private fun fitContentSurface(w: Int, h: Int) {
        val sv = contentSurface ?: return
        val parent = sv.parent as? android.view.View ?: return
        val pw = parent.width; val ph = parent.height
        if (w <= 0 || h <= 0 || pw <= 0 || ph <= 0) return
        val scale = minOf(pw.toFloat() / w, ph.toFloat() / h)
        val lp = android.widget.FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), android.view.Gravity.CENTER)
        sv.layoutParams = lp
        Log.i(TAG, "Shared video ${w}x$h shown at ${lp.width}x${lp.height}")
    }

    private fun startReceiver(ep: SdpHelper.SlidesEndpoint) {
        withContentSurface { surface ->
            receiver?.stop()
            receiver = ContentReceiver(RtpPorts.slidesAudio, RtpPorts.slidesVideo, ep.ip, ep.audioPort, ep.videoPort, surface,
                onVideoSize = { w, h -> activity.runOnUiThread { fitContentSurface(w, h) } }).also { it.setMuted(shareMuted); it.start() }
        }
    }

    /** Sender: opens the gallery to choose the video once the other person accepted. */
    fun pickVideo() {
        activity.launchVideoPicker { uri ->
            if (uri == null) emitShareEvent("videoEnded") else startStreaming(uri)
        }
    }

    private fun startStreaming(uri: android.net.Uri) {
        val ep = sharedEndpoint ?: return
        activity.lifecycleScope.launch(Dispatchers.Main) {
            val s = ContentStreamer(activity, uri, RtpPorts.slidesAudio, RtpPorts.slidesVideo, ep.ip, ep.audioPort, ep.videoPort) {
                activity.runOnUiThread { emitShareEvent("videoEnded") }
            }
            val info = withContext(Dispatchers.IO) { s.inspect() }
            if (info.error != null) {
                emitShareEvent("videoError", JSONObject().put("msg", info.error).toString())
                return@launch
            }
            streamer = s
            s.start()
            // The sender watches and hears the same video locally. On speakerphone the mic can pick it up
            // and the other person may hear an echo through the call; the page's mute button silences it.
            withContentSurface { surface ->
                try {
                    preview = android.media.MediaPlayer().apply {
                        setDataSource(activity, uri)
                        setSurface(surface)
                        setOnVideoSizeChangedListener { _, w, h -> fitContentSurface(w, h) }
                        val v = if (shareMuted) 0f else com.demo.imsdc.media.SHARED_VIDEO_VOLUME
                        setVolume(v, v)
                        setOnPreparedListener { it.start() }
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Local preview unavailable: ${e.message}")
                }
            }
            emitShareEvent("videoReady", JSONObject().put("name", info.name).put("dur", info.durationMs / 1000.0).toString())
            videoTicker?.cancel()
            videoTicker = activity.lifecycleScope.launch {
                while (true) {
                    delay(500)
                    val pos = streamer?.positionMs() ?: break
                    emitShareEvent("videoTime", JSONObject().put("t", pos / 1000.0).toString())
                }
            }
        }
    }

    /**
     * Sender: share this phone's screen once the other person accepted. Asks the system for consent, switches
     * the call service to media projection (Android 14 needs that first), then streams the screen.
     */
    fun startScreen() {
        activity.launchScreenCapture { data ->
            if (data == null) { emitShareEvent("videoEnded"); return@launchScreenCapture }
            activity.lifecycleScope.launch(Dispatchers.Main) {
                val ep = sharedEndpoint
                if (ep == null) { emitShareEvent("videoEnded"); return@launch }
                if (!com.demo.imsdc.call.CallService.startSharing(activity)) {
                    emitShareEvent("videoError", JSONObject().put("msg", "Không thể chia sẻ màn hình trên máy này").toString())
                    return@launch
                }
                try {
                    val pm = activity.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                    val projection = pm.getMediaProjection(android.app.Activity.RESULT_OK, data)
                        ?: throw IllegalStateException("no MediaProjection")
                    val s = com.demo.imsdc.media.ScreenStreamer(projection, screenMetrics(), RtpPorts.slidesVideo, ep.ip, ep.videoPort) {
                        activity.runOnUiThread { emitShareEvent("videoEnded") }
                    }
                    screen = s
                    s.start()
                    emitShareEvent("videoReady", JSONObject().put("name", "Màn hình").put("dur", 0).toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Screen share failed: ${e.message}", e)
                    screen?.stop(); screen = null
                    com.demo.imsdc.call.CallService.stopSharing(activity)
                    emitShareEvent("videoError", JSONObject().put("msg", "Không thể chia sẻ màn hình").toString())
                }
            }
        }
    }

    /** The real screen size (not just this window), so the whole screen is captured. */
    private fun screenMetrics(): android.util.DisplayMetrics {
        val m = android.util.DisplayMetrics().apply { setTo(activity.resources.displayMetrics) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = activity.windowManager.maximumWindowMetrics.bounds
            m.widthPixels = b.width(); m.heightPixels = b.height()
        } else {
            @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.getRealMetrics(m)
        }
        return m
    }

    /** "Dừng chia sẻ" on the ongoing-call notification: leave the share, and tell the page. */
    fun stopScreenFromNotification() {
        if (shareState == ShareState.NONE) return
        stopShare()
        emitShareEvent("stopped")
    }

    /** Sender: pause or resume the stream and the local preview together. */
    fun pauseVideo(paused: Boolean) {
        streamer?.let { if (paused) it.pause() else it.resume() }
        try { preview?.let { if (paused) it.pause() else it.start() } } catch (e: Exception) { Log.w(TAG, "Preview pause: ${e.message}") }
    }

    /** Mute the shared video's sound on this phone only: the receiver's stream, or the sender's local preview. */
    fun muteShareAudio(muted: Boolean) {
        shareMuted = muted
        receiver?.setMuted(muted)
        val v = if (muted) 0f else com.demo.imsdc.media.SHARED_VIDEO_VOLUME
        try { preview?.setVolume(v, v) } catch (e: Exception) { Log.w(TAG, "Preview volume: ${e.message}") }
    }

    private fun releaseVideo() {
        videoTicker?.cancel(); videoTicker = null
        streamer?.stop(); streamer = null
        screen?.let { it.stop(); com.demo.imsdc.call.CallService.stopSharing(activity) }
        screen = null
        receiver?.stop(); receiver = null
        try { preview?.release() } catch (_: Exception) {}
        preview = null
        sharedEndpoint = null
        activity.runOnUiThread {
            contentSurface?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
            contentSurface = null
            if (remoteHiddenForContent) activity.binding.remoteVideoView.visibility = View.VISIBLE
            remoteHiddenForContent = false
            applyRemoteFold()
        }
    }

    /** Call ended: drop any share state. */
    fun resetShare() {
        heldCancelled = true
        heldDecision?.complete(false)
        clearShareSession()
    }
}
