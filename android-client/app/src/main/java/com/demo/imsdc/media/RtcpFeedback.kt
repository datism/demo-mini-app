package com.demo.imsdc.media

/**
 * RTCP feedback for the shared-content video (RFC 4585): generic NACK (PT 205, FMT 1) asks the sender to send
 * lost packets again; PLI (PT 206, FMT 1) asks it for a new keyframe. The MF content relay passes every packet
 * of 12 bytes or more from one phone to the other, so these reach the sender as they are.
 */
object RtcpFeedback {
    private const val PT_RTPFB = 205
    private const val PT_PSFB = 206
    private const val FMT_NACK = 1
    private const val FMT_PLI = 1

    /**
     * A NACK for [lost] sequence numbers, the oldest first (the rest may come in any order after it); each FCI
     * covers one PID and the 16 after it.
     */
    fun buildNack(senderSsrc: Int, mediaSsrc: Int, lost: Collection<Int>): ByteArray {
        val seqs = lost.map { it and 0xFFFF }.distinct()
        // Sequence order counted from the oldest, so a wrap (65535 -> 0) stays together.
        val first = seqs.firstOrNull() ?: return ByteArray(0)
        val sorted = seqs.sortedBy { (it - first) and 0xFFFF }
        val fci = ArrayList<Pair<Int, Int>>() // (pid, blp)
        for (s in sorted) {
            val last = fci.lastOrNull()
            val d = if (last == null) -1 else (s - last.first) and 0xFFFF
            if (last != null && d in 1..16) fci[fci.size - 1] = last.first to (last.second or (1 shl (d - 1)))
            else fci += s to 0
        }
        val words = 2 + fci.size // after the first word: sender SSRC, media SSRC, FCIs
        val out = ByteArray(4 * (words + 1))
        out[0] = (0x80 or FMT_NACK).toByte()
        out[1] = PT_RTPFB.toByte()
        out[2] = (words shr 8).toByte(); out[3] = words.toByte()
        putInt(out, 4, senderSsrc); putInt(out, 8, mediaSsrc)
        fci.forEachIndexed { i, (pid, blp) ->
            val o = 12 + 4 * i
            out[o] = (pid shr 8).toByte(); out[o + 1] = pid.toByte()
            out[o + 2] = (blp shr 8).toByte(); out[o + 3] = blp.toByte()
        }
        return out
    }

    /** The sequence numbers a NACK asks for, or null when [buf] is not a NACK. */
    fun parseNack(buf: ByteArray, n: Int): List<Int>? {
        if (n < 16 || (buf[0].toInt() and 0xC0) != 0x80 || (buf[0].toInt() and 0x1F) != FMT_NACK) return null
        if ((buf[1].toInt() and 0xFF) != PT_RTPFB) return null
        val words = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val end = minOf(n, 4 * (words + 1))
        val out = ArrayList<Int>()
        var o = 12
        while (o + 4 <= end) {
            val pid = ((buf[o].toInt() and 0xFF) shl 8) or (buf[o + 1].toInt() and 0xFF)
            val blp = ((buf[o + 2].toInt() and 0xFF) shl 8) or (buf[o + 3].toInt() and 0xFF)
            out += pid
            for (b in 0 until 16) if ((blp shr b) and 1 != 0) out += (pid + b + 1) and 0xFFFF
            o += 4
        }
        return out
    }

    fun buildPli(senderSsrc: Int, mediaSsrc: Int): ByteArray {
        val out = ByteArray(12)
        out[0] = (0x80 or FMT_PLI).toByte()
        out[1] = PT_PSFB.toByte()
        out[3] = 2
        putInt(out, 4, senderSsrc); putInt(out, 8, mediaSsrc)
        return out
    }

    fun isPli(buf: ByteArray, n: Int): Boolean =
        n >= 12 && (buf[0].toInt() and 0xC0) == 0x80 && (buf[0].toInt() and 0x1F) == FMT_PLI && (buf[1].toInt() and 0xFF) == PT_PSFB

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}

/** The last [capacity] packets sent, by sequence number, so a NACK can be answered with the very same bytes. */
class SentPackets(private val capacity: Int = 2048) {
    private val slots = arrayOfNulls<ByteArray>(capacity)

    @Synchronized fun put(seq: Int, packet: ByteArray) { slots[(seq and 0xFFFF) % capacity] = packet }

    /** The packet sent with [seq], if it is still kept (its own header says so, not just its slot). */
    @Synchronized fun get(seq: Int): ByteArray? {
        val p = slots[(seq and 0xFFFF) % capacity] ?: return null
        if (p.size < 12) return null
        val s = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        return if (s == (seq and 0xFFFF)) p else null
    }
}
