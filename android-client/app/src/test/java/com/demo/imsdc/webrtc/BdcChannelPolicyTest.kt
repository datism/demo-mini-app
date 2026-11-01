package com.demo.imsdc.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BdcChannelPolicyTest {

    @Test
    fun createsWhenThereIsNoChannel() {
        assertTrue(WebRtcManager.needsNewBdcChannel(existingId = null, isClosed = false))
    }

    @Test
    fun keepsAnOpenStream10Channel() {
        assertFalse(WebRtcManager.needsNewBdcChannel(existingId = WebRtcManager.BDC_STREAM_ID, isClosed = false))
    }

    @Test
    fun replacesAClosedChannel() {
        assertTrue(WebRtcManager.needsNewBdcChannel(existingId = WebRtcManager.BDC_STREAM_ID, isClosed = true))
    }

    @Test
    fun replacesAChannelOnTheWrongStream() {
        assertTrue(WebRtcManager.needsNewBdcChannel(existingId = 0, isClosed = false))
        assertTrue(WebRtcManager.needsNewBdcChannel(existingId = 100, isClosed = false))
    }
}
