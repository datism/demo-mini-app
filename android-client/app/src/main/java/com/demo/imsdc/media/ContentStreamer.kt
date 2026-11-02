package com.demo.imsdc.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private const val TAG = "ContentStreamer"

/**
 * Sends a video file from the phone as shared content (a=content:slides): its H.264 samples go out as
 * RTP payload type 97 exactly as stored (no re-encode), its audio track is decoded and sent as PCMA
 * (payload type 8, 8 kHz mono). Both go to the MF's content-share ports, which forward them to the other phone.
 *
 * Video and audio run on their own threads against one wall clock, so they stay in step; [pause] stops
 * both and moves the clock, [resume] continues from the same point.
 */
class ContentStreamer(
    private val context: Context,
    private val uri: Uri,
    private val localAudioPort: Int,
    private val localVideoPort: Int,
    private val mfIp: String,
    private val mfAudioPort: Int,
    private val mfVideoPort: Int,
    private val onEnded: () -> Unit
) {
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var originNs = 0L
    @Volatile private var pausedAtNs = 0L
    private var videoSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private val threads = mutableListOf<Thread>()
    private val finished = AtomicInteger(0)
    private var workers = 0

    /** Duration, name and a verdict on the file, read once before the share starts. */
    data class Info(val name: String, val durationMs: Long, val error: String?)

    fun inspect(): Info {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            var durationUs = 0L
            var avc = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) avc = true
            }
            Info(displayName(), durationUs / 1000, if (avc) null else "Video không được hỗ trợ (cần H.264)")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read video: ${e.message}", e)
            Info(displayName(), 0, "Không đọc được video")
        } finally {
            ex.release()
        }
    }

    private fun displayName(): String = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "video"
    } catch (e: Exception) { "video" }

    fun start() {
        if (running) return
        running = true
        paused = false
        originNs = System.nanoTime()
        videoSocket = bind(localVideoPort)
        audioSocket = bind(localAudioPort)
        finished.set(0)
        workers = 2
        threads += Thread({ guarded("video", ::videoLoop) }, "content-video").also { it.start() }
        threads += Thread({ guarded("audio", ::audioLoop) }, "content-audio").also { it.start() }
        Log.i(TAG, "Streaming $uri to MF $mfIp (video $mfVideoPort, audio $mfAudioPort)")
    }

    fun pause() {
        if (!running || paused) return
        pausedAtNs = System.nanoTime()
        paused = true
    }

    fun resume() {
        if (!running || !paused) return
        originNs += System.nanoTime() - pausedAtNs
        paused = false
    }

    /** Position in the file, in milliseconds. */
    fun positionMs(): Long = (((if (paused) pausedAtNs else System.nanoTime()) - originNs) / 1_000_000L).coerceAtLeast(0)

    fun stop() {
        running = false
        threads.forEach { it.interrupt() }
        threads.clear()
        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        videoSocket = null
        audioSocket = null
    }

    private fun bind(port: Int): DatagramSocket = try {
        DatagramSocket(null).apply { reuseAddress = true; sendBufferSize = 1024 * 1024; bind(InetSocketAddress(port)) }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot bind $port (${e.message}), using any free port")
        DatagramSocket()
    }

    private fun guarded(name: String, body: () -> Unit) {
        try { body() } catch (e: InterruptedException) { /* stopped */ } catch (e: Exception) {
            Log.e(TAG, "$name stream failed: ${e.message}", e)
        } finally {
            if (finished.incrementAndGet() == workers && running) { running = false; onEnded() }
        }
    }

    /** Blocks until the file's time [mediaUs] is due; holds while paused. */
    private fun waitUntil(mediaUs: Long) {
        while (running) {
            if (paused) { Thread.sleep(20); continue }
            val dueUs = mediaUs - (System.nanoTime() - originNs) / 1000
            if (dueUs <= 0) return
            Thread.sleep(minOf(dueUs / 1000, 20L).coerceAtLeast(1L))
        }
    }

    // ───────────────────────────── video ─────────────────────────────

    private fun videoLoop() {
        val socket = videoSocket ?: return
        val target = InetAddress.getByName(mfIp)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC
            } ?: return
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val sps = parameterSet(fmt, "csd-0")
            val pps = parameterSet(fmt, "csd-1")
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val rtp = H264RtpSender(socket, target, mfVideoPort, TAG)
            fun sendNal(nal: ByteArray, tsUs: Long, marker: Boolean) { if (running) rtp.sendNal(nal, tsUs, marker) }

            while (running) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val ptsUs = ex.sampleTime
                val sync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                val data = ByteArray(n)
                buf.position(0)
                buf.get(data, 0, n)
                waitUntil(ptsUs)
                if (!running) break
                val nals = nalUnits(data)
                if (sync) { sps?.let { sendNal(it, ptsUs, false) }; pps?.let { sendNal(it, ptsUs, false) } }
                nals.forEachIndexed { i, nal -> sendNal(nal, ptsUs, i == nals.lastIndex) }
                ex.advance()
            }
        } finally {
            ex.release()
        }
    }

    private fun parameterSet(fmt: MediaFormat, key: String): ByteArray? {
        val bb = fmt.getByteBuffer(key) ?: return null
        val all = ByteArray(bb.remaining()).also { bb.duplicate().get(it) }
        val skip = if (all.size > 4 && all[2].toInt() == 0 && all[3].toInt() == 1) 4 else if (all.size > 3 && all[2].toInt() == 1) 3 else 0
        return all.copyOfRange(skip, all.size)
    }

    // ───────────────────────────── audio ─────────────────────────────

    private fun audioLoop() {
        val socket = audioSocket ?: return
        val target = InetAddress.getByName(mfIp)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return // a video without sound is still shared
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            val out8k = ArrayList<Short>(4000)
            var acc = 0.0; var sum = 0.0; var count = 0
            var packet = 0L
            var seq = Random.nextInt(0xFFFF)
            val ssrc = Random.nextInt()
            var inDone = false
            var outDone = false

            while (running && !outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val pcm = ShortArray(info.size / 2)
                    ob.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm)
                    // mono, then box-filter down to 8 kHz
                    val ratio = sampleRate / 8000.0
                    var i = 0
                    while (i + channels <= pcm.size) {
                        var m = 0
                        for (c in 0 until channels) m += pcm[i + c]
                        sum += m / channels; count++; acc += 1.0
                        if (acc >= ratio) { out8k.add((sum / count).toInt().toShort()); acc -= ratio; sum = 0.0; count = 0 }
                        i += channels
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                    codec.releaseOutputBuffer(oi, false)
                    while (out8k.size >= 160 && running) {
                        val payload = ByteArray(160) { RtpMediaManager.linearToAlaw(out8k[it]) }
                        out8k.subList(0, 160).clear()
                        waitUntil(packet * 20_000L)
                        if (!running) break
                        send(socket, target, mfAudioPort, rtp(8, seq, packet * 160, ssrc, false), payload)
                        seq = (seq + 1) and 0xFFFF
                        packet++
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private fun rtp(pt: Int, seq: Int, ts: Long, ssrc: Int, marker: Boolean) =
        RtpMediaManager.buildRtpHeader(pt.toByte(), seq, ts, ssrc, marker)

    private fun send(socket: DatagramSocket, to: InetAddress, port: Int, header: ByteArray, payload: ByteArray) {
        val bytes = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, bytes, 0, header.size)
        System.arraycopy(payload, 0, bytes, header.size, payload.size)
        try { socket.send(DatagramPacket(bytes, bytes.size, to, port)) } catch (e: Exception) {
            if (running) Log.w(TAG, "RTP send failed: ${e.message}")
        }
    }

    companion object {
        /**
         * The NAL units of one stored sample: MP4 keeps them length-prefixed (4 bytes); a file that stores
         * start codes instead is split on them.
         */
        fun nalUnits(sample: ByteArray): List<ByteArray> {
            val lengthPrefixed = ArrayList<ByteArray>()
            var p = 0
            var ok = true
            while (p + 4 <= sample.size) {
                val len = ((sample[p].toInt() and 0xFF) shl 24) or ((sample[p + 1].toInt() and 0xFF) shl 16) or
                    ((sample[p + 2].toInt() and 0xFF) shl 8) or (sample[p + 3].toInt() and 0xFF)
                if (len <= 0 || p + 4 + len > sample.size) { ok = false; break }
                lengthPrefixed.add(sample.copyOfRange(p + 4, p + 4 + len))
                p += 4 + len
            }
            if (ok && p == sample.size && lengthPrefixed.isNotEmpty()) return lengthPrefixed

            return H264RtpSender.splitAnnexB(sample)
        }
    }
}
