# Client Two ADC PeerConnections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Android client keeps the emote ADC (the *Home* ADC, mid 3) open for the whole call, opens every other mini-app on a separate *App* ADC (mid 4), and spends exactly one Re-INVITE per app change.

**Architecture:** `WebRtcManager` owns three PeerConnections — `bdcPC` (mid 2, never touched), `homeAdcPC` (mid 3, created once, closed at hang-up) and `appAdcPC` (mid 4, replaced per app). `SdpHelper` and a new pure `AdcSdp` object address application m-lines by `a=mid`, never by position. `MiniAppManager` sends one Re-INVITE per change through a latest-wins queue; `CallActivity` diffs incoming Re-INVITEs by mid and rebuilds only `appAdcPC`.

**Tech Stack:** Kotlin, Android, org.webrtc `PeerConnection`/`DataChannel`, JUnit4 JVM tests (`android-client/app/src/test/java/com/demo/imsdc/...`).

**Spec:** `docs/superpowers/specs/2026-10-31-background-emote-adc-design.md` (§2, §3, §5). Network counterpart: `docs/superpowers/plans/2026-10-31-network-two-adc-slots.md` (already merged when this plan runs; the network keeps the Home line byte-stable and puts the App line in `<callID>-app`).

## Global Constraints

- m-line layout, fixed for every offer and answer: mid 0 `m=audio`, mid 1 `m=video`, mid 2 BDC (`a=dcmap:10 subprotocol="http"`), mid 3 Home ADC (`a=3gpp-req-app:"fun-calling-app";1000-UE`), mid 4 App ADC (`a=3gpp-req-app:"<appId>";1000-UE`, `m=application 0 …` while no app is open).
- m-lines are never removed (RFC 3264 §8). Once mid 4 has appeared it stays in every later offer.
- An unchanged m-line keeps port, ICE ufrag/pwd, fingerprint, `a=setup` and `a=tls-id`. `a=tls-id` is **one per DTLS association** (`SdpHelper.tlsIdFor(label, fingerprintLine)`, from Plan 1) — labels become `"bdc"`, `"home"`, `"app"`.
- The BDC PeerConnection and its channel are never recreated during a call (Plan 1). Nothing in this plan may call `createBDC` or close `bdcPC`.
- One Re-INVITE per app change. The release-to-`operator-menu` pre-step, `awaitAppDownload`, `APP_DOWNLOAD_WAIT_MS` and the chained `REINVITE_ANSWER_WAIT_MS` wait are removed (Task 7).
- Home ADC messages reach the page only while the emote page is in front; otherwise they are logged and dropped. Page → ADC goes to the ADC owned by the foreground app (Home for `fun-calling-app`, App for everything else).
- Hotline calls are unchanged: the hotline app is the Home line, mid 4 is never used.
- A single-ADC peer (old client / network) must keep working: a Re-INVITE with only two application m-lines is the Home-only case.
- **No JDK or Android SDK in the authoring environment**: `./gradlew testDebugUnitTest` cannot run there. Each task must be written so that it compiles by inspection (match imports, signatures and nullability exactly) and the user runs `cd android-client && ./gradlew testDebugUnitTest assembleDebug` before merging. The ledger must record that the Gradle run is outstanding.

## File Structure

| File | Role |
|---|---|
| `webrtc/AdcSlots.kt` (new) | Pure: `AdcSlot` mids, `SdpAppLine`, `parseAppLines`, `AppSlotChange`/`diffAppSlot`, `LatestRequestQueue` |
| `webrtc/AdcSdp.kt` (new) | Pure: split a merged SDP into BDC / Home / App parts by mid; merge three parts |
| `sip/SdpHelper.kt` | `translateWebRtcOfferToSipOffer` emits mid 2/3/4; `translateSipAnswerToWebRtcAnswer` keeps up to three application sections |
| `sip/SipManager.kt` | `activeReqAppId` → `homeReqApp`, `appReqApp`, `appSlotUsed`; four translate call sites |
| `webrtc/WebRtcManager.kt` | `homeAdcPC`, `appAdcPC`, `closeAppADC()`, per-mid offer/answer/remote-description handling |
| `webrtc/DataChannelManager.kt`, `ImsDcSdk.kt`, `PhotoShareTransport.kt` | use the foreground ADC channel; Home messages dropped when an app is in front |
| `ui/MiniAppManager.kt` | one Re-INVITE per change, latest-wins queue, Back/Exit closes mid 4 |
| `ui/CallActivity.kt` | `onReInviteReceived` diff by mid; `onJsRequestedAdc` binds to the open Home ADC |
| tests under `app/src/test/java/com/demo/imsdc/{webrtc,sip}/` | `AdcSlotsTest`, `AdcSdpTest`, `SdpHelperSlotsTest` |

## Task list

1. Pure slot model: `AdcSlots.kt` (parse, diff, latest-wins queue)
2. Pure SDP split/merge: `AdcSdp.kt`
3. `SdpHelper`: three application m-lines by mid, answer keeps three sections
4. `SipManager`: `homeReqApp` / `appReqApp` / `appSlotUsed`
5. `WebRtcManager`: Home and App PeerConnections, per-mid descriptions
6. Message routing: foreground ADC channel, drop Home traffic while an app is in front
7. `MiniAppManager`: one Re-INVITE per app change, queue, Back/Exit
8. `CallActivity`: incoming Re-INVITE diff by mid, `onJsRequestedAdc` binds to Home
9. Error handling: App-slot rollback and 491 retry
10. Cleanup and spec wording

---

### Task 1: Pure slot model (`AdcSlots.kt`)

**Files:**
- Create: `android-client/app/src/main/java/com/demo/imsdc/webrtc/AdcSlots.kt`
- Test: `android-client/app/src/test/java/com/demo/imsdc/webrtc/AdcSlotsTest.kt`

**Interfaces:**
- Produces (used by Tasks 3–9):
  - `object AdcMid { const val BDC = "2"; const val HOME = "3"; const val APP = "4" }`
  - `data class SdpAppLine(val mid: String, val port: Int, val reqApp: String?) { val isOpen get() = port != 0 }`
  - `fun parseAppLines(sdp: String): List<SdpAppLine>` — one entry per `m=application`, in order; `mid` from `a=mid:` (fallback: `"2"`, `"3"`, `"4"` by position); `reqApp` from `a=3gpp-req-app:"<id>";…` (quoted id) or `null`.
  - `sealed class AppSlotChange { object None; data class Open(val appId: String); object Close; data class Replace(val appId: String) }`
  - `fun diffAppSlot(current: String?, incoming: SdpAppLine?): AppSlotChange` — `incoming == null` or (closed and `current == null`) → `None`; closed and `current != null` → `Close`; open with no `reqApp` → `None`; open, `current == null` → `Open(id)`; open, `current == id` → `None`; open, `current != id` → `Replace(id)`.
  - `class LatestRequestQueue<T>` with `fun submit(item: T): T?` (returns the item to run now, or `null` if busy — then the item is kept as *pending*, replacing any older pending one), `fun complete(): T?` (marks the in-flight request done and returns the pending one to run next, or `null`), `val busy: Boolean`.

- [ ] **Step 1: Failing tests**

```kotlin
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
```

- [ ] **Step 2: Run, expect FAIL (cannot compile: symbols missing)** — `cd android-client && ./gradlew testDebugUnitTest --tests '*AdcSlotsTest*'`. If Gradle is unavailable, record that and continue (see Global Constraints).

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run, expect PASS** — same command.

- [ ] **Step 5: Commit**

```bash
git add android-client/app/src/main/java/com/demo/imsdc/webrtc/AdcSlots.kt android-client/app/src/test/java/com/demo/imsdc/webrtc/AdcSlotsTest.kt
git commit -m "feat(android): pure ADC slot model, mid-based SDP parse and latest-wins queue

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Pure SDP split/merge by role (`AdcSdp.kt`)

**Files:**
- Create: `android-client/app/src/main/java/com/demo/imsdc/webrtc/AdcSdp.kt`
- Test: `android-client/app/src/test/java/com/demo/imsdc/webrtc/AdcSdpTest.kt`

**Interfaces:**
- Consumes: `AdcMid` (Task 1).
- Produces:
  - `data class AdcParts(val bdc: String, val home: String?, val app: String?)` — each a **standalone single-m-line SDP** for one PeerConnection: `bdc` = session header + audio + video + first application block (exactly what `WebRtcManager.SplitAdcAnswer` returns today as its first element); `home` / `app` = session header without `a=group:BUNDLE` + that application block without `a=bundle-only`, `null` when the block is absent or lacks `a=ice-ufrag:` (existing safety rule).
  - `object AdcSdp`: `fun split(sdp: String): AdcParts` (2nd application block → `home`, 3rd → `app`; positional because answers mirror the offer's m-line order), `fun merge(bdc: String, home: String?, app: String?, isAnswer: Boolean, remoteMids: Pair<String?, String?> = null to null): String` (output application order BDC, Home, App; mids forced to `AdcMid.HOME` / `AdcMid.APP`, or in an answer to the remote offer's mids when given), `fun normalizeToLocalMid(block: String): String` (`a=mid:` → `0`, the per-PC mid used today by `replaceMidInSdp(..., "0")`).
  - `fun closedAppLine(): String` → `"m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:4\r\n"`.

- [ ] **Step 1: Failing tests**

```kotlin
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
}
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew testDebugUnitTest --tests '*AdcSdpTest*'`

- [ ] **Step 3: Implement** — `splitMedia` is a pure copy of `WebRtcManager.splitSDPToMediaBlocks` (`WebRtcManager.kt:606-640`; each block is its trimmed lines joined with `\r\n` plus a trailing `\r\n`).

```kotlin
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

    fun split(sdp: String): AdcParts {
        val m = splitMedia(sdp)
        val bdc = StringBuilder(m.header)
        m.audio?.let { bdc.append(it) }
        m.video?.let { bdc.append(it) }
        m.apps.firstOrNull()?.let { bdc.append(it) }
        return AdcParts(
            bdc.toString(),
            m.apps.getOrNull(1)?.let { standalone(m, it) },
            m.apps.getOrNull(2)?.let { standalone(m, it) }
        )
    }

    private fun setMid(block: String, mid: String): String =
        block.replace("\r", "").split("\n").filter { it.trim().isNotEmpty() }
            .joinToString("\r\n") { if (it.trim().startsWith("a=mid:")) "a=mid:$mid" else it.trim() } + "\r\n"

    fun normalizeToLocalMid(block: String): String = setMid(block, "0")

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
```

- [ ] **Step 4: Run, expect PASS** — same command. (Test `mergeOrdersBdcHomeApp_andForcesMids` expects the BDC block to keep its own mid `0`; `bdc` in the test has `a=mid:0` for its application block.)

- [ ] **Step 5: Commit**

```bash
git add android-client/app/src/main/java/com/demo/imsdc/webrtc/AdcSdp.kt android-client/app/src/test/java/com/demo/imsdc/webrtc/AdcSdpTest.kt
git commit -m "feat(android): pure SDP split/merge for BDC, Home and App ADC parts

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `SdpHelper` — three application m-lines by mid

**Files:**
- Modify: `android-client/app/src/main/java/com/demo/imsdc/sip/SdpHelper.kt` (`translateWebRtcOfferToSipOffer` `:33-280`, `translateSipAnswerToWebRtcAnswer` `:281-400`)
- Test: `android-client/app/src/test/java/com/demo/imsdc/sip/SdpHelperSlotsTest.kt`; `SdpHelperTlsIdTest.kt` must keep passing unchanged (it passes only `(sdp, ip)`).

**Interfaces:**
- Consumes: `AdcMid` (Task 1).
- Produces: new signature (all new parameters have defaults, so existing callers compile):
  `fun translateWebRtcOfferToSipOffer(sdp: String, localIpAddress: String, homeReqApp: String? = null, isCallHoldActive: Boolean = false, localAudioPort: Int = 40002, localVideoPort: Int = 40004, appReqApp: String? = null, appSlotUsed: Boolean = false): String`
  - mid 2 = first application block of the WebRTC SDP (BDC, unchanged logic).
  - mid 3 (Home) = the block whose `a=mid:` is `"3"`; legacy fallback: the first later block whose mid is not `"4"`. Emitted when `homeReqApp != null` — exactly the old `activeReqAppId` rules (`"close"`/`"operator-menu"` → `m=application 0`, otherwise full section with `a=tls-id:${tlsIdFor("home", fingerprint)}`).
  - mid 4 (App) = the block with `a=mid:4`. Emitted open (`appReqApp != null` and the block exists, `a=tls-id:${tlsIdFor("app", fingerprint)}`, `a=dcmap:1000 subprotocol="<appReqApp>"`, `a=3gpp-req-app:"<appReqApp>";1000-UE`). Otherwise, when `appSlotUsed` or the block exists, emitted closed: `m=application 0 UDP/DTLS/SCTP webrtc-datachannel`, `c=IN IP4 127.0.0.1`, `a=mid:4`. Otherwise not emitted.
  - `translateSipAnswerToWebRtcAnswer`: keeps up to three application sections (2nd and 3rd handled identically, each keeping its own `a=mid`, fallback `"3"`/`"4"`).

- [ ] **Step 1: Failing tests**

```kotlin
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
}
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew testDebugUnitTest --tests '*SdpHelperSlotsTest*' --tests '*SdpHelperTlsIdTest*'`

- [ ] **Step 3a: Rename and extend the signature** — rename the parameter `activeReqAppId` → `homeReqApp`, add `appReqApp: String? = null, appSlotUsed: Boolean = false` at the end; `val hasADC = homeReqApp != null`.

- [ ] **Step 3b: Replace the credential/endpoint extraction** — in `translateWebRtcOfferToSipOffer` replace everything from `val bdcCreds = extractCreds(orig.appBlocks.getOrNull(0))` up to (not including) `// 1.5. Extract only the first SSRC mapping lines` with:

```kotlin
    fun midOf(block: String): String? = block.split("\n").map { it.trim() }
        .firstOrNull { it.startsWith("a=mid:") }?.substringAfter("a=mid:")?.trim()

    val bdcBlock = orig.appBlocks.getOrNull(0)
    val laterBlocks = orig.appBlocks.drop(1)
    val homeBlock = laterBlocks.firstOrNull { midOf(it) == "3" } ?: laterBlocks.firstOrNull { midOf(it) != "4" }
    val appBlock = laterBlocks.firstOrNull { midOf(it) == "4" }

    class Endpoint(val port: Int, val candidate: String)
    fun endpointOf(block: String?, defaultPort: Int): Endpoint {
        var port = defaultPort
        var candidate = ""
        if (block != null) {
            val blockLines = block.split("\n")
            blockLines.find { it.startsWith("m=application ") }?.split(" ")?.getOrNull(1)?.toIntOrNull()?.let { port = it }
            val cand = blockLines.find {
                it.startsWith("a=candidate:") && it.contains(" typ host", ignoreCase = true) &&
                    it.contains(" udp ", ignoreCase = true) && (it.split(" ").getOrNull(4)?.contains(":") == false)
            } ?: blockLines.find {
                it.startsWith("a=candidate:") && it.contains(" typ host", ignoreCase = true) && it.contains(" udp ", ignoreCase = true)
            }
            if (cand != null) {
                val tokens = cand.trim().split(" ").toMutableList()
                if (tokens.size >= 8) {
                    tokens[4] = validIp
                    tokens[5] = port.toString()
                }
                candidate = tokens.joinToString(" ")
            }
        }
        return Endpoint(port, candidate)
    }

    val bdcCreds = extractCreds(bdcBlock)
    val homeCreds = extractCreds(homeBlock)
    val appCreds = extractCreds(appBlock)
    val bdcEp = endpointOf(bdcBlock, 40000)
    val homeEp = endpointOf(homeBlock, 40006)
    val appEp = endpointOf(appBlock, 40008)
    val bdcPort = bdcEp.port
    val bdcCandidate = bdcEp.candidate
```

(The BDC emission block `// 4. Synthesize BDC application section (mid 2)` keeps using `bdcPort`, `bdcCandidate`, `bdcCreds` unchanged.)

- [ ] **Step 3c: Replace the ADC emission** — replace from `// 5. Synthesize ADC application section (mid 3) if active` through the closing brace of `if (hasADC) { … }` with:

```kotlin
    fun addAdcSection(mid: String, label: String, creds: Credentials, ep: Endpoint, appId: String) {
        newLines.add("m=application ${ep.port} UDP/DTLS/SCTP webrtc-datachannel")
        newLines.add("c=IN IP4 $validIp")
        newLines.add("a=mid:$mid")
        if (creds.ufrag.isNotEmpty()) newLines.add(creds.ufrag)
        if (creds.pwd.isNotEmpty()) newLines.add(creds.pwd)
        if (creds.fingerprint.isNotEmpty()) newLines.add(creds.fingerprint)
        newLines.add(if (creds.setup.isNotEmpty()) creds.setup else "a=setup:actpass")
        if (ep.candidate.isNotEmpty()) newLines.add(ep.candidate)
        newLines.add("a=tls-id:${tlsIdFor(label, creds.fingerprint)}")
        newLines.add("a=dcmap:1000 subprotocol=\"$appId\"")
        newLines.add("a=3gpp-req-app:\"$appId\";1000-UE")
        newLines.add(if (isCallHoldActive) "a=inactive" else "a=sendrecv")
    }
    fun addClosedSection(mid: String) {
        newLines.add("m=application 0 UDP/DTLS/SCTP webrtc-datachannel")
        newLines.add("c=IN IP4 127.0.0.1")
        newLines.add("a=mid:$mid")
    }

    // 5. Home ADC (mid 3)
    if (hasADC) {
        val appId = homeReqApp!!
        if (appId == "close" || appId == "operator-menu") addClosedSection("3")
        else addAdcSection("3", "home", homeCreds, homeEp, appId)
    }
    // 6. App ADC (mid 4): once it has appeared it stays in every offer, as port 0 while no app is open
    if (appReqApp != null && appBlock != null) {
        addAdcSection("4", "app", appCreds, appEp, appReqApp)
    } else if (appSlotUsed || appBlock != null) {
        addClosedSection("4")
    }
```

`Credentials` is the existing local data class; `addAdcSection` is a local function declared after it, so it is in scope.

- [ ] **Step 3d: Third section in `translateSipAnswerToWebRtcAnswer`** — inside `if (app1 != null) { … if (appSections.size > 1) { … } }` replace the `app2` block with a loop over `appSections.drop(1).take(2)` using the same body, with the mid fallback `if (app.mid.isNotEmpty()) app.mid else (index + 3).toString()`:

```kotlin
    appSections.drop(1).take(2).forEachIndexed { index, app ->
        val candidateLine = "a=candidate:1 1 udp 2122260991 ${app.remoteIp} ${app.appPort} typ host generation 0"
        newLines.add("m=application ${app.appPort} UDP/DTLS/SCTP webrtc-datachannel")
        newLines.add("c=IN IP4 ${app.remoteIp}")
        newLines.add("a=mid:${if (app.mid.isNotEmpty()) app.mid else (index + 3).toString()}")
        newLines.add("a=sctp-port:5000")
        newLines.add("a=ice-lite")
        if (app.iceUfrag.isNotEmpty()) newLines.add(app.iceUfrag)
        if (app.icePwd.isNotEmpty()) newLines.add(app.icePwd)
        if (app.fingerprint.isNotEmpty()) newLines.add(app.fingerprint)
        if (app.setup.isNotEmpty()) newLines.add(app.setup)
        newLines.add(candidateLine)
    }
```

A port-0 section produces a candidate with port 0; `AdcSdp.split` already drops a block without `a=ice-ufrag:`, and the closed network line carries none.

- [ ] **Step 4: Run, expect PASS** — same command; both test classes green.

- [ ] **Step 5: Commit**

```bash
git add android-client/app/src/main/java/com/demo/imsdc/sip/SdpHelper.kt android-client/app/src/test/java/com/demo/imsdc/sip/SdpHelperSlotsTest.kt
git commit -m "feat(android): SdpHelper emits Home (mid 3) and App (mid 4) ADC m-lines

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `SipManager` — `homeReqApp`, `appReqApp`, `appSlotUsed`

**Files:**
- Modify: `android-client/app/src/main/java/com/demo/imsdc/sip/SipManager.kt` (`:87`, `:735`, four translate calls `:881`, `:931`, `:967`, `:1073`), `ui/MiniAppManager.kt`, `ui/CallActivity.kt` (rename sites only)

**Interfaces:**
- Produces: `var homeReqApp: String?` (replaces `activeReqAppId`), `var appReqApp: String?`, `var appSlotUsed: Boolean`. `resetCallState()` clears all three. Every `translateWebRtcOfferToSipOffer` call passes `homeReqApp, isCallHoldActive, audioPort, videoPort, appReqApp, appSlotUsed`.

- [ ] **Step 1: Rename and add fields**

```bash
cd android-client/app/src/main/java/com/demo/imsdc
grep -rln "activeReqAppId" --include=*.kt . | xargs sed -i 's/activeReqAppId/homeReqApp/g'
```

In `SipManager.kt` after `var homeReqApp: String? = null` add:

```kotlin
    var appReqApp: String? = null     // mid 4; null while no app is open
    var appSlotUsed: Boolean = false  // mid 4 has appeared in this call; it stays in every SDP from then on
```

In `resetCallState()` after `homeReqApp = null`: `appReqApp = null; appSlotUsed = false`.

- [ ] **Step 2: Pass the new arguments** — at all four calls change `homeReqApp, isCallHoldActive, RtpPorts.current().first, RtpPorts.current().second)` to `homeReqApp, isCallHoldActive, RtpPorts.current().first, RtpPorts.current().second, appReqApp, appSlotUsed)`.

- [ ] **Step 3: Check** — `grep -rn "activeReqAppId" android-client/app/src` returns nothing; `./gradlew compileDebugKotlin` (when available).

- [ ] **Step 4: Commit** — `git commit -am "refactor(android): homeReqApp/appReqApp/appSlotUsed on SipManager"` with the trailer.

---

### Task 5: `WebRtcManager` — Home and App PeerConnections

**Files:**
- Modify: `android-client/app/src/main/java/com/demo/imsdc/webrtc/WebRtcManager.kt`, `ui/CallActivity.kt:1193` (`adcPC` rename only)

**Interfaces:**
- Consumes: `AdcSdp`, `AdcMid`, `parseAppLines`, `closedAppLine` (Tasks 1–2).
- Produces (used by Tasks 6–9):
  - `internal var homeAdcPC`, `internal var appAdcPC: PeerConnection?`; `var adcChannel` / `var adc2Channel` (Home streams 1000/1001, unchanged names), `var appAdcChannel: DataChannel?`.
  - `fun createAdcPeerConnection(kind: AdcKind = AdcKind.HOME, onStateChange: …)` with `enum class AdcKind { HOME, APP }`.
  - `fun createADC(): DataChannel?` — Home, stream 1000 (unchanged contract); `fun createADC2()` — Home, stream 1001; **new** `fun createAppADC(): DataChannel?` — creates `appAdcPC` if null and a negotiated channel `id=1000, label="app-dc-app"` in `appAdcChannel`.
  - **new** `fun closeAppADC()` — closes only `appAdcChannel` and `appAdcPC` (dispose after 500 ms, exactly like today's `closeADC`), never touches Home or BDC. `fun closeADC()` now closes the App slot and then Home (hang-up use only).
  - `@Volatile var foregroundIsHome: Boolean = true`; `val foregroundAdcChannel: DataChannel? get() = if (foregroundIsHome) adcChannel else appAdcChannel`.
  - `createOffer()` / `createAnswer()` / `setRemoteAnswer()` / `setRemoteOffer()` / `getLocalSdp()` handle Home and App through `AdcSdp.split` / `AdcSdp.merge`.
  - Removed: `currentAdcMid`, `getNextAdcMid()`, `lastRemoteAdcOfferMid`, `SplitAdcAnswer`, `MergeAdcOffer`, `replaceMidInSdp` (replaced by `AdcSdp.normalizeToLocalMid`). `splitSDPToMediaBlocks` / `extractMidFromSdp` may stay if still referenced; delete when unused.

- [ ] **Step 1: Rename** — in `WebRtcManager.kt` and `CallActivity.kt:1193`: `adcPC` → `homeAdcPC` (`sed -i 's/\badcPC\b/homeAdcPC/g'`; `adcPCLocal` stays). Then add next to the fields at `:44`:

```kotlin
    internal var appAdcPC: PeerConnection? = null
    var appAdcChannel: DataChannel? = null
    @Volatile var foregroundIsHome: Boolean = true
    val foregroundAdcChannel: DataChannel? get() = if (foregroundIsHome) adcChannel else appAdcChannel
    private var remoteHomeMid: String? = null
    private var remoteAppMid: String? = null

    enum class AdcKind { HOME, APP }
```

- [ ] **Step 2: `createAdcPeerConnection(kind, …)`** — change the signature to `fun createAdcPeerConnection(kind: AdcKind = AdcKind.HOME, onStateChange: (PeerConnection.PeerConnectionState) -> Unit)` and the final assignment at `:308` to:

```kotlin
        val created = peerConnectionFactory?.createPeerConnection(config, observer)
        if (kind == AdcKind.HOME) homeAdcPC = created else appAdcPC = created
        Log.i(TAG, "ADC PeerConnection created ($kind)")
```

In `createOfferRaw` / `createAnswerRaw`: `val isAdc = (pc == homeAdcPC || pc == appAdcPC)`.

- [ ] **Step 3: `createAppADC` and `closeAppADC`** — add after `createADC2()`:

```kotlin
    fun createAppADC(): DataChannel? {
        if (appAdcPC == null) {
            createAdcPeerConnection(AdcKind.APP) { state -> Log.d(TAG, "App ADC PeerConnection state: $state") }
        }
        appAdcChannel?.let { if (it.state() == DataChannel.State.CLOSED) appAdcChannel = null }
        if (appAdcChannel == null) {
            val init = DataChannel.Init().apply { id = 1000; negotiated = true; ordered = true }
            appAdcChannel = appAdcPC?.createDataChannel("app-dc-app", init)
            Log.i(TAG, "App ADC created: id=1000, label=app-dc-app, negotiated=true")
        }
        return appAdcChannel
    }

    /** Closes only the App slot (mid 4). The Home ADC and the BDC are not touched. */
    fun closeAppADC() {
        Log.i(TAG, "Closing App ADC and destroying its PeerConnection...")
        try {
            appAdcChannel?.close()
            appAdcChannel = null
            val old = appAdcPC
            appAdcPC?.close()
            appAdcPC = null
            if (old != null) {
                Thread {
                    try {
                        Thread.sleep(500)
                        old.dispose()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error disposing App ADC PC: ${e.message}")
                    }
                }.start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing App ADC: ${e.message}")
        }
    }
```

Rewrite `closeADC()` (`:962`): first line `closeAppADC()`, keep its existing Home body, **delete** the `currentAdcMid++` line. In `close()` (`:994`) call `closeAppADC()` wherever it releases `bdcPC`/`homeAdcPC`.

- [ ] **Step 4: Offer, answer, remote descriptions** — replace `createOffer()` (`:365`):

```kotlin
    private suspend fun offerFor(pc: PeerConnection?): String? {
        pc ?: return null
        if (pc.signalingState() == PeerConnection.SignalingState.STABLE && pc.localDescription != null) {
            return pc.localDescription.description // unchanged m-line: same credentials, same tls-id
        }
        val raw = createOfferRaw(pc).description
        awaitIceGatheringComplete(pc)
        return pc.localDescription?.description ?: raw
    }

    suspend fun createOffer(): String {
        ensureBdcChannel()
        val bdcOffer = offerFor(bdcPC) ?: ""
        val merged = AdcSdp.merge(bdcOffer, offerFor(homeAdcPC), offerFor(appAdcPC), isAnswer = false)
        return if (homeAdcPC == null) {
            merged + "m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 0.0.0.0\r\na=mid:3\r\n"  // Rule 12, as before
        } else merged
    }
```

(The BDC branch keeps the existing "reuse when STABLE" behaviour: `offerFor(bdcPC)` is equivalent to the code it replaces.) Replace `createAnswer()` (`:567`) the same way with `answerFor(pc)` (`createAnswerRaw`) and:

```kotlin
        return AdcSdp.merge(answerFor(bdcPC) ?: "", answerFor(homeAdcPC), answerFor(appAdcPC), isAnswer = true, remoteMids = remoteHomeMid to remoteAppMid)
```

Replace `setRemoteAnswer`:

```kotlin
    private suspend fun applyAnswer(pc: PeerConnection?, part: String?, label: String) {
        if (pc == null || part == null) return
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            Log.i(TAG, "Skipping setRemoteAnswer on $label PC because signalingState is ${pc.signalingState()}")
            return
        }
        setRemoteDescriptionRaw(pc, AdcSdp.normalizeToLocalMid(addCandidateIfMissing(part)), SessionDescription.Type.ANSWER)
    }

    suspend fun setRemoteAnswer(sdpAnswer: String) {
        val parts = AdcSdp.split(sdpAnswer)
        val bdcAnswer = addCandidateIfMissing(parts.bdc)
        if (bdcPC != null && bdcPC!!.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            setRemoteDescriptionRaw(bdcPC, bdcAnswer, SessionDescription.Type.ANSWER)
        } else {
            Log.i(TAG, "Skipping setRemoteAnswer on BDC PC because signalingState is ${bdcPC?.signalingState()}")
        }
        applyAnswer(homeAdcPC, parts.home, "Home ADC")
        applyAnswer(appAdcPC, parts.app, "App ADC")
    }
```

Replace `setRemoteOffer`:

```kotlin
    private suspend fun applyOffer(pc: PeerConnection?, part: String?, label: String) {
        if (pc == null || part == null) return
        if (pc.signalingState() == PeerConnection.SignalingState.STABLE && pc.localDescription == null) {
            setRemoteDescriptionRaw(pc, AdcSdp.normalizeToLocalMid(addCandidateIfMissing(part)), SessionDescription.Type.OFFER)
        } else {
            Log.i(TAG, "Skipping setRemoteOffer on $label PC because signalingState is ${pc.signalingState()}")
        }
    }

    suspend fun setRemoteOffer(sdpOffer: String) {
        val parts = AdcSdp.split(sdpOffer)
        val lines = parseAppLines(sdpOffer)
        remoteHomeMid = lines.getOrNull(1)?.mid
        remoteAppMid = lines.getOrNull(2)?.mid
        val bdcOffer = addCandidateIfMissing(parts.bdc)
        if (bdcPC != null) {
            if (bdcPC!!.signalingState() == PeerConnection.SignalingState.STABLE && bdcPC!!.localDescription == null) {
                setRemoteDescriptionRaw(bdcPC, bdcOffer, SessionDescription.Type.OFFER)
            } else {
                Log.i(TAG, "Skipping setRemoteOffer on BDC PC because signalingState is ${bdcPC?.signalingState()}")
            }
        }
        if (parts.home != null && homeAdcPC == null) {
            createAdcPeerConnection(AdcKind.HOME) { state -> Log.d(TAG, "Home ADC (B-side) state: $state") }
            createADC()
        }
        applyOffer(homeAdcPC, parts.home, "Home ADC")
        // The App PC is created or closed by CallActivity from the mid-4 diff before this call.
        applyOffer(appAdcPC, parts.app, "App ADC")
    }
```

A Home line with port 0 in a later offer is ignored: the Home ADC is closed only by `closeADC()` at hang-up.

Replace `getLocalSdp()` body's merge: `return AdcSdp.merge(bdcSdp, homeAdcPC?.localDescription?.description, appAdcPC?.localDescription?.description, isAnswer = false)` (keep the early `bdcSdp == null` return).

- [ ] **Step 5: Remove dead code** — delete `SplitAdcAnswer`, `MergeAdcOffer`, `replaceMidInSdp`, `getNextAdcMid`, `currentAdcMid`, `lastRemoteAdcOfferMid`; `grep -rn "SplitAdcAnswer\|MergeAdcOffer\|getNextAdcMid\|lastRemoteAdcOfferMid\|currentAdcMid" android-client/app/src` must be empty (tests included).

- [ ] **Step 6: Build and commit** — `./gradlew testDebugUnitTest compileDebugKotlin` (when available); `git commit -am "feat(android): Home and App ADC PeerConnections with per-mid descriptions"` with the trailer.

---

### Task 6: Message routing — foreground ADC channel

**Files:**
- Modify: `android-client/app/src/main/java/com/demo/imsdc/webrtc/DataChannelManager.kt` (`setupADC` `:471-600`, `sendViaADC` `:658`), `webrtc/ImsDcSdk.kt` (`:45`, `:57`, `:68`), `webrtc/PhotoShareTransport.kt` (`:216`)

**Interfaces:**
- Consumes: `WebRtcManager.foregroundAdcChannel`, `foregroundIsHome`, `appAdcChannel` (Task 5).
- Produces: `DataChannelManager.setupAppADC()` (observer for `appAdcChannel`, used by Tasks 7–8). Home traffic is dropped while an app is in front; page → ADC always uses the foreground app's ADC.

These classes wrap native `DataChannel` objects that cannot be built in a JVM test, so this task has no unit test; it is verified by compile and by the device checks in Task 10.

- [ ] **Step 1: Share the observer** — in `DataChannelManager.kt` turn the body of `setupADC()` into a private function and keep `setupADC()` as a one-line caller:

```kotlin
    fun setupADC() = attachAdcObserver(webRtcManager.adcChannel, isHome = true)

    fun setupAppADC() = attachAdcObserver(webRtcManager.appAdcChannel, isHome = false)

    private fun attachAdcObserver(adc: DataChannel?, isHome: Boolean) {
        if (adc != null) {
            adc.registerObserver(object : DataChannel.Observer {
                // … the existing observer body, unchanged, except the first lines of onMessage (Step 2)
            })
        }
    }
```

Keep the existing text of the observer verbatim (it already refers to the local name `adc`); only the `val adc = webRtcManager.adcChannel` / `if (adc != null)` lines at the top of the old function are replaced by the parameter and the null check shown.

- [ ] **Step 2: Drop Home traffic while an app is in front** — at the top of `onMessage`, after `buffer ?: return`:

```kotlin
                        if (isHome && !webRtcManager.foregroundIsHome) {
                            Log.i(TAG, "Home ADC message dropped: an app is in front (${buffer.data.remaining()} bytes)")
                            return
                        }
```

App-ADC messages are never dropped: they can arrive just before the UI finishes switching to the app.

- [ ] **Step 3: Send on the foreground channel** — replace `webRtcManager.adcChannel` with `webRtcManager.foregroundAdcChannel` in `sendViaADC` (`DataChannelManager.kt:659`), in `ImsDcSdk.flushPendingAdc` / `sendOrQueueAdc` / its retry loop / `awaitOpenChannel` (`ImsDcSdk.kt:45, 57, 68`, and `:~73` `awaitOpenChannel`), and in `PhotoShareTransport.kt:216`. `sendViaADC2`, `setupADC2` and every `adc2Channel` use stay on Home (emotes, stream 1001).

- [ ] **Step 4: Compile and commit** — `./gradlew compileDebugKotlin` (when available); `git commit -am "feat(android): route ADC traffic by foreground app"` with the trailer.

---

### Task 7: `MiniAppManager` — one Re-INVITE per app change

**Files:**
- Modify: `android-client/app/src/main/java/com/demo/imsdc/ui/MiniAppManager.kt` (`launchMiniApp` `:408-525`, `triggerAppNegotiationReInvite` / `sendAppReInvite` `:527-620`)

**Interfaces:**
- Consumes: `LatestRequestQueue`, `AdcMid` (Task 1); `SipManager.homeReqApp/appReqApp/appSlotUsed` (Task 4); `WebRtcManager.createAppADC/closeAppADC/foregroundIsHome/homeAdcPC/appAdcPC` (Task 5); `DataChannelManager.setupAppADC` (Task 6).
- Produces: `const val HOME_APP = "fun-calling-app"` (companion object of `MiniAppManager`); `fun triggerAppNegotiationReInvite(appId: String)` keeps its name and contract: requests the Re-INVITE for the target app; `fun onReInviteAnswered()` unchanged.

**Rulings baked into this task**
- `operator-menu` and `fun-calling-app` both mean "emotes (Home)" once the Home ADC is open. While Home is **not** open, `operator-menu` stays the local menu page and sends nothing (there is no ADC to release any more).
- Opening an app while the Home ADC is not open first runs the Home Re-INVITE (mid 3), then the app Re-INVITE (mid 4). This is the only case with two Re-INVITEs and only happens when the user taps an app before ever opening emotes.
- Requests that are already the current state (`appReqApp == target`, or Back while no app is open) send nothing.

- [ ] **Step 1: Replace the Re-INVITE machinery** — delete `reInviteJob`'s download-wait state (`reInviteTargetId`, `reInviteWaitingForDownload`, `APP_DOWNLOAD_WAIT_MS`), the `awaitAppDownload` call, the "Releasing ADC of …" block and `REINVITE_ANSWER_WAIT_MS`; replace `triggerAppNegotiationReInvite` and `sendAppReInvite` with:

```kotlin
    companion object {
        const val HOME_APP = "fun-calling-app"
        private const val ANSWER_TIMEOUT_MS = 5000L
    }

    private val requests = LatestRequestQueue<String>()
    private var reInviteAnswer: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun onReInviteAnswered() {
        reInviteAnswer?.complete(Unit)
    }

    private fun normalizeTarget(appId: String): String {
        val id = when {
            appId.contains("0003210000000038") || appId.contains("tuyaapp") || appId.contains("sketch-sync-app") -> "sketch-sync-app"
            else -> appId
        }
        val homeOpen = activity.webRtcManager.homeAdcPC != null
        return if (id.contains("operator-menu") && homeOpen) HOME_APP else id
    }

    fun triggerAppNegotiationReInvite(appId: String) {
        val target = normalizeTarget(appId)
        if (target.contains("operator-menu")) return // Home not open: local menu, nothing to negotiate
        requests.submit(target)?.let { runRequest(it) }
            ?: Log.i(TAG, "Re-INVITE in flight, keeping only the latest request: $target")
    }

    private fun runRequest(target: String) {
        activity.lifecycleScope.launch(Dispatchers.Main) {
            try {
                if (target != HOME_APP && activity.webRtcManager.homeAdcPC == null) {
                    sendChange(HOME_APP)            // Home first (mid 3), see ruling
                }
                sendChange(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Re-INVITE coroutine cancelled for $target (call ended)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Re-INVITE for $target: ${e.message}")
            } finally {
                requests.complete()?.let { runRequest(it) }
            }
        }
    }

    /** Applies the local ADC change for [target] and sends exactly one Re-INVITE (or none if nothing changes). */
    private suspend fun sendChange(target: String) {
        val web = activity.webRtcManager
        val sip = activity.sipManager
        if (target == HOME_APP) {
            val homeOpen = web.homeAdcPC != null
            val appOpen = sip.appReqApp != null
            if (homeOpen && !appOpen) return          // already on emotes: nothing to negotiate
            if (!homeOpen) {
                sip.homeReqApp = HOME_APP
                web.createADC(); activity.dcManager.setupADC()
                web.createADC2(); activity.dcManager.setupADC2()
            }
            if (appOpen) {
                sip.appReqApp = null                  // mid 4 -> port 0
                web.closeAppADC()
            }
        } else {
            if (sip.appReqApp == target && web.appAdcPC != null) return
            web.closeAppADC()                         // replace in place: new PC, new credentials, new tls-id
            sip.appReqApp = target
            sip.appSlotUsed = true
            web.createAppADC()
            activity.dcManager.setupAppADC()
        }
        val localOffer = web.createOffer()
        reInviteAnswer = kotlinx.coroutines.CompletableDeferred()
        Log.i(TAG, "Re-INVITE for $target (home=${sip.homeReqApp}, app=${sip.appReqApp})")
        withContext(Dispatchers.IO) { sip.sendReInviteWithSdp(localOffer) }
        val answered = kotlinx.coroutines.withTimeoutOrNull(ANSWER_TIMEOUT_MS) { reInviteAnswer?.await() }
        if (answered == null) onReInviteUnanswered(target) // Task 9
    }

    // Replaced in Task 9; until then a missing answer is only logged.
    private fun onReInviteUnanswered(target: String) {
        Log.w(TAG, "No 200 OK for the Re-INVITE of $target within ${ANSWER_TIMEOUT_MS} ms")
    }
```

- [ ] **Step 2: Foreground flag and Back/Exit** — in `launchMiniApp`, right after `activity.currentAppId = targetId` add `activity.webRtcManager.foregroundIsHome = (targetId == HOME_APP || targetId == "operator-menu")`. In the `targetId == "operator-menu"` branch replace `triggerAppNegotiationReInvite("operator-menu")` with `triggerAppNegotiationReInvite(HOME_APP)` (Back/Exit closes mid 4: one Re-INVITE; with no app open `sendChange` returns without sending). The page reload that follows is unchanged. In the tail `if (sendRemoteTrigger && targetId != "operator-menu")` keep as is.

- [ ] **Step 3: Remove dead references** — `grep -n "APP_DOWNLOAD_WAIT_MS\|REINVITE_ANSWER_WAIT_MS\|reInviteWaitingForDownload\|reInviteTargetId\|reInviteJob" ui/MiniAppManager.kt` must be empty.

- [ ] **Step 4: Compile and commit** — `./gradlew compileDebugKotlin`; `git commit -am "feat(android): one Re-INVITE per app change with a latest-wins queue"` with the trailer.

---

### Task 8: `CallActivity` — incoming Re-INVITE by mid, Home binding

**Files:**
- Modify: `android-client/app/src/main/java/com/demo/imsdc/ui/CallActivity.kt` (`onReInviteReceived` `:1014-1075`, `onJsRequestedAdc` `:1179-1205`)

**Interfaces:**
- Consumes: `parseAppLines`, `diffAppSlot`, `AppSlotChange` (Task 1); `WebRtcManager` and `DataChannelManager` calls (Tasks 5–6); `MiniAppManager.HOME_APP` (Task 7).

- [ ] **Step 1: Replace the body of `onReInviteReceived`** from `var reqApp = …parseReqApp(rawMsg)` through the `webRtcManager.createADC2 … }` block (everything before `if (sdp.trim().isNotEmpty())`) with:

```kotlin
                    val appLines = com.demo.imsdc.webrtc.parseAppLines(rawSdp)
                    val homeLine = appLines.getOrNull(1)          // mid 3
                    val appLine = appLines.getOrNull(2)           // mid 4
                    if (appLine != null) sipManager.appSlotUsed = true

                    // Home ADC: opened by the first offer that carries it, never closed by a Re-INVITE.
                    val homeJustOpened = homeLine != null && homeLine.isOpen && webRtcManager.homeAdcPC == null
                    if (homeJustOpened) {
                        val homeId = homeLine!!.reqApp ?: MiniAppManager.HOME_APP
                        sipManager.homeReqApp = homeId
                        webRtcManager.createADC(); dcManager.setupADC()
                        if (homeId == MiniAppManager.HOME_APP) { webRtcManager.createADC2(); dcManager.setupADC2() }
                    }

                    // App ADC: rebuild only the PeerConnection of mid 4, and only when mid 4 changed.
                    val change = com.demo.imsdc.webrtc.diffAppSlot(sipManager.appReqApp, appLine)
                    Log.i(TAG, "Re-INVITE: home=${homeLine?.reqApp}/${homeLine?.port} app=${appLine?.reqApp}/${appLine?.port} change=$change")
                    when (change) {
                        is com.demo.imsdc.webrtc.AppSlotChange.Open, is com.demo.imsdc.webrtc.AppSlotChange.Replace -> {
                            val id = if (change is com.demo.imsdc.webrtc.AppSlotChange.Open) change.appId
                                     else (change as com.demo.imsdc.webrtc.AppSlotChange.Replace).appId
                            webRtcManager.closeAppADC()
                            sipManager.appReqApp = id
                            webRtcManager.createAppADC(); dcManager.setupAppADC()
                        }
                        com.demo.imsdc.webrtc.AppSlotChange.Close -> {
                            webRtcManager.closeAppADC()
                            sipManager.appReqApp = null
                        }
                        com.demo.imsdc.webrtc.AppSlotChange.None -> Unit
                    }
```

- [ ] **Step 2: Replace the UI launch** at the end of the same handler (`miniAppManager.launchMiniApp(reqApp, sendRemoteTrigger = false)`) with:

```kotlin
                        val launch = when (change) {
                            is com.demo.imsdc.webrtc.AppSlotChange.Open -> change.appId
                            is com.demo.imsdc.webrtc.AppSlotChange.Replace -> change.appId
                            com.demo.imsdc.webrtc.AppSlotChange.Close -> MiniAppManager.HOME_APP
                            com.demo.imsdc.webrtc.AppSlotChange.None -> if (homeJustOpened) (sipManager.homeReqApp ?: MiniAppManager.HOME_APP) else null
                        }
                        if (launch != null) miniAppManager.launchMiniApp(launch, sendRemoteTrigger = false)
```

Delete the old log lines that print `reqApp` (the variable no longer exists). Hotline: a hotline offer has the BDC and one ADC line, i.e. `appLines.size == 2`; `homeLine` is the hotline app, `appLine` is null, behaviour matches the old single-app path.

- [ ] **Step 3: `onJsRequestedAdc`** — replace the `if (currentReqApp != targetId || webRtcManager.homeAdcPC == null) { … }` condition (and drop the now-unused `currentReqApp` computation) with:

```kotlin
                val isHome = targetId == MiniAppManager.HOME_APP
                val alreadyOpen = if (isHome) webRtcManager.homeAdcPC != null
                                  else sipManager.appReqApp == targetId && webRtcManager.appAdcPC != null
                if (!alreadyOpen) {
                    android.util.Log.i(TAG, "JS called createDataChannel() for app $appId ($targetId), natively triggering SIP Re-INVITE")
                    miniAppManager.triggerAppNegotiationReInvite(targetId)
                } else if (isHome) {
                    // The Home ADC has been open since the first emote Re-INVITE: bind the page to it.
                    val adc = webRtcManager.adcChannel
                    if (adc != null && adc.state() == org.webrtc.DataChannel.State.OPEN) {
                        imsDcSdk.notifyDataChannelState(imsDcSdk.getPrimaryDcLabel(), 1)
                        imsDcSdk.flushPendingAdc()
                    }
                    android.util.Log.i(TAG, "Home ADC already open, page bound without a Re-INVITE")
                } else {
                    android.util.Log.d(TAG, "ADC negotiation already in progress for $appId ($targetId), ignoring duplicate JS request")
                }
```

- [ ] **Step 4: Compile and commit** — `./gradlew compileDebugKotlin`; `grep -n "closeADC()" ui/CallActivity.kt ui/MiniAppManager.kt` must show no remaining call except hang-up; `git commit -am "feat(android): diff incoming Re-INVITE by mid; bind emote page to the open Home ADC"` with the trailer.

---

### Task 9: Error handling — App-slot rollback and 491 retry

**Files:**
- Modify: `webrtc/AdcSlots.kt` (add `reInviteRetryDelayMs`), `sip/SipManager.kt` (`:569` failed branch, new callback), `ui/MiniAppManager.kt` (`onReInviteUnanswered`, new `onReInviteFailed`), `ui/CallActivity.kt` (wire the callback)
- Test: `AdcSlotsTest.kt`

**Interfaces:**
- Produces: `fun reInviteRetryDelayMs(isCallIdOwner: Boolean, random: kotlin.random.Random = kotlin.random.Random): Long` — RFC 3261 §14.1: owner of the Call-ID waits 2100–4000 ms, the other side 0–2000 ms. `SipManager.onReInviteFailed: ((statusCode: Int) -> Unit)?`. `MiniAppManager.onReInviteFailed(code: Int)`.

- [ ] **Step 1: Failing test** (append to `AdcSlotsTest`)

```kotlin
    @Test
    fun retryDelayFollowsRfc3261() {
        repeat(200) {
            assertTrue(reInviteRetryDelayMs(true) in 2100L..4000L)
            assertTrue(reInviteRetryDelayMs(false) in 0L..2000L)
        }
    }
```

- [ ] **Step 2: Implement** (append to `AdcSlots.kt`)

```kotlin
fun reInviteRetryDelayMs(isCallIdOwner: Boolean, random: kotlin.random.Random = kotlin.random.Random): Long =
    if (isCallIdOwner) random.nextLong(2100, 4001) else random.nextLong(0, 2001)
```

- [ ] **Step 3: Report failures from SipManager** — add `var onReInviteFailed: ((Int) -> Unit)? = null` next to `onReInviteReceived`; in the branch `Log.w(TAG, "Re-INVITE failed with $statusCode — call continues")` add `onReInviteFailed?.invoke(statusCode)`. In `CallActivity` (near `sipManager.onReInviteReceived`) add `sipManager.onReInviteFailed = { code -> lifecycleScope.launch(Dispatchers.Main) { miniAppManager.onReInviteFailed(code) } }` and clear it where `onInviteResponse = null` is cleared (`:1667`).

- [ ] **Step 4: Rollback and retry in `MiniAppManager`** — replace the Task 7 stub `onReInviteUnanswered` and add `onReInviteFailed`:

```kotlin
    private var lastTarget: String? = null          // set at the top of sendChange: lastTarget = target

    /** Roll back the App slot only. Home ADC and BDC are untouched. */
    private fun rollbackAppSlot(reason: String) {
        Log.w(TAG, "Rolling back App ADC: $reason")
        activity.webRtcManager.closeAppADC()
        activity.sipManager.appReqApp = null          // mid 4 -> port 0 locally; appSlotUsed stays true
        android.widget.Toast.makeText(activity, "Không mở được ứng dụng, quay về emote", android.widget.Toast.LENGTH_SHORT).show()
        launchMiniApp(HOME_APP, sendRemoteTrigger = false)
    }

    private fun onReInviteUnanswered(target: String) {
        if (target != HOME_APP) rollbackAppSlot("no 200 OK within ${ANSWER_TIMEOUT_MS} ms for $target")
        else Log.w(TAG, "No 200 OK for the Home Re-INVITE")
    }

    fun onReInviteFailed(code: Int) {
        val target = lastTarget ?: return
        reInviteAnswer?.complete(Unit)                // unblock the waiting request
        if (code == 491) {
            val delayMs = reInviteRetryDelayMs(isCallIdOwner = activity.isCaller)
            Log.i(TAG, "491 glare for $target, retrying in $delayMs ms")
            activity.lifecycleScope.launch(Dispatchers.Main) {
                kotlinx.coroutines.delay(delayMs)
                triggerAppNegotiationReInvite(target)
            }
        } else if (target != HOME_APP) {
            rollbackAppSlot("Re-INVITE for $target failed with $code")
        }
    }
```

`activity.isCaller`: use the existing flag that says this side sent the initial INVITE (`grep -n "isCaller" ui/CallActivity.kt`; if it is private or named differently, expose or use that name). In `sendChange` add `lastTarget = target` as its first line. A 491 retry re-enters `triggerAppNegotiationReInvite`, which queues behind the current request via `LatestRequestQueue`, so a newer user request still wins.

The failed request's local state: after `complete(Unit)` the code in `sendChange` sees `answered != null` and does not roll back a second time; for 491 the retry closes and recreates `appAdcPC` through `sendChange` (which calls `closeAppADC()` first), so no stale PeerConnection is left.

- [ ] **Step 5: Run unit tests, compile, commit** — `./gradlew testDebugUnitTest compileDebugKotlin`; `git commit -am "feat(android): roll back the App ADC on Re-INVITE failure; retry 491 with RFC 3261 back-off"` with the trailer.

---

### Task 10: Cleanup, spec wording, device run

**Files:**
- Modify: `webrtc/DataChannelManager.kt` (`awaitAppDownload`), `docs/superpowers/specs/2026-10-31-background-emote-adc-design.md` (§3)

- [ ] **Step 1: Remove dead code** — `grep -rn "awaitAppDownload\|APP_DOWNLOAD_WAIT_MS\|REINVITE_ANSWER_WAIT_MS\|SplitAdcAnswer\|MergeAdcOffer\|currentAdcMid" android-client/app/src`; delete `awaitAppDownload` (and any state only it used) from `DataChannelManager.kt` if nothing references it; keep `cancelPendingDownload`.
- [ ] **Step 2: Spec wording** — in the spec §3 `SdpHelper` bullet replace "`a=tls-id` generated once per m-line per call and reused" with "`a=tls-id` is one per DTLS association: stable while the PeerConnection lives, new with a new PeerConnection (derived from the certificate fingerprint)".
- [ ] **Step 3: Full build** — `cd android-client && ./gradlew testDebugUnitTest assembleDebug`.
- [ ] **Step 4: Commit** — `git commit -am "chore(android): remove release-and-wait leftovers; align spec tls-id wording"` with the trailer.
- [ ] **Step 5: Device run (user, two phones, after the network plan is deployed)** — with the existing scripts (`test_fun_calling_*`, `test_caro_*`): call → emotes → Caro → back → photo-share → Caro → photo-share → hang up, then check `adb logcat`: exactly one `Re-INVITE for <app>` line per step; one `BDC created` per call; no `Closing Application Data Channel (ADC)` (Home) before hang-up; `Closing App ADC` once per leave/switch; emote messages flow before and after each step; no `Home ADC message dropped` while emotes is in front.

---

## Scan table (for the SDD ledger)

| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| 1 → 2 | `AdcMid` constants | consistent |
| 1 → 3, 7, 8, 9 | `parseAppLines`, `diffAppSlot`, `AppSlotChange`, `LatestRequestQueue` | consistent; Task 8 pattern-matches the sealed class exactly as declared |
| 2 → 5 | `AdcSdp.split/merge/normalizeToLocalMid` | consistent; Task 5 deletes `SplitAdcAnswer`/`MergeAdcOffer` that `AdcSdp` replaces |
| 3 ↔ 4 | `translateWebRtcOfferToSipOffer(…, appReqApp, appSlotUsed)` | Task 4 passes them at all four call sites; Task 3 parameter order = Task 4 call order |
| 3 ↔ 5 | mids 2/3/4 expected by the helper; `AdcSdp.merge` forces mids 3/4 | consistent |
| 4 → 7, 8 | `homeReqApp`, `appReqApp`, `appSlotUsed` | rename done by sed in Task 4 before 7/8 |
| 5 → 6, 7, 8 | `foregroundIsHome`, `foregroundAdcChannel`, `createAppADC`, `closeAppADC`, `homeAdcPC`, `appAdcPC` | consistent |
| 6 → 7, 8 | `setupAppADC` | produced in Task 6, used in 7 and 8 |
| 7 ↔ 9 | `onReInviteUnanswered` stub, `lastTarget`, `reInviteAnswer` | Task 9 replaces the stub and adds `lastTarget`; `sendChange` must set it (stated in Task 9) |
| 8 ↔ 9 | `CallActivity` edited by both | disjoint regions (handler vs callback wiring) |
| Each task | tests vs code vs files | Task 3's tests call the new signature with all positional args; `SdpHelperTlsIdTest` still passes `(sdp, ip)` |

## Risks (carried into the ledger)

1. **Nothing compiles here.** No JDK/Android SDK in the authoring environment; the first compile is the user's `./gradlew testDebugUnitTest assembleDebug`. Implementers must match imports and nullability by reading neighbouring code.
2. **`AdcSdp.merge` in an answer** forces mids to the remote offer's (`remoteHomeMid`/`remoteAppMid`); an old peer that numbers its ADC `5`, `6`… is answered with the same numbers.
3. **Two PeerConnections negotiated in one Re-INVITE** (Home + App when the user opens an app first): handled as two Re-INVITEs (Task 7 ruling); the network plan's slotted handler does not create Home and App in one offer.
4. **Home line port 0 from a legacy peer is ignored** (Home only closes at hang-up). A legacy "release ADC" Re-INVITE therefore no longer frees the Home ADC on this client.
5. **`isCaller` flag name** in Task 9 is unverified; the implementer must locate the real one.
6. **Races on the native DataChannel** during `closeAppADC` → `createAppADC` in the same coroutine rely on the existing 500 ms delayed `dispose()`.
