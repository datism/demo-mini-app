package com.demo.imsdc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdcSdpTest {
    private fun block(mid: String, port: Int, ufrag: String) =
        "m=application $port UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.2.3.4\r\na=mid:$mid\r\na=ice-ufrag:$ufrag\r\na=bundle-only\r\n"

    private val header = "v=0\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0\r\n"
    private val av = "m=audio 4000 RTP/AVP 8\r\na=mid:0\r\nm=video 4002 RTP/AVP 97\r\na=mid:1\r\n"

    @Test
    fun splitsThreeApplicationBlocksInOrder() {
        val sdp = header + av + block("2", 9, "b") + block("3", 9, "h") + block("4", 9, "a")
        val p = AdcSdp.split(sdp)
        assertTrue(p.bdc.contains("a=ice-ufrag:b") && p.bdc.contains("m=audio") && !p.bdc.contains("ufrag:h"))
        assertTrue(p.home!!.contains("a=ice-ufrag:h") && !p.home!!.contains("bundle-only") && !p.home!!.contains("a=group:BUNDLE"))
        assertTrue(p.app!!.contains("a=ice-ufrag:a"))
    }

    @Test
    fun blockWithoutIceUfragIsNull() {
        val sdp = header + av + block("2", 9, "b") + "m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:3\r\n"
        assertNull(AdcSdp.split(sdp).home)
    }

    @Test
    fun twoApplicationBlocksMeansNoApp() {
        val p = AdcSdp.split(header + av + block("2", 9, "b") + block("3", 9, "h"))
        assertTrue(p.home != null)
        assertNull(p.app)
    }

    @Test
    fun mergeOrdersBdcHomeApp_andForcesMids() {
        val bdc = header + av + block("0", 9, "b")
        val home = "v=0\r\n" + block("0", 9, "h")
        val app = "v=0\r\n" + block("0", 9, "a")
        val out = AdcSdp.merge(bdc, home, app, isAnswer = false)
        val mids = Regex("a=mid:(\\d)").findAll(out).map { it.groupValues[1] }.toList()
        assertEquals(listOf("0", "1", "0", "3", "4"), mids) // bdc block keeps its own mid, home=3, app=4
        assertTrue(out.indexOf("ufrag:h") < out.indexOf("ufrag:a"))
    }

    @Test
    fun mergeWithoutAppDoesNotAppendClosedLine_callerDecides() {
        val out = AdcSdp.merge(header + av + block("0", 9, "b"), "v=0\r\n" + block("0", 9, "h"), null, false)
        assertEquals(2, Regex("m=application").findAll(out).count())
    }

    @Test
    fun normalizeToLocalMid() {
        assertTrue(AdcSdp.normalizeToLocalMid(block("4", 9, "a")).contains("a=mid:0\r\n"))
    }

    @Test
    fun closedAppLineShape() {
        assertEquals("m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:4\r\n", closedAppLine())
    }

    @Test
    fun splitPicksHomeAndAppByMid_evenWhenReordered() {
        val p = AdcSdp.split(header + av + block("2", 9, "b") + block("4", 9, "a") + block("3", 9, "h"))
        assertTrue(p.home!!.contains("a=ice-ufrag:h"))
        assertTrue(p.app!!.contains("a=ice-ufrag:a"))
    }

    @Test
    fun splitAppOnlyIsNotTakenForHome() {
        val p = AdcSdp.split(header + av + block("2", 9, "b") + block("4", 9, "a"))
        assertNull(p.home)
        assertTrue(p.app!!.contains("a=ice-ufrag:a"))
    }

    @Test
    fun answerFromOwnOffer_takesRoleOppositeToRemote() {
        val offer = "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=setup:actpass\r\na=mid:0\r\n"
        assertTrue(AdcSdp.answerSetupFromOffer(offer, "a=setup:passive\r\n").contains("a=setup:active\r\n"))
        assertTrue(AdcSdp.answerSetupFromOffer(offer, "a=setup:active\r\n").contains("a=setup:passive\r\n"))
        assertTrue(AdcSdp.answerSetupFromOffer(offer, null).contains("a=setup:passive\r\n"))
        assertTrue(!AdcSdp.answerSetupFromOffer(offer, "a=setup:passive").contains("actpass"))
    }

    @Test
    fun answerFromOwnOffer_keepsAnEstablishedRole() {
        val desc = "a=setup:active\r\na=mid:0\r\n"
        assertEquals(desc, AdcSdp.answerSetupFromOffer(desc, "a=setup:passive"))
    }
}
