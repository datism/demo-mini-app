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
        threads += Thread({ guarded("audio", ::audioLoop) }, "content-rx-audio").also { it.start() }
        Log.i(TAG, "Receiving shared content from MF $mfIp (video $mfVideoPort, audio $mfAudioPort)")
    }

    fun setMuted(value: Boolean) {
        muted = value
        try { track?.setVolume(if (value) 0f else 1f) } catch (_: Exception) {}
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
        DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(port)) }
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

    private fun videoLoop() {
        val socket = videoSocket ?: return
        val buf = ByteArray(2048)
        val frame = ByteArrayOutputStream()
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var lastSeq = -1
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

        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            socket.receive(pkt)
            val n = pkt.length
            if (n < 13 || (buf[1].toInt() and 0x7F) != 97) continue
            val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (lastSeq >= 0 && seq != ((lastSeq + 1) and 0xFFFF)) broken = true // lost packet: drop this picture
            lastSeq = seq
            val marker = (buf[1].toInt() and 0x80) != 0
            val type = buf[12].toInt() and 0x1F
            when {
                type in 1..23 -> addNal(buf, 12, n - 12)
                type == 24 -> { // STAP-A: several NAL units in one packet
                    var p = 13
                    while (p + 2 <= n) {
                        val size = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
                        if (size <= 0 || p + 2 + size > n) break
                        addNal(buf, p + 2, size)
                        p += 2 + size
                    }
                }
                type == 28 && n > 14 -> { // FU-A: rebuild the NAL header on the first fragment
                    val header = buf[13].toInt()
                    if ((header and 0x80) != 0) {
                        val nalHeader = ((buf[12].toInt() and 0xE0) or (header and 0x1F)).toByte()
                        val nalType = header and 0x1F
                        if (nalType == 5) hasIdr = true
                        frame.write(startCode); frame.write(nalHeader.toInt())
                    }
                    frame.write(buf, 14, n - 14)
                }
            }
            if (!marker) continue

            val picture = frame.toByteArray()
            frame.reset()
            val keyframe = hasIdr
            hasIdr = false
            if (broken) { broken = false; sawKey = false; continue } // lost packet: wait for the next keyframe
            if (keyframe) sawKey = true
            if (!sawKey) continue // decoding can only start at a keyframe
            val d = codec ?: startDecoder(sps, pps) ?: continue
            feed(d, picture)
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

    private fun feed(c: MediaCodec, picture: ByteArray) {
        try {
            val ii = c.dequeueInputBuffer(10_000)
            if (ii >= 0) {
                val ib = c.getInputBuffer(ii)!!
                ib.clear(); ib.put(picture)
                c.queueInputBuffer(ii, 0, picture.size, System.nanoTime() / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val oi = c.dequeueOutputBuffer(info, 0)
                if (oi >= 0) c.releaseOutputBuffer(oi, true) else break
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "Decode error: ${e.message}")
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
        t.setVolume(if (muted) 0f else 1f)
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
}
