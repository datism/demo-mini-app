package com.demo.imsdc.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseDrainTest {

    @Test
    fun noPictureYet_isShown() {
        assertTrue(ContentReceiver.shouldShow(0L, -1L))
        assertTrue(ContentReceiver.shouldShow(null, 5_000_000L))
    }

    @Test
    fun laterPicture_isShown() {
        assertTrue(ContentReceiver.shouldShow(5_033_366L, 5_000_000L))
    }

    @Test
    fun resentPicturesUpToTheStillOne_areNotShown() {
        val still = 5_000_000L
        // After a pause the sender re-sends from the keyframe before it: none of those may appear again.
        for (media in listOf(3_000_000L, 4_966_633L, still)) assertFalse("$media", ContentReceiver.shouldShow(media, still))
        assertTrue(ContentReceiver.shouldShow(still + 33_366L, still))
    }

    @Test
    fun farBack_isANewStart() {
        assertTrue(ContentReceiver.shouldShow(0L, 60_000_000L))
    }
}
