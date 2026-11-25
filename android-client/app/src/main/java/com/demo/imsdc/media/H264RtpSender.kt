package com.demo.imsdc.media

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * Sends H.264 NAL units as RTP payload type 97 (RFC 6184), or H.265 as 98 (RFC 7798): small ones whole,
 * large ones as FU fragments.
 * One instance per stream: it owns the sequence number and SSRC. Used by the video-file and screen shares.
 */
class H264RtpSender(
    private val socket: DatagramSocket,
    private val to: InetAddress,
    private val port: Int,
    private val tag: String,
    /** H.265 (RFC 7798, payload type 98) instead of H.264 (RFC 6184, payload type 97). */
    private val hevc: Boolean = false
) {
    private val payloadType = if (hevc) PT_H265 else PT_H264
    private var seq = Random.nextInt(0xFFFF)
    private val ssrc = Random.nextInt()
    @Volatile var closed = false
    private var nextSendNs = 0L
    /** Clockwise rotation (0/90/180/270) the picture needs for display; sent in every packet when not 0. */
    @Volatile var rotation = 0
    /**
     * The pictures being sent now are only for the other phone's decoder, not to be shown yet (the few after a
     * pause point: its decoder gives out a picture only when later input arrives). Marked in every packet.
     */
    @Volatile var hidden = false

    /** Sends one NAL (without start code); [marker] goes on the last packet of the picture. */
    fun sendNal(nal: ByteArray, tsUs: Long, marker: Boolean) {
        if (nal.isEmpty()) return
        val ts = tsUs * 9 / 100 // 90 kHz
        if (nal.size <= MAX_SINGLE) {
            send(header(ts, marker), nal)
            return
        }
        if (hevc) { sendHevcFragments(nal, ts, marker); return }
        // FU-A: the NAL header is rebuilt in every fragment's two prefix bytes
        val indicator = ((nal[0].toInt() and 0xE0) or 28).toByte()
        var pos = 1
        while (pos < nal.size) {
            val len = minOf(MAX_FRAGMENT, nal.size - pos)
            val first = pos == 1
            val last = pos + len >= nal.size
            val fu = ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or (nal[0].toInt() and 0x1F)).toByte()
            val payload = ByteArray(2 + len)
            payload[0] = indicator; payload[1] = fu
            System.arraycopy(nal, pos, payload, 2, len)
            send(header(ts, marker && last), payload)
            pos += len
        }
    }

    /** H.265 FU (type 49): 2-byte payload header + FU header, then the NAL after its own 2-byte header. */
    private fun sendHevcFragments(nal: ByteArray, ts: Long, marker: Boolean) {
        val type = (nal[0].toInt() shr 1) and 0x3F
        val h0 = ((nal[0].toInt() and 0x81) or (49 shl 1)).toByte()
        val h1 = nal[1]
        var pos = 2
        while (pos < nal.size) {
            val len = minOf(MAX_FRAGMENT, nal.size - pos)
            val first = pos == 2
            val last = pos + len >= nal.size
            val payload = ByteArray(3 + len)
            payload[0] = h0; payload[1] = h1
            payload[2] = ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or type).toByte()
            System.arraycopy(nal, pos, payload, 3, len)
            send(header(ts, marker && last), payload)
            pos += len
        }
    }

    private fun header(ts: Long, marker: Boolean): ByteArray {
        val h = RtpMediaManager.buildRtpHeader(payloadType.toByte(), seq, ts, ssrc, marker).also { seq = (seq + 1) and 0xFFFF }
        return if (rotation == 0 && !hidden) h else withExtensions(h, rotation, hidden)
    }

    /**
     * Spaces packets [PACE_GAP_NS] apart. A phone keyframe is hundreds of packets; sent in one burst they
     * overflow the Wi-Fi / radio queue and get lost, freezing the picture on the other phone. Normal
     * pictures (tens of packets) still go out well within one frame time.
     */
    private fun pace() {
        val now = System.nanoTime()
        if (now < nextSendNs) java.util.concurrent.locks.LockSupport.parkNanos(nextSendNs - now)
        nextSendNs = maxOf(now, nextSendNs) + PACE_GAP_NS
    }

    private fun send(header: ByteArray, payload: ByteArray) {
        if (com.demo.imsdc.call.CallHold.active) return // shared content is suspended with the call
        val bytes = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, bytes, 0, header.size)
        System.arraycopy(payload, 0, bytes, header.size, payload.size)
        sent.put(((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF), bytes)
        transmit(bytes)
    }

    /** Paced send; also used for packets sent again, so the two never burst together. */
    private fun transmit(bytes: ByteArray) = synchronized(sendLock) {
        pace()
        try { socket.send(DatagramPacket(bytes, bytes.size, to, port)) } catch (e: Exception) {
            if (!closed) Log.w(tag, "RTP send failed: ${e.message}")
        }
    }

    private val sendLock = Any()
    /** Packets already sent, for [startFeedback]: a lost one is sent again as it was. */
    private val sent = SentPackets()
    @Volatile private var resent = 0
    @Volatile private var nacked = 0

    /**
     * Reads what the other phone sends back on this socket (through the MF content relay): a NACK is answered
     * by sending those packets again, a PLI calls [onKeyframeRequest] (a screen share makes a new keyframe;
     * a video file cannot). Stops when the socket is closed.
     */
    fun startFeedback(onKeyframeRequest: (() -> Unit)? = null) {
        Thread({
            val buf = ByteArray(1500)
            var lastLogNs = System.nanoTime()
            try { socket.soTimeout = 500 } catch (_: Exception) {}
            while (!closed && !socket.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try { socket.receive(pkt) } catch (_: java.net.SocketTimeoutException) { continue } catch (_: Exception) { break }
                val n = pkt.length
                val lost = RtcpFeedback.parseNack(buf, n)
                if (lost != null) {
                    nacked += lost.size
                    for (s in lost) {
                        if (com.demo.imsdc.call.CallHold.active) break
                        val p = sent.get(s) ?: continue
                        transmit(p)
                        resent++
                    }
                } else if (RtcpFeedback.isPli(buf, n)) {
                    Log.i(tag, "Keyframe asked for by the other phone")
                    onKeyframeRequest?.invoke()
                }
                if (System.nanoTime() - lastLogNs > 5_000_000_000L && nacked > 0) {
                    Log.i(tag, "Last 5 s: ${nacked} packet(s) asked for again, $resent sent again")
                    nacked = 0; resent = 0
                    lastLogNs = System.nanoTime()
                }
            }
        }, "$tag-feedback").apply { isDaemon = true; start() }
    }

    companion object {
        /** RFC 8285 one-byte header extension id carrying the video orientation (as IMS CVO does). */
        const val ROTATION_EXT_ID = 1
        const val PT_H264 = 97
        const val PT_H265 = 98

        /** RFC 8285 one-byte header extension id marking a picture not to be shown yet ([hidden]). */
        const val HIDDEN_EXT_ID = 2

        /**
         * [header] (12 bytes, no CSRC) with the X bit set and a one-word extension: the rotation, and the hidden
         * mark when [hidden].
         */
        fun withExtensions(header: ByteArray, rotation: Int, hidden: Boolean = false): ByteArray {
            val out = header.copyOf(12 + 8)
            out[0] = (out[0].toInt() or 0x10).toByte()
            out[12] = 0xBE.toByte(); out[13] = 0xDE.toByte() // one-byte header profile
            out[14] = 0; out[15] = 1                         // one 32-bit word follows
            out[16] = (ROTATION_EXT_ID shl 4).toByte()        // id 1, 1 byte of data
            out[17] = ((rotation / 90) and 3).toByte()
            if (hidden) { out[18] = (HIDDEN_EXT_ID shl 4).toByte(); out[19] = 1 } // id 2, 1 byte; else padding
            return out
        }

        private const val MAX_SINGLE = 1200
        private const val MAX_FRAGMENT = 1180
        /** 0.25 ms between packets: at most ~4.8 MB/s (~38 Mbps), a 300-packet keyframe over ~75 ms. */
        private const val PACE_GAP_NS = 250_000L

        /** Splits an Annex-B buffer (00 00 01 / 00 00 00 01 start codes) into its NAL units. */
        fun splitAnnexB(data: ByteArray): List<ByteArray> {
            val starts = ArrayList<Pair<Int, Int>>() // (index of start code, its length)
            var i = 0
            while (i + 3 <= data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                    if (data[i + 2].toInt() == 1) { starts.add(i to 3); i += 3; continue }
                    if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) { starts.add(i to 4); i += 4; continue }
                }
                i++
            }
            return starts.mapIndexed { idx, (at, len) ->
                val end = if (idx + 1 < starts.size) starts[idx + 1].first else data.size
                data.copyOfRange(at + len, end)
            }.filter { it.isNotEmpty() }
        }
    }
}
