package com.demo.imsdc.sip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyCancelsTest {

    @Test
    fun cancelForAnUnknownCall_isEarly() {
        assertTrue(isEarlyCancel("b2b_x1", null, hasToTag = false))
        assertTrue(isEarlyCancel("b2b_x2", "b2b_x1", hasToTag = false))
    }

    @Test
    fun cancelForTheRingingCall_isNotEarly() {
        assertFalse(isEarlyCancel("b2b_x1", "b2b_x1", hasToTag = false))
        assertFalse(isEarlyCancel("b2b_x1", "x1", hasToTag = false)) // same call, with or without the b2b_ prefix
    }

    @Test
    fun inDialogCancel_isNotEarly() {
        assertFalse(isEarlyCancel("b2b_x2", "b2b_x1", hasToTag = true))
        assertFalse(isEarlyCancel(null, "b2b_x1", hasToTag = false))
    }

    @Test
    fun rememberedId_isTakenOnce_andExpires() {
        var t = 0L
        val ids = ExpiringIds(ttlMs = 1_000, max = 2, now = { t })
        ids.add("a")
        assertTrue(ids.remove("a"))
        assertFalse(ids.remove("a"))

        ids.add("b")
        t = 1_000
        assertFalse("expired", ids.remove("b"))

        ids.add("c"); ids.add("d"); ids.add("e")
        assertFalse("oldest dropped over the limit", ids.contains("c"))
        assertTrue(ids.contains("d") && ids.contains("e"))
    }
}
