# Network Two ADC Slots Implementation Plan (Plan 2 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The IMS-AS and MF carry two independent ADC slots per call, a permanent *Home* slot (emotes, mid 3) and a replaceable *App* slot (mid 4), so an app change touches only the App slot and the Home ADC and BDC are never torn down.

**Architecture:** Today the IMS-AS keeps one ADC (`ActiveAppID`, `IsAnchorCtxCreated`, `IsADCReleasing`) and the MF holds one `AdcPC` per session. Replace that with two slots keyed `home` / `app`: the IMS-AS diffs each slot on every Re-INVITE; the MF names the ADC contexts `<callID>-home` and `<callID>-app`, each with its own AdcPC, paired A↔B by slot. A single-ADC offer from an old client maps onto the Home slot. This is build step 2 of the spec; Plan 1 (BDC) is done, Plan 3 (client) follows.

**Tech Stack:** Go 1.27 (`/home/tiu/go-toolchain/go/bin/go`, on PATH), pion/webrtc in the MF, `net/http/httptest` for fakes.

**Spec:** `docs/superpowers/specs/2026-10-31-background-emote-adc-design.md` (sections 2, 4, 5, 6, 7 step 2)

## Global Constraints

- m-line layout in every offer/answer: mid 0 audio, mid 1 video, mid 2 BDC, mid 3 Home ADC (`a=3gpp-req-app:"fun-calling-app";1000-UE`, streams 1000 + 1001), mid 4 App ADC (stream 1000, port 0 while no app).
- m-lines are never removed (RFC 3264 §8); an unused App slot stays as port 0.
- An unchanged m-line keeps the same port, ICE ufrag/pwd, fingerprint, `a=setup` and `a=tls-id` as in the previous rewrite (byte-identical).
- **Ruling (deviates from spec §4 naming, for minimal risk):** the Home ADC keeps living on the *existing parent MF context* (`<callID>`, the "context already exists" branch of `CreateMediaContext`, which adds an AdcPC to the parent sessions). Only the App slot is a new MF context `<callID>-app`, built on *shadow sessions* (carrying just the ADC fields; `CallID = <callID>-app`) so every existing ADC code path (`handleADC`, P2P relay by `CallID`, `initSessionAdcPC`) works on them unchanged. The legacy `-adc` suffix is dead code (the IMS-AS never creates it) and is folded into one `isAdcContext()` helper.
- Hotline calls (198 / 1900) use the Home slot only; mid 4 is never used; behaviour unchanged.
- An old single-ADC offer (exactly two `m=application` lines: BDC + one ADC) maps onto the Home slot.
- `go build ./... && go test ./...` in `go-mf/` must pass after every task.
- The `operator-menu` release path and `IsADCReleasing` stay working until the final cleanup task.
- No phones in this environment: end-to-end behaviour is covered by Go tests with fake MF/DCSF HTTP servers.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `go-mf/internal/imsas/adc_slots.go` | New | `AdcSlot` type, `adcLine`, `parseAdcLines`, per-slot state |
| `go-mf/internal/imsas/sdp.go` | SDP rewrite | Stable `getTLSId`, per-slot `RewriteSDP`, multi-line `ExcludeADCFromSDP` |
| `go-mf/internal/imsas/b2bua.go` | Session model, teardown | Two slots on `CallSession`; delete both contexts |
| `go-mf/internal/imsas/b2bua_invite.go` | Re-INVITE handling | Per-slot diff in `handleReInvite` |
| `go-mf/internal/imsas/b2bua_response.go` | 200 OK handling | Per-slot context patch / release |
| `go-mf/internal/mf/adc_context.go` | New | `isAdcContext`, `appParentID`, `newShadowSession` |
| `go-mf/internal/mf/media_function.go`, `sba_handler.go`, `proxy_handler.go` | MF | Per-slot AdcPC, `LinkSessions` by slot |
| `go-mf/internal/uesim/*.go` | UE simulator | Three application m-lines |
| `*_test.go` beside each | New | Tests per task |

## Task list

1. IMS-AS: slot model and `parseAdcLines` (pure)
2. IMS-AS: stable `getTLSId`, per-slot `RewriteSDP`, multi-line `ExcludeADCFromSDP` (pure)
3. MF: `isAdcContext` helper replaces every `HasSuffix(..., "-adc")`; slot-aware `splitSDPAnswerN`
4. MF: extract ADC transport setup, then `<callID>-app` context on shadow sessions with slot-paired `LinkSessions`
5. IMS-AS: slot state on `CallSession`, `slotMFFromContext`, `withSlots` (pure helpers)
6. IMS-AS: slotted Re-INVITE handler and 200 OK path for the App slot, per-slot teardown
7. UE simulator: three application m-lines
8. Scenario test with fake MF/DCSF
9. Cleanup: remove the `operator-menu` release path and `IsADCReleasing`

---

### Task 1: Slot model and `parseAdcLines` (pure)

**Files:**
- Create: `go-mf/internal/imsas/adc_slots.go`
- Test: `go-mf/internal/imsas/adc_slots_test.go`

**Interfaces:**
- Consumes: `sba.AppBindingInfo{ReqAppId, AppDcInfo string}` (existing, `internal/sba`).
- Produces (used by Tasks 2, 5, 7):
  - `type AdcSlot string` with `SlotHome AdcSlot = "home"`, `SlotApp AdcSlot = "app"`.
  - `type adcLine struct { Slot AdcSlot; Port int; Binding *sba.AppBindingInfo }` — `Binding` is nil when the m-line has no `a=3gpp-req-app`.
  - `func parseAdcLines(sdp string) []adcLine` — application m-lines in order, the first (BDC) skipped; line 1 → `SlotHome`, line 2 → `SlotApp`. A BDC-stream (<1000) `a=3gpp-req-app` is ignored, as `parseReqApp` does today.
  - `func (l adcLine) Active() bool` — `Port != 0`.
  - `func adcLineFor(lines []adcLine, s AdcSlot) *adcLine`.
  - `func slotSuffix(s AdcSlot) string` — `"-home"` / `"-app"`.

- [ ] **Step 1: Write the failing test**

```go
package imsas

import "testing"

const sdpBase = "v=0\r\no=- 1 1 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\n" +
	"m=audio 9 UDP/TLS/RTP/SAVPF 8\r\nc=IN IP4 1.1.1.1\r\n" +
	"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=dcmap:10 subprotocol=\"http\"\r\n"

func TestParseAdcLines_LegacySingleAdcMapsToHome(t *testing.T) {
	sdp := sdpBase + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=3gpp-req-app:\"caro\";1000-UE\r\n"
	got := parseAdcLines(sdp)
	if len(got) != 1 || got[0].Slot != SlotHome || got[0].Binding == nil || got[0].Binding.ReqAppId != "caro" {
		t.Fatalf("got %+v", got)
	}
}

func TestParseAdcLines_HomeAndApp(t *testing.T) {
	sdp := sdpBase +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"fun-calling-app\";1000-UE\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"caro\";1000-UE\r\n"
	got := parseAdcLines(sdp)
	if len(got) != 2 || got[0].Slot != SlotHome || got[1].Slot != SlotApp {
		t.Fatalf("got %+v", got)
	}
	if got[0].Binding.ReqAppId != "fun-calling-app" || got[1].Binding.ReqAppId != "caro" {
		t.Fatalf("apps: %+v %+v", got[0].Binding, got[1].Binding)
	}
}

func TestParseAdcLines_AppPortZeroIsInactive(t *testing.T) {
	sdp := sdpBase +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"fun-calling-app\";1000-UE\r\n" +
		"m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\n"
	got := parseAdcLines(sdp)
	if len(got) != 2 || !got[0].Active() || got[1].Active() || got[1].Binding != nil {
		t.Fatalf("got %+v", got)
	}
	if adcLineFor(got, SlotApp) == nil || adcLineFor(got, SlotHome) == nil {
		t.Fatal("adcLineFor must find both")
	}
}

func TestParseAdcLines_NoAdc(t *testing.T) {
	if got := parseAdcLines(sdpBase); len(got) != 0 {
		t.Fatalf("got %+v", got)
	}
}

func TestParseAdcLines_IgnoresBdcStreamReqApp(t *testing.T) {
	sdp := sdpBase + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"x\";10-UE\r\n"
	got := parseAdcLines(sdp)
	if len(got) != 1 || got[0].Binding != nil {
		t.Fatalf("got %+v", got)
	}
}

func TestSlotSuffix(t *testing.T) {
	if slotSuffix(SlotHome) != "-home" || slotSuffix(SlotApp) != "-app" {
		t.Fatal("suffix")
	}
}
```

- [ ] **Step 2: Run it, expect FAIL** (`undefined: parseAdcLines`)

Run: `cd go-mf && go test ./internal/imsas/ -run 'AdcLines|SlotSuffix' -v`

- [ ] **Step 3: Implement**

```go
package imsas

import (
	"strconv"
	"strings"

	"github.com/demo/ims-dc-mf/internal/sba"
)

type AdcSlot string

const (
	SlotHome AdcSlot = "home"
	SlotApp  AdcSlot = "app"
)

type adcLine struct {
	Slot    AdcSlot
	Port    int
	Binding *sba.AppBindingInfo
}

func (l adcLine) Active() bool { return l.Port != 0 }

func slotSuffix(s AdcSlot) string { return "-" + string(s) }

func adcLineFor(lines []adcLine, s AdcSlot) *adcLine {
	for i := range lines {
		if lines[i].Slot == s {
			return &lines[i]
		}
	}
	return nil
}

// parseAdcLines returns the ADC m-lines of an SDP. The first m=application is
// the BDC and is skipped; the second is the Home slot, the third the App slot.
func parseAdcLines(sdp string) []adcLine {
	var out []adcLine
	appIdx := -1
	for _, raw := range strings.Split(sdp, "\n") {
		line := strings.TrimSpace(raw)
		if strings.HasPrefix(line, "m=") {
			if !strings.HasPrefix(line, "m=application ") {
				appIdx = -2 // inside a non-application section: ignore attributes
				continue
			}
			if appIdx == -2 {
				appIdx = -1
			}
			appIdx++
			if appIdx == 0 {
				appIdx = 0
				continue
			}
			l := adcLine{}
			switch appIdx {
			case 1:
				l.Slot = SlotHome
			case 2:
				l.Slot = SlotApp
			default:
				appIdx = 3
				continue
			}
			if f := strings.Fields(line); len(f) >= 2 {
				l.Port, _ = strconv.Atoi(f[1])
			}
			out = append(out, l)
			continue
		}
		if appIdx >= 1 && appIdx <= 2 && len(out) > 0 && strings.HasPrefix(line, "a=3gpp-req-app:") {
			if b := parseReqApp(line); b != nil {
				out[len(out)-1].Binding = b
			}
		}
	}
	return out
}
```

Note: `appIdx` starts at -1 so the first `m=application` increments it to 0 (BDC). The `-2` guard stops a later `m=audio`/`m=video` section from attributing its attributes to an ADC line. If the compiler flags the redundant `appIdx = 0`, remove it.

- [ ] **Step 4: Run, expect PASS**

Run: `cd go-mf && go build ./... && go test ./internal/imsas/ -v -run 'AdcLines|SlotSuffix'`

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/imsas/adc_slots.go go-mf/internal/imsas/adc_slots_test.go
git commit -m "feat(imsas): parse Home/App ADC m-lines by position

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Stable `getTLSId`, per-slot `RewriteSDP`, multi-line `ExcludeADCFromSDP` (pure)

**Files:**
- Modify: `go-mf/internal/imsas/sdp.go` (`getTLSId` `:~229`, `RewriteSDP` `:66`, `ExcludeADCFromSDP` `:~245`, `MFAllocResponse` `:11`)
- Test: `go-mf/internal/imsas/sdp_slots_test.go`

**Interfaces:**
- Consumes: `AdcSlot`, `parseAdcLines` (Task 1).
- Produces (used by Task 5):
  - `getTLSId(key string) string` — deterministic: first 32 hex chars of `sha256(key)`; same key → same value.
  - `MFAllocResponse` gains `Slots map[AdcSlot]*SlotMF` where `type SlotMF struct { Port int; IceUfrag, IcePwd, Fingerprint, Setup string }`.
  - `RewriteSDP(origSDP string, mfInfo *MFAllocResponse, activeAppId string) string` — **signature unchanged** (callers outside Task 5 keep compiling). Behaviour: the 1st `m=application` is the BDC (unchanged). The 2nd is rewritten from `mfInfo.Slots[SlotHome]` if `Slots` is non-nil, else from the legacy `Adc*` fields (old behaviour). The 3rd is rewritten from `mfInfo.Slots[SlotApp]`; if its port in the input is 0 it is emitted as `m=application 0 UDP/DTLS/SCTP webrtc-datachannel` with only the attributes `ExcludeADCFromSDP` keeps. `a=tls-id` for slot X is `getTLSId(mfInfo.SessionID + "-" + string(X))` (legacy: `-adc`).
  - `ExcludeADCFromSDP(sdp string) string` — unchanged for 1 or 2 application lines; with 3 it disables only the 3rd (App) line and leaves Home alone (the "exclude" operation is only ever used for the App slot / P2A; for old single-ADC offers it keeps today's behaviour).

- [ ] **Step 1: Write the failing tests**

```go
package imsas

import (
	"strings"
	"testing"
)

func threeAppSDP(appPort string) string {
	return "v=0\r\no=- 1 1 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\n" +
		"m=audio 9 UDP/TLS/RTP/SAVPF 8\r\nc=IN IP4 1.1.1.1\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=dcmap:10 subprotocol=\"http\"\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=3gpp-req-app:\"fun-calling-app\";1000-UE\r\n" +
		"m=application " + appPort + " UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=3gpp-req-app:\"caro\";1000-UE\r\n"
}

func testMf() *MFAllocResponse {
	return &MFAllocResponse{
		SessionID: "call-A", IP: "10.0.0.1", Port: 40000,
		IceUfrag: "bu", IcePwd: "bp", DtlsFingerprint: "sha-256 BB", SetupRole: "passive",
		Slots: map[AdcSlot]*SlotMF{
			SlotHome: {Port: 40001, IceUfrag: "hu", IcePwd: "hp", Fingerprint: "sha-256 HH", Setup: "actpass"},
			SlotApp:  {Port: 40002, IceUfrag: "au", IcePwd: "ap", Fingerprint: "sha-256 AA", Setup: "actpass"},
		},
	}
}

func TestGetTLSId_Deterministic(t *testing.T) {
	if getTLSId("x-home") != getTLSId("x-home") {
		t.Fatal("must be stable")
	}
	if getTLSId("x-home") == getTLSId("x-app") {
		t.Fatal("different keys must differ")
	}
	if n := len(getTLSId("x")); n < 20 {
		t.Fatalf("tls-id too short: %d", n)
	}
}

func TestRewriteSDP_UnchangedLinesAreByteIdentical(t *testing.T) {
	first := RewriteSDP(threeAppSDP("9"), testMf(), "caro")
	second := RewriteSDP(threeAppSDP("9"), testMf(), "caro")
	if first != second {
		t.Fatalf("two rewrites of the same input differ:\n%s\n---\n%s", first, second)
	}
}

func TestRewriteSDP_SlotsUseOwnMfEndpoints(t *testing.T) {
	out := RewriteSDP(threeAppSDP("9"), testMf(), "caro")
	for _, want := range []string{
		"m=application 40001 ", "a=ice-ufrag:hu", "a=fingerprint:sha-256 HH",
		"m=application 40002 ", "a=ice-ufrag:au", "a=fingerprint:sha-256 AA",
		"a=3gpp-req-app:\"caro\";1000-UE",
	} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in:\n%s", want, out)
		}
	}
	if !strings.Contains(out, "a=tls-id:"+getTLSId("call-A-home")) || !strings.Contains(out, "a=tls-id:"+getTLSId("call-A-app")) {
		t.Fatalf("tls-ids not per slot:\n%s", out)
	}
}

func TestRewriteSDP_AppPortZeroStaysZero(t *testing.T) {
	out := RewriteSDP(threeAppSDP("0"), testMf(), "")
	if !strings.Contains(out, "m=application 0 UDP/DTLS/SCTP") {
		t.Fatalf("App slot must stay port 0:\n%s", out)
	}
	if strings.Contains(out, "m=application 40002") {
		t.Fatalf("inactive App slot must not get an MF port:\n%s", out)
	}
}

func TestRewriteSDP_LegacyFieldsStillWork(t *testing.T) {
	legacy := &MFAllocResponse{SessionID: "c-A", IP: "10.0.0.1", Port: 40000, AdcPort: 40009,
		AdcIceUfrag: "u", AdcIcePwd: "p", AdcDtlsFingerprint: "sha-256 LL", AdcSetupRole: "actpass"}
	sdp := "v=0\r\no=- 1 1 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
	out := RewriteSDP(sdp, legacy, "caro")
	if !strings.Contains(out, "m=application 40009 ") || !strings.Contains(out, "a=tls-id:"+getTLSId("c-A-adc")) {
		t.Fatalf("legacy path broken:\n%s", out)
	}
}

func TestExcludeADC_ThreeLinesDisablesOnlyApp(t *testing.T) {
	out := ExcludeADCFromSDP(threeAppSDP("9"))
	lines := parseAdcLines(out)
	if len(lines) != 2 || !lines[0].Active() || lines[1].Active() {
		t.Fatalf("got %+v\n%s", lines, out)
	}
}

func TestExcludeADC_LegacyOneAdcStillDisabled(t *testing.T) {
	sdp := "v=0\r\ns=-\r\nt=0 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"x\";1000-UE\r\n"
	out := ExcludeADCFromSDP(sdp)
	if l := parseAdcLines(out); len(l) != 1 || l[0].Active() {
		t.Fatalf("got %+v", l)
	}
}
```

- [ ] **Step 2: Run, expect FAIL** (`Slots`, `SlotMF` undefined)

Run: `cd go-mf && go test ./internal/imsas/ -run 'GetTLSId|RewriteSDP|ExcludeADC' -v`

- [ ] **Step 3: Implement**

In `sdp.go`:

1. Imports: replace `"crypto/rand"` with `"crypto/sha256"`; keep `"encoding/hex"`.
2. Add to `MFAllocResponse` (after `AdcSetupRole`):

```go
	Slots map[AdcSlot]*SlotMF `json:"-"`
```

and, below the struct:

```go
type SlotMF struct {
	Port        int
	IceUfrag    string
	IcePwd      string
	Fingerprint string
	Setup       string
}
```

3. Replace `getTLSId`:

```go
// getTLSId is deterministic: an unchanged m-line must keep the same a=tls-id
// across rewrites (RFC 8842: a new value means a new DTLS association).
func getTLSId(key string) string {
	sum := sha256.Sum256([]byte(key))
	return hex.EncodeToString(sum[:16])
}
```

4. In `RewriteSDP`, replace the `else { // ADC media block … }` branch (the whole branch after the BDC `if appCount == 1 {…}`) with:

```go
			} else {
				slot := SlotHome
				if appCount >= 3 {
					slot = SlotApp
				}
				sm := (*SlotMF)(nil)
				if mfInfo.Slots != nil {
					sm = mfInfo.Slots[slot]
				}
				origPort := 9
				if f := strings.Fields(trimmed); len(f) >= 2 {
					fmt.Sscanf(f[1], "%d", &origPort)
				}
				if slot == SlotApp && origPort == 0 {
					// Inactive App slot: keep the line, port 0, no MF endpoint.
					newLines = append(newLines, "m=application 0 UDP/DTLS/SCTP webrtc-datachannel")
					newLines = append(newLines, fmt.Sprintf("c=IN IP4 %s", externalIP))
					continue
				}
				var adcPort int
				var adcIceUfrag, adcIcePwd, adcFingerprint, role, tlsKey string
				if sm != nil {
					adcPort, adcIceUfrag, adcIcePwd, adcFingerprint, role = sm.Port, sm.IceUfrag, sm.IcePwd, sm.Fingerprint, sm.Setup
					tlsKey = mfInfo.SessionID + "-" + string(slot)
				} else {
					adcPort = mfInfo.AdcPort
					if adcPort == 0 {
						adcPort = mfInfo.Port + 100 // fallback
					}
					adcIceUfrag = mfInfo.AdcIceUfrag
					if adcIceUfrag == "" {
						adcIceUfrag = mfInfo.IceUfrag
					}
					adcIcePwd = mfInfo.AdcIcePwd
					if adcIcePwd == "" {
						adcIcePwd = mfInfo.IcePwd
					}
					adcFingerprint = mfInfo.AdcDtlsFingerprint
					if adcFingerprint == "" {
						adcFingerprint = mfInfo.DtlsFingerprint
					}
					role = adcSetupRole
					tlsKey = mfInfo.SessionID + "-adc"
				}
				newLines = append(newLines, fmt.Sprintf("m=application %d UDP/DTLS/SCTP webrtc-datachannel", adcPort))
				newLines = append(newLines, fmt.Sprintf("c=IN IP4 %s", externalIP))
				newLines = append(newLines, fmt.Sprintf("a=ice-ufrag:%s", adcIceUfrag))
				newLines = append(newLines, fmt.Sprintf("a=ice-pwd:%s", adcIcePwd))
				newLines = append(newLines, fmt.Sprintf("a=fingerprint:%s", adcFingerprint))
				newLines = append(newLines, fmt.Sprintf("a=setup:%s", role))
				newLines = append(newLines, "a=connection:new")
				newLines = append(newLines, "a=sctp-port:5000")
				newLines = append(newLines, fmt.Sprintf("a=candidate:1 1 UDP 2122260991 %s %d typ host", externalIP, adcPort))
				newLines = append(newLines, fmt.Sprintf("a=tls-id:%s", getTLSId(tlsKey)))
				appId := activeAppId
				if slot == SlotHome && sm != nil {
					appId = "fun-calling-app"
				}
				if appId != "" {
					endpointType := "UE"
					if appId == "enterprise-callerid" {
						endpointType = "Server"
					}
					newLines = append(newLines, fmt.Sprintf("a=dcmap:1000 subprotocol=\"%s\"", appId))
					newLines = append(newLines, fmt.Sprintf("a=3gpp-req-app:\"%s\";1000-%s", appId, endpointType))
				}
			}
```

   Also: because `shouldDiscardLine` removes `a=3gpp-req-app:` and `a=dcmap:` from the input, the Home line's req-app comes from the code above, and the Home streams 1000 + 1001 `a=dcmap` for emotes: add, inside the `if appId != ""` block, after the 1000 dcmap line:

```go
					if slot == SlotHome && sm != nil {
						newLines = append(newLines, "a=dcmap:1001 subprotocol=\"emoji\"")
					}
```

5. In `ExcludeADCFromSDP` change `shouldDisable` to:

```go
			shouldDisable := (totalApps == 1 && appCount == 1) || (totalApps == 2 && appCount == 2) || (totalApps >= 3 && appCount == 3)
```

   (old `totalApps > 1 && appCount == 2` becomes the `totalApps == 2` case, so the 2-line behaviour is unchanged).

- [ ] **Step 4: Run, expect PASS; full suite green**

Run: `cd go-mf && go build ./... && go test ./...`

If `TestRewriteSDP_UnchangedLinesAreByteIdentical` fails, something non-deterministic is still in the output (a timestamp or random value) — find and remove it. Note the `o=` line is rewritten from the input, so identical input gives identical output.

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/imsas/sdp.go go-mf/internal/imsas/sdp_slots_test.go
git commit -m "feat(imsas): per-slot SDP rewrite with stable tls-id

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: MF `isAdcContext` helper and slot-aware `splitSDPAnswerN`

**Files:**
- Create: `go-mf/internal/mf/adc_context.go`, `go-mf/internal/mf/adc_context_test.go`
- Modify: `go-mf/internal/mf/sdp_utils.go:251` (`splitSDPAnswer`), `media_function.go:467,732,1678`, `proxy_handler.go:236`, `sba_handler.go:449`
- Test: `go-mf/internal/mf/sdp_split_test.go`

**Interfaces:**
- Produces (used by Task 4):
  - `func isAdcContext(id string) bool` — true for ids ending `-adc` (legacy), `-app`.
  - `func appParentID(contextID string) (parent string, ok bool)` — `"c1-app"` → `("c1", true)`; anything else → `("", false)`.
  - `func splitSDPAnswerN(sdp string, adcBlock int) (bdcAnswer, adcAnswer string)` — `adcBlock` is the index into the `m=application` blocks (1 = Home, 2 = App). `splitSDPAnswer(sdp)` becomes `splitSDPAnswerN(sdp, 1)`; all current callers keep working.

- [ ] **Step 1: Failing tests**

```go
package mf

import (
	"strings"
	"testing"
)

func TestIsAdcContext(t *testing.T) {
	cases := map[string]bool{"c1": false, "c1-adc": true, "c1-app": true, "c1-home": false, "": false}
	for id, want := range cases {
		if got := isAdcContext(id); got != want {
			t.Errorf("isAdcContext(%q)=%v want %v", id, got, want)
		}
	}
}

func TestAppParentID(t *testing.T) {
	if p, ok := appParentID("c1-app"); !ok || p != "c1" {
		t.Fatalf("got %q %v", p, ok)
	}
	if _, ok := appParentID("c1"); ok {
		t.Fatal("plain id has no parent")
	}
	if _, ok := appParentID("c1-adc"); ok {
		t.Fatal("legacy -adc is not an app context")
	}
}

const threeBlockAnswer = "v=0\r\no=- 1 1 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 2 3 4\r\n" +
	"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=mid:2\r\na=ice-ufrag:bdc\r\na=ice-pwd:bdcpwdbdcpwdbdcpwdbdcpwd\r\na=fingerprint:sha-256 BB\r\na=setup:active\r\na=sctp-port:5000\r\n" +
	"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=mid:3\r\na=ice-ufrag:home\r\na=ice-pwd:homepwdhomepwdhomepwdhome\r\na=fingerprint:sha-256 HH\r\na=setup:active\r\na=sctp-port:5000\r\n" +
	"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=mid:4\r\na=ice-ufrag:app\r\na=ice-pwd:apppwdapppwdapppwdapppwd\r\na=fingerprint:sha-256 AA\r\na=setup:active\r\na=sctp-port:5000\r\n"

func TestSplitSDPAnswerN_PicksRequestedBlock(t *testing.T) {
	_, home := splitSDPAnswerN(threeBlockAnswer, 1)
	_, app := splitSDPAnswerN(threeBlockAnswer, 2)
	if !strings.Contains(home, "a=ice-ufrag:home") || strings.Contains(home, "a=ice-ufrag:app") {
		t.Fatalf("home block wrong:\n%s", home)
	}
	if !strings.Contains(app, "a=ice-ufrag:app") || strings.Contains(app, "a=ice-ufrag:home") {
		t.Fatalf("app block wrong:\n%s", app)
	}
}

func TestSplitSDPAnswerN_MissingBlockIsEmpty(t *testing.T) {
	if _, adc := splitSDPAnswerN(threeBlockAnswer, 3); adc != "" {
		t.Fatalf("want empty, got:\n%s", adc)
	}
}

func TestSplitSDPAnswer_StillMeansHome(t *testing.T) {
	_, a := splitSDPAnswer(threeBlockAnswer)
	_, b := splitSDPAnswerN(threeBlockAnswer, 1)
	if a != b {
		t.Fatal("splitSDPAnswer must equal splitSDPAnswerN(…,1)")
	}
}
```

- [ ] **Step 2: Run, expect FAIL** — `cd go-mf && go test ./internal/mf/ -run 'AdcContext|AppParent|SplitSDPAnswer' -v`

- [ ] **Step 3: Implement**

`adc_context.go`:

```go
package mf

import "strings"

// isAdcContext reports whether a context/call id names an ADC sub-context.
// "-adc" is the legacy name (the IMS-AS no longer creates it); "-app" is the
// App slot. The Home ADC lives on the parent context and has no suffix.
func isAdcContext(id string) bool {
	return strings.HasSuffix(id, "-adc") || strings.HasSuffix(id, "-app")
}

func appParentID(contextID string) (string, bool) {
	if !strings.HasSuffix(contextID, "-app") {
		return "", false
	}
	return strings.TrimSuffix(contextID, "-app"), true
}
```

`sdp_utils.go`: rename the existing function `splitSDPAnswer` to `splitSDPAnswerN(sdpAnswer string, adcBlock int) (bdcAnswer string, adcAnswer string)`; change its two uses of `appBlocks[1]` / `len(appBlocks) > 1` to `appBlocks[adcBlock]` / `len(appBlocks) > adcBlock`; add:

```go
func splitSDPAnswer(sdpAnswer string) (string, string) { return splitSDPAnswerN(sdpAnswer, 1) }
```

Replace the five `strings.HasSuffix(<id>, "-adc")` call sites with `isAdcContext(<id>)` (`media_function.go:467`, `:732`, `:1678`, `proxy_handler.go:236`, `sba_handler.go:449`). Do not change any other logic in this task. `sba_handler.go:449` is `else if strings.HasSuffix(contextID, "-adc")`; keep its body as is.

- [ ] **Step 4: Run, expect PASS; whole module green** — `cd go-mf && go build ./... && go test ./...`

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/mf/
git commit -m "refactor(mf): isAdcContext helper and slot-aware splitSDPAnswerN

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Extract ADC transport setup; `<callID>-app` context on shadow sessions

**Files:**
- Modify: `go-mf/internal/mf/sba_handler.go` (`CreateMediaContext`: the `if exists { … }` block at `:29-:384`), `go-mf/internal/mf/media_function.go` (`SessionInfo` `:42`, `SetRemoteAnswer` `:1659`, `CleanupSession` `:~1795`), `go-mf/internal/mf/adc_context.go`
- Test: `go-mf/internal/mf/shadow_session_test.go`

**Interfaces:**
- Consumes: Task 3 (`isAdcContext`, `appParentID`, `splitSDPAnswerN`).
- Produces (used by Task 5):
  - `SessionInfo.AdcBlock int` — index of the `m=application` block this session's ADC answer comes from; `0` means 1 (Home). Method `func (s *SessionInfo) adcBlock() int`.
  - `func newShadowSession(parent *SessionInfo, id, callID string, adcBlock int) *SessionInfo` — copies `UserID`, `LocalIP`, `LocalPort`, `BdcLocalPort`, `LocalAudioPort`, `LocalVideoPort`, `RtpIP`, `ProxyMode`, `Mdc2Protocol`, `DcasIP`, `DcasPort`, `ReplaceHttpUrls`; sets `ID=id`, `CallID=callID`, `AdcBlock=adcBlock`, `CreatedAt=now`, empty `tcpConns`. It does **not** copy `BdcPC`, `AudioConn`, `VideoConn`, `AudioTrack`, `VideoTrack`, `LinkedSession`.
  - `CreateMediaContext` with `ContextID = "<callID>-app"` and the parent context present creates shadow sessions `<sessA>-app` / `<sessB>-app`, registers them in `m.sessions`, sets `cinfo.SessionA/B` to them, links them (`LinkSessions(shadowA, shadowB)`), builds their AdcPC, and returns a termination list with `adc-media` items (same shape as the Home path). Deleting that context (`DeleteMediaContext`) cleans up only the shadows.
  - `UpdateMediaContext` on `<callID>-app` applies `/terminations/{0,1}/sdp` answers via `SetRemoteAnswer(shadowID, …)`, which uses `splitSDPAnswerN(sdp, session.adcBlock())`.
  - `m.buildAdcTransport(contextID string, ctx *sba.MediaContext, sessA, sessB *SessionInfo) *sba.MediaContext` — the body of today's `if exists {…}` block, parameterised on the two sessions.

- [ ] **Step 1: Failing tests (pure; no pion sockets)**

```go
package mf

import "testing"

func TestNewShadowSession_CopiesOnlyAdcRelevantFields(t *testing.T) {
	parent := &SessionInfo{
		ID: "pa", CallID: "c1", UserID: "u", LocalIP: "10.0.0.1", LocalPort: 40000, BdcLocalPort: 40000,
		ProxyMode: "UDP_PROXY", Mdc2Protocol: "TCP", DcasIP: "9.9.9.9", DcasPort: 7000,
		AudioConn: nil, LinkedSession: &SessionInfo{ID: "pb"},
	}
	sh := newShadowSession(parent, "pa-app", "c1-app", 2)
	if sh.ID != "pa-app" || sh.CallID != "c1-app" || sh.adcBlock() != 2 {
		t.Fatalf("identity wrong: %+v", sh)
	}
	if sh.UserID != "u" || sh.LocalIP != "10.0.0.1" || sh.ProxyMode != "UDP_PROXY" || sh.DcasPort != 7000 {
		t.Fatalf("fields not copied: %+v", sh)
	}
	if sh.BdcPC != nil || sh.AudioConn != nil || sh.LinkedSession != nil {
		t.Fatal("shadow must not share the parent's BDC PC, audio conn or link")
	}
}

func TestAdcBlockDefaultsToHome(t *testing.T) {
	if (&SessionInfo{}).adcBlock() != 1 {
		t.Fatal("zero AdcBlock must mean the Home block (1)")
	}
}

func TestLinkSessions_PairsShadowsNotParents(t *testing.T) {
	m := &MediaFunction{sessions: map[string]*SessionInfo{}, contexts: map[string]*ContextInfo{}}
	pa, pb := &SessionInfo{ID: "pa", CallID: "c1"}, &SessionInfo{ID: "pb", CallID: "c1"}
	sa, sb := newShadowSession(pa, "pa-app", "c1-app", 2), newShadowSession(pb, "pb-app", "c1-app", 2)
	for _, s := range []*SessionInfo{pa, pb, sa, sb} {
		m.sessions[s.ID] = s
	}
	if err := m.LinkSessions("pa", "pb"); err != nil {
		t.Fatal(err)
	}
	if err := m.LinkSessions("pa-app", "pb-app"); err != nil {
		t.Fatal(err)
	}
	if pa.LinkedSession != pb || sa.LinkedSession != sb {
		t.Fatal("each slot must link to its own peer")
	}
}

func TestCleanupShadow_LeavesParentAlone(t *testing.T) {
	m := &MediaFunction{sessions: map[string]*SessionInfo{},
		latchedAudioSessions: map[string]*SessionInfo{}, latchedVideoSessions: map[string]*SessionInfo{}}
	parent := &SessionInfo{ID: "pa", CallID: "c1"}
	sh := newShadowSession(parent, "pa-app", "c1-app", 2)
	m.sessions["pa"], m.sessions["pa-app"] = parent, sh
	m.CleanupSession("pa-app")
	if _, ok := m.sessions["pa"]; !ok {
		t.Fatal("parent session must survive deleting the app shadow")
	}
	if _, ok := m.sessions["pa-app"]; ok {
		t.Fatal("shadow must be removed")
	}
}
```

If `MediaFunction` has other map fields that `CleanupSession` dereferences (check with `go vet`/a nil-map panic), add them to the literal in the test; do not change production code for it.

- [ ] **Step 2: Run, expect FAIL** (`newShadowSession` / `adcBlock` undefined) — `cd go-mf && go test ./internal/mf/ -run 'Shadow|AdcBlock|LinkSessions_Pairs' -v`

- [ ] **Step 3a: Extract (behaviour-preserving)**

In `sba_handler.go`, move the body of `if exists { … return &sba.MediaContext{…}, nil }` (everything from `log.Printf("[MF] CreateMediaContext: context %s already exists…` to its `return`) into a new method:

```go
func (m *MediaFunction) buildAdcTransport(contextID string, ctx *sba.MediaContext, sessA, sessB *SessionInfo) *sba.MediaContext
```

Inside the moved body: delete the `existingCtx`/`sessA`/`sessB` lookup lines (they become parameters), and change the final `return &sba.MediaContext{…}, nil` to `return &sba.MediaContext{…}`. Every other line stays as is. In the original place keep:

```go
	if exists {
		m.mu.Lock()
		sessA := m.sessions[existingCtx.SessionA]
		sessB := m.sessions[existingCtx.SessionB]
		m.mu.Unlock()
		return m.buildAdcTransport(contextID, ctx, sessA, sessB), nil
	}
```

Guard the one place that dereferences the BDC PC of a possibly-shadow session: in the moved block replace `mergeSDPOffers(sessX.BdcPC.LocalDescription().SDP, sessX.AdcPC.LocalDescription().SDP)` (A and B variants) with:

```go
				localSDP := sessX.AdcPC.LocalDescription().SDP
				if sessX.BdcPC != nil {
					localSDP = mergeSDPOffers(sessX.BdcPC.LocalDescription().SDP, localSDP)
				}
```

(`sessX` = `sessA` / `sessB` respectively; keep the following `rewriteSDPPorts` / `rewriteAdcPortInSDP` lines.) Also guard `setupX_BDC` reads, which already check `BdcPC != nil`.

Also in the moved block, the four `_, adcOffer := splitSDPAnswer(sdpOfferX)` calls (A and B, offerer and answerer branches — grep `splitSDPAnswer(` in `sba_handler.go`) become `splitSDPAnswerN(sdpOfferX, sessX.adcBlock())`, so a shadow session reads the App block (index 2) while Home keeps block 1.

Run `cd go-mf && go build ./... && go test ./...` — must be green before continuing; commit this step separately:

```bash
git commit -am "refactor(mf): extract buildAdcTransport from CreateMediaContext

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 3b: Shadow sessions and the `-app` context**

`media_function.go`: add `AdcBlock int` to `SessionInfo` (next to the `AdcLocalPort` group). In `SetRemoteAnswer` change `bdcAns, adcAns := splitSDPAnswer(sdpAnswer)` to `bdcAns, adcAns := splitSDPAnswerN(sdpAnswer, session.adcBlock())`. `CleanupSession` needs no change (it already skips nil `BdcPC` and only closes a non-shared `AudioConn`).

`adc_context.go` additions:

```go
func (s *SessionInfo) adcBlock() int {
	if s.AdcBlock == 0 {
		return 1
	}
	return s.AdcBlock
}

// newShadowSession builds a session that carries only the ADC side of a call
// slot. It shares nothing closable with its parent (no BdcPC, no media sockets).
func newShadowSession(parent *SessionInfo, id, callID string, adcBlock int) *SessionInfo {
	parent.mu.Lock()
	defer parent.mu.Unlock()
	return &SessionInfo{
		ID: id, CallID: callID, UserID: parent.UserID,
		LocalIP: parent.LocalIP, LocalPort: parent.LocalPort, BdcLocalPort: parent.BdcLocalPort,
		LocalAudioPort: parent.LocalAudioPort, LocalVideoPort: parent.LocalVideoPort, RtpIP: parent.RtpIP,
		ProxyMode: parent.ProxyMode, Mdc2Protocol: parent.Mdc2Protocol,
		DcasIP: parent.DcasIP, DcasPort: parent.DcasPort, ReplaceHttpUrls: parent.ReplaceHttpUrls,
		AdcBlock: adcBlock, CreatedAt: time.Now(), tcpConns: map[uint16]net.Conn{},
	}
}
```

(add the `net` and `time` imports.)

`sba_handler.go`, in `CreateMediaContext` right after the `if exists {…}` block and before `log.Printf("[MF] CreateMediaContext context=%s"`:

```go
	if parentID, ok := appParentID(contextID); ok {
		m.mu.Lock()
		parent := m.contexts[parentID]
		var pa, pb *SessionInfo
		if parent != nil {
			pa, pb = m.sessions[parent.SessionA], m.sessions[parent.SessionB]
		}
		m.mu.Unlock()
		if pa == nil {
			return nil, fmt.Errorf("parent context %s not found for app slot", parentID)
		}
		shA := newShadowSession(pa, pa.ID+"-app", contextID, 2)
		var shB *SessionInfo
		if pb != nil {
			shB = newShadowSession(pb, pb.ID+"-app", contextID, 2)
		}
		cinfo := &ContextInfo{ContextID: contextID, SessionA: shA.ID}
		m.mu.Lock()
		m.sessions[shA.ID] = shA
		if shB != nil {
			m.sessions[shB.ID] = shB
			cinfo.SessionB = shB.ID
		}
		m.contexts[contextID] = cinfo
		m.mu.Unlock()
		if shB != nil {
			if err := m.LinkSessions(shA.ID, shB.ID); err != nil {
				return nil, err
			}
		}
		log.Printf("[MF] Creating App-slot context %s (parent %s)", contextID, parentID)
		return m.buildAdcTransport(contextID, ctx, shA, shB), nil
	}
```

`buildAdcTransport` already tolerates `sessB == nil` (every B branch is `if sessB != nil`).

- [ ] **Step 4: Run, expect PASS; whole module green** — `cd go-mf && go build ./... && go vet ./internal/mf/ && go test ./...`

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/mf/
git commit -m "feat(mf): App-slot ADC context on shadow sessions

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Slot state on `CallSession`, `slotMFFromContext`, `withSlots` (pure helpers)

**Files:**
- Modify: `go-mf/internal/imsas/b2bua.go` (`CallSession` `:60-80`, `SerializableCallSession` `:92-130`, `ToSerializable` `:132`, `ToCallSession` `:170`)
- Create: `go-mf/internal/imsas/b2bua_slots.go`
- Test: `go-mf/internal/imsas/b2bua_slots_test.go`

**Interfaces:**
- Consumes: `AdcSlot`, `SlotMF`, `MFAllocResponse.Slots` (Tasks 1–2); `sba.MediaContext` / `sba.MediaItem` (`LocalMbEndpoint{IP,Port}`, `LocalDcEndpoint{Fingerprint,IceUfrag,IcePwd,SecuritySetup}`).
- Produces (used by Task 6):
  - `type slotState struct { AppID string; CtxCreated bool }`.
  - `CallSession` gains `AppSlot slotState`, `AppMfA, AppMfB *SlotMF`, `AppAnswerPending bool`. The **Home slot is the existing fields** `ActiveAppID` + `IsAnchorCtxCreated` (Ruling: unchanged, so old clients and the hotline path need no change). `SerializableCallSession` gets `AppSlotAppID string`, `AppSlotCtx bool`, `AppMfA, AppMfB *SlotMF` (json `appSlotAppId`, `appSlotCtx`, `appMfA`, `appMfB`) and both converters copy them.
  - `func appContextID(callID string) string` → `callID + "-app"`.
  - `func slotMFFromContext(ctx *sba.MediaContext) (a, b *SlotMF)` — the `adc-media` item of `term-caller-a` → `a`, of `term-callee-b` → `b`; nil when absent.
  - `func (s *CallSession) withSlots(base *MFAllocResponse, appMf *SlotMF) *MFAllocResponse` — returns a **copy** of `base` whose `Slots` has `SlotHome` built from `base.AdcPort/AdcIceUfrag/AdcIcePwd/AdcDtlsFingerprint` with `Setup: "actpass"`, and `SlotApp` = `appMf` (omitted when nil). Never mutates `base`.

- [ ] **Step 1: Failing tests**

```go
package imsas

import (
	"testing"

	"github.com/demo/ims-dc-mf/internal/sba"
)

func adcItem(ip string, port int, ufrag string) sba.MediaItem {
	return sba.MediaItem{
		MediaID: "adc-media", MediaResourceType: "DC",
		LocalMbEndpoint: &sba.EndpointIPPort{IP: ip, Port: port},
		LocalDcEndpoint: &sba.DcEndpoint{Fingerprint: "sha-256 FF", IceUfrag: ufrag, IcePwd: "pw", SecuritySetup: "actpass"},
	}
}

func TestSlotMFFromContext(t *testing.T) {
	ctx := &sba.MediaContext{Terminations: []sba.Termination{
		{TerminationID: "term-caller-a", Medias: []sba.MediaItem{{MediaID: "audio", MediaResourceType: "RTP"}, adcItem("1.1.1.1", 40010, "ua")}},
		{TerminationID: "term-callee-b", Medias: []sba.MediaItem{adcItem("1.1.1.1", 40011, "ub")}},
	}}
	a, b := slotMFFromContext(ctx)
	if a == nil || a.Port != 40010 || a.IceUfrag != "ua" || a.Fingerprint != "sha-256 FF" || a.Setup != "actpass" {
		t.Fatalf("a=%+v", a)
	}
	if b == nil || b.Port != 40011 {
		t.Fatalf("b=%+v", b)
	}
}

func TestSlotMFFromContext_NoAdc(t *testing.T) {
	a, b := slotMFFromContext(&sba.MediaContext{})
	if a != nil || b != nil {
		t.Fatal("want nil,nil")
	}
}

func TestWithSlots_CopiesAndDoesNotMutate(t *testing.T) {
	base := &MFAllocResponse{SessionID: "s", AdcPort: 40001, AdcIceUfrag: "hu", AdcIcePwd: "hp", AdcDtlsFingerprint: "sha-256 HH"}
	s := &CallSession{}
	out := s.withSlots(base, &SlotMF{Port: 40002, IceUfrag: "au"})
	if base.Slots != nil {
		t.Fatal("base must not be mutated")
	}
	h, a := out.Slots[SlotHome], out.Slots[SlotApp]
	if h == nil || h.Port != 40001 || h.IceUfrag != "hu" || h.Fingerprint != "sha-256 HH" || h.Setup != "actpass" {
		t.Fatalf("home=%+v", h)
	}
	if a == nil || a.Port != 40002 {
		t.Fatalf("app=%+v", a)
	}
	if out2 := s.withSlots(base, nil); out2.Slots[SlotApp] != nil {
		t.Fatal("nil app must omit the App slot")
	}
}

func TestSerializableRoundTripKeepsAppSlot(t *testing.T) {
	s := &CallSession{CallID: "c1", AppSlot: slotState{AppID: "caro", CtxCreated: true}, AppMfA: &SlotMF{Port: 1}, AppMfB: &SlotMF{Port: 2}}
	ser := s.ToSerializable()
	back := ser.ToCallSession()
	if back.AppSlot.AppID != "caro" || !back.AppSlot.CtxCreated || back.AppMfA.Port != 1 || back.AppMfB.Port != 2 {
		t.Fatalf("lost: %+v", back)
	}
	if appContextID("c1") != "c1-app" {
		t.Fatal("appContextID")
	}
}
```

- [ ] **Step 2: Run, expect FAIL** — `cd go-mf && go test ./internal/imsas/ -run 'SlotMF|WithSlots|SerializableRoundTrip' -v`

- [ ] **Step 3: Implement**

`b2bua.go`: add to `CallSession` (after `ActiveAppID`):

```go
	AppSlot          slotState // App ADC slot; the Home slot is ActiveAppID + IsAnchorCtxCreated
	AppMfA, AppMfB   *SlotMF   // MF endpoints for the App slot, toward caller / callee
	AppAnswerPending bool      // a Re-INVITE that changed the App slot awaits its 200 OK
```

Add the four JSON fields to `SerializableCallSession` and copy them in `ToSerializable` / `ToCallSession` (`AppSlotAppID: s.AppSlot.AppID`, `AppSlotCtx: s.AppSlot.CtxCreated`, `AppMfA`, `AppMfB`; reverse in `ToCallSession`).

`b2bua_slots.go`:

```go
package imsas

import "github.com/demo/ims-dc-mf/internal/sba"

type slotState struct {
	AppID      string
	CtxCreated bool
}

func appContextID(callID string) string { return callID + slotSuffix(SlotApp) }

func slotMFFromContext(ctx *sba.MediaContext) (a, b *SlotMF) {
	if ctx == nil {
		return nil, nil
	}
	for _, term := range ctx.Terminations {
		for _, mi := range term.Medias {
			if mi.MediaID != "adc-media" || mi.LocalMbEndpoint == nil {
				continue
			}
			sm := &SlotMF{Port: mi.LocalMbEndpoint.Port}
			if dc := mi.LocalDcEndpoint; dc != nil {
				sm.IceUfrag, sm.IcePwd, sm.Fingerprint, sm.Setup = dc.IceUfrag, dc.IcePwd, dc.Fingerprint, dc.SecuritySetup
			}
			switch term.TerminationID {
			case "term-caller-a":
				a = sm
			case "term-callee-b":
				b = sm
			}
		}
	}
	return a, b
}

func (s *CallSession) withSlots(base *MFAllocResponse, appMf *SlotMF) *MFAllocResponse {
	if base == nil {
		return nil
	}
	cp := *base
	cp.Slots = map[AdcSlot]*SlotMF{
		SlotHome: {Port: base.AdcPort, IceUfrag: base.AdcIceUfrag, IcePwd: base.AdcIcePwd,
			Fingerprint: base.AdcDtlsFingerprint, Setup: "actpass"},
	}
	if appMf != nil {
		cp.Slots[SlotApp] = appMf
	}
	return &cp
}
```

- [ ] **Step 4: Run, expect PASS; module green** — `cd go-mf && go build ./... && go test ./...`

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/imsas/
git commit -m "feat(imsas): App-slot state and slot helpers on CallSession

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Slotted Re-INVITE handler, App-slot 200 OK path, per-slot teardown

**Files:**
- Modify: `go-mf/internal/imsas/b2bua_invite.go` (`handleReInvite` `:201`: add the dispatch; extract the DCSF notification), `go-mf/internal/imsas/b2bua_response.go` (re-INVITE 200 OK branch `:~50-250`), `go-mf/internal/imsas/b2bua.go` (BYE teardown `:~530`, `:~638`), `go-mf/internal/imsas/b2bua_response.go` (`:~381`, `:~489`)
- Create: `go-mf/internal/imsas/b2bua_slot_invite.go`
- Test: `go-mf/internal/imsas/b2bua_slot_invite_test.go` (pure decision function only; end-to-end is Task 8)

**Interfaces:**
- Consumes: Tasks 1, 2, 5.
- Produces:
  - `type appAction int` with `appNone`, `appOpen`, `appClose`.
  - `func decideAppAction(app slotState, line *adcLine) appAction` — `line == nil` or (`!line.Active()` and `!app.CtxCreated`) → `appNone`; `!line.Active()` and `app.CtxCreated` → `appClose`; active with `Binding == nil` → `appNone`; active, `app.CtxCreated` and `app.AppID == line.Binding.ReqAppId` → `appNone`; otherwise `appOpen` (covers first open and switch).
  - `func (b *B2BUA) notifyMediaChangeRequest(session *CallSession, binding *sba.AppBindingInfo, reqAppId string)` — the DCSF notification + stale-instruction drain extracted verbatim from `handleReInvite` (`// 1. Notify DCSF via DC1` … up to and including the `for len(session.InstructionChan) > 0` drain), reused by both handlers.
  - `func (b *B2BUA) handleSlottedReInvite(session *CallSession, msg *SIPMessage, remoteAddr *net.UDPAddr, lines []adcLine)`.
  - Dispatch at the top of `handleReInvite`, after `session.OrigMsg = msg` and the first log line: `if lines := parseAdcLines(msg.Body); len(lines) == 2 && !config.IsHotline(session.Callee) { b.handleSlottedReInvite(session, msg, remoteAddr, lines); return }`. One- or zero-ADC offers (old clients, the first Home Re-INVITE, hotline) take the existing path untouched.

- [ ] **Step 1: Failing test for the decision function**

```go
package imsas

import (
	"testing"

	"github.com/demo/ims-dc-mf/internal/sba"
)

func bind(id string) *sba.AppBindingInfo { return &sba.AppBindingInfo{ReqAppId: id, AppDcInfo: "1000-UE"} }

func TestDecideAppAction(t *testing.T) {
	cases := []struct {
		name string
		app  slotState
		line *adcLine
		want appAction
	}{
		{"no line", slotState{}, nil, appNone},
		{"port0 and nothing open", slotState{}, &adcLine{Slot: SlotApp, Port: 0}, appNone},
		{"port0 closes open app", slotState{AppID: "caro", CtxCreated: true}, &adcLine{Slot: SlotApp, Port: 0}, appClose},
		{"first open", slotState{}, &adcLine{Slot: SlotApp, Port: 9, Binding: bind("caro")}, appOpen},
		{"same app unchanged", slotState{AppID: "caro", CtxCreated: true}, &adcLine{Slot: SlotApp, Port: 9, Binding: bind("caro")}, appNone},
		{"switch app", slotState{AppID: "caro", CtxCreated: true}, &adcLine{Slot: SlotApp, Port: 9, Binding: bind("photo-share")}, appOpen},
		{"active without req-app", slotState{}, &adcLine{Slot: SlotApp, Port: 9}, appNone},
		{"reopen after close", slotState{AppID: "caro", CtxCreated: false}, &adcLine{Slot: SlotApp, Port: 9, Binding: bind("caro")}, appOpen},
	}
	for _, c := range cases {
		if got := decideAppAction(c.app, c.line); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}
```

- [ ] **Step 2: Run, expect FAIL** — `cd go-mf && go test ./internal/imsas/ -run DecideAppAction -v`

- [ ] **Step 3a: Extract `notifyMediaChangeRequest` (behaviour-preserving)**

In `handleReInvite` cut from `// 1. Notify DCSF via DC1 (MEDIA_CHANGE_REQUEST)` through the end of the `for len(session.InstructionChan) > 0 { <-session.InstructionChan }` drain into `notifyMediaChangeRequest(session, appBinding, reqAppId)` (parameters replace the captured `appBinding`, `reqAppId`; `msg` is not used there). Replace the cut block with `b.notifyMediaChangeRequest(session, appBinding, reqAppId)`. `cd go-mf && go build ./... && go test ./...` must stay green; commit separately (`refactor(imsas): extract notifyMediaChangeRequest`, with the trailer).

- [ ] **Step 3b: Implement the decision function and the slotted handler**

`b2bua_slot_invite.go`:

```go
package imsas

import (
	"fmt"
	"log"
	"net"
	"time"

	"github.com/demo/ims-dc-mf/internal/sba"
)

type appAction int

const (
	appNone appAction = iota
	appOpen
	appClose
)

func decideAppAction(app slotState, line *adcLine) appAction {
	if line == nil {
		return appNone
	}
	if !line.Active() {
		if app.CtxCreated {
			return appClose
		}
		return appNone
	}
	if line.Binding == nil {
		return appNone
	}
	if app.CtxCreated && app.AppID == line.Binding.ReqAppId {
		return appNone
	}
	return appOpen
}

func (b *B2BUA) mfURLFor(session *CallSession) string {
	if session.MfHost == "" {
		session.MfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, session.CallID)
	}
	return fmt.Sprintf("http://%s:%d", session.MfHost, b.cfg.Services.Mf.Port)
}

func (b *B2BUA) forwardReInvite(session *CallSession, msg *SIPMessage, sdp string) {
	targetCallID := session.TermCallID
	if msg.ExtractUsername("From") != session.Caller {
		targetCallID = session.OrigCallID
	}
	headers := map[string]string{"Content-Type": "application/sdp", "Call-ID": targetCallID}
	b.forwardToSCSCF(b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, sdp))
}

// closeAppSlot deletes the App slot's MF context and clears its state.
func (b *B2BUA) closeAppSlot(session *CallSession) {
	if session.AppSlot.CtxCreated {
		if err := b.CallNmfDeleteMediaContext(b.mfURLFor(session), appContextID(session.CallID)); err != nil {
			log.Printf("[IMS-AS] App slot: delete MF context failed: %v", err)
		}
	}
	session.AppSlot = slotState{}
	session.AppMfA, session.AppMfB = nil, nil
}

// slotOffer rewrites the offer for the peer: Home line unchanged, App line from appMf.
func (b *B2BUA) slotOffer(session *CallSession, msg *SIPMessage, isCallerInitiator bool, activeApp string) string {
	target, appMf := session.MfInfoB, session.AppMfB
	if !isCallerInitiator {
		target, appMf = session.MfInfoA, session.AppMfA
	}
	if target == nil {
		return msg.Body
	}
	return RewriteSDP(msg.Body, session.withSlots(target, appMf), activeApp)
}

func (b *B2BUA) handleSlottedReInvite(session *CallSession, msg *SIPMessage, remoteAddr *net.UDPAddr, lines []adcLine) {
	isCallerInitiator := msg.ExtractUsername("From") == session.Caller
	line := adcLineFor(lines, SlotApp)
	action := decideAppAction(session.AppSlot, line)
	log.Printf("[IMS-AS] Slotted re-INVITE: app action=%d (current app=%q created=%v)", action, session.AppSlot.AppID, session.AppSlot.CtxCreated)

	switch action {
	case appNone:
		b.forwardReInvite(session, msg, b.slotOffer(session, msg, isCallerInitiator, session.AppSlot.AppID))
		return

	case appClose:
		b.closeAppSlot(session)
		session.AppAnswerPending = false
		b.forwardReInvite(session, msg, b.slotOffer(session, msg, isCallerInitiator, ""))
		return
	}

	// appOpen: bind the app with the DCSF, then (re)create the App-slot MF context.
	binding := line.Binding
	b.notifyMediaChangeRequest(session, binding, binding.ReqAppId)
	select {
	case instruction := <-session.InstructionChan:
		var mSpec *sba.DcMediaSpecification
		for _, mi := range instruction.MediaInstructionSet {
			if mi.MediaResourceType == "DC" {
				mSpec = mi.DcMediaSpecification
				break
			}
		}
		select {
		case session.ResponseChan <- instruction:
		default:
		}
		if mSpec == nil || mSpec.AdcType == "P2P" {
			b.forwardReInvite(session, msg, msg.Body)
			return
		}

		replacing := session.AppSlot.CtxCreated
		if replacing {
			b.closeAppSlot(session)
			time.Sleep(500 * time.Millisecond) // let the shared ADC UDP mux release the old ICE ufrag
		}

		dcMedia := &sba.DcMedia{MediaProxyConfig: mSpec.MediaProxyConfig, Streams: mSpec.Streams, ReplaceHttpUrls: mSpec.ReplaceHttpUrls}
		if mSpec.Mdc2EndpointInfo != nil {
			dcMedia.Mdc2Info = &sba.Mdc2Info{Mdc2Protocol: mSpec.Mdc2EndpointInfo.Mdc2Protocol,
				RemoteMdc2Endpoint: mSpec.Mdc2EndpointInfo.Mdc2EndpointDcAs, LocalMdc2Endpoint: mSpec.Mdc2EndpointInfo.Mdc2EndpointMf}
		}
		var sdpA, sdpB string
		if isCallerInitiator {
			sdpA = msg.Body
		} else {
			sdpB = msg.Body
		}
		item := func(sdp string) []sba.MediaItem {
			return []sba.MediaItem{{MediaID: "adc-media", MediaResourceType: "DC", SDP: sdp, DcMedia: dcMedia}}
		}
		req := sba.MediaContext{
			ContextID: appContextID(session.CallID), IsCoreNetwork: true,
			Terminations: []sba.Termination{
				{TerminationID: "term-caller-a", Medias: item(sdpA)},
				{TerminationID: "term-callee-b", Medias: item(sdpB)},
			},
		}
		allocated, err := b.CallNmfCreateMediaContext(b.mfURLFor(session), req)
		if err != nil {
			log.Printf("[IMS-AS] App slot: MF create failed: %v", err)
			b.sendError(msg, 503, "Service Unavailable", remoteAddr)
			return
		}
		session.AppMfA, session.AppMfB = slotMFFromContext(allocated)
		session.AppSlot = slotState{AppID: binding.ReqAppId, CtxCreated: true}
		session.AppAnswerPending = true
		session.AdcType = mSpec.AdcType
		if isCallerInitiator {
			session.LastCallerReInviteMsg = msg
		} else {
			session.LastCalleeReInviteMsg = msg
		}

		offer := b.slotOffer(session, msg, isCallerInitiator, binding.ReqAppId)
		if mSpec.AdcType == "P2A" {
			offer = ExcludeADCFromSDP(offer) // P2A: the peer gets no App line (port 0)
		}
		b.forwardReInvite(session, msg, offer)

	case <-time.After(5 * time.Second):
		log.Printf("[IMS-AS] App slot: no media instruction in 5 s; disabling the App line [TS 24.186 9.4.4]")
		b.forwardReInvite(session, msg, ExcludeADCFromSDP(msg.Body))
	}
}
```

The NMF create request mirrors today's P2A2P request (terminations `term-caller-a` / `term-callee-b`, media id `adc-media`); the hotline and interworking variants are not reachable here (hotline is excluded by the dispatch; slotted offers come from the new client only). If `mSpec.AdcType == "P2A"` the callee-side `SDP` field in the request is empty, which `buildAdcTransport` already treats as "MF is the offerer".

- [ ] **Step 3c: 200 OK path for an App-slot change**

In `b2bua_response.go`, in the re-INVITE branch (`if cseqVal > session.InitialCSeq || isCalleeInitiatedAnswer {`) immediately after `dcsfURL` is computed and **before** the `if session.IsADCReleasing {` block, add:

```go
		if session.AppAnswerPending || (session.AppSlot.CtxCreated == false && len(parseAdcLines(msg.Body)) == 2) {
			b.handleSlottedAnswer(session, msg, dcsfURL, isCallerInitiator)
			return
		}
```

(`isCallerInitiator` is computed earlier in this branch for the P2A2P path; if it is declared later in the function, compute it here with the same expression that path uses.) Add `handleSlottedAnswer` to `b2bua_slot_invite.go`:

```go
// handleSlottedAnswer finishes a Re-INVITE that changed the App slot: patch the
// App-slot MF context with the answer, notify the DCSF, and return the answer to
// the initiator with the App line rewritten to the MF.
func (b *B2BUA) handleSlottedAnswer(session *CallSession, msg *SIPMessage, dcsfURL string, isCallerInitiator bool) {
	if session.AppAnswerPending && session.AppSlot.CtxCreated && session.AdcType != "P2A" {
		path := "/terminations/1/sdp"
		if !isCallerInitiator {
			path = "/terminations/0/sdp"
		}
		patches := []sba.PatchDocument{{Op: "replace", Path: path, Value: json.RawMessage(fmt.Sprintf("%q", msg.Body))}}
		if err := b.CallNmfUpdateMediaContext(b.mfURLFor(session), appContextID(session.CallID), patches); err != nil {
			log.Printf("[IMS-AS] App slot: answer patch failed: %v", err)
		}
	}
	if session.AppAnswerPending {
		note := sba.SessionEventNotification{
			SessionID:         session.CallID,
			NotificationEvent: sba.NotificationEvent{ReferenceID: 12, EventType: "MediaChangeSuccessEvent", EventInitiator: "served IMS subscriber"},
			PsDataOff:         session.PsDataOff,
			SessionInfo: sba.SessionInfo{
				CallingIdentity: "sip:" + session.Caller + "@" + b.cfg.Domains.Ims,
				CalledIdentity:  "sip:" + session.Callee + "@" + b.cfg.Domains.Ims,
				SessionCase:     "ORIGINATING_IMS_SESSION",
			},
		}
		go func() { _ = b.CallNmfSessionNotifyForApp(dcsfURL, note) }()
	}
	session.AppAnswerPending = false

	target, appMf := session.MfInfoA, session.AppMfA
	if !isCallerInitiator {
		target, appMf = session.MfInfoB, session.AppMfB
	}
	out := msg.Body
	if target != nil {
		cp := session.withSlots(target, appMf)
		for _, sm := range cp.Slots {
			sm.Setup = "passive" // MF answers: passive toward the answering UE
		}
		out = RewriteSDP(msg.Body, cp, session.AppSlot.AppID)
	}
	b.sendAnswerToInitiator(session, msg, isCallerInitiator, out)
}
```

Two names above stand for code that already exists in this branch and must be reused, not invented: replace `b.CallNmfSessionNotifyForApp(dcsfURL, note)` with the existing `b.CallNimsasSessionEventNotify(dcsfURL, note)`; replace `b.sendAnswerToInitiator(...)` with the existing tail of the P2A2P path (the `headers := … "To": msg.GetHeader("To") …` block through the 200 OK build/`sendUDP` to the initiator, `b2bua_response.go` right after `[IMS-AS] P2A2P: Rewrote terminating ADC SDP Answer`), moved into a small helper `sendAnswerToInitiator(session, msg, isCallerInitiator, sdp)` that both paths call. Add `encoding/json` to the imports of the new file.

Home Setup: the loop sets every slot (including Home) to `passive`, matching how the existing answer path sets `AdcSetupRole = "passive"`.

- [ ] **Step 3d: Per-slot teardown**

Everywhere the BYE / CANCEL / announcement paths delete the call's MF contexts (`b2bua.go:~530`, `:~638`, `b2bua_response.go:~381`, `:~489`) add `b.closeAppSlot(session)` before the existing `CallNmfDeleteMediaContext(mfURL, session.ContextID)`. At `b2bua.go:~533-534` also replace the `session.CallID+"-adc"` delete (dead legacy name) with `b.closeAppSlot(session)`, and the same at `:~639`. `closeAppSlot` is a no-op when the App slot was never created.

- [ ] **Step 4: Run, expect PASS; module green** — `cd go-mf && go build ./... && go vet ./internal/imsas/ && go test ./...`

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/imsas/
git commit -m "feat(imsas): App-slot Re-INVITE handling with per-slot MF context

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: UE simulator — three application m-lines

**Files:**
- Modify: `go-mf/internal/uesim/webrtc_manager.go` (add helper after `appendReqAppToSDPClose`, `:~975`), `go-mf/internal/uesim/sip_manager.go` (`:678` open path, `:745` close path)
- Test: `go-mf/internal/uesim/webrtc_manager_test.go`

**Interfaces:**
- Consumes: nothing from earlier tasks (the simulator is a separate package; it speaks plain SDP).
- Produces:
  - `func setAppSlotReqApp(sdp, appId string, open bool) string` — operates on the **3rd** `m=application` section (App slot). `open=true`: ensures the section exists-with-port-9 and appends `a=dcmap:1000 subprotocol="<appId>"` + `a=3gpp-req-app:"<appId>";1000-UE` after its m-line; `open=false`: sets its port to `0` and drops its `a=3gpp-req-app` / `a=dcmap` lines. Sections 1 and 2 are copied through untouched. If the SDP has fewer than three application sections, it is returned unchanged.
  - Home slot keeps using `appendReqAppToSDP` (2nd application section), which is exactly what the current simulator already does for `emoji`.

- [ ] **Step 1: Failing tests**

```go
func threeAppSDP(appPort string) string {
	return "v=0\r\nm=audio 4000 RTP/AVP 0\r\nm=video 4002 RTP/AVP 96\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:2\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:3\r\n" +
		"m=application " + appPort + " UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:4\r\n"
}

func TestSetAppSlotReqApp_Open(t *testing.T) {
	out := setAppSlotReqApp(threeAppSDP("0"), "caro", true)
	i := strings.LastIndex(out, "m=application ")
	tail := out[i:]
	if !strings.HasPrefix(tail, "m=application 9 ") || !strings.Contains(tail, `a=3gpp-req-app:"caro";1000-UE`) || !strings.Contains(tail, `a=dcmap:1000 subprotocol="caro"`) {
		t.Fatalf("app section wrong:\n%s", tail)
	}
	if strings.Count(out, "3gpp-req-app") != 1 {
		t.Fatalf("only the 3rd section may carry req-app:\n%s", out)
	}
}

func TestSetAppSlotReqApp_Close(t *testing.T) {
	open := setAppSlotReqApp(threeAppSDP("0"), "caro", true)
	out := setAppSlotReqApp(open, "caro", false)
	i := strings.LastIndex(out, "m=application ")
	if !strings.HasPrefix(out[i:], "m=application 0 ") || strings.Contains(out[i:], "3gpp-req-app") {
		t.Fatalf("not closed:\n%s", out[i:])
	}
	if !strings.Contains(out, "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:2") {
		t.Fatal("BDC section must be untouched")
	}
}

func TestSetAppSlotReqApp_TwoSectionsUnchanged(t *testing.T) {
	two := "v=0\r\nm=application 9 X\r\nm=application 9 X\r\n"
	if got := setAppSlotReqApp(two, "caro", true); got != two {
		t.Fatalf("changed: %q", got)
	}
}
```

- [ ] **Step 2: Run, expect FAIL** — `cd go-mf && go test ./internal/uesim/ -run SetAppSlotReqApp -v`

- [ ] **Step 3: Implement**

```go
// setAppSlotReqApp edits the 3rd m=application section (the App ADC slot, mid 4).
func setAppSlotReqApp(sdp, appId string, open bool) string {
	lines := strings.Split(strings.ReplaceAll(sdp, "\r\n", "\n"), "\n")
	count := 0
	for _, l := range lines {
		if strings.HasPrefix(strings.TrimSpace(l), "m=application ") {
			count++
		}
	}
	if count < 3 {
		return sdp
	}
	var out []string
	appIdx, inApp := 0, false
	for _, l := range lines {
		t := strings.TrimSpace(l)
		if t == "" {
			continue
		}
		if strings.HasPrefix(t, "m=") {
			inApp = false
		}
		if strings.HasPrefix(t, "m=application ") {
			appIdx++
			if appIdx == 3 {
				inApp = true
				parts := strings.Split(t, " ")
				if open {
					parts[1] = "9"
				} else {
					parts[1] = "0"
				}
				out = append(out, strings.Join(parts, " "))
				if open {
					out = append(out, fmt.Sprintf("a=dcmap:1000 subprotocol=%q", appId),
						fmt.Sprintf("a=3gpp-req-app:%q;1000-UE", appId))
				}
				continue
			}
		}
		if inApp && (strings.HasPrefix(t, "a=3gpp-req-app:") || strings.HasPrefix(t, "a=dcmap:")) {
			continue // replaced above on open, dropped on close
		}
		out = append(out, t)
	}
	return strings.Join(out, "\r\n") + "\r\n"
}
```

Wire-up in `sip_manager.go`: the app-open Re-INVITE (`:678`) calls `setAppSlotReqApp(localSDP, appId, true)` for every app except `emoji`/`fun-calling-app` (those keep `appendReqAppToSDP`); the close path (`:745`) calls `setAppSlotReqApp(mergedSDP, appId, false)` instead of `appendReqAppToSDPClose` when the SDP has three application sections. The simulator's pion layer needs no change for the scenario test, which is SDP-level (Task 8); full pion support for a second ADC PeerConnection is out of scope and recorded as a risk.

- [ ] **Step 4: Run, expect PASS; module green** — `cd go-mf && go build ./... && go test ./...`

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/uesim/
git commit -m "feat(uesim): App-slot (3rd application m-line) SDP helper

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Scenario test with fake MF and DCSF (IMS-AS level)

**Files:**
- Create: `go-mf/internal/imsas/slot_scenario_test.go`

**Interfaces:**
- Consumes: everything from Tasks 1–6 (`decideAppAction`, `parseAdcLines`, `RewriteSDP`, `withSlots`, `slotMFFromContext`, `appContextID`, `CallSession` slot fields).
- Produces: the regression net for "one Re-INVITE per app change, Home never deleted".

The test drives the **pure** pieces in sequence the way `handleSlottedReInvite` does (no sockets): it keeps a `CallSession`, a recording fake of the MF REST API (`httptest.Server` handling `POST /nmf/.../media-contexts` and `DELETE`), and for each step builds the SIP offer, runs `parseAdcLines` → `decideAppAction`, applies the create/delete calls to the fake, updates `session.AppSlot`, and rewrites the SDP.

- [ ] **Step 1: Write the test**

```go
package imsas

import (
	"strings"
	"testing"
)

type fakeMF struct{ created, deleted []string }

func (f *fakeMF) apply(s *CallSession, act appAction, appID string) {
	switch act {
	case appClose:
		f.deleted = append(f.deleted, appContextID(s.CallID))
		s.AppSlot = slotState{}
		s.AppMfA, s.AppMfB = nil, nil
	case appOpen:
		if s.AppSlot.CtxCreated {
			f.deleted = append(f.deleted, appContextID(s.CallID))
		}
		f.created = append(f.created, appContextID(s.CallID))
		s.AppSlot = slotState{AppID: appID, CtxCreated: true}
		s.AppMfA = &SlotMF{Port: 40100 + len(f.created), IceUfrag: "u" + appID, IcePwd: "pw", Fingerprint: "sha-256 AA", Setup: "actpass"}
	}
}

func offer(homeOpen bool, appPort, appID string) string {
	s := "v=0\r\nm=audio 4000 RTP/AVP 0\r\nm=video 4002 RTP/AVP 96\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:2\r\na=dcmap:10 subprotocol=\"http\"\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:3\r\na=dcmap:1000 subprotocol=\"fun-calling-app\"\r\na=3gpp-req-app:\"fun-calling-app\";1000-UE\r\n" +
		"m=application " + appPort + " UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:4\r\n"
	if appID != "" {
		s += "a=dcmap:1000 subprotocol=\"" + appID + "\"\r\na=3gpp-req-app:\"" + appID + "\";1000-UE\r\n"
	}
	return s
}

func TestScenario_OneReInvitePerStep_HomeNeverDeleted(t *testing.T) {
	s := &CallSession{CallID: "call1"}
	mf := &fakeMF{}
	home := &MFAllocResponse{SessionID: "call1", AdcPort: 40001, AdcIceUfrag: "hu", AdcIcePwd: "hp", AdcDtlsFingerprint: "sha-256 HH"}

	steps := []struct {
		name, appPort, appID string
		wantAct              appAction
		wantCreated, wantDel int
	}{
		{"open caro", "9", "caro", appOpen, 1, 0},
		{"back to emotes", "0", "", appClose, 1, 1},
		{"open photo-share", "9", "photo-share", appOpen, 2, 1},
		{"caro directly", "9", "caro", appOpen, 3, 2}, // replace in place: delete + create
		{"resend same offer", "9", "caro", appNone, 3, 2},
		{"back to emotes again", "0", "", appClose, 3, 3},
	}
	var prevHomeLine string
	for _, st := range steps {
		sdp := offer(true, st.appPort, st.appID)
		lines := parseAdcLines(sdp)
		if len(lines) != 2 {
			t.Fatalf("%s: want 2 ADC lines, got %d", st.name, len(lines))
		}
		act := decideAppAction(s.AppSlot, adcLineFor(lines, SlotApp))
		if act != st.wantAct {
			t.Fatalf("%s: action %v want %v", st.name, act, st.wantAct)
		}
		mf.apply(s, act, st.appID)
		if len(mf.created) != st.wantCreated || len(mf.deleted) != st.wantDel {
			t.Fatalf("%s: created=%d deleted=%d want %d/%d", st.name, len(mf.created), len(mf.deleted), st.wantCreated, st.wantDel)
		}
		out := RewriteSDP(sdp, s.withSlots(home, s.AppMfA), s.AppSlot.AppID)

		// Home context is never the one being deleted.
		for _, d := range mf.deleted {
			if strings.HasSuffix(d, "-home") || d == "call1" {
				t.Fatalf("%s: Home context deleted: %s", st.name, d)
			}
		}
		// Home line is byte-identical across every step.
		i := strings.Index(out, "a=mid:3")
		j := strings.Index(out[i:], "m=application")
		homeLine := out[i : i+j]
		if prevHomeLine != "" && homeLine != prevHomeLine {
			t.Fatalf("%s: Home section changed:\n--- before\n%s\n--- after\n%s", st.name, prevHomeLine, homeLine)
		}
		prevHomeLine = homeLine
		// BDC stays untouched and the App section is closed exactly when no app is open.
		if !strings.Contains(out, "a=dcmap:10 subprotocol=\"http\"") {
			t.Fatalf("%s: BDC lost", st.name)
		}
		if (s.AppSlot.CtxCreated) == strings.Contains(out, "m=application 0 ") {
			t.Fatalf("%s: App line state does not match slot state:\n%s", st.name, out)
		}
	}
}
```

- [ ] **Step 2: Run, expect PASS** (all pieces exist) — `cd go-mf && go test ./internal/imsas/ -run Scenario -v`. If the Home-section assertion fails, the cause is a `RewriteSDP` instability (Task 2): fix there, not here.

- [ ] **Step 3: Module green and commit**

```bash
cd go-mf && go build ./... && go vet ./... && go test ./...
git add go-mf/internal/imsas/slot_scenario_test.go
git commit -m "test(imsas): one Re-INVITE per app change; Home context never deleted

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Cleanup — remove the `operator-menu` release path and `IsADCReleasing`

**Files:**
- Modify: `go-mf/internal/imsas/b2bua_invite.go`, `b2bua_response.go`, `b2bua.go` (every reference found by the grep below)

**Interfaces:**
- Consumes: Tasks 5–6 (slotted path handles app changes for new clients).
- Produces: no new symbols.

**Gate:** do this task only after Plan 3 (the Android client) is merged and no deployed client still sends the release-to-`operator-menu` Re-INVITE. Old clients that send a single-ADC offer continue through the legacy `handleReInvite` path, which still needs `IsADCReleasing` — so removal is deferred to that point. If the executor is running Plan 2 on its own, **skip this task**, record `Ruling: Task 9 deferred until the client ships` in the ledger, and mark the plan complete.

- [ ] **Step 1: Find the surface** — `cd go-mf && grep -rn "IsADCReleasing\|operator-menu\|session.CallID *+ *\"-adc\"" internal/ | grep -v _test.go`
- [ ] **Step 2: Remove** the `operator-menu` release detection in `handleReInvite`, the `IsADCReleasing` field and its `SerializableCallSession` twin, the `if session.IsADCReleasing {` block in `b2bua_response.go`, and every remaining `+"-adc"` delete.
- [ ] **Step 3: Delete the dead tests** that assert the removed behaviour, then `cd go-mf && go build ./... && go vet ./... && go test ./...`.
- [ ] **Step 4: Commit** — `git commit -m "refactor(imsas): drop operator-menu release path and IsADCReleasing" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"`

---

## Scan table (for the SDD ledger)

| Pair / task | Produces vs consumes | Finding |
|---|---|---|
| 1 → 2 | `AdcSlot`, `slotSuffix` → `MFAllocResponse.Slots` | consistent |
| 1 → 5, 6, 8 | `parseAdcLines`, `adcLineFor`, `adcLine.Active` | consistent; Task 6 uses `line.Binding.ReqAppId` |
| 2 → 5, 6, 8 | `SlotMF`, `RewriteSDP(…, activeApp)` | `withSlots` copies Home from `Adc*`; Task 8 asserts byte-stable Home |
| 3 → 4 | `splitSDPAnswerN`, `isAdcContext`, `appParentID` | consistent; Task 4 consumes all three |
| 4 → 6 | MF `CreateMediaContext` for ID `<callID>-app` | Task 6 sends `ContextID: appContextID(callID)` = `callID+"-app"` — matches `appParentID` |
| 5 → 6 | `slotState`, `AppMfA/B`, `AppAnswerPending`, `withSlots`, `slotMFFromContext` | consistent |
| 6 → 8 | `decideAppAction`, `closeAppSlot` semantics | Task 8's `fakeMF.apply` mirrors the handler (replace = delete + create) |
| 7 ↔ others | independent package | none |
| 9 ↔ 6 | shares `b2bua_response.go` | Task 9 is gated on Plan 3 |
| Each task | tests vs code vs file list | Task 6 touches `b2bua_response.go` and `b2bua.go`, both listed; Task 6's `handleSlottedAnswer` names two existing helpers (`CallNimsasSessionEventNotify`, the P2A2P answer tail) that the implementer must locate and reuse — flagged in the task text |

## Risks (carried into the ledger)

1. **No phone testing here.** Go tests cover SDP and state; real DTLS/SCTP on two ADC PeerConnections is verified only on devices (Plan 3, Task "device run").
2. **MF has no automated tests.** Tasks 3–4 add the first; the shadow-session wiring is also exercised only by a live call.
3. **Shared ADC mux (port 40005).** Two `AdcPC`s demultiplex by ICE ufrag; distinct ufrags per slot are guaranteed because the contexts are separate.
4. **500 ms delete→create gap** when the App slot is replaced in one Re-INVITE (copied from the legacy path); if a stale ufrag still lingers, raise it or poll the MF.
5. **`a=setup`.** Home answers `passive` and offers `actpass` on every re-offer; Android must not treat a repeated `actpass` as a new DTLS association (Plan 3 verifies by the stable `tls-id`).
6. **Shadow sessions share no closable resources with the parent** (Task 4 test pins this); `CleanupSession` on a shadow must not close `BdcPC`/audio/video.
7. **Old clients** keep the legacy path (zero- or one-ADC offers; hotline). Task 9 stays deferred until they are gone.
