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

    /** A newer request is waiting: the running one is superseded. */
    val hasPending: Boolean
        @Synchronized get() = pending != null

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

/** Apps on the two ADC slots: Home (mid 3) and App (mid 4); null = slot closed. */
data class SlotState(val home: String?, val app: String?)

/**
 * Slots a request for [target] should end in. A hotline call keeps its hotline app on the Home slot and
 * never uses mid 4 (go-mf keeps hotlines on the legacy single-ADC path); otherwise Home holds [homeApp]
 * and any other target goes on mid 4.
 */
fun desiredSlots(target: String, homeApp: String, hotlineApp: String?): SlotState = when {
    hotlineApp != null -> SlotState(hotlineApp, null)
    target == homeApp -> SlotState(homeApp, null)
    else -> SlotState(homeApp, target)
}

/** Local steps before the offer: [openHome] app id for a new Home PC, [closeApp] drop the App PC, [openApp] app id for a new App PC. */
data class SlotPlan(val openHome: String?, val closeApp: Boolean, val openApp: String?)

/**
 * What to do to reach [desired]. null: nothing to negotiate, because the local slots already match and the
 * far end confirmed them. [confirmed] null means unknown (a Re-INVITE went unanswered). A slot that is
 * local but not confirmed (its offer got 491 or no answer) needs no local step: the plan has no steps and
 * the same offer is sent again. An open Home line is never replaced.
 */
fun planSlotChange(desired: SlotState, local: SlotState, confirmed: SlotState?): SlotPlan? {
    val want = if (local.home != null) desired.copy(home = local.home) else desired
    if (want == local && local == confirmed) return null
    return SlotPlan(
        openHome = if (local.home == null) want.home else null,
        closeApp = local.app != null && local.app != want.app,
        openApp = want.app?.takeIf { it != local.app }
    )
}

sealed class ReInviteOutcome {
    data class Answered(val sdp: String?) : ReInviteOutcome()
    data class Failed(val code: Int) : ReInviteOutcome()
    object Timeout : ReInviteOutcome() {
        override fun toString() = "Timeout"
    }
}

enum class OutcomeAction { CONFIRM, RETRY, SUPERSEDED, ROLLBACK }

/** Attempts per request when the far end answers 491 (RFC 3261 14.1 glare). */
const val MAX_GLARE_ATTEMPTS = 3

/**
 * Next step after one Re-INVITE: 200 OK confirms; 491 is re-offered after the back-off unless a newer
 * request supersedes it or the attempts are used up; any other failure, and no answer in time, roll back.
 */
fun outcomeAction(outcome: ReInviteOutcome, attempt: Int, superseded: Boolean): OutcomeAction = when (outcome) {
    is ReInviteOutcome.Answered -> OutcomeAction.CONFIRM
    is ReInviteOutcome.Failed -> when {
        // 430: the server lost our registration; SipManager registers again, and the offer is retried once.
        outcome.code == 430 -> if (attempt == 1 && !superseded) OutcomeAction.RETRY else OutcomeAction.ROLLBACK
        outcome.code != 491 -> OutcomeAction.ROLLBACK
        superseded -> OutcomeAction.SUPERSEDED
        attempt < MAX_GLARE_ATTEMPTS -> OutcomeAction.RETRY
        else -> OutcomeAction.ROLLBACK
    }
    ReInviteOutcome.Timeout -> OutcomeAction.ROLLBACK
}

/**
 * Slots the far end holds after one Re-INVITE: what was offered on 200 OK, unchanged after a rejection
 * (it kept the previous session), unknown after no answer (a late 200 OK may still have applied it).
 */
fun confirmedAfter(outcome: ReInviteOutcome, offered: SlotState, before: SlotState?): SlotState? = when (outcome) {
    is ReInviteOutcome.Answered -> offered
    is ReInviteOutcome.Failed -> before
    ReInviteOutcome.Timeout -> null
}

/**
 * After a 491 back-off: true when the far end's Re-INVITE, handled meanwhile, changed the confirmed slots
 * ([atGlare] = confirmed when the 491 came). Its offer won the glare (RFC 3261 14.1), so the retry is dropped
 * and the page it launched stays consistent with the slots.
 */
fun supersededDuringBackOff(atGlare: SlotState?, now: SlotState?): Boolean = atGlare != now

/**
 * After a rolled-back request ([isSync] = it was itself a sync): re-offer the local slots when they differ from
 * what the far end holds, e.g. a rejected switch whose App PC was closed locally, or unknown after no answer.
 */
fun needsSyncAfterRollback(isSync: Boolean, local: SlotState, confirmed: SlotState?): Boolean =
    !isSync && local != confirmed

/**
 * Page to launch after a glare retry got 200 OK for [target], when [shownPage] (launched by an incoming
 * Re-INVITE during the back-off) shows something else; null = already shown. operator-menu is a view of Home.
 */
fun pageToRelaunch(target: String, shownPage: String, homeApp: String): String? {
    val shown = if (shownPage == "operator-menu") homeApp else shownPage
    return if (shown == target) null else target
}

/** CSeq of the one outgoing Re-INVITE whose final response is awaited; a response for any other CSeq is stale. */
class PendingReInvite {
    private var cseq = NONE

    @Synchronized
    fun begin(cseq: Int) {
        this.cseq = cseq
    }

    /** CSeq of the Re-INVITE being awaited, or -1 when none is. */
    @Synchronized
    fun peek(): Int = cseq

    /** True once, for the awaited CSeq; false for a stale or unknown one. */
    @Synchronized
    fun finish(cseq: Int): Boolean {
        if (cseq <= 0 || cseq != this.cseq) return false
        this.cseq = NONE
        return true
    }

    @Synchronized
    fun clear() {
        cseq = NONE
    }

    private companion object {
        const val NONE = -1
    }
}
