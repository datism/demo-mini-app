package com.demo.imsdc.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayoutClockTest {

    private val delay = 100_000L
    private val frame = 33_333L

    @Test
    fun steadyStream_isShownTheDelayAfterArrival() {
        val clock = PlayoutClock(delay)
        for (i in 0 until 30) {
            val t = 1_000_000L + i * frame
            assertEquals(t + delay, clock.renderAtUs(i * frame, t, t))
        }
    }

    @Test
    fun stall_restartsFromTheLatePicture() {
        val clock = PlayoutClock(delay)
        clock.renderAtUs(0, 0, 0)
        // The next picture is decoded 500 ms late: shown the delay after it arrived, not 400 ms in the past.
        val arrival = 500_000L
        assertEquals(arrival + delay, clock.renderAtUs(frame, arrival, arrival))
    }

    @Test
    fun backlog_drainsInsteadOfStaying() {
        val clock = PlayoutClock(delay)
        // Timeline set by a picture that arrived at 0.
        clock.renderAtUs(0, 0, 0)
        // A decoder stall: the next second of pictures arrived on time but waited in the queue, and are
        // decoded only now (t = 1 s). Each must be shown at once, not a second behind the stream.
        val now = 1_000_000L
        for (i in 1..30) {
            val at = clock.renderAtUs(i * frame, i * frame, now)
            assertTrue("picture $i shown at $at, more than the delay after now", at <= now + delay)
        }
        // Then pictures arriving on time are shown the delay after arrival again.
        val t = now + frame
        val at = clock.renderAtUs(31 * frame, t, t)
        assertTrue("delay stayed: $at vs ${t + delay}", at <= t + delay + PlayoutClock.LATE_RESET_US)
    }

    @Test
    fun delayThatPiledUp_comesBackDown() {
        val clock = PlayoutClock(delay)
        clock.renderAtUs(0, 0, 0)
        // Picture 1 is held up 500 ms on the network: the timeline restarts 500 ms later.
        assertEquals(600_000L, clock.renderAtUs(frame, 500_000L, 500_000L))
        // The stream is back on time: picture 20 arrives at its own time. Against that late timeline it would
        // be shown ~570 ms after arriving; the timeline catches up to the delay instead.
        val arrival = 20 * frame
        assertEquals(arrival + delay, clock.renderAtUs(20 * frame, arrival, arrival))
    }

    @Test
    fun resumeAfterPause_restarts() {
        val clock = PlayoutClock(delay)
        clock.renderAtUs(0, 0, 0)
        // Paused 5 s: the stream continues from the next picture, which is far late against the old timeline.
        val t = 5_000_000L
        assertEquals(t + delay, clock.renderAtUs(frame, t, t))
    }
}
