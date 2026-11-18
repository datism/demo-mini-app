package com.demo.imsdc.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PausePointTest {

    private fun header() = ByteArray(12).also { it[0] = 0x80.toByte(); it[1] = 97 }

    @Test
    fun follow_movesTheStreamTowardThePreviewInSmallSteps() {
        assertEquals(0L, ContentStreamer.followStepUs(5_000_000L, 5_030_000L)) // within the slack
        assertEquals(0L, ContentStreamer.followStepUs(5_000_000L, 4_970_000L))
        assertEquals(80_000L, ContentStreamer.followStepUs(5_000_000L, 5_080_000L)) // preview ahead
        assertEquals(-80_000L, ContentStreamer.followStepUs(5_000_000L, 4_920_000L)) // preview behind
        assertEquals(150_000L, ContentStreamer.followStepUs(5_000_000L, 6_000_000L)) // capped
        assertEquals(-150_000L, ContentStreamer.followStepUs(5_000_000L, 4_000_000L))
    }

    @Test
    fun pausePoint_isTheLaterOfShownAndSent() {
        assertEquals(5_100_000L, ContentStreamer.pauseStopUs(5_000_000L, 5_100_000L)) // preview behind the stream
        assertEquals(5_200_000L, ContentStreamer.pauseStopUs(5_200_000L, 5_100_000L)) // preview ahead
        assertEquals(5_100_000L, ContentStreamer.pauseStopUs(-1L, 5_100_000L))
        assertEquals(-1L, ContentStreamer.pauseStopUs(-1L, -1L))
    }

    @Test
    fun hiddenMark_survivesTheTrip() {
        val payload = byteArrayOf(0x41, 1, 2, 3)
        for (rotation in listOf(0, 90, 270)) {
            for (hidden in listOf(false, true)) {
                val h = H264RtpSender.withExtensions(header(), rotation, hidden)
                val got = ContentReceiver.stripExtension(h + payload, h.size + payload.size)
                assertEquals(hidden, got.hidden)
                assertEquals(rotation, got.rotation)
                assertEquals(12 + payload.size, got.data.size) // plain header, payload right after
                assertEquals(0x41.toByte(), got.data[12])
            }
        }
    }

    @Test
    fun plainPacket_isNotHidden() {
        val p = header() + byteArrayOf(0x41, 1)
        val got = ContentReceiver.stripExtension(p, p.size)
        assertFalse(got.hidden)
        assertEquals(null, got.rotation)
    }

    @Test
    fun keptPictures_followThePausePictureAtTheStreamsPace() {
        val now = 10_000_000L
        val delay = 100_000L
        val paused = 5_000_000L
        val a = ContentReceiver.keptRenderAtUs(now, delay, paused, paused + 33_366L)
        val b = ContentReceiver.keptRenderAtUs(now, delay, paused, paused + 66_733L)
        assertEquals(now + delay + 33_366L, a)
        assertTrue(b - a in 33_000L..34_000L)
        // Unknown times are shown right after the delay, never in the past.
        assertEquals(now + delay, ContentReceiver.keptRenderAtUs(now, delay, -1L, null))
        assertEquals(now + delay, ContentReceiver.keptRenderAtUs(now, delay, paused, paused - 1_000L))
    }
}
