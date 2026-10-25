# Caro Mini-App Demo Upgrade — Design

- **Date:** 2026-10-25
- **Status:** Approved in brainstorming, pending spec review
- **Goal:** Make the Caro mini-app look and feel polished for customer/management demos of 5G New Calling (IMS DC). Gameplay rules stay the same (9x9 board, 5 in a row).

## 1. Scope

### In scope
1. **Layout:** remote video stays clearly visible during the game (board in the lower part of the screen).
2. **Move feedback:** piece pop animation, sound, vibration; visible feedback for rejected moves.
3. **Win celebration:** animated winning line, confetti for the winner, result card.
4. **Turn timer:** 20 s per turn, enforced by the server; on timeout the turn passes to the opponent.
5. **Bug fix:** the garbled (mojibake) Vietnamese error strings in `caro_game.go`.

### Out of scope
- Android/Kotlin changes (layout XML, native haptics, new bridges).
- Rule changes: board size, blocked-both-ends rule, alternating first player, rematch flow, AI opponent.
- New mini-app IDs, connection policy (stays P2A2P via DCAS) or DCAR changes.

### Files touched
| File | Change |
|---|---|
| `go-mf/static/apps/caro/index.html` | Layout, feedback, celebration, timer UI |
| `android-client/app/src/main/assets/miniapps/caro/index.html` | Identical copy of the above (bundled fallback) |
| `go-mf/internal/dcas/caro_game.go` | Timer, new message fields, fixed error strings, pure helper functions |
| `go-mf/internal/dcas/dcas.go` | `caroTimers` field + init in `NewDCASServer` |
| `go-mf/internal/dcas/caro_game_test.go` | New unit tests |

## 2. Layout (CSS only)

The WebView spans between the native top bar and the bottom call controls; the native local-preview (90x125dp) floats top-left. Neither changes.

```
┌────────────────────────────┐  ← native top bar
│ [you]                      │  native local preview
│        REMOTE FACE         │  top ~40%: fully transparent
│   ( X  2 )  vs  ( 1  O )   │  score pills (active one has timer ring)
│      Lượt của bạn · 14s     │  turn text
├────────────────────────────┤
│  ┌──────────────────────┐  │  bottom ~60%: frosted glass panel
│  │      9x9 board       │  │
│  └──────────────────────┘  │
│        [ Ván mới ]          │
└────────────────────────────┘  ← native call controls
```

- Remove the full-screen `rgba(0,0,0,0.75)` + blur background from `#game-container`; page background is transparent.
- Bottom panel: rounded top corners, ~55% dark glass with `backdrop-filter: blur`.
- Cells: ~35% opacity (was 90%); X/O glyphs get a glow (`text-shadow`) for contrast.
- Board size: `min(92vw, available panel height)`; no scrolling in the WebView area of ~360x490 CSS px (Galaxy S24, between the native top bar and call controls). The clear video area height is a CSS variable `--video-gap` (default `25vh`) to tune on device.
- Score pills sit at the bottom edge of the video area with a text shadow and a small dark backing for legibility on bright video.

## 3. Move feedback

Moves remain **server-authoritative**, but the existing optimistic placement is kept: your own piece is drawn (with pop, sound and vibration) as soon as you tap. The client remembers the last board confirmed by DCAS and rolls back to it if the server answers with `status: "error"`.

**On own tap (optimistic) and on `update_board` with the opponent's `last_move`:**
- Newly placed glyph animates scale 0 → 1.15 → 1 (~200 ms); last-move ring fades in.
- Sound: short synthesized "tock" via Web Audio API (no audio files). Own move uses a slightly higher pitch than the opponent's move.
- Vibration: `navigator.vibrate(25)` for own move. (`VIBRATE` permission already exists in `AndroidManifest.xml`.)
- Web Audio context is created lazily on the first tap; WebView already sets `mediaPlaybackRequiresUserGesture = false`.

**Rejected moves:**
- Client handles `status: "error"` (today it is silently ignored): board shake (~300 ms), bottom toast with the server's `message`, `navigator.vibrate([30, 60, 30])`, and rollback of the optimistic piece and turn to the last server state.
- Client-side pre-check: tapping when it is not your turn, or on an occupied cell, shows the same toast/shake without sending to the server.
- Server error strings rewritten in proper UTF-8 Vietnamese:

| Case | Text |
|---|---|
| Game over | `Ván đã kết thúc. Bấm 'Ván mới' để chơi lại.` |
| Board full of players | `Bàn cờ đã đủ 2 người chơi.` |
| Not your turn | `Chưa đến lượt của bạn.` |
| Invalid position | `Vị trí không hợp lệ.` |
| Occupied cell | `Ô này đã có quân.` |

## 4. Win celebration

Triggered by `update_board` with non-empty `winner` (or `draw: true`).

1. **Winning line:** an SVG line overlaid on the board, drawn across the winning cells with a stroke-dash animation (~400 ms). The server's `win_cells` are not ordered (forward cells then backward cells), so the client sorts them by row, then column, and uses the first and last cell centres (this gives the two ends for every straight direction, including the anti-diagonal).
2. **Confetti:** ~2.5 s canvas particle effect (~120 particles, ~40 lines of code), **winner's phone only**.
3. **Result card** (existing `#winner-overlay`, restyled), shown 600 ms after the line:
   - Winner: "🎉 Bạn thắng!" + score, `navigator.vibrate([40, 60, 40, 60, 40])`.
   - Loser: "Bạn thua 😅" in a calmer style, no confetti.
   - Draw: "🤝 Hòa".
   - Keeps the existing "Ván mới" button (sends `new_game`).

Everything stays inline in `index.html`; no external libraries.

## 5. Turn timer (server-enforced)

### 5.1 State
Added to `CaroGame` (persisted in Redis as part of `caro:game:<callID>`):

```go
TurnSeq       int   // incremented on every turn change
TurnStartedAt int64 // unix milliseconds when the current turn began
```

Package-level, overridable in tests:

```go
var CaroTurnTimeout = 20 * time.Second
```

Added to `DCASServer` (initialised in `NewDCASServer` in `dcas.go`):

```go
caroTimers map[string]*time.Timer // callID -> pending turn timer, guarded by caroMu
```

### 5.2 Behaviour
- `armCaroTimer(callID string, seq int, d time.Duration)` stops any existing timer for `callID` and schedules `time.AfterFunc(d, func() { s.caroOnTimeout(callID, seq) })`. Caller must hold `caroMu`.
- Armed (with full `CaroTurnTimeout`, after setting `TurnStartedAt = now`):
  - after `new_game`;
  - after a valid move that does not end the game.
- Re-armed (with `caroRemaining(game, now)`) on `sync_state` and late-join when `Status == "playing"` and no timer exists for the call — this recovers timers after a DCAS restart.
- `caroOnTimeout(callID, seq)`:
  1. Lock `caroMu`; load game from Redis.
  2. If the game key is missing/empty (removed by `removeSessionFromRedis` when the last session leaves) or no active sessions remain (`getAllCallSessions` empty): delete the timer entry and return. This is the only cleanup path for timers of ended calls (see 5.2 note).
  3. Call `applyCaroTimeout(&game, seq, now)`; if it returns false, return.
  4. Save game; broadcast timeout `update_board` (5.3) on streams `1000` and `1001` like existing broadcasts; arm the next timer with full timeout.
- Timer is stopped and removed on win and draw.
- **Not** stopped from the session cleanup in `dcas.go` (~lines 703-718): that code runs under `s.mu`, while Caro handlers take `caroMu` then `s.mu`; taking `caroMu` there would invert the lock order and risk deadlock. Instead a pending timer for an ended call fires at most once more and removes itself (step 2 above).
- A rejected move does not touch the timer.

### 5.3 Pure helpers (no Redis / network)

```go
// applyCaroTimeout switches the turn if seq still matches and the game is playing.
// Returns false (no change) when the game is over or seq is stale.
func applyCaroTimeout(game *CaroGame, seq int, now time.Time) bool

// caroRemaining returns time left in the current turn, clamped to [1s, CaroTurnTimeout].
func caroRemaining(game *CaroGame, now time.Time) time.Duration
```

`applyCaroTimeout` on success: `Turn` flips X↔O, `TurnSeq++`, `TurnStartedAt = now` (ms).

### 5.4 Protocol changes (additive, backwards compatible)
- `game_start`, `update_board` and the `sync_state` response gain `turn_ms_left` (int, ms).
- Timeout broadcast is an `update_board` with:
  ```json
  {"service":"caro","status":"update_board","board":[...],"next_turn":"O",
   "move_count":12,"score_x":1,"score_o":0,"winner":"","draw":false,
   "timeout":true,"timed_out":"X","turn_ms_left":20000}
  ```
  No `last_move` field.
- Old clients ignore the new fields; the current client's `renderBoard(board, undefined)` already handles a missing last move.

### 5.5 Client timer UI
- SVG ring around the active player's score pill; counts down locally from `turn_ms_left` using `performance.now()` (no dependency on device clock vs server clock).
- Colour: green → amber at ≤10 s → red at ≤5 s; soft tick sound each second for the final 3 s.
- Each `update_board` / `game_start` resets the ring from its `turn_ms_left`; ring hidden when the game is over.
- On `timeout: true`: the timed-out player sees toast "⏰ Hết giờ! Bạn mất lượt"; the other sees "Đối thủ hết giờ — đến lượt bạn".
- The client never decides a timeout itself; at 0 it just waits for the server.

## 6. Error handling
- All new server paths log with the existing `[DCAS-CARO]` prefix.
- Redis read/parse failure inside `caroOnTimeout`: log and return without re-arming (next move or `sync_state` re-arms).
- Web Audio or `navigator.vibrate` unavailable: feature silently skipped (feature-detected, wrapped in try/catch).

## 7. Testing

### Go unit tests — `internal/dcas/caro_game_test.go`
- `applyCaroTimeout`: switches turn and increments seq; no-op on stale seq; no-op when status is `win_X` / `draw`.
- `caroRemaining`: normal case; already expired → 1 s; `TurnStartedAt` in the future → capped at `CaroTurnTimeout`.
- `checkCaroWin`: horizontal, vertical, both diagonals, 6 in a row, line touching the board edge, no win with 4.
- Error-string guard: all `sendCaroError` messages are valid UTF-8 and contain none of `Ã`, `Ä`, `á»`.
- Run: `go test ./internal/dcas/...` and `go vet ./internal/dcas/...`.

### Headless page check (Playwright, installed in `go-mf/test/caro_ui`)
- Load `caro/index.html` at 360x490, inject messages via `window.receiveData(...)`: `game_start` → move → `error` → timeout → win.
- Screenshot each step; assert toast visible, ring colour changes, SVG win line present, result card shown.
- Screenshots shared for layout review before device testing.

### Device check (2x Galaxy S24)
- Regression: `run_caro_proof.py`, `test_long_caro_play.py`.
- Manual: wait 20 s → turn passes on both phones; tap on opponent's turn → shake + toast; win a round → line, confetti (winner only), result card; remote face visible above the board throughout.

## 8. Deployment
1. `cd go-mf && docker build -t go-mf:local . && kind load docker-image go-mf:local --name ims-dc-cluster`
2. `kubectl rollout restart -n demo-mini-app deployment/dcas deployment/dcar`
3. Rebuild and reinstall the APK on both phones (bundled `caro/index.html` copy).
