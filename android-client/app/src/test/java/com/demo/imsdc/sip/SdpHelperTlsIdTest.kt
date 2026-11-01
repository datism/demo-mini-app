package com.demo.imsdc.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpHelperTlsIdTest {

    private val fpA = "a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
    private val fpB = "a=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00"

    @Test
    fun sameFingerprintGivesSameTlsId() {
        assertEquals(SdpHelper.tlsIdFor("bdc", fpA), SdpHelper.tlsIdFor("bdc", fpA))
    }

    @Test
    fun newFingerprintGivesNewTlsId() {
        assertNotEquals(SdpHelper.tlsIdFor("adc", fpA), SdpHelper.tlsIdFor("adc", fpB))
    }

    @Test
    fun linesWithSameFingerprintStillDiffer() {
        assertNotEquals(SdpHelper.tlsIdFor("bdc", fpA), SdpHelper.tlsIdFor("adc", fpA))
    }

    @Test
    fun tlsIdMatchesRfc8842Syntax() {
        val id = SdpHelper.tlsIdFor("bdc", "")
        assertTrue(id, Regex("^[A-Za-z0-9+/_-]{20,255}$").matches(id))
    }

    @Test
    fun offerKeepsTlsIdAcrossCalls() {
        val webRtcOffer = listOf(
            "v=0",
            "o=- 1 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
            "c=IN IP4 0.0.0.0",
            "a=ice-ufrag:abcd",
            "a=ice-pwd:abcdefghijklmnopqrstuvwx",
            fpA,
            "a=setup:actpass",
            "a=mid:0",
            "a=candidate:1 1 udp 2122260223 192.168.1.10 50000 typ host",
        ).joinToString("\r\n") + "\r\n"

        fun tlsLine(sdp: String) = sdp.split("\r\n").single { it.startsWith("a=tls-id:") }

        val first = SdpHelper.translateWebRtcOfferToSipOffer(webRtcOffer, "192.168.1.10")
        Thread.sleep(5) // the old implementation used the clock
        val second = SdpHelper.translateWebRtcOfferToSipOffer(webRtcOffer, "192.168.1.10")

        assertEquals(tlsLine(first), tlsLine(second))
        assertEquals("a=tls-id:" + SdpHelper.tlsIdFor("bdc", fpA), tlsLine(first))
    }
}
