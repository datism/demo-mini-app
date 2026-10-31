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
    fun complete(): T? {
        val next = pending
        pending = null
        inFlight = next != null
        return next
    }
}
