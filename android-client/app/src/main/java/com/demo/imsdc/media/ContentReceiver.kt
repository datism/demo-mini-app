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
    private val surface: Surface,
    /** Size of the picture as it should be shown (after rotation), each time it changes. */
    private val onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> }
) {
    /** Clockwise rotation the sender's video needs, from the RTP header extension (0 when absent). */
    @Volatile private var rotation = 0
    @Volatile private var running = false
    @Volatile private var muted = false
    private var videoSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private val threads = mutableListOf<Thread>()
    /** Media time (the sender's file time) of the last picture handed to the screen, and when. */
    @Volatile private var lastRenderedMediaUs = -1L
    @Volatile private var lastRenderNs = 0L
    /** The sender resumed: show the pictures kept back since its pause (see decodeLoop). */
    @Volatile private var releaseKept = false

    /**
     * The sender paused after the picture at [stopUs] (media time, < 0 = not known). Once nothing new has been
     * shown for [STILL_MS], calls [onStill] (on a background thread) with the media time of the picture on screen;
     * after [STILL_GIVE_UP_MS] it answers with whatever is on screen.
     */
    fun awaitStill(stopUs: Long, onStill: (mediaUs: Long) -> Unit) {
        Thread({
            val deadline = System.nanoTime() + STILL_GIVE_UP_MS * 1_000_000L
            try {
                while (running && System.nanoTime() < deadline) {
                    Thread.sleep(50)
                    val idleMs = (System.nanoTime() - lastRenderNs) / 1_000_000L
                    if (lastRenderedMediaUs >= 0 && idleMs >= STILL_MS) {
                        Log.i(TAG, "Paused: still on ${lastRenderedMediaUs / 1000} ms (sender stopped at ${stopUs / 1000} ms)")
                        onStill(lastRenderedMediaUs); return@Thread
                    }
                }
                if (running && lastRenderedMediaUs >= 0) {
                    Log.w(TAG, "Paused: picture not still after $STILL_GIVE_UP_MS ms, answering ${lastRenderedMediaUs / 1000} ms")
                    onStill(lastRenderedMediaUs)
                }
            } catch (_: InterruptedException) {}
        }, "content-rx-still").start()
    }

    /** The sender resumed: the pictures kept back at its pause are shown now, on time. */
    fun resumeKept() { releaseKept = true }

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
    /** [arrivalUs]: when its last packet came in, so playout is timed from arrival, not from decoding. */
    /** [hidden]: sent after the sender's pause point, for the decoder only: kept back until it resumes. */
    private class Picture(val data: ByteArray, val rtpTs: Long, val key: Boolean, val hidden: Boolean = false,
                          val arrivalUs: Long = System.nanoTime() / 1000)
    private val pictures = java.util.concurrent.LinkedBlockingQueue<Picture>(PICTURE_QUEUE)
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var vps: ByteArray? = null // H.265 only
    /** The stream is H.265 (payload type 98) rather than H.264 (97). */
    @Volatile private var hevc = false
    /** For the 5-second log line: packets given up as lost, and times the decoder had no free input buffer. */
    @Volatile private var lostPackets = 0
    private var stalls = 0

    private fun videoLoop() {
        val socket = videoSocket ?: return
        val buf = ByteArray(2048)
        val frame = ByteArrayOutputStream()
        // Packets can arrive out of order on the way through the MF (seen: 36835 before 36831..36834).
        // They are held here by sequence number and handled in order; a gap is only called a loss once
        // REORDER_WINDOW later packets have arrived without it.
        val pending = HashMap<Int, RtpPacket>()
        var frameHidden = false
        var expected = -1
        var broken = false
        var sawKey = false
        var hasIdr = false

        fun addNal(nal: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (hevc) {
                val type = (nal[off].toInt() shr 1) and 0x3F
                when (type) {
                    32 -> vps = nal.copyOfRange(off, off + len)
                    33 -> sps = nal.copyOfRange(off, off + len)
                    34 -> pps = nal.copyOfRange(off, off + len)
                }
                if (isHevcKey(type)) hasIdr = true
            } else {
                val type = nal[off].toInt() and 0x1F
                if (type == 7) sps = nal.copyOfRange(off, off + len)
                if (type == 8) pps = nal.copyOfRange(off, off + len)
                if (type == 5) hasIdr = true
            }
            frame.write(startCode); frame.write(nal, off, len)
        }

        /** H.265 payloads (RFC 7798): single NAL, aggregation packet (48), fragmentation unit (49). */
        fun handleHevc(p: ByteArray, n: Int) {
            if (n < 14) return
            when ((p[12].toInt() shr 1) and 0x3F) {
                48 -> {
                    var i = 14
                    while (i + 2 <= n) {
                        val size = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                        if (size <= 0 || i + 2 + size > n) break
                        addNal(p, i + 2, size)
                        i += 2 + size
                    }
                }
                49 -> if (n > 15) {
                    val fu = p[14].toInt()
                    if ((fu and 0x80) != 0) {
                        val type = fu and 0x3F
                        if (isHevcKey(type)) hasIdr = true
                        frame.write(startCode)
                        frame.write((p[12].toInt() and 0x81) or (type shl 1))
                        frame.write(p[13].toInt())
                    }
                    frame.write(p, 15, n - 15)
                }
                else -> addNal(p, 12, n - 12)
            }
        }

        fun handle(rp: RtpPacket) {
            val p = rp.data
            val n = p.size
            if (rp.hidden) frameHidden = true
            val rtpTs = ((p[4].toLong() and 0xFF) shl 24) or ((p[5].toLong() and 0xFF) shl 16) or
                ((p[6].toLong() and 0xFF) shl 8) or (p[7].toLong() and 0xFF)
            val marker = (p[1].toInt() and 0x80) != 0
            val type = p[12].toInt() and 0x1F
            if (hevc) handleHevc(p, n) else when {
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
            val hidden = frameHidden
            frameHidden = false
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
            val pic = Picture(picture, rtpTs, keyframe, hidden)
            if (!pictures.offer(pic)) {
                Log.w(TAG, "Decoder ${PICTURE_QUEUE} pictures behind: skipping to the next keyframe")
                pictures.clear()
                sawKey = keyframe
                if (keyframe) pictures.offer(pic)
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
            val pt = buf[1].toInt() and 0x7F
            if (n < 13 || (pt != H264RtpSender.PT_H264 && pt != H264RtpSender.PT_H265)) continue
            if ((pt == H264RtpSender.PT_H265) != hevc) {
                // Codec changed (or the first packet): start over, parameter sets and all.
                hevc = pt == H264RtpSender.PT_H265
                sps = null; pps = null; vps = null
                frame.reset(); sawKey = false; hasIdr = false
                Log.i(TAG, "Shared video is ${if (hevc) "H.265" else "H.264"}")
            }
            val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (expected < 0) expected = seq
            if (((seq - expected) and 0xFFFF) >= 0x8000) continue // older than what was already given up on
            val packet = stripExtension(buf, n)
            if (packet.rotation != null) rotation = packet.rotation
            pending[seq] = packet
            drain()
            if (pending.size > REORDER_WINDOW) {
                // Still missing after a window of later packets: it is lost. Skip to the oldest one held.
                val next = pending.keys.minByOrNull { (it - expected) and 0xFFFF }!!
                Log.w(TAG, "Lost ${(next - expected) and 0xFFFF} video packet(s) before seq $next")
                broken = true
                lostPackets += (next - expected) and 0xFFFF
                expected = next
                drain()
            }
        }
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        val clock = PlayoutClock(PLAYOUT_DELAY_MS * 1000L)
        var lastTs = -1L
        var ptsBaseUs = 0L
        var codecRotation = 0
        var codecHevc = false
        // The stream reorders pictures (B-frames): its decoder must not run low-latency (see startDecoder).
        var reordered = false
        var codecLowLatency = false
        var shownW = 0; var shownH = 0
        // A picture the decoder had no input buffer for: it is retried, never dropped. Dropping one broke
        // every later picture that referenced it (smearing / freezes until the next keyframe).
        var held: Picture? = null
        var lastStatsNs = System.nanoTime()
        // Arrival time and media time of each picture in the decoder, by presentation time (output can come
        // out reordered).
        val arrivals = LinkedHashMap<Long, Long>()
        val mediaTimes = LinkedHashMap<Long, Long>()
        // Pictures sent after the sender's pause point (hidden): decoded, but kept back (output buffer not
        // released) until the sender resumes, then shown on time. They pushed the pause picture out of the
        // decoder, which gives out a picture only when later input arrives.
        val hiddenPts = HashSet<Long>()
        class Kept(val index: Int, val mediaUs: Long?)
        val kept = ArrayList<Kept>()

        fun showKept(c: MediaCodec) {
            releaseKept = false
            if (kept.isEmpty()) return
            val nowUs = System.nanoTime() / 1000
            val fromUs = lastRenderedMediaUs
            for (k in kept) {
                val atUs = keptRenderAtUs(nowUs, PLAYOUT_DELAY_MS * 1000L, fromUs, k.mediaUs)
                try { c.releaseOutputBuffer(k.index, atUs * 1000) } catch (_: Exception) {}
                if (k.mediaUs != null) lastRenderedMediaUs = k.mediaUs
                lastRenderNs = atUs * 1000
            }
            Log.i(TAG, "Resumed: showing ${kept.size} picture(s) kept back at the pause")
            kept.clear()
        }

        /** Shows one decoded picture, or keeps it back (see [kept]). */
        fun output(c: MediaCodec, oi: Int) {
            val nowUs = System.nanoTime() / 1000
            val arrivalUs = arrivals.remove(info.presentationTimeUs) ?: nowUs
            val mediaUs = mediaTimes.remove(info.presentationTimeUs)
            if (hiddenPts.remove(info.presentationTimeUs)) {
                kept += Kept(oi, mediaUs)
                if (kept.size > MAX_KEPT) { // never hold the decoder's output buffers for long
                    try { c.releaseOutputBuffer(kept.removeAt(0).index, false) } catch (_: Exception) {}
                }
                return
            }
            if (kept.isNotEmpty()) showKept(c) // resumed: the sender's next picture came before its message
            val renderAtUs = clock.renderAtUs(info.presentationTimeUs, arrivalUs, nowUs)
            c.releaseOutputBuffer(oi, renderAtUs * 1000)
            if (mediaUs != null) lastRenderedMediaUs = mediaUs
            // When it is actually on screen (its render time may still be ahead).
            lastRenderNs = maxOf(nowUs, renderAtUs) * 1000
        }

        while (running) {
            if (releaseKept) codec?.let { showKept(it) } ?: run { releaseKept = false }
            val picture = held ?: pictures.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)
            held = null
            // B-frames found while low-latency: switch over on a keyframe, so the picture never stops.
            val leaveLowLatency = codecLowLatency && reordered && picture?.key == true
            if (codec != null && (codecRotation != rotation || codecHevc != hevc || leaveLowLatency)) {
                // Rotation, codec and low-latency are set when the decoder starts: restart it, from the next keyframe.
                Log.i(TAG, "Video rotation $codecRotation° -> $rotation°, hevc $codecHevc -> $hevc, low-latency $codecLowLatency (B-frames $reordered): restarting the decoder")
                kept.clear()
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                codec = null
            }
            if (codec == null && picture != null && !picture.key) continue
            val c = codec ?: (if (picture != null) startDecoder(rotation, !reordered)?.also {
                codecRotation = rotation; codecHevc = hevc; codecLowLatency = !reordered
            } else null)
            if (c == null) continue
            try {
                if (picture != null) {
                    val ii = c.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        // RTP time (90 kHz, wraps at 2^32) to a steadily increasing presentation time.
                        if (lastTs >= 0) {
                            val stepUs = ((picture.rtpTs - lastTs) and 0xFFFFFFFFL).let {
                                if (it >= 0x80000000L) it - 0x100000000L else it
                            } * 100 / 9
                            if (stepUs < 0 && !reordered && stepUs > -5_000_000L) {
                                reordered = true
                                if (codecLowLatency) Log.i(TAG, "Stream has B-frames: decoder without low-latency from the next keyframe")
                            }
                            ptsBaseUs += stepUs
                        }
                        lastTs = picture.rtpTs
                        val ib = c.getInputBuffer(ii)!!
                        ib.clear(); ib.put(picture.data)
                        c.queueInputBuffer(ii, 0, picture.data.size, ptsBaseUs, 0)
                        arrivals[ptsBaseUs] = picture.arrivalUs
                        if (arrivals.size > 64) arrivals.remove(arrivals.keys.first())
                        mediaTimes[ptsBaseUs] = mediaUsOf(picture.rtpTs)
                        if (mediaTimes.size > 64) mediaTimes.remove(mediaTimes.keys.first())
                        if (picture.hidden) hiddenPts += ptsBaseUs else hiddenPts.remove(ptsBaseUs)
                        if (hiddenPts.size > 64) hiddenPts.clear()
                    } else {
                        held = picture // decoder busy: free its output below, then try again
                        stalls++
                    }
                }
                // Each decoded picture is shown at its own time, a fixed delay behind its arrival, so network
                // jitter is absorbed instead of showing up as uneven motion.
                while (true) {
                    val oi = c.dequeueOutputBuffer(info, 0)
                    if (oi >= 0) output(c, oi)
                    else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val (w, h) = shownSize(c.outputFormat, codecRotation)
                        if (w > 0 && h > 0 && (w != shownW || h != shownH)) {
                            shownW = w; shownH = h
                            Log.i(TAG, "Shared video ${w}x$h (rotation $codecRotation°)")
                            onVideoSize(w, h)
                        }
                    }
                    else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
                }
                if (System.nanoTime() - lastStatsNs > 5_000_000_000L) {
                    if (stalls > 0 || lostPackets > 0) Log.i(TAG, "video last 5 s: $lostPackets packet(s) lost, decoder busy $stalls time(s), ${pictures.size} picture(s) queued")
                    stalls = 0; lostPackets = 0
                    lastStatsNs = System.nanoTime()
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Decode error: ${e.message}")
            }
        }
    }

    /** The decoded picture's visible size, turned for a 90/270 rotation. */
    private fun shownSize(f: MediaFormat, rotation: Int): Pair<Int, Int> {
        var w = f.getInteger(MediaFormat.KEY_WIDTH)
        var h = f.getInteger(MediaFormat.KEY_HEIGHT)
        if (f.containsKey("crop-right") && f.containsKey("crop-left")) w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
        if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
        return if (rotation % 180 == 90) h to w else w to h
    }

    private fun startDecoder(rotation: Int, lowLatency: Boolean): MediaCodec? {
        val sps = sps; val pps = pps; val vps = vps; val hevc = hevc
        if (sps == null || pps == null || (hevc && vps == null)) return null
        return try {
            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, 1280, 720)
            if (hevc) {
                // H.265: VPS, SPS and PPS all go in csd-0.
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(startCode + vps!! + startCode + sps + startCode + pps))
            } else {
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(startCode + sps))
                format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(startCode + pps))
            }
            // Turns the picture as it is drawn on the surface (portrait phone video arrives as landscape frames).
            if (rotation != 0) format.setInteger(MediaFormat.KEY_ROTATION, rotation)
            // Output each picture as soon as it is decoded where the decoder supports it (otherwise it keeps the
            // last few back until more input arrives). Not with B-frames: in that mode decoders assume display
            // order = decode order and give reordered pictures out of order or broken.
            if (lowLatency && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            Log.i(TAG, "Video decoder started (${if (hevc) "hevc" else "avc"}, rotation $rotation°, low-latency $lowLatency)")
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
            .setBufferSizeInBytes(maxOf(minBuf, 6400))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.setVolume(if (muted) 0f else SHARED_VIDEO_VOLUME)
        t.play()
        // Delay the sound as much as the picture's playout buffer, so they stay in step.
        t.write(ShortArray((8 * PLAYOUT_DELAY_MS).toInt()), 0, (8 * PLAYOUT_DELAY_MS).toInt())
        var written = 8 * PLAYOUT_DELAY_MS // frames given to the track (8 kHz mono)
        val maxBuffered = 8 * (PLAYOUT_DELAY_MS + CATCH_UP_MS)
        val buf = ByteArray(2048)
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            socket.receive(pkt)
            val n = pkt.length
            if (n < 13 || (buf[1].toInt() and 0x7F) != 8) continue
            // Sound waiting in the track is delay: after a burst it would stay (a sender pause then kept
            // playing here for that long). Above the target, drop this packet so it shrinks back.
            val buffered = written - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
            if (buffered > maxBuffered) continue
            val pcm = ShortArray(n - 12) { RtpMediaManager.alawToLinear(buf[12 + it]) }
            t.write(pcm, 0, pcm.size)
            written += pcm.size
        }
    }

    companion object {
        /** About a second of video at 30 fps. */
        private const val PICTURE_QUEUE = 30
        /**
         * How far playback runs behind the stream, to absorb network jitter. Every picture waiting for its
         * time holds one of the decoder's output buffers: at 200 ms (6 pictures at 30 fps) a 1080p decoder ran
         * out of them and stopped taking input.
         */
        const val PLAYOUT_DELAY_MS = 100L
        /** Extra delay tolerated beyond [PLAYOUT_DELAY_MS] before playback catches up (picture and sound). */
        const val CATCH_UP_MS = 150L

        /** No new picture shown for this long: the picture has stopped (well past the playout delay). */
        const val STILL_MS = 250L
        private const val STILL_GIVE_UP_MS = 3_000L

        /** At most this many pictures kept back at a pause (each holds one of the decoder's output buffers). */
        private const val MAX_KEPT = 6

        /**
         * When a picture kept back at the pause is shown, the sender having resumed at [nowUs]: the playout delay
         * after now, plus how far it is past the pause picture [pausedUs], so they follow at the stream's pace.
         */
        fun keptRenderAtUs(nowUs: Long, delayUs: Long, pausedUs: Long, mediaUs: Long?): Long {
            val after = if (mediaUs == null || pausedUs < 0) 0L else (mediaUs - pausedUs).coerceIn(0L, 1_000_000L)
            return nowUs + delayUs + after
        }

        /** The sender's RTP timestamp is the file's media time at 90 kHz with no offset (H264RtpSender). */
        fun mediaUsOf(rtpTs: Long): Long = (rtpTs and 0xFFFFFFFFL) * 100 / 9

        /** H.265 random-access (IRAP) pictures: BLA, IDR, CRA. Decoding can start there. */
        fun isHevcKey(type: Int) = type in 16..21

        /**
         * Returns the packet with a plain 12-byte header (CSRCs and header extension removed, so the payload
         * starts at byte 12), the rotation found in its extension, if any, and its hidden mark.
         */
        fun stripExtension(buf: ByteArray, n: Int): RtpPacket {
            val cc = buf[0].toInt() and 0x0F
            val hasExt = (buf[0].toInt() and 0x10) != 0
            if (cc == 0 && !hasExt) return RtpPacket(buf.copyOf(n), null, false)
            var off = 12 + 4 * cc
            var rotation: Int? = null
            var hidden = false
            if (hasExt) {
                if (off + 4 > n) return RtpPacket(buf.copyOf(n), null, false)
                val profile = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
                val words = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
                val end = off + 4 + 4 * words
                if (end > n) return RtpPacket(buf.copyOf(n), null, false)
                if (profile == 0xBEDE) { // one-byte elements: id(4) len-1(4) data...
                    var i = off + 4
                    while (i < end) {
                        val b = buf[i].toInt() and 0xFF
                        if (b == 0) { i++; continue } // padding
                        val id = b shr 4
                        val len = (b and 0x0F) + 1
                        if (id == 15 || i + 1 + len > end) break
                        if (id == H264RtpSender.ROTATION_EXT_ID) rotation = (buf[i + 1].toInt() and 3) * 90
                        if (id == H264RtpSender.HIDDEN_EXT_ID) hidden = buf[i + 1].toInt() != 0
                        i += 1 + len
                    }
                }
                off = end
            }
            val out = ByteArray(12 + n - off)
            System.arraycopy(buf, 0, out, 0, 12)
            out[0] = (out[0].toInt() and 0xE0).toByte() // clear X and CC
            System.arraycopy(buf, off, out, 12, n - off)
            return RtpPacket(out, rotation, hidden)
        }
        /** Packets held while waiting for a late one (about 60 ms of a 10 Mbit/s video). */
        private const val REORDER_WINDOW = 64
    }
}

/**
 * Maps a picture's presentation time to when it should be on screen: [delayUs] behind its arrival, then at
 * the stream's own pace. The timeline starts again from a picture that is far late (a stall), or that would
 * be shown more than [catchUpUs] later than [delayUs] after it arrived (delay piled up, or a pause, seek or
 * new stream).
 *
 * The timeline used to start from when a picture was decoded and only ever moved later: each stall added
 * delay that never went away, up to about a second, and a sender's pause kept playing here for that long.
 * Timed from arrival, pictures that waited in the queue are shown at once and the backlog drains.
 */
class PlayoutClock(private val delayUs: Long, private val catchUpUs: Long = ContentReceiver.CATCH_UP_MS * 1000L) {
    private var baseWallUs = Long.MIN_VALUE
    private var basePtsUs = 0L

    fun renderAtUs(ptsUs: Long, arrivalUs: Long, nowUs: Long): Long {
        var at = if (baseWallUs == Long.MIN_VALUE) Long.MIN_VALUE else baseWallUs + (ptsUs - basePtsUs)
        if (at == Long.MIN_VALUE || at < nowUs - LATE_RESET_US || at > arrivalUs + delayUs + catchUpUs) {
            baseWallUs = arrivalUs + delayUs
            basePtsUs = ptsUs
            at = baseWallUs
        }
        return at
    }

    companion object {
        const val LATE_RESET_US = 100_000L
    }
}

/** A received RTP packet with its header extension read: [data] has a plain 12-byte header. */
class RtpPacket(val data: ByteArray, val rotation: Int?, val hidden: Boolean)
