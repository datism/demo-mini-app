# Hotline 198 – Self-service app (replaces Smart Menu)

Date: 2026-11-26 · Status: awaiting approval (builder-checked)

## Intent

Dialling **198** opens a self-service mini-app where the caller does everything
themselves during the call: see balance and data, register a data package, see this
month's charges and top up, and report a network problem. Nobody is transferred to an
agent. It replaces today's Smart Menu (agent greeting / cartoon video / system time)
entirely.

Why: demo the GSMA 5G New Calling "enterprise hotline" case where the network
answers the call and serves a visual self-service app over the IMS data channel
(P2A, ADC stream 1000), with DCAS acting as the operator's back office.

Visual design (approved): canvas "Tổng đài 198 – Call Center App"
https://claude.ai/artifact/8i453bhoyCm4XfeGqHVezt — screens Main, Packages,
Success, Bill, Network. Light, off-white `#F6F7F9`, ink `#111827`, one accent
`#D9480F`, Space Grotesk headings + Inter body. Not the SkyLotus/flight look.

## Goals

- 198 → new app, same app id `smart-menu` (so no routing / APK change).
- Data comes from DCAS over the data channel (approach A below); the page holds
  no business data of its own.
- State per caller survives screen changes and re-opening the app within the
  same DCAS run (balance drops after a package, rises after a top-up, reports list grows).

## Non-goals

- No agent / callback / transfer of any kind.
- No SIM & subscriber screen (dropped by the user).
- No real billing, payment or OTP; all data is mock, in memory, lost on DCAS restart.
- No GPS / cell-ID location; "Địa điểm" is a plain text field.
- No Android (Kotlin) changes, no APK rebuild.

## Anti-goals

- Breaking 1900 / flight-booking (it shares nothing with smart-menu: its
  "agent" button calls `startAgentCall()`, not the smart-menu greeting mp3).
- Breaking the draw/clear relay and caro / enterprise-callerid / flight-booking
  fall-through that currently lives at the top of `handleSmartMenuMessage`.

## Approaches considered

- **A. Page ↔ DCAS (chosen by user).** Page sends `{service:"smart-menu", action}`;
  a new Go handler answers from an in-memory per-caller account store. Same
  pattern as `flight_booking.go`. Real use of the data channel, consistent data.
- B. Page-only mock data. Fastest, but barely uses the data channel and resets every open.
- C. Hybrid (catalog in page, only commits go to DCAS). Data split in two places.

## Constraints (from the project)

- App served by DCAR from `go-mf/static/apps/smart-menu/` (`app.json`, `index.html`);
  `app.json` stays `appId: smart-menu`, policy `P2A`; bump `version` so phones
  re-download (BDC cache uses ETag / 304).
- Page transport is the same as other apps: send via
  `AndroidBridge.sendData(json)` → fallback `AndroidBridge.send(1000, json)` →
  `IMSDataChannelBridge.send(1000, json)`; receive via `window.receiveData` and
  `window.onDataChannelMessage`. Must expose `window.resetSmartMenu()` (Android
  calls it when the app is reset) — it returns the page to Main.
- Android shows smart-menu in the band under its own top bar (not full height), and
  hides local video. The page must be fluid in height and scroll; it does not draw
  its own call timer (the call screen already shows call status).
- No external network on the cluster (air-gapped): no Google Fonts at runtime —
  use a system font stack with "Space Grotesk"/"Inter" first; ship no font files.
- DCAS knows the caller via `s.flightCaller(sessionID)` (reads IMS-AS session);
  falls back to `"guest"`.

## Design

### Page (`static/apps/smart-menu/index.html`, single file, vanilla JS)

One page, screens switched in JS (same idea as flight-booking, own code and styling):
`home`, `packages`, `success`, `bill`, `network`, plus a small `toast` for errors.
On load and on `resetSmartMenu()` it sends `profile` and shows Home with a
skeleton until the reply arrives.

- **Home**: "198 · Dịch vụ tự phục vụ", caller number, balance + expiry, data left / total with bar,
  3 rows → Packages, Bill, Network.
- **Packages**: 3 packages from `packages` reply, radio select, footer button
  "Đăng ký <code> · <price>" → `subscribe`. On `insufficient_balance` show toast
  "Số dư không đủ, vui lòng nạp thêm" with a link to Bill.
- **Success**: generic receipt (title, rows of label/value, transaction code), used for
  both subscribe and top-up; buttons "Làm việc khác" (→ Home, re-send `profile`).
  The design's "Kết thúc cuộc gọi" button is dropped (no hang-up bridge for pages).
- **Bill**: month total from `profile`, 3 amounts (50k/100k/200k, 100k preselected),
  button "Nạp <amount>" → `topup` → Success.
- **Network**: issue chips (Data chậm / Mất sóng / Rớt cuộc gọi / Không gửi được SMS,
  one required), location text (required), note (optional), "Gửi phản ánh" → `report`;
  on success show toast "Đã gửi phản ánh #PA-xxxxx" and prepend to the previous-reports list
  (from `reports` in the `profile` reply).

Buttons disable while a request is in flight; 8 s without reply → toast
"Không nhận được phản hồi, thử lại" and re-enable.

### Messages (ADC 1000, JSON)

Requests: `{"service":"smart-menu","action":<a>, ...}`

| action | fields | reply (always `service:"smart-menu"`, `action`, `status`) |
|---|---|---|
| `profile` | – | `account` (see below), `packages`, `reports` |
| `subscribe` | `code` | `ok`: `txn`, `account`, `receipt`; `error` + `code:"insufficient_balance"\|"unknown_package"` + `message` |
| `topup` | `amount` (50000/100000/200000) | `ok`: `txn`, `account`, `receipt`; `error` + `code:"bad_amount"` |
| `report` | `issue`, `location`, `note` | `ok`: `ticket`, `reports`; `error` + `code:"missing_field"` |
| other | – | `error`, `code:"unknown_action"` |

`account`: `{msisdn, balance, expiry:"30/12/2026", data_left_mb, data_total_mb,
package, bill_month, bill_total}`.
`packages`: `[{code,name,desc,price}]` = SPEED120 (4 GB/ngày · 30 ngày, 120.000),
MAX90 (5 GB/tháng · 30 ngày, 90.000), DAY10 (2 GB · 24 giờ, 10.000).
`receipt`: `{title, rows:[{label,value}]}` so the page renders it as is.
`reports`: newest first, `{ticket, issue, sent, status}`.

### Backend (`internal/dcas/selfcare_198.go` + `_test.go`)

- New account store keyed by caller number (mutex + map), new account seeded with:
  balance 148.500đ, package MAX90, data 3.200/5.000 MB, bill 86.400đ, one old report
  `#PA-20431 · Mất sóng · Đã xong`.
- `subscribe`: price ≤ balance → deduct, add price to `bill_total` (it is a
  charge this month), set package, reset data to the package's current allowance
  (SPEED120 4 096 MB — today's quota; MAX90 5 120 MB; DAY10 2 048 MB), txn
  `GD-XXXXXX`; else `insufficient_balance` (no change). Re-subscribing the
  current package is allowed (renews it). `expiry` never changes. *(my calls)*
- `topup`: add amount to `balance` only (bill unchanged), txn.
- `report`: ticket `PA-NNNNN`, status "Đang xử lý".
- Pure `buildSelfcareReply(req, caller, store, idGen)` for tests, thin
  `handleSelfcareMessage(sessionID, data)` that resolves the caller and sends.
- Routing in `dcas.go` *(my call)*: only messages with `service:"smart-menu"`
  reach the new handler (from `processMdc2Payload` and from the stream-1000
  WebSocket path). In `handleSmartMenuMessage` the draw/clear relay and the
  caro / enterprise-callerid / flight-booking fall-throughs stay; after them,
  `service:"smart-menu"` → new handler; anything else (no service, unknown
  service, unparseable) is logged and dropped — no reply, as before for
  unparseable. The old switch (video/story/time/agent/balance), `MenuAction`,
  `SmartMenuResponse` and `triggerVideo` are removed if nothing else uses them.
  The UE simulator's `ask_time` then gets `unknown_action` (fine; it only checks the channel).

### Cleanup

- Delete `static/apps/smart-menu/cartoon_video.mp4`, `sample_video.mp4` and
  `agent_greeting.mp3` (only the old page used them; Android's
  `playAgentVoiceAudio` already skips when the file is missing and the new page
  never calls it). Android code is left as is.
- Old root-level device scripts for the old Smart Menu (`test_smart_menu_198.py`,
  `test_tap_smartmenu.py`, `test_smartmenu_*`, `test_funny_cartoon_1080p.py`,
  `test_interactive_video_proof.py`, `test_agent_voice_playback.py`, section 5 of
  `test_all_services_e2e_regression.py`) target removed UI. They need real phones,
  so they are left untouched and listed as stale in CHANGELOG/CONTEXT; not deleted.
- Update README section K, CHANGELOG, CONTEXT.md status.

## Testing

- Go: table tests for `buildSelfcareReply` (profile seed, subscribe ok / insufficient /
  unknown, topup ok / bad amount, report ok / missing field, per-caller isolation,
  unknown action); existing `go test ./...` stays green.
- Page: Playwright on the file with a fake `AndroidBridge` that answers like DCAS;
  walk Home → Packages → Success, Bill → Success, Network submit; screenshots at 390 px.
- Real phones (S24) are not available here; on-device check is left to the user.

## Left to the builder

Exact markup/CSS of the page within the approved look; Go type names; error copy beyond what's listed.
