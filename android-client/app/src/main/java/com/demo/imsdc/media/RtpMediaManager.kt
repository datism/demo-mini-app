package com.demo.imsdc.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import android.view.Surface
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.YuvHelper
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.experimental.xor

private const val TAG = "RtpMediaManager"
// RTP payload limit. 1200 is what WebRTC uses: the packet stays under the MTU of VPNs and mobile
// links (WireGuard ~1420, IPsec ~1400, some carriers less). Bigger packets were split into IP
// fragments, and losing any fragment lost the packet, freezing video until the next keyframe.
private const val MAX_RTP_PAYLOAD = 1200

class RtpMediaManager(
    private val context: Context,
    private val localAudioPort: Int = 40002,
    private val localVideoPort: Int = 40004,
    private val localIp: String? = null
) {
    // Media attributes
    private var remoteIP: String? = null
    private var remoteAudioPort: Int = 40002
    private var remoteVideoPort: Int = 40004

    // SSRC values generated randomly per instance to avoid reflection / collision
    private val localAudioSsrc = kotlin.random.Random.nextInt(100000, 999999)
    private val localVideoSsrc = kotlin.random.Random.nextInt(100000, 999999)

    // Sockets
    private var audioSocket: DatagramSocket? = null
    private var videoSocket: DatagramSocket? = null

    // Threads
    private var audioSenderThread: Thread? = null
    private var audioReceiverThread: Thread? = null
    /** When the far end's last call audio packet arrived (elapsedRealtime), 0 before the first. The far phone
     *  sends audio all call long (silence too), so a long gap means it is gone: CallActivity's media watchdog. */
    @Volatile var lastAudioRxMs = 0L
        private set
    private var videoReceiverThread: Thread? = null

    // State
    @Volatile
    private var isRunning = false

    // Packet counters for PoC validation
    private var audioPacketsSent = 0
    private var audioPacketsReceived = 0
    private var videoPacketsSent = 0
    private var videoPacketsReceived = 0
    private var loggedEncoderLayout = false
    private var encoderDiagFrames = 0
    @Volatile private var spsPpsConfig: ByteArray? = null
    private var videoSendErrors = 0
    @Volatile private var lastKeyframeForcedAt = 0L
    private var videoFramesDecoded = 0

    // WebRTC connection tracking
    private var localVideoTrack: VideoTrack? = null
    private var videoSink: VideoSink? = null

    // Codecs
    private var videoEncoder: MediaCodec? = null
    @Volatile
    private var videoDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

    // Video output surface
    @Volatile private var decoderSurface: Surface? = null

    // Guards videoDecoder's lifecycle: the receiver thread feeds it while the main thread replaces it.
    private val decoderLock = Any()
    // Set when a new decoder is created; the receiver thread then waits for (and asks for) a keyframe.
    @Volatile private var decoderNeedsKeyframe = false
    private var lastDecoderRecreateAt = 0L

    // Preallocated buffers for YUV rotation and NV12 transformation
    private val rotatedY = ByteBuffer.allocateDirect(307200)
    private val rotatedU = ByteBuffer.allocateDirect(76800)
    private val rotatedV = ByteBuffer.allocateDirect(76800)
    private val nv12Buffer = ByteBuffer.allocateDirect(460800)

    // G.711 A-law encoding utilities
    companion object {
        fun linearToAlaw(sample: Short): Byte {
            val s = sample.toInt()
            var sign = 0
            var temp = s
            if (temp < 0) {
                temp = -temp
                sign = 0x80
            }
            val clip = 32635
            if (temp > clip) temp = clip

            var exponent = 7
            var expMask = 0x4000
            while ((temp and expMask) == 0 && exponent > 0) {
                exponent--
                expMask = expMask shr 1
            }

            val mantissa = if (exponent == 0) {
                (temp ushr 4) and 0x0F
            } else {
                (temp ushr (exponent + 3)) and 0x0F
            }

            val alaw = sign or (exponent shl 4) or mantissa
            return (alaw xor 0xD5).toByte()
        }

        fun alawToLinear(alaw: Byte): Short {
            val a = (alaw.toInt() and 0xFF) xor 0xD5
            val sign = a and 0x80
            val exponent = (a and 0x70) ushr 4
            val mantissa = a and 0x0F

            var sample = if (exponent == 0) {
                (mantissa shl 4) or 8
            } else {
                ((mantissa or 0x10) shl (exponent + 3)) or (1 shl (exponent + 2))
            }

            if (sign != 0) {
                sample = -sample
            }
            return sample.toShort()
        }

        fun buildRtpHeader(payloadType: Byte, seqNum: Int, timestamp: Long, ssrc: Int, marker: Boolean = false): ByteArray {
            val header = ByteArray(12)
            header[0] = 0x80.toByte() // Version 2
            header[1] = ((payloadType.toInt() and 0x7F) or (if (marker) 0x80 else 0)).toByte()
            header[2] = (seqNum ushr 8).toByte()
            header[3] = (seqNum and 0xFF).toByte()
            header[4] = (timestamp ushr 24).toByte()
            header[5] = (timestamp ushr 16).toByte()
            header[6] = (timestamp ushr 8).toByte()
            header[7] = (timestamp and 0xFF).toByte()
            header[8] = (ssrc ushr 24).toByte()
            header[9] = (ssrc ushr 16).toByte()
            header[10] = (ssrc ushr 8).toByte()
            header[11] = (ssrc and 0xFF).toByte()
            return header
        }
    }

    /**
     * Set the remote destination for RTP streams.
     */
    fun setRemoteDestination(ip: String, audioPort: Int, videoPort: Int) {
        this.remoteIP = ip
        this.remoteAudioPort = audioPort
        this.remoteVideoPort = videoPort
        Log.i(TAG, "Remote destination configured: IP=$ip, AudioPort=$audioPort, VideoPort=$videoPort")
        if (isRunning && audioSocket != null) {
            sendNatHolePunchPackets()
        }
    }

    /**
     * Send NAT hole-punch packets (silence RTP frames) to help punch through NAT/firewalls immediately.
     */
    private fun sendNatHolePunchPackets() {
        val ipStr = remoteIP ?: return
        val socket = audioSocket ?: return
        if (socket.isClosed) return

        Thread {
            try {
                val targetAddr = InetAddress.getByName(ipStr)
                val silencePayload = ByteArray(160) { 0xD5.toByte() } // G.711 A-law silence
                for (i in 0 until 3) {
                    val header = buildRtpHeader(8, i, (i * 160).toLong(), localAudioSsrc)
                    val packetBytes = ByteArray(12 + silencePayload.size)
                    System.arraycopy(header, 0, packetBytes, 0, 12)
                    System.arraycopy(silencePayload, 0, packetBytes, 12, silencePayload.size)
                    val packet = DatagramPacket(packetBytes, packetBytes.size, targetAddr, remoteAudioPort)
                    socket.send(packet)
                    Thread.sleep(20)
                }
                Log.i(TAG, "Sent 3 NAT hole punching packets to $ipStr:$remoteAudioPort")
            } catch (e: Exception) {
                Log.w(TAG, "NAT hole punch error: ${e.message}")
            }
        }.start()
    }

    /**
     * Bind a UDP socket to [addr], falling back to the wildcard address on [port].
     * A failed bind() closes the socket, so the fallback needs a fresh one.
     */
    private fun bindRtpSocket(name: String, addr: InetSocketAddress, port: Int, bufferSize: Int = 0): DatagramSocket {
        fun open(target: InetSocketAddress): DatagramSocket {
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = true
                if (bufferSize > 0) {
                    try { socket.receiveBufferSize = bufferSize } catch (_: Exception) {}
                    try { socket.sendBufferSize = bufferSize } catch (_: Exception) {}
                }
                socket.bind(target)
                return socket
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }
        return try {
            open(addr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind $name to $addr, falling back to wildcard: ${e.message}")
            open(InetSocketAddress(port))
        }
    }

    /**
     * Start audio and video RTP sender & receiver.
     */
    @Synchronized
    fun start(localVideoTrack: VideoTrack?, surface: Surface?) {
        if (localVideoTrack != null) {
            this.localVideoTrack = localVideoTrack
        }
        if (surface != null && surface.isValid) {
            this.decoderSurface = surface
        }

        if (!isRunning) {
            Log.i(TAG, "Starting RTP Media Manager (localAudioPort=$localAudioPort, localVideoPort=$localVideoPort, localIp=$localIp)...")
            try {
                // Bind local sockets with SO_REUSEADDR to prevent EADDRINUSE
                // Explicitly bind to localIp if provided to ensure UDP packets use the exact IP announced in SDP
                val isLocalIpValid = !localIp.isNullOrBlank() &&
                    !localIp.contains(":") &&
                    localIp != "0.0.0.0" &&
                    localIp != "127.0.0.1" &&
                    localIp != "::"

                val bindAudioAddr = if (isLocalIpValid) {
                    try {
                        InetSocketAddress(InetAddress.getByName(localIp), localAudioPort)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve localIp=$localIp for audio, falling back to wildcard: ${e.message}")
                        InetSocketAddress(localAudioPort)
                    }
                } else {
                    InetSocketAddress(localAudioPort)
                }

                val bindVideoAddr = if (isLocalIpValid) {
                    try {
                        InetSocketAddress(InetAddress.getByName(localIp), localVideoPort)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve localIp=$localIp for video, falling back to wildcard: ${e.message}")
                        InetSocketAddress(localVideoPort)
                    }
                } else {
                    InetSocketAddress(localVideoPort)
                }

                audioSocket = bindRtpSocket("audioSocket", bindAudioAddr, localAudioPort)
                // An IDR frame arrives as a burst of ~30-60 packets; the default buffer can overflow.
                videoSocket = bindRtpSocket("videoSocket", bindVideoAddr, localVideoPort, 1 shl 20)
                Log.i(TAG, "RTP Sockets bound successfully: Audio=${audioSocket?.localSocketAddress}, Video=${videoSocket?.localSocketAddress}")
                isRunning = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind RTP sockets: ${e.message}", e)
                try { audioSocket?.close() } catch (_: Exception) {}
                try { videoSocket?.close() } catch (_: Exception) {}
                audioSocket = null
                videoSocket = null
                isRunning = false
                return
            }

            // Start Audio components
            startAudioReceiver()
            startAudioSender()
            sendNatHolePunchPackets()
        }

        // Start or update Video components
        synchronized(decoderLock) {
            if (videoDecoder == null && decoderSurface?.isValid == true && videoSocket != null) {
                startVideoReceiver()
            }
        }

        if (videoEncoder == null && this.localVideoTrack != null && videoSocket != null) {
            startVideoSender()
        }
    }

    @Synchronized
    fun onSurfaceDestroyed(surface: Surface? = null) {
        // Two views can show the call video (background, Vẽ/Chia sẻ tile): one going away while the
        // decoder draws to the other must not stop it.
        if (surface != null && surface !== decoderSurface) {
            Log.i(TAG, "Surface destroyed, but the decoder draws elsewhere: keeping it")
            return
        }
        Log.i(TAG, "Surface destroyed, releasing video decoder safely")
        // The receiver thread may be inside feedDecoder() with this decoder: wait for it.
        synchronized(decoderLock) {
            releaseDecoderLocked()
            decoderSurface = null
        }
        // videoReceiverThread remains alive to receive UDP packets
    }

    @Synchronized
    fun updateDecoderSurface(surface: Surface?) {
        Log.i(TAG, "Updating decoder surface: valid=${surface?.isValid}")
        if (surface == null || !surface.isValid) return
        synchronized(decoderLock) {
            // onStart calls this on every return to the screen. Rebuilding a working decoder
            // freezes the picture until the next keyframe, so keep it.
            if (surface === decoderSurface && videoDecoder != null) {
                Log.i(TAG, "Decoder already renders to this surface, keeping it")
                return
            }
            this.decoderSurface = surface
            if (!isRunning || videoSocket == null) return
            releaseDecoderLocked()
            if (videoReceiverThread?.isAlive == true) {
                reinitDecoder(surface)
            } else {
                startVideoReceiver()
            }
        }
    }

    /** Caller holds [decoderLock]. stop() and release() are tried separately so a failed stop never leaks the codec. */
    private fun releaseDecoderLocked() {
        val old = videoDecoder ?: return
        videoDecoder = null
        try { old.stop() } catch (e: Exception) { Log.w(TAG, "Decoder stop failed: ${e.message}") }
        try { old.release() } catch (e: Exception) { Log.w(TAG, "Decoder release failed: ${e.message}") }
    }

    /** Caller holds [decoderLock]. A fresh decoder can only start from a keyframe: the receiver asks for one. */
    private fun reinitDecoder(surface: Surface): Boolean {
        return try {
            val decoder = MediaCodec.createDecoderByType("video/avc")
            val format = MediaFormat.createVideoFormat("video/avc", 480, 640)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            videoDecoder = decoder
            decoderNeedsKeyframe = true
            Log.i(TAG, "H.264 Video Decoder initialized on surface")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init video decoder: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun updateLocalVideoTrack(track: VideoTrack?) {
        Log.i(TAG, "Updating local video track: $track")
        if (track != null) {
            // A new track: unhook the old one, or both would feed the encoder at once.
            val old = localVideoTrack
            val sink = videoSink
            if (old != null && old !== track && sink != null) {
                try { old.removeSink(sink) } catch (_: Exception) {}
            }
            this.localVideoTrack = track
            if (isRunning && videoEncoder == null && videoSocket != null) {
                startVideoSender()
            } else if (isRunning && videoSink != null) {
                track.addSink(videoSink)
            }
        }
    }

    /**
     * Stop and release all media components.
     */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        Log.i(TAG, "Stopping RTP Media Manager...")

        // Remove video sink. A track already disposed throws, which would skip closing the sockets below.
        localVideoTrack?.let { track ->
            videoSink?.let { sink ->
                try { track.removeSink(sink) } catch (e: Exception) { Log.w(TAG, "removeSink failed: ${e.message}") }
            }
        }
        videoSink = null

        // Stop sockets to interrupt blocking reads
        try { audioSocket?.close() } catch (_: Exception) {}
        try { videoSocket?.close() } catch (_: Exception) {}

        // Wait for threads
        try {
            audioSenderThread?.join(500)
            audioReceiverThread?.join(500)
            videoReceiverThread?.join(500)
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for threads to stop", e)
        }

        // Release components
        releaseAudio()
        releaseVideo()

        audioSocket = null
        videoSocket = null
        Log.i(TAG, "RTP Media Manager stopped.")
    }

    // ─── AUDIO SENDER (Microphone -> PCMA -> RTP) ────────────────────────────

    private fun startAudioSender() {
        audioSenderThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            
            val minBufSize = AudioRecord.getMinBufferSize(
                8000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = Math.max(minBufSize, 640)
            
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    8000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord initialization failed.")
                    return@Thread
                }
                
                audioRecord?.startRecording()
                Log.i(TAG, "AudioRecord started (VOICE_COMMUNICATION with Hardware AEC).")
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for recording audio: ${e.message}")
                return@Thread
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord start error: ${e.message}", e)
                return@Thread
            }

            val pcmBuffer = ShortArray(160) // 20ms frame
            val rtpPayload = ByteArray(160)
            var seqNum = 0
            var timestamp = 0L
            val ssrc = localAudioSsrc

            val socket = audioSocket

            while (isRunning) {
                val ipStr = remoteIP
                val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                if (read <= 0) continue

                // Encode to A-law (PCMA)
                for (i in 0 until read) {
                    rtpPayload[i] = linearToAlaw(pcmBuffer[i])
                }

                // Fill silence if read was short
                if (read < pcmBuffer.size) {
                    for (i in read until pcmBuffer.size) {
                        rtpPayload[i] = 0x55.toByte()
                    }
                }

                // Send RTP Packet
                if (socket != null && !socket.isClosed && ipStr != null) {
                    try {
                        val header = buildRtpHeader(8, seqNum++, timestamp, ssrc)
                        val packetBytes = ByteArray(12 + rtpPayload.size)
                        System.arraycopy(header, 0, packetBytes, 0, 12)
                        System.arraycopy(rtpPayload, 0, packetBytes, 12, rtpPayload.size)

                        val packet = DatagramPacket(
                            packetBytes,
                            packetBytes.size,
                            InetAddress.getByName(ipStr),
                            remoteAudioPort
                        )
                        socket.send(packet)
                        audioPacketsSent++
                        if (audioPacketsSent % 100 == 0) {
                            Log.d(TAG, "📊 Audio Packets Sent: $audioPacketsSent")
                        }
                    } catch (e: Exception) {
                        // Socket might be closed, ignore
                    }
                }
                timestamp += 160
            }
        }.apply { start() }
    }

    // ─── AUDIO RECEIVER (RTP -> PCMA -> Playback) ────────────────────────────

    private fun startAudioReceiver() {
        audioReceiverThread = Thread {
            val minBufSize = AudioTrack.getMinBufferSize(
                8000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            try {
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(8000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    // Headroom for Wi-Fi jitter bursts (non-blocking writes drop what does not fit).
                    .setBufferSizeInBytes(Math.max(minBufSize * 2, 1600))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                audioTrack?.play()
                Log.i(TAG, "AudioTrack started.")
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack initialization failed: ${e.message}", e)
                return@Thread
            }

            val socket = audioSocket
            val packetBuf = ByteArray(2048)
            val pcmOutput = ShortArray(2048)
            // The two phones' audio clocks differ slightly. When the far end records faster than this
            // phone plays, a blocking write() let the surplus queue up in the socket buffer and the voice
            // fell further behind the longer the call ran. Write without blocking: once the track's buffer
            // is full the rest of the packet is dropped, so the delay stays bounded by that buffer.
            // (Don't cap below the buffer size instead: a stream track only starts once its buffer is full.)
            var lateDropped = 0

            while (isRunning && socket != null && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(packetBuf, packetBuf.size)
                    socket.receive(packet)

                    val len = packet.length
                    if (len <= 12) continue // Min RTP length

                    // Verify payload type is PCMA (8)
                    val pt = packetBuf[1].toInt() and 0x7F
                    if (pt != 8) continue

                    // Extract SSRC from RTP header (bytes 8..11) to drop loopback packets
                    val packetSsrc = ((packetBuf[8].toInt() and 0xFF) shl 24) or
                            ((packetBuf[9].toInt() and 0xFF) shl 16) or
                            ((packetBuf[10].toInt() and 0xFF) shl 8) or
                            (packetBuf[11].toInt() and 0xFF)
                    if (packetSsrc == localAudioSsrc) {
                        // Drop loopback packet sent by our own sender
                        continue
                    }
                    lastAudioRxMs = android.os.SystemClock.elapsedRealtime()

                    val payloadLen = len - 12
                    val decodeSamples = minOf(payloadLen, pcmOutput.size)
                    for (i in 0 until decodeSamples) {
                        pcmOutput[i] = alawToLinear(packetBuf[12 + i])
                    }

                    val track = audioTrack
                    if (track != null) {
                        val w = track.write(pcmOutput, 0, decodeSamples, AudioTrack.WRITE_NON_BLOCKING)
                        if (w in 0 until decodeSamples) {
                            lateDropped++
                            if (lateDropped == 1 || lateDropped % 50 == 0) {
                                Log.w(TAG, "Audio playout buffer full, dropped late audio $lateDropped times")
                            }
                        }
                    }
                    audioPacketsReceived++
                    if (audioPacketsReceived % 100 == 0) {
                        Log.d(TAG, "📊 Audio Packets Received: $audioPacketsReceived")
                    }
                } catch (e: Exception) {
                    // Socket closed, exit loop
                }
            }
        }.apply { start() }
    }

    // ─── VIDEO SENDER (Camera Feed -> H.264 Encoder -> FU-A -> RTP) ──────────

    // The MediaTek hardware H.264 encoder (e.g. OPPO Reno13) produces grey video with a green right-edge
    // band even though the Image planes we write read back correctly. Use the software encoder there.
    private val preferSoftwareEncoderOnMtk = true

    private fun createVideoEncoder(): MediaCodec {
        val hw = MediaCodec.createEncoderByType("video/avc")
        val hwName = hw.name
        if (!preferSoftwareEncoderOnMtk || !hwName.contains("mtk", ignoreCase = true)) return hw
        return try {
            val sw = MediaCodec.createByCodecName("c2.android.avc.encoder")
            hw.release()
            Log.i(TAG, "🔬 Using software encoder ${sw.name} instead of $hwName")
            sw
        } catch (e: Exception) {
            Log.w(TAG, "Software encoder unavailable (${e.message}), keeping $hwName")
            hw
        }
    }

    private fun buildEncoderFormat(prepend: Boolean, iFrame: Boolean, color: Int): MediaFormat =
        MediaFormat.createVideoFormat("video/avc", 480, 640).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
            setInteger(MediaFormat.KEY_BIT_RATE, 800000) // 800kbps is crisp and smooth
            setInteger(MediaFormat.KEY_FRAME_RATE, 25)
            if (iFrame) setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            if (prepend) setInteger("prepend-sps-pps-to-idr-frames", 1)
        }

    /** The software encoder rejects "prepend-sps-pps-to-idr-frames"; fall back to the formats it accepts. */
    private fun configureEncoderWithFallbacks() {
        var enc = videoEncoder ?: return
        val flex = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        val variants = listOf(
            Triple("full", true, true) to flex,
            Triple("no-prepend", false, true) to flex,
            Triple("no-prepend-no-iframe", false, false) to flex,
            Triple("planar", false, true) to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            Triple("semiplanar", false, true) to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        )
        var lastError: Exception? = null
        for ((v, color) in variants) {
            try {
                enc.configure(buildEncoderFormat(v.second, v.third, color), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "🔬 Encoder ${enc.name} configured with variant '${v.first}' color=$color")
                videoEncoder = enc
                return
            } catch (e: IllegalArgumentException) {
                lastError = e
                Log.w(TAG, "🔬 Encoder ${enc.name} rejected variant '${v.first}' color=$color: ${e.message}")
                try {
                    val caps = enc.codecInfo.getCapabilitiesForType("video/avc")
                    val vc = caps.videoCapabilities
                    Log.w(TAG, "🔬 caps colorFormats=${caps.colorFormats.toList()} bitrate=${vc.bitrateRange} " +
                            "w=${vc.supportedWidths} h=${vc.supportedHeights} fps=${vc.supportedFrameRates} " +
                            "align=${vc.widthAlignment}x${vc.heightAlignment}")
                } catch (_: Exception) {}
                val name = enc.name
                try { enc.release() } catch (_: Exception) {}
                enc = MediaCodec.createByCodecName(name)
                videoEncoder = enc
            }
        }
        throw lastError ?: IllegalStateException("no encoder variant accepted")
    }

    private fun startVideoSender() {
        if (videoEncoder != null) return
        val track = localVideoTrack ?: return
        try {
            spsPpsConfig = null
            videoEncoder = createVideoEncoder()
            configureEncoderWithFallbacks()
            videoEncoder?.start()
            Log.i(TAG, "H.264 Video Encoder initialized.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video encoder: ${e.message}", e)
            return
        }

        // Setup loop to read encoded H.264 buffers and send them via RTP
        val encoderOutputThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            var seqNum = 0
            val ssrc = localVideoSsrc
            val socket = videoSocket

            while (isRunning && socket != null && !socket.isClosed) {
                val encoder = videoEncoder ?: break
                val ipStr = remoteIP
                try {
                    val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "🔬 EncOut format: ${encoder.outputFormat}")
                    }
                    if (outputBufferIndex >= 0) {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 && ipStr != null) {
                            val naluBytes = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(naluBytes)

                            // Find and packetize H.264 NALUs from the byte stream
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (isConfig) {
                                spsPpsConfig = naluBytes
                            } else if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                // Software encoders emit SPS/PPS once at start; a receiver that missed
                                // them can never decode, so repeat them before every keyframe.
                                val cfg = spsPpsConfig
                                val startsWithSps = naluBytes.size > 4 && (naluBytes[4].toInt() and 0x1F) == 7
                                if (cfg != null && !startsWithSps) {
                                    seqNum = packetizeAndSendH264(cfg, bufferInfo.presentationTimeUs / 1000, seqNum, ssrc, socket, ipStr, false)
                                }
                            }
                            seqNum = packetizeAndSendH264(naluBytes, bufferInfo.presentationTimeUs / 1000, seqNum, ssrc, socket, ipStr, !isConfig)
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                    Log.w(TAG, "encoderOutputThread exception: ${e.message}")
                }
            }
            Log.i(TAG, "Video encoderOutputThread terminated.")
        }
        encoderOutputThread.start()

        // Hook custom VideoSink into WebRTC camera track to feed YUV frames into Encoder
        videoSink = VideoSink { frame ->
            if (!isRunning) return@VideoSink
            val encoder = videoEncoder ?: return@VideoSink
            
            val i420 = frame.buffer.toI420() ?: return@VideoSink
            val rotation = frame.rotation
            // An input slot taken but never queued is gone for good; after a few the encoder stops.
            var pendingInput = -1

            try {
                val outWidth = 480
                val outHeight = 640

                rotatedY.clear()
                rotatedU.clear()
                rotatedV.clear()
                
                if (rotation == 90 || rotation == 270) {
                    YuvHelper.I420Rotate(
                        i420.dataY, i420.strideY,
                        i420.dataU, i420.strideU,
                        i420.dataV, i420.strideV,
                        rotatedY, outWidth,
                        rotatedU, outWidth / 2,
                        rotatedV, outWidth / 2,
                        i420.width, i420.height,
                        rotation
                    )
                } else {
                    YuvHelper.I420Rotate(
                        i420.dataY, i420.strideY,
                        i420.dataU, i420.strideU,
                        i420.dataV, i420.strideV,
                        rotatedY, i420.width,
                        rotatedU, i420.width / 2,
                        rotatedV, i420.width / 2,
                        i420.width, i420.height,
                        rotation
                    )
                }
                
                rotatedY.rewind()
                rotatedU.rewind()
                rotatedV.rewind()

                val nv12Width = if (rotation == 90 || rotation == 270) outWidth else i420.width
                val nv12Height = if (rotation == 90 || rotation == 270) outHeight else i420.height

                val inputBufferIndex = encoder.dequeueInputBuffer(5000)
                if (inputBufferIndex < 0) return@VideoSink
                pendingInput = inputBufferIndex
                // COLOR_FormatYUV420Flexible must be filled through the Image API: chroma layout
                // (planar I420, semi-planar NV12, padded strides) differs per chip, and copying raw
                // NV12 into an encoder that expects another layout loses the colour (black-and-white).
                val image = try { encoder.getInputImage(inputBufferIndex) } catch (_: Exception) { null }
                if (image != null) {
                    if (!loggedEncoderLayout) {
                        loggedEncoderLayout = true
                        val pl = image.planes
                        Log.i(TAG, "🎨 Encoder ${encoder.name} input ${image.width}x${image.height} " +
                                "Y(row=${pl[0].rowStride},px=${pl[0].pixelStride}) " +
                                "U(row=${pl[1].rowStride},px=${pl[1].pixelStride}) " +
                                "V(row=${pl[2].rowStride},px=${pl[2].pixelStride})")
                    }
                    val written = try {
                        writeI420ToImage(image, rotatedY, rotatedU, rotatedV, nv12Width, nv12Height)
                        if (encoderDiagFrames < 3 || encoderDiagFrames % 150 == 0) {
                            logEncoderInputDiag(frame.rotation, i420.width, i420.height, image, nv12Width, nv12Height)
                        }
                        encoderDiagFrames++
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Encoder image write failed: ${e.message}")
                        false
                    }
                    // Always hand the buffer back, or the encoder runs out of input slots.
                    pendingInput = -1
                    encoder.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        if (written) image.width * image.height * 3 / 2 else 0,
                        frame.timestampNs / 1000,
                        0
                    )
                    return@VideoSink
                }

                // Fallback for codecs without Image input: semi-planar NV12, as before.
                nv12Buffer.clear()

                YuvHelper.I420ToNV12(
                    rotatedY, nv12Width,
                    rotatedU, nv12Width / 2,
                    rotatedV, nv12Width / 2,
                    nv12Buffer,
                    nv12Width, nv12Height
                )
                
                nv12Buffer.rewind()
                val dataSize = nv12Width * nv12Height * 3 / 2
                val nv12Bytes = ByteArray(dataSize)
                nv12Buffer.get(nv12Bytes)

                run {
                    val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(nv12Bytes)
                        pendingInput = -1
                        encoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            nv12Bytes.size,
                            frame.timestampNs / 1000,
                            0
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "VideoSink error: ${e.message}", e)
            } finally {
                if (pendingInput >= 0) {
                    try { encoder.queueInputBuffer(pendingInput, 0, 0, frame.timestampNs / 1000, 0) } catch (_: Exception) {}
                }
                i420.release()
            }
        }
        track.addSink(videoSink)
        Log.i(TAG, "VideoSink hook added to localVideoTrack.")
    }

    /**
     * Packetizes an H.264 byte stream into RTP packets (supporting FU-A fragmentation).
     */
    private fun packetizeAndSendH264(
        stream: ByteArray,
        ptsMs: Long,
        startSeqNum: Int,
        ssrc: Int,
        socket: DatagramSocket,
        ipStr: String?,
        endOfFrame: Boolean
    ): Int {
        if (ipStr == null) return startSeqNum
        var seqNum = startSeqNum
        val timestamp = ptsMs * 90 // 90kHz clock for H.264

        // Find NAL units (delimited by 0x00000001 or 0x000001)
        val nalus = splitNalus(stream)
        val targetAddr = try { InetAddress.getByName(ipStr) } catch (_: Exception) { return startSeqNum }
        val lastNalu = nalus.indexOfLast { it.isNotEmpty() }
        var sentInFrame = 0
        // A keyframe is 20-40 packets; sent back-to-back the burst overflows Wi-Fi/VPN queues
        // and a few packets in a row are dropped. Spread it out (well within the 40 ms frame time).
        fun pace() {
            sentInFrame++
            if (sentInFrame % 4 == 0) try { Thread.sleep(1) } catch (_: InterruptedException) {}
        }

        for ((idx, nalu) in nalus.withIndex()) {
            if (nalu.isEmpty()) continue
            val naluHeader = nalu[0]
            val lastOfFrame = endOfFrame && idx == lastNalu

            if (nalu.size <= MAX_RTP_PAYLOAD) {
                // Single NAL unit packet
                try {
                    val rtpHeader = buildRtpHeader(97, seqNum, timestamp, ssrc, lastOfFrame)
                    val packetBytes = ByteArray(12 + nalu.size)
                    System.arraycopy(rtpHeader, 0, packetBytes, 0, 12)
                    System.arraycopy(nalu, 0, packetBytes, 12, nalu.size)

                    val packet = DatagramPacket(
                        packetBytes,
                        packetBytes.size,
                        targetAddr,
                        remoteVideoPort
                    )
                    socket.send(packet)
                    videoPacketsSent++
                    if (videoPacketsSent == 1 || videoPacketsSent % 50 == 0) {
                        Log.d(TAG, "📊 Video Packets Sent (Single NAL): $videoPacketsSent to $ipStr:$remoteVideoPort")
                    }
                } catch (e: Exception) {
                    noteVideoSendError(e)
                }
                seqNum = (seqNum + 1) and 0xFFFF
                pace()
            } else {
                // Fragmented using FU-A
                val payloadSize = MAX_RTP_PAYLOAD - 2 // FU indicator + FU header
                var sentBytes = 1 // skip original NALU header
                val totalPayload = nalu.size - 1

                while (sentBytes <= totalPayload) {
                    val chunkSize = Math.min(payloadSize, totalPayload - sentBytes + 1)
                    val isStart = sentBytes == 1
                    val isEnd = sentBytes + chunkSize - 1 == totalPayload

                    // FU indicator
                    val fuIndicator = ((naluHeader.toInt() and 0xE0) or 28).toByte()
                    // FU header
                    var fuHeaderVal = naluHeader.toInt() and 0x1F
                    if (isStart) fuHeaderVal = fuHeaderVal or 0x80
                    if (isEnd) fuHeaderVal = fuHeaderVal or 0x40
                    val fuHeader = fuHeaderVal.toByte()

                    try {
                        val rtpHeader = buildRtpHeader(97, seqNum, timestamp, ssrc, lastOfFrame && isEnd)
                        val packetBytes = ByteArray(12 + 2 + chunkSize)
                        System.arraycopy(rtpHeader, 0, packetBytes, 0, 12)
                        packetBytes[12] = fuIndicator
                        packetBytes[13] = fuHeader
                        System.arraycopy(nalu, sentBytes, packetBytes, 14, chunkSize)

                        val packet = DatagramPacket(
                            packetBytes,
                            packetBytes.size,
                            targetAddr,
                            remoteVideoPort
                        )
                        socket.send(packet)
                        videoPacketsSent++
                        if (videoPacketsSent == 1 || videoPacketsSent % 50 == 0) {
                            Log.d(TAG, "📊 Video Packets Sent (FU-A): $videoPacketsSent to $ipStr:$remoteVideoPort")
                        }
                    } catch (e: Exception) {
                        noteVideoSendError(e)
                    }
                    seqNum = (seqNum + 1) and 0xFFFF
                    sentBytes += chunkSize
                    pace()
                }
            }
        }
        return seqNum
    }

    // RTCP PLI (RFC 4585): ask the far end for a keyframe instead of waiting up to 1 s for the next one.
    private fun sendKeyframeRequest(socket: DatagramSocket) {
        val ip = remoteIP ?: return
        try {
            val ssrc = localVideoSsrc
            val pli = byteArrayOf(
                0x81.toByte(), 206.toByte(), 0, 2,
                (ssrc ushr 24).toByte(), (ssrc ushr 16).toByte(), (ssrc ushr 8).toByte(), ssrc.toByte(),
                0, 0, 0, 0
            )
            socket.send(DatagramPacket(pli, pli.size, InetAddress.getByName(ip), remoteVideoPort))
            Log.i(TAG, "📨 Sent keyframe request (PLI)")
        } catch (e: Exception) {
            Log.w(TAG, "PLI send failed: ${e.message}")
        }
    }

    private fun forceKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastKeyframeForcedAt < 200) return
        val encoder = videoEncoder ?: return
        lastKeyframeForcedAt = now
        try {
            encoder.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            Log.i(TAG, "🔑 Keyframe requested by far end (PLI), encoding one now")
        } catch (e: Exception) {
            Log.w(TAG, "Keyframe request failed: ${e.message}")
        }
    }

    // A failed send is a lost packet for the far end; it used to be swallowed silently.
    private fun noteVideoSendError(e: Exception) {
        videoSendErrors++
        if (videoSendErrors == 1 || videoSendErrors % 20 == 0) {
            Log.w(TAG, "⚠️ Video RTP send failed $videoSendErrors times (last: ${e.message})")
        }
    }

    // Copies tightly packed I420 planes into an encoder input Image, honouring its strides.
    private fun writeI420ToImage(image: android.media.Image, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, srcW: Int, srcH: Int) {
        val w = minOf(srcW, image.width)
        val h = minOf(srcH, image.height)
        val planes = image.planes
        copyPlane(y, srcW, planes[0], w, h)
        copyPlane(u, srcW / 2, planes[1], w / 2, h / 2)
        copyPlane(v, srcW / 2, planes[2], w / 2, h / 2)
    }

    /** Mean of a sampled grid of a plane (honours row/pixel stride), or -1 if nothing could be read. */
    private fun samplePlaneMean(buf: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int): Int {
        var sum = 0L
        var n = 0
        val limit = buf.limit()
        for (r in 0 until h step maxOf(1, h / 16)) {
            for (c in 0 until w step maxOf(1, w / 16)) {
                val idx = r * rowStride + c * pixelStride
                if (idx < limit) { sum += buf.get(idx).toInt() and 0xFF; n++ }
            }
        }
        return if (n == 0) -1 else (sum / n).toInt()
    }

    /**
     * Diagnostic for grey / green-edge video from MediaTek encoders. Compares what we rotated
     * (source) with what the encoder's Image planes hold after the write: a source chroma mean far
     * from 128 with an Image chroma mean of 128 or 0 means the colour never reached the encoder.
     */
    private fun logEncoderInputDiag(rotation: Int, camW: Int, camH: Int, image: android.media.Image, outW: Int, outH: Int) {
        val pl = image.planes
        val srcY = samplePlaneMean(rotatedY, outW, 1, outW, outH)
        val srcU = samplePlaneMean(rotatedU, outW / 2, 1, outW / 2, outH / 2)
        val srcV = samplePlaneMean(rotatedV, outW / 2, 1, outW / 2, outH / 2)
        val dstY = samplePlaneMean(pl[0].buffer, pl[0].rowStride, pl[0].pixelStride, minOf(outW, image.width), minOf(outH, image.height))
        val dstU = samplePlaneMean(pl[1].buffer, pl[1].rowStride, pl[1].pixelStride, minOf(outW, image.width) / 2, minOf(outH, image.height) / 2)
        val dstV = samplePlaneMean(pl[2].buffer, pl[2].rowStride, pl[2].pixelStride, minOf(outW, image.width) / 2, minOf(outH, image.height) / 2)
        fun range(b: ByteBuffer, n: Int): String {
            var lo = 255; var hi = 0
            for (i in 0 until n step 97) { val v = b.get(i).toInt() and 0xFF; if (v < lo) lo = v; if (v > hi) hi = v }
            return "$lo-$hi"
        }
        val ranges = "rangeYUV=${range(rotatedY, outW * outH)}/${range(rotatedU, outW * outH / 4)}/${range(rotatedV, outW * outH / 4)}"
        Log.i(TAG, "🔬 EncDiag $ranges rot=$rotation cam=${camW}x$camH out=${outW}x$outH img=${image.width}x${image.height} " +
                "srcYUV=$srcY/$srcU/$srcV dstYUV=$dstY/$dstU/$dstV " +
                "bufY(pos=${pl[0].buffer.position()} lim=${pl[0].buffer.limit()} cap=${pl[0].buffer.capacity()}) " +
                "bufU(pos=${pl[1].buffer.position()} lim=${pl[1].buffer.limit()} cap=${pl[1].buffer.capacity()}) " +
                "bufV(pos=${pl[2].buffer.position()} lim=${pl[2].buffer.limit()} cap=${pl[2].buffer.capacity()}) " +
                "uvSameBacking=${pl[1].buffer === pl[2].buffer}")
    }

    private fun copyPlane(src: ByteBuffer, srcStride: Int, plane: android.media.Image.Plane, w: Int, h: Int) {
        val dst = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1) {
            val row = ByteArray(w)
            for (r in 0 until h) {
                src.position(r * srcStride)
                src.get(row, 0, w)
                dst.position(r * rowStride)
                dst.put(row, 0, w)
            }
        } else {
            // Semi-planar (U and V interleaved): each sample goes every pixelStride bytes.
            // The last sample of the last row may sit past the buffer end, so bound by limit.
            val limit = dst.limit()
            for (r in 0 until h) {
                val srcRow = r * srcStride
                val dstRow = r * rowStride
                for (c in 0 until w) {
                    val idx = dstRow + c * pixelStride
                    if (idx < limit) dst.put(idx, src.get(srcRow + c))
                }
            }
        }
        src.rewind()
    }

    private fun splitNalus(stream: ByteArray): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        var i = 0
        val n = stream.size
        while (i < n) {
            // Find start code 00 00 00 01 or 00 00 01
            if (i + 3 < n && stream[i] == 0.toByte() && stream[i+1] == 0.toByte() && stream[i+2] == 0.toByte() && stream[i+3] == 1.toByte()) {
                val start = i + 4
                i += 4
                while (i < n) {
                    if (i + 3 < n && stream[i] == 0.toByte() && stream[i+1] == 0.toByte() && stream[i+2] == 0.toByte() && stream[i+3] == 1.toByte()) {
                        break
                    }
                    if (i + 2 < n && stream[i] == 0.toByte() && stream[i+1] == 0.toByte() && stream[i+2] == 1.toByte()) {
                        break
                    }
                    i++
                }
                list.add(stream.copyOfRange(start, i))
            } else if (i + 2 < n && stream[i] == 0.toByte() && stream[i+1] == 0.toByte() && stream[i+2] == 1.toByte()) {
                val start = i + 3
                i += 3
                while (i < n) {
                    if (i + 3 < n && stream[i] == 0.toByte() && stream[i+1] == 0.toByte() && stream[i+2] == 0.toByte() && stream[i+3] == 1.toByte()) {
                        break
                    }
                    if (i + 2 < n && stream[i] == 0.toByte() && stream[i+1] == 0.toByte() && stream[i+2] == 1.toByte()) {
                        break
                    }
                    i++
                }
                list.add(stream.copyOfRange(start, i))
            } else {
                i++
            }
        }
        return list
    }

    // ─── VIDEO RECEIVER (RTP -> H.264 NALUs -> Decoder -> Surface) ──────────

    private fun startVideoReceiver() {
        if (videoDecoder != null) return
        val surface = decoderSurface
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "Decoder surface is null or invalid. Cannot start video receiver yet.")
            return
        }

        reinitDecoder(surface) // on failure feedDecoder() retries; the thread must still run (it also reads PLIs)
        if (videoReceiverThread?.isAlive == true) return

        videoReceiverThread = Thread {
            val socket = videoSocket
            val packetBuf = ByteArray(2048)
            val naluStream = ByteArrayOutputStream()
            var lastFuTimestamp = -1L
            var lostPackets = 0
            var waitingForKeyframe = false
            var skippedNalus = 0

            // After any loss, later frames reference the missing data and decode as smeared
            // blocks. Freeze on the last good picture until the next keyframe (SPS/IDR) instead.
            fun deliver(nalu: ByteArray, ptsMs: Long) {
                val type = if (nalu.size > 4) nalu[4].toInt() and 0x1F else 0
                if (waitingForKeyframe) {
                    if (type == 5 || type == 7) {
                        waitingForKeyframe = false
                        Log.i(TAG, "🔑 Keyframe received, video resumes (skipped $skippedNalus NALUs)")
                        skippedNalus = 0
                    } else {
                        skippedNalus++
                        return
                    }
                }
                if (!feedDecoder(nalu, ptsMs) && videoDecoder != null) waitingForKeyframe = true
            }

            // Depacketize one in-order RTP packet into NALUs.
            fun process(pkt: ByteArray, len: Int) {
                val timestamp = ((pkt[4].toLong() and 0xFF) shl 24) or
                        ((pkt[5].toLong() and 0xFF) shl 16) or
                        ((pkt[6].toLong() and 0xFF) shl 8) or
                        (pkt[7].toLong() and 0xFF)

                val payloadHeader = pkt[12].toInt() and 0xFF
                val naluType = payloadHeader and 0x1F

                if (naluType == 28) {
                    // FU-A Fragmentation Unit
                    val fuHeader = pkt[13].toInt() and 0xFF
                    val isStart = (fuHeader and 0x80) != 0
                    val isEnd = (fuHeader and 0x40) != 0
                    val originalType = fuHeader and 0x1F

                    if (isStart) {
                        naluStream.reset()
                        // Prepend start code 00 00 00 01
                        naluStream.write(byteArrayOf(0, 0, 0, 1))
                        // Reconstruct NALU header
                        val reconstructedHeader = (payloadHeader and 0xE0) or originalType
                        naluStream.write(reconstructedHeader)
                        lastFuTimestamp = timestamp
                    }

                    if (timestamp == lastFuTimestamp) {
                        // Append fragment payload
                        naluStream.write(pkt, 14, len - 14)
                    }

                    if (isEnd && timestamp == lastFuTimestamp) {
                        val completeNalu = naluStream.toByteArray()
                        deliver(completeNalu, timestamp / 90) // Convert 90kHz timestamp to milliseconds
                        naluStream.reset()
                    }
                } else if (naluType in 1..23) {
                    // Single NAL Unit
                    naluStream.reset()
                    // Prepend start code 00 00 00 01
                    naluStream.write(byteArrayOf(0, 0, 0, 1))
                    naluStream.write(pkt, 12, len - 12)

                    deliver(naluStream.toByteArray(), timestamp / 90)
                    naluStream.reset()
                }
            }

            // The network reorders packets (seen as 1504,1506,1507,1505). Hold early packets
            // briefly and release them in sequence order; only a gap that outlives the hold
            // window counts as loss.
            val held = java.util.TreeMap<Int, ByteArray>() // seq distance from expected -> packet
            var expectedSeq = -1
            var oldestHeldAt = 0L
            val maxHeldPackets = 32
            val maxHoldMs = 60L

            fun drain() {
                while (true) {
                    val pkt = held.remove(0) ?: break
                    process(pkt, pkt.size)
                    expectedSeq = (expectedSeq + 1) and 0xFFFF
                    // Re-key the rest relative to the new expected seq.
                    val rest = held.entries.map { (it.key - 1) to it.value }
                    held.clear()
                    rest.forEach { held[it.first] = it.second }
                }
                if (held.isEmpty()) oldestHeldAt = 0L
            }

            fun skipGap() {
                val first = held.firstKey()
                lostPackets += first
                Log.w(TAG, "⚠️ Video RTP loss: $first packet(s) before seq ${(expectedSeq + first) and 0xFFFF} never arrived (total lost $lostPackets of $videoPacketsReceived) — freezing until keyframe")
                waitingForKeyframe = true
                lastFuTimestamp = -1L
                naluStream.reset()
                expectedSeq = (expectedSeq + first) and 0xFFFF
                val rest = held.entries.map { (it.key - first) to it.value }
                held.clear()
                rest.forEach { held[it.first] = it.second }
                oldestHeldAt = System.currentTimeMillis()
                drain()
            }

            var lastPliAt = 0L
            // While frozen, keep asking for a keyframe (a PLI can be lost too).
            fun requestKeyframeIfFrozen() {
                val now = System.currentTimeMillis()
                if (waitingForKeyframe && socket != null && now - lastPliAt > 300) {
                    lastPliAt = now
                    sendKeyframeRequest(socket)
                }
            }

            Log.i(TAG, "Video receiver thread started, listening on socket...")
            try { socket?.soTimeout = 20 } catch (_: Exception) {}
            while (isRunning && socket != null && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(packetBuf, packetBuf.size)
                    if (decoderNeedsKeyframe) {
                        // New decoder (surface recreated or codec error): it cannot decode P-frames yet.
                        decoderNeedsKeyframe = false
                        waitingForKeyframe = true
                        lastFuTimestamp = -1L
                        naluStream.reset()
                    }
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        // A decoded frame waits in the codec until it is pulled out; show it now
                        // instead of when the next packet arrives.
                        drainDecoder()
                        if (held.isNotEmpty() && System.currentTimeMillis() - oldestHeldAt > maxHoldMs) skipGap()
                        requestKeyframeIfFrozen()
                        continue
                    }
                    val len = packet.length
                    // RTCP PLI from the far end: it lost video and wants a keyframe.
                    if (len >= 12 && (packetBuf[1].toInt() and 0xFF) == 206 && (packetBuf[0].toInt() and 0x1F) == 1) {
                        forceKeyframe()
                        continue
                    }
                    if (len <= 12) continue

                    val pt = packetBuf[1].toInt() and 0x7F
                    if (pt != 97 && pt != 100) continue // Payload Type is H.264

                    videoPacketsReceived++
                    if (videoPacketsReceived == 1 || videoPacketsReceived % 50 == 0) {
                        Log.d(TAG, "📊 Video Packets Received: $videoPacketsReceived (len=$len, from ${packet.address}:${packet.port})")
                    }

                    val seq = ((packetBuf[2].toInt() and 0xFF) shl 8) or (packetBuf[3].toInt() and 0xFF)
                    if (expectedSeq < 0) expectedSeq = seq
                    val dist = (seq - expectedSeq) and 0xFFFF
                    when {
                        dist == 0 -> {
                            process(packetBuf, len)
                            expectedSeq = (expectedSeq + 1) and 0xFFFF
                            val rest = held.entries.map { (it.key - 1) to it.value }
                            held.clear()
                            rest.forEach { held[it.first] = it.second }
                            drain()
                        }
                        dist < 1000 -> {
                            if (held.isEmpty()) oldestHeldAt = System.currentTimeMillis()
                            held[dist] = packetBuf.copyOf(len)
                        }
                        dist > 0xFFFF - 1000 -> {
                            continue // late duplicate or arrived after we gave up on it
                        }
                        else -> {
                            // Sender restarted its sequence; resync.
                            Log.w(TAG, "Video RTP seq jumped from $expectedSeq to $seq, resyncing")
                            held.clear()
                            oldestHeldAt = 0L
                            waitingForKeyframe = true
                            lastFuTimestamp = -1L
                            naluStream.reset()
                            process(packetBuf, len)
                            expectedSeq = (seq + 1) and 0xFFFF
                        }
                    }

                    while (held.isNotEmpty() &&
                        (held.size > maxHeldPackets || System.currentTimeMillis() - oldestHeldAt > maxHoldMs)) {
                        skipGap()
                    }
                    requestKeyframeIfFrozen()
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
            Log.i(TAG, "Video receiver thread terminated.")
        }.apply { start() }
    }

    // Returns false when the NALU could not be queued, so the caller can resync on a keyframe.
    private fun feedDecoder(nalu: ByteArray, ptsMs: Long): Boolean = synchronized(decoderLock) {
        val surface = decoderSurface
        if (surface == null || !surface.isValid) return false  // Surface not valid or destroyed
        val decoder = videoDecoder ?: recreateDecoderLocked(surface) ?: return false
        var queued = false

        try {
            // Wait up to 10 ms: a NALU dropped here breaks every frame until the next keyframe.
            val inputBufferIndex = decoder.dequeueInputBuffer(10000)
            if (inputBufferIndex < 0) Log.w(TAG, "Decoder input busy, video NALU dropped")
            if (inputBufferIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                if (inputBuffer == null || inputBuffer.capacity() < nalu.size) {
                    // Hand the slot back empty, or the decoder runs out of input buffers for good.
                    decoder.queueInputBuffer(inputBufferIndex, 0, 0, ptsMs * 1000, 0)
                    Log.w(TAG, "Decoder input buffer too small (${inputBuffer?.capacity()} < ${nalu.size}), NALU dropped")
                } else {
                    inputBuffer.clear()
                    inputBuffer.put(nalu)
                    decoder.queueInputBuffer(inputBufferIndex, 0, nalu.size, ptsMs * 1000, 0)
                    queued = true
                }
            }
            drainDecoderLocked(decoder)
        } catch (e: IllegalStateException) {
            // CodecException is an IllegalStateException: the decoder is dead. Replace it on the
            // same surface (it used to stay dead, a black picture until the surface was recreated).
            Log.e(TAG, "feedDecoder codec error, recreating decoder: ${e.message}")
            if (videoDecoder === decoder) releaseDecoderLocked()
            recreateDecoderLocked(surface)
            queued = false
        } catch (e: Exception) {
            Log.e(TAG, "feedDecoder error: ${e.message}", e)
        }
        queued
    }

    /** Caller holds [decoderLock]. At most once a second, so a decoder that keeps failing cannot spin. */
    private fun recreateDecoderLocked(surface: Surface): MediaCodec? {
        if (!isRunning || !surface.isValid) return null
        val now = System.currentTimeMillis()
        if (now - lastDecoderRecreateAt < 1000) return null
        lastDecoderRecreateAt = now
        return if (reinitDecoder(surface)) videoDecoder else null
    }

    private fun drainDecoder(): Unit = synchronized(decoderLock) {
        val decoder = videoDecoder ?: return
        try { drainDecoderLocked(decoder) } catch (e: Exception) { Log.w(TAG, "Decoder drain failed: ${e.message}") }
    }

    /**
     * Caller holds [decoderLock]. Render each decoded frame now. releaseOutputBuffer(i, true) renders at the
     * buffer's PTS, and that PTS is the far phone's camera clock: when it reads up to a second ahead of this
     * phone's clock the display holds every frame back that long, and the decoder stalls on full buffers.
     */
    private fun drainDecoderLocked(decoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
        while (outputBufferIndex >= 0) {
            decoder.releaseOutputBuffer(outputBufferIndex, System.nanoTime())
            videoFramesDecoded++
            if (videoFramesDecoded == 1 || videoFramesDecoded % 300 == 0) {
                Log.i(TAG, "🎬 Video Frames Rendered to Surface: $videoFramesDecoded")
            }
            outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    // ─── RELEASE HELPERS ─────────────────────────────────────────────────────

    private fun releaseAudio() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioTrack = null
    }

    private fun releaseVideo() {
        val enc = videoEncoder
        videoEncoder = null
        try { enc?.stop() } catch (_: Exception) {}
        try { enc?.release() } catch (_: Exception) {}

        synchronized(decoderLock) { releaseDecoderLocked() }
    }
}
