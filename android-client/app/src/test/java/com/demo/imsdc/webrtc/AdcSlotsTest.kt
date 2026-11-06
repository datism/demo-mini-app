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

    @Test
    fun retryDelayFollowsRfc3261() {
        repeat(200) {
            assertTrue(reInviteRetryDelayMs(true) in 2100L..4000L)
            assertTrue(reInviteRetryDelayMs(false) in 0L..2000L)
        }
    }

    @Test
    fun pickSlotsUsesMidNotPosition() {
        val later = listOf(SdpAppLine("4", 9, "caro"), SdpAppLine("3", 9, "fun-calling-app"))
        val (home, app) = pickSlots(later) { it.mid }
        assertEquals("3", home!!.mid)
        assertEquals("4", app!!.mid)
    }

    @Test
    fun pickSlotsAppOnlyLeavesHomeEmpty() {
        val (home, app) = pickSlots(listOf(SdpAppLine("4", 0, null))) { it.mid }
        assertNull(home)
        assertEquals("4", app!!.mid)
    }

    @Test
    fun pickSlotsFallsBackToPositionWithoutKnownMids() {
        val (home, app) = pickSlots(listOf("x", "y")) { null }
        assertEquals("x", home)
        assertEquals("y", app)
    }

    // ── Slot negotiation (C2, C3, I2, I4) ──────────────────────────────────

    private val home = "fun-calling-app"

    @Test
    fun hotlineCallKeepsTheHotlineAppOnHomeForAnyTarget() {
        assertEquals(SlotState("hotline-app", null), desiredSlots("sketch-sync-app", home, "hotline-app"))
        assertEquals(SlotState("hotline-app", null), desiredSlots(home, home, "hotline-app"))
    }

    @Test
    fun normalCallPutsOtherAppsOnMid4() {
        assertEquals(SlotState(home, "sketch-sync-app"), desiredSlots("sketch-sync-app", home, null))
        assertEquals(SlotState(home, null), desiredSlots(home, home, null))
    }

    @Test
    fun nothingToNegotiateWhenLocalMatchesAndIsConfirmed() {
        val s = SlotState(home, "a")
        assertNull(planSlotChange(SlotState(home, "a"), s, s))
    }

    @Test
    fun sameTargetAfter491IsReOfferedWithoutLocalSteps() {
        // The App PC was opened for "a" but the far end answered 491: still only Home is confirmed.
        val plan = planSlotChange(SlotState(home, "a"), SlotState(home, "a"), SlotState(home, null))
        assertEquals(SlotPlan(openHome = null, closeApp = false, openApp = null), plan)
    }

    @Test
    fun unknownConfirmedStateForcesAReOffer() {
        val s = SlotState(home, null)
        assertEquals(SlotPlan(null, false, null), planSlotChange(s, s, null))
    }

    @Test
    fun hotlineCallOpensOnlyTheHomeSlot() {
        val plan = planSlotChange(desiredSlots("x", home, "hotline-app"), SlotState(null, null), SlotState(null, null))
        assertEquals(SlotPlan(openHome = "hotline-app", closeApp = false, openApp = null), plan)
    }

    @Test
    fun hotlineAlreadyOnHomeNeedsNothing() {
        val s = SlotState("hotline-app", null)
        assertNull(planSlotChange(desiredSlots("hotline-app", home, "hotline-app"), s, s))
    }

    @Test
    fun replacingTheAppClosesAndOpensMid4() {
        val s = SlotState(home, "a")
        assertEquals(SlotPlan(null, true, "b"), planSlotChange(SlotState(home, "b"), s, s))
    }

    @Test
    fun backToHomeClosesMid4Only() {
        val s = SlotState(home, "a")
        assertEquals(SlotPlan(null, true, null), planSlotChange(SlotState(home, null), s, s))
    }

    @Test
    fun anOpenHomeLineIsNeverReplaced() {
        val s = SlotState("other", null)
        assertEquals(SlotPlan(null, false, "a"), planSlotChange(SlotState(home, "a"), s, s))
    }

    @Test
    fun outcomeActions() {
        assertEquals(OutcomeAction.CONFIRM, outcomeAction(ReInviteOutcome.Answered("v=0"), 1, superseded = true))
        assertEquals(OutcomeAction.RETRY, outcomeAction(ReInviteOutcome.Failed(491), 1, superseded = false))
        assertEquals(OutcomeAction.SUPERSEDED, outcomeAction(ReInviteOutcome.Failed(491), 1, superseded = true))
        assertEquals(OutcomeAction.ROLLBACK, outcomeAction(ReInviteOutcome.Failed(491), MAX_GLARE_ATTEMPTS, superseded = false))
        assertEquals(OutcomeAction.ROLLBACK, outcomeAction(ReInviteOutcome.Failed(488), 1, superseded = false))
        // 430 (registration lost): one retry after re-registering, then give up.
        assertEquals(OutcomeAction.RETRY, outcomeAction(ReInviteOutcome.Failed(430), 1, superseded = false))
        assertEquals(OutcomeAction.ROLLBACK, outcomeAction(ReInviteOutcome.Failed(430), 2, superseded = false))
        assertEquals(OutcomeAction.ROLLBACK, outcomeAction(ReInviteOutcome.Failed(430), 1, superseded = true))
        assertEquals(OutcomeAction.ROLLBACK, outcomeAction(ReInviteOutcome.Timeout, 1, superseded = false))
    }

    @Test
    fun confirmedSlotsFollowTheOutcome() {
        val offered = SlotState(home, "a")
        val before = SlotState(home, null)
        assertEquals(offered, confirmedAfter(ReInviteOutcome.Answered(null), offered, before))
        assertEquals(before, confirmedAfter(ReInviteOutcome.Failed(491), offered, before))
        assertNull(confirmedAfter(ReInviteOutcome.Timeout, offered, before))
    }

    @Test
    fun pendingReInviteAcceptsOnlyTheAwaitedCSeqOnce() {
        val p = PendingReInvite()
        p.begin(7)
        assertFalse(p.finish(6))   // late answer to an earlier Re-INVITE
        assertTrue(p.finish(7))
        assertFalse(p.finish(7))   // duplicate final response
    }

    @Test
    fun answerAfterTheTimeoutIsStale() {
        val p = PendingReInvite()
        p.begin(7)
        p.clear()                  // request gave up after 5 s
        p.begin(8)                 // next request in flight
        assertFalse(p.finish(7))
        assertTrue(p.finish(8))
    }

    @Test
    fun peekShowsTheAwaitedCSeqForCancel() {
        val p = PendingReInvite()
        assertEquals(-1, p.peek())
        p.begin(9)
        assertEquals(9, p.peek())
        p.finish(9)
        assertEquals(-1, p.peek())
    }

    @Test
    fun nothingAwaitedRejectsEveryCSeq() {
        assertFalse(PendingReInvite().finish(1))
    }

    @Test
    fun queueReportsAWaitingRequest() {
        val q = LatestRequestQueue<String>()
        q.submit("a")
        assertFalse(q.hasPending)
        q.submit("b")
        assertTrue(q.hasPending)
        assertEquals("b", q.complete())
        assertFalse(q.hasPending)
    }

    // N1: the far end's Re-INVITE during the 491 back-off opened Y: our retry for X is dropped.
    @Test
    fun incomingOfferDuringBackOffSupersedesTheRetry() {
        val atGlare = SlotState("home", null)
        assertTrue(supersededDuringBackOff(atGlare, SlotState("home", "Y")))
        assertTrue(supersededDuringBackOff(SlotState("home", "X"), SlotState("home", null)))
        assertTrue(supersededDuringBackOff(null, SlotState("home", "Y")))
    }

    @Test
    fun noIncomingOfferDuringBackOffKeepsTheRetry() {
        assertFalse(supersededDuringBackOff(SlotState("home", "X"), SlotState("home", "X")))
        assertFalse(supersededDuringBackOff(null, null))
    }

    // N1: a retry that got 200 OK puts its target on screen unless it is already there.
    @Test
    fun confirmedRetryRelaunchesItsTargetOverAnotherPage() {
        assertEquals("X", pageToRelaunch("X", "Y", "home"))
        assertEquals("home", pageToRelaunch("home", "Y", "home"))
        assertNull(pageToRelaunch("X", "X", "home"))
    }

    @Test
    fun operatorMenuCountsAsTheHomePage() {
        assertNull(pageToRelaunch("home", "operator-menu", "home"))
        assertEquals("X", pageToRelaunch("X", "operator-menu", "home"))
    }

    // N2: switch X->Y rejected, App PC closed locally, far end still holds X: sync.
    @Test
    fun rejectedSwitchClosedLocallyNeedsSync() {
        assertTrue(needsSyncAfterRollback(isSync = false, local = SlotState("home", null), confirmed = SlotState("home", "X")))
    }

    // N2: close-to-Home rejected (or 3x 491): local has no App, the far end kept X: sync.
    @Test
    fun rejectedCloseToHomeNeedsSync() {
        val before = SlotState("home", "X")
        val confirmed = confirmedAfter(ReInviteOutcome.Failed(488), SlotState("home", null), before)
        assertTrue(needsSyncAfterRollback(false, SlotState("home", null), confirmed))
        val afterGlares = confirmedAfter(ReInviteOutcome.Failed(491), SlotState("home", null), before)
        assertEquals(OutcomeAction.ROLLBACK, outcomeAction(ReInviteOutcome.Failed(491), MAX_GLARE_ATTEMPTS, superseded = false))
        assertTrue(needsSyncAfterRollback(false, SlotState("home", null), afterGlares))
    }

    @Test
    fun unknownConfirmedAfterTimeoutNeedsSync() {
        assertTrue(needsSyncAfterRollback(false, SlotState("home", null), null))
    }

    @Test
    fun matchingSlotsOrASyncNeedNoFurtherSync() {
        // Rejected first App open: rolled back to what the far end holds.
        assertFalse(needsSyncAfterRollback(false, SlotState("home", null), SlotState("home", null)))
        // Rejected Home offer: local Home dropped, far end has none.
        assertFalse(needsSyncAfterRollback(false, SlotState(null, null), SlotState(null, null)))
        // A sync is never re-synced.
        assertFalse(needsSyncAfterRollback(true, SlotState("home", null), SlotState("home", "X")))
    }

    // N2: the sync for a rolled-back switch re-offers the local slots with no local step.
    @Test
    fun syncAfterRejectedSwitchReOffersWithoutSteps() {
        val plan = planSlotChange(SlotState("home", null), SlotState("home", null), SlotState("home", "X"))
        assertEquals(SlotPlan(openHome = null, closeApp = false, openApp = null), plan)
    }
}
