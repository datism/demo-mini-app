# Hotline 198 Self-service App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dialling 198 opens a self-service page (balance/data, packages, top-up, network report) backed by a per-subscriber in-memory account in DCAS, replacing the old Smart Menu.

**Architecture:** The page stays app id `smart-menu` (routing unchanged) and talks to DCAS over ADC stream 1000 with `{service:"smart-menu", action}` JSON. A new `selfcare_198.go` in `internal/dcas` holds a mutex-guarded store keyed by caller number and a pure reply builder; `dcas.go` routes `service:"smart-menu"` to it and drops the old Smart Menu switch.

**Tech Stack:** Go 1.24 (stdlib `testing`), single-file vanilla HTML/CSS/JS page, Playwright (Node, preinstalled) for the page check.

**Spec:** `docs/superpowers/specs/2026-11-26-hotline-198-selfcare-design.md`

## Global Constraints

- App id stays `smart-menu`; `app.json` keeps `connectionPolicy: "P2A"`, version bumps `1.0.0` → `2.0.0`.
- No Android/Kotlin changes; no external network at runtime (no Google Fonts, no CDN).
- Page fonts: `"Space Grotesk", "Inter", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif` (headings) and `"Inter", system-ui, …` (body). Colours: ground `#F6F7F9`, ink `#111827`, muted `#4B5563`, line `#E5E7EB`, accent `#D9480F`, accent-soft `#FFF4ED`, accent-ink `#9A3412`, ok `#15803D`/`#DCFCE7`.
- Page transport: send `AndroidBridge.sendData(json)` → else `AndroidBridge.send(1000, json)` → else `IMSDataChannelBridge.send(1000, json)`; receive on `window.receiveData` and `window.onDataChannelMessage`; expose `window.resetSmartMenu()`.
- Seed per new caller: balance `148500`, package `MAX90`, data `3200`/`5000` MB (page shows GB = MB/1000, one decimal, comma: `3,2 / 5 GB`), expiry `"30/12/2026"`, bill month `"11"`, bill total `86400`, one report `{ticket:"PA-20431", issue:"Mất sóng", sent:"18/11", status:"Đã xong"}`.
- Packages (order fixed): `SPEED120` "4 GB/ngày · 30 ngày" 120000 alloc 4000; `MAX90` "5 GB/tháng · 30 ngày" 90000 alloc 5000; `DAY10` "2 GB · 24 giờ" 10000 alloc 2000.
- Top-up amounts allowed: 50000, 100000, 200000. Issues allowed: "Data chậm", "Mất sóng", "Rớt cuộc gọi", "Không gửi được SMS".
- Money in VND ints on the wire; page formats `148.500đ`.

## Review Focus

- Two calls from the same number (or reopen after reset) must see the updated balance/package/reports, while a different number sees fresh seed data → test `TestSelfcareAccountsArePerCaller` (Task 1).
- Subscribing with exactly the balance equal to price must succeed (≤, not <) → test `TestSelfcareSubscribeExactBalance` (Task 1).
- Whitespace-only `location` must count as missing → test case in `TestSelfcareReportValidation` (Task 1).
- Non-smart-menu payloads on stream 1000 (draw/clear, caro, flight-booking, no service) must keep their old routing and never get a smart-menu reply → test `TestSmartMenuRoutingLeavesOtherServices` (Task 2).
- DCAS never replies (or slow phone) → page re-enables buttons and shows the retry toast after 8 s → Playwright check "no reply" in Task 3.

---

### Task 1: Self-care account store and reply builder

**Files:**
- Create: `go-mf/internal/dcas/selfcare_198.go`
- Test: `go-mf/internal/dcas/selfcare_198_test.go`

**Interfaces:**
- Produces:
  - `type SelfcareRequest struct { Service, Action, Code string; Amount int; Issue, Location, Note string }` (json: `service, action, code, amount, issue, location, note`)
  - `type SelfcareAccount struct { MSISDN string; Balance int; Expiry, Package string; DataLeftMB, DataTotalMB int; BillMonth string; BillTotal int }` (json: `msisdn, balance, expiry, package, data_left_mb, data_total_mb, bill_month, bill_total`)
  - `type SelfcarePackage struct { Code, Name, Desc string; Price int }` (json `code,name,desc,price`); `type SelfcareReport struct { Ticket, Issue, Sent, Status string }`; `type SelfcareReceipt struct { Title string; Rows []SelfcareRow }`; `type SelfcareRow struct { Label, Value string }`
  - `type SelfcareResponse struct { Service, Action, Status, Code, Message, Txn, Ticket string; Account *SelfcareAccount; Packages []SelfcarePackage; Reports []SelfcareReport; Receipt *SelfcareReceipt }` (json snake_case, all but service/action/status `omitempty`). `Status` is `"ok"` or `"error"`.
  - `type selfcareStore struct` with `newSelfcareStore() *selfcareStore`; package var `selfcareAccounts = newSelfcareStore()`
  - `type selfcareIDs struct { Txn func() string; Ticket func() string; Today func() string }`; `var defaultSelfcareIDs` (txn `GD-` + 6 upper alnum, ticket `PA-` + 5 digits, today `dd/mm`)
  - `func buildSelfcareReply(req SelfcareRequest, caller string, st *selfcareStore, ids selfcareIDs) SelfcareResponse`
  - `func (s *DCASServer) handleSelfcareMessage(sessionID string, data []byte, conn *websocket.Conn)` — unmarshal, `caller := s.flightCaller(sessionID)`, build, then write to `conn` if non-nil else `s.sendToSession(sessionID, "1000", resp)`; log one line per action.

- [ ] **Step 1: Write the failing tests** in `selfcare_198_test.go` using a fresh store and fixed ids (`Txn: "GD-TEST01"`, `Ticket: "PA-99999"`, `Today: "26/11"`):

```go
func TestSelfcareProfileSeed(t *testing.T) // action "profile", caller "1001": Status ok; Account == {MSISDN:"1001", Balance:148500, Expiry:"30/12/2026", Package:"MAX90", DataLeftMB:3200, DataTotalMB:5000, BillMonth:"11", BillTotal:86400}; Packages codes == [SPEED120 MAX90 DAY10]; Reports == [{PA-20431 Mất sóng 18/11 Đã xong}]
func TestSelfcareSubscribeOK(t *testing.T) // subscribe DAY10: Txn "GD-TEST01"; Account.Balance 138500, Package "DAY10", DataLeftMB 2000, DataTotalMB 2000, BillTotal 96400; Receipt.Title "Đăng ký thành công"; Receipt rows contain {"Gói","DAY10 · 2 GB · 24 giờ"} and {"Đã trừ","10.000đ"} and {"Mã giao dịch","GD-TEST01"}
func TestSelfcareSubscribeInsufficient(t *testing.T) // subscribe SPEED120 twice (148500→28500, then 28500<120000): second Status "error", Code "insufficient_balance", Message "Số dư không đủ, vui lòng nạp thêm"; profile afterwards still Balance 28500, Package "SPEED120"
func TestSelfcareSubscribeExactBalance(t *testing.T) // set st's account for "1001" to Balance 10000 directly (same package), subscribe DAY10 → ok, Balance 0; subscribe DAY10 again → insufficient_balance. Then topup 100000, subscribe DAY10 again → ok (re-subscribing the current package is allowed)
func TestSelfcareSubscribeUnknown(t *testing.T) // code "XYZ" → error "unknown_package"; balance unchanged
func TestSelfcareTopup(t *testing.T) // amount 100000 → ok, Balance 248500, BillTotal 86400, Receipt.Title "Nạp tiền thành công", row {"Số tiền","100.000đ"}; amount 70000 → error "bad_amount"
func TestSelfcareReportValidation(t *testing.T) // table: missing issue, issue "Khác", location "   ", → error "missing_field"; valid {Data chậm, "12 Trần Thái Tông", ""} → ok, Ticket "PA-99999", Reports[0] == {PA-99999 Data chậm 26/11 Đang xử lý}, len 2
func TestSelfcareAccountsArePerCaller(t *testing.T) // caller "1001" subscribes DAY10; caller "1002" profile still Balance 148500; "1001" profile Balance 138500
func TestSelfcareUnknownAction(t *testing.T) // action "ask_time" → Status "error", Code "unknown_action"
```

Add `func TestFormatVND(t *testing.T)` for `formatVND(148500) == "148.500đ"`, `formatVND(0) == "0đ"`, `formatVND(1200000) == "1.200.000đ"`.

- [ ] **Step 2: Run to see them fail**

Run: `cd go-mf && go test ./internal/dcas/ -run 'Selfcare|FormatVND' -v`
Expected: FAIL — `undefined: buildSelfcareReply` (build error).

- [ ] **Step 3: Implement `selfcare_198.go`** with the types above, `formatVND(n int) string`, the store (`map[string]*selfcareAccountState` + `sync.Mutex`; state = `SelfcareAccount` + `[]SelfcareReport` newest first; created from the seed on first access), and `buildSelfcareReply`. Every reply carries `Account` (copy) and `Reports`; `profile` also carries `Packages`. Subscribe receipt rows: Gói (`<code> · <desc>`), Đã trừ, Số dư còn lại, Mã giao dịch. Top-up receipt rows: Số tiền, Số dư mới, Mã giao dịch. Trim `location`/`note`. Empty caller → `"guest"`.

- [ ] **Step 4: Run tests**

Run: `cd go-mf && go test ./internal/dcas/ -run 'Selfcare|FormatVND' -v`
Expected: PASS, all listed tests.

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/dcas/selfcare_198.go go-mf/internal/dcas/selfcare_198_test.go
git commit -m "DCAS: 198 self-care accounts (balance, packages, top-up, reports)"
```

### Task 2: Route smart-menu to the new handler, remove old Smart Menu

**Files:**
- Modify: `go-mf/internal/dcas/dcas.go` (`MenuAction`/`MenuResponse` ~105-111 if unused elsewhere, `SmartMenuResponse` ~385-393, `handleSmartMenuMessage` ~432-532, `triggerVideo` ~534-560, `processMdc2Payload` ~238-268)
- Test: `go-mf/internal/dcas/selfcare_198_test.go`

**Interfaces:**
- Consumes: `handleSelfcareMessage(sessionID string, data []byte, conn *websocket.Conn)` (Task 1).
- Produces: `func smartMenuRoute(data []byte) string` returning one of `"caro"`, `"enterprise-callerid"`, `"flight-booking"`, `"relay"` (type draw/clear), `"selfcare"`, `"drop"`; `handleSmartMenuMessage` switches on it.

- [ ] **Step 1: Write the failing test**

```go
func TestSmartMenuRoutingLeavesOtherServices(t *testing.T) {
	cases := map[string]string{
		`{"service":"smart-menu","action":"profile"}`:      "selfcare",
		`{"service":"caro","action":"move"}`:               "caro",
		`{"service":"flight-booking","action":"search"}`:   "flight-booking",
		`{"service":"enterprise-callerid"}`:                "enterprise-callerid",
		`{"type":"draw","x":1}`:                            "relay",
		`{"type":"clear"}`:                                 "relay",
		`{"action":"heart"}`:                               "drop",
		`{"service":"whatever","action":"x"}`:              "drop",
		`not json`:                                         "drop",
	}
	for in, want := range cases {
		if got := smartMenuRoute([]byte(in)); got != want { t.Errorf("%s: got %q want %q", in, got, want) }
	}
}
```

- [ ] **Step 2: Run** `cd go-mf && go test ./internal/dcas/ -run SmartMenuRouting -v` — Expected: FAIL, `undefined: smartMenuRoute`.

- [ ] **Step 3: Implement** `smartMenuRoute`; rewrite `handleSmartMenuMessage` to switch on it, keeping the existing draw/clear relay body verbatim under `"relay"` and logging `"[DCAS] Dropping stream-1000 payload with no smart-menu service"` for `"drop"`. In `processMdc2Payload`, the `"smart-menu"` case calls `s.handleSelfcareMessage(sessionID, payload, nil)`; leave the other cases. Delete the old action switch, `SmartMenuResponse`, `triggerVideo`, and `MenuAction`/`MenuResponse` if `grep -rn` shows no other users; drop now-unused imports.

- [ ] **Step 4: Run all Go tests**

Run: `cd go-mf && go vet ./... && go test ./...`
Expected: vet clean, all packages `ok`.

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/dcas/dcas.go go-mf/internal/dcas/selfcare_198_test.go
git commit -m "DCAS: route 198 smart-menu to self-care, drop old Smart Menu actions"
```

### Task 3: The 198 page

**Files:**
- Modify (rewrite): `go-mf/static/apps/smart-menu/index.html`
- Modify: `go-mf/static/apps/smart-menu/app.json` (`version` `"2.0.0"`, `appName` `"Tổng đài 198 – Tự phục vụ"`)
- Delete: `go-mf/static/apps/smart-menu/cartoon_video.mp4`, `sample_video.mp4`, `agent_greeting.mp3`
- Test (scratch, not committed): `<scratchpad>/check198.js`

**Interfaces:**
- Consumes: wire format of Task 1 (`SelfcareResponse` JSON).
- Produces: globals `window.receiveData(json)`, `window.onDataChannelMessage(json)`, `window.resetSmartMenu()`.

- [ ] **Step 1: Write the Playwright check** `check198.js` (390×844, `file://` page). Before load, `addInitScript` installs a fake `window.AndroidBridge.sendData(json)` that answers after 50 ms via `window.receiveData` with replies from a tiny JS port of the seed/rules (profile, subscribe, topup, report). Assertions:
  - Home shows `148.500đ`, `3,2 / 5 GB` area, three service rows.
  - Packages → select DAY10 → button text `Đăng ký DAY10 · 10.000đ` → Success shows `Đăng ký thành công` and `GD-` code → "Làm việc khác" → Home shows `138.500đ`.
  - Bill shows `96.400đ`, 100.000đ preselected → "Nạp 100.000đ" → Success `Nạp tiền thành công` → Home `238.500đ`.
  - Network: submit with no issue → button disabled or toast; pick "Mất sóng", type location, submit → toast contains `PA-`, list has 2 entries.
  - `window.resetSmartMenu()` from Bill → Home.
  - "no reply": set fake bridge to silent, tap Đăng ký → after 8.5 s toast `Không nhận được phản hồi, thử lại` and button enabled.
  - Save screenshots of each screen to the scratchpad.

- [ ] **Step 2: Run** `node <scratchpad>/check198.js go-mf/static/apps/smart-menu/index.html` — Expected: FAIL (old page has none of these).

- [ ] **Step 3: Rewrite `index.html`** per spec "Page" section and the approved canvas: screens `home|packages|success|bill|network` rendered from a single `state` object; header on sub-screens = back button (44 px) + title; sticky footer action button; skeleton on Home until first `profile` reply; in-flight flag disables action buttons; 8 s timeout toast; `insufficient_balance` toast with "Nạp tiền" link to Bill. No emoji icons (inline stroke SVG). `body` background `#F6F7F9`, fluid height, `.main` scrolls.

- [ ] **Step 4: Run** the check — Expected: all assertions pass; look at the screenshots against the canvas.

- [ ] **Step 5: Commit**

```bash
git add -A go-mf/static/apps/smart-menu
git commit -m "198 page: self-service app (packages, top-up, network report)"
```

### Task 4: Docs

**Files:**
- Modify: `README.md` (section "K. Dịch vụ Tổng Đài … 198" ~line 728), `CHANGELOG.md` (new top entry), `CONTEXT.md` (section 2 status line), `.antigravity/MEMORY.md` (append session note)

- [ ] **Step 1:** Rewrite README section K to describe the self-service app (4 functions, actions table, per-subscriber in-memory state, no agent). CHANGELOG entry lists Go/page changes, deleted media, and marks stale device scripts: `test_smart_menu_198.py`, `test_tap_smartmenu.py`, `test_smartmenu_cartoon_video.py`, `test_smartmenu_refresh_and_back.py`, `test_funny_cartoon_1080p.py`, `test_interactive_video_proof.py`, `test_agent_voice_playback.py`, section 5 of `test_all_services_e2e_regression.py`.
- [ ] **Step 2:** `cd go-mf && go test ./...` — Expected: all `ok`.
- [ ] **Step 3: Commit and push**

```bash
git add README.md CHANGELOG.md CONTEXT.md .antigravity/MEMORY.md
git commit -m "Docs: 198 self-service app"
git push -u origin claude/peaceful-cray-xljxny
```
