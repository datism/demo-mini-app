package com.demo.imsdc.media

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * Sends H.264 NAL units as RTP payload type 97 (RFC 6184): small ones whole, large ones as FU-A fragments.
 * One instance per stream: it owns the sequence number and SSRC. Used by the video-file and screen shares.
 */
class H264RtpSender(
    private val socket: DatagramSocket,
    private val to: InetAddress,
    private val port: Int,
    private val tag: String
) {
    private var seq = Random.nextInt(0xFFFF)
    private val ssrc = Random.nextInt()
    @Volatile var closed = false

    /** Sends one NAL (without start code); [marker] goes on the last packet of the picture. */
    fun sendNal(nal: ByteArray, tsUs: Long, marker: Boolean) {
        if (nal.isEmpty()) return
        val ts = tsUs * 9 / 100 // 90 kHz
        if (nal.size <= MAX_SINGLE) {
            send(header(ts, marker), nal)
            return
        }
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
            if (!last && (seq % 4) == 0) Thread.sleep(1) // spread a keyframe, as the camera path does
        }
    }

    private fun header(ts: Long, marker: Boolean): ByteArray =
        RtpMediaManager.buildRtpHeader(97.toByte(), seq, ts, ssrc, marker).also { seq = (seq + 1) and 0xFFFF }

    private fun send(header: ByteArray, payload: ByteArray) {
        val bytes = ByteArray(header.size + payload.size)
        System.arraycopy(header, 0, bytes, 0, header.size)
        System.arraycopy(payload, 0, bytes, header.size, payload.size)
        try { socket.send(DatagramPacket(bytes, bytes.size, to, port)) } catch (e: Exception) {
            if (!closed) Log.w(tag, "RTP send failed: ${e.message}")
        }
    }

    companion object {
        private const val MAX_SINGLE = 1200
        private const val MAX_FRAGMENT = 1180

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
