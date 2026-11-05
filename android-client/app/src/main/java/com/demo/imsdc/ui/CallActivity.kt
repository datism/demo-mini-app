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
import com.demo.imsdc.notification.IncomingCallNotificationManager
import com.demo.imsdc.sip.SipManager
import com.demo.imsdc.webrtc.AndroidBridgeInterface
import com.demo.imsdc.webrtc.DataChannelManager
import com.demo.imsdc.webrtc.WebRtcManager
import com.demo.imsdc.media.RtpMediaManager
import android.app.KeyguardManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.view.SurfaceHolder
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import org.webrtc.PeerConnection
import java.net.InetAddress

private const val TAG = "CallActivity"

/**
 * CallActivity � M�n h�nh g?i di?n
 *
 * Layout: SurfaceView (video background) + WebView transparent (Mini-app overlay)
 *
 * Lu?ng kh?i t?o khi Activity b?t d?u:
 *   1. L?y th�ng tin t? Intent (sipServer, localUser, remoteUser, mode)
 *   2. Kh?i t?o WebRtcManager ? t?o PeerConnection ? t?o placeholder DC
 *   3. createOffer() ? l?y SDP
 *   4. SipManager.invite() ? g?i SIP INVITE v?i SDP
 *   5. Nh?n 200 OK ? setRemoteAnswer() v?i SDP d� anchor v�o MF
 *   6. ICE connected ? openDataChannels()
 *   7. BDC: g?i HTTP GET ? nh?n HTML ? loadWebView()
 *   8. ADC: ready for heart events
 *   9. WebView: inject AndroidBridge, set transparent background
 */
class CallActivity : AppCompatActivity() {

    companion object {
        /** 1900 agent attempt: how long it rings, and how long "không trả lời" stays before the page returns. */
        private const val AGENT_RING_MS = 20_000L
        private const val AGENT_NO_ANSWER_MS = 2_500L
        @Volatile
        var activeInstance: CallActivity? = null
        val isCallActive: Boolean
            get() = activeInstance?.let { !it.isFinishing && !it.isDestroyed && !it.isEndingCall } ?: false

        // Hotline numbers answered by the network (P2A): voice-only, the mapped mini-app auto-launches.
        // Keep in sync with go-mf/internal/config/hotline.go.
        private val HOTLINE_APPS = mapOf("198" to "smart-menu", "1900" to "flight-booking")
        private val APP_RESET_JS = mapOf("smart-menu" to "resetSmartMenu", "flight-booking" to "resetFlightBooking")

        fun hotlineApp(user: String): String? = HOTLINE_APPS[user.removePrefix("+84")]

        // In-call home screen: the emote app (it needs its own ADC), with the app tray at the bottom.
        // "‹ Menu" and Back return here instead of to operator-menu.
        const val HOME_APP = "fun-calling-app"

        const val EXTRA_SIP_SERVER  = "sip_server"
        const val EXTRA_LOCAL_USER  = "local_user"
        const val EXTRA_REMOTE_USER = "remote_user"
        const val EXTRA_LOCAL_IP    = "local_ip"
        const val EXTRA_LOCAL_PORT  = "local_port"
        const val EXTRA_SIP_PORT    = "sip_port"
        const val EXTRA_INCOMING_INVITE  = "incoming_invite"
        const val EXTRA_INCOMING_SDP     = "incoming_sdp"
        const val EXTRA_INCOMING_CALL_ID = "incoming_call_id"
        const val EXTRA_INCOMING_RAW_SDP = "incoming_raw_sdp"
        const val EXTRA_AUTO_ANSWER      = "auto_answer"
        /** Tap on the ongoing-call notification: just bring the call back, change nothing. */
        const val EXTRA_FROM_ONGOING     = "from_ongoing"
        /** From a request notification: true = "Đồng ý" (accept what the other phone asked). */
        const val EXTRA_REQUEST_ANSWER   = "request_answer"

        fun launch(
            activity: androidx.appcompat.app.AppCompatActivity,
            sipServer: String,
            localUser: String,
            remoteUser: String,
            localIp: String,
            localPort: Int = 15060,
            sipPort: Int = 5060
        ) {
            val intent = Intent(activity, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SIP_SERVER,  sipServer)
                putExtra(EXTRA_LOCAL_USER,  localUser)
                putExtra(EXTRA_REMOTE_USER, remoteUser)
                putExtra(EXTRA_LOCAL_IP,    localIp)
                putExtra(EXTRA_LOCAL_PORT,  localPort)
                putExtra(EXTRA_SIP_PORT,    sipPort)
            }
            activity.startActivity(intent)
        }

        fun launchIncoming(
            context: Context,
            sipServer: String,
            localUser: String,
            remoteUser: String,
            localIp: String,
            localPort: Int,
            sipPort: Int,
            incomingInvite: String,
            incomingSdp: String,
            incomingCallId: String,
            incomingRawSdp: String?
        ) {
            IncomingCallNotificationManager.show(
                context = context,
                sipServer = sipServer,
                localUser = localUser,
                remoteUser = remoteUser,
                localIp = localIp,
                localPort = localPort,
                sipPort = sipPort,
                incomingInvite = incomingInvite,
                incomingSdp = incomingSdp,
                incomingCallId = incomingCallId,
                incomingRawSdp = incomingRawSdp
            )
        }
    }

    // --- ViewBinding ---------------------------------------------------------
    lateinit var binding: ActivityCallBinding
    lateinit var miniAppManager: MiniAppManager

    // --- Dependencies --------------------------------------------------------
    lateinit var sipManager: SipManager
    lateinit var webRtcManager: WebRtcManager
    lateinit var dcManager: DataChannelManager
    private lateinit var androidBridge: AndroidBridgeInterface
    lateinit var imsDcSdk: com.demo.imsdc.webrtc.ImsDcSdk

    // --- Intent extras -------------------------------------------------------
    private lateinit var sipServer:  String
    private lateinit var localUser:  String
    lateinit var remoteUser: String
    /** What is shown for the other person: the name saved in the app's phonebook, else the number. */
    val peerName: String get() = CertifiedNumbers.of(remoteUser)?.name ?: Phonebook.label(this, remoteUser)
    private lateinit var localIp:    String
    private var localPort = 15060
    private var sipPort   = 5060

    // --- State ---------------------------------------------------------------
    var callId: String? = null
    var isMiniAppInitiator = false

    // ─── Photo picker for <input type="file"> inside mini-apps (photo-share) ───
    // WebView shows nothing for a file input unless WebChromeClient.onShowFileChooser opens a chooser.
    private var fileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private val mediaPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        fileChooserCallback?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        fileChooserCallback = null
    }

    /** Opens Android's visual media picker (gallery) for images and videos. */
    fun launchPhotoPicker(callback: android.webkit.ValueCallback<Array<android.net.Uri>>): Boolean {
        fileChooserCallback?.onReceiveValue(null) // a previous chooser must always be completed
        fileChooserCallback = callback
        return try {
            val request = androidx.activity.result.PickVisualMediaRequest.Builder()
                .setMediaType(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                .build()
            mediaPicker.launch(request)
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Cannot open visual media picker: ${e.message}", e)
            fileChooserCallback = null
            callback.onReceiveValue(null)
            false
        }
    }
    /** Content Sharing: the system's "start recording/casting" consent; [onResult] gets null when refused. */
    private var screenCaptureCallback: ((android.content.Intent?) -> Unit)? = null
    private val screenCapture = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        screenCaptureCallback?.invoke(if (r.resultCode == RESULT_OK) r.data else null)
        screenCaptureCallback = null
    }
    fun launchScreenCapture(onResult: (android.content.Intent?) -> Unit) {
        screenCaptureCallback = onResult
        try {
            val pm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            screenCapture.launch(pm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Cannot ask for screen capture: ${e.message}", e)
            screenCaptureCallback = null
            onResult(null)
        }
    }

    private var videoPickerCallback: ((android.net.Uri?) -> Unit)? = null
    private val videoPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        videoPickerCallback?.invoke(uri)
        videoPickerCallback = null
    }

    /** Content Sharing: opens the gallery for one video; [onPicked] gets null when the user backs out. */
    fun launchVideoPicker(onPicked: (android.net.Uri?) -> Unit) {
        videoPickerCallback = onPicked
        try {
            videoPicker.launch(
                androidx.activity.result.PickVisualMediaRequest.Builder()
                    .setMediaType(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly)
                    .build()
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Cannot open the video picker: ${e.message}", e)
            videoPickerCallback = null
            onPicked(null)
        }
    }
    private var pendingInviteMsg: String? = null  // B-side: luu INVITE g?c d? g?i 200 OK
    private var eglBase: org.webrtc.EglBase? = null
    private var rtpMediaManager: RtpMediaManager? = null
    private var timerJob: kotlinx.coroutines.Job? = null
    private var elapsedSeconds = 0
    private var isDataChannelsOpened = false
    private var isRendererInitialized = false
    private var isSpeakerMode = false
    var currentAppId: String = "operator-menu"
    private var isCallActiveUiShown = false
    private var isVideoEnabled = false
    internal var isEndingCall = false
    private var ringtone: Ringtone? = null

    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD") {
                Log.i(TAG, "Debug broadcast received! Launching whiteboard app...")
                miniAppManager.launchMiniApp("sketch-sync-app")
            } else if (intent?.action == "com.demo.imsdc.ACTION_LAUNCH_CARO") {
                Log.i(TAG, "Debug broadcast received! Launching caro app...")
                miniAppManager.launchMiniApp("caro")
            } else if (intent?.action == "com.demo.imsdc.ACTION_LAUNCH_FUNCALL") {
                Log.i(TAG, "Debug broadcast received! Launching fun-calling app...")
                miniAppManager.launchMiniApp("fun-calling-app")
            } else if (intent?.action == "com.demo.imsdc.ACTION_LAUNCH_SMARTMENU") {
                Log.i(TAG, "Debug broadcast received! Launching smart-menu app...")
                miniAppManager.launchMiniApp("smart-menu")
            } else if (intent?.action == "com.demo.imsdc.ACTION_LAUNCH_ENTERPRISE" || (intent?.action == "com.demo.imsdc.ACTION_LAUNCH_APP" && intent.getStringExtra("appId") == "enterprise-callerid")) {
                Log.i(TAG, "Debug broadcast received! Launching enterprise-callerid app...")
                miniAppManager.launchMiniApp("enterprise-callerid")
            } else if (intent?.action == "com.demo.imsdc.ACTION_LAUNCH_APP") {
                val appId = intent.getStringExtra("appId") ?: "operator-menu"
                Log.i(TAG, "Debug broadcast received! Launching generic app: $appId...")
                miniAppManager.launchMiniApp(appId)
            } else if (intent?.action == "com.demo.imsdc.ACTION_TEST_DRAW") {
                Log.i(TAG, "Debug broadcast received! Testing real-time stroke sync over ADC...")
                val isCross = intent.getBooleanExtra("cross", false)
                val rawColor = intent.getStringExtra("color") ?: if (isCross) "#00E676" else "#FF0000"
                val color = when (rawColor.lowercase()) {
                    "red" -> "#FF0000"
                    "green" -> "#00E676"
                    "blue" -> "#2196F3"
                    "yellow" -> "#FFEB3B"
                    else -> if (rawColor.startsWith("#")) rawColor else "#$rawColor"
                }
                val points = if (isCross) {
                    listOf(Pair(750, 400), Pair(650, 500), Pair(550, 600), Pair(450, 700), Pair(350, 800), Pair(250, 900))
                } else {
                    listOf(Pair(250, 400), Pair(350, 500), Pair(450, 600), Pair(550, 700), Pair(650, 800), Pair(750, 900))
                }
                for (i in 0 until points.size - 1) {
                    val p1 = points[i]
                    val p2 = points[i + 1]
                    val segJson = JSONObject().apply {
                        put("type", "draw")
                        put("fromX", p1.first)
                        put("fromY", p1.second)
                        put("toX", p2.first)
                        put("toY", p2.second)
                        put("color", color)
                        put("size", 14)
                        put("brushType", "pen")
                    }.toString()
                    imsDcSdk.sendOrQueueAdc(segJson.toByteArray(Charsets.UTF_8))
                }
                // Render on local Canvas immediately
                binding.webviewMiniapp.post {
                    val js = """
                        (function() {
                            const c = document.querySelector('canvas.whiteboard') || document.querySelector('canvas');
                            if (!c) return;
                            const ctx = c.getContext('2d');
                            if (!ctx) return;
                            const w = c.width || c.clientWidth || 300;
                            const h = c.height || c.clientHeight || 150;
                            ctx.strokeStyle = '$color';
                            ctx.lineWidth = 12;
                            ctx.lineCap = 'round';
                            ctx.beginPath();
                            ${if (isCross) "ctx.moveTo(w * 0.8, h * 0.25); ctx.lineTo(w * 0.2, h * 0.75);" else "ctx.moveTo(w * 0.2, h * 0.25); ctx.lineTo(w * 0.8, h * 0.75);"}
                            ctx.stroke();
                        })();
                    """.trimIndent()
                    binding.webviewMiniapp.evaluateJavascript(js, null)
                }
            } else if (intent?.action == "com.demo.imsdc.ACTION_EVAL_JS") {
                val js = intent.getStringExtra("js") ?: ""
                Log.i(TAG, "Evaluating JS: $js")
                binding.webviewMiniapp.post {
                    binding.webviewMiniapp.evaluateJavascript(js) { res ->
                        Log.i(TAG, "JS eval result: $res")
                    }
                }
            } else if (intent?.action == "com.demo.imsdc.ACTION_TEST_CARO_MOVE") {
                val row = intent.getIntExtra("row", 4)
                val col = intent.getIntExtra("col", 4)
                val symbol = intent.getStringExtra("symbol") ?: "X"
                Log.i(TAG, "Debug broadcast: Caro move at ($row, $col) symbol: $symbol")
                val moveJson = JSONObject().apply {
                    put("service", "caro")
                    put("action", "move")
                    put("row", row)
                    put("col", col)
                    put("symbol", symbol)
                }.toString()
                imsDcSdk.sendOrQueueAdc(moveJson.toByteArray(Charsets.UTF_8))
                binding.webviewMiniapp.post {
                    val js = """
                        (function() {
                            if (typeof initialized !== 'undefined') {
                                initialized = true;
                                gameActive = true;
                            }
                            const cell = document.querySelector('.cell[data-row="$row"][data-col="$col"]');
                            if (cell) {
                                document.querySelectorAll('.cell.last-move').forEach(c => c.classList.remove('last-move'));
                                cell.textContent = '$symbol';
                                cell.className = 'cell ${symbol.lowercase()} last-move';
                            }
                            const turnEl = document.getElementById('turn-indicator');
                            if (turnEl) {
                                turnEl.textContent = '${if (symbol == "X") "⏳ Đợi đối thủ (O)..." else "👉 Lượt của bạn (X)"}';
                                turnEl.style.color = '${if (symbol == "X") "#fbbf24" else "#22c55e"}';
                            }
                        })();
                    """.trimIndent()
                    binding.webviewMiniapp.evaluateJavascript(js, null)
                }
            } else if (intent?.action == "com.demo.imsdc.ACTION_SEND_EMOJI") {
                val emojiType = intent.getStringExtra("emoji") ?: "heart"
                Log.i(TAG, "Debug broadcast: Send emoji $emojiType")
                val emojiJson = JSONObject().apply {
                    put("service", "fun-calling")
                    put("action", "send_emoji")
                    put("type", emojiType)
                }.toString()
                imsDcSdk.sendOrQueueAdc(emojiJson.toByteArray(Charsets.UTF_8))
                binding.webviewMiniapp.post {
                    val js = """
                        (function() {
                            if (typeof renderEmoji === 'function') {
                                renderEmoji('$emojiType');
                            }
                        })();
                    """.trimIndent()
                    binding.webviewMiniapp.evaluateJavascript(js, null)
                }
            } else if (intent?.action == "com.demo.imsdc.ANSWER") {
                Log.d(TAG, "Simulating ANSWER button click & bringing CallActivity to front")
                val bringToFrontIntent = Intent(this@CallActivity, CallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    startActivity(bringToFrontIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error bringing CallActivity to front: ${e.message}")
                }
                answerIncomingCall()
            } else if (intent?.action == "com.demo.imsdc.END_CALL") {
                Log.d(TAG, "Simulating END_CALL button click")
                binding.btnEndCall.performClick()
            } else if (intent?.action == "com.demo.imsdc.ACTION_EXIT_MINIAPP") {
                Log.d(TAG, "Simulating Exit Mini-app click")
                binding.btnExitMiniapp.performClick()
            }
        }
    }

    private var incomingRemoteSdp: String? = null
    private var incomingCallIdStr: String? = null
    private var incomingRawSdpStr: String? = null

    private var ringbackToneGenerator: ToneGenerator? = null

    private fun startRingbackTone() {
        try {
            if (ringbackToneGenerator == null) {
                ringbackToneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
            }
            ringbackToneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            Log.i(TAG, "🎶 Ringback tone started (TONE_SUP_RINGTONE)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ringback tone: ${e.message}")
        }
    }

    private fun stopRingbackTone() {
        try {
            ringbackToneGenerator?.stopTone()
            ringbackToneGenerator?.release()
            ringbackToneGenerator = null
            Log.i(TAG, "🎶 Ringback tone stopped and released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ringback tone: ${e.message}")
        }
    }

    private fun configureWindowAndLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private val requiredPermissions = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.CAMERA
    )
    private var mapLocationController: com.demo.imsdc.location.MapLocationController? = null

    internal fun cancelMapLocationForNavigation() { mapLocationController?.cancel() }

    private val permissionRequestCode = 101

    // --- Lifecycle -----------------------------------------------------------

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_REQUEST_ANSWER, false)) miniAppManager.answerShare(true) // nothing pending: ignored
        if (intent.getBooleanExtra(EXTRA_FROM_ONGOING, false)) return
        configureWindowAndLockScreen()
        setIntent(intent)
        val autoAnswer = intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)
        val isIncoming = intent.getStringExtra(EXTRA_INCOMING_INVITE) != null
        if (!isIncoming || autoAnswer) {
            IncomingCallNotificationManager.dismiss(this)
        }
        Log.i(TAG, "onNewIntent received in CallActivity: ${intent.extras}")

        sipServer  = intent.getStringExtra(EXTRA_SIP_SERVER)  ?: sipServer
        localUser  = intent.getStringExtra(EXTRA_LOCAL_USER)  ?: localUser
        var resolvedNewIp = SipManager.shared?.getLocalIp()
            ?: intent.getStringExtra(EXTRA_LOCAL_IP)
            ?: localIp
        if (resolvedNewIp.isNullOrBlank() || resolvedNewIp == "0.0.0.0" || resolvedNewIp == "::" || resolvedNewIp.contains(":")) {
            resolvedNewIp = com.demo.imsdc.util.NetworkUtils.getLocalIpAddress(this, sipServer)
        }
        localIp    = resolvedNewIp
        sipPort    = intent.getIntExtra(EXTRA_SIP_PORT,   sipPort)

        isDataChannelsOpened = false
        isMiniAppInitiator = false

        val incomingInvite = intent.getStringExtra(EXTRA_INCOMING_INVITE)?.replace("\\n", "\n")
        val incomingSdp = intent.getStringExtra(EXTRA_INCOMING_SDP)?.replace("\\n", "\n")
        val incomingCallId = intent.getStringExtra(EXTRA_INCOMING_CALL_ID)
        val incomingRawSdp = intent.getStringExtra(EXTRA_INCOMING_RAW_SDP)?.replace("\\n", "\n")

        if (incomingInvite != null && incomingSdp != null && incomingCallId != null) {
            pendingInviteMsg = incomingInvite
            callId = incomingCallId
            incomingRemoteSdp = incomingSdp
            incomingCallIdStr = incomingCallId
            incomingRawSdpStr = incomingRawSdp ?: ""

            setupUI()
            initializeSipManager()
            initializeWebRtcManager()

            if (autoAnswer) {
                Log.i(TAG, "onNewIntent: autoAnswer requested — answering immediately")
                binding.slideToAnswerView.visibility = View.GONE
                binding.layoutBottomControls.visibility = View.VISIBLE
                handleIncomingCall(incomingSdp, incomingCallId, incomingRawSdp ?: "")
            } else {
                binding.tvCallStatus.visibility = View.GONE
                binding.layoutMiddleDetails.visibility = View.GONE
                binding.layoutBottomControls.visibility = View.GONE
                binding.slideToAnswerView.visibility = View.VISIBLE
                binding.localVideoView.visibility = View.GONE
                binding.remoteVideoView.visibility = View.GONE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this
        configureWindowAndLockScreen()

        val isIncoming = intent.getStringExtra(EXTRA_INCOMING_INVITE) != null
        if (!isIncoming) {
            IncomingCallNotificationManager.dismiss(this)
        }

        // Prevent accidental Back button from killing CallActivity (and the call)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hotlineApp(remoteUser) != null || currentAppId == "smart-menu") {
                    Log.i(TAG, "Back button pressed in hotline $remoteUser: resetting $currentAppId without opening operator-menu")
                    resetHotlineApp()
                } else if (binding.webviewMiniapp.visibility == View.VISIBLE && currentAppId != "operator-menu" && currentAppId != HOME_APP) {
                    Log.i(TAG, "Back button pressed: returning to $HOME_APP instead of finishing CallActivity")
                    miniAppManager.launchMiniApp(HOME_APP)
                } else {
                    Toast.makeText(this@CallActivity, "Vui lòng bấm nút Kết thúc để dừng cuộc gọi", Toast.LENGTH_SHORT).show()
                }
            }
        })

        miniAppManager = MiniAppManager(this)

        // Wake screen + show on lockscreen for incoming calls
        if (isIncoming) {
            // Play default ringtone
            try {
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
                ringtone?.play()
                Log.i(TAG, "🔔 Incoming call - ringtone playing")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play ringtone: ${e.message}")
            }
        }

        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Lấy params từ Intent
        val isIncomingCall = intent.getStringExtra(EXTRA_INCOMING_INVITE) != null
        val targetRemoteUser = intent.getStringExtra(EXTRA_REMOTE_USER)
        if (!isIncomingCall && targetRemoteUser.isNullOrBlank()) {
            Log.e(TAG, "CallActivity launched without remoteUser, finishing immediately to avoid unwanted call")
            finish()
            return
        }
        sipServer  = intent.getStringExtra(EXTRA_SIP_SERVER)  ?: ""
        localUser  = intent.getStringExtra(EXTRA_LOCAL_USER)  ?: ""
        remoteUser = targetRemoteUser ?: ""
        var resolvedLocalIp = SipManager.shared?.getLocalIp()
            ?: intent.getStringExtra(EXTRA_LOCAL_IP)
            ?: com.demo.imsdc.util.NetworkUtils.getLocalIpAddress(this, sipServer)
        if (resolvedLocalIp.isNullOrBlank() || resolvedLocalIp == "0.0.0.0" || resolvedLocalIp == "::" || resolvedLocalIp.contains(":")) {
            resolvedLocalIp = com.demo.imsdc.util.NetworkUtils.getLocalIpAddress(this, sipServer)
        }
        localIp    = resolvedLocalIp
        localPort  = intent.getIntExtra(EXTRA_LOCAL_PORT, 15060)
        sipPort    = intent.getIntExtra(EXTRA_SIP_PORT,   5060)

        Log.i(TAG, "CallActivity: $localUser -> $remoteUser via $sipServer:$sipPort")

        if (checkPermissions()) {
            initAndStart()
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(this, requiredPermissions, permissionRequestCode)
        }
    }

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (mapLocationController?.onPermissionResult(requestCode) == true) return
        if (requestCode == permissionRequestCode) {
            if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                initAndStart()
            } else {
                Toast.makeText(this, "Quy\u1ec1n \u00e2m thanh v\u00e0 camera l\u00e0 b\u1eaft bu\u1ed9c \u0111\u1ec3 th\u1ef1c hi\u1ec7n cu\u1ed9c g\u1ecdi PoC!", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initAndStart() {
        setupUI()
        initializeSipManager()
        initializeWebRtcManager()

        val incomingInvite = intent.getStringExtra(EXTRA_INCOMING_INVITE)?.replace("\\n", "\n")
        val incomingSdp = intent.getStringExtra(EXTRA_INCOMING_SDP)?.replace("\\n", "\n")
        val incomingCallId = intent.getStringExtra(EXTRA_INCOMING_CALL_ID)
        val incomingRawSdp = intent.getStringExtra(EXTRA_INCOMING_RAW_SDP)?.replace("\\n", "\n")
        val autoAnswer = intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)

        if (incomingInvite != null && incomingSdp != null && incomingCallId != null) {
            pendingInviteMsg = incomingInvite
            callId = incomingCallId
            incomingRemoteSdp = incomingSdp
            incomingCallIdStr = incomingCallId
            incomingRawSdpStr = incomingRawSdp ?: ""

            if (autoAnswer) {
                Log.i(TAG, "initAndStart: autoAnswer requested — answering immediately")
                binding.slideToAnswerView.visibility = View.GONE
                binding.layoutBottomControls.visibility = View.VISIBLE
                handleIncomingCall(incomingSdp, incomingCallId, incomingRawSdp ?: "")
            } else {
                binding.tvCallStatus.visibility = View.GONE
                binding.layoutMiddleDetails.visibility = View.GONE
                binding.layoutBottomControls.visibility = View.GONE
                binding.slideToAnswerView.visibility = View.VISIBLE
                binding.localVideoView.visibility = View.GONE
                binding.remoteVideoView.visibility = View.GONE
            }
        } else {
            binding.tvCallStatus.visibility = View.VISIBLE
            binding.layoutMiddleDetails.visibility = View.VISIBLE
            binding.layoutBottomControls.visibility = View.VISIBLE
            binding.slideToAnswerView.visibility = View.GONE
            startCall()
        }

        val filter = android.content.IntentFilter().apply {
            addAction("com.demo.imsdc.ACTION_LAUNCH_WHITEBOARD")
            addAction("com.demo.imsdc.ACTION_LAUNCH_CARO")
            addAction("com.demo.imsdc.ACTION_LAUNCH_FUNCALL")
            addAction("com.demo.imsdc.ACTION_LAUNCH_SMARTMENU")
            addAction("com.demo.imsdc.ACTION_LAUNCH_ENTERPRISE")
            addAction("com.demo.imsdc.ACTION_LAUNCH_APP")
            addAction("com.demo.imsdc.ACTION_TEST_DRAW")
            addAction("com.demo.imsdc.ACTION_EVAL_JS")
            addAction("com.demo.imsdc.ACTION_TEST_CARO_MOVE")
            addAction("com.demo.imsdc.ACTION_SEND_EMOJI")
            addAction("com.demo.imsdc.ANSWER")
            addAction("com.demo.imsdc.END_CALL")
            addAction("com.demo.imsdc.ACTION_EXIT_MINIAPP")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(debugReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(debugReceiver, filter)
        }
    }


    // --- UI Setup ------------------------------------------------------------

    private fun setupUI() {
        binding.tvRemoteUser.text = peerName
        val initial = peerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        binding.tvTopAvatar.text = initial
        binding.tvBigAvatar.text = initial
        binding.tvBigName.text = peerName
        CertifiedNumbers.of(remoteUser)?.let { showCertified(it) }
        startAvatarPulse()

        // Setup background placeholder & details defaults
        binding.viewBackgroundPlaceholder.visibility = View.VISIBLE
        binding.layoutMiddleDetails.visibility = View.GONE
        binding.localVideoView.visibility = View.GONE
        binding.remoteVideoView.visibility = View.GONE

        binding.tvCallStatus.text = "Đang kết nối..."

        // Mute button click listener
        binding.btnMute.setOnClickListener {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.isMicrophoneMute = !audioManager.isMicrophoneMute
            val isMuted = audioManager.isMicrophoneMute
            setCallButtonOn(binding.btnMute, isMuted)
            Toast.makeText(this, if (isMuted) "\u0110\u00e3 t\u1eaft ti\u1ebfng" else "\u0110\u00e3 b\u1eadt ti\u1ebfng", Toast.LENGTH_SHORT).show()
        }

        // Speaker button click listener
        binding.btnSpeakerCircle.setOnClickListener {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            setSpeakerphoneOn(audioManager, !isSpeakerMode)
            Toast.makeText(this, if (isSpeakerMode) "Loa ngo\u00e0i: B\u1eacT" else "Loa ngo\u00e0i: T\u1eaeT", Toast.LENGTH_SHORT).show()
        }

        // Hold/Resume button click listener (reusing keypad UI)
        binding.btnKeypadCircle.setOnClickListener {
            toggleCallHold()
        }

        // N�t K?t th�c cu?c g?i
        binding.btnEndCall.setOnClickListener {
            endCall()
        }

        // Nút quay lại Menu của mini-app
        binding.btnExitMiniapp.setOnClickListener {
            if (hotlineApp(remoteUser) != null || currentAppId == "smart-menu") {
                Log.i(TAG, "Exit clicked in hotline $remoteUser: resetting $currentAppId")
                resetHotlineApp()
            } else {
                Log.i(TAG, "User clicked Exit Mini-app -> returning to $HOME_APP")
                miniAppManager.launchMiniApp(HOME_APP)
            }
        }

        // Slide-to-Answer / Slide-to-Reject listeners (Samsung One UI style)
        binding.slideToAnswerView.onAnswerListener = {
            Log.i(TAG, "\uD83D\uDFE2 Slide-to-Answer triggered by user")
            answerIncomingCall()
        }

        binding.slideToAnswerView.onRejectListener = {
            Log.i(TAG, "\uD83D\uDD34 Slide-to-Reject triggered by user")
            val originalInvite = pendingInviteMsg
            if (originalInvite != null) {
                lifecycleScope.launch {
                    try {
                        sipManager.sendDecline(originalInvite)
                    } catch (e: Exception) {
                        Log.e(TAG, "Decline send error: ${e.message}")
                    }
                    binding.tvCallStatus.text = "Cu\u1ed9c g\u1ecdi b\u1ecb t\u1eeb ch\u1ed1i"
                    delay(1000)
                    finish()
                }
            } else {
                finish()
            }
        }

        // Setup OpenGL Renderers for local & remote video
        if (eglBase == null) {
            eglBase = org.webrtc.EglBase.create()
        }
        val eglContext = eglBase!!.eglBaseContext

        binding.remoteVideoView.visibility = View.GONE

        if (!isRendererInitialized) {
            try {
                binding.localVideoView.init(eglContext, null)
                binding.localVideoView.setEnableHardwareScaler(true)
                binding.localVideoView.setMirror(true)
                binding.localVideoView.setZOrderMediaOverlay(true)
                isRendererInitialized = true
            } catch (e: Exception) {
                Log.w(TAG, "localVideoView init failed or already initialized: ${e.message}")
            }
        }
        binding.localVideoView.visibility = View.GONE

        // Route audio to speakerphone for hands-free PoC demo
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        setSpeakerphoneOn(audioManager, true)

        // Setup WebView
        miniAppManager.setupWebView()

        // Hiển thị WebView chỉ sau khi Mini-app load xong
        binding.webviewMiniapp.visibility = View.INVISIBLE

        setupDraggableLocalVideo()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableLocalVideo() {
        // Đẩy local video view lên trên cùng của View hierarchy để không bị
        // WebView (transparent, full-screen) nuốt sự kiện touch khi kéo/thả.
        binding.localVideoView.bringToFront()

        var dX = 0f
        var dY = 0f
        var initialX = 0f
        var initialY = 0f
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var isDragging = false

        // A tap (not a drag) on my camera tile folds it into a camera button; tapping the button opens it again.
        binding.localVideoView.setOnClickListener { miniAppManager.onCameraTileTapped() }
        binding.btnCameraRestore.setOnClickListener { miniAppManager.onCameraButtonTapped() }
        // Vẽ / Chia sẻ: the other person's tile folds the same way.
        binding.remoteTileView.setOnClickListener { miniAppManager.onRemoteTileTapped() }
        binding.btnRemoteRestore.setOnClickListener { miniAppManager.onRemoteButtonTapped() }
        // The tile is a media overlay like my camera, set before it is ever shown (no runtime layer switch).
        binding.remoteTileView.setZOrderMediaOverlay(true)
        binding.remoteTileView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { attachRemoteVideo() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { rtpMediaManager?.onSurfaceDestroyed(holder.surface) }
        })
        // Full screen: the call pill shows (or hides) the call buttons.
        binding.layoutCallPill.setOnClickListener { miniAppManager.toggleCallControls() }

        binding.localVideoView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Caro shows my camera as a fixed tile next to the other person's video.
                    if (miniAppManager.fixedTiles()) return@setOnTouchListener false
                    // Đảm bảo video view luôn ở trên cùng ngay khi bắt đầu chạm,
                    // phòng trường hợp WebView được bringToFront lại sau khi load page.
                    binding.localVideoView.bringToFront()
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    initialX = event.rawX
                    initialY = event.rawY
                    isDragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - initialX) > touchSlop ||
                        kotlin.math.abs(event.rawY - initialY) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        // Limit dragging within the screen boundaries
                        val parent = view.parent as? View
                        val maxX = parent?.width?.toFloat() ?: 0f
                        val maxY = parent?.height?.toFloat() ?: 0f

                        var newX = event.rawX + dX
                        var newY = event.rawY + dY

                        if (maxX > 0 && maxY > 0) {
                            newX = newX.coerceIn(0f, maxX - view.width)
                            newY = newY.coerceIn(0f, maxY - view.height)
                        }

                        view.animate()
                            .x(newX)
                            .y(newY)
                            .setDuration(0)
                            .start()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }


    @SuppressLint("SetJavaScriptEnabled")


    // --- Init SIP ------------------------------------------------------------

    private fun initializeSipManager() {
        val sm = SipManager.shared ?: SipManager(
            sipServerHost = sipServer,
            sipServerPort = sipPort,
            localUsername = localUser,
            localPort = localPort
        ).also { SipManager.shared = it }

        sipManager = sm
        if (!sm.isRunning) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    sm.start(this@CallActivity)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start SipManager in CallActivity: ${e.message}")
                }
            }
        }

        // Callback: INVITE responses (180, 183, 200, 4xx, etc.)
        sipManager.onInviteResponse = { statusCode, sdpAnswer, toHeaderVal, rawSdp ->
            Log.i(TAG, "SIP INVITE response received: $statusCode, rawSdp len=${rawSdp?.length ?: 0}")

            // 1. Process Early Media (183 Session Progress) IMMEDIATELY on the background thread
            if (statusCode == 183) {
                if (rawSdp != null && rawSdp.trim().isNotEmpty()) {
                    try {
                        val (ip, aPort, vPort) = parseRemoteSdp(rawSdp)
                        Log.i(TAG, "📢 Early Media (183) active: RTP destination $ip:$aPort")
                        rtpMediaManager?.setRemoteDestination(ip, aPort, vPort)
                        rtpMediaManager?.start(null, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start Early Media RTP: ${e.message}", e)
                    }
                } else {
                    Log.w(TAG, "183 Session Progress received but SDP was empty!")
                }
            }

            // 2. Update UI on Main Thread
            runOnUiThread {
                try {
                    when (statusCode) {
                        in 100..199 -> {
                            if (statusCode == 180) {
                                binding.tvCallStatus.text = "🔔 Đang đổ chuông..."
                                startRingbackTone()
                            } else if (statusCode == 183) {
                                stopRingbackTone()
                                binding.tvCallStatus.text = "📢 Đang phát thông báo..."
                            } else if (statusCode == 100) {
                                binding.tvCallStatus.text = "Đang kết nối..."
                            }
                        }
                        200 -> {
                            stopRingbackTone()
                            binding.tvCallStatus.text = "✅ Đã kết nối"
                            sdpAnswer?.let { handleSdpAnswer(it, toHeaderVal, rawSdp ?: "") }
                        }
                        487 -> {
                            stopRingbackTone()
                            binding.tvCallStatus.text = "📴 Cuộc gọi kết thúc"
                            lifecycleScope.launch {
                                delay(1500)
                                resetAudio()
                                releaseVideoViews()
                                webRtcManager.close()
                                finish()
                            }
                        }
                        404 -> {
                            stopRingbackTone()
                            binding.tvCallStatus.text = "❌ Không tìm thấy $peerName"
                            lifecycleScope.launch {
                                delay(2000)
                                resetAudio()
                                releaseVideoViews()
                                webRtcManager.close()
                                finish()
                            }
                        }
                        480 -> {
                            stopRingbackTone()
                            binding.tvCallStatus.text = "❌ $peerName không trực tuyến (Offline)"
                            lifecycleScope.launch {
                                delay(2000)
                                resetAudio()
                                releaseVideoViews()
                                webRtcManager.close()
                                finish()
                            }
                        }
                        486, 603 -> {
                            stopRingbackTone()
                            binding.tvCallStatus.text = "Người dùng từ chối cuộc gọi"
                            lifecycleScope.launch {
                                delay(2000)
                                resetAudio()
                                releaseVideoViews()
                                webRtcManager.close()
                                finish()
                            }
                        }
                        408 -> {
                            stopRingbackTone()
                            binding.tvCallStatus.text = "⏱️ Yêu cầu hết hạn - Không trả lời (408)"
                            lifecycleScope.launch {
                                delay(2000)
                                resetAudio()
                                releaseVideoViews()
                                webRtcManager.close()
                                finish()
                            }
                        }
                        430 -> {
                            stopRingbackTone()
                            binding.tvCallStatus.text = "🔄 Mất đăng ký (Server restart). Đang đăng ký lại..."
                            SipManager.shared?.let { sm ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        sm.register(localIp)
                                        Log.i(TAG, "Auto-re-registered successfully after SIP 430")
                                        withContext(Dispatchers.Main) {
                                            binding.tvCallStatus.text = "✅ Đã đăng ký lại. Đang gọi lại..."
                                            delay(1500)
                                            startCall()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Auto-re-registration failed: ${e.message}")
                                        withContext(Dispatchers.Main) {
                                            binding.tvCallStatus.text = "❌ Đăng ký lại thất bại: ${e.message}"
                                            delay(2000)
                                            resetAudio()
                                            releaseVideoViews()
                                            webRtcManager.close()
                                            finish()
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            if (statusCode >= 300) {
                                stopRingbackTone()
                                binding.tvCallStatus.text = "⚠️ Cuộc gọi thất bại ($statusCode)"
                                lifecycleScope.launch {
                                    delay(2000)
                                    resetAudio()
                                    releaseVideoViews()
                                    webRtcManager.close()
                                    finish()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating UI on SIP response $statusCode: ${e.message}", e)
                }
            }
        }

        // Callback: B nhận được INVITE (incoming call)
        sipManager.onIncomingInvite = { fromUser, sdp, callId, rawMsg, rawSdp ->
            Log.i(TAG, "← Incoming INVITE from $fromUser (call=$callId)")
            pendingInviteMsg = rawMsg
            incomingRemoteSdp = sdp
            incomingCallIdStr = callId
            incomingRawSdpStr = rawSdp ?: ""
            lifecycleScope.launch(Dispatchers.Main) {
                binding.tvCallStatus.visibility = View.GONE
                binding.layoutMiddleDetails.visibility = View.GONE
                binding.layoutBottomControls.visibility = View.GONE
                binding.slideToAnswerView.visibility = View.VISIBLE
                binding.localVideoView.visibility = View.GONE
                binding.remoteVideoView.visibility = View.GONE
            }
        }

        sipManager.onCallEnded = {
            lifecycleScope.launch(Dispatchers.Main) {
                isEndingCall = true
                sipManager.markCallEnding()
                stopRingbackTone()
                binding.tvCallStatus.text = "Cuộc gọi kết thúc"
                delay(1500)
                finish()
            }
        }

        sipManager.onIpChangedDuringCall = {
            lifecycleScope.launch(Dispatchers.Main) {
                Log.w(TAG, "Local IP changed during the call, ending it")
                Toast.makeText(this@CallActivity, "Mạng thay đổi – cuộc gọi đã kết thúc", Toast.LENGTH_LONG).show()
                endCall()
            }
        }

        sipManager.onReInviteProvisional = { code, cseq -> miniAppManager.onReInviteProvisional(code, cseq) }

        sipManager.onReInviteFailed = { code, cseq ->
            lifecycleScope.launch(Dispatchers.Main) { miniAppManager.onReInviteFailed(code, cseq) }
        }

        // A CANCEL of the share request we are asking our user about: close the sheet, the call goes on.
        sipManager.onHeldReInviteCancelled = {
            lifecycleScope.launch(Dispatchers.Main) { miniAppManager.onHeldReInviteCancelled() }
        }

        sipManager.onReInviteReceived = { sdp, rawMsg ->
            Log.i(TAG, "Handling incoming Re-INVITE automatically...")
            lifecycleScope.launch(Dispatchers.Main) {
                var toLaunch: String? = null
                // Serialised with this side's Re-INVITEs: while one of ours is in progress, answer 491 (RFC 3261 14.2).
                val handled = miniAppManager.handleIncomingReInvite { withContext(Dispatchers.IO) {
                try {
                    // A PC still holding our unanswered offer (491/timeout, retry pending) cannot take the
                    // far end's offer: drop it, this offer decides the slot; our retry re-plans from there.
                    if (webRtcManager.homeAdcPC?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                        Log.w(TAG, "Incoming Re-INVITE while the Home offer is unanswered: dropping the local Home offer")
                        webRtcManager.closeADC()
                        sipManager.homeReqApp = null
                        sipManager.appReqApp = null
                    } else if (webRtcManager.appAdcPC?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                        Log.w(TAG, "Incoming Re-INVITE while the App offer is unanswered: dropping the local App offer")
                        webRtcManager.closeAppADC()
                        sipManager.appReqApp = null
                    }
                    val rawSdp = com.demo.imsdc.sip.SdpHelper.extractSdpBody(rawMsg) ?: ""
                    // Content Sharing: an offer that adds the content-share stream is a request the user decides on.
                    // 180 goes out, the page shows the invite, and 200 OK / 603 follows its answer.
                    val slidesNow = sipManager.slides.mode
                    val slidesOpen = slidesNow == com.demo.imsdc.sip.SlidesState.Mode.SEND || slidesNow == com.demo.imsdc.sip.SlidesState.Mode.RECV
                    val offersSlides = com.demo.imsdc.sip.SdpHelper.slidesEndpoint(rawSdp) != null
                    // A share request never changes the App line itself; an offer that opens or swaps the App line
                    // (a page being launched) is an app switch even if it carries a stale a=dcmap:1002.
                    val offeredApp = com.demo.imsdc.webrtc.pickSlots(com.demo.imsdc.webrtc.parseAppLines(rawSdp).drop(1)) { it.mid }.second
                    val appLineUnchanged = com.demo.imsdc.webrtc.diffAppSlot(sipManager.appReqApp, offeredApp) == com.demo.imsdc.webrtc.AppSlotChange.None
                    val photoOpening = com.demo.imsdc.sip.SdpHelper.hasContentShare(rawSdp) && !sipManager.shareStream && appLineUnchanged
                    val photoClosing = !com.demo.imsdc.sip.SdpHelper.hasContentShare(rawSdp) && sipManager.shareStream
                    val videoOpening = offersSlides && !slidesOpen
                    val videoClosing = !offersSlides && slidesOpen
                    val shareOpening = photoOpening || videoOpening
                    val shareClosing = photoClosing || videoClosing
                    if (shareOpening && !miniAppManager.awaitShareDecision(rawMsg, if (videoOpening) "video" else "photo")) {
                        return@withContext true // 603 / 487 already sent; nothing changes, the call goes on
                    }
                    // The other phone opens an app on the App line: our user decides first. The emote app (Home
                    // line), closing an app and re-offers of the same app are answered without asking.
                    val askedApp = when (val c = com.demo.imsdc.webrtc.diffAppSlot(sipManager.appReqApp, offeredApp)) {
                        is com.demo.imsdc.webrtc.AppSlotChange.Open -> c.appId
                        is com.demo.imsdc.webrtc.AppSlotChange.Replace -> c.appId
                        else -> null
                    }
                    if (askedApp != null && !shareOpening && hotlineApp(remoteUser) == null &&
                        askedApp != MiniAppManager.HOME_APP && !askedApp.contains("operator-menu") &&
                        !miniAppManager.awaitAppDecision(rawMsg, askedApp)) {
                        return@withContext true // 603 / 487 already sent; nothing changes, the call goes on
                    }
                    if (photoOpening) sipManager.shareStream = true // the answer carries the stream too
                    if (photoClosing) sipManager.shareStream = false
                    if (videoOpening) {
                        val (a, v) = com.demo.imsdc.media.RtpPorts.allocateSlides()
                        sipManager.slides = com.demo.imsdc.sip.SlidesState(com.demo.imsdc.sip.SlidesState.Mode.RECV, a, v)
                    }
                    if (videoClosing) sipManager.slides = com.demo.imsdc.sip.SlidesState(com.demo.imsdc.sip.SlidesState.Mode.CLOSED)
                    if (rawSdp.trim().isNotEmpty()) {
                        val (ip, aPort, vPort) = parseRemoteSdp(rawSdp)
                        rtpMediaManager?.setRemoteDestination(ip, aPort, vPort)
                    }

                    val appLines = com.demo.imsdc.webrtc.parseAppLines(rawSdp)
                    // Home = mid 3, App = mid 4, chosen by a=mid (the BDC is the first application line).
                    val (homeLine, appLine) = com.demo.imsdc.webrtc.pickSlots(appLines.drop(1)) { it.mid }
                    if (appLine != null) sipManager.appSlotUsed = true

                    // Home ADC: opened by the first offer that carries it, never closed by a Re-INVITE.
                    val homeJustOpened = homeLine != null && homeLine.isOpen && webRtcManager.homeAdcPC == null
                    if (homeJustOpened) {
                        val homeId = homeLine!!.reqApp ?: MiniAppManager.HOME_APP
                        sipManager.homeReqApp = homeId
                        webRtcManager.createADC(); dcManager.setupADC()
                        if (homeId == MiniAppManager.HOME_APP) { webRtcManager.createADC2(); dcManager.setupADC2() }
                    }

                    // App ADC: rebuild only the PeerConnection of mid 4, and only when mid 4 changed.
                    val change = com.demo.imsdc.webrtc.diffAppSlot(sipManager.appReqApp, appLine)
                    Log.i(TAG, "Re-INVITE: home=${homeLine?.reqApp}/${homeLine?.port} app=${appLine?.reqApp}/${appLine?.port} change=$change")
                    when (change) {
                        is com.demo.imsdc.webrtc.AppSlotChange.Open, is com.demo.imsdc.webrtc.AppSlotChange.Replace -> {
                            val id = if (change is com.demo.imsdc.webrtc.AppSlotChange.Open) change.appId
                                     else (change as com.demo.imsdc.webrtc.AppSlotChange.Replace).appId
                            webRtcManager.closeAppADC()
                            sipManager.appReqApp = id
                            webRtcManager.createAppADC(); dcManager.setupAppADC()
                        }
                        com.demo.imsdc.webrtc.AppSlotChange.Close -> {
                            webRtcManager.closeAppADC()
                            sipManager.appReqApp = null
                        }
                        com.demo.imsdc.webrtc.AppSlotChange.None -> Unit
                    }
                    if (sdp.trim().isNotEmpty()) {
                        Log.i(TAG, "B (Re-INVITE): Setting remote offer...")
                        webRtcManager.setRemoteOffer(sdp)
                    }

                    val sdpAnswer = webRtcManager.createAnswer()
                    sipManager.sendOkAnswer(rawMsg, sdpAnswer)
                    if (photoOpening) miniAppManager.onShareAccepted()
                    if (videoOpening) miniAppManager.onVideoShareAccepted(rawSdp)
                    if (shareClosing) miniAppManager.onShareClosedByPeer()

                    Log.i(TAG, "? Re-INVITE auto-answered successfully")
                    toLaunch = when (change) {
                        is com.demo.imsdc.webrtc.AppSlotChange.Open -> change.appId
                        is com.demo.imsdc.webrtc.AppSlotChange.Replace -> change.appId
                        com.demo.imsdc.webrtc.AppSlotChange.Close -> MiniAppManager.HOME_APP
                        com.demo.imsdc.webrtc.AppSlotChange.None -> if (homeJustOpened) (sipManager.homeReqApp ?: MiniAppManager.HOME_APP) else null
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error auto-answering Re-INVITE: ${e.message}", e)
                    false
                }
                } }
                if (!handled) {
                    Log.w(TAG, "Incoming Re-INVITE during our own: answering 491")
                    sipManager.sendRequestPending(rawMsg)
                }
                // Outside the lock: launching the page may queue a Re-INVITE of its own.
                toLaunch?.let { miniAppManager.launchMiniApp(it, sendRemoteTrigger = false) }
            }
        }

        // The answer is applied by MiniAppManager, under the negotiation lock, only if its CSeq is the one awaited.
        sipManager.onReInviteResponse = { cseq, sdpAnswer, rawSdp ->
            Log.i(TAG, "? Re-INVITE 200 OK received (CSeq $cseq)")
            lifecycleScope.launch(Dispatchers.Main) { miniAppManager.onReInviteAnswered(cseq, sdpAnswer, rawSdp) }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                sipManager.start()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting SipManager", e)
            }
        }
    }

    // --- Init WebRTC ---------------------------------------------------------

    private fun initializeWebRtcManager() {
        if (eglBase == null) {
            eglBase = org.webrtc.EglBase.create()
        }
        val eglCtx = eglBase!!.eglBaseContext

        webRtcManager = WebRtcManager(applicationContext)
        webRtcManager.initialize(eglCtx)

        // G�n SurfaceViewRenderers cho manager
        webRtcManager.localVideoView = binding.localVideoView
        webRtcManager.remoteVideoView = binding.remoteVideoView

        val isCallee = (intent.getStringExtra(EXTRA_INCOMING_INVITE) != null)
        com.demo.imsdc.media.RtpPorts.allocate()
        val localAudioPort = com.demo.imsdc.media.RtpPorts.audio
        val localVideoPort = com.demo.imsdc.media.RtpPorts.video
        var effectiveLocalIp = sipManager.getLocalIp()
        if (effectiveLocalIp.isNullOrBlank() || effectiveLocalIp == "0.0.0.0" || effectiveLocalIp == "::" || effectiveLocalIp.contains(":")) {
            effectiveLocalIp = com.demo.imsdc.util.NetworkUtils.getLocalIpAddress(this, sipServer)
        }
        Log.i(TAG, "Initializing RtpMediaManager: isCallee=$isCallee, localAudioPort=$localAudioPort, localVideoPort=$localVideoPort, effectiveLocalIp=$effectiveLocalIp")
        if (rtpMediaManager != null) {
            Log.w(TAG, "RtpMediaManager already exists, stopping old instance before creating a new one")
            try { rtpMediaManager?.stop() } catch (_: Exception) {}
            rtpMediaManager = null
        }
        rtpMediaManager = RtpMediaManager(applicationContext, localAudioPort, localVideoPort, effectiveLocalIp)

        dcManager = DataChannelManager(webRtcManager, lifecycleScope)
        dcManager.webView = binding.webviewMiniapp
        dcManager.sipServerIp = sipManager.sipServerHost
        dcManager.role = if (intent.getStringExtra(EXTRA_INCOMING_INVITE) != null) "callee" else "caller"

        imsDcSdk = com.demo.imsdc.webrtc.ImsDcSdk(this, binding.webviewMiniapp, webRtcManager, dcManager)
        dcManager.imsDcSdk = imsDcSdk

        webRtcManager.onRemoteDataChannelMessage = { data ->
            dcManager.handleAdcMessage(data)
        }

        dcManager.onAppReady = { appId, source -> miniAppManager.onAppReady(appId, source) }

        dcManager.onHtmlReceived = { _ ->
            Log.i(TAG, "Mini-app HTML received")
            lifecycleScope.launch(Dispatchers.Main) {
                binding.layoutMiddleDetails.visibility = View.GONE
                binding.webviewMiniapp.visibility = View.VISIBLE
            }
        }

        dcManager.onHeartReceived = {
            Log.i(TAG, "?? Heart received from peer!")
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(this@CallActivity, "\u2764\ufe0f B\u1ea1n nh\u1eadn \u0111\u01b0\u1ee3c tr\u00e1i tim!", Toast.LENGTH_SHORT).show()
            }
        }

        androidBridge = AndroidBridgeInterface(dcManager) { appId ->
            lifecycleScope.launch(Dispatchers.Main) {
                Log.i(TAG, "Launching new mini app requested by JS: $appId")
                miniAppManager.launchMiniApp(appId)
            }
        }

        dcManager.onLaunchAppReceived = { appId ->
            lifecycleScope.launch(Dispatchers.Main) {
                Log.i(TAG, "Received remote trigger to launch mini app: $appId")
                miniAppManager.launchMiniApp(appId, sendRemoteTrigger = false)
            }
        }

        // Kh?i t?o v� dang k� GSMA SDK controller
        imsDcSdk = com.demo.imsdc.webrtc.ImsDcSdk(applicationContext, binding.webviewMiniapp, webRtcManager, dcManager)
        // Called on the JS bridge thread: hop to Main before reading WebRTC/SIP slot state or queueing a Re-INVITE.
        imsDcSdk.onJsRequestedAdc = { appId ->
            lifecycleScope.launch(Dispatchers.Main) {
                if (isMiniAppInitiator) {
                    val targetId = when {
                        appId.contains("0003210000000038") || appId.contains("tuyaapp") || appId.contains("sketch-sync-app") -> "sketch-sync-app"
                        appId.contains("operator-menu") -> "operator-menu"
                        else -> appId
                    }
                    // A hotline app lives on the Home slot (mid 3); mid 4 is never used on a hotline call.
                    val isHome = targetId == MiniAppManager.HOME_APP || hotlineApp(remoteUser) != null
                    val alreadyOpen = if (isHome) webRtcManager.homeAdcPC != null
                                      else sipManager.appReqApp == targetId && webRtcManager.appAdcPC != null
                    if (!alreadyOpen) {
                        android.util.Log.i(TAG, "JS called createDataChannel() for app $appId ($targetId), natively triggering SIP Re-INVITE")
                        miniAppManager.triggerAppNegotiationReInvite(targetId)
                    } else if (isHome) {
                        // The Home ADC has been open since the first emote Re-INVITE: bind the page to it.
                        val adc = webRtcManager.adcChannel
                        if (adc != null && adc.state() == org.webrtc.DataChannel.State.OPEN) {
                            imsDcSdk.notifyDataChannelState(imsDcSdk.getPrimaryDcLabel(), 1)
                            imsDcSdk.flushPendingAdc()
                        }
                        android.util.Log.i(TAG, "Home ADC already open, page bound without a Re-INVITE")
                    } else {
                        android.util.Log.d(TAG, "ADC negotiation already in progress for $appId ($targetId), ignoring duplicate JS request")
                    }
                } else {
                    android.util.Log.i(TAG, "Not initiator for app $appId. Waiting for incoming Re-INVITE.")
                }
            }
        }
        mapLocationController = com.demo.imsdc.location.MapLocationController(this, binding.webviewMiniapp) {
            currentAppId == "photo-share" && !isEndingCall &&
                binding.webviewMiniapp.visibility == View.VISIBLE &&
                binding.webviewMiniapp.url == "https://miniapp/index.html" &&
                lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        androidBridge.onRequestMapLocation = { id ->
            runOnUiThread { mapLocationController?.request(id) }
        }
        androidBridge.onCancelMapLocation = { id ->
            runOnUiThread { mapLocationController?.cancelFromJs(id) }
        }
        androidBridge.onJsRequestedAdc = imsDcSdk.onJsRequestedAdc
        val sdkBridge = imsDcSdk.getJavascriptInterface()
        // Inject AndroidBridge & IMSDataChannelBridge into WebView
        binding.webviewMiniapp.addJavascriptInterface(androidBridge, "AndroidBridge")
        binding.webviewMiniapp.addJavascriptInterface(androidBridge, "IMSDataChannelBridge")
        binding.webviewMiniapp.addJavascriptInterface(androidBridge, "ImsDcBridge")
        binding.webviewMiniapp.addJavascriptInterface(sdkBridge, "_dsbridge")

        // Photo-share transport: chunk 16KB + flow control qua ADC 1000 (P2P relay).
        // Khởi tạo SAU imsDcSdk để deliverToJs có đường đẩy lên WebView ngay lập tức.
        val photoShare = com.demo.imsdc.webrtc.PhotoShareTransport(webRtcManager, lifecycleScope, imsDcSdk)
        photoShare.webView = binding.webviewMiniapp
        dcManager.photoShare = photoShare
        androidBridge.photoShare = photoShare
        androidBridge.shareHandler = object : AndroidBridgeInterface.ShareHandler {
            override fun requestShare(kind: String) = miniAppManager.requestShare(kind)
            override fun cancelShare() = miniAppManager.cancelShare()
            override fun answerShare(accept: Boolean) = miniAppManager.answerShare(accept)
            override fun stopShare() = miniAppManager.stopShare()
            override fun pickVideo() = miniAppManager.pickVideo()
            override fun startScreen() = miniAppManager.startScreen()
            override fun pauseVideo(paused: Boolean) = miniAppManager.pauseVideo(paused)
            override fun muteShareAudio(muted: Boolean) = miniAppManager.muteShareAudio(muted)
            override fun peerNumber(): String = remoteUser
            override fun setFullScreen(on: Boolean) = miniAppManager.setFullScreen(on)
            override fun showCallControls() = miniAppManager.showCallControls()
        }

        // Callback: PeerConnection connected ? m? DataChannels v� kh?i d?ng Mini-app
        webRtcManager.onPeerConnected = {
            Log.i(TAG, "?? PeerConnection CONNECTED to MF!")
            showCallActiveUi()
            // Auto-launch whiteboard has been disabled. Menu will be shown.
        }

        webRtcManager.onPeerDisconnected = {
            Log.w(TAG, "PeerConnection disconnected from MF")
        }
    }

    private fun startCallTimer() {
        if (timerJob != null) return
        elapsedSeconds = 0
        binding.tvCallTimer.visibility = View.VISIBLE
        timerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                val minutes = elapsedSeconds / 60
                val seconds = elapsedSeconds % 60
                binding.tvCallTimer.text = String.format(" · %02d:%02d", minutes, seconds)
                binding.tvPillTimer.text = String.format("%02d:%02d", minutes, seconds)
                kotlinx.coroutines.delay(1000)
                elapsedSeconds++
            }
        }
    }

    private fun stopCallTimer() {
        timerJob?.cancel()
        timerJob = null
        binding.tvCallTimer.visibility = View.GONE
    }


    // --- Start Call (A-side) -------------------------------------------------

    private fun startCall() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    binding.tvCallStatus.text = "Đang kết nối..."
                }

                // 1. T?o PeerConnection
                webRtcManager.createPeerConnection(isCaller = true) { state ->
                    Log.d(TAG, "WebRTC(A) ICE state: $state")
                }

                // B?t d?u capture local camera v� microphone
                withContext(Dispatchers.Main) {
                    val enableVideo = (hotlineApp(remoteUser) == null)
                    isVideoEnabled = enableVideo
                    webRtcManager.startLocalCapture(binding.localVideoView, enableVideo)
                    startOngoingCallService()
                }

                // 2. T?o placeholder DC d? SDP c m=application
                webRtcManager.createPlaceholderChannel()

                // 3. T?o BDC (pre-negotiated, chua m?  ch? ICE connected)
                webRtcManager.ensureBdcChannel()

                // 4. T?o WebRTC offer d? c SDP km INVITE (early offer)
                val localOffer = webRtcManager.createOffer()
                Log.i(TAG, "WebRTC offer created, SDP length=${localOffer.length}")

                withContext(Dispatchers.Main) {
                    binding.tvCallStatus.text = "Đang kết nối..."
                }

                // 5. Gởi SIP INVITE kèm SDP offer
                callId = sipManager.invite(remoteUser, localIp, localOffer)
                Log.i(TAG, "SIP INVITE sent with SDP, call_id=$callId")

                withContext(Dispatchers.Main) {
                    binding.tvCallStatus.text = "Đang kết nối..."
                    // Launch a coroutine to handle INVITE response timeout (30 seconds)
                    lifecycleScope.launch {
                        delay(30000)
                        if (!sipManager.isCallActive) {
                            Log.w(TAG, "Call request timed out - no response from $remoteUser")
                            stopRingbackTone()
                            binding.tvCallStatus.text = "⏱️ Không trả lời (Timeout)"
                            delay(2000)
                            endCall()
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "startCall error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.tvCallStatus.text = "\u274c L\u1ed7i: ${e.message}"
                }
            }
        }
    }

    // --- Handle SDP Answer (A-side, sau khi nh?n 200 OK) --------------------

    private fun handleSdpAnswer(sdpAnswer: String, toHeaderVal: String, rawSdp: String) {
        Log.i(TAG, "Setting remote SDP answer (anchored by Erlang to MF)...")

        // C?u hnh d?a ch? dch cho RTP t? raw SIP SDP
        val (ip, aPort, vPort) = parseRemoteSdp(rawSdp)
        rtpMediaManager?.setRemoteDestination(ip, aPort, vPort)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                webRtcManager.setRemoteAnswer(sdpAnswer)
                Log.i(TAG, "? Remote answer set  sending ACK (no SDP)")

                sipManager.sendAck(toHeaderVal)

                withContext(Dispatchers.Main) {
                    showCallActiveUi()
                    imsDcSdk.handleRemoteSdp(sdpAnswer, callId = callId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleSdpAnswer error: ${e.message}", e)
            }
        }
    }

    // --- Answer Incoming Call (shared helper) ---------------------------------

    /**
     * Trích xuất SDP đã cache và thực hiện nhận cuộc gọi.
     * Được gọi từ SlideToAnswerView callback và debug broadcast ANSWER.
     */
    private fun answerIncomingCall() {
        val remoteSdp = incomingRemoteSdp
            ?: intent.getStringExtra(EXTRA_INCOMING_SDP)?.replace("\\n", "\n") ?: ""
        val cId = incomingCallIdStr
            ?: intent.getStringExtra(EXTRA_INCOMING_CALL_ID) ?: callId ?: ""
        val rawSdp = incomingRawSdpStr
            ?: intent.getStringExtra(EXTRA_INCOMING_RAW_SDP)?.replace("\\n", "\n") ?: ""
        binding.slideToAnswerView.visibility = View.GONE
        binding.layoutBottomControls.visibility = View.VISIBLE
        handleIncomingCall(remoteSdp, cId, rawSdp)
    }

    // --- Handle Incoming Call (B-side) ---------------------------------------

    /**
     * u?c g?i khi B nh?n SIP INVITE t? Erlang.
     * Flow B-side:
     *   1. T?o PeerConnection
     *   2. setRemoteOffer(sdpFromMF) ? ICE params c?a MF d anchor
     *   3. createAnswer() ? SDP Answer c?a B
     *   4. G?i SIP 200 OK v?i SDP Answer
     *   Erlang s? anchor SDP Answer ny vo MF-B, r?i g?i v? A
     */
    private fun handleIncomingCall(remoteSdp: String, incomingCallId: String, rawSdp: String) {
        // Stop ringtone when processing incoming call
        try { ringtone?.stop() } catch (_: Exception) {}
        IncomingCallNotificationManager.dismiss(this)
        isDataChannelsOpened = false
        // Cấu hình địa chỉ đích cho RTP (nếu có SDP Offer ngay)
        if (rawSdp.trim().isNotEmpty()) {
            val (ip, aPort, vPort) = parseRemoteSdp(rawSdp)
            rtpMediaManager?.setRemoteDestination(ip, aPort, vPort)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    binding.tvCallStatus.text = "\ud83d\udcf2 \u0110ang x\u1eed l\u00fd cu\u1ed9c g\u1ecdi \u0111\u1ebfn..."
                }

                // 1. T?o PeerConnection cho B
                webRtcManager.createPeerConnection(isCaller = false) { state ->
                    Log.d(TAG, "WebRTC(B) ICE state: $state")
                }

                // 2. T?o BDC (pre-negotiated, ID c? d?nh)
                webRtcManager.ensureBdcChannel()

                if (remoteSdp.trim().isEmpty()) {
                    Log.i(TAG, "B (Delayed Offer): Creating local SDP Offer...")
                    withContext(Dispatchers.Main) {
                        val enableVideo = (hotlineApp(remoteUser) == null)
                        isVideoEnabled = enableVideo
                        webRtcManager.startLocalCapture(binding.localVideoView, enableVideo)
                        startOngoingCallService()
                    }
                    val localOffer = webRtcManager.createOffer()

                    sipManager.onAckWithSdp = { remoteSdpAnswer, rawSdpAnswer ->
                        Log.i(TAG, "B (Delayed Offer): ACK received, setting remote answer...")

                        // C?u h�nh d?a ch? d�ch cho RTP t? raw SIP SDP
                        val (ip, aPort, vPort) = parseRemoteSdp(rawSdpAnswer)
                        rtpMediaManager?.setRemoteDestination(ip, aPort, vPort)

                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                webRtcManager.setRemoteAnswer(remoteSdpAnswer)
                                Log.i(TAG, "B (Delayed Offer): ? Remote answer set � ICE starting")
                                withContext(Dispatchers.Main) {
                                    showCallActiveUi()
                                    imsDcSdk.handleRemoteSdp(remoteSdpAnswer, callId = incomingCallId)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "B (Delayed Offer) setRemoteAnswer error: ${e.message}", e)
                            }
                        }
                    }

                    val originalInvite = pendingInviteMsg?.takeIf { it.contains("Via:") }
                        ?: sipManager.lastIncomingInviteMsg
                        ?: pendingInviteMsg

                    if (originalInvite != null && originalInvite.contains("Via:")) {
                        Log.i(TAG, "B (Delayed Offer): Sending SIP 200 OK with local SDP Offer")
                        sipManager.sendOkAnswer(originalInvite, localOffer)
                    } else {
                        Log.e(TAG, "B: No valid pending invite message stored!")
                    }
                } else {
                    // Regular Offer flow
                    Log.i(TAG, "B: Setting remote offer (anchored SDP from MF)...")
                    webRtcManager.setRemoteOffer(remoteSdp)
                    withContext(Dispatchers.Main) {
                        // Truy?n callId d? SDK nh?n d?ng session
                        imsDcSdk.handleRemoteSdp(remoteSdp, callId = incomingCallId)
                    }

                    // B?t d?u capture local camera v� microphone cho B SAU khi d� set remote offer!
                    withContext(Dispatchers.Main) {
                        val enableVideo = (hotlineApp(remoteUser) == null)
                        isVideoEnabled = enableVideo
                        webRtcManager.startLocalCapture(binding.localVideoView, enableVideo)
                        startOngoingCallService()
                    }

                    Log.i(TAG, "B: Creating SDP Answer...")
                    val sdpAnswer = webRtcManager.createAnswer()
                    Log.i(TAG, "B: SDP Answer created (${sdpAnswer.length} bytes)")

                    val originalInvite = pendingInviteMsg?.takeIf { it.contains("Via:") }
                        ?: sipManager.lastIncomingInviteMsg
                        ?: pendingInviteMsg

                    if (originalInvite != null && originalInvite.contains("Via:")) {
                        Log.i(TAG, "B: Sending SIP 200 OK with SDP Answer")
                        sipManager.sendOkAnswer(originalInvite, sdpAnswer)
                    } else {
                        Log.e(TAG, "B: No valid pending invite message stored! (pending=$pendingInviteMsg, last=${sipManager.lastIncomingInviteMsg != null})")
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.tvCallStatus.text = "\u2705 \u0110\u00e3 ch\u1ea5p nh\u1eadn cu\u1ed9c g\u1ecdi"
                    showCallActiveUi()
                }

            } catch (e: Exception) {
                Log.e(TAG, "handleIncomingCall error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.tvCallStatus.text = "\u274c L\u1ed7i x\u1eed l\u00fd cu\u1ed9c g\u1ecdi \u0111\u1ebfn"
                }
            }
        }
    }

    private fun showCallActiveUi() {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.tvCallStatus.visibility = View.VISIBLE
            if (currentAppId == "operator-menu") {
                binding.tvCallStatus.text = "🟢 Cuộc gọi thoại & video đã kết nối"
            }
            startCallTimer()

            // Hide gradient placeholder & middle avatar details
            binding.viewBackgroundPlaceholder.visibility = View.GONE
            binding.layoutMiddleDetails.visibility = View.GONE

            // Open DataChannels (BDC & ADC) for mini-app interaction
            openDataChannels()

            if (!isCallActiveUiShown) {
                isCallActiveUiShown = true
                startOngoingCallService()
                val hotlineAppId = hotlineApp(remoteUser)
                if (hotlineAppId != null) {
                    binding.localVideoView.visibility = View.GONE
                    binding.remoteVideoView.visibility = View.GONE
                    Log.i(TAG, "Hotline $remoteUser: auto-launching $hotlineAppId via BDC & ADC")
                    miniAppManager.launchMiniApp(hotlineAppId, sendRemoteTrigger = true)
                } else if (currentAppId == "operator-menu") {
                    binding.localVideoView.visibility = View.VISIBLE
                    binding.remoteVideoView.visibility = View.VISIBLE
                    // The caller opens the emote home (Re-INVITE for its ADC); the callee shows the menu
                    // until that Re-INVITE arrives, so only one side negotiates.
                    if (intent.getStringExtra(EXTRA_INCOMING_INVITE) == null) {
                        Log.i(TAG, "Caller: opening $HOME_APP as the in-call home")
                        miniAppManager.launchMiniApp(HOME_APP, sendRemoteTrigger = true)
                    } else {
                        Log.i(TAG, "Operator menu will load by default via BDC.")
                        miniAppManager.launchMiniApp("operator-menu", sendRemoteTrigger = false)
                    }
                }
            } else {
                Log.i(TAG, "showCallActiveUi already initialized, active app=$currentAppId - preserving active mini-app")
            }

            // Start RTP audio/video streaming
            val surface = binding.remoteVideoView.holder.surface
            if (surface != null && surface.isValid) {
                Log.i(TAG, "Starting RTP media stream directly (surface is already valid)")
                rtpMediaManager?.start(webRtcManager.localVideoTrack, surface)
            } else {
                Log.i(TAG, "Starting RTP audio first, waiting for remote video surface callback...")
                rtpMediaManager?.start(webRtcManager.localVideoTrack, null)
            }

            binding.remoteVideoView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.i(TAG, "remoteVideoView surface created, updating RTP media decoder surface now")
                    attachRemoteVideo()
                    rtpMediaManager?.updateLocalVideoTrack(webRtcManager.localVideoTrack)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.i(TAG, "remoteVideoView surface destroyed, releasing decoder safely")
                    rtpMediaManager?.onSurfaceDestroyed(holder.surface)
                }
            })
        }
    }

    // --- Open DataChannels (sau khi ICE connected) ---------------------------

    /** Hotline apps have no operator-menu to return to: reset the app to its first screen instead. */
    private fun resetHotlineApp() {
        val fn = APP_RESET_JS[currentAppId] ?: "resetSmartMenu"
        binding.webviewMiniapp.evaluateJavascript("if (typeof $fn === 'function') $fn();", null)
    }

    private fun openDataChannels() {
        Log.i(TAG, "Opening DataChannels (was opened=$isDataChannelsOpened)...")
        isDataChannelsOpened = true

        // Setup BDC – sẽ tự động gửi HTTP GET khi mở
        dcManager.setupBDC()

        // Setup ADC – sẽ lắng nghe message từ MF
        dcManager.setupADC()

        dcManager.onLaunchAppReceived = { appId ->
            Log.i(TAG, "Remote requested launch app: $appId")
            lifecycleScope.launch(Dispatchers.Main) {
                miniAppManager.launchMiniApp(appId, sendRemoteTrigger = false)
            }
        }

    }

    // --- End Call ------------------------------------------------------------

    /** The call service was started for this call (it must start while the app is in the foreground). */
    private var callServiceStarted = false

    /**
     * Keeps the call (mic, camera, network) alive while the user is on the home screen or in another app.
     * Started as soon as the call is placed or answered, while the app is on screen: Android refuses a mic or
     * camera service started from the background (e.g. a call that connects after the caller pressed Home).
     */
    private fun startOngoingCallService() {
        if (callServiceStarted) return
        callServiceStarted = true
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Without it the service still runs; the notification is just not shown.
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
        }
        com.demo.imsdc.call.CallService.onUserAction = { action ->
            runOnUiThread {
                when (action) {
                    com.demo.imsdc.call.CallService.ACTION_END -> endCall()
                    com.demo.imsdc.call.CallService.ACTION_STOP_SHARE -> miniAppManager.stopScreenFromNotification()
                    com.demo.imsdc.call.CallService.ACTION_DECLINE_REQUEST -> miniAppManager.answerShare(false)
                }
            }
        }
        com.demo.imsdc.call.CallService.start(this, peerName, camera = isVideoEnabled)
    }

    private fun endCall() {
        mapLocationController?.cancel()
        if (isEndingCall) return
        isEndingCall = true
        agentCallJob?.cancel()
        com.demo.imsdc.call.CallService.stop(this)
        callServiceStarted = false
        sipManager.markCallEnding()
        Log.i(TAG, "Ending call...")
        IncomingCallNotificationManager.dismiss(this)
        stopRingbackTone()
        isDataChannelsOpened = false
        stopCallTimer()
        rtpMediaManager?.stop()
        // Photo-share: dọn photostore theo vòng đời cuộc gọi (GSMA TS.66 container lifecycle).
        dcManager.photoShare?.cleanup(this)
        imsDcSdk.onCallEnded()
        val cId = callId ?: sipManager.currentCallId
        val fTag = sipManager.currentFromTag
        lifecycleScope.launch(Dispatchers.IO) {
            sipManager.bye(remoteUser, localIp, overrideCallId = cId, overrideFromTag = fTag)
        }
        resetAudio()
        releaseVideoViews()
        webRtcManager.close()
        finish()
    }

    // --- Lifecycle cleanup ---------------------------------------------------

    override fun onStart() {
        super.onStart()
        activeInstance = this
        // Back on screen: the call screen shows the ringing call or the pending request itself.
        IncomingCallNotificationManager.onCallScreenShown(this)
        if (::miniAppManager.isInitialized) miniAppManager.onCallScreenShown()
        if (isVideoEnabled && isCallActiveUiShown) {
            if (cameraPausedInBackground) {
                Log.i(TAG, "onStart: resuming camera capture (app returning to foreground)")
                webRtcManager.resumeLocalCapture()
                cameraPausedInBackground = false
            }
            rtpMediaManager?.updateLocalVideoTrack(webRtcManager.localVideoTrack)
            attachRemoteVideo()
        }
    }

    /**
     * Points the call-video decoder at the view that shows it: the tile in Vẽ / Chia sẻ
     * (MiniAppManager.remoteTileShown), the full-screen background otherwise. No-op until that surface exists.
     */
    fun attachRemoteVideo() {
        val tile = binding.remoteTileView.holder.surface
        val surface = if (::miniAppManager.isInitialized && miniAppManager.remoteTileShown && tile != null && tile.isValid) tile
            else binding.remoteVideoView.holder.surface
        if (surface != null && surface.isValid) rtpMediaManager?.updateDecoderSurface(surface)
    }

    /** onStop paused my camera (no camera-type call service); onStart resumes it. */
    private var cameraPausedInBackground = false

    override fun onStop() {
        mapLocationController?.cancel("background")
        super.onStop()
        if (!isChangingConfigurations) {
            // Hidden (Home, another app, screen off): the ringing call and a pending request ask by notification.
            IncomingCallNotificationManager.onCallScreenHidden(this)
            if (::miniAppManager.isInitialized) miniAppManager.onCallScreenHidden()
        }
        if (isVideoEnabled && isCallActiveUiShown) {
            if (com.demo.imsdc.call.CallService.cameraInBackground) {
                // The call service holds the camera type: keep sending my video (the preview just isn't drawn).
                Log.i(TAG, "onStop: camera keeps running in the background")
            } else {
                Log.i(TAG, "onStop: pausing camera capture (app going to background)")
                webRtcManager.pauseLocalCapture()
                cameraPausedInBackground = true
            }
        }
    }

    override fun onDestroy() {
        mapLocationController?.cancel()
        mapLocationController = null
        super.onDestroy()
        agentCallJob?.cancel()
        com.demo.imsdc.call.CallService.stop(this)
        isEndingCall = true
        if (activeInstance === this) {
            activeInstance = null
        }
        IncomingCallNotificationManager.dismiss(this)
        stopRingbackTone()
        // Stop ringtone if still playing
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        isDataChannelsOpened = false
        try {
            unregisterReceiver(debugReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering debugReceiver: ${e.message}")
        }
        stopCallTimer()
        rtpMediaManager?.stop()
        // Photo-share: dọn photostore theo vòng đời cuộc gọi (GSMA TS.66 container lifecycle).
        dcManager.photoShare?.cleanup(this)
        imsDcSdk.onCallEnded()
        // Ensure remote peer is released if call was in progress or ringing
        if (!isEndingCall && (callId != null || SipManager.shared?.currentCallId != null)) {
            val rUser = remoteUser
            val lIp = localIp
            val cId = callId ?: SipManager.shared?.currentCallId
            val fTag = SipManager.shared?.currentFromTag
            val established = isCallActiveUiShown || (this::sipManager.isInitialized && sipManager.isCallActive)
            SipManager.shared?.let { sm ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        if (established) {
                            sm.bye(rUser, lIp, overrideCallId = cId, overrideFromTag = fTag)
                        } else {
                            sm.cancel(rUser, lIp, overrideCallId = cId, overrideFromTag = fTag)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        resetAudio()
        releaseVideoViews()
        // NOTE: Redundant sipManager.resetCallState() removed to prevent wiping pendingCallId before sm.bye/sm.cancel sends packet
        sipManager.onInviteResponse = null
        sipManager.onCallEnded = null
        sipManager.onIpChangedDuringCall = null
        sipManager.onAckWithSdp = null
        sipManager.onReInviteReceived = null
        sipManager.onReInviteFailed = null
        sipManager.onHeldReInviteCancelled = null
        if (::miniAppManager.isInitialized) { miniAppManager.resetShare(); miniAppManager.resetRequests() }
        sipManager.onReInviteResponse = null
        try {
            (binding.webviewMiniapp.parent as? android.view.ViewGroup)?.removeView(binding.webviewMiniapp)
            binding.webviewMiniapp.stopLoading()
            binding.webviewMiniapp.removeJavascriptInterface("AndroidBridge")
            binding.webviewMiniapp.removeJavascriptInterface("IMSDataChannelBridge")
            binding.webviewMiniapp.removeJavascriptInterface("ImsDcBridge")
            binding.webviewMiniapp.removeJavascriptInterface("_dsbridge")
            binding.webviewMiniapp.removeAllViews()
            binding.webviewMiniapp.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying WebView: ${e.message}")
        }
        webRtcManager.close()
    }

    private fun releaseVideoViews() {
        try {
            if (isRendererInitialized) {
                binding.localVideoView.release()
                isRendererInitialized = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing video views: ${e.message}")
        }
        val oldEglBase = eglBase
        eglBase = null
        // Delay eglBase release to allow WebRtcManager background thread to safely dispose capturers
        Thread {
            try {
                Thread.sleep(1000)
                oldEglBase?.release()
                Log.i(TAG, "EglBase released safely.")
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing EglBase: ${e.message}")
            }
        }.start()
    }

    private fun resetAudio() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
            setSpeakerphoneOn(audioManager, false)
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting audio: ${e.message}")
        }
    }

    private fun parseRemoteSdp(sdp: String): Triple<String, Int, Int> {
        var ip = sipServer // M?c d?nh l� IP c?a SIP Server
        var audioPort = 40002
        var videoPort = 40004

        val lines = sdp.replace("\r", "").split("\n")
        var currentMedia = ""
        var seenAudio = false
        var seenVideo = false
        for (line in lines) {
            val trimmed = line.trim()
            // Only the first audio and video lines are the call's microphone/camera. A later pair is the
            // shared content (a=content:slides) with its own ports; taking those sent the camera and mic
            // into the content stream (and to port 0 once the share closed).
            if (trimmed.startsWith("m=audio ")) {
                currentMedia = if (seenAudio) "slides" else "audio"
                val parts = trimmed.split(" ")
                if (parts.size >= 2 && !seenAudio) {
                    audioPort = parts[1].toIntOrNull() ?: 40002
                }
                seenAudio = true
            } else if (trimmed.startsWith("m=video ")) {
                currentMedia = if (seenVideo) "slides" else "video"
                val parts = trimmed.split(" ")
                if (parts.size >= 2 && !seenVideo) {
                    videoPort = parts[1].toIntOrNull() ?: 40004
                }
                seenVideo = true
            } else if (trimmed.startsWith("m=application ")) {
                currentMedia = "application"
            } else if (trimmed.startsWith("c=IN IP4 ")) {
                val parts = trimmed.split(" ")
                if (parts.size >= 3) {
                    val candidateIp = parts[2]
                    if (currentMedia == "audio" || currentMedia == "video" || currentMedia == "") {
                        if (candidateIp != "0.0.0.0" && candidateIp != "127.0.0.1") {
                            ip = candidateIp
                        }
                    }
                }
            }
        }
        return Triple(ip, audioPort, videoPort)
    }


    // --- Helpers ------------------------------------------------------------

    private fun toggleCallHold() {
        val nextHoldState = !sipManager.isCallHoldActive
        sipManager.isCallHoldActive = nextHoldState

        setCallButtonOn(binding.btnKeypadCircle, nextHoldState)

        Toast.makeText(this, if (nextHoldState) "\u0110ang gi\u1eef cu\u1ed9c g\u1ecdi..." else "\u0110ang kh\u00f4i ph\u1ee5c cu\u1ed9c g\u1ecdi...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                binding.tvCallStatus.text = if (nextHoldState) "\u23f8\ufe0f \u0110ang gi\u1eef" else "\u2705 \u0110\u00e3 k\u1ebft n\u1ed1i"
                // Serialised with app changes; its answer/failure is matched by CSeq, never taken for an app request.
                miniAppManager.sendHoldReInvite()
            } catch (e: Exception) {
                Log.e(TAG, "L?i thay d?i tr?ng th�i Hold/Resume: ${e.message}")
            }
        }
    }



    // Messenger-style toggle: white circle with a dark icon when on, translucent with a white icon when off.
    private fun setCallButtonOn(btn: android.widget.ImageView, on: Boolean) {
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (on) "#FFFFFFFF" else "#29FFFFFF")
        )
        btn.imageTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (on) "#FF111111" else "#FFFFFFFF")
        )
    }

    // Soft ring that grows and fades behind the big avatar while the call is connecting.
    /** Carrier-verified number: blue screen, brand icon with a green check, "Đã xác thực" badge. */
    private fun showCertified(c: CertifiedNumbers.Certified) {
        binding.viewBackgroundPlaceholder.setBackgroundResource(R.drawable.gradient_call_verified)
        binding.tvBigAvatar.setBackgroundResource(R.drawable.bg_verified_avatar)
        binding.tvBigAvatar.text = c.icon
        binding.tvBigAvatar.textSize = 44f
        binding.tvTopAvatar.text = c.icon
        binding.tvVerifiedCheck.visibility = View.VISIBLE
        binding.tvBigNumber.text = remoteUser
        binding.tvBigNumber.visibility = View.VISIBLE
        binding.tvVerifiedBadge.visibility = View.VISIBLE
        binding.tvVerifiedBy.visibility = View.VISIBLE
    }

    // ── 1900 "Gặp tổng đài viên": the verified screen rings, nobody answers, back to the booking page ──
    private var agentCallJob: kotlinx.coroutines.Job? = null

    fun startAgentCall() {
        if (agentCallJob?.isActive == true) return
        val c = CertifiedNumbers.of(remoteUser)
        agentCallJob = lifecycleScope.launch(Dispatchers.Main) {
            try {
                if (c != null) showCertified(c)
                binding.tvBigNumber.text = "Tổng đài viên · $remoteUser"
                binding.tvBigNumber.visibility = View.VISIBLE
                binding.tvVerifiedBy.text = "Đang gọi tổng đài viên…"
                binding.tvVerifiedBy.visibility = View.VISIBLE
                binding.webviewMiniapp.visibility = View.INVISIBLE
                binding.viewBackgroundPlaceholder.visibility = View.VISIBLE
                binding.layoutMiddleDetails.visibility = View.VISIBLE
                binding.layoutMiddleDetails.setOnClickListener { agentRingCancelled = true }
                agentRingCancelled = false
                Log.i(TAG, "Agent call: ringing")
                startRingbackTone()
                var waited = 0L
                while (waited < AGENT_RING_MS && !agentRingCancelled) { delay(250); waited += 250 }
                stopRingbackTone()
                binding.tvVerifiedBy.text = "Tổng đài viên không trả lời"
                Log.i(TAG, "Agent call: no answer (cancelled=$agentRingCancelled)")
                delay(AGENT_NO_ANSWER_MS)
            } finally {
                stopRingbackTone()
                binding.layoutMiddleDetails.setOnClickListener(null)
                binding.layoutMiddleDetails.isClickable = false
                binding.tvBigNumber.text = remoteUser
                binding.tvVerifiedBy.text = "Xác thực bởi nhà mạng"
                binding.layoutMiddleDetails.visibility = View.GONE
                binding.viewBackgroundPlaceholder.visibility = View.GONE
                binding.webviewMiniapp.visibility = View.VISIBLE
            }
        }
    }
    @Volatile private var agentRingCancelled = false

    private fun startAvatarPulse() {
        val ring = binding.viewAvatarPulse
        val scaleX = android.animation.ObjectAnimator.ofFloat(ring, View.SCALE_X, 0.85f, 1.35f)
        val scaleY = android.animation.ObjectAnimator.ofFloat(ring, View.SCALE_Y, 0.85f, 1.35f)
        val alpha = android.animation.ObjectAnimator.ofFloat(ring, View.ALPHA, 1f, 0f)
        listOf(scaleX, scaleY, alpha).forEach {
            it.repeatCount = android.animation.ValueAnimator.INFINITE
            it.duration = 2000
        }
        android.animation.AnimatorSet().apply { playTogether(scaleX, scaleY, alpha); start() }
    }

    private fun setSpeakerphoneOn(audioManager: android.media.AudioManager, on: Boolean) {
        isSpeakerMode = on
        setCallButtonOn(binding.btnSpeakerCircle, on)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (on) {
                val devices = audioManager.availableCommunicationDevices
                val speaker = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    private var agentMediaPlayer: android.media.MediaPlayer? = null

    fun playAgentVoiceAudio() {
        try {
            agentMediaPlayer?.stop()
            agentMediaPlayer?.release()
            agentMediaPlayer = null

            // From the smart-menu package downloaded from the DCAR (no pages are bundled in the APK).
            val greeting = java.io.File(cacheDir, "miniapps/smart-menu/agent_greeting.mp3")
            if (!greeting.exists()) { Log.w(TAG, "agent_greeting.mp3 not downloaded yet, skipping"); return }
            agentMediaPlayer = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build()
                )
                setDataSource(greeting.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    it.release()
                    agentMediaPlayer = null
                }
            }
            Log.i(TAG, "🔊 Native MediaPlayer playing agent_greeting.mp3 successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing native agent voice audio: ${e.message}", e)
        }
    }
}

