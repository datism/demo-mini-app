package com.demo.imsdc.webrtc

data class AdcParts(val bdc: String, val home: String?, val app: String?)

fun closedAppLine(): String =
    "m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:${AdcMid.APP}\r\n"

object AdcSdp {

    private class Media(val header: String, val audio: String?, val video: String?, val apps: List<String>)

    private fun splitMedia(sdp: String): Media {
        val header = StringBuilder()
        var audio: StringBuilder? = null
        var video: StringBuilder? = null
        val apps = mutableListOf<StringBuilder>()
        var cur: StringBuilder? = header
        for (line in sdp.replace("\r", "").split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("m=")) {
                val sb = StringBuilder()
                cur = when {
                    t.startsWith("m=audio ") -> sb.also { audio = it }
                    t.startsWith("m=video ") -> sb.also { video = it }
                    t.startsWith("m=application ") -> sb.also { apps.add(it) }
                    else -> null
                }
            }
            cur?.append(t)?.append("\r\n")
        }
        return Media(header.toString(), audio?.toString(), video?.toString(), apps.map { it.toString() })
    }

    private fun standalone(m: Media, block: String): String? {
        val sb = StringBuilder()
        m.header.replace("\r", "").split("\n").forEach {
            val t = it.trim()
            if (t.isNotEmpty() && !t.startsWith("a=group:BUNDLE")) sb.append(t).append("\r\n")
        }
        var hasUfrag = false
        block.replace("\r", "").split("\n").forEach {
            val t = it.trim()
            if (t.isNotEmpty() && !t.startsWith("a=bundle-only")) {
                if (t.startsWith("a=ice-ufrag:")) hasUfrag = true
                sb.append(t).append("\r\n")
            }
        }
        return if (hasUfrag) sb.toString() else null
    }

    private fun midOf(block: String): String? = block.replace("\r", "").split("\n").map { it.trim() }
        .firstOrNull { it.startsWith("a=mid:") }?.substringAfter("a=mid:")?.trim()

    /** BDC = first application section; Home and App are chosen by a=mid (see [pickSlots]). */
    fun split(sdp: String): AdcParts {
        val m = splitMedia(sdp)
        val bdc = StringBuilder(m.header)
        m.audio?.let { bdc.append(it) }
        m.video?.let { bdc.append(it) }
        m.apps.firstOrNull()?.let { bdc.append(it) }
        val (home, app) = pickSlots(m.apps.drop(1)) { midOf(it) }
        return AdcParts(bdc.toString(), home?.let { standalone(m, it) }, app?.let { standalone(m, it) })
    }

    private fun setMid(block: String, mid: String): String =
        block.replace("\r", "").split("\n").filter { it.trim().isNotEmpty() }
            .joinToString("\r\n") { if (it.trim().startsWith("a=mid:")) "a=mid:$mid" else it.trim() } + "\r\n"

    fun normalizeToLocalMid(block: String): String = setMid(block, "0")

    /**
     * An answer carries a=setup:active or a=setup:passive, never actpass (RFC 5763 §5). A STABLE PC whose
     * local description is its own earlier offer reuses it in an answer, so put in the DTLS role that PC
     * took when the far end answered: the opposite of the far end's a=setup (absent = active, RFC 4145 §4).
     */
    fun answerSetupFromOffer(localOffer: String, remoteAnswer: String?): String {
        val remote = Regex("a=setup:(\\w+)").find(remoteAnswer ?: "")?.groupValues?.get(1)
        val role = if (remote == "passive") "active" else "passive"
        return localOffer.replace("a=setup:actpass", "a=setup:$role")
    }

    /** First application block of an ADC part (its m-line section), without the session header. */
    private fun firstApp(part: String): String? = splitMedia(part).apps.firstOrNull()

    fun merge(
        bdc: String,
        home: String?,
        app: String?,
        isAnswer: Boolean,
        remoteMids: Pair<String?, String?> = null to null
    ): String {
        val b = splitMedia(bdc)
        val sb = StringBuilder(b.header)
        b.audio?.let { sb.append(it) }
        b.video?.let { sb.append(it) }
        b.apps.firstOrNull()?.let { sb.append(it) }
        home?.let { h -> firstApp(h)?.let { sb.append(setMid(it, if (isAnswer) remoteMids.first ?: AdcMid.HOME else AdcMid.HOME)) } }
        app?.let { a -> firstApp(a)?.let { sb.append(setMid(it, if (isAnswer) remoteMids.second ?: AdcMid.APP else AdcMid.APP)) } }
        return sb.toString()
    }
}
