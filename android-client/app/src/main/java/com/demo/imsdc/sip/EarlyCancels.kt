package com.demo.imsdc.sip

/**
 * Call-IDs remembered for a while (a CANCEL that came before its INVITE). The SIP server handles each
 * message on its own, and a CANCEL takes a shorter path than an INVITE, so it can overtake it.
 */
class ExpiringIds(
    private val ttlMs: Long = 32_000L,
    private val max: Int = 16,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private val ids = LinkedHashMap<String, Long>()

    @Synchronized fun add(id: String) {
        prune()
        ids.remove(id)
        ids[id] = now() + ttlMs
        while (ids.size > max) ids.remove(ids.keys.first())
    }

    /** True (once) if [id] was added and has not expired. */
    @Synchronized fun remove(id: String): Boolean {
        prune()
        return ids.remove(id) != null
    }

    @Synchronized fun contains(id: String): Boolean { prune(); return id in ids }

    private fun prune() {
        val t = now()
        ids.entries.removeAll { it.value <= t }
    }
}

/**
 * A CANCEL for a call this phone has not seen: no To-tag (not in a dialog) and not the call it knows. Its
 * INVITE may still be on the way; the CANCEL must not end whatever call is current.
 */
fun isEarlyCancel(cancelCallId: String?, pendingCallId: String?, hasToTag: Boolean): Boolean {
    if (hasToTag || cancelCallId.isNullOrEmpty()) return false
    if (pendingCallId == null) return true
    return cancelCallId.removePrefix("b2b_") != pendingCallId.removePrefix("b2b_")
}
