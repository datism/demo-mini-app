package com.demo.imsdc.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentStreamerTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun lengthPrefixedSample_isSplitIntoItsNalUnits() {
        val sample = b(0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 9)
        val nals = ContentStreamer.nalUnits(sample)
        assertEquals(2, nals.size)
        assertArrayEquals(b(0x65, 1, 2), nals[0])
        assertArrayEquals(b(0x41, 9), nals[1])
    }

    @Test
    fun startCodeSample_isSplitOnStartCodes() {
        val sample = b(0, 0, 0, 1, 0x67, 5, 6, 0, 0, 1, 0x68, 7)
        val nals = ContentStreamer.nalUnits(sample)
        assertEquals(2, nals.size)
        assertArrayEquals(b(0x67, 5, 6), nals[0])
        assertArrayEquals(b(0x68, 7), nals[1])
    }

    @Test
    fun brokenLengthFallsBackAndNeverThrows() {
        assertEquals(0, ContentStreamer.nalUnits(b(0, 0, 0, 99, 1, 2)).size)
        assertEquals(0, ContentStreamer.nalUnits(ByteArray(0)).size)
    }
}
