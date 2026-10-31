package com.demo.imsdc.webrtc

object AdcMid {
    const val BDC = "2"
    const val HOME = "3"
    const val APP = "4"
}

data class SdpAppLine(val mid: String, val port: Int, val reqApp: String?) {
    val isOpen: Boolean get() = port != 0
}

private val REQ_APP = Regex("^a=3gpp-req-app:\\s*\"([^\"]+)\"")

fun parseAppLines(sdp: String): List<SdpAppLine> {
    val out = mutableListOf<SdpAppLine>()
    var port = 0
    var mid: String? = null
    var reqApp: String? = null
    var inApp = false
    fun flush() {
        if (inApp) out.add(SdpAppLine(mid ?: (out.size + 2).toString(), port, reqApp))
    }
    for (raw in sdp.replace("\r", "").split("\n")) {
        val t = raw.trim()
        if (t.startsWith("m=")) {
            flush()
            inApp = t.startsWith("m=application ")
            port = if (inApp) t.split(" ").getOrNull(1)?.toIntOrNull() ?: 0 else 0
            mid = null
            reqApp = null
        } else if (inApp) {
            if (t.startsWith("a=mid:")) mid = t.substringAfter("a=mid:").trim()
            REQ_APP.find(t)?.let { reqApp = it.groupValues[1] }
        }
    }
    flush()
    return out
}

/**
 * Home (mid 3) and App (mid 4) sections among the application sections that follow the BDC, chosen by
 * a=mid. Only when no section carries mid 3 or 4 (a peer with other mids) does position decide:
 * first = Home, second = App.
 */
fun <T> pickSlots(later: List<T>, midOf: (T) -> String?): Pair<T?, T?> {
    val byMid = later.any { midOf(it) == AdcMid.HOME || midOf(it) == AdcMid.APP }
    return if (byMid) later.firstOrNull { midOf(it) == AdcMid.HOME } to later.firstOrNull { midOf(it) == AdcMid.APP }
    else later.getOrNull(0) to later.getOrNull(1)
}

sealed class AppSlotChange {
    object None : AppSlotChange()
    data class Open(val appId: String) : AppSlotChange()
    object Close : AppSlotChange()
    data class Replace(val appId: String) : AppSlotChange()
}

fun diffAppSlot(current: String?, incoming: SdpAppLine?): AppSlotChange {
    if (incoming == null) return AppSlotChange.None
    if (!incoming.isOpen) return if (current == null) AppSlotChange.None else AppSlotChange.Close
    val id = incoming.reqApp ?: return AppSlotChange.None
    return when {
        current == null -> AppSlotChange.Open(id)
        current == id -> AppSlotChange.None
        else -> AppSlotChange.Replace(id)
    }
}

/** One request runs at a time; while it runs only the newest later request is kept. */
class LatestRequestQueue<T> {
    private var inFlight = false
    private var pending: T? = null
    val busy: Boolean get() = inFlight

    @Synchronized
    fun submit(item: T): T? {
        if (inFlight) {
            pending = item
            return null
        }
        inFlight = true
        return item
    }

    @Synchronized
    fun reset() {
        inFlight = false
        pending = null
    }

    @Synchronized
    fun complete(): T? {
        val next = pending
        pending = null
        inFlight = next != null
        return next
    }
}

/** RFC 3261 14.1: the Call-ID owner retries a glared Re-INVITE after 2.1-4 s, the other side after 0-2 s. */
fun reInviteRetryDelayMs(isCallIdOwner: Boolean, random: kotlin.random.Random = kotlin.random.Random): Long =
    if (isCallIdOwner) random.nextLong(2100, 4001) else random.nextLong(0, 2001)
