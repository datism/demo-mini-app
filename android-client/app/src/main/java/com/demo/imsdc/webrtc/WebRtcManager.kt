package com.demo.imsdc.webrtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "WebRtcManager"

/**
 * WebRtcManager — Quản lý WebRTC PeerConnection cho IMS DC
 *
 * Trong PoC này, chỉ dùng DataChannel (KHÔNG có Audio/Video track).
 * WebRTC được dùng để cung cấp:
 *   - DTLS key exchange với MF
 *   - SCTP multiplexing cho nhiều DataChannel
 *
 * Luồng hoạt động:
 *   1. createOffer() → SDP chứa m=application section
 *   2. Erlang SIP Server anchor SDP vào MF
 *   3. setRemoteAnswer() với SDP Answer đã sửa từ MF
 *   4. ICE kết nối tới MF endpoint
 *   5. DataChannel được mở (BDC Stream 10, ADC Stream 1000)
 *
 * DataChannels được tạo với negotiated=true (pre-negotiated):
 *   - Không cần SDP offer/answer để negotiate từng channel
 *   - Stream ID được đặt cố định theo spec (10 và 1000)
 */
class WebRtcManager(private val context: Context) {

    // ─── Callbacks ──────────────────────────────────────────────────────────
    var onPeerConnected: (() -> Unit)? = null
    var onPeerDisconnected: (() -> Unit)? = null
    var onRemoteDataChannelMessage: ((String) -> Unit)? = null

    // ─── State ──────────────────────────────────────────────────────────────
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var eglContext: EglBase.Context? = null
    internal var bdcPC: PeerConnection? = null
    internal var homeAdcPC: PeerConnection? = null
    internal var appAdcPC: PeerConnection? = null
    var appAdcChannel: DataChannel? = null
    @Volatile var foregroundIsHome: Boolean = true
    val foregroundAdcChannel: DataChannel? get() = if (foregroundIsHome) adcChannel else appAdcChannel
    private var remoteHomeMid: String? = null
    private var remoteAppMid: String? = null

    enum class AdcKind { HOME, APP }

    // Video/Audio renderers and tracks
    var localVideoView: SurfaceViewRenderer? = null
    var remoteVideoView: android.view.SurfaceView? = null
    var localAudioTrack: AudioTrack? = null
    var localVideoTrack: VideoTrack? = null
    var remoteVideoTrack: VideoTrack? = null
    var videoCapturer: VideoCapturer? = null
    var surfaceTextureHelper: SurfaceTextureHelper? = null
    var videoSource: VideoSource? = null

    // DataChannel refs
    var bdcChannel: DataChannel? = null

    var adcChannel: DataChannel? = null
    private var onIceGatheringComplete: (() -> Unit)? = null

    // ─── Init ────────────────────────────────────────────────────────────────

    /**
     * Khởi tạo PeerConnectionFactory.
     * Phải gọi trên Main Thread hoặc dùng PeerConnectionFactory.initialize() trước.
     */
    fun initialize(eglContext: EglBase.Context? = null) {
        this.eglContext = eglContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)

        val options = PeerConnectionFactory.Options()

        // Force WebRTC to use VPN network (Tailscale) instead of WiFi/Cellular
        // networkIgnoreMask bit flags: WIFI=2, CELLULAR=4, ETHERNET=8, VPN=16, LOOPBACK=32
        // By ignoring WiFi(2) + Cellular(4) + Ethernet(8), WebRTC is forced to use VPN or unknown adapters
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val vpnNetwork = cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

        // Log all networks for debugging
        @Suppress("DEPRECATION")
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net)
            val linkProps = cm.getLinkProperties(net)
            Log.i(TAG, "Network handle=${net.networkHandle}: " +
                "VPN=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}, " +
                "WiFi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}, " +
                "Cell=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}, " +
                "iface=${linkProps?.interfaceName}, " +
                "addrs=${linkProps?.linkAddresses?.joinToString { it.address.hostAddress ?: "?" }}")
        }

        if (vpnNetwork != null) {
            // Ignore WiFi(2) + Cellular(4) + Ethernet(8) + Loopback(32) = 46
            // This forces WebRTC native layer to only gather ICE candidates on VPN interface
            // Do NOT set networkIgnoreMask  Tailscale tun0 is classified as "unknown" by WebRTC native,
            // so ignoring WiFi+Cell+Eth+Loopback leaves ZERO valid interfaces.
            // Instead, let WebRTC gather on ALL interfaces; the MF ice-lite answer
            // will naturally select the VPN candidate via ICE connectivity checks.
            options.networkIgnoreMask = 0
            Log.i(TAG, "VPN network detected (handle=${vpnNetwork.networkHandle}), " +
                "VPN detected, networkIgnoreMask=0 (gather on ALL interfaces), binding process to VPN")
            // Also bind at process level for Java sockets (SIP already uses its own socket)
            cm.bindProcessToNetwork(vpnNetwork)
        } else {
            Log.w(TAG, "⚠️ No VPN network found! WebRTC will use WiFi - MF may be unreachable!")
        }
        val encoderFactory = if (eglContext != null) {
            DefaultVideoEncoderFactory(eglContext, true, true)
        } else {
            SoftwareVideoEncoderFactory()
        }
        val decoderFactory = if (eglContext != null) {
            DefaultVideoDecoderFactory(eglContext)
        } else {
            SoftwareVideoDecoderFactory()
        }

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            // AudioDeviceModule omitted: Audio is managed exclusively via RtpMediaManager plain UDP to prevent Hardware AEC contention
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory initialized with hardware/software video codecs")
    }

    fun ensureVpnBound() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val vpnNetwork = cm.allNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (vpnNetwork != null) {
                cm.bindProcessToNetwork(vpnNetwork)
                Log.i(TAG, "Ensured process bound to VPN network (${vpnNetwork.networkHandle})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error ensuring VPN bound: ${e.message}")
        }
    }

    // ─── Create PeerConnection ────────────────────────────────────────────────

    fun createPeerConnection(isCaller: Boolean, onStateChange: (PeerConnection.PeerConnectionState) -> Unit) {
        createBdcPeerConnection(isCaller, onStateChange)
    }

    fun createBdcPeerConnection(isCaller: Boolean, onStateChange: (PeerConnection.PeerConnectionState) -> Unit) {
        ensureVpnBound()
        if (peerConnectionFactory == null) {
            initialize(eglContext)
        }
        try {
            bdcChannel?.close()
        } catch (_: Exception) {}
        bdcChannel = null

        val config = PeerConnection.RTCConfiguration(
            emptyList<PeerConnection.IceServer>()
        ).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "BDC ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        Log.i(TAG, "✅ BDC ICE Connected to MF!")
                        onPeerConnected?.invoke()
                    }
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        Log.i(TAG, "✅ BDC ICE Completed")
                    }
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        Log.w(TAG, "❌ BDC ICE Failed/Disconnected")
                        onPeerDisconnected?.invoke()
                    }
                    else -> {}
                }
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                Log.i(TAG, "BDC PeerConnection state: $state")
                state?.let { onStateChange(it) }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "BDC ICE Gathering: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    onIceGatheringComplete?.invoke()
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                Log.d(TAG, "BDC Local ICE candidate: ${candidate?.sdp}")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onRemoveTrack(receiver: RtpReceiver?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                Log.i(TAG, "BDC onAddTrack: kind=${track?.kind()}, id=${track?.id()}")
                if (track is VideoTrack) {
                    Log.i(TAG, "Remote VideoTrack received: ${track.id()}")
                    remoteVideoTrack = track
                    track.setEnabled(true)
                }
            }
        }

        bdcPC = peerConnectionFactory?.createPeerConnection(config, observer)
        Log.i(TAG, "BDC PeerConnection created")

        // Audio and Video are handled by RtpMediaManager over plain UDP.
        // WebRTC is strictly used for DataChannel only.
    }

    fun createAdcPeerConnection(kind: AdcKind = AdcKind.HOME, onStateChange: (PeerConnection.PeerConnectionState) -> Unit) {
        ensureVpnBound()
        if (peerConnectionFactory == null) {
            initialize(eglContext)
        }
        val config = PeerConnection.RTCConfiguration(
            emptyList<PeerConnection.IceServer>()
        ).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "$kind ADC ICE connection state: $state")
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                Log.i(TAG, "$kind ADC PeerConnection state: $state")
                state?.let { onStateChange(it) }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "$kind ADC ICE Gathering: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    onIceGatheringComplete?.invoke()
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                Log.d(TAG, "$kind ADC Local ICE candidate: ${candidate?.sdp}")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {
                dc?.let {
                    Log.i(TAG, "Remote created DataChannel: id=${it.id()}, label=${it.label()}")
                    it.registerObserver(object : DataChannel.Observer {
                        override fun onStateChange() {
                            try {
                                Log.i(TAG, "Remote DataChannel ${it.id()} state: ${it.state()}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in remote DataChannel onStateChange: ${e.message}")
                            }
                        }
                        override fun onMessage(buffer: DataChannel.Buffer?) {
                            buffer ?: return
                            try {
                                val byteBuffer = buffer.data
                                val bytes = ByteArray(byteBuffer.remaining())
                                byteBuffer.get(bytes)
                                val data = String(bytes, Charsets.UTF_8)
                                Log.i(TAG, "ADC remote channel received ${bytes.size} bytes: $data")
                                onRemoteDataChannelMessage?.invoke(data)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling remote DataChannel message", e)
                            }
                        }
                        override fun onBufferedAmountChange(p0: Long) {}
                    })
                }
            }
            override fun onRenegotiationNeeded() {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onRemoveTrack(receiver: RtpReceiver?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }

        val created = peerConnectionFactory?.createPeerConnection(config, observer)
        if (kind == AdcKind.HOME) homeAdcPC = created else appAdcPC = created
        Log.i(TAG, "ADC PeerConnection created ($kind)")
    }

    // ─── Create Offer ─────────────────────────────────────────────────────────

    private suspend fun awaitIceGatheringComplete(pc: PeerConnection?) = suspendCancellableCoroutine<Unit> { cont ->
        if (pc == null || pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        onIceGatheringComplete = {
            onIceGatheringComplete = null
            if (cont.isActive) {
                cont.resume(Unit)
            }
        }
        cont.invokeOnCancellation {
            onIceGatheringComplete = null
        }
    }

    private suspend fun createOfferRaw(pc: PeerConnection?): SessionDescription = suspendCancellableCoroutine { cont ->
        if (pc == null) {
            cont.resumeWithException(Exception("PeerConnection is null"))
            return@suspendCancellableCoroutine
        }
        val isAdc = (pc == homeAdcPC || pc == appAdcPC)
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    cont.resumeWithException(Exception("createOffer returned null SDP"))
                    return
                }
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        cont.resume(sdp)
                    }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(Exception("setLocalDescription: $error"))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(Exception("createOffer: $error"))
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Local offer of one PeerConnection. A STABLE PC with a local description reuses it: an unchanged
     * m-line keeps the same ICE credentials, fingerprint and tls-id.
     */
    private suspend fun offerFor(pc: PeerConnection?): String? {
        pc ?: return null
        if (pc.signalingState() == PeerConnection.SignalingState.STABLE && pc.localDescription != null) {
            return pc.localDescription.description // unchanged m-line: same credentials, same tls-id
        }
        val raw = createOfferRaw(pc).description
        awaitIceGatheringComplete(pc)
        return pc.localDescription?.description ?: raw
    }

    suspend fun createOffer(): String {
        ensureBdcChannel()
        val bdcOffer = offerFor(bdcPC) ?: ""
        val merged = AdcSdp.merge(bdcOffer, offerFor(homeAdcPC), offerFor(appAdcPC), isAnswer = false)
        return if (homeAdcPC == null) {
            // Rule 12: Set ADC port to 0 instead of completely dropping the m=application block
            merged + "m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:3\r\n"
        } else merged
    }

    // ─── Set Remote Answer ────────────────────────────────────────────────────

    private fun addCandidateIfMissing(sdp: String): String {
        val lines = sdp.replace("\r", "").split("\n").filter { it.trim().isNotEmpty() }
        var hasCandidate = false
        var ip = ""
        var port = ""
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("a=candidate:")) {
                hasCandidate = true
            }
            if (trimmed.startsWith("c=IN IP4 ")) {
                ip = trimmed.substringAfter("c=IN IP4 ").trim()
            }
            if (trimmed.startsWith("m=application ")) {
                val parts = trimmed.split(" ")
                if (parts.size >= 2) {
                    port = parts[1]
                }
            }
        }
        if (!hasCandidate && ip.isNotEmpty() && port.isNotEmpty() && port != "0") {
            val newLines = lines.toMutableList()
            val candidateLine = "a=candidate:1 1 udp 2122260991 $ip $port typ host generation 0"
            newLines.add(candidateLine)
            Log.i(TAG, "Synthesized candidate line for separate PC: $candidateLine")
            return newLines.joinToString("\r\n") + "\r\n"
        }
        return sdp
    }

    private suspend fun applyAnswer(pc: PeerConnection?, part: String?, label: String) {
        if (pc == null || part == null) return
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            Log.i(TAG, "Skipping setRemoteAnswer on $label PC because signalingState is ${pc.signalingState()}")
            return
        }
        setRemoteDescriptionRaw(pc, AdcSdp.normalizeToLocalMid(addCandidateIfMissing(part)), SessionDescription.Type.ANSWER)
    }

    suspend fun setRemoteAnswer(sdpAnswer: String) {
        Log.i(TAG, "setRemoteAnswer parsing sdpAnswer (length=${sdpAnswer.length})")
        val parts = AdcSdp.split(sdpAnswer)
        val bdcAnswer = addCandidateIfMissing(parts.bdc)
        if (bdcPC != null && bdcPC!!.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            setRemoteDescriptionRaw(bdcPC, bdcAnswer, SessionDescription.Type.ANSWER)
        } else {
            Log.i(TAG, "Skipping setRemoteAnswer on BDC PC because signalingState is ${bdcPC?.signalingState()}")
        }
        applyAnswer(homeAdcPC, parts.home, "Home ADC")
        applyAnswer(appAdcPC, parts.app, "App ADC")
    }

    private suspend fun setRemoteDescriptionRaw(pc: PeerConnection?, sdpText: String, type: SessionDescription.Type) = suspendCancellableCoroutine<Unit> { cont ->
        if (pc == null) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        val sdp = SessionDescription(type, sdpText)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.i(TAG, "✅ Remote description set for ${pc}")
                cont.resume(Unit)
            }
            override fun onSetFailure(error: String?) {
                cont.resumeWithException(Exception("setRemoteDescription error: $error"))
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sdp)
    }

    // ─── Set Remote Offer (B-side incoming call) ──────────────────────────────

    private suspend fun applyOffer(pc: PeerConnection?, part: String?, label: String) {
        if (pc == null || part == null) return
        if (pc.signalingState() == PeerConnection.SignalingState.STABLE && pc.localDescription == null) {
            setRemoteDescriptionRaw(pc, AdcSdp.normalizeToLocalMid(addCandidateIfMissing(part)), SessionDescription.Type.OFFER)
        } else {
            Log.i(TAG, "Skipping setRemoteOffer on $label PC because signalingState is ${pc.signalingState()}")
        }
    }

    suspend fun setRemoteOffer(sdpOffer: String) {
        val parts = AdcSdp.split(sdpOffer)
        val (homeLine, appLine) = pickSlots(parseAppLines(sdpOffer).drop(1)) { it.mid }
        remoteHomeMid = homeLine?.mid
        remoteAppMid = appLine?.mid
        val bdcOffer = addCandidateIfMissing(parts.bdc)
        if (bdcPC != null) {
            if (bdcPC!!.signalingState() == PeerConnection.SignalingState.STABLE && bdcPC!!.localDescription == null) {
                setRemoteDescriptionRaw(bdcPC, bdcOffer, SessionDescription.Type.OFFER)
            } else {
                Log.i(TAG, "Skipping setRemoteOffer on BDC PC because signalingState is ${bdcPC?.signalingState()}")
            }
        }
        if (parts.home != null && homeAdcPC == null) {
            createAdcPeerConnection(AdcKind.HOME) { state -> Log.d(TAG, "Home ADC (B-side) state: $state") }
            createADC()
        }
        // A Home line with port 0 in a later offer is ignored: the Home ADC is closed only by closeADC() at hang-up.
        applyOffer(homeAdcPC, parts.home, "Home ADC")
        // The App PC is created or closed by CallActivity from the mid-4 diff before this call.
        applyOffer(appAdcPC, parts.app, "App ADC")
    }

    // ─── Create Answer (B-side) ───────────────────────────────────────────────

    private suspend fun createAnswerRaw(pc: PeerConnection?): SessionDescription = suspendCancellableCoroutine { cont ->
        if (pc == null) {
            cont.resumeWithException(Exception("PeerConnection is null"))
            return@suspendCancellableCoroutine
        }
        val isAdc = (pc == homeAdcPC || pc == appAdcPC)
        pc.transceivers?.forEach { transceiver ->
            val kind = transceiver.mediaType
            if (kind == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO || kind == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                Log.i(TAG, "Forcing transceiver direction to SEND_RECV (answer): kind=$kind, currentDirection=${transceiver.direction}")
                transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            }
        }
        val constraints = MediaConstraints().apply {
            if (isAdc) {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            } else {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }
        }
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    cont.resumeWithException(Exception("createAnswer returned null SDP"))
                    return
                }
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        cont.resume(sdp)
                    }
                    override fun onSetFailure(error: String?) {
                        cont.resumeWithException(Exception("setLocalDescription(answer): $error"))
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(Exception("createAnswer: $error"))
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    /**
     * Local answer of one PeerConnection. A STABLE PC with a local description reuses it (unchanged m-line,
     * with a=setup:actpass rewritten to the established role when that description is this side's offer);
     * a PC that holds no remote offer contributes no line.
     */
    private suspend fun answerFor(pc: PeerConnection?): String? {
        pc ?: return null
        val state = pc.signalingState()
        val local = pc.localDescription
        if (state == PeerConnection.SignalingState.STABLE && local != null) {
            // This side made the PC's last offer: reuse it with the established DTLS role, not actpass.
            return if (local.type == SessionDescription.Type.OFFER)
                AdcSdp.answerSetupFromOffer(local.description, pc.remoteDescription?.description)
            else local.description
        }
        if (state != PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
            Log.i(TAG, "No answer from PC in signalingState $state (no remote offer applied)")
            return null
        }
        val raw = createAnswerRaw(pc).description
        awaitIceGatheringComplete(pc)
        return pc.localDescription?.description ?: raw
    }

    suspend fun createAnswer(): String {
        ensureBdcChannel()
        return AdcSdp.merge(answerFor(bdcPC) ?: "", answerFor(homeAdcPC), answerFor(appAdcPC), isAnswer = true, remoteMids = remoteHomeMid to remoteAppMid)
    }

    // ─── Data Channel Creation ───────────────────────────────────────────────

    fun createBDC(streamId: Int): DataChannel? {
        val pc = bdcPC ?: return null
        val init = DataChannel.Init().apply {
            id = streamId
            negotiated = true
            ordered = true
            protocol = "http"
        }
        try {
            bdcChannel?.close()
        } catch (_: Exception) {}
        bdcChannel = pc.createDataChannel("bdc", init)
        Log.i(TAG, "BDC created: id=$streamId, protocol=http, negotiated=true, channel=${bdcChannel}")
        return bdcChannel
    }

    /**
     * Returns the BDC, creating it only when needed. A Re-INVITE must not replace it: the channel's observer
     * (DataChannelManager.setupBDC) is registered once per call, and a replacement channel would have none.
     */
    fun ensureBdcChannel(): DataChannel? {
        val existing = bdcChannel
        val isClosed = existing?.state() == DataChannel.State.CLOSED
        if (!needsNewBdcChannel(existing?.id(), isClosed)) return existing
        if (existing != null) Log.w(TAG, "Replacing BDC (id=${existing.id()}, closed=$isClosed)")
        return createBDC(BDC_STREAM_ID)
    }

    companion object {
        /** BDC stream id, announced as a=dcmap:10 in every offer/answer. */
        const val BDC_STREAM_ID = 10

        /** A new BDC is needed only when there is no usable stream-10 channel; an open one is never replaced. */
        fun needsNewBdcChannel(existingId: Int?, isClosed: Boolean): Boolean =
            existingId != BDC_STREAM_ID || isClosed
    }

    /**
     * Tạo ADC (Application Data Channel) — Stream ID 1000 on the Home PeerConnection (mid 3).
     * Dùng để gửi/nhận sự kiện "thả tim" giữa A và B qua MF.
     */
    fun createADC(): DataChannel? {
        if (homeAdcPC == null) {
            createAdcPeerConnection { state ->
                Log.d(TAG, "Dynamic ADC PeerConnection ICE state: $state")
            }
        }
        adcChannel?.let {
            if (it.state() == DataChannel.State.CLOSED) {
                adcChannel = null
            }
        }
        if (adcChannel == null) {
            val init = DataChannel.Init().apply {
                id = 1000
                negotiated = true
                ordered = true
            }
            adcChannel = homeAdcPC?.createDataChannel("app-dc", init)
            Log.i(TAG, "ADC created: id=1000, label=app-dc, negotiated=true, channel=${adcChannel}")
        }
        
        return adcChannel
    }

    var adc2Channel: DataChannel? = null

    fun createADC2(): DataChannel? {
        if (homeAdcPC == null) return null
        adc2Channel?.let {
            if (it.state() == DataChannel.State.CLOSED) {
                adc2Channel = null
            }
        }
        if (adc2Channel == null) {
            val init = DataChannel.Init().apply {
                id = 1001
                negotiated = true
                ordered = true
            }
            adc2Channel = homeAdcPC?.createDataChannel("app-dc-2", init)
            Log.i(TAG, "ADC2 created: id=1001, label=app-dc-2, negotiated=true")
        }
        return adc2Channel
    }

    /** App slot (mid 4): its own PeerConnection with a negotiated channel id=1000, label "app-dc-app". */
    fun createAppADC(): DataChannel? {
        if (appAdcPC == null) {
            createAdcPeerConnection(AdcKind.APP) { state -> Log.d(TAG, "App ADC PeerConnection state: $state") }
        }
        appAdcChannel?.let { if (it.state() == DataChannel.State.CLOSED) appAdcChannel = null }
        if (appAdcChannel == null) {
            val init = DataChannel.Init().apply { id = 1000; negotiated = true; ordered = true }
            appAdcChannel = appAdcPC?.createDataChannel("app-dc-app", init)
            Log.i(TAG, "App ADC created: id=1000, label=app-dc-app, negotiated=true")
        }
        return appAdcChannel
    }

    /** Closes only the App slot (mid 4). The Home ADC and the BDC are not touched. */
    fun closeAppADC() {
        Log.i(TAG, "Closing App ADC and destroying its PeerConnection...")
        try {
            appAdcChannel?.close()
            appAdcChannel = null
            val old = appAdcPC
            appAdcPC?.close()
            appAdcPC = null
            if (old != null) {
                Thread {
                    try {
                        Thread.sleep(500)
                        old.dispose()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error disposing App ADC PC: ${e.message}")
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing App ADC: ${e.message}")
        }
    }

    /**
     * Tạo placeholder DataChannel để kích hoạt m=application trong SDP Offer.
     * DataChannel này không được dùng thực tế.
     */
    fun createPlaceholderChannel() {
        bdcPC?.createDataChannel("placeholder", DataChannel.Init())
        Log.d(TAG, "Placeholder DataChannel created for SDP negotiation")
    }

    // ─── Extract m=application from SDP ─────────────────────────────────────

    /**
     * Trích xuất toàn bộ m=application section từ SDP.
     * Erlang SIP Server sẽ dùng section này để nhúng vào SIP INVITE,
     * sau đó anchor nó vào MF.
     *
     * @return SDP string chứa toàn bộ content (Erlang cần full SDP để parse)
     */
    fun getLocalSdp(): String? {
        val bdcSdp = bdcPC?.localDescription?.description ?: return null
        return AdcSdp.merge(bdcSdp, homeAdcPC?.localDescription?.description, appAdcPC?.localDescription?.description, isAnswer = false)
    }

    // ─── Local Media Capture ──────────────────────────────────────────────────

    private fun createVideoCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames

        // Thử tìm camera trước (front-facing)
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    return capturer
                }
            }
        }

        // Nếu không có camera trước, thử camera sau
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    return capturer
                }
            }
        }

        return null
    }

    /**
     * Bắt đầu capture local audio/video và hiển thị lên local renderer view.
     * Đồng thời add các track này vào PeerConnection để send sang phía đối phương.
     */
    fun startLocalCapture(localView: SurfaceViewRenderer, enableVideo: Boolean = true) {
        this.localVideoView = localView
        Log.i(TAG, "Starting local capture (audio + video=$enableVideo)...")
        
        val rand = java.util.UUID.randomUUID().toString().take(8)
        val audioTrackId = "audio_track_$rand"
        val videoTrackId = "video_track_$rand"
        val streamId = "stream_$rand"

        // 1. Audio handled via RtpMediaManager plain UDP (no WebRTC AudioTrack needed)
        Log.i(TAG, "Audio handled via RtpMediaManager plain UDP: skipping WebRTC AudioTrack creation")

        if (!enableVideo) {
            Log.i(TAG, "Video is disabled, skipping camera capture and video track addition")
            return
        }

        // 2. Tạo Video Track (sử dụng camera)
        videoCapturer = createVideoCapturer()
        if (videoCapturer != null) {
            val contextToUse = this.eglContext ?: EglBase.create().eglBaseContext
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", contextToUse)
            videoSource = peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)

            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            // Bắt đầu capture ở độ phân giải 640x480, 30fps
            videoCapturer?.startCapture(640, 480, 30)

            localVideoTrack = peerConnectionFactory?.createVideoTrack(videoTrackId, videoSource)
            localVideoTrack?.setEnabled(true)

            // Render camera của chính mình lên góc màn hình
            localVideoTrack?.addSink(localView)

            // Removed bdcPC?.addTrack(localVideoTrack...) since video uses plain RTP via RtpMediaManager
            Log.i(TAG, "Local VideoTrack created but NOT added to WebRTC (uses plain UDP): id=$videoTrackId")
        } else {
            Log.e(TAG, "Failed to create VideoCapturer (no camera found or emulator lacks camera)")
        }
    }

    fun pauseLocalCapture() {
        Log.i(TAG, "Pausing local camera capture (Activity going background)")
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing camera: ${e.message}")
        }
    }

    fun resumeLocalCapture() {
        if (videoCapturer == null) {
            Log.w(TAG, "No video capturer to resume")
            return
        }
        Log.i(TAG, "Resuming local camera capture (Activity returning to foreground)")
        try {
            videoCapturer?.startCapture(640, 480, 30)
            Log.i(TAG, "Camera capture resumed successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resume camera, recreating capturer: ${e.message}")
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
            } catch (_: Exception) {}

            val newCapturer = createVideoCapturer()
            if (newCapturer != null) {
                newCapturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
                newCapturer.startCapture(640, 480, 30)
                videoCapturer = newCapturer
                localVideoView?.let { localVideoTrack?.addSink(it) }
                Log.i(TAG, "Camera capturer recreated and resumed")
            } else {
                Log.e(TAG, "Cannot recreate video capturer")
            }
        }
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    /** Hang-up only: closes the App slot, then the Home ADC. The BDC is not touched. */
    fun closeADC() {
        closeAppADC()
        Log.i(TAG, "Closing Home Application Data Channel (ADC) and destroying PeerConnection...")
        try {
            adcChannel?.close()
            adcChannel = null
            
            adc2Channel?.close()
            adc2Channel = null
            
            val oldAdcPC = homeAdcPC
            homeAdcPC?.close()
            homeAdcPC = null
            
            // Dispose in a background thread after a delay to prevent native WebRTC SIGABRT
            if (oldAdcPC != null) {
                Thread {
                    try {
                        Thread.sleep(500)
                        oldAdcPC.dispose()
                        Log.i(TAG, "ADC PeerConnection disposed safely.")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error disposing ADC PC: ${e.message}")
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ADC: ${e.message}")
        }
    }

    fun close() {
        Thread {
            Log.i(TAG, "Releasing WebRTC resources in background thread...")
            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping capturer: ${e.message}")
            }
            try {
                videoSource?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Error disposing videoSource: ${e.message}")
            }
            videoSource = null
            try {
                surfaceTextureHelper?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Error disposing surfaceTextureHelper: ${e.message}")
            }
            closeAppADC()
            try {
                bdcChannel?.close()
                bdcChannel = null
                adcChannel?.close()
                adcChannel = null
                adc2Channel?.close()
                adc2Channel = null
                
                val oldBdc = bdcPC
                val oldAdc = homeAdcPC
                bdcPC?.close()
                homeAdcPC?.close()
                bdcPC = null
                homeAdcPC = null
                
                // Delay disposal to prevent native WebRTC SIGABRT
                Thread.sleep(500)
                
                oldBdc?.dispose()
                oldAdc?.dispose()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing PeerConnections: ${e.message}")
            }
            Log.i(TAG, "WebRTC call resources released safely (VPN binding retained)")
        }.start()
    }
}


