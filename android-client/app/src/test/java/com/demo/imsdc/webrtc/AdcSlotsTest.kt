package com.demo.imsdc.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdcSlotsTest {

    private fun sdp(appPort: String, appId: String?): String {
        val sb = StringBuilder("v=0\r\nm=audio 4000 RTP/AVP 8\r\na=mid:0\r\nm=video 4002 RTP/AVP 97\r\na=mid:1\r\n")
        sb.append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:2\r\na=dcmap:10 subprotocol=\"http\"\r\n")
        sb.append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:3\r\na=3gpp-req-app:\"fun-calling-app\";1000-UE\r\n")
        sb.append("m=application $appPort UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:4\r\n")
        if (appId != null) sb.append("a=3gpp-req-app:\"$appId\";1000-UE\r\n")
        return sb.toString()
    }

    @Test
    fun parsesThreeApplicationLinesByMid() {
        val lines = parseAppLines(sdp("9", "caro"))
        assertEquals(listOf("2", "3", "4"), lines.map { it.mid })
        assertEquals(listOf(null, "fun-calling-app", "caro"), lines.map { it.reqApp })
        assertTrue(lines[2].isOpen)
    }

    @Test
    fun closedAppLineHasPortZero() {
        val l = parseAppLines(sdp("0", null)).last()
        assertEquals("4", l.mid)
        assertFalse(l.isOpen)
        assertNull(l.reqApp)
    }

    @Test
    fun missingMidFallsBackToPosition() {
        val s = "v=0\r\nm=application 9 X\r\nm=application 9 X\r\n"
        assertEquals(listOf("2", "3"), parseAppLines(s).map { it.mid })
    }

    @Test
    fun diffCoversEveryCase() {
        val open = { id: String? -> SdpAppLine("4", 9, id) }
        val closed = SdpAppLine("4", 0, null)
        assertEquals(AppSlotChange.None, diffAppSlot(null, null))
        assertEquals(AppSlotChange.None, diffAppSlot(null, closed))
        assertEquals(AppSlotChange.Close, diffAppSlot("caro", closed))
        assertEquals(AppSlotChange.Open("caro"), diffAppSlot(null, open("caro")))
        assertEquals(AppSlotChange.None, diffAppSlot("caro", open("caro")))
        assertEquals(AppSlotChange.Replace("photo-share"), diffAppSlot("caro", open("photo-share")))
        assertEquals(AppSlotChange.None, diffAppSlot("caro", open(null)))
    }

    @Test
    fun queueKeepsOnlyLatestWhileBusy() {
        val q = LatestRequestQueue<String>()
        assertEquals("a", q.submit("a"))
        assertTrue(q.busy)
        assertNull(q.submit("b"))
        assertNull(q.submit("c"))
        assertEquals("c", q.complete())
        assertTrue(q.busy)
        assertNull(q.complete())
        assertFalse(q.busy)
        assertEquals("d", q.submit("d"))
    }
}
