package com.demo.imsdc.media

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

private const val TAG = "ScreenStreamer"

/**
 * Shares the phone's screen as shared content (a=content:slides): MediaProjection draws the screen into a
 * H.264 encoder's input surface, and each encoded picture goes out as RTP payload type 97 to the MF's
 * content-share video port, the same way a video file does. No audio: the call's mic carries the voice.
 *
 * [onEnded] runs (once, on a background thread) when the user revokes the capture from the system UI
 * or the encoder fails; [stop] does not call it.
 */
class ScreenStreamer(
    private val projection: MediaProjection,
    private val metrics: DisplayMetrics,
    private val localVideoPort: Int,
    private val mfIp: String,
    private val mfVideoPort: Int,
    private val onEnded: () -> Unit
) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var thread: Thread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (running) { Log.i(TAG, "Screen capture stopped by the system/user"); end() }
        }
    }

    fun start() {
        if (running) return
        running = true
        // Long side 1280, even sizes (encoders need them), the screen's aspect ratio.
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels))
        val w = (metrics.widthPixels * scale).toInt() and -2
        val h = (metrics.heightPixels * scale).toInt() and -2

        fun format(withProfile: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // A still screen produces no new frames; repeat the last one so the receiver keeps getting pictures.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (withProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            enc.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject the profile/level hints; retry without them.
            enc.reset()
            enc.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val input = enc.createInputSurface()
        enc.start()
        codec = enc

        socket = try {
            DatagramSocket(null).apply { reuseAddress = true; sendBufferSize = 1024 * 1024; bind(InetSocketAddress(localVideoPort)) }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot bind $localVideoPort (${e.message}), using any free port")
            DatagramSocket()
        }

        // Android 14 requires the callback to be registered before createVirtualDisplay.
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        display = projection.createVirtualDisplay(
            "content-share-screen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null
        )
        thread = Thread({ outputLoop(enc) }, "content-screen").also { it.start() }
        Log.i(TAG, "Sharing screen ${w}x$h to MF $mfIp:$mfVideoPort")
    }

    private fun outputLoop(enc: MediaCodec) {
        val sock = socket ?: return
        val rtp = H264RtpSender(sock, InetAddress.getByName(mfIp), mfVideoPort, TAG)
        val info = MediaCodec.BufferInfo()
        var config: List<ByteArray> = emptyList() // SPS + PPS, sent again before every keyframe
        var lastKeyNs = System.nanoTime()
        try {
            while (running) {
                // Ask for a keyframe now and then even if the encoder ignores the I-frame interval on still screens.
                if (System.nanoTime() - lastKeyNs > 2_000_000_000L) {
                    enc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    lastKeyNs = System.nanoTime()
                }
                val i = enc.dequeueOutputBuffer(info, 20_000)
                if (i < 0) continue
                val buf = enc.getOutputBuffer(i)
                val data = ByteArray(info.size)
                if (buf != null && info.size > 0) { buf.position(info.offset); buf.get(data, 0, info.size) }
                enc.releaseOutputBuffer(i, false)
                if (data.isEmpty()) continue
                val nals = H264RtpSender.splitAnnexB(data)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) { config = nals; continue }
                if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                    lastKeyNs = System.nanoTime()
                    config.forEach { rtp.sendNal(it, info.presentationTimeUs, false) }
                }
                nals.forEachIndexed { n, nal -> if (running) rtp.sendNal(nal, info.presentationTimeUs, n == nals.lastIndex) }
            }
        } catch (e: Exception) {
            if (running) { Log.e(TAG, "Screen encoder failed: ${e.message}", e); end() }
        }
    }

    /** Stopped from outside (not by us): release and report. */
    private fun end() {
        if (!running) return
        release()
        onEnded()
    }

    fun stop() { if (running) release() }

    private fun release() {
        running = false
        try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { projection.stop() } catch (_: Exception) {}
        if (thread !== Thread.currentThread()) try { thread?.join(500) } catch (_: Exception) {}
        thread = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    companion object {
        private const val MAX_SIDE = 1280
        private const val FPS = 20
        private const val BIT_RATE = 2_000_000
    }
}
