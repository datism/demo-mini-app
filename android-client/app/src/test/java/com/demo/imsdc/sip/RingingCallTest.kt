package com.demo.imsdc.sip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingingCallTest {

    @Test
    fun theCallHeldHere_rings() {
        assertTrue(isRingingCall("b2b_abc", "b2b_abc", callActive = false))
        assertTrue(isRingingCall("b2b_abc", "abc", callActive = false))
    }

    @Test
    fun cancelledBeforeTheScreenOpened_doesNotRing() {
        // handleCancel cleared the call (resetCallState) before the call screen checked.
        assertFalse(isRingingCall(null, "b2b_abc", callActive = false))
    }

    @Test
    fun anotherCall_doesNotRing() {
        assertFalse(isRingingCall("b2b_xyz", "b2b_abc", callActive = false))
    }

    @Test
    fun answered_isNoLongerRinging() {
        assertFalse(isRingingCall("b2b_abc", "b2b_abc", callActive = true))
    }
}
