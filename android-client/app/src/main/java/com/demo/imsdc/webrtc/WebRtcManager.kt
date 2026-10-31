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
    var currentAdcMid = 3
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

    fun createAdcPeerConnection(onStateChange: (PeerConnection.PeerConnectionState) -> Unit) {
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
                Log.i(TAG, "ADC ICE connection state: $state")
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                Log.i(TAG, "ADC PeerConnection state: $state")
                state?.let { onStateChange(it) }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ADC ICE Gathering: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    onIceGatheringComplete?.invoke()
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                Log.d(TAG, "ADC Local ICE candidate: ${candidate?.sdp}")
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

        homeAdcPC = peerConnectionFactory?.createPeerConnection(config, observer)
        Log.i(TAG, "ADC PeerConnection created")
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
        val isAdc = (pc == homeAdcPC)
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

    suspend fun createOffer(): String {
        ensureBdcChannel()
        
        val finalBdcOffer = if (bdcPC != null && bdcPC!!.signalingState() == PeerConnection.SignalingState.STABLE && bdcPC!!.localDescription != null) {
            Log.i(TAG, "Reusing existing BDC local description (STABLE) for offer")
            bdcPC!!.localDescription.description
        } else {
            val bdcOffer = createOfferRaw(bdcPC).description
            Log.i(TAG, "Waiting for BDC ICE gathering complete (offer)...")
            awaitIceGatheringComplete(bdcPC)
            bdcPC?.localDescription?.description ?: bdcOffer
        }

        val adcPCLocal = homeAdcPC
        val finalAdcOffer = if (adcPCLocal != null) {
            val adcOffer = createOfferRaw(adcPCLocal).description
            Log.i(TAG, "Waiting for ADC ICE gathering complete (offer)...")
            awaitIceGatheringComplete(adcPCLocal)
            adcPCLocal.localDescription?.description ?: adcOffer
        } else {
            null
        }

        return if (finalAdcOffer != null) {
            MergeAdcOffer(finalBdcOffer, finalAdcOffer)
        } else {
            // Rule 12: Set ADC port to 0 instead of completely dropping the m=application block
            finalBdcOffer + "m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:3\r\n"
        }
    }

    // ─── Set Remote Answer ────────────────────────────────────────────────────

    private fun replaceMidInSdp(sdp: String, newMid: String): String {
        val lines = sdp.replace("\r", "").split("\n")
        val newLines = lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("a=mid:")) {
                "a=mid:$newMid"
            } else {
                line
            }
        }
        return newLines.filter { it.trim().isNotEmpty() }.joinToString("\r\n") + "\r\n"
    }

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

    suspend fun setRemoteAnswer(sdpAnswer: String) {
        Log.i(TAG, "setRemoteAnswer parsing sdpAnswer (length=${sdpAnswer.length})")
        val (bdcAnswerRaw, adcAnswerRaw) = SplitAdcAnswer(sdpAnswer)
        val bdcAnswer = addCandidateIfMissing(bdcAnswerRaw)
        Log.i(TAG, "SplitAdcAnswer results: bdcAnswer.length=${bdcAnswer.length}, adcAnswer.length=${adcAnswerRaw?.length ?: 0}")
        if (bdcPC != null && bdcPC!!.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            Log.i(TAG, "Setting Remote Answer on BDC PC (length=${bdcAnswer.length}):\n$bdcAnswer")
            setRemoteDescriptionRaw(bdcPC, bdcAnswer, SessionDescription.Type.ANSWER)
        } else {
            Log.i(TAG, "Skipping setRemoteAnswer on BDC PC because signalingState is ${bdcPC?.signalingState()}")
        }
        if (adcAnswerRaw != null && homeAdcPC != null) {
            Log.i(TAG, "homeAdcPC state before setting answer: signalingState=${homeAdcPC?.signalingState()}")
            Log.i(TAG, "homeAdcPC localDescription SDP:\n${homeAdcPC?.localDescription?.description}")
            if (homeAdcPC!!.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                val withCandidate = addCandidateIfMissing(adcAnswerRaw)
                val normalizedAdcAnswer = replaceMidInSdp(withCandidate, "0")
                Log.i(TAG, "Setting Remote Answer on ADC PC (length=${normalizedAdcAnswer.length}):\n$normalizedAdcAnswer")
                setRemoteDescriptionRaw(homeAdcPC, normalizedAdcAnswer, SessionDescription.Type.ANSWER)
            } else {
                Log.i(TAG, "Skipping setRemoteAnswer on ADC PC because signalingState is ${homeAdcPC?.signalingState()}")
            }
        }
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

    suspend fun setRemoteOffer(sdpOffer: String) {
        val (bdcOfferRaw, adcOfferRaw) = SplitAdcAnswer(sdpOffer)
        val bdcOffer = addCandidateIfMissing(bdcOfferRaw)
        if (bdcPC != null) {
            if (bdcPC!!.signalingState() == PeerConnection.SignalingState.STABLE && bdcPC!!.localDescription == null) {
                setRemoteDescriptionRaw(bdcPC, bdcOffer, SessionDescription.Type.OFFER)
            } else {
                Log.i(TAG, "Skipping setRemoteOffer on BDC PC because signalingState is ${bdcPC?.signalingState()} and localDescription is set")
            }
        }
        if (adcOfferRaw != null && !adcOfferRaw.contains("m=application 0")) {
            if (homeAdcPC == null) {
                createAdcPeerConnection { state ->
                    Log.d(TAG, "ADC PC (B-side) ICE state: $state")
                }
                createADC()
            }
            if (homeAdcPC!!.signalingState() == PeerConnection.SignalingState.STABLE && homeAdcPC!!.localDescription == null) {
                val withCandidate = addCandidateIfMissing(adcOfferRaw)
                val normalizedAdcOffer = replaceMidInSdp(withCandidate, "0")
                Log.i(TAG, "Setting Remote Offer on ADC PC (length=${normalizedAdcOffer.length}):\n$normalizedAdcOffer")
                setRemoteDescriptionRaw(homeAdcPC, normalizedAdcOffer, SessionDescription.Type.OFFER)
            } else {
                Log.i(TAG, "Skipping setRemoteOffer on ADC PC because signalingState is ${homeAdcPC?.signalingState()}")
            }
        } else {
            Log.i(TAG, "ADC offer is null or disabled (port 0). Closing/skipping ADC.")
            closeADC()
        }
    }

    // ─── Create Answer (B-side) ───────────────────────────────────────────────

    private suspend fun createAnswerRaw(pc: PeerConnection?): SessionDescription = suspendCancellableCoroutine { cont ->
        if (pc == null) {
            cont.resumeWithException(Exception("PeerConnection is null"))
            return@suspendCancellableCoroutine
        }
        val isAdc = (pc == homeAdcPC)
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

    suspend fun createAnswer(): String {
        ensureBdcChannel()
        
        val finalBdcAnswer = if (bdcPC != null && bdcPC!!.signalingState() == PeerConnection.SignalingState.STABLE && bdcPC!!.localDescription != null) {
            Log.i(TAG, "Reusing existing BDC local description (STABLE) for answer")
            bdcPC!!.localDescription.description
        } else {
            val bdcAnswer = createAnswerRaw(bdcPC).description
            Log.i(TAG, "Waiting for BDC ICE gathering complete (answer)...")
            awaitIceGatheringComplete(bdcPC)
            bdcPC?.localDescription?.description ?: bdcAnswer
        }

        val adcPCLocal = homeAdcPC
        val finalAdcAnswer = if (adcPCLocal != null) {
            val adcAnswer = createAnswerRaw(adcPCLocal).description
            Log.i(TAG, "Waiting for ADC ICE gathering complete (answer)...")
            awaitIceGatheringComplete(adcPCLocal)
            adcPCLocal.localDescription?.description ?: adcAnswer
        } else {
            null
        }

        return if (finalAdcAnswer != null) {
            MergeAdcOffer(finalBdcAnswer, finalAdcAnswer, true)
        } else {
            finalBdcAnswer
        }
    }

    // ─── SDP Helper Functions ───────────────────────────────────────────────

    data class SdpMediaBlocks(
        val sessionHeader: String,
        val audioBlock: String?,
        val videoBlock: String?,
        val appBlocks: List<String>
    )

    fun splitSDPToMediaBlocks(sdp: String): SdpMediaBlocks {
        val lines = sdp.replace("\r", "").split("\n")
        val sessionHeader = StringBuilder()
        var audioBlock: StringBuilder? = null
        var videoBlock: StringBuilder? = null
        val appBlocks = mutableListOf<StringBuilder>()
        var currentBlock: StringBuilder? = sessionHeader

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("m=")) {
                val sb = StringBuilder()
                if (trimmed.startsWith("m=audio ")) {
                    audioBlock = sb
                    currentBlock = audioBlock
                } else if (trimmed.startsWith("m=video ")) {
                    videoBlock = sb
                    currentBlock = videoBlock
                } else if (trimmed.startsWith("m=application ")) {
                    val newApp = sb
                    appBlocks.add(newApp)
                    currentBlock = newApp
                } else {
                    currentBlock = null
                }
            }
            currentBlock?.append(trimmed)?.append("\r\n")
        }
        return SdpMediaBlocks(
            sessionHeader.toString(),
            audioBlock?.toString(),
            videoBlock?.toString(),
            appBlocks.map { it.toString() }
        )
    }

    var lastRemoteAdcOfferMid: String? = null

    fun extractMidFromSdp(sdp: String): String? {
        for (line in sdp.replace("\r", "").split("\n")) {
            val trimmed = line.trim()
            if (trimmed.startsWith("a=mid:")) {
                return trimmed.substringAfter("a=mid:").trim()
            }
        }
        return null
    }

    fun SplitAdcAnswer(sdpAnswer: String): Pair<String, String?> {
        val blocks = splitSDPToMediaBlocks(sdpAnswer)
        val bdcSb = StringBuilder()
        bdcSb.append(blocks.sessionHeader)
        blocks.audioBlock?.let { bdcSb.append(it) }
        blocks.videoBlock?.let { bdcSb.append(it) }
        if (blocks.appBlocks.isNotEmpty()) {
            bdcSb.append(blocks.appBlocks[0])
        }
        val bdcAnswer = bdcSb.toString()

        var adcAnswer: String? = null
        if (blocks.appBlocks.size > 1) {
            val adcSessionHeader = StringBuilder()
            // Giữ nguyên các session attribute cần thiết cho Pion:
            //  - ice-ufrag/ice-pwd: cần để ICE negotiation hoạt động
            //  - fingerprint/setup: cần cho DTLS-SRTP handshake
            // Chỉ lọc a=group:BUNDLE ở cấp session (đã gỡ BUNDLE grouping
            // vì ADC là PeerConnection riêng) và a=bundle-only (liên kết bundle).
            blocks.sessionHeader.replace("\r", "").split("\n").forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("a=group:BUNDLE")) {
                    adcSessionHeader.append(trimmed).append("\r\n")
                }
            }
            val cleanAppBlock = StringBuilder()
            blocks.appBlocks[1].replace("\r", "").split("\n").forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("a=bundle-only")) {
                    cleanAppBlock.append(trimmed).append("\r\n")
                }
            }
            // Fallback an toàn: nếu block application rỗng sau khi làm sạch
            // (hoặc thiếu ICE attrs) thì trả null để caller bỏ qua ADC, tránh crash createAnswer().
            if (cleanAppBlock.isBlank() || !cleanAppBlock.contains("a=ice-ufrag:")) {
                Log.w(TAG, "SplitAdcAnswer: ADC app block invalid/empty (len=${cleanAppBlock.length}), skipping ADC")
                return Pair(bdcAnswer, null)
            }
            lastRemoteAdcOfferMid = extractMidFromSdp(cleanAppBlock.toString())
            Log.i(TAG, "SplitAdcAnswer extracted remote ADC mid: $lastRemoteAdcOfferMid")
            val adcSb = StringBuilder()
            adcSb.append(adcSessionHeader.toString())
            adcSb.append(cleanAppBlock.toString())
            adcAnswer = adcSb.toString()
        }
        return Pair(bdcAnswer, adcAnswer)
    }

    fun MergeAdcOffer(bdcSdp: String, adcSdp: String, isAnswer: Boolean = false): String {
        val bdcBlocks = splitSDPToMediaBlocks(bdcSdp)
        val adcBlocks = splitSDPToMediaBlocks(adcSdp)
        val sb = StringBuilder()
        sb.append(bdcBlocks.sessionHeader)
        bdcBlocks.audioBlock?.let { sb.append(it) }
        bdcBlocks.videoBlock?.let { sb.append(it) }
        if (bdcBlocks.appBlocks.isNotEmpty()) {
            sb.append(bdcBlocks.appBlocks[0])
        }
        if (adcBlocks.appBlocks.isNotEmpty()) {
            val appBlock = adcBlocks.appBlocks[0]
            if (isAnswer) {
                val targetMid = lastRemoteAdcOfferMid ?: "3"
                val normalizedAppBlock = replaceMidInSdp(appBlock, targetMid)
                Log.i(TAG, "MergeAdcOffer (Answer): Preserved ADC mid=$targetMid in cumulative SDP answer")
                sb.append(normalizedAppBlock)
            } else {
                val mid = getNextAdcMid()
                val normalizedAppBlock = replaceMidInSdp(appBlock, mid)
                Log.i(TAG, "MergeAdcOffer (Offer): Generated ADC mid=$mid in cumulative SDP offer")
                sb.append(normalizedAppBlock)
            }
        }
        return sb.toString()
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
     * Tạo ADC (Application Data Channel) — Stream ID 1000
     * Dùng để gửi/nhận sự kiện "thả tim" giữa A và B qua MF.
     */
    fun getNextAdcMid(): String {
        return currentAdcMid.toString()
    }

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
        val bdcSdp = bdcPC?.localDescription?.description
        val adcSdp = homeAdcPC?.localDescription?.description
        if (bdcSdp == null) return null
        if (adcSdp == null) return bdcSdp
        return MergeAdcOffer(bdcSdp, adcSdp)
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

    fun closeADC() {
        Log.i(TAG, "Closing Application Data Channel (ADC) and destroying PeerConnection...")
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
            
            currentAdcMid++ // Increment mid to prevent reuse
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


