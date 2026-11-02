package com.demo.imsdc.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpHelperSlotsTest {

    private fun fp(n: Int) = "a=fingerprint:sha-256 " + "%02X".format(n).repeat(32).chunked(2).joinToString(":")

    private fun appBlock(mid: String, port: Int, ufrag: String, fp: String) = listOf(
        "m=application $port UDP/DTLS/SCTP webrtc-datachannel", "c=IN IP4 0.0.0.0",
        "a=ice-ufrag:$ufrag", "a=ice-pwd:pwpwpwpwpwpwpwpwpwpwpw", fp, "a=setup:actpass", "a=mid:$mid",
        "a=candidate:1 1 udp 2122260223 192.168.1.10 $port typ host",
    )

    private fun webRtcOffer(withApp: Boolean): String {
        val l = mutableListOf("v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0")
        l += appBlock("2", 50000, "bdcu", fp(1))
        l += appBlock("3", 50002, "homu", fp(2))
        if (withApp) l += appBlock("4", 50004, "appu", fp(3))
        return l.joinToString("\r\n") + "\r\n"
    }

    private fun sections(sip: String): List<String> =
        sip.split("\r\n").fold(mutableListOf<MutableList<String>>()) { acc, line ->
            if (line.startsWith("m=")) acc.add(mutableListOf(line)) else acc.lastOrNull()?.add(line); acc
        }.map { it.joinToString("\n") }.filter { it.startsWith("m=application") }

    private fun translate(withApp: Boolean, home: String?, app: String?, used: Boolean) =
        SdpHelper.translateWebRtcOfferToSipOffer(webRtcOffer(withApp), "192.168.1.10", home, false, 40002, 40004, app, used)

    @Test
    fun homeOnly_hasBdcAndHomeSections() {
        val s = sections(translate(false, "fun-calling-app", null, false))
        assertEquals(2, s.size)
        assertTrue(s[1].contains("a=mid:3") && s[1].contains("a=3gpp-req-app:\"fun-calling-app\";1000-UE"))
    }

    @Test
    fun appOpen_addsMid4WithOwnTlsId() {
        val s = sections(translate(true, "fun-calling-app", "caro", true))
        assertEquals(3, s.size)
        assertTrue(s[2].contains("a=mid:4") && s[2].contains("a=3gpp-req-app:\"caro\";1000-UE") && s[2].startsWith("m=application 50004"))
        val tls = { b: String -> b.split("\n").single { it.startsWith("a=tls-id:") } }
        assertTrue(tls(s[1]) != tls(s[2]))
    }

    @Test
    fun appClosed_staysAsPortZero_onceUsed() {
        val s = sections(translate(false, "fun-calling-app", null, true))
        assertEquals(3, s.size)
        assertTrue(s[2].startsWith("m=application 0 ") && s[2].contains("a=mid:4"))
        assertFalse(s[2].contains("3gpp-req-app"))
    }

    @Test
    fun neverUsed_noMid4() {
        assertEquals(2, sections(translate(false, "fun-calling-app", null, false)).size)
    }

    @Test
    fun homeSectionIsByteStable_whenAppChanges() {
        val a = sections(translate(true, "fun-calling-app", "caro", true))[1]
        val b = sections(translate(true, "fun-calling-app", "photo-share", true))[1]
        assertEquals(a, b)
    }

    @Test
    fun sipAnswerKeepsThreeApplicationSections() {
        val sip = listOf("v=0", "o=- 1 2 IN IP4 1.1.1.1", "s=-", "t=0 0",
            "m=application 40000 UDP/DTLS/SCTP webrtc-datachannel", "c=IN IP4 1.1.1.1", "a=mid:2", "a=ice-ufrag:b", "a=ice-pwd:p", "a=fingerprint:sha-256 AA", "a=setup:passive",
            "m=application 40001 UDP/DTLS/SCTP webrtc-datachannel", "c=IN IP4 1.1.1.1", "a=mid:3", "a=ice-ufrag:h", "a=ice-pwd:p", "a=fingerprint:sha-256 AA", "a=setup:passive",
            "m=application 40002 UDP/DTLS/SCTP webrtc-datachannel", "c=IN IP4 1.1.1.1", "a=mid:4", "a=ice-ufrag:a", "a=ice-pwd:p", "a=fingerprint:sha-256 AA", "a=setup:passive",
        ).joinToString("\r\n") + "\r\n"
        val out = SdpHelper.translateSipAnswerToWebRtcAnswer(sip)
        assertEquals(3, Regex("m=application").findAll(out).count())
        assertTrue(out.contains("a=mid:3") && out.contains("a=mid:4"))
    }

    @Test
    fun mid4WithoutHome_emitsClosedMid3First() {
        val s = sections(translate(true, null, "caro", true))
        assertEquals(3, s.size)
        assertTrue(s[1].startsWith("m=application 0 ") && s[1].contains("a=mid:3"))
        assertTrue(s[2].contains("a=mid:4"))
    }

    private fun sipAnswer(vararg secs: Pair<String, Int>): String {
        val l = mutableListOf("v=0", "o=- 1 2 IN IP4 1.1.1.1", "s=-", "t=0 0")
        secs.forEach { (mid, port) ->
            l += listOf("m=application $port UDP/DTLS/SCTP webrtc-datachannel", "c=IN IP4 1.1.1.1", "a=mid:$mid")
            if (port != 0) l += listOf("a=ice-ufrag:u$mid", "a=ice-pwd:p", "a=fingerprint:sha-256 AA", "a=setup:passive")
        }
        return l.joinToString("\r\n") + "\r\n"
    }

    @Test
    fun sipAnswer_closedSectionHasNoCandidateOrIceLite() {
        val out = sections(SdpHelper.translateSipAnswerToWebRtcAnswer(sipAnswer("2" to 40000, "3" to 40001, "4" to 0)))
        val app = out.single { it.contains("a=mid:4") }
        assertTrue(app.startsWith("m=application 0 "))
        assertFalse(app.contains("a=candidate:"))
        assertFalse(app.contains("a=ice-lite"))
        assertTrue(out.single { it.contains("a=mid:3") }.contains("a=candidate:"))
    }

    @Test
    fun sipAnswer_selectsSlotsByMid() {
        val out = sections(SdpHelper.translateSipAnswerToWebRtcAnswer(sipAnswer("2" to 40000, "4" to 40002, "3" to 40001)))
        assertEquals(3, out.size)
        assertTrue(out[1].contains("a=mid:3") && out[1].contains("a=ice-ufrag:u3"))
        assertTrue(out[2].contains("a=mid:4") && out[2].contains("a=ice-ufrag:u4"))
    }

    @Test
    fun shareStream_addsContentShareDcmapOnlyToAppSection() {
        val withShare = SdpHelper.translateWebRtcOfferToSipOffer(webRtcOffer(true), "192.168.1.10", "fun-calling-app", false, 40002, 40004, "photo-share", true, true)
        val s = sections(withShare)
        assertEquals(3, s.size)
        assertTrue(s[2].contains("a=dcmap:1002 subprotocol=\"content-share\""))
        assertFalse(s[1].contains("a=dcmap:1002"))
        assertTrue(SdpHelper.hasContentShare(withShare))
    }

    @Test
    fun noShareStream_hasNoContentShareDcmap() {
        val plain = SdpHelper.translateWebRtcOfferToSipOffer(webRtcOffer(true), "192.168.1.10", "fun-calling-app", false, 40002, 40004, "photo-share", true)
        assertFalse(SdpHelper.hasContentShare(plain))
        assertFalse(SdpHelper.hasContentShare(null))
    }
}
