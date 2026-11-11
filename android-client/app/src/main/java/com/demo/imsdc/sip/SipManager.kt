package com.demo.imsdc.sip

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import com.demo.imsdc.media.RtpPorts
import com.demo.imsdc.notification.IncomingCallNotificationManager
import com.demo.imsdc.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.net.InetAddress
import java.net.Socket
import java.net.InetSocketAddress
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private const val TAG = "SipManager"
// Wait for a new network to settle before comparing IPs, so a short flap does not end the call.
private const val IP_CHECK_DELAY_MS = 1500L
/** Registration lifetime asked for in REGISTER, and when it is renewed (half way, so a lost refresh still has time). */
private const val REGISTER_EXPIRES_S = 7200
private const val REGISTER_REFRESH_MS = REGISTER_EXPIRES_S * 1000L / 2
/** No 200 OK to a REGISTER within this time: the connection is taken as dead and reopened. */
private const val REGISTER_REPLY_TIMEOUT_MS = 4000L

/**
 * SipManager — SIP stack tối giản cho PoC IMS DC
 *
 * Triển khai:
 *  - REGISTER: đăng ký với SIP Server (Erlang) qua UDP
 *  - INVITE: khởi tạo cuộc gọi, nhúng SDP DataChannel
 *  - Lắng nghe response: 100 Trying, 200 OK, 404, v.v.
 *
 * Không implement:
 *  - Authentication (407/401) — PoC không cần
 *  - TCP transport
 *  - Re-REGISTER timer
 *
 * Mối quan hệ với WebRTC:
 *  WebRtcManager tạo SDP, trích xuất m=application section,
 *  sau đó SipManager nhúng vào SIP INVITE body.
 */
class SipManager(
    val sipServerHost: String,
    private val sipServerPort: Int = 5060,
    private val localUsername: String,
    private var localPort: Int = 0,
    private val password: String = "password"
) {
    companion object {
        var shared: SipManager? = null
        var appContext: Context? = null

        /** The SIP manager for [username] on [server], as the app sets it up (password "password<user>"). */
        fun create(server: String, username: String) = SipManager(
            sipServerHost = server,
            sipServerPort = 5060,
            localUsername = username,
            localPort = 0,
            password = "password$username"
        )
    }

    // ─── Callbacks ──────────────────────────────────────────────────────────
    var onRegistered: (() -> Unit)? = null
    var onInviteResponse: ((statusCode: Int, sdpBody: String?, toHeader: String, rawSdp: String?) -> Unit)? = null
    // rawMsg: bản tin INVITE gốc — B cần để build 200 OK response
    var onIncomingInvite: ((fromUser: String, sdpBody: String, callId: String, rawMsg: String, rawSdp: String?) -> Unit)? = null
    var onCallEnded: (() -> Unit)? = null
    var onCancelReceived: (() -> Unit)? = null
    var onAckWithSdp: ((sdpBody: String, rawSdp: String) -> Unit)? = null
    var onReInviteResponse: ((cseq: Int, sdpAnswer: String?, rawSdp: String?) -> Unit)? = null
    // The phone's IP changed during a call: SIP has moved to the new IP, the UI must end the call.
    var onIpChangedDuringCall: (() -> Unit)? = null

    // ─── State ──────────────────────────────────────────────────────────────
    private var appContext: Context? = null
    private var tcpSocket: Socket? = null
    private var tcpReader: BufferedReader? = null
    private var tcpWriter: OutputStream? = null
    var isRunning = false
        private set
    private val cseqCounter = AtomicInteger(1)
    private var lastLocalIp: String = ""
    private var lastTargetNetwork: android.net.Network? = null
    private var registerScope: CoroutineScope? = null
    private var initialRegRetryJob: kotlinx.coroutines.Job? = null
    private var authRetryJob: kotlinx.coroutines.Job? = null
    var activeToHeader: String? = null
    var homeReqApp: String? = null
    var appReqApp: String? = null     // mid 4; null while no app is open
    var appSlotUsed: Boolean = false  // mid 4 has appeared in this call; it stays in every SDP from then on
    /** The App line carries the content-share stream (a=dcmap:1002) while a Content Sharing photo session is open. */
    @Volatile var shareStream: Boolean = false
    /** The shared-content video/audio pair (a=content:slides) of a Content Sharing video session. */
    @Volatile var slides: SlidesState = SlidesState.OFF

    /** A provisional (180) response to one of our Re-INVITEs: the far end is asking its user. */
    var onReInviteProvisional: ((code: Int, cseq: Int) -> Unit)? = null
    /** The far end withdrew the Re-INVITE we were holding for a decision (CANCEL); the call stays up. */
    var onHeldReInviteCancelled: (() -> Unit)? = null
    /** Incoming Re-INVITE whose answer is deferred (user decision pending); only one at a time. */
    @Volatile private var heldReInviteMsg: String? = null
    var isCallHoldActive: Boolean = false
    var onReInviteReceived: ((String, String) -> Unit)? = null
    var onReInviteFailed: ((code: Int, cseq: Int) -> Unit)? = null
    /** True when this side sent the initial INVITE (owns the Call-ID), RFC 3261 14.1. */
    @Volatile var isCallIdOwner = false
    // CSeq of the initial INVITE — responses with higher CSeq are Re-INVITE responses
    private var initialInviteCSeq: Int = -1
    var lastReInviteCSeq: Int = -1
    var isCallActive: Boolean = false
        private set
    private val sipScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var ipCheckJob: kotlinx.coroutines.Job? = null
    /** Renews the registration before it expires: during a long call no screen does it (MainActivity only on resume). */
    private var registerRefreshJob: kotlinx.coroutines.Job? = null
    private var registerWatchdogJob: kotlinx.coroutines.Job? = null
    /** When the last REGISTER 200 OK arrived (System.currentTimeMillis). */
    @Volatile var lastRegisterOkMs = 0L
        private set
    /** A reconnect started by [forceReconnect] is in progress (cleared by the next REGISTER OK, or after 30 s). */
    private val reconnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var reconnectStartedMs = 0L
    // IP announced in the current call's SDP; the call's RTP is tied to it.
    private var callLocalIp: String? = null

    private fun getTag(headerVal: String): String? {
        val idx = headerVal.indexOf(";tag=")
        if (idx != -1) {
            val sub = headerVal.substring(idx + 5).trim()
            return sub.substringBefore(";").substringBefore(" ").trim()
        }
        return null
    }
    private var keepAliveThread: Thread? = null

    // Lưu thông tin cuộc gọi đang pending (để gửi ACK sau 200 OK)
    private var pendingCallId: String? = null
    private var pendingFromTag: String? = null
    val currentCallId: String?
        get() = pendingCallId
    val currentFromTag: String?
        get() = pendingFromTag
    private var pendingBranch: String? = null
    private var pendingRemoteUser: String? = null
    private var pendingCSeq: Int = 1
    var lastLocalOfferSdp: String? = null
    var lastIncomingInviteMsg: String? = null

    // ─── Khởi động ──────────────────────────────────────────────────────────

    /**
     * Checks whether the provided string is a valid IPv4 address (excluding 0.0.0.0 and loopback).
     */
    private fun isValidIpv4(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        if (ip.contains(":") || ip == "0.0.0.0" || ip == "127.0.0.1" || ip == "::") return false
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }

    /**
     * Gets the definitive local IP address used for SIP and RTP communications.
     * Prioritizes the bound local address of the connected TCP socket if it is a valid IPv4 address.
     * Fallbacks to kernel routing probe or active network IPv4 address.
     */
    fun getLocalIp(): String {
        val socketIp = tcpSocket?.localAddress?.hostAddress
        if (isValidIpv4(socketIp)) {
            lastLocalIp = socketIp!!
            return socketIp
        }
        if (isValidIpv4(lastLocalIp)) {
            return lastLocalIp
        }
        val probed = NetworkUtils.getLocalIpAddress(appContext, sipServerHost)
        if (isValidIpv4(probed)) {
            lastLocalIp = probed
            return probed
        }
        return "127.0.0.1"
    }

    fun start(context: android.content.Context? = null) {
        if (context != null) {
            appContext = context.applicationContext
            Companion.appContext = context.applicationContext
        }
        if (isRunning) {
            Log.d(TAG, "SipManager is already running on port $localPort")
            return
        }
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(sipServerHost, sipServerPort), 10000)
            tcpSocket = socket
            tcpReader = BufferedReader(InputStreamReader(socket.inputStream))
            tcpWriter = socket.outputStream
            localPort = socket.localPort
            val rawIp = socket.localAddress?.hostAddress
            val localIp = if (isValidIpv4(rawIp)) rawIp!! else NetworkUtils.getLocalIpAddress(appContext, sipServerHost)
            lastLocalIp = localIp
            isRunning = true
            Log.i(TAG, "SIP TCP socket opened on port $localPort with local IP $localIp")
            startReceiveLoop()
            startKeepAliveLoop()
            watchNetwork()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open TCP connection to $sipServerHost:$sipServerPort: ${e.message}")
            throw e
        }
    }

    private fun reconnect() {
        try {
            tcpSocket?.close()
        } catch (e: Exception) {}
        
        tcpWriter = null
        tcpReader = null
        
        try { Thread.sleep(2000) } catch (e: Exception) {}
        
        if (!isRunning) return
        
        try {
            Log.i(TAG, "Reconnecting SIP TCP socket to $sipServerHost:$sipServerPort")
            val socket = Socket()
            socket.connect(InetSocketAddress(sipServerHost, sipServerPort), 10000)
            tcpSocket = socket
            tcpWriter = socket.outputStream
            tcpReader = BufferedReader(InputStreamReader(socket.inputStream))
            localPort = socket.localPort
            val rawIp = socket.localAddress?.hostAddress
            val localIp = if (isValidIpv4(rawIp)) rawIp!! else NetworkUtils.getLocalIpAddress(appContext, sipServerHost)
            lastLocalIp = localIp
            Log.i(TAG, "SIP TCP socket reconnected with local IP $localIp on port $localPort!")
            startReceiveLoop()
            startKeepAliveLoop()
            
            sipScope.launch(Dispatchers.IO) {
                register(lastLocalIp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reconnect failed: ${e.message}")
        }
    }

    private fun scheduleRegisterRefresh() {
        registerRefreshJob?.cancel()
        registerRefreshJob = sipScope.launch {
            delay(REGISTER_REFRESH_MS)
            if (!isRunning) return@launch
            Log.i(TAG, "Renewing SIP registration (expires after ${REGISTER_EXPIRES_S}s)")
            try { register() } catch (e: Exception) { Log.w(TAG, "Registration refresh failed: ${e.message}") }
        }
    }

    /** Re-registers; if the connection turns out dead (no reply), the REGISTER watchdog reconnects. */
    fun ensureConnected(reason: String) {
        if (!isRunning) return
        Log.i(TAG, "ensureConnected: $reason")
        sipScope.launch {
            try { register() } catch (e: Exception) { Log.w(TAG, "ensureConnected register failed: ${e.message}") }
        }
    }

    /** Drops the current connection and opens a new one (which registers again). One at a time. */
    private fun forceReconnect(reason: String) {
        val now = System.currentTimeMillis()
        if (reconnecting.get() && now - reconnectStartedMs < 30_000) return
        reconnecting.set(true)
        reconnectStartedMs = now
        Log.w(TAG, "$reason: connection is dead, reconnecting")
        initialRegRetryJob?.cancel()
        authRetryJob?.cancel()
        sipScope.launch(Dispatchers.IO) {
            synchronized(this@SipManager) {
                // Detach the old socket first: its receive loop then sees a different socket and exits
                // instead of starting a second reconnect when the close makes its read fail.
                val old = tcpSocket
                tcpSocket = null
                try { old?.close() } catch (_: Exception) {}
                reconnect()
            }
        }
    }

    /** Registers again right away (used when the server says it lost our registration). */
    fun reRegisterNow(reason: String) {
        if (!isRunning) return
        Log.w(TAG, "Re-registering: $reason")
        sipScope.launch {
            try { register() } catch (e: Exception) { Log.w(TAG, "Re-register failed: ${e.message}") }
        }
    }

    fun stop() {
        registerRefreshJob?.cancel()
        registerRefreshJob = null
        registerWatchdogJob?.cancel()
        registerWatchdogJob = null
        isRunning = false
        unwatchNetwork()
        keepAliveThread?.interrupt()
        keepAliveThread = null
        initialRegRetryJob?.cancel()
        initialRegRetryJob = null
        authRetryJob?.cancel()
        authRetryJob = null
        registerScope?.cancel()
        registerScope = null
        try {
            sipScope.cancel()
        } catch (e: Exception) {}
        try { tcpSocket?.close() } catch (e: Exception) {}
        tcpSocket = null
        tcpReader = null
        tcpWriter = null
        Log.i(TAG, "SIP Manager stopped")
    }

    private fun startKeepAliveLoop() {
        keepAliveThread?.interrupt()
        val currentSocket = tcpSocket
        keepAliveThread = Thread {
            Log.d(TAG, "SIP Keep-Alive thread started")
            while (isRunning && tcpSocket === currentSocket) {
                try {
                    Thread.sleep(3000)
                    if (!isRunning || tcpSocket !== currentSocket) break
                    sendRaw("\r\n\r\n")
                    Log.v(TAG, "→ Sent SIP TCP keep-alive (double CRLF)")
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Keep-alive send error: ${e.message}")
                }
            }
            Log.d(TAG, "SIP Keep-Alive thread stopped")
        }.apply {
            isDaemon = true
            name = "SIP-KeepAlive"
            start()
        }
    }

    fun getLocalPort(): Int = localPort

    // ─── Network change ──────────────────────────────────────────────────────

    /**
     * Watches the default network. When the IP used to reach the SIP server changes
     * (Wi-Fi <-> mobile data, new carrier IP, VPN reconnect), the SIP TCP connection and the
     * call's RTP are still tied to the old IP. SIP reconnects and REGISTERs from the new IP,
     * and a call in progress is ended cleanly instead of being left frozen.
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = (appContext ?: Companion.appContext)
            ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleIpCheck()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = scheduleIpCheck()
            override fun onLost(network: Network) = scheduleIpCheck()
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Cannot watch network changes: ${e.message}")
        }
    }

    private fun unwatchNetwork() {
        ipCheckJob?.cancel()
        ipCheckJob = null
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            val cm = (appContext ?: Companion.appContext)
                ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
    }

    private fun scheduleIpCheck() {
        ipCheckJob?.cancel()
        ipCheckJob = sipScope.launch {
            delay(IP_CHECK_DELAY_MS)
            checkLocalIp()
        }
    }

    private suspend fun checkLocalIp() {
        if (!isRunning) return
        val newIp = NetworkUtils.getLocalIpAddress(appContext ?: Companion.appContext, sipServerHost)
        // No usable network yet (e.g. right after onLost): wait for the next callback.
        if (!isValidIpv4(newIp)) return

        val sipIp = lastLocalIp
        val callIp = callLocalIp
        val sipMoved = isValidIpv4(sipIp) && newIp != sipIp
        // Checked separately: a failed keep-alive may already have moved SIP to the new IP.
        val callMoved = pendingCallId != null && isValidIpv4(callIp) && newIp != callIp
        if (!sipMoved && !callMoved) return

        Log.w(TAG, "Local IP changed to $newIp (SIP was $sipIp, call was $callIp)" +
            (if (sipMoved) ": reconnecting SIP and re-registering" else "") +
            (if (callMoved) ", ending the call" else ""))
        if (sipMoved && reconnectFromNewIp()) {
            register()
        }

        if (callMoved) {
            val endCall = onIpChangedDuringCall
            if (endCall != null) {
                endCall()
            } else {
                bye(pendingRemoteUser ?: "unknown")
            }
        }
    }

    /** Replaces the SIP TCP connection, still bound to the old IP, with one from the current network. */
    private fun reconnectFromNewIp(): Boolean = synchronized(this) {
        val oldSocket = tcpSocket
        // The old receive and keep-alive loops see a different socket and exit without reconnecting.
        tcpSocket = null
        tcpReader = null
        tcpWriter = null
        try { oldSocket?.close() } catch (_: Exception) {}

        repeat(3) { attempt ->
            if (!isRunning) return false
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(sipServerHost, sipServerPort), 5000)
                tcpSocket = socket
                tcpReader = BufferedReader(InputStreamReader(socket.inputStream))
                tcpWriter = socket.outputStream
                localPort = socket.localPort
                val rawIp = socket.localAddress?.hostAddress
                lastLocalIp = if (isValidIpv4(rawIp)) rawIp!! else NetworkUtils.getLocalIpAddress(appContext, sipServerHost)
                Log.i(TAG, "SIP TCP socket reconnected from new IP $lastLocalIp on port $localPort")
                startReceiveLoop()
                startKeepAliveLoop()
                return true
            } catch (e: Exception) {
                Log.w(TAG, "SIP reconnect after IP change failed (attempt ${attempt + 1}): ${e.message}")
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
        }
        false
    }

    // ─── Receive Loop ────────────────────────────────────────────────────────

    private fun startReceiveLoop() {
        val currentSocket = tcpSocket
        Thread {
            while (isRunning && tcpSocket === currentSocket) {
                val reader = tcpReader
                if (reader == null) {
                    try { Thread.sleep(1000) } catch (e: Exception) {}
                    continue
                }
                try {
                    var line = reader.readLine()
                    if (line == null) {
                        Log.e(TAG, "TCP connection closed by server")
                        if (isRunning && tcpSocket === currentSocket) {
                            reconnect()
                        }
                        break
                    }
                    // Skip leading CRLF keep-alives
                    while (line != null && line.isEmpty()) {
                        line = reader.readLine()
                    }
                    if (line == null) {
                        Log.e(TAG, "TCP connection closed by server")
                        if (isRunning && tcpSocket === currentSocket) {
                            reconnect()
                        }
                        break
                    }
                    val headers = java.lang.StringBuilder()
                    var contentLength = 0
                    while (line != null && line.isNotEmpty()) {
                        headers.append(line).append("\r\n")
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                        line = reader.readLine()
                    }
                    headers.append("\r\n")
                    
                    val body = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = reader.read(body, read, contentLength - read)
                        if (count == -1) break
                        read += count
                    }
                    
                    val msg = headers.toString() + String(body)
                    Log.d(TAG, "← Received:\n$msg")
                    handleIncomingMessage(msg)
                } catch (e: Exception) {
                    if (isRunning && tcpSocket === currentSocket) {
                        Log.e(TAG, "Receive error: ${e.message}")
                        reconnect()
                    }
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "SIP-Receive"
            start()
        }
    }

    private fun handleIncomingMessage(msg: String) {
        when {
            // SIP Response
            msg.startsWith("SIP/2.0") -> handleResponse(msg)
            // Incoming INVITE (B-side)
            msg.startsWith("INVITE") -> handleIncomingInvite(msg)
            // CANCEL
            msg.startsWith("CANCEL") -> handleCancel(msg)
            // ACK
            msg.startsWith("ACK") -> {
                Log.i(TAG, "← ACK received")
                val sdp = SdpHelper.extractSdpBody(msg)
                if (sdp != null) {
                    val translatedAnswer = SdpHelper.translateSipAnswerToWebRtcAnswer(sdp)
                    onAckWithSdp?.invoke(translatedAnswer, sdp)
                }
            }
            // BYE
            msg.startsWith("BYE") -> handleBye(msg)
            else -> Log.w(TAG, "Unknown SIP message: ${msg.take(50)}")
        }
    }

    // ─── Handle Response ─────────────────────────────────────────────────────

    private fun getCSeqNumber(msg: String): Int {
        val cseqHeader = getHeader(msg, "cseq") ?: return -1
        return cseqHeader.trim().split("\\s+".toRegex()).firstOrNull()?.toIntOrNull() ?: -1
    }

    private fun getCSeqMethod(msg: String): String {
        val cseqHeader = getHeader(msg, "cseq") ?: return ""
        return cseqHeader.trim().split("\\s+".toRegex()).lastOrNull()?.uppercase() ?: ""
    }

    private fun isReInviteResponse(msg: String): Boolean {
        if (initialInviteCSeq <= 0) return false
        val cseqNum = getCSeqNumber(msg)
        // Callee-initiated re-INVITE: the callee uses its OWN independent CSeq counter, so
        // the 200 OK for its own re-INVITE may have CSeq <= initialInviteCSeq. It is still a
        // re-INVITE response because it echoes lastReInviteCSeq (the CSeq we just sent).
        return cseqNum > initialInviteCSeq ||
            (lastReInviteCSeq > 0 && cseqNum == lastReInviteCSeq)
    }

    private fun handleResponse(msg: String) {
        val statusCode = parseStatusCode(msg)
        val sdp = SdpHelper.extractSdpBody(msg)
        val cseqMethod = getCSeqMethod(msg)
        Log.i(TAG, "← Response: $statusCode (CSeq method=$cseqMethod)")

        // Ignore REGISTER responses in the INVITE callback path
        if (cseqMethod == "REGISTER") {
            when (statusCode) {
                200 -> {
                    Log.i(TAG, "✅ REGISTER OK")
                    lastRegisterOkMs = System.currentTimeMillis()
                    reconnecting.set(false)
                    initialRegRetryJob?.cancel()
                    authRetryJob?.cancel()
                    scheduleRegisterRefresh()
                    onRegistered?.invoke()
                }
                401 -> {
                    Log.i(TAG, "← 401 Unauthorized — handling digest auth challenge")
                    initialRegRetryJob?.cancel()
                    handleAuthChallenge(msg)
                }
                else -> Log.w(TAG, "REGISTER response: $statusCode (ignored)")
            }
            return
        }

        // INVITE / Re-INVITE responses
        if (cseqMethod == "INVITE") {
            val isReInvite = isReInviteResponse(msg)

            when (statusCode) {
                100 -> Log.d(TAG, "100 Trying — waiting for 200 OK")
                200 -> {
                    if (isReInvite) {
                        val toHeaderVal = getHeader(msg, "to") ?: ""
                        val cseqVal = getCSeqNumber(msg)
                        
                        if (cseqVal < lastReInviteCSeq) {
                            Log.w(TAG, "Ignoring stale Re-INVITE 200 OK (CSeq $cseqVal < $lastReInviteCSeq)")
                            sipScope.launch {
                                sendAck(toHeaderVal, cseqOverride = cseqVal)
                            }
                            return
                        }

                        Log.i(TAG, "✅ Re-INVITE 200 OK — sending ACK")
                        activeToHeader = toHeaderVal
                        val translatedAnswer = sdp?.let { SdpHelper.translateSipAnswerToWebRtcAnswer(it, lastLocalOfferSdp) }
                        onReInviteResponse?.invoke(cseqVal, translatedAnswer, sdp)
                        sipScope.launch {
                            sendAck(toHeaderVal, cseqOverride = cseqVal)
                        }
                    } else {
                        Log.i(TAG, "✅ INVITE 200 OK — forwarding to caller")
                        isCallActive = true
                        val toHeaderVal = getHeader(msg, "to") ?: ""
                        activeToHeader = toHeaderVal // Store toHeaderVal including the callee's to-tag
                        val translatedAnswer = sdp?.let { SdpHelper.translateSipAnswerToWebRtcAnswer(it, lastLocalOfferSdp) }
                        onInviteResponse?.invoke(200, translatedAnswer, toHeaderVal, sdp)
                    }
                }
                401 -> {
                    Log.i(TAG, "← 401 Unauthorized — handling digest auth challenge")
                    handleAuthChallenge(msg)
                }
                in 100..199 -> {
                    // 180 Ringing, 183 Session Progress (Early Media) etc. — only forward for initial INVITE
                    if (!isReInvite) {
                        val sdp = SdpHelper.extractSdpBody(msg)
                        val toHeaderVal = getHeader(msg, "to") ?: ""
                        onInviteResponse?.invoke(statusCode, sdp, toHeaderVal, sdp)
                    } else {
                        Log.d(TAG, "Re-INVITE provisional $statusCode")
                        onReInviteProvisional?.invoke(statusCode, getCSeqNumber(msg))
                    }
                }
                else -> {
                    if (isReInvite) {
                        // Re-INVITE failures should NOT terminate the ongoing call
                        Log.w(TAG, "Re-INVITE failed with $statusCode — call continues")
                        // 430: the server no longer has our registration (expired, or server restarted). Register
                        // again now; the caller retries the Re-INVITE once (MiniAppManager).
                        if (statusCode == 430) reRegisterNow("430 on Re-INVITE")
                        onReInviteFailed?.invoke(statusCode, getCSeqNumber(msg))
                        // 481: the other side no longer has this call (it hung up and its BYE was lost or
                        // crossed): the call is over here too (RFC 3261 12.2.1.2).
                        if (statusCode == 481) {
                            Log.w(TAG, "Re-INVITE got 481: the other side has ended the call, ending it here")
                            resetCallState()
                            onCallEnded?.invoke()
                        }
                    } else {
                        Log.w(TAG, "INVITE response: $statusCode")
                        onInviteResponse?.invoke(statusCode, null, "", null)
                    }
                }
            }
            return
        }

        // BYE responses etc.
        when (statusCode) {
            401 -> {
                Log.i(TAG, "← 401 Unauthorized — handling digest auth challenge")
                handleAuthChallenge(msg)
            }
            else -> Log.d(TAG, "Response $statusCode for $cseqMethod — no handler")
        }
    }

    // ─── Handle Incoming INVITE (B receives call) ────────────────────────────

    private fun handleIncomingInvite(msg: String) {
        val fromUser = getFromUsername(msg) ?: "unknown"
        val callId   = getHeader(msg, "call-id") ?: randomCallId()
        val sdp      = SdpHelper.extractSdpBody(msg)

        Log.i(TAG, "← Incoming INVITE from $fromUser (call=$callId)")

        // A new call while one is ringing or up, or a call to ourselves: 486 Busy Here.
        // Only an INVITE without a To-tag starts a new call (RFC 3261 12.1); one with a To-tag is a
        // re-INVITE of the current call, whatever Call-ID it carries, and must never be turned away.
        // Checked before the pending* fields below are overwritten, so the current call keeps its state.
        val newDialog = getTag(getHeader(msg, "to") ?: "").isNullOrEmpty()
        val inCall = com.demo.imsdc.ui.CallActivity.isCallActive || isCallActive ||
            (pendingCallId != null && IncomingCallNotificationManager.ringing)
        val busyReason = when {
            !newDialog -> null
            fromUser == localUsername -> "call to self"
            inCall && callId != pendingCallId -> "already in a call (${pendingCallId})"
            else -> null
        }
        if (busyReason != null) {
            Log.i(TAG, "→ 486 Busy Here to $fromUser (call=$callId): $busyReason")
            try {
                sendRaw(buildResponse(msg, 486, "Busy Here", "Content-Length: 0\r\n"))
                busyCallIds.add(callId)
            } catch (e: Exception) {
                Log.e(TAG, "486 send error: ${e.message}")
            }
            return
        }

        // An INVITE with a To-tag belongs to an existing dialog (a re-INVITE: hold, mute, opening an app).
        // With no call up, that dialog is gone (the other side already hung up, or its BYE crossed this
        // re-INVITE): answer 481 (RFC 3261 12.2.2) instead of treating it as a new call and ringing.
        if (!newDialog && !inCall) {
            Log.i(TAG, "→ 481 to a re-INVITE from $fromUser for a call that has ended (call=$callId)")
            try { sendRaw(buildResponse(msg, 481, "Call/Transaction Does Not Exist", "Content-Length: 0\r\n")) } catch (e: Exception) {
                Log.e(TAG, "481 send error: ${e.message}")
            }
            return
        }

        // Save call info for callee (B-side) to send BYE or ACK
        val from     = getHeader(msg, "from") ?: ""
        val to       = getHeader(msg, "to") ?: ""
        pendingCallId = callId
        pendingRemoteUser = fromUser
        activeToHeader = from
        val toTag = getTag(to)
        pendingFromTag = if (!toTag.isNullOrEmpty()) toTag else randomTag()

        // Detect Re-INVITE based on active call and matching Call-ID
        val isReInvite = isCallActive && !pendingCallId.isNullOrEmpty() && callId == pendingCallId

        val incomingCSeq = getCSeqNumber(msg)
        if (initialInviteCSeq <= 0 && incomingCSeq > 0) {
            initialInviteCSeq = incomingCSeq
        }

        if (isReInvite) {
            Log.i(TAG, "← Incoming Re-INVITE for Call-ID $callId (CSeq $incomingCSeq)")
            val trying = buildResponse(msg, 100, "Trying", "")
            try {
                sendRaw(trying)
            } catch (e: Exception) {
                Log.e(TAG, "Trying send error for Re-INVITE: ${e.message}")
            }
            val translatedSdp = sdp?.let { SdpHelper.translateSipAnswerToWebRtcAnswer(it) } ?: ""
            onReInviteReceived?.invoke(translatedSdp, msg)
            return
        }

        // Initial INVITE: the far end created the Call-ID. Not cleared for a Re-INVITE, which would make
        // both sides use the 0-2 s glare back-off (RFC 3261 14.1).
        isCallIdOwner = false

        // Gửi 100 Trying ngay lập tức
        lastIncomingInviteMsg = msg
        val trying = buildResponse(msg, 100, "Trying", "")
        try {
            sendRaw(trying)
        } catch (e: Exception) {
            Log.e(TAG, "Trying send error: ${e.message}")
        }

        // Gửi 180 Ringing ngay lập tức
        val ringing = buildResponse(msg, 180, "Ringing", "")
        try {
            sendRaw(ringing)
            Log.i(TAG, "→ Sent SIP 180 Ringing")
        } catch (e: Exception) {
            Log.e(TAG, "Ringing send error: ${e.message}")
        }

        val translatedSdp = sdp?.let { SdpHelper.translateSipAnswerToWebRtcAnswer(it) } ?: ""

        // Kích hoạt IncomingCallNotificationManager độc lập để bật màn hình, rung chuông và hiển thị FullScreen notification
        val ctx = appContext ?: Companion.appContext
        val activeLocalPort = getLocalPort()
        val portToUse = if (activeLocalPort > 0) activeLocalPort else localPort
        val effectiveIp = getLocalIp()
        if (ctx != null) {
            IncomingCallNotificationManager.show(
                context = ctx,
                sipServer = sipServerHost,
                localUser = localUsername,
                remoteUser = fromUser,
                localIp = effectiveIp,
                localPort = portToUse,
                sipPort = sipServerPort,
                incomingInvite = msg,
                incomingSdp = translatedSdp,
                incomingCallId = callId,
                incomingRawSdp = sdp
            )
        } else {
            Log.w(TAG, "IncomingCallNotificationManager skipped: appContext is null")
        }

        // Thông báo lên UI/logic layer, kèm theo rawMsg để B có thể gửi 200 OK
        onIncomingInvite?.invoke(fromUser, translatedSdp, callId, msg, sdp)
    }

    // ─── Handle CANCEL ───────────────────────────────────────────────────────

    private fun handleCancel(msg: String) {
        Log.i(TAG, "← CANCEL received")
        if (isOtherCall(msg)) {
            try { sendRaw(buildResponse(msg, 200, "OK", "")) } catch (_: Exception) {}
            Log.i(TAG, "CANCEL for a call answered busy: 200 OK, current call untouched")
            return
        }
        // CANCEL of a Re-INVITE we hold for the user: 200 to the CANCEL, 487 to the Re-INVITE, the call goes on.
        val held = heldReInviteMsg
        if (held != null && getCSeqNumber(held) == getCSeqNumber(msg)) {
            heldReInviteMsg = null
            try {
                sendRaw(buildResponse(msg, 200, "OK", ""))
                sendRaw(buildResponse(held, 487, "Request Terminated", "Content-Length: 0\r\n"))
                Log.i(TAG, "→ 200 OK for CANCEL and 487 for the held Re-INVITE; call continues")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to answer CANCEL of a Re-INVITE: ${e.message}")
            }
            onHeldReInviteCancelled?.invoke()
            return
        }
        // 1. Phản hồi 200 OK cho bản tin CANCEL
        val cancelOk = buildResponse(msg, 200, "OK", "")
        try {
            sendRaw(cancelOk)
            Log.i(TAG, "→ Sent 200 OK for CANCEL")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send 200 OK for CANCEL: ${e.message}")
        }

        // 2. Phản hồi 487 Request Terminated cho INVITE ban đầu
        val terminated = buildResponse(msg, 487, "Request Terminated", "")
        try {
            sendRaw(terminated)
            Log.i(TAG, "→ Sent 487 Request Terminated for INVITE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send 487 for INVITE: ${e.message}")
        }

        // 3. Tắt chuông & hủy thông báo cuộc gọi đến
        val ctx = appContext ?: Companion.appContext
        ctx?.let {
            IncomingCallNotificationManager.dismiss(it)
        }

        resetCallState()
        onCancelReceived?.invoke()
        onCallEnded?.invoke()
    }

    /** Call-IDs answered with 486 Busy Here. */
    private val busyCallIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** A CANCEL/BYE of a call we turned away busy: it must not end the current call. */
    private fun isOtherCall(msg: String): Boolean {
        val id = getHeader(msg, "call-id") ?: return false
        return busyCallIds.remove(id)
    }

    // ─── Handle BYE ─────────────────────────────────────────────────────────

    private fun handleBye(msg: String) {
        Log.i(TAG, "← BYE received")
        if (isOtherCall(msg)) {
            try { sendRaw(buildResponse(msg, 481, "Call/Transaction Does Not Exist", "")) } catch (_: Exception) {}
            Log.i(TAG, "BYE for a call answered busy: 481, current call untouched")
            return
        }
        val response = buildResponse(msg, 200, "OK", "")
        try {
            sendRaw(response)
        } catch (e: Exception) {
            Log.e(TAG, "BYE OK response send error: ${e.message}")
        }

        val ctx = appContext ?: Companion.appContext
        ctx?.let {
            IncomingCallNotificationManager.dismiss(it)
        }

        resetCallState()
        onCallEnded?.invoke()
    }

    fun markCallEnding() {
        isCallActive = false
    }

    fun resetCallState() {
        isCallActive = false
        activeToHeader = null
        homeReqApp = null
        appReqApp = null
        appSlotUsed = false
        shareStream = false
        slides = SlidesState.OFF
        heldReInviteMsg = null
        isCallHoldActive = false
        initialInviteCSeq = -1
        lastReInviteCSeq = -1
        isCallIdOwner = false
        pendingCallId = null
        pendingFromTag = null
        pendingBranch = null
        pendingRemoteUser = null
        pendingCSeq = 1
        lastLocalOfferSdp = null
        callLocalIp = null
        Log.i(TAG, "SIP call state reset successfully")
    }

    // ─── REGISTER ────────────────────────────────────────────────────────────

    /**
     * Gửi SIP REGISTER tới Erlang SIP Server.
     * Phải gọi trước khi thực hiện cuộc gọi.
     */
    suspend fun register(localIp: String? = null) = withContext(Dispatchers.IO) {
        val effectiveIp = getLocalIp()
        lastLocalIp = effectiveIp
        val branch  = randomBranch()
        val tag     = randomTag()
        val callId  = randomCallId()
        val cseq    = cseqCounter.getAndIncrement()

        val msg = buildString {
            appendLine("REGISTER sip:$sipServerHost SIP/2.0")
            appendLine("Via: SIP/2.0/TCP $effectiveIp:$localPort;branch=$branch")
            appendLine("Max-Forwards: 70")
            appendLine("From: <sip:$localUsername@$sipServerHost>;tag=$tag")
            appendLine("To: <sip:$localUsername@$sipServerHost>")
            appendLine("Call-ID: $callId")
            appendLine("CSeq: $cseq REGISTER")
            appendLine("Contact: <sip:$localUsername@$effectiveIp:$localPort>;+sip.app-subtype=\"webrtc-datachannel\";+g.3gpp.ps-data-off")
            appendLine("Expires: $REGISTER_EXPIRES_S")
            appendLine("Content-Length: 0")
            append("\r\n")
        }.replace("\r\n", "\n").replace("\n", "\r\n")

        // A REGISTER that gets no answer means the TCP connection is dead (after sleep, a NAT drop, a server
        // restart): writes into a half-dead socket still "succeed", so only the missing reply shows it.
        val sentAt = System.currentTimeMillis()
        registerWatchdogJob?.cancel()
        registerWatchdogJob = sipScope.launch {
            delay(REGISTER_REPLY_TIMEOUT_MS)
            if (isRunning && lastRegisterOkMs < sentAt) forceReconnect("no reply to REGISTER in ${REGISTER_REPLY_TIMEOUT_MS / 1000} s")
        }

        initialRegRetryJob?.cancel()
        initialRegRetryJob = sipScope.launch(Dispatchers.IO) {
            var attempts = 0
            while (attempts < 5 && isRunning) {
                Log.i(TAG, "→ REGISTER for $localUsername (Attempt ${attempts + 1})")
                sendRaw(msg)
                attempts++
                delay(1000L) // Wait 1 second before retrying
            }
        }
    }

    // ─── Digest Auth Challenge Handler ────────────────────────────────────────

    private fun handleAuthChallenge(msg: String) {
        val wwwAuth = getHeader(msg, "www-authenticate") ?: run {
            Log.e(TAG, "401 but no WWW-Authenticate header found")
            return
        }

        val realm = Regex("""realm="([^"]+)"""").find(wwwAuth)?.groupValues?.get(1) ?: "ims"
        val nonce = Regex("""nonce="([^"]+)"""").find(wwwAuth)?.groupValues?.get(1) ?: ""

        Log.i(TAG, "Auth challenge: realm=$realm, nonce=$nonce")

        // Compute Digest MD5 response
        val uri = "sip:$sipServerHost"
        val ha1 = md5Hex("$localUsername:$realm:$password")
        val ha2 = md5Hex("REGISTER:$uri")
        val response = md5Hex("$ha1:$nonce:$ha2")

        val localIp = getLocalIp()
        lastLocalIp = localIp
        val branch  = randomBranch()
        val tag     = randomTag()
        val callId  = randomCallId()
        val cseq    = cseqCounter.getAndIncrement()

        val authMsg = buildString {
            appendLine("REGISTER sip:$sipServerHost SIP/2.0")
            appendLine("Via: SIP/2.0/TCP $localIp:$localPort;branch=$branch")
            appendLine("Max-Forwards: 70")
            appendLine("From: <sip:$localUsername@$sipServerHost>;tag=$tag")
            appendLine("To: <sip:$localUsername@$sipServerHost>")
            appendLine("Call-ID: $callId")
            appendLine("CSeq: $cseq REGISTER")
            appendLine("Contact: <sip:$localUsername@$localIp:$localPort>;+sip.app-subtype=\"webrtc-datachannel\";+g.3gpp.ps-data-off")
            appendLine("Expires: $REGISTER_EXPIRES_S")
            appendLine("Authorization: Digest username=\"$localUsername\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\", algorithm=MD5")
            appendLine("Content-Length: 0")
            append("\r\n")
        }.replace("\r\n", "\n").replace("\n", "\r\n")

        authRetryJob?.cancel()
        authRetryJob = sipScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (attempt < 5 && isRunning) {
                Log.i(TAG, "→ REGISTER with Authorization for $localUsername (Attempt ${attempt + 1})")
                try {
                    sendRaw(authMsg)
                } catch (e: Exception) {
                    Log.e(TAG, "Auth REGISTER send error: ${e.message}")
                }
                kotlinx.coroutines.delay(1000)
                attempt++
            }
        }
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ─── INVITE ──────────────────────────────────────────────────────────────

    /**
     * Gửi SIP INVITE tới toUser.
     *
     * @param toUser       Username của người được gọi
     * @param localIp      IP của thiết bị local (tùy chọn, nếu không truyền sẽ tự lấy từ socket)
     * @param sdpBody      SDP body bao gồm m=application section (từ WebRtcManager)
     */
    suspend fun invite(toUser: String, localIp: String? = null, sdpBody: String?): String =
        withContext(Dispatchers.IO) {
            val effectiveIp = getLocalIp()
            val branch  = randomBranch()
            val fromTag = randomTag()
            val callId  = randomCallId()
            val cseq    = cseqCounter.getAndIncrement()

            // Lưu lại để gửi ACK sau
            isCallIdOwner = true
            pendingCallId  = callId
            pendingFromTag = fromTag
            pendingBranch  = branch
            pendingRemoteUser = toUser
            pendingCSeq = cseq
            initialInviteCSeq = cseq  // Track initial INVITE CSeq for Re-INVITE detection
            lastLocalOfferSdp = sdpBody
            lastLocalIp = effectiveIp
            callLocalIp = effectiveIp

            val msg = if (!sdpBody.isNullOrEmpty()) {
                val translatedSdp = SdpHelper.translateWebRtcOfferToSipOffer(sdpBody, effectiveIp, homeReqApp = homeReqApp, isCallHoldActive = isCallHoldActive, localAudioPort = RtpPorts.current().first, localVideoPort = RtpPorts.current().second, appReqApp = appReqApp, appSlotUsed = appSlotUsed, shareStream = shareStream, slides = slides)
                val contentLength = translatedSdp.toByteArray(Charsets.UTF_8).size
                buildString {
                    appendLine("INVITE sip:$toUser@$sipServerHost SIP/2.0")
                    appendLine("Via: SIP/2.0/TCP $effectiveIp:$localPort;branch=$branch")
                    appendLine("Max-Forwards: 70")
                    appendLine("From: <sip:$localUsername@$sipServerHost>;tag=$fromTag")
                    appendLine("To: <sip:$toUser@$sipServerHost>")
                    appendLine("Call-ID: $callId")
                    appendLine("CSeq: $cseq INVITE")
                    appendLine("Contact: <sip:$localUsername@$effectiveIp:$localPort>;+sip.app-subtype=\"webrtc-datachannel\"")
                    appendLine("Content-Type: application/sdp")
                    appendLine("Content-Length: $contentLength")
                    append("\r\n")
                    append(translatedSdp)
                }
            } else {
                buildString {
                    appendLine("INVITE sip:$toUser@$sipServerHost SIP/2.0")
                    appendLine("Via: SIP/2.0/TCP $effectiveIp:$localPort;branch=$branch")
                    appendLine("Max-Forwards: 70")
                    appendLine("From: <sip:$localUsername@$sipServerHost>;tag=$fromTag")
                    appendLine("To: <sip:$toUser@$sipServerHost>")
                    appendLine("Call-ID: $callId")
                    appendLine("CSeq: $cseq INVITE")
                    appendLine("Contact: <sip:$localUsername@$effectiveIp:$localPort>;+sip.app-subtype=\"webrtc-datachannel\"")
                    appendLine("Content-Length: 0")
                    append("\r\n")
                }
            }
            val formattedMsg = msg.replace("\r\n", "\n").replace("\n", "\r\n")

            Log.i(TAG, "→ INVITE $localUsername → $toUser (call=$callId, sdp=${!sdpBody.isNullOrEmpty()})")
            sendRaw(formattedMsg)
            callId
        }

    /** 491 Request Pending to an incoming Re-INVITE that arrived while ours is in progress (RFC 3261 14.2). */
    suspend fun sendRequestPending(originalInvite: String) = withContext(Dispatchers.IO) {
        Log.w(TAG, "→ 491 Request Pending (own Re-INVITE in progress)")
        sendRaw(buildResponse(originalInvite, 491, "Request Pending", ""))
    }

    /** 180 Ringing for an incoming Re-INVITE the user has to decide on; remembered so a CANCEL can withdraw it. */
    suspend fun sendReInviteRinging(originalInvite: String) = withContext(Dispatchers.IO) {
        heldReInviteMsg = originalInvite
        Log.i(TAG, "→ 180 Ringing (Re-INVITE waiting for the user)")
        sendRaw(buildResponse(originalInvite, 180, "Ringing", ""))
    }

    /** Final non-2xx answer to an incoming Re-INVITE (e.g. 603 Decline). Unlike [sendDecline] the call stays up. */
    suspend fun sendReInviteReject(originalInvite: String, code: Int, reason: String) = withContext(Dispatchers.IO) {
        heldReInviteMsg = null
        Log.i(TAG, "→ $code $reason (Re-INVITE rejected, call continues)")
        sendRaw(buildResponse(originalInvite, code, reason, "Content-Length: 0\r\n"))
    }

    /** Called once the held Re-INVITE got its final answer (200 OK or reject). */
    fun clearHeldReInvite() { heldReInviteMsg = null }

    /** CANCEL for our own pending Re-INVITE with [cseq]; the far end answers it 487 and the call goes on. */
    suspend fun cancelReInvite(cseq: Int) = withContext(Dispatchers.IO) {
        val callId = pendingCallId ?: return@withContext
        val fromTag = pendingFromTag ?: ""
        val toUser = pendingRemoteUser ?: return@withContext
        val localIp = getLocalIp()
        val msg = buildString {
            appendLine("CANCEL sip:$toUser@$sipServerHost SIP/2.0")
            appendLine("Via: SIP/2.0/TCP $localIp:$localPort;branch=${randomBranch()}")
            appendLine("Route: <sip:$sipServerHost:$sipServerPort;lr;transport=tcp>")
            appendLine("Max-Forwards: 70")
            appendLine("From: <sip:$localUsername@$sipServerHost>;tag=$fromTag")
            appendLine("To: ${activeToHeader ?: "<sip:$toUser@$sipServerHost>"}")
            appendLine("Call-ID: $callId")
            appendLine("CSeq: $cseq CANCEL")
            appendLine("Content-Length: 0")
            append("\r\n")
        }.replace("\r\n", "\n").replace("\n", "\r\n")
        Log.i(TAG, "→ CANCEL of Re-INVITE (call=$callId, cseq=$cseq)")
        sendRaw(msg)
    }

    /** [onCSeq] gets the CSeq before the request goes out, so its final response can be matched to it. */
    suspend fun sendReInviteWithSdp(sdpBody: String, onCSeq: (Int) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val callId = pendingCallId ?: randomCallId()
            val fromTag = pendingFromTag ?: randomTag()
            val toVal = activeToHeader ?: "<sip:${pendingRemoteUser ?: "unknown"}@$sipServerHost>"
            val cseq = cseqCounter.getAndIncrement()
            lastReInviteCSeq = cseq
            onCSeq(cseq)
            val localIp = getLocalIp()
            lastLocalIp = localIp
            val branch = randomBranch()

            lastLocalOfferSdp = sdpBody

            val translatedSdp = SdpHelper.translateWebRtcOfferToSipOffer(sdpBody, localIp, homeReqApp = homeReqApp, isCallHoldActive = isCallHoldActive, localAudioPort = RtpPorts.current().first, localVideoPort = RtpPorts.current().second, appReqApp = appReqApp, appSlotUsed = appSlotUsed, shareStream = shareStream, slides = slides)
            val contentLength = translatedSdp.toByteArray(Charsets.UTF_8).size

            val msg = buildString {
                appendLine("INVITE sip:${pendingRemoteUser ?: "unknown"}@$sipServerHost SIP/2.0")
                appendLine("Via: SIP/2.0/TCP $localIp:$localPort;branch=$branch")
                appendLine("Route: <sip:$sipServerHost:$sipServerPort;lr;transport=tcp>")
                appendLine("Max-Forwards: 70")
                appendLine("From: <sip:$localUsername@$sipServerHost>;tag=$fromTag")
                appendLine("To: $toVal")
                appendLine("Call-ID: $callId")
                appendLine("CSeq: $cseq INVITE")
                appendLine("Contact: <sip:$localUsername@$localIp:$localPort>;+sip.app-subtype=\"webrtc-datachannel\"")
                appendLine("Content-Type: application/sdp")
                appendLine("Content-Length: $contentLength")
                append("\r\n")
                append(translatedSdp)
            }
            val formattedMsg = msg.replace("\r\n", "\n").replace("\n", "\r\n")
            Log.i(TAG, "→ Re-INVITE $localUsername (call=$callId, cseq=$cseq)")
            sendRaw(formattedMsg)
            callId
        }

    // ─── 200 OK for Incoming INVITE (B-side answer) ──────────────────────────

    /**
     * Gửi 200 OK khi B chấp nhận cuộc gọi.
     * @param originalInvite: INVITE message gốc nhận được
     * @param sdpAnswer: SDP Answer của B (m=application với WebRTC params của B)
     */
    suspend fun sendOkAnswer(originalInvite: String, sdpAnswer: String) =
        withContext(Dispatchers.IO) {
            val localIp = getLocalIp()
            lastLocalIp = localIp
            callLocalIp = localIp
            val translatedAnswer = SdpHelper.translateWebRtcOfferToSipOffer(sdpAnswer, localIp, homeReqApp = homeReqApp, isCallHoldActive = isCallHoldActive, localAudioPort = RtpPorts.current().first, localVideoPort = RtpPorts.current().second, appReqApp = appReqApp, appSlotUsed = appSlotUsed, shareStream = shareStream, slides = slides)
            val contentLength = translatedAnswer.toByteArray(Charsets.UTF_8).size
            val response = buildResponse(
                originalInvite, 200, "OK",
                "Content-Type: application/sdp\r\n" +
                "Content-Length: $contentLength\r\n"
            ) + translatedAnswer

            Log.i(TAG, "→ 200 OK (answering INVITE)")
            isCallActive = true
            sendRaw(response)
        }

    /**
     * Gửi 603 Decline khi B từ chối cuộc gọi.
     * @param originalInvite: INVITE message gốc nhận được
     */
    suspend fun sendDecline(originalInvite: String) =
        withContext(Dispatchers.IO) {
            val response = buildResponse(
                originalInvite, 603, "Decline",
                "Content-Length: 0\r\n"
            )
            Log.i(TAG, "→ 603 Decline (rejecting INVITE)")
            sendRaw(response)

            // Declining can come from the notification while CallActivity is already showing
            // the ringing screen, so end the call locally the same way a CANCEL or BYE does.
            val ctx = appContext ?: Companion.appContext
            ctx?.let { IncomingCallNotificationManager.dismiss(it) }
            resetCallState()
            onCallEnded?.invoke()
        }

    // ─── BYE ─────────────────────────────────────────────────────────────────

    suspend fun bye(
        toUser: String,
        localIp: String? = null,
        overrideCallId: String? = null,
        overrideFromTag: String? = null
    ) = withContext(Dispatchers.IO) {
        val callId = overrideCallId ?: pendingCallId ?: return@withContext
        val fromTag = overrideFromTag ?: pendingFromTag ?: ""
        val cseq   = cseqCounter.getAndIncrement()
        val effectiveIp = getLocalIp()

        val msg = buildString {
            appendLine("BYE sip:$toUser@$sipServerHost SIP/2.0")
            appendLine("Via: SIP/2.0/TCP $effectiveIp:$localPort;branch=${randomBranch()}")
            appendLine("Max-Forwards: 70")
            appendLine("From: <sip:$localUsername@$sipServerHost>;tag=$fromTag")
            appendLine("To: <sip:$toUser@$sipServerHost>")
            appendLine("Call-ID: $callId")
            appendLine("CSeq: $cseq BYE")
            appendLine("Content-Length: 0")
            append("\r\n")
        }.replace("\r\n", "\n").replace("\n", "\r\n")

        Log.i(TAG, "→ BYE (callId=$callId, tag=$fromTag)")
        sendRaw(msg)
        resetCallState()
    }

    // ─── CANCEL ──────────────────────────────────────────────────────────────

    suspend fun cancel(
        toUser: String,
        localIp: String? = null,
        overrideCallId: String? = null,
        overrideFromTag: String? = null
    ) = withContext(Dispatchers.IO) {
        val callId = overrideCallId ?: pendingCallId ?: return@withContext
        val fromTag = overrideFromTag ?: pendingFromTag ?: ""
        val cseq   = initialInviteCSeq.takeIf { it > 0 } ?: (cseqCounter.get() - 1)
        val effectiveIp = getLocalIp()

        val msg = buildString {
            appendLine("CANCEL sip:$toUser@$sipServerHost SIP/2.0")
            appendLine("Via: SIP/2.0/TCP $effectiveIp:$localPort;branch=${randomBranch()}")
            appendLine("Max-Forwards: 70")
            appendLine("From: <sip:$localUsername@$sipServerHost>;tag=$fromTag")
            appendLine("To: <sip:$toUser@$sipServerHost>")
            appendLine("Call-ID: $callId")
            appendLine("CSeq: $cseq CANCEL")
            appendLine("Content-Length: 0")
            append("\r\n")
        }.replace("\r\n", "\n").replace("\n", "\r\n")

        Log.i(TAG, "→ CANCEL (callId=$callId, tag=$fromTag)")
        sendRaw(msg)
        resetCallState()
    }

    // ─── ACK ────────────────────────────────────────────────────────────────

    suspend fun sendAck(toHeaderVal: String, sdpBody: String? = null, cseqOverride: Int? = null) =
        withContext(Dispatchers.IO) {
            val callId = pendingCallId ?: return@withContext
            val cseq   = cseqOverride ?: pendingCSeq
            val remoteUser = pendingRemoteUser ?: "server"

            // Lấy local IP từ socket
            val localIp = getLocalIp()

            val msg = if (!sdpBody.isNullOrEmpty()) {
                val translatedSdp = SdpHelper.translateWebRtcOfferToSipOffer(sdpBody, localIp, homeReqApp = homeReqApp, isCallHoldActive = isCallHoldActive, localAudioPort = RtpPorts.current().first, localVideoPort = RtpPorts.current().second, appReqApp = appReqApp, appSlotUsed = appSlotUsed, shareStream = shareStream, slides = slides)
                val contentLength = translatedSdp.toByteArray(Charsets.UTF_8).size
                buildString {
                    appendLine("ACK sip:$remoteUser@$sipServerHost:$sipServerPort SIP/2.0")
                    appendLine("Via: SIP/2.0/TCP $localIp:$localPort;branch=${randomBranch()}")
                    appendLine("Max-Forwards: 70")
                    appendLine("From: <sip:$localUsername@$sipServerHost>;tag=${pendingFromTag ?: ""}")
                    appendLine("To: $toHeaderVal")
                    appendLine("Call-ID: $callId")
                    appendLine("CSeq: $cseq ACK")
                    appendLine("Content-Type: application/sdp")
                    appendLine("Content-Length: $contentLength")
                    append("\r\n")
                    append(translatedSdp)
                }
            } else {
                buildString {
                    appendLine("ACK sip:$remoteUser@$sipServerHost:$sipServerPort SIP/2.0")
                    appendLine("Via: SIP/2.0/TCP $localIp:$localPort;branch=${randomBranch()}")
                    appendLine("Max-Forwards: 70")
                    appendLine("From: <sip:$localUsername@$sipServerHost>;tag=${pendingFromTag ?: ""}")
                    appendLine("To: $toHeaderVal")
                    appendLine("Call-ID: $callId")
                    appendLine("CSeq: $cseq ACK")
                    appendLine("Content-Length: 0")
                    append("\r\n")
                }
            }
            val formattedMsg = msg.replace("\r\n", "\n").replace("\n", "\r\n")

            Log.i(TAG, "→ ACK for call $callId with To: $toHeaderVal (sdp=${!sdpBody.isNullOrEmpty()})")
            try {
                sendRaw(formattedMsg)
            } catch (e: Exception) {
                Log.e(TAG, "ACK send error: ${e.message}")
            }
        }

    // ─── Send Raw ────────────────────────────────────────────────────────────

    private fun sendRaw(msg: String) {
        val bytes = msg.toByteArray(Charsets.UTF_8)
        synchronized(this) {
            try {
                val writer = tcpWriter
                if (writer != null) {
                    writer.write(bytes)
                    writer.flush()
                } else {
                    Log.w(TAG, "tcpWriter is null, triggering reconnect...")
                    if (isRunning) {
                        reconnect()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP send error: ${e.message}")
                if (isRunning) {
                    reconnect()
                }
            }
        }
    }

    // ─── Build Helpers ───────────────────────────────────────────────────────

    private fun buildResponse(
        requestMsg: String,
        statusCode: Int,
        reason: String,
        extraHeaders: String
    ): String {
        val vias   = getHeaders(requestMsg, "via")
        val recordRoutes = getHeaders(requestMsg, "record-route")
        val from   = getHeader(requestMsg, "from")   ?: ""
        var to     = getHeader(requestMsg, "to")     ?: ""
        val callId = getHeader(requestMsg, "call-id")?: ""
        val cseq   = getHeader(requestMsg, "cseq")   ?: ""

        if (statusCode in 180..299 && !to.contains(";tag=")) {
            to = "$to;tag=${randomTag()}"
        }

        return buildString {
            appendLine("SIP/2.0 $statusCode $reason")
            vias.forEach { appendLine("Via: $it") }
            recordRoutes.forEach { appendLine("Record-Route: $it") }
            if (from.isNotEmpty())   appendLine("From: $from")
            if (to.isNotEmpty())     appendLine("To: $to")
            if (callId.isNotEmpty()) appendLine("Call-ID: $callId")
            if (cseq.isNotEmpty())   appendLine("CSeq: $cseq")
            if (extraHeaders.isNotEmpty()) append(extraHeaders)
            else appendLine("Content-Length: 0")
            append("\r\n")
        }.replace("\r\n", "\n").replace("\n", "\r\n")
    }

    // ─── Parse Helpers ───────────────────────────────────────────────────────

    private fun parseStatusCode(msg: String): Int {
        return try {
            msg.lines().firstOrNull()
                ?.split(" ")?.getOrNull(1)?.toInt() ?: 0
        } catch (e: Exception) { 0 }
    }

    private fun getHeader(msg: String, name: String): String? {
        val lower = name.lowercase()
        return msg.lines()
            .firstOrNull { it.lowercase().startsWith("$lower:") }
            ?.substringAfter(":")?.trim()
    }

    private fun getHeaders(msg: String, name: String): List<String> {
        val lower = name.lowercase()
        return msg.lines()
            .filter { it.lowercase().startsWith("$lower:") }
            .map { it.substringAfter(":").trim() }
    }

    private fun getFromUsername(msg: String): String? {
        val from = getHeader(msg, "from") ?: return null
        return Regex("sip:([^@>]+)@").find(from)?.groupValues?.getOrNull(1)
    }


    // ─── Random Helpers ──────────────────────────────────────────────────────

    private fun randomTag()    = (1..8).map { Random.nextInt(36).toString(36) }.joinToString("")
    private fun randomBranch() = "z9hG4bK" + (1..10).map { Random.nextInt(10) }.joinToString("")
    private fun randomCallId() = (1..16).map { Random.nextInt(36).toString(36) }.joinToString("") + "@imsdc"



}


