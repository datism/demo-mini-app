# BDC Survives Re-INVITE Implementation Plan (Plan 1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Re-INVITE no longer closes and recreates the Android client's BDC DataChannel, and every m-line carries an `a=tls-id` that only changes when its DTLS association does.

**Architecture:** `WebRtcManager.createOffer()` / `createAnswer()` currently call `createBDC(10)` unconditionally, closing the live BDC (whose observer was registered once by `DataChannelManager.setupBDC()`) and replacing it with an unobserved one. Replace that with `ensureBdcChannel()`, which creates stream 10 only if there is no usable stream-10 channel. In `SdpHelper`, derive each m-line's `tls-id` from its DTLS fingerprint instead of the clock, so an unchanged PeerConnection yields an identical line. This is build step 1 of the spec and ships on its own; the network (Plan 2) and the two-ADC client (Plan 3) follow.

**Tech Stack:** Kotlin (Android, minSdk 26, JVM 17), `io.github.webrtc-sdk:android:104.5112.09`, JUnit 4 for JVM unit tests, Gradle wrapper 9.3.0.

**Spec:** `docs/superpowers/specs/2026-10-31-background-emote-adc-design.md` (sections 3 "`webrtc/WebRtcManager.kt`", 3 "`sip/SdpHelper.kt`" tls-id bullet, 7 step 1)

## Global Constraints

- BDC is stream id **10**, `a=dcmap:10 subprotocol="http"`; this must not change.
- An unchanged m-line keeps the same port, ICE ufrag/pwd, fingerprint, `a=setup` and `a=tls-id` as in the previous offer/answer.
- `a=tls-id` value: RFC 8842 syntax, 20–255 chars from `[A-Za-z0-9+/\-_]`.
- No change to SIP signalling, the ADC flow, the MF/IMS-AS, or hotline behaviour in this plan.
- Android unit tests need JDK 17 and an Android SDK (`ANDROID_HOME` or `android-client/local.properties`). The cloud dev container has neither; run Gradle steps on a dev machine.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `android-client/app/build.gradle.kts` | Build config | Add JUnit 4 and `unitTests.isReturnDefaultValues = true` (so `android.util.Log` calls in `SdpHelper` are no-ops under JVM tests) |
| `android-client/app/src/main/java/com/demo/imsdc/sip/SdpHelper.kt` | WebRTC↔SIP SDP translation | Add `tlsIdFor(label, fingerprintLine)`; use it for BDC and ADC lines |
| `android-client/app/src/main/java/com/demo/imsdc/webrtc/WebRtcManager.kt` | PeerConnections and DataChannels | Add `BDC_STREAM_ID`, pure `needsNewBdcChannel(...)`, `ensureBdcChannel()`; call it from `createOffer()` / `createAnswer()` |
| `android-client/app/src/main/java/com/demo/imsdc/ui/CallActivity.kt` | Call lifecycle | Replace `createBDC(0)` (caller) and `createBDC(100)` (callee) with `ensureBdcChannel()` |
| `android-client/app/src/test/java/com/demo/imsdc/sip/SdpHelperTlsIdTest.kt` | New | tls-id tests |
| `android-client/app/src/test/java/com/demo/imsdc/webrtc/BdcChannelPolicyTest.kt` | New | `needsNewBdcChannel` tests |

---

### Task 1: JVM unit-test setup + stable per-m-line `tls-id`

**Files:**
- Modify: `android-client/app/build.gradle.kts` (`android { … }` block and `dependencies { … }`)
- Modify: `android-client/app/src/main/java/com/demo/imsdc/sip/SdpHelper.kt:21` (add function), `:234`, `:259`
- Test: `android-client/app/src/test/java/com/demo/imsdc/sip/SdpHelperTlsIdTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `SdpHelper.tlsIdFor(label: String, fingerprintLine: String): String` — 64 lowercase hex chars, deterministic in `(label, fingerprintLine)`.

- [ ] **Step 1: Enable JVM unit tests**

In `android-client/app/build.gradle.kts`, inside `android { … }` after the `buildFeatures { … }` block, add:

```kotlin
    testOptions {
        // SdpHelper logs through android.util.Log; return defaults instead of throwing under JVM tests.
        unitTests.isReturnDefaultValues = true
    }
```

In `dependencies { … }`, after the `coreLibraryDesugaring(...)` line, add:

```kotlin

    // ─── Unit tests (JVM) ────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Write the failing test**

Create `android-client/app/src/test/java/com/demo/imsdc/sip/SdpHelperTlsIdTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Run the test to verify it fails**

Run (from `android-client/`): `./gradlew :app:testDebugUnitTest --tests com.demo.imsdc.sip.SdpHelperTlsIdTest`
Expected: compilation FAIL — `Unresolved reference: tlsIdFor`.

- [ ] **Step 4: Implement `tlsIdFor` and use it**

In `SdpHelper.kt`, directly after `object SdpHelper {` (line 21), add:

```kotlin

    /**
     * a=tls-id for an m-line (RFC 8842). It must change only when the line's DTLS association does, so
     * derive it from the line's certificate fingerprint: the same PeerConnection gives the same id on
     * every Re-INVITE, a new PeerConnection (new certificate) gives a new one.
     */
    fun tlsIdFor(label: String, fingerprintLine: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$label|$fingerprintLine".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
```

(`java.security.MessageDigest` is already imported at the top of the file.)

Replace line 234:

```kotlin
    newLines.add("a=tls-id:tlsid${System.currentTimeMillis()}bdc")
```

with:

```kotlin
    newLines.add("a=tls-id:${tlsIdFor("bdc", bdcCreds.fingerprint)}")
```

Replace line 259:

```kotlin
    newLines.add("a=tls-id:tlsid${System.currentTimeMillis()}adc")
```

with:

```kotlin
    newLines.add("a=tls-id:${tlsIdFor("adc", adcCreds.fingerprint)}")
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests com.demo.imsdc.sip.SdpHelperTlsIdTest`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add android-client/app/build.gradle.kts \
        android-client/app/src/main/java/com/demo/imsdc/sip/SdpHelper.kt \
        android-client/app/src/test/java/com/demo/imsdc/sip/SdpHelperTlsIdTest.kt
git commit -m "fix(android): keep each m-line's tls-id stable across Re-INVITEs"
```

---

### Task 2: Keep the BDC channel across Re-INVITEs

**Files:**
- Modify: `android-client/app/src/main/java/com/demo/imsdc/webrtc/WebRtcManager.kt:366` (`createOffer`), `:568` (`createAnswer`), `:730-746` (next to `createBDC`)
- Modify: `android-client/app/src/main/java/com/demo/imsdc/ui/CallActivity.kt:1277`, `:1393`
- Test: `android-client/app/src/test/java/com/demo/imsdc/webrtc/BdcChannelPolicyTest.kt`

**Interfaces:**
- Consumes: the JUnit setup from Task 1.
- Produces:
  - `WebRtcManager.BDC_STREAM_ID: Int = 10` (companion `const val`)
  - `WebRtcManager.needsNewBdcChannel(existingId: Int?, isClosed: Boolean): Boolean` (companion, pure)
  - `WebRtcManager.ensureBdcChannel(): DataChannel?` (instance)

- [ ] **Step 1: Write the failing test**

Create `android-client/app/src/test/java/com/demo/imsdc/webrtc/BdcChannelPolicyTest.kt`:

```kotlin
package com.demo.imsdc.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BdcChannelPolicyTest {

    @Test
    fun createsWhenThereIsNoChannel() {
        assertTrue(WebRtcManager.needsNewBdcChannel(existingId = null, isClosed = false))
    }

    @Test
    fun keepsAnOpenStream10Channel() {
        assertFalse(WebRtcManager.needsNewBdcChannel(existingId = WebRtcManager.BDC_STREAM_ID, isClosed = false))
    }

    @Test
    fun replacesAClosedChannel() {
        assertTrue(WebRtcManager.needsNewBdcChannel(existingId = WebRtcManager.BDC_STREAM_ID, isClosed = true))
    }

    @Test
    fun replacesAChannelOnTheWrongStream() {
        assertTrue(WebRtcManager.needsNewBdcChannel(existingId = 0, isClosed = false))
        assertTrue(WebRtcManager.needsNewBdcChannel(existingId = 100, isClosed = false))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests com.demo.imsdc.webrtc.BdcChannelPolicyTest`
Expected: compilation FAIL — `Unresolved reference: needsNewBdcChannel` / `BDC_STREAM_ID`.

- [ ] **Step 3: Implement the policy and `ensureBdcChannel()`**

`WebRtcManager` has no `companion object` yet (`TAG` is a file-level `private const val` at line 12, `android.util.Log` is imported at line 6). Add this block just after the `createBDC` function (ends at line 746):

```kotlin

    companion object {
        /** BDC stream id, announced as a=dcmap:10 in every offer/answer. */
        const val BDC_STREAM_ID = 10

        /** A new BDC is needed only when there is no usable stream-10 channel; an open one is never replaced. */
        fun needsNewBdcChannel(existingId: Int?, isClosed: Boolean): Boolean =
            existingId != BDC_STREAM_ID || isClosed
    }
```

Then add this instance function directly after `createBDC`, before the companion block:

```kotlin

    /**
     * Returns the BDC, creating it only when needed. A Re-INVITE must not replace it: the channel's observer
     * (DataChannelManager.setupBDC) is registered once per call, and a replacement channel would have none.
     */
    fun ensureBdcChannel(): DataChannel? {
        val existing = bdcChannel
        val isClosed = existing?.state() == DataChannel.State.CLOSED
        if (!needsNewBdcChannel(existing?.id(), isClosed)) return existing
        if (existing != null) Log.w(TAG, "Replacing BDC (id=${existing.id()}, closed=$isClosed)")
        return createBDC(BDC_STREAM_ID)
    }
```

In `createOffer()` replace line 366:

```kotlin
        createBDC(10)
```

with:

```kotlin
        ensureBdcChannel()
```

In `createAnswer()` replace line 568 (`        createBDC(10)`) with `        ensureBdcChannel()` the same way.

- [ ] **Step 4: Make call setup create stream 10 directly**

The caller and callee currently create a throwaway BDC on stream 0 / 100 that the first `createOffer()` / `createAnswer()` replaced with stream 10. `ensureBdcChannel()` would also replace it (wrong stream), but say what is meant.

In `CallActivity.kt` replace line 1277:

```kotlin
                webRtcManager.createBDC(0)
```

with:

```kotlin
                webRtcManager.ensureBdcChannel()
```

and line 1393:

```kotlin
                webRtcManager.createBDC(100)
```

with:

```kotlin
                webRtcManager.ensureBdcChannel()
```

Leave the `// 3.` / `// 2.` comments above them as they are.

- [ ] **Step 5: Run the unit tests and build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: all tests PASS (Task 1's 5 + these 4), APK builds.

- [ ] **Step 6: Commit**

```bash
git add android-client/app/src/main/java/com/demo/imsdc/webrtc/WebRtcManager.kt \
        android-client/app/src/main/java/com/demo/imsdc/ui/CallActivity.kt \
        android-client/app/src/test/java/com/demo/imsdc/webrtc/BdcChannelPolicyTest.kt
git commit -m "fix(android): keep the BDC channel open across Re-INVITEs"
```

---

### Task 3: Verify on two phones

No code. Confirms the fix end to end; record the results in the PR description.

**Files:** none.

- [ ] **Step 1: Install the debug APK on both phones**

Run: `./gradlew :app:installDebug` with each phone connected (or `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`).

- [ ] **Step 2: Run the flow and capture logs**

On the caller: `adb -s <caller> logcat -c && adb -s <caller> logcat -v time > caller.log`
Then: call → emote home loads → open Caro → back → open photo-share → back → hang up.

- [ ] **Step 3: Check the logs**

```bash
grep -c "BDC created: id=10" caller.log            # expect 1
grep -c "Replacing BDC" caller.log                 # expect 0
grep "BDC (id=10) closed" caller.log               # expect only at hang-up
grep "a=tls-id:" caller.log | grep -c tlsid        # expect 0 (no clock-based ids left)
```

- [ ] **Step 4: Check a fresh download over the BDC mid-call**

Clear one app from the caller's cache (`adb -s <caller> shell run-as com.demo.imsdc rm -rf cache/miniapps/caro`), repeat call → emotes → Caro, and confirm the log shows `BDC (id=10) received message` for the Caro package after the emote Re-INVITE. Before this fix that reply had no observer.
