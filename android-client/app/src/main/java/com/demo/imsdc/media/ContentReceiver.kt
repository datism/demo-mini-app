package com.demo.imsdc.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

private const val TAG = "ContentReceiver"


/**
 * Volume of a shared video's sound on both phones (the sender's preview and the receiver's stream), against
 * the call voice at full volume: at 1.0 the video drowned out the other person.
 */
const val SHARED_VIDEO_VOLUME = 0.3f

/**
 * Receives the shared content (a=content:slides) the other phone streams through the MF: H.264 RTP is
 * reassembled into access units and decoded onto [surface]; PCMA RTP is played through an AudioTrack.
 *
 * The MF only learns this phone's address from the packets it sends, so a 1-byte keepalive goes to both
 * MF ports every second from the same sockets that receive (the MF never forwards anything that short).
 */
class ContentReceiver(
    private val localAudioPort: Int,
    private val localVideoPort: Int,
    private val mfIp: String,
    private val mfAudioPort: Int,
    private val mfVideoPort: Int,
    private val surface: Surface
) {
    @Volatile private var running = false
    @Volatile private var muted = false
    private var videoSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private val threads = mutableListOf<Thread>()

    fun start() {
        if (running) return
        running = true
        videoSocket = bind(localVideoPort)
        audioSocket = bind(localAudioPort)
        threads += Thread({ guarded("keepalive", ::keepaliveLoop) }, "content-keepalive").also { it.start() }
        threads += Thread({ guarded("video", ::videoLoop) }, "content-rx-video").also { it.start() }
        threads += Thread({ guarded("decode", ::decodeLoop) }, "content-rx-decode").also { it.start() }
        threads += Thread({ guarded("audio", ::audioLoop) }, "content-rx-audio").also { it.start() }
        Log.i(TAG, "Receiving shared content from MF $mfIp (video $mfVideoPort, audio $mfAudioPort)")
    }

    fun setMuted(value: Boolean) {
        muted = value
        try { track?.setVolume(if (value) 0f else SHARED_VIDEO_VOLUME) } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        threads.forEach { it.interrupt() }
        threads.clear()
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    private fun bind(port: Int): DatagramSocket = try {
        // A large receive buffer: a keyframe of a phone video is hundreds of packets in one burst.
        DatagramSocket(null).apply { reuseAddress = true; receiveBufferSize = 4 * 1024 * 1024; bind(InetSocketAddress(port)) }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot bind $port (${e.message}), using any free port")
        DatagramSocket()
    }

    private fun guarded(name: String, body: () -> Unit) {
        try { body() } catch (e: Exception) { if (running) Log.e(TAG, "$name receive failed: ${e.message}", e) }
    }

    private fun keepaliveLoop() {
        val ip = InetAddress.getByName(mfIp)
        val ping = ByteArray(1)
        while (running) {
            try {
                videoSocket?.send(DatagramPacket(ping, 1, ip, mfVideoPort))
                audioSocket?.send(DatagramPacket(ping, 1, ip, mfAudioPort))
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Keepalive failed: ${e.message}")
            }
            Thread.sleep(1000)
        }
    }

    // ───────────────────────────── video ─────────────────────────────

    private val startCode = byteArrayOf(0, 0, 0, 1)

    /**
     * Whole pictures from [videoLoop] to [decodeLoop]. Receiving never waits on the decoder, so the socket
     * is always drained and a busy decoder no longer turns into packet loss (and a freeze until the next
     * keyframe). If the decoder falls this far behind, the backlog is dropped and decoding restarts at
     * the next keyframe, so the picture stays live instead of lagging further.
     */
    private val pictures = java.util.concurrent.LinkedBlockingQueue<ByteArray>(PICTURE_QUEUE)
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null

    private fun videoLoop() {
        val socket = videoSocket ?: return
        val buf = ByteArray(2048)
        val frame = ByteArrayOutputStream()
        // Packets can arrive out of order on the way through the MF (seen: 36835 before 36831..36834).
        // They are held here by sequence number and handled in order; a gap is only called a loss once
        // REORDER_WINDOW later packets have arrived without it.
        val pending = HashMap<Int, ByteArray>()
        var expected = -1
        var broken = false
        var sawKey = false
        var hasIdr = false

        fun addNal(nal: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val type = nal[off].toInt() and 0x1F
            if (type == 7) sps = nal.copyOfRange(off, off + len)
            if (type == 8) pps = nal.copyOfRange(off, off + len)
            if (type == 5) hasIdr = true
            frame.write(startCode); frame.write(nal, off, len)
        }

        fun handle(p: ByteArray) {
            val n = p.size
            val marker = (p[1].toInt() and 0x80) != 0
            val type = p[12].toInt() and 0x1F
            when {
                type in 1..23 -> addNal(p, 12, n - 12)
                type == 24 -> { // STAP-A: several NAL units in one packet
                    var i = 13
                    while (i + 2 <= n) {
                        val size = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                        if (size <= 0 || i + 2 + size > n) break
                        addNal(p, i + 2, size)
                        i += 2 + size
                    }
                }
                type == 28 && n > 14 -> { // FU-A: rebuild the NAL header on the first fragment
                    val header = p[13].toInt()
                    if ((header and 0x80) != 0) {
                        val nalHeader = ((p[12].toInt() and 0xE0) or (header and 0x1F)).toByte()
                        if ((header and 0x1F) == 5) hasIdr = true
                        frame.write(startCode); frame.write(nalHeader.toInt())
                    }
                    frame.write(p, 14, n - 14)
                }
            }
            if (!marker) return

            val picture = frame.toByteArray()
            frame.reset()
            val keyframe = hasIdr
            hasIdr = false
            if (broken) {
                // Drop only the damaged picture. A damaged keyframe leaves nothing to decode against, so
                // then wait for the next one; otherwise keep going (the decoder conceals the missing bit).
                broken = false
                if (keyframe) sawKey = false
                return
            }
            if (keyframe) sawKey = true
            if (!sawKey) return // decoding can only start at a keyframe
            if (!pictures.offer(picture)) {
                Log.w(TAG, "Decoder ${PICTURE_QUEUE} pictures behind: skipping to the next keyframe")
                pictures.clear()
                sawKey = keyframe
                if (keyframe) pictures.offer(picture)
            }
        }

        fun drain() {
            while (true) {
                val p = pending.remove(expected) ?: return
                handle(p)
                expected = (expected + 1) and 0xFFFF
            }
        }

        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            socket.receive(pkt)
            val n = pkt.length
            if (n < 13 || (buf[1].toInt() and 0x7F) != 97) continue
            val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (expected < 0) expected = seq
            if (((seq - expected) and 0xFFFF) >= 0x8000) continue // older than what was already given up on
            pending[seq] = buf.copyOf(n)
            drain()
            if (pending.size > REORDER_WINDOW) {
                // Still missing after a window of later packets: it is lost. Skip to the oldest one held.
                val next = pending.keys.minByOrNull { (it - expected) and 0xFFFF }!!
                Log.w(TAG, "Lost ${(next - expected) and 0xFFFF} video packet(s) before seq $next")
                broken = true
                expected = next
                drain()
            }
        }
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val picture = pictures.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)
            val c = codec ?: (if (picture != null) startDecoder(sps, pps) else null)
            if (c == null) continue
            try {
                if (picture != null) {
                    val ii = c.dequeueInputBuffer(20_000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        ib.clear(); ib.put(picture)
                        c.queueInputBuffer(ii, 0, picture.size, System.nanoTime() / 1000, 0)
                    }
                }
                // Show every decoded picture as soon as it is ready, not when the next one arrives.
                while (true) {
                    val oi = c.dequeueOutputBuffer(info, 0)
                    if (oi >= 0) c.releaseOutputBuffer(oi, true) else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Decode error: ${e.message}")
            }
        }
    }

    private fun startDecoder(sps: ByteArray?, pps: ByteArray?): MediaCodec? {
        if (sps == null || pps == null) return null
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(startCode + sps))
            format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(startCode + pps))
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            Log.i(TAG, "Video decoder started")
            c
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start video decoder: ${e.message}", e)
            null
        }
    }

    // ───────────────────────────── audio ─────────────────────────────

    private fun audioLoop() {
        val socket = audioSocket ?: return
        val minBuf = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 3200) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.setVolume(if (muted) 0f else SHARED_VIDEO_VOLUME)
        t.play()
        val buf = ByteArray(2048)
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            socket.receive(pkt)
            val n = pkt.length
            if (n < 13 || (buf[1].toInt() and 0x7F) != 8) continue
            val pcm = ShortArray(n - 12) { RtpMediaManager.alawToLinear(buf[12 + it]) }
            t.write(pcm, 0, pcm.size)
        }
    }

    companion object {
        /** About a second of video at 30 fps. */
        private const val PICTURE_QUEUE = 30
        /** Packets held while waiting for a late one (about 60 ms of a 10 Mbit/s video). */
        private const val REORDER_WINDOW = 64
    }
}
