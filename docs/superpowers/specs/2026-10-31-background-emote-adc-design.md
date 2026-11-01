# Background Emote ADC — Design

- **Date:** 2026-10-31
- **Status:** Approved in brainstorming, pending spec review
- **Goal:** The emote app (`fun-calling-app`, the in-call home) keeps its ADC open for the whole call. Opening, leaving or switching other mini-apps costs exactly one Re-INVITE and never closes or reopens the emote ADC or the BDC.

## 1. Scope

### In scope
1. **BDC fix:** a Re-INVITE no longer recreates the local BDC DataChannel; the channel created at call start (and its observer) lives for the whole call.
2. **Stable SDP for unchanged lines:** one `a=tls-id` per m-line for the whole call (today a new one is generated on every offer, which signals a new DTLS association per RFC 8842).
3. **Two ADC slots:** a permanent *home* ADC (emotes) and a replaceable *app* ADC (Caro, photo-share, whiteboard, …), each its own m-line, PeerConnection and MF context.
4. **One Re-INVITE per app change:** no more release-to-`operator-menu` step and no pre-Re-INVITE download wait.

### Out of scope
- Showing emotes while another app is in the foreground (no overlay, no emote button over Caro). Home ADC messages that arrive while another app is in front are logged and dropped.
- Carrying the emote ADC in the initial INVITE: it is still added by one Re-INVITE right after answer.
- Hotline (198 / 1900) behaviour changes.
- A second WebView.

## 2. SDP layout and call flow

### m-line order (all offers and answers)

| mid | m-line | Lifetime |
|---|---|---|
| 0 | `m=audio` | whole call |
| 1 | `m=video` | whole call |
| 2 | BDC (`a=dcmap:10 subprotocol="http"`) | whole call, sent unchanged |
| 3 | **Home ADC**: `a=3gpp-req-app:"fun-calling-app";1000-UE`, streams 1000 + 1001 | from Re-INVITE #1 to hang-up, sent unchanged after that |
| 4 | **App ADC**: `a=3gpp-req-app:"<appId>";1000-UE`, stream 1000 | added on the first app open; port 0 while no app is open |

### Flow

| Step | Re-INVITEs | SDP change |
|---|---|---|
| Call connects (INVITE / 200 OK) | – | mid 0–2 |
| Caller opens emote home | 1 | adds mid 3 |
| Open Caro | 1 | adds mid 4 = caro |
| Back to emotes | 1 | mid 4 → port 0 |
| Open photo-share | 1 | mid 4 reused: new port, ICE/DTLS credentials, `tls-id`, req-app |
| Caro → photo-share directly | 1 | mid 4 replaced in place |
| Hang up | – | BYE |

### Rules
- Once mid 4 exists it stays in every later offer (port 0 when unused); m-lines are never removed (RFC 3264 §8).
- An unchanged m-line keeps the same port, ICE ufrag/pwd, fingerprint, `a=setup` and `a=tls-id` as in the previous offer/answer.
- Either side may send the Re-INVITE; the answerer mirrors the m-lines and opens the requested app with `sendRemoteTrigger = false` (as today).
- **Hotline calls:** the hotline app occupies mid 3 (home slot); mid 4 is never used. Behaviour unchanged.
- **Backward compatibility:** a single-ADC offer (old client) maps onto the home slot on the network side.

## 3. Android client

### `webrtc/WebRtcManager.kt`
- `adcPC` → `homeAdcPC` (streams 1000 `adcChannel`, 1001 `adc2Channel`). Created by Re-INVITE #1, closed only on hang-up.
- New `appAdcPC` with stream 1000 (`appAdcChannel`). `closeAppADC()` closes only this one; the existing tear-everything `closeADC()` is no longer used for app changes.
- `createOffer()` / `createAnswer()`: call `createBDC(10)` only when `bdcChannel == null` (today it is called unconditionally at `:366` and `:568`, closing the live channel).
- Each PeerConnection reuses its local description when STABLE and its m-line is unchanged (already done for the BDC; same for home). Only PCs whose m-line changes produce a new offer/answer.

### `sip/SdpHelper.kt`
- Parse application blocks by `a=mid`, not by position (`appBlocks.getOrNull(0/1)` today).
- `translateWebRtcOfferToSipOffer` emits up to three application m-lines (mid 2, 3, 4; mid 4 as port 0 when no app is open).
- `a=tls-id` is one per DTLS association: stable while the PeerConnection lives, new with a new PeerConnection (derived from the certificate fingerprint) (today `tlsid${System.currentTimeMillis()}` per offer).
- `translateSipAnswerToWebRtcAnswer` splits the answer into BDC / home / app parts for their PCs.

### `ui/MiniAppManager.kt`
- `sipManager.activeReqAppId` → `homeReqApp` (set once) and `appReqApp` (`null` when no app open).
- `triggerAppNegotiationReInvite(app)` sends exactly one Re-INVITE. Removed: the release-to-`operator-menu` pre-step (`:572-578`) and `awaitAppDownload` (`:559-568`).
- Back / Exit to emotes: one Re-INVITE (mid 4 → port 0), then reload the emote page; the home ADC is untouched.
- When the emote page calls `createAppDataChannel`, it binds to the already-open home ADC. `onJsRequestedAdc` (`CallActivity.kt:1179`) sends a Re-INVITE only if the home ADC does not exist yet.
- Request queue: if an app change is requested while a Re-INVITE is in flight, keep only the latest request and send it once the current transaction completes.

### `ui/CallActivity.kt` — incoming Re-INVITE (`onReInviteReceived`, `:1014`)
- Diff m-lines by mid against the current state; rebuild only `appAdcPC` if mid 4 changed; launch the app mid 4 now names, or return to emotes on port 0.

### Message routing
- One WebView shows the foreground app.
- Home ADC → page only while emotes is in front; otherwise log and drop.
- Page → the ADC owned by the foreground app (home for emotes, app ADC for everything else).

## 4. Network (`go-mf`)

### IMS-AS (`internal/imsas/`)
- `parseReqApp` (`b2bua.go:868`) returns a list of `{mid, reqAppId, appDcInfo, port}` for every ADC m-line.
- `CallSession`: replace `ActiveAppID` / `IsAnchorCtxCreated` / `IsADCReleasing` with two slots, `Home` and `App`, each `{appId, ctxCreated}`.
- `handleReInvite` (`b2bua_invite.go:201`) diffs per slot:
  - unchanged → no action for that slot;
  - new or different app → DCSF bind + create MF context for the slot;
  - port 0 → delete the slot's MF context;
  - forward the Re-INVITE to the peer. A single Re-INVITE may delete and recreate the App slot.
- `RewriteSDP` / `RewriteSDPForADC` (`sdp.go:66`, `:291`) rewrite each ADC m-line with its slot's MF address, credentials and a stable `tls-id` (`getTLSId(<callID>-home)` / `getTLSId(<callID>-app)`); unchanged lines are byte-identical to the previous rewrite. `ExcludeADCFromSDP` handles all ADC lines.
- `hotlineReInviteAnswer` (`b2bua_invite.go:1039`) uses the Home slot only.
- Hang-up deletes both contexts (`b2bua.go:534`, `:639`).

### DCSF (`internal/dcsf/`)
- Per-request logic unchanged (one app per bind). The IMS-AS issues one bind per changed slot and passes the slot so the MF context is named accordingly.

### MF (`internal/mf/`)
- ADC contexts `<callID>-home` and `<callID>-app`, each with its own PeerConnection and `SessionInfo`, created and deleted independently.
- Replace `strings.HasSuffix(callID, "-adc")` (`media_function.go:467`, `:732`, `:1678`; `proxy_handler.go:236`; `sba_handler.go:449`) with an `isAdcContext()` helper accepting both suffixes plus a parent-call lookup.
- `LinkSessions` pairs A-home↔B-home and A-app↔B-app; no cross-slot relay.
- Both slots share the existing ADC UDP mux (port 40005); ICE ufrag demultiplexes.

### UE simulator (`internal/uesim/`)
- `sip_manager.go` / `webrtc_manager.go` mirror the client: three application m-lines, unchanged lines kept stable, so `dctest/` scenarios can drive the new flow without phones.

## 5. Error handling

| Case | Behaviour |
|---|---|
| Re-INVITE 4xx/5xx or no 200 OK within 5 s (timeout only, normal answer is sub-second) | Roll back the App slot only: close `appAdcPC`, mid 4 → port 0 locally, toast, show emotes. Home ADC and BDC untouched. |
| Re-INVITE glare (491) | Retry after random back-off (RFC 3261 §14.1). |
| Home ADC fails (DTLS/SCTP) | Log and rebuild the Home slot with one Re-INVITE — the only case emotes renegotiates. |
| BDC fails | Real transport loss; the existing pending-download resend works because the observer stays attached. |
| App change requested while a Re-INVITE is in flight | Keep the latest request only; send one Re-INVITE when the current transaction completes. |

## 6. Testing

- **Go unit (`imsas/`):** `parseReqApp` with multiple ADC lines; per-slot diff in `handleReInvite` (unchanged / new / switch / port 0); `RewriteSDP` keeps unchanged lines byte-identical incl. `tls-id`; hotline answer unchanged (extend `hotline_answer_test.go`).
- **Go MF:** both slot contexts coexist and delete independently; `LinkSessions` pairs by slot.
- **Scenario (`uesim` / `dctest`):** call → emotes → Caro → back → photo-share → Caro → photo-share → hang up. Assert one Re-INVITE per step; the home ADC never closes and messages flow before and after each step; the BDC PC and channel never close.
- **Android:** JVM unit tests for `SdpHelper` (mid-based parsing, three application lines, stable `tls-id`). Device run with the existing scripts (`test_fun_calling_*`, `test_caro_*`) plus log checks: one `BDC created` per call, no home-ADC close before hang-up, one Re-INVITE per app change.

## 7. Build order

1. **BDC fix (client only):** conditional `createBDC(10)` and stable per-m-line `tls-id`. Shippable on its own.
2. **Network two slots:** IMS-AS, MF, `uesim`, with Go and scenario tests. Old single-ADC clients keep working via the Home slot.
3. **Client two ADC PCs:** `WebRtcManager`, mid-based `SdpHelper`, single-Re-INVITE `MiniAppManager`, message routing.
4. **Cleanup:** remove the `operator-menu` release path, `IsADCReleasing`, `awaitAppDownload` / `APP_DOWNLOAD_WAIT_MS` and the chained `REINVITE_ANSWER_WAIT_MS` wait.
