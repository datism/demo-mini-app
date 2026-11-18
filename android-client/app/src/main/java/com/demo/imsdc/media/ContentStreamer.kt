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
 * Sends a video file from the phone as shared content (a=content:slides): its H.264 / H.265 samples go out as
 * RTP payload type 97 / 98 exactly as stored (no re-encode), its audio track is decoded and sent as PCMA
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
    /**
     * Latest presentation time among the video samples sent so far (samples go in decode order, so the max).
     * On pause the sender's own preview is moved to this frame: the frame the other phone ends on.
     */
    @Volatile var lastSentVideoPtsUs = -1L
        private set
    /** Held while one video sample goes out: [pause] waits for it, so no sample is half sent or sent after. */
    private val sendLock = Object()
    /**
     * A pause by the user, at this media time ([pauseAt]; -1 = none pending): pictures up to it go out on time,
     * then a few hidden ones ([TAIL]), then the stream parks.
     */
    @Volatile private var stopAtUs = -1L
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
            var supported = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                if (mime in SUPPORTED_VIDEO) supported = true
            }
            Info(displayName(), durationUs / 1000, if (supported) null else "Video không được hỗ trợ (cần H.264 hoặc H.265)")
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
        lastSentVideoPtsUs = -1L
        stopAtUs = -1L
        originNs = System.nanoTime()
        videoSocket = bind(localVideoPort)
        audioSocket = bind(localAudioPort)
        finished.set(0)
        workers = 2
        threads += Thread({ guarded("video", ::videoLoop) }, "content-video").also { it.start() }
        threads += Thread({ guarded("audio", ::audioLoop) }, "content-audio").also { it.start() }
        Log.i(TAG, "Streaming $uri to MF $mfIp (video $mfVideoPort, audio $mfAudioPort)")
    }

    /** Stops before the next sample (a pause for hold). When it returns no video sample is going out. */
    fun pause() {
        if (!running) return
        synchronized(sendLock) {
            stopAtUs = -1L
            if (paused) return
            pausedAtNs = System.nanoTime()
            paused = true
        }
        Log.i(TAG, "Paused after sending video at ${lastSentVideoPtsUs / 1000} ms")
    }

    /**
     * A pause by the user, ending on the picture at media time [stopUs] (both phones stop there): pictures up to
     * it still go out on time; the next [TAIL] go at once, marked hidden (the other phone's decoder gives out
     * the pause picture only when later input arrives, and keeps these back until [resume]); then the stream
     * parks with its clock on [stopUs], so resuming continues from there with nothing sent twice.
     */
    fun pauseAt(stopUs: Long) {
        if (!running) return
        synchronized(sendLock) {
            if (paused) return
            stopAtUs = maxOf(stopUs, 0L)
        }
        Log.i(TAG, "Pausing at ${stopUs / 1000} ms (last sent ${lastSentVideoPtsUs / 1000} ms)")
    }

    /** The video thread reached the pause point and sent the hidden tail: hold here, the clock on [stopUs]. */
    private fun park(stopUs: Long) {
        synchronized(sendLock) {
            if (stopAtUs != stopUs || paused) return // resumed meanwhile
            stopAtUs = -1L
            val now = System.nanoTime()
            pausedAtNs = now
            originNs = now - stopUs * 1000
            paused = true
        }
        Log.i(TAG, "Paused at ${stopUs / 1000} ms: last picture shown ${lastSentVideoPtsUs / 1000} ms")
    }

    fun resume() {
        if (!running) return
        synchronized(sendLock) {
            stopAtUs = -1L // resumed before the pause point was reached
            if (!paused) return
            originNs += System.nanoTime() - pausedAtNs
            paused = false
        }
    }

    /** Position in the file, in milliseconds. */
    fun positionMs(): Long = if (originNs == 0L) 0L else (((if (paused) pausedAtNs else System.nanoTime()) - originNs) / 1_000_000L).coerceAtLeast(0)

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

    /** Blocks until the file's time [mediaUs] is due; holds while paused. Returns early once [stop] is true. */
    private fun waitUntil(mediaUs: Long, stop: () -> Boolean = { false }) {
        while (running) {
            if (stop()) return
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
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) in SUPPORTED_VIDEO
            } ?: return
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val hevc = fmt.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC
            // H.264: csd-0 = SPS, csd-1 = PPS. H.265: csd-0 = VPS + SPS + PPS together.
            val paramSets = listOf("csd-0", "csd-1").flatMap { parameterSets(fmt, it) }
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val rtp = H264RtpSender(socket, target, mfVideoPort, TAG, hevc)
            // Phones store portrait video as landscape frames plus a rotation tag; the frames go out as stored,
            // so the tag goes with them (RTP header extension) for the other phone to apply.
            val rotation = if (fmt.containsKey("rotation-degrees")) ((fmt.getInteger("rotation-degrees") % 360 + 360) % 360) / 90 * 90 else 0
            rtp.rotation = rotation
            Log.i(TAG, "video ${if (hevc) "H.265" else "H.264"}, rotation $rotation°, ${paramSets.size} parameter sets")
            fun sendNal(nal: ByteArray, tsUs: Long, marker: Boolean) { if (running) rtp.sendNal(nal, tsUs, marker) }
            var frames = 0
            var lastLogNs = 0L

            fun sendSample(data: ByteArray, ptsUs: Long, sync: Boolean, hidden: Boolean) {
                val nals = nalUnits(data)
                rtp.hidden = hidden
                if (sync) paramSets.forEach { sendNal(it, ptsUs, false) }
                nals.forEachIndexed { i, nal -> sendNal(nal, ptsUs, i == nals.lastIndex) }
                rtp.hidden = false
            }
            var tailSent = 0
            while (running) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val ptsUs = ex.sampleTime
                val sync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                val data = ByteArray(n)
                buf.position(0)
                buf.get(data, 0, n)
                // Sent only while not paused, as a whole, under [sendLock] (see [pause]).
                var sent = false
                while (running && !sent) {
                    val stop = stopAtUs
                    if (stop >= 0 && ptsUs > stop) {
                        // Past the pause point: a few pictures at once, hidden, then park until resumed.
                        if (tailSent < TAIL) {
                            synchronized(sendLock) { if (stopAtUs == stop) { sendSample(data, ptsUs, sync, true); sent = true } }
                            if (sent) tailSent++
                        } else {
                            park(stop)
                            tailSent = 0
                        }
                        continue
                    }
                    if (stop < 0) tailSent = 0
                    waitUntil(ptsUs) { stopAtUs.let { it >= 0 && ptsUs > it } }
                    if (!running) break
                    synchronized(sendLock) {
                        val st = stopAtUs
                        if (!paused && !(st >= 0 && ptsUs > st)) {
                            sendSample(data, ptsUs, sync, false)
                            if (running) lastSentVideoPtsUs = maxOf(lastSentVideoPtsUs, ptsUs)
                            sent = true
                        }
                    }
                }
                if (!running) break
                frames++
                val lateMs = ((System.nanoTime() - originNs) / 1000 - ptsUs) / 1000
                if (!paused && System.nanoTime() - lastLogNs > 1_000_000_000L) {
                    Log.d(TAG, "video: $frames frames sent, ${lateMs} ms behind the clock")
                    lastLogNs = System.nanoTime()
                }
                ex.advance()
            }
        } finally {
            ex.release()
        }
    }

    /** The NAL units stored in a codec-specific-data buffer (Annex-B, or a single bare NAL). */
    private fun parameterSets(fmt: MediaFormat, key: String): List<ByteArray> {
        val bb = fmt.getByteBuffer(key) ?: return emptyList()
        val all = ByteArray(bb.remaining()).also { bb.duplicate().get(it) }
        if (all.isEmpty()) return emptyList()
        return H264RtpSender.splitAnnexB(all).ifEmpty { listOf(all) }
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
         * Pictures sent hidden after a pause point: enough for the other phone's decoder to give out the pause
         * picture (it holds back a few: B-frame reordering, or the decoder's own pipeline).
         */
        const val TAIL = 4

        /**
         * The pause point: the later of the picture the sender shows ([shownUs]) and the last one sent ([sentUs]),
         * so neither phone goes back; the other one plays on to it (a few frames at most). -1 if neither is known.
         */
        fun pauseStopUs(shownUs: Long, sentUs: Long): Long = maxOf(shownUs, sentUs, -1L)
        private val SUPPORTED_VIDEO = setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)

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
