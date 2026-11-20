package com.demo.imsdc.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtcpFeedbackTest {

    private fun roundTrip(seqs: List<Int>): List<Int> {
        val nack = RtcpFeedback.buildNack(1, 2, seqs)
        return RtcpFeedback.parseNack(nack, nack.size)!!
    }

    @Test
    fun nack_header_isRtpfbGenericNack() {
        val nack = RtcpFeedback.buildNack(0x11223344, 0x55667788, listOf(100))
        assertEquals(0x81, nack[0].toInt() and 0xFF) // V=2, FMT=1
        assertEquals(205, nack[1].toInt() and 0xFF)
        assertEquals(16, nack.size)                  // header + 2 SSRCs + one FCI
        assertEquals(3, ((nack[2].toInt() and 0xFF) shl 8) or (nack[3].toInt() and 0xFF)) // length in words - 1
    }

    @Test
    fun nack_close_seqs_shareOneFci() {
        val seqs = listOf(100, 101, 105, 116)
        val nack = RtcpFeedback.buildNack(1, 2, seqs)
        assertEquals(16, nack.size)
        assertEquals(seqs, roundTrip(seqs))
    }

    @Test
    fun nack_far_seqs_takeSeveralFcis() {
        val seqs = listOf(100, 117, 300)
        assertEquals(24, RtcpFeedback.buildNack(1, 2, seqs).size)
        assertEquals(seqs, roundTrip(seqs))
    }

    @Test
    fun nack_acrossTheWrap_staysInOrder() {
        val seqs = listOf(65534, 65535, 0, 1)
        assertEquals(seqs, roundTrip(seqs))
        assertEquals(16, RtcpFeedback.buildNack(1, 2, seqs).size)
    }

    @Test
    fun parseNack_rejectsOtherPackets() {
        val pli = RtcpFeedback.buildPli(1, 2)
        assertNull(RtcpFeedback.parseNack(pli, pli.size))
        val rtp = RtpMediaManager.buildRtpHeader(97, 5, 0, 1) + ByteArray(20)
        assertNull(RtcpFeedback.parseNack(rtp, rtp.size))
    }

    @Test
    fun pli_isRecognised_andNackIsNot() {
        val pli = RtcpFeedback.buildPli(1, 2)
        assertTrue(RtcpFeedback.isPli(pli, pli.size))
        val nack = RtcpFeedback.buildNack(1, 2, listOf(3))
        assertFalse(RtcpFeedback.isPli(nack, nack.size))
    }

    @Test
    fun sentPackets_returnsTheSameBytes_onlyWhileKept() {
        val store = SentPackets(capacity = 4)
        val p5 = RtpMediaManager.buildRtpHeader(97, 5, 0, 1) + byteArrayOf(9)
        store.put(5, p5)
        assertArrayEquals(p5, store.get(5))
        // Seq 9 lands in the same slot: 5 is gone, and asking for 5 must not return 9's bytes.
        store.put(9, RtpMediaManager.buildRtpHeader(97, 9, 0, 1))
        assertNull(store.get(5))
        assertNull(store.get(6))
    }

    @Test
    fun hevcReference_types() {
        assertFalse(ContentReceiver.isHevcReference(0))  // TRAIL_N
        assertTrue(ContentReceiver.isHevcReference(1))   // TRAIL_R
        assertFalse(ContentReceiver.isHevcReference(8))  // RASL_N
        assertTrue(ContentReceiver.isHevcReference(19))  // IDR_W_RADL
        assertTrue(ContentReceiver.isHevcReference(21))  // CRA
    }
}
