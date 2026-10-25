# Caro Demo Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Caro mini-app demo-ready: remote video visible above the board, move/error feedback, win celebration, and a 20 s server-enforced turn timer.

**Architecture:** Server logic stays in DCAS (`go-mf/internal/dcas/caro_game.go`), which owns the game state in Redis and now also owns one in-memory `time.Timer` per call. Pure helpers (no Redis/network) carry the rules so they can be unit-tested. The client is the single self-contained page `go-mf/static/apps/caro/index.html` (copied verbatim into the Android assets), verified with a headless Playwright script that feeds it fake DCAS messages.

**Tech Stack:** Go 1.21 (stdlib + existing vendored deps), plain HTML/CSS/JS (Web Audio, Vibration API, SVG, Canvas), Node 22 + Playwright for the headless page check.

**Spec:** `docs/superpowers/specs/2026-10-25-caro-demo-upgrade-design.md`

## Global Constraints

- No Android/Kotlin/layout XML changes. Only `caro/index.html` (both copies), `caro_game.go`, `dcas.go`, new tests, test tooling, `.gitignore`, `CHANGELOG.md`.
- `index.html` stays a single file: no external libraries, fonts, images or audio files.
- Board size stays 9×9, win = 5 in a row; connection policy stays P2A2P; no new app IDs.
- Turn timeout: **20 s** (`CaroTurnTimeout = 20 * time.Second`); on timeout the turn passes to the opponent.
- Protocol changes are additive only: new fields `turn_ms_left`, `timeout`, `timed_out`.
- Keep existing log lines, comments and error handling; new server logs use the `[DCAS-CARO]` prefix.
- Do not hardcode IPs/ports. Do not `git push`.
- All Vietnamese UI/server text must be valid UTF-8, exactly as written in this plan.
- Run Go commands from `go-mf/` (module `github.com/demo/ims-dc-mf`, vendored deps). Baseline: `go test ./internal/dcas/` passes.
- Headless viewport: **360×490** CSS px (the WebView area on Galaxy S24 between the native top bar and call controls).

## File Structure

| File | Responsibility |
|---|---|
| `go-mf/internal/dcas/caro_game.go` | Caro rules, Redis persistence, broadcasts; gains error-text consts, turn fields, pure timer helpers, timer wiring |
| `go-mf/internal/dcas/dcas.go` | `DCASServer` struct/constructor; gains `caroTimers` + `caroTimeoutFn` fields |
| `go-mf/internal/dcas/caro_game_test.go` (new) | Unit tests for win detection, error texts, timer helpers, timer arming |
| `go-mf/static/apps/caro/index.html` | The Caro page (layout, feedback, celebration, timer UI) |
| `android-client/app/src/main/assets/miniapps/caro/index.html` | Byte-identical copy of the page bundled in the APK |
| `go-mf/test/caro_ui/check_caro.js` (new) | Headless Playwright check; one `scenario(...)` per feature |
| `go-mf/test/caro_ui/package.json` (new) | Pins Playwright for the check |
| `.gitignore` | Ignore `node_modules/` and check screenshots |
| `CHANGELOG.md` | Release note |

---

### Task 1: Fix garbled error texts and cover win detection

**Files:**
- Modify: `go-mf/internal/dcas/caro_game.go` (consts after line 12; calls at lines 244, 263, 270, 276, 280)
- Create: `go-mf/internal/dcas/caro_game_test.go`

**Interfaces:**
- Produces: consts `caroErrGameOver`, `caroErrBoardFull`, `caroErrNotYourTurn`, `caroErrInvalidPos`, `caroErrOccupied` and `var caroErrorMessages []string`; test helper `boardWith(cells [][2]int, sym string) [CaroBoardSize][CaroBoardSize]string`.

- [ ] **Step 1: Write the failing tests**

Create `go-mf/internal/dcas/caro_game_test.go`:

```go
package dcas

import (
	"strings"
	"testing"
	"unicode/utf8"
)

func boardWith(cells [][2]int, sym string) [CaroBoardSize][CaroBoardSize]string {
	var b [CaroBoardSize][CaroBoardSize]string
	for _, c := range cells {
		b[c[0]][c[1]] = sym
	}
	return b
}

func TestCheckCaroWin(t *testing.T) {
	s := &DCASServer{}
	cases := []struct {
		name    string
		cells   [][2]int
		last    [2]int
		wantLen int
	}{
		{"horizontal", [][2]int{{4, 0}, {4, 1}, {4, 2}, {4, 3}, {4, 4}}, [2]int{4, 2}, 5},
		{"vertical", [][2]int{{0, 3}, {1, 3}, {2, 3}, {3, 3}, {4, 3}}, [2]int{0, 3}, 5},
		{"diagonal touching edge", [][2]int{{4, 4}, {5, 5}, {6, 6}, {7, 7}, {8, 8}}, [2]int{8, 8}, 5},
		{"anti-diagonal", [][2]int{{0, 8}, {1, 7}, {2, 6}, {3, 5}, {4, 4}}, [2]int{2, 6}, 5},
		{"six in a row", [][2]int{{8, 0}, {8, 1}, {8, 2}, {8, 3}, {8, 4}, {8, 5}}, [2]int{8, 3}, 6},
		{"four is not a win", [][2]int{{2, 2}, {2, 3}, {2, 4}, {2, 5}}, [2]int{2, 5}, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			b := boardWith(tc.cells, "X")
			got := s.checkCaroWin(&b, tc.last[0], tc.last[1], "X")
			if len(got) != tc.wantLen {
				t.Fatalf("len(winCells) = %d, want %d (%v)", len(got), tc.wantLen, got)
			}
		})
	}
}

func TestCaroErrorMessagesAreCleanUTF8(t *testing.T) {
	if len(caroErrorMessages) != 5 {
		t.Fatalf("expected 5 caro error messages, got %d", len(caroErrorMessages))
	}
	for _, m := range caroErrorMessages {
		if !utf8.ValidString(m) {
			t.Errorf("invalid UTF-8: %q", m)
		}
		for _, bad := range []string{"Ã", "Ä", "á»"} {
			if strings.Contains(m, bad) {
				t.Errorf("message %q contains mojibake sequence %q", m, bad)
			}
		}
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd go-mf && go test ./internal/dcas/ -run 'TestCheckCaroWin|TestCaroErrorMessages' -v`
Expected: build FAIL with `undefined: caroErrorMessages`.

- [ ] **Step 3: Add the consts and use them**

In `caro_game.go`, after `const CaroWinCount = 5` add:

```go

// Caro error texts sent to the client (shown as a toast).
const (
	caroErrGameOver    = "Ván đã kết thúc. Bấm 'Ván mới' để chơi lại."
	caroErrBoardFull   = "Bàn cờ đã đủ 2 người chơi."
	caroErrNotYourTurn = "Chưa đến lượt của bạn."
	caroErrInvalidPos  = "Vị trí không hợp lệ."
	caroErrOccupied    = "Ô này đã có quân."
)

var caroErrorMessages = []string{caroErrGameOver, caroErrBoardFull, caroErrNotYourTurn, caroErrInvalidPos, caroErrOccupied}
```

Then replace each whole `s.sendCaroError(sessionID, "...")` call in `caroMakeMove` (keep indentation; the old strings are mojibake so match by line):

| Old line (before this task's insert) | New call |
|---|---|
| 244 (`Game Ä‘Ã£ káº¿t thÃºc...`) | `s.sendCaroError(sessionID, caroErrGameOver)` |
| 263 (`BÃ n cá» Ä‘Ã£ Ä‘á»§...`) | `s.sendCaroError(sessionID, caroErrBoardFull)` |
| 270 (`ChÆ°a Ä‘áº¿n lÆ°á»£t...`) | `s.sendCaroError(sessionID, caroErrNotYourTurn)` |
| 276 (`Vá»‹ trÃ­ khÃ´ng...`) | `s.sendCaroError(sessionID, caroErrInvalidPos)` |
| 280 (`Ã” nÃ y Ä‘Ã£ cÃ³ quÃ¢n.`) | `s.sendCaroError(sessionID, caroErrOccupied)` |

Verify none are left: `grep -c 'Ã\|Ä' go-mf/internal/dcas/caro_game.go` → `0`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd go-mf && go test ./internal/dcas/ -v -run 'TestCheckCaroWin|TestCaroErrorMessages' && go vet ./internal/dcas/`
Expected: all PASS, vet silent.

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/dcas/caro_game.go go-mf/internal/dcas/caro_game_test.go
git commit -m "fix(caro): replace mojibake error texts and add win detection tests"
```

---

### Task 2: Turn state fields and pure timer helpers

**Files:**
- Modify: `go-mf/internal/dcas/caro_game.go` (`CaroGame` struct; new helpers after `boardToSlice`)
- Test: `go-mf/internal/dcas/caro_game_test.go`

**Interfaces:**
- Produces:
  - `var CaroTurnTimeout = 20 * time.Second`
  - `CaroGame.TurnSeq int`, `CaroGame.TurnStartedAt int64` (unix ms)
  - `func startCaroTurn(game *CaroGame, now time.Time)` — `TurnSeq++`, `TurnStartedAt = now.UnixMilli()`
  - `func applyCaroTimeout(game *CaroGame, seq int, now time.Time) bool`
  - `func caroRemaining(game *CaroGame, now time.Time) time.Duration` — clamped to `[1s, CaroTurnTimeout]`
  - `func caroTurnMsLeft(game *CaroGame, now time.Time) int64` — `0` unless `Status == "playing"`

- [ ] **Step 1: Write the failing tests**

Append to `caro_game_test.go` (add `"time"` to the imports):

```go
func TestStartCaroTurn(t *testing.T) {
	now := time.UnixMilli(1_700_000_000_000)
	g := &CaroGame{TurnSeq: 3}
	startCaroTurn(g, now)
	if g.TurnSeq != 4 || g.TurnStartedAt != now.UnixMilli() {
		t.Fatalf("got seq=%d startedAt=%d", g.TurnSeq, g.TurnStartedAt)
	}
}

func TestApplyCaroTimeout(t *testing.T) {
	now := time.UnixMilli(1_700_000_000_000)

	g := &CaroGame{Status: "playing", Turn: "X", TurnSeq: 5}
	if !applyCaroTimeout(g, 5, now) {
		t.Fatal("expected timeout to apply")
	}
	if g.Turn != "O" || g.TurnSeq != 6 || g.TurnStartedAt != now.UnixMilli() {
		t.Fatalf("after timeout: turn=%s seq=%d startedAt=%d", g.Turn, g.TurnSeq, g.TurnStartedAt)
	}

	stale := &CaroGame{Status: "playing", Turn: "X", TurnSeq: 6}
	if applyCaroTimeout(stale, 5, now) || stale.Turn != "X" || stale.TurnSeq != 6 {
		t.Fatal("stale seq must not change the game")
	}

	for _, status := range []string{"win_X", "win_O", "draw"} {
		over := &CaroGame{Status: status, Turn: "X", TurnSeq: 5}
		if applyCaroTimeout(over, 5, now) || over.Turn != "X" {
			t.Fatalf("status %s must not change the game", status)
		}
	}
}

func TestCaroRemaining(t *testing.T) {
	now := time.UnixMilli(1_700_000_000_000)
	g := &CaroGame{TurnStartedAt: now.Add(-5 * time.Second).UnixMilli()}
	if got := caroRemaining(g, now); got != 15*time.Second {
		t.Fatalf("normal: got %v want 15s", got)
	}
	g.TurnStartedAt = now.Add(-60 * time.Second).UnixMilli()
	if got := caroRemaining(g, now); got != time.Second {
		t.Fatalf("expired: got %v want 1s", got)
	}
	g.TurnStartedAt = now.Add(30 * time.Second).UnixMilli()
	if got := caroRemaining(g, now); got != CaroTurnTimeout {
		t.Fatalf("future start: got %v want %v", got, CaroTurnTimeout)
	}
}

func TestCaroTurnMsLeft(t *testing.T) {
	now := time.UnixMilli(1_700_000_000_000)
	g := &CaroGame{Status: "playing", TurnStartedAt: now.Add(-2 * time.Second).UnixMilli()}
	if got := caroTurnMsLeft(g, now); got != 18000 {
		t.Fatalf("playing: got %d want 18000", got)
	}
	g.Status = "win_X"
	if got := caroTurnMsLeft(g, now); got != 0 {
		t.Fatalf("game over: got %d want 0", got)
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd go-mf && go test ./internal/dcas/ -run 'TestStartCaroTurn|TestApplyCaroTimeout|TestCaroRemaining|TestCaroTurnMsLeft'`
Expected: build FAIL with `undefined: startCaroTurn` (and the other helpers / fields).

- [ ] **Step 3: Implement**

In `caro_game.go`, after the `caroErrorMessages` var add:

```go

// CaroTurnTimeout is how long a player has to move before the turn passes
// to the opponent. A var so tests can shorten it.
var CaroTurnTimeout = 20 * time.Second
```

Add to the end of the `CaroGame` struct (after `WinCells  [][2]int`):

```go
	TurnSeq       int   // incremented on every turn change; guards stale timers
	TurnStartedAt int64 // unix ms when the current turn began
```

After `func (s *DCASServer) boardToSlice(...)` add:

```go
// startCaroTurn marks the beginning of a new turn.
func startCaroTurn(game *CaroGame, now time.Time) {
	game.TurnSeq++
	game.TurnStartedAt = now.UnixMilli()
}

// applyCaroTimeout passes the turn to the opponent if seq still matches the
// current turn and the game is in progress. Returns false when nothing changed.
func applyCaroTimeout(game *CaroGame, seq int, now time.Time) bool {
	if game.Status != "playing" || game.TurnSeq != seq {
		return false
	}
	if game.Turn == "X" {
		game.Turn = "O"
	} else {
		game.Turn = "X"
	}
	startCaroTurn(game, now)
	return true
}

// caroRemaining returns the time left in the current turn, clamped to
// [1s, CaroTurnTimeout] so a recovered timer never fires instantly or late.
func caroRemaining(game *CaroGame, now time.Time) time.Duration {
	left := CaroTurnTimeout - now.Sub(time.UnixMilli(game.TurnStartedAt))
	if left < time.Second {
		return time.Second
	}
	if left > CaroTurnTimeout {
		return CaroTurnTimeout
	}
	return left
}

// caroTurnMsLeft is the turn_ms_left value sent to clients (0 when the game is over).
func caroTurnMsLeft(game *CaroGame, now time.Time) int64 {
	if game.Status != "playing" {
		return 0
	}
	return caroRemaining(game, now).Milliseconds()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd go-mf && go test ./internal/dcas/ -v && go vet ./internal/dcas/`
Expected: all PASS (including Task 1 and the existing `TestImsEENotification`).

- [ ] **Step 5: Commit**

```bash
git add go-mf/internal/dcas/caro_game.go go-mf/internal/dcas/caro_game_test.go
git commit -m "feat(caro): add turn sequence state and pure timer helpers"
```

---

### Task 3: Wire the server-side turn timer

**Files:**
- Modify: `go-mf/internal/dcas/dcas.go:55-68` (struct), `:86-98` (constructor)
- Modify: `go-mf/internal/dcas/caro_game.go` (`caroNewGame`, `checkLateJoinCaro`, `caroMakeMove`, `caroSyncState`; new timer methods)
- Test: `go-mf/internal/dcas/caro_game_test.go`

**Interfaces:**
- Consumes: `startCaroTurn`, `applyCaroTimeout`, `caroRemaining`, `caroTurnMsLeft`, `CaroTurnTimeout` (Task 2).
- Produces (all require the caller to hold `s.caroMu`, except `caroOnTimeout` which takes it):
  - `func (s *DCASServer) armCaroTimer(callID string, seq int, d time.Duration)`
  - `func (s *DCASServer) stopCaroTimer(callID string)`
  - `func (s *DCASServer) caroEnsureTimer(callID string, game *CaroGame, now time.Time)`
  - `func (s *DCASServer) caroOnTimeout(callID string, seq int)`
  - Fields `caroTimers map[string]*time.Timer`, `caroTimeoutFn func(callID string, seq int)` (nil → `s.caroOnTimeout`; tests override).
  - Messages: `turn_ms_left` (int ms) on `game_start` and `update_board`; timeout broadcast = `update_board` with `timeout: true`, `timed_out: "X"|"O"`, no `last_move`.

**Lock-order note:** Caro handlers take `caroMu`, then `s.mu` (via `getAllCallSessions`). Never take `caroMu` while holding `s.mu`. So timers are **not** stopped from the session cleanup in `dcas.go` (~lines 703-718); an orphaned timer fires once, finds the game gone, and removes itself.

- [ ] **Step 1: Write the failing tests**

Append to `caro_game_test.go`:

```go
func newTestCaroServer() *DCASServer {
	return &DCASServer{caroTimers: make(map[string]*time.Timer)}
}

func TestArmCaroTimerFiresWithSeq(t *testing.T) {
	s := newTestCaroServer()
	fired := make(chan int, 2)
	s.caroTimeoutFn = func(callID string, seq int) { fired <- seq }
	s.armCaroTimer("call1", 7, 10*time.Millisecond)
	select {
	case seq := <-fired:
		if seq != 7 {
			t.Fatalf("fired with seq %d, want 7", seq)
		}
	case <-time.After(time.Second):
		t.Fatal("timer did not fire")
	}
}

func TestArmCaroTimerReplacesPrevious(t *testing.T) {
	s := newTestCaroServer()
	fired := make(chan int, 2)
	s.caroTimeoutFn = func(callID string, seq int) { fired <- seq }
	s.armCaroTimer("call1", 1, 30*time.Millisecond)
	s.armCaroTimer("call1", 2, 10*time.Millisecond)
	time.Sleep(100 * time.Millisecond)
	close(fired)
	var got []int
	for seq := range fired {
		got = append(got, seq)
	}
	if len(got) != 1 || got[0] != 2 {
		t.Fatalf("fired %v, want only [2]", got)
	}
}

func TestStopCaroTimer(t *testing.T) {
	s := newTestCaroServer()
	fired := make(chan int, 1)
	s.caroTimeoutFn = func(callID string, seq int) { fired <- seq }
	s.armCaroTimer("call1", 1, 20*time.Millisecond)
	s.stopCaroTimer("call1")
	if _, ok := s.caroTimers["call1"]; ok {
		t.Fatal("timer entry not removed")
	}
	select {
	case <-fired:
		t.Fatal("stopped timer fired")
	case <-time.After(60 * time.Millisecond):
	}
}

func TestCaroEnsureTimer(t *testing.T) {
	now := time.Now()
	s := newTestCaroServer()
	s.caroTimeoutFn = func(string, int) {}

	over := &CaroGame{Status: "win_X", TurnSeq: 1, TurnStartedAt: now.UnixMilli()}
	s.caroEnsureTimer("call1", over, now)
	if _, ok := s.caroTimers["call1"]; ok {
		t.Fatal("must not arm a timer for a finished game")
	}

	playing := &CaroGame{Status: "playing", TurnSeq: 1, TurnStartedAt: now.UnixMilli()}
	s.caroEnsureTimer("call1", playing, now)
	first, ok := s.caroTimers["call1"]
	if !ok {
		t.Fatal("expected a timer for a game in progress")
	}
	s.caroEnsureTimer("call1", playing, now)
	if s.caroTimers["call1"] != first {
		t.Fatal("must keep an already pending timer")
	}
	s.stopCaroTimer("call1")
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd go-mf && go test ./internal/dcas/ -run 'CaroTimer|CaroEnsureTimer'`
Expected: build FAIL with `unknown field caroTimers in struct literal` / `undefined: armCaroTimer`.

- [ ] **Step 3: Add the fields**

In `dcas.go`, inside `type DCASServer struct`, after the `caroMu` line add:

```go
	caroTimers           map[string]*time.Timer     // call_id -> pending turn timer, guarded by caroMu
	caroTimeoutFn        func(callID string, seq int) // nil = s.caroOnTimeout; overridden in tests
```

In `NewDCASServer`, after `interworkingSessions: make(map[string]string),` add:

```go
		caroTimers:           make(map[string]*time.Timer),
```

(`time` is already imported in `dcas.go`.)

- [ ] **Step 4: Add the timer methods**

In `caro_game.go`, after `caroTurnMsLeft` add:

```go
// armCaroTimer (re)starts the turn timer for a call. Caller must hold caroMu.
func (s *DCASServer) armCaroTimer(callID string, seq int, d time.Duration) {
	if s.caroTimers == nil {
		s.caroTimers = make(map[string]*time.Timer)
	}
	s.stopCaroTimer(callID)
	fire := s.caroTimeoutFn
	if fire == nil {
		fire = s.caroOnTimeout
	}
	s.caroTimers[callID] = time.AfterFunc(d, func() { fire(callID, seq) })
}

// stopCaroTimer cancels the pending turn timer for a call. Caller must hold caroMu.
func (s *DCASServer) stopCaroTimer(callID string) {
	if t, ok := s.caroTimers[callID]; ok {
		t.Stop()
		delete(s.caroTimers, callID)
	}
}

// caroEnsureTimer re-arms the turn timer for a game in progress when none is
// pending (e.g. after a DCAS restart). Caller must hold caroMu.
func (s *DCASServer) caroEnsureTimer(callID string, game *CaroGame, now time.Time) {
	if game.Status != "playing" {
		return
	}
	if _, ok := s.caroTimers[callID]; ok {
		return
	}
	s.armCaroTimer(callID, game.TurnSeq, caroRemaining(game, now))
}

// caroOnTimeout runs when a player did not move within CaroTurnTimeout:
// the turn passes to the opponent and both players are notified.
func (s *DCASServer) caroOnTimeout(callID string, seq int) {
	s.caroMu.Lock()
	defer s.caroMu.Unlock()

	gameVal, err := getFromRedis("caro:game:" + callID)
	if err != nil {
		log.Printf("[DCAS-CARO] Turn timeout for CallID=%s: Redis read failed: %v", callID, err)
		return
	}
	sessions := s.getAllCallSessions(callID)
	if gameVal == "" || len(sessions) == 0 {
		log.Printf("[DCAS-CARO] Turn timeout for CallID=%s: game ended or no sessions, dropping timer", callID)
		s.stopCaroTimer(callID)
		return
	}

	var game CaroGame
	if err := json.Unmarshal([]byte(gameVal), &game); err != nil {
		log.Printf("[DCAS-CARO] Turn timeout for CallID=%s: parse game failed: %v", callID, err)
		return
	}

	timedOut := game.Turn
	if !applyCaroTimeout(&game, seq, time.Now()) {
		return // a move or new game already advanced the turn
	}

	gameBytes, _ := json.Marshal(&game)
	setToRedis("caro:game:"+callID, string(gameBytes))
	log.Printf("[DCAS-CARO] Turn timeout: %s ran out of time, turn passes to %s (CallID=%s seq=%d)",
		timedOut, game.Turn, callID, game.TurnSeq)

	resp := map[string]interface{}{
		"service":      "caro",
		"status":       "update_board",
		"board":        s.boardToSlice(&game.Board),
		"next_turn":    game.Turn,
		"move_count":   game.MoveCount,
		"score_x":      game.ScoreX,
		"score_o":      game.ScoreO,
		"winner":       "",
		"draw":         false,
		"timeout":      true,
		"timed_out":    timedOut,
		"turn_ms_left": CaroTurnTimeout.Milliseconds(),
	}
	for _, sid := range sessions {
		s.sendToSession(sid, "1000", resp)
		s.sendToSession(sid, "1001", resp)
	}

	s.armCaroTimer(callID, game.TurnSeq, CaroTurnTimeout)
}
```

- [ ] **Step 5: Run the new tests**

Run: `cd go-mf && go test ./internal/dcas/ -v -run 'CaroTimer|CaroEnsureTimer'`
Expected: all PASS.

- [ ] **Step 6: Hook the timer into the game flow**

All four functions below already run with `caroMu` held.

**`caroNewGame`:**
1. Change `var prevPlayerX, prevPlayerO string` to:
   ```go
   	var prevPlayerX, prevPlayerO string
   	var prevTurnSeq int
   ```
2. Inside `if err := json.Unmarshal([]byte(prevGameVal), &prevGame); err == nil {`, after `prevPlayerO = prevGame.PlayerO` add `prevTurnSeq = prevGame.TurnSeq` (carrying the sequence forward makes timers from the previous game stale).
3. After `game.PlayerO = prevPlayerO` add `game.TurnSeq = prevTurnSeq`.
4. Directly before `gameBytes, _ := json.Marshal(game)` add `startCaroTurn(game, time.Now())`.
5. Directly after `setToRedis("caro:game:"+callID, string(gameBytes))` (the first one in this function) add `s.armCaroTimer(callID, game.TurnSeq, CaroTurnTimeout)`.
6. In the `game_start` `resp` map add `"turn_ms_left": CaroTurnTimeout.Milliseconds(),` after `"score_o":     game.ScoreO,`.

**`checkLateJoinCaro`:** inside `if changed {`, directly after its `setToRedis(...)` add `s.caroEnsureTimer(callID, &game, time.Now())`; in its `resp` map add `"turn_ms_left": caroTurnMsLeft(&game, time.Now()),` after `"score_o":`.

**`caroMakeMove`:**
1. Replace
   ```go
   	if winner == "" && !draw {
   		game.Turn = nextTurn
   	}
   ```
   with
   ```go
   	if winner == "" && !draw {
   		game.Turn = nextTurn
   		startCaroTurn(&game, time.Now())
   	}
   ```
2. Directly after `setToRedis("caro:game:"+callID, string(updatedGameBytes))` add:
   ```go
   	if winner == "" && !draw {
   		s.armCaroTimer(callID, game.TurnSeq, CaroTurnTimeout)
   	} else {
   		s.stopCaroTimer(callID)
   	}
   ```
3. In the broadcast `resp` map add `"turn_ms_left": caroTurnMsLeft(&game, time.Now()),` after `"draw":       draw,`.

**`caroSyncState`:** directly after the `if changed { ... }` block add `s.caroEnsureTimer(callID, &game, time.Now())`; in its `resp` map add `"turn_ms_left": caroTurnMsLeft(&game, time.Now()),` after `"score_o":`.

Run `gofmt -w internal/dcas/caro_game.go internal/dcas/dcas.go` from `go-mf/`.

- [ ] **Step 7: Build, test, vet**

Run: `cd go-mf && go build ./... && go test ./internal/dcas/ -v && go vet ./internal/dcas/`
Expected: build OK, all tests PASS, vet silent. Also `grep -c turn_ms_left internal/dcas/caro_game.go` → `5`.

- [ ] **Step 8: Commit**

```bash
git add go-mf/internal/dcas/caro_game.go go-mf/internal/dcas/dcas.go go-mf/internal/dcas/caro_game_test.go
git commit -m "feat(caro): enforce 20s turn timer on DCAS"
```

---

### Task 4: Headless check harness + new layout

**Files:**
- Create: `go-mf/test/caro_ui/package.json`, `go-mf/test/caro_ui/check_caro.js`
- Modify: `.gitignore`
- Rewrite: `go-mf/static/apps/caro/index.html`

**Interfaces:**
- Produces (page): element ids `game-container`, `video-gap`, `game-header`, `status-bar`, `pill-me`, `pill-opp`, `me-symbol`, `opp-symbol`, `me-score`, `opp-score`, `turn-indicator`, `turn-text`, `turn-timer`, `panel`, `board-wrap`, `board`, `header-new-game-btn`, `winner-overlay`, `winner-text`, `new-game-btn`; cells `.cell[data-row][data-col]` containing `<span class="glyph">`; JS functions `sendToServer(payload)`, `renderBoard(board, lastMove, popMove)`, `updateScores()`, `updateTurn(nextTurn)`, `setActivePill(symbol|null)`, `showWinner(winner, isDraw, winCells)`, `window.receiveData(str)`; globals `mySymbol`, `currentTurn`, `boardState`, `gameActive`, `initialized`, `myScore`, `opponentScore`, `lastServerBoard`, `lastServerMove`, `lastServerTurn`.
- Produces (harness): `scenario(name, async fn(page))`, `check(name, ok, detail)`, `send(page, msg)`, `emptyBoard()`, `gameStart(symbol, extra)`.

- [ ] **Step 1: Install Playwright (needs network, ~150 MB)**

Create `go-mf/test/caro_ui/package.json`:

```json
{
  "name": "caro-ui-check",
  "private": true,
  "description": "Headless checks for the Caro mini-app page",
  "scripts": {
    "check": "node check_caro.js"
  },
  "devDependencies": {
    "playwright": "^1.48.0"
  }
}
```

Run: `cd go-mf/test/caro_ui && npm install && npx playwright install chromium`
Expected: installs without errors. (If Chromium system deps are missing on WSL: `npx playwright install-deps chromium`, which needs sudo — ask the user.)

Append to `.gitignore`:

```
# Caro headless UI check
go-mf/test/caro_ui/node_modules/
go-mf/test/caro_ui/out/
```

- [ ] **Step 2: Write the harness with the layout scenario**

Create `go-mf/test/caro_ui/check_caro.js`:

```js
// Headless check for static/apps/caro/index.html.
// Feeds fake DCAS messages through window.receiveData and screenshots each scenario.
// Usage: cd go-mf/test/caro_ui && npm run check
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const PAGE = 'file://' + path.resolve(__dirname, '../../static/apps/caro/index.html');
const OUT = path.join(__dirname, 'out');
// WebView area on Galaxy S24 between the native top bar and the call controls.
const VIEWPORT = { width: 360, height: 490 };

let failures = 0;
function check(name, ok, detail) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail !== undefined ? '  (' + detail + ')' : ''}`);
  if (!ok) failures++;
}

function emptyBoard() {
  return Array.from({ length: 9 }, () => Array(9).fill(''));
}

function gameStart(symbol, extra) {
  return Object.assign({
    service: 'caro', status: 'game_start', your_symbol: symbol, next_turn: 'X',
    board: emptyBoard(), score_x: 0, score_o: 0, turn_ms_left: 20000,
  }, extra || {});
}

async function send(page, msg) {
  await page.evaluate((m) => window.receiveData(JSON.stringify(m)), msg);
}

const scenarios = [];
function scenario(name, fn) { scenarios.push({ name, fn }); }

// ---------------------------------------------------------------- scenarios

scenario('layout', async (page) => {
  await send(page, gameStart('X', { score_x: 2, score_o: 1 }));
  const r = await page.evaluate(() => {
    const rect = (id) => document.getElementById(id).getBoundingClientRect().toJSON();
    return {
      bg: getComputedStyle(document.getElementById('game-container')).backgroundColor,
      board: rect('board'),
      panel: rect('panel'),
      vw: innerWidth,
      vh: innerHeight,
    };
  });
  check('container has no dark overlay', r.bg === 'rgba(0, 0, 0, 0)', r.bg);
  check('top of screen is clear for the video', r.panel.top >= r.vh * 0.3, `panel.top=${r.panel.top}`);
  check('board fits inside the viewport',
    r.board.left >= 0 && r.board.right <= r.vw && r.board.bottom <= r.vh, JSON.stringify(r.board));
  check('board is at least 260px wide', r.board.width >= 260, `width=${r.board.width}`);
  check('my score shown', (await page.textContent('#me-score')).trim() === '2');
  check('opponent score shown', (await page.textContent('#opp-score')).trim() === '1');
  check('my pill is active on my turn', await page.$eval('#pill-me', (p) => p.classList.contains('active')));
});

// ---------------------------------------------------------------- runner

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  for (const s of scenarios) {
    const page = await browser.newPage({ viewport: VIEWPORT });
    await page.addInitScript(() => {
      window.__sent = [];
      window.AndroidBridge = { sendData: (str) => window.__sent.push(JSON.parse(str)) };
    });
    // Dark stand-in for the remote video so screenshots resemble the device.
    await page.goto(PAGE);
    await page.evaluate(() => {
      document.documentElement.style.background = 'linear-gradient(#334155, #0f172a)';
    });
    console.log(`--- ${s.name}`);
    try {
      await s.fn(page);
    } catch (e) {
      check(`${s.name} ran without errors`, false, e.message);
    }
    await page.screenshot({ path: path.join(OUT, `${s.name}.png`) });
    await page.close();
  }
  await browser.close();
  console.log(failures ? `\n${failures} check(s) failed` : '\nAll checks passed');
  process.exit(failures ? 1 : 0);
})();
```

Note: the page sets `html, body { background: transparent !important }`; inline styles on `<html>` still lose to `!important`, so if the gradient does not show, that is cosmetic only.

- [ ] **Step 3: Run it to verify it fails**

Run: `cd go-mf/test/caro_ui && npm run check`
Expected: FAIL — `container has no dark overlay (rgba(0, 0, 0, 0.75))` and an error for missing `#panel` / `#me-score`.

- [ ] **Step 4: Rewrite the page with the new layout**

Replace the whole of `go-mf/static/apps/caro/index.html` with:

```html
<!DOCTYPE html>
<html lang="vi">

<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
  <title>Caro Game</title>
  <style>
    :root {
      /* Clear area above the board so the remote face stays visible. Tune on device. */
      --video-gap: 25vh;
      --board-size: min(92vw, calc(100vh - var(--video-gap) - 76px));
      --x-color: #60a5fa;
      --o-color: #f87171;
    }

    * {
      margin: 0;
      padding: 0;
      box-sizing: border-box;
    }

    html,
    body {
      width: 100%;
      height: 100%;
      background: transparent !important;
      overflow: hidden;
      font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
      user-select: none;
      -webkit-user-select: none;
    }

    #game-container {
      position: fixed;
      inset: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
    }

    #video-gap {
      flex: 0 0 var(--video-gap);
    }

    #game-header {
      width: 100%;
      padding: 0 12px 6px;
      color: white;
      text-align: center;
    }

    #status-bar {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 8px;
    }

    .score-pill {
      position: relative;
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 5px 12px;
      border-radius: 16px;
      background: rgba(15, 23, 42, 0.55);
      font-size: 13px;
      color: rgba(255, 255, 255, 0.9);
      text-shadow: 0 1px 3px rgba(0, 0, 0, 0.8);
      transition: background 0.2s, transform 0.2s;
    }

    .score-pill.active {
      background: rgba(37, 99, 235, 0.7);
      transform: scale(1.06);
      font-weight: 700;
    }

    .pill-symbol {
      font-weight: 800;
    }

    .pill-symbol.x {
      color: var(--x-color);
    }

    .pill-symbol.o {
      color: var(--o-color);
    }

    .pill-score {
      font-weight: 800;
      font-size: 15px;
    }

    .vs {
      color: rgba(255, 255, 255, 0.8);
      font-size: 12px;
      text-shadow: 0 1px 3px rgba(0, 0, 0, 0.8);
    }

    #header-new-game-btn {
      margin-left: 6px;
      padding: 5px 12px;
      border-radius: 16px;
      border: none;
      background: rgba(59, 130, 246, 0.85);
      color: white;
      font-weight: 700;
      font-size: 13px;
      cursor: pointer;
    }

    #turn-indicator {
      margin-top: 4px;
      min-height: 18px;
      font-size: 13px;
      font-weight: 600;
      color: #fbbf24;
      text-shadow: 0 1px 4px rgba(0, 0, 0, 0.9);
    }

    #turn-timer {
      margin-left: 6px;
      font-variant-numeric: tabular-nums;
    }

    #panel {
      flex: 1;
      width: 100%;
      display: flex;
      justify-content: center;
      align-items: flex-start;
      padding-top: 10px;
      background: rgba(2, 6, 23, 0.55);
      backdrop-filter: blur(10px);
      -webkit-backdrop-filter: blur(10px);
      border-radius: 20px 20px 0 0;
    }

    #board-wrap {
      position: relative;
      width: var(--board-size);
      height: var(--board-size);
    }

    #board {
      display: grid;
      grid-template-columns: repeat(9, 1fr);
      grid-template-rows: repeat(9, 1fr);
      gap: 1px;
      width: 100%;
      height: 100%;
      background: rgba(255, 255, 255, 0.18);
      border-radius: 10px;
      overflow: hidden;
      box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);
    }

    .cell {
      background: rgba(15, 23, 42, 0.35);
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: calc(var(--board-size) / 13);
      font-weight: 800;
      cursor: pointer;
      transition: background 0.15s;
      -webkit-tap-highlight-color: transparent;
    }

    .cell:active {
      background: rgba(51, 65, 85, 0.55);
    }

    .glyph {
      display: inline-block;
    }

    .cell.x {
      color: var(--x-color);
      text-shadow: 0 0 8px rgba(96, 165, 250, 0.8);
    }

    .cell.o {
      color: var(--o-color);
      text-shadow: 0 0 8px rgba(248, 113, 113, 0.8);
    }

    .cell.last-move {
      box-shadow: inset 0 0 0 2px rgba(251, 191, 36, 0.85);
    }

    .cell.win {
      background: rgba(34, 197, 94, 0.35);
      animation: winPulse 0.6s ease infinite alternate;
    }

    @keyframes winPulse {
      from {
        background: rgba(34, 197, 94, 0.25);
      }

      to {
        background: rgba(34, 197, 94, 0.5);
      }
    }

    #winner-overlay {
      display: none;
      position: fixed;
      inset: 0;
      background: rgba(0, 0, 0, 0.55);
      z-index: 200;
      flex-direction: column;
      align-items: center;
      justify-content: center;
    }

    #winner-overlay.show {
      display: flex;
    }

    #winner-text {
      font-size: 36px;
      font-weight: 800;
      color: #fbbf24;
      text-shadow: 0 4px 16px rgba(0, 0, 0, 0.5);
      margin-bottom: 16px;
    }

    #new-game-btn {
      padding: 12px 32px;
      border: none;
      border-radius: 24px;
      background: linear-gradient(135deg, #3b82f6, #8b5cf6);
      color: white;
      font-size: 16px;
      font-weight: 700;
      cursor: pointer;
      box-shadow: 0 4px 16px rgba(59, 130, 246, 0.4);
    }
  </style>
</head>

<body>
  <div id="game-container">
    <div id="video-gap"></div>
    <div id="game-header">
      <div id="status-bar">
        <div class="score-pill" id="pill-me">
          <span class="pill-symbol x" id="me-symbol">X</span>
          <span class="pill-label">Bạn</span>
          <span class="pill-score" id="me-score">0</span>
        </div>
        <span class="vs">vs</span>
        <div class="score-pill" id="pill-opp">
          <span class="pill-score" id="opp-score">0</span>
          <span class="pill-label">Đối thủ</span>
          <span class="pill-symbol o" id="opp-symbol">O</span>
        </div>
        <button id="header-new-game-btn" onclick="requestNewGame()">Ván mới</button>
      </div>
      <div id="turn-indicator"><span id="turn-text">Đang chờ kết nối...</span><span id="turn-timer"></span></div>
    </div>
    <div id="panel">
      <div id="board-wrap">
        <div id="board"></div>
      </div>
    </div>
  </div>

  <div id="winner-overlay">
    <div id="winner-text"></div>
    <button id="new-game-btn" onclick="requestNewGame()">Ván mới</button>
  </div>

  <script>
    const BOARD_SIZE = 9;
    let mySymbol = 'X';
    let currentTurn = 'X';
    let boardState = [];
    let gameActive = false;
    let initialized = false;

    let myScore = 0;
    let opponentScore = 0;

    // Last state confirmed by DCAS, used to roll back optimistic moves the server rejects.
    let lastServerBoard = null;
    let lastServerMove = null;
    let lastServerTurn = null;

    function sendToServer(payload) {
      if (window.IMSDataChannelBridge) {
        window.IMSDataChannelBridge.send(1000, JSON.stringify(payload));
      } else if (window.AndroidBridge) {
        window.AndroidBridge.sendData(JSON.stringify(payload));
      }
    }

    function cellAt(row, col) {
      return document.querySelector(`.cell[data-row="${row}"][data-col="${col}"]`);
    }

    // Initialize empty board
    function initBoard() {
      const board = document.getElementById('board');
      board.innerHTML = '';
      for (let r = 0; r < BOARD_SIZE; r++) {
        for (let c = 0; c < BOARD_SIZE; c++) {
          const cell = document.createElement('div');
          cell.className = 'cell';
          cell.dataset.row = r;
          cell.dataset.col = c;
          cell.addEventListener('click', () => onCellClick(r, c));
          board.appendChild(cell);
        }
      }
    }

    function setCell(cell, val) {
      cell.innerHTML = val ? '<span class="glyph">' + val + '</span>' : '';
      cell.className = 'cell';
      if (val === 'X') cell.classList.add('x');
      if (val === 'O') cell.classList.add('o');
    }

    function onCellClick(row, col) {
      if (!gameActive || currentTurn !== mySymbol) return;
      if (boardState[row] && boardState[row][col] !== '') return;

      // Optimistic UI Update: Make it feel instant and hide network latency!
      boardState[row][col] = mySymbol;

      const cell = cellAt(row, col);
      if (cell) {
        setCell(cell, mySymbol);
        // Remove last-move from other cells, add to this one optimistically
        document.querySelectorAll('.cell.last-move').forEach(c => c.classList.remove('last-move'));
        cell.classList.add('last-move');
      }

      updateTurn(mySymbol === 'X' ? 'O' : 'X');

      sendToServer({
        service: 'caro',
        action: 'move',
        row: row,
        col: col
      });
    }

    function requestNewGame() {
      document.getElementById('winner-overlay').classList.remove('show');
      sendToServer({
        service: 'caro',
        action: 'new_game'
      });
    }

    function renderBoard(board, lastMove, popMove) {
      boardState = board.map(row => row.slice());
      document.querySelectorAll('.cell').forEach(cell => {
        const r = parseInt(cell.dataset.row);
        const c = parseInt(cell.dataset.col);
        setCell(cell, board[r] ? board[r][c] : '');
        if (lastMove && lastMove.row === r && lastMove.col === c) {
          cell.classList.add('last-move');
          if (popMove) cell.classList.add('pop');
        }
      });
    }

    function updateScores() {
      const oppSymbol = mySymbol === 'X' ? 'O' : 'X';
      document.getElementById('me-score').textContent = myScore;
      document.getElementById('opp-score').textContent = opponentScore;
      const meSym = document.getElementById('me-symbol');
      meSym.textContent = mySymbol;
      meSym.className = 'pill-symbol ' + mySymbol.toLowerCase();
      const oppSym = document.getElementById('opp-symbol');
      oppSym.textContent = oppSymbol;
      oppSym.className = 'pill-symbol ' + oppSymbol.toLowerCase();
    }

    // Highlights the pill of the player whose turn it is (null = nobody).
    function setActivePill(symbol) {
      document.getElementById('pill-me').classList.toggle('active', symbol !== null && symbol === mySymbol);
      document.getElementById('pill-opp').classList.toggle('active', symbol !== null && symbol !== mySymbol);
    }

    function updateTurn(nextTurn) {
      currentTurn = nextTurn;
      const indicator = document.getElementById('turn-indicator');
      const text = document.getElementById('turn-text');
      if (nextTurn === mySymbol) {
        text.textContent = '👉 Lượt của bạn';
        indicator.style.color = '#22c55e';
      } else {
        text.textContent = '⏳ Đợi đối thủ...';
        indicator.style.color = '#fbbf24';
      }
      setActivePill(gameActive ? nextTurn : null);
    }

    function showWinner(winner, isDraw, winCells) {
      gameActive = false;
      setActivePill(null);
      const overlay = document.getElementById('winner-overlay');
      const text = document.getElementById('winner-text');
      if (isDraw) {
        text.textContent = '🤝 Hòa!';
        text.style.color = '#94a3b8';
      } else if (winner === mySymbol) {
        text.textContent = '🎉 Bạn thắng!';
        text.style.color = '#22c55e';
      } else {
        text.textContent = '😢 Bạn thua!';
        text.style.color = '#ef4444';
      }
      overlay.classList.add('show');
    }

    function rememberServerState(data) {
      lastServerBoard = data.board;
      lastServerMove = data.last_move || null;
      lastServerTurn = data.next_turn;
    }

    function applyScores(data) {
      if (data.score_x === undefined || data.score_o === undefined) return;
      if (mySymbol === 'X') {
        myScore = data.score_x;
        opponentScore = data.score_o;
      } else {
        myScore = data.score_o;
        opponentScore = data.score_x;
      }
    }

    // Called by native Android when ADC data arrives
    window.receiveData = function (dataStr) {
      console.log('[Caro] Received: ' + dataStr);
      try {
        const data = JSON.parse(dataStr);
        if (data.service !== 'caro') return;

        if (data.status === 'game_start' || data.status === 'late_join') {
          mySymbol = data.your_symbol || 'X';
          initialized = true;
          gameActive = true;
          applyScores(data);
          rememberServerState(data);
          renderBoard(data.board, null, false);
          updateScores();
          updateTurn(data.next_turn);
          document.getElementById('winner-overlay').classList.remove('show');
        } else if (data.status === 'update_board') {
          if (!initialized) {
            console.log('[Caro] Ignoring update_board because not initialized yet.');
            return;
          }
          rememberServerState(data);
          renderBoard(data.board, data.last_move, false);
          applyScores(data);
          updateScores();
          if (data.winner && data.winner !== '') {
            showWinner(data.winner, false, data.win_cells);
          } else if (data.draw) {
            showWinner('', true, null);
          } else {
            gameActive = true;
            updateTurn(data.next_turn);
          }
        }
      } catch (e) {
        console.error('[Caro] Parse error: ' + e.message);
      }
    };

    // Send sync_state to DCAS to initialize/restore game state
    // Note: re-INVITE for caro app may take 2-5 seconds to complete (ADC recreation + MF DCAS TCP connect).
    // We retry with 2s intervals until gameActive becomes true or we exhaust retries.
    let syncRetries = 0;
    const MAX_SYNC_RETRIES = 15;

    function sendSyncState() {
      sendToServer({ service: 'caro', action: 'sync_state' });
      console.log('[Caro] sync_state sent (attempt ' + (syncRetries + 1) + '/' + MAX_SYNC_RETRIES + ')');
    }

    window.onload = function () {
      initBoard();

      // Request GSMA Data Channel creation (Triggers re-INVITE natively)
      if (window.ImsDcBridge) {
        window.ImsDcBridge.createDataChannel("caro");
      } else if (window._dsbridge) {
        // Fallback for some JS bridges
        window._dsbridge.call("createDataChannel", "caro");
      }

      // Send initial sync_state immediately (may arrive before re-INVITE completes — harmless, will be retried)
      sendSyncState();
      // Retry periodically until game becomes active or we run out of retries
      const retryInterval = setInterval(function () {
        if (initialized) {
          clearInterval(retryInterval);
          console.log('[Caro] Game initialized, stopping sync_state retries');
          return;
        }
        syncRetries++;
        if (syncRetries >= MAX_SYNC_RETRIES) {
          clearInterval(retryInterval);
          console.log('[Caro] sync_state failed after ' + MAX_SYNC_RETRIES + ' retries');
          return;
        }
        sendSyncState();
      }, 2000);
    };
  </script>
</body>

</html>
```

Behaviour changes vs. the old page, on purpose: `renderBoard` copies the board instead of aliasing the server array (so optimistic edits can be rolled back); scores update on every `update_board` (previously only on win); the "active" pill now follows the turn (previously "Bạn" was always highlighted).

- [ ] **Step 5: Run the check to verify it passes**

Run: `cd go-mf/test/caro_ui && npm run check`
Expected: all `layout` checks PASS. Open `go-mf/test/caro_ui/out/layout.png` and confirm the top ~third is clear and the board sits in the bottom panel.

- [ ] **Step 6: Commit**

```bash
git add .gitignore go-mf/test/caro_ui/package.json go-mf/test/caro_ui/package-lock.json go-mf/test/caro_ui/check_caro.js go-mf/static/apps/caro/index.html
git commit -m "feat(caro): board-bottom layout keeping remote video visible, add headless UI check"
```

---

### Task 5: Move feedback and rejected-move handling

**Files:**
- Modify: `go-mf/static/apps/caro/index.html`
- Modify: `go-mf/test/caro_ui/check_caro.js`

**Interfaces:**
- Consumes: Task 4 page functions/ids.
- Produces: `fx(name)` (records effects in `window.__fx` for tests), `playTone(freq, durMs, type, gain)`, `buzz(pattern)`, `showToast(msg)`, `shakeBoard()`, `rejectLocally(msg)`, `onServerError(message)`; element `#toast`; CSS classes `.pop`, `.shake`, `#toast.show`. Recorded effect names: `tone:<freq>`, `buzz:<JSON pattern>`.

- [ ] **Step 1: Add the failing scenario**

In `check_caro.js`, after the `layout` scenario add:

```js
scenario('feedback', async (page) => {
  await send(page, gameStart('X'));

  // Own move: optimistic pop, tone, vibration, sent to server
  await page.click('.cell[data-row="4"][data-col="4"]');
  let fx = await page.evaluate(() => window.__fx || []);
  check('own move plays tone 880', fx.includes('tone:880'), fx.join(','));
  check('own move vibrates 25ms', fx.includes('buzz:25'), fx.join(','));
  check('own move pops', await page.$eval('.cell[data-row="4"][data-col="4"]', (c) => c.classList.contains('pop')));
  check('move sent to server', await page.evaluate(
    () => window.__sent.some((m) => m.action === 'move' && m.row === 4 && m.col === 4)));

  // Server rejects it: toast + shake + rollback
  await send(page, { service: 'caro', status: 'error', message: 'Ô này đã có quân.' });
  check('toast shows server message', (await page.textContent('#toast')).includes('Ô này đã có quân.'));
  check('toast visible', await page.$eval('#toast', (t) => t.classList.contains('show')));
  check('board shakes', await page.$eval('#board-wrap', (b) => b.classList.contains('shake')));
  check('optimistic piece rolled back',
    (await page.textContent('.cell[data-row="4"][data-col="4"]')).trim() === '');

  // Opponent move: lower tone + pop
  const b = emptyBoard();
  b[0][0] = 'O';
  await page.evaluate(() => { window.__fx = []; });
  await send(page, {
    service: 'caro', status: 'update_board', board: b, last_move: { row: 0, col: 0, player: 'O' },
    next_turn: 'X', move_count: 1, score_x: 0, score_o: 0, winner: '', draw: false, turn_ms_left: 20000,
  });
  fx = await page.evaluate(() => window.__fx || []);
  check('opponent move plays tone 660', fx.includes('tone:660'), fx.join(','));
  check('opponent move pops', await page.$eval('.cell[data-row="0"][data-col="0"]', (c) => c.classList.contains('pop')));

  // Not my turn: local toast, nothing sent
  await send(page, {
    service: 'caro', status: 'update_board', board: b, last_move: { row: 0, col: 0, player: 'O' },
    next_turn: 'O', move_count: 1, score_x: 0, score_o: 0, winner: '', draw: false, turn_ms_left: 20000,
  });
  const sentBefore = await page.evaluate(() => window.__sent.filter((m) => m.action === 'move').length);
  await page.click('.cell[data-row="2"][data-col="2"]');
  check('not-your-turn toast', (await page.textContent('#toast')).includes('Chưa đến lượt của bạn.'));
  check('no move sent on opponent turn',
    (await page.evaluate(() => window.__sent.filter((m) => m.action === 'move').length)) === sentBefore);
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd go-mf/test/caro_ui && npm run check`
Expected: `layout` PASS; `feedback` FAILs (no `tone:880`, no `#toast`).

- [ ] **Step 3: Add CSS**

In `index.html`, before `</style>` add:

```css
    .cell.pop .glyph {
      animation: pop 0.2s ease-out;
    }

    @keyframes pop {
      0% {
        transform: scale(0);
      }

      70% {
        transform: scale(1.15);
      }

      100% {
        transform: scale(1);
      }
    }

    #board-wrap.shake {
      animation: shake 0.3s ease-in-out;
    }

    @keyframes shake {

      0%,
      100% {
        transform: translateX(0);
      }

      20%,
      60% {
        transform: translateX(-6px);
      }

      40%,
      80% {
        transform: translateX(6px);
      }
    }

    #toast {
      position: fixed;
      left: 50%;
      bottom: 16px;
      transform: translate(-50%, 20px);
      max-width: 90vw;
      padding: 10px 18px;
      border-radius: 20px;
      background: rgba(15, 23, 42, 0.92);
      color: white;
      font-size: 14px;
      font-weight: 600;
      text-align: center;
      opacity: 0;
      pointer-events: none;
      transition: opacity 0.2s, transform 0.2s;
      z-index: 300;
    }

    #toast.show {
      opacity: 1;
      transform: translate(-50%, 0);
    }
```

- [ ] **Step 4: Add markup and effect helpers**

Directly before `<script>` add:

```html
  <div id="toast"></div>
```

In the script, directly after the `lastServerTurn` declaration add:

```js
    // ---- Effects (sound, vibration, toast). All optional: silently skipped if unsupported.
    function fx(name) {
      (window.__fx = window.__fx || []).push(name);
    }

    let audioCtx = null;
    function getAudio() {
      if (!audioCtx) {
        try {
          audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        } catch (e) {
          audioCtx = null;
        }
      }
      return audioCtx;
    }

    function playTone(freq, durMs, type, gain) {
      fx('tone:' + freq);
      const ctx = getAudio();
      if (!ctx) return;
      try {
        const osc = ctx.createOscillator();
        const g = ctx.createGain();
        osc.type = type || 'sine';
        osc.frequency.value = freq;
        g.gain.setValueAtTime(gain || 0.2, ctx.currentTime);
        g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + durMs / 1000);
        osc.connect(g).connect(ctx.destination);
        osc.start();
        osc.stop(ctx.currentTime + durMs / 1000);
      } catch (e) {
        // audio not available
      }
    }

    function buzz(pattern) {
      fx('buzz:' + JSON.stringify(pattern));
      try {
        if (navigator.vibrate) navigator.vibrate(pattern);
      } catch (e) {
        // vibration not available
      }
    }

    let toastTimeout = null;
    function showToast(msg) {
      const toast = document.getElementById('toast');
      toast.textContent = msg;
      toast.classList.add('show');
      clearTimeout(toastTimeout);
      toastTimeout = setTimeout(() => toast.classList.remove('show'), 2200);
    }

    function shakeBoard() {
      const wrap = document.getElementById('board-wrap');
      wrap.classList.remove('shake');
      void wrap.offsetWidth; // restart the animation
      wrap.classList.add('shake');
    }

    function rejectLocally(msg) {
      showToast(msg);
      shakeBoard();
      buzz([30, 60, 30]);
    }

    function onServerError(message) {
      rejectLocally(message || 'Nước đi không hợp lệ.');
      if (lastServerBoard) renderBoard(lastServerBoard, lastServerMove, false);
      if (lastServerTurn) updateTurn(lastServerTurn);
    }
```

- [ ] **Step 5: Use them in the game flow**

Replace the whole `function onCellClick(row, col) { ... }` from Task 4 with:

```js
    function onCellClick(row, col) {
      if (!gameActive) return;
      if (currentTurn !== mySymbol) {
        rejectLocally('Chưa đến lượt của bạn.');
        return;
      }
      if (boardState[row] && boardState[row][col] !== '') {
        rejectLocally('Ô này đã có quân.');
        return;
      }

      // Optimistic UI Update: Make it feel instant and hide network latency!
      boardState[row][col] = mySymbol;

      const cell = cellAt(row, col);
      if (cell) {
        setCell(cell, mySymbol);
        // Remove last-move from other cells, add to this one optimistically
        document.querySelectorAll('.cell.last-move').forEach(c => c.classList.remove('last-move'));
        cell.classList.add('last-move', 'pop');
      }
      playTone(880, 90, 'triangle', 0.25);
      buzz(25);

      updateTurn(mySymbol === 'X' ? 'O' : 'X');

      sendToServer({
        service: 'caro',
        action: 'move',
        row: row,
        col: col
      });
    }
```

In `receiveData`, in the `update_board` branch, replace `          renderBoard(data.board, data.last_move, false);` with:

```js
          const opponentMoved = !!(data.last_move && data.last_move.player && data.last_move.player !== mySymbol);
          renderBoard(data.board, data.last_move, opponentMoved);
          if (opponentMoved) playTone(660, 90, 'triangle', 0.2);
```

In `receiveData`, after the closing `}` of the `else if (data.status === 'update_board') { ... }` branch add:

```js
        else if (data.status === 'error') {
          onServerError(data.message);
        }
```

- [ ] **Step 6: Run the check**

Run: `cd go-mf/test/caro_ui && npm run check`
Expected: `layout` and `feedback` all PASS. Look at `out/feedback.png` (toast visible at the bottom).

- [ ] **Step 7: Commit**

```bash
git add go-mf/static/apps/caro/index.html go-mf/test/caro_ui/check_caro.js
git commit -m "feat(caro): move sound/vibration/pop and visible rejected-move feedback"
```

---

### Task 6: Win celebration

**Files:**
- Modify: `go-mf/static/apps/caro/index.html`
- Modify: `go-mf/test/caro_ui/check_caro.js`

**Interfaces:**
- Consumes: `fx`, `playTone`, `buzz`, `showWinner(winner, isDraw, winCells)` call sites (Task 4/5).
- Produces: `drawWinLine(cells)`, `clearWinLine()`, `launchConfetti(durationMs)`; elements `#win-line` (SVG, viewBox 0 0 900 900, cell centre = `(index + 0.5) * 100`), `#confetti` (canvas), `#winner-score`; overlay card classes `win` / `lose` / `draw`; recorded effect `confetti`. `showWinner` shows the card after `RESULT_DELAY_MS = 1000` (400 ms line + 600 ms) for wins, immediately for draws.

- [ ] **Step 1: Add the failing scenarios**

In `check_caro.js`, after the `feedback` scenario add:

```js
function winMessage() {
  const b = emptyBoard();
  // Unordered on purpose: DCAS sends forward cells then backward cells.
  const cells = [[4, 4], [4, 0], [4, 1], [4, 2], [4, 3]];
  cells.forEach(([r, c]) => { b[r][c] = 'X'; });
  return {
    service: 'caro', status: 'update_board', board: b, last_move: { row: 4, col: 4, player: 'X' },
    next_turn: 'O', move_count: 9, score_x: 1, score_o: 0, winner: 'X', draw: false,
    win_cells: cells, turn_ms_left: 0,
  };
}

scenario('win', async (page) => {
  await send(page, gameStart('X'));
  await send(page, winMessage());
  const line = await page.evaluate(() => {
    const l = document.querySelector('#win-line line');
    return {
      shown: document.getElementById('win-line').classList.contains('show'),
      x1: +l.getAttribute('x1'), y1: +l.getAttribute('y1'),
      x2: +l.getAttribute('x2'), y2: +l.getAttribute('y2'),
    };
  });
  check('win line shown', line.shown);
  check('win line spans (4,0) to (4,4)',
    line.x1 === 50 && line.y1 === 450 && line.x2 === 450 && line.y2 === 450, JSON.stringify(line));
  check('result card waits for the line',
    !(await page.$eval('#winner-overlay', (o) => o.classList.contains('show'))));
  await page.waitForTimeout(1200);
  check('result card shown', await page.$eval('#winner-overlay', (o) => o.classList.contains('show')));
  check('winner text', (await page.textContent('#winner-text')).includes('Bạn thắng'));
  check('score on card', (await page.textContent('#winner-score')).includes('1'));
  const fx = await page.evaluate(() => window.__fx || []);
  check('confetti on the winner phone', fx.includes('confetti'), fx.join(','));
  check('winner vibration', fx.includes('buzz:[40,60,40,60,40]'), fx.join(','));
});

scenario('lose', async (page) => {
  await send(page, gameStart('O'));
  await send(page, winMessage());
  await page.waitForTimeout(1200);
  check('loser text', (await page.textContent('#winner-text')).includes('Bạn thua'));
  const fx = await page.evaluate(() => window.__fx || []);
  check('no confetti on the loser phone', !fx.includes('confetti'), fx.join(','));
});

scenario('new-game-clears-win', async (page) => {
  await send(page, gameStart('X'));
  await send(page, winMessage());
  await send(page, gameStart('X', { score_x: 1 }));
  await page.waitForTimeout(1200);
  check('win line hidden after new game',
    !(await page.$eval('#win-line', (s) => s.classList.contains('show'))));
  check('result card does not pop up after new game',
    !(await page.$eval('#winner-overlay', (o) => o.classList.contains('show'))));
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd go-mf/test/caro_ui && npm run check`
Expected: `win`, `lose`, `new-game-clears-win` FAIL (no `#win-line`, card shown immediately).

- [ ] **Step 3: Add CSS**

Before `</style>` add:

```css
    #win-line {
      position: absolute;
      inset: 0;
      width: 100%;
      height: 100%;
      pointer-events: none;
      display: none;
    }

    #win-line.show {
      display: block;
    }

    #win-line line {
      stroke: #fde047;
      stroke-width: 14;
      stroke-linecap: round;
      stroke-dasharray: 100;
      stroke-dashoffset: 100;
      filter: drop-shadow(0 0 6px rgba(253, 224, 71, 0.9));
    }

    #win-line.draw line {
      animation: drawLine 0.4s ease-out forwards;
    }

    @keyframes drawLine {
      to {
        stroke-dashoffset: 0;
      }
    }

    #confetti {
      position: fixed;
      inset: 0;
      pointer-events: none;
      z-index: 250;
      display: none;
    }

    #winner-card {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 24px 32px;
      border-radius: 24px;
      background: rgba(15, 23, 42, 0.9);
      box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5);
      animation: cardIn 0.35s cubic-bezier(0.2, 1.4, 0.4, 1);
    }

    #winner-card.lose {
      animation: none;
      background: rgba(30, 41, 59, 0.9);
    }

    #winner-score {
      color: rgba(255, 255, 255, 0.85);
      font-size: 15px;
      margin-bottom: 16px;
    }

    @keyframes cardIn {
      from {
        transform: scale(0.6);
        opacity: 0;
      }

      to {
        transform: scale(1);
        opacity: 1;
      }
    }
```

- [ ] **Step 4: Add markup**

Replace

```html
      <div id="board-wrap">
        <div id="board"></div>
      </div>
```

with

```html
      <div id="board-wrap">
        <div id="board"></div>
        <svg id="win-line" viewBox="0 0 900 900" preserveAspectRatio="none">
          <line pathLength="100" x1="0" y1="0" x2="0" y2="0"></line>
        </svg>
      </div>
```

Replace

```html
  <div id="winner-overlay">
    <div id="winner-text"></div>
    <button id="new-game-btn" onclick="requestNewGame()">Ván mới</button>
  </div>
```

with

```html
  <div id="winner-overlay">
    <div id="winner-card">
      <div id="winner-text"></div>
      <div id="winner-score"></div>
      <button id="new-game-btn" onclick="requestNewGame()">Ván mới</button>
    </div>
  </div>
  <canvas id="confetti"></canvas>
```

- [ ] **Step 5: Add the celebration code and replace `showWinner`**

After `onServerError` (Task 5) add:

```js
    // ---- Win celebration
    const RESULT_DELAY_MS = 1000; // 400ms line draw + 600ms pause
    let resultTimeout = null;

    function drawWinLine(cells) {
      if (!cells || cells.length < 2) return;
      // DCAS sends the cells unordered; sorting by row then column gives the two ends of any straight line.
      const sorted = cells.slice().sort((a, b) => a[0] - b[0] || a[1] - b[1]);
      const first = sorted[0];
      const last = sorted[sorted.length - 1];
      const svg = document.getElementById('win-line');
      const line = svg.querySelector('line');
      line.setAttribute('x1', (first[1] + 0.5) * 100);
      line.setAttribute('y1', (first[0] + 0.5) * 100);
      line.setAttribute('x2', (last[1] + 0.5) * 100);
      line.setAttribute('y2', (last[0] + 0.5) * 100);
      svg.classList.remove('draw');
      void svg.getBoundingClientRect(); // restart the animation
      svg.classList.add('show', 'draw');
    }

    function clearWinLine() {
      document.getElementById('win-line').classList.remove('show', 'draw');
    }

    function launchConfetti(durationMs) {
      fx('confetti');
      const canvas = document.getElementById('confetti');
      const ctx2d = canvas.getContext && canvas.getContext('2d');
      if (!ctx2d) return;
      canvas.width = window.innerWidth;
      canvas.height = window.innerHeight;
      canvas.style.display = 'block';
      const colors = ['#f87171', '#60a5fa', '#fbbf24', '#34d399', '#a78bfa', '#f472b6'];
      const parts = Array.from({ length: 120 }, () => ({
        x: Math.random() * canvas.width,
        y: -20 - Math.random() * canvas.height * 0.5,
        vx: (Math.random() - 0.5) * 2,
        vy: 2 + Math.random() * 3,
        size: 4 + Math.random() * 5,
        rot: Math.random() * Math.PI,
        vr: (Math.random() - 0.5) * 0.2,
        color: colors[Math.floor(Math.random() * colors.length)],
      }));
      const start = performance.now();
      function frame(now) {
        ctx2d.clearRect(0, 0, canvas.width, canvas.height);
        for (const p of parts) {
          p.x += p.vx;
          p.y += p.vy;
          p.vy += 0.04;
          p.rot += p.vr;
          ctx2d.save();
          ctx2d.translate(p.x, p.y);
          ctx2d.rotate(p.rot);
          ctx2d.fillStyle = p.color;
          ctx2d.fillRect(-p.size / 2, -p.size / 4, p.size, p.size / 2);
          ctx2d.restore();
        }
        if (now - start < durationMs) {
          requestAnimationFrame(frame);
        } else {
          ctx2d.clearRect(0, 0, canvas.width, canvas.height);
          canvas.style.display = 'none';
        }
      }
      requestAnimationFrame(frame);
    }
```

Replace the whole `function showWinner(winner, isDraw, winCells) { ... }` from Task 4 with:

```js
    function showWinner(winner, isDraw, winCells) {
      gameActive = false;
      setActivePill(null);
      const overlay = document.getElementById('winner-overlay');
      const card = document.getElementById('winner-card');
      const text = document.getElementById('winner-text');
      const iWon = !isDraw && winner === mySymbol;

      if (isDraw) {
        text.textContent = '🤝 Hòa';
        text.style.color = '#94a3b8';
        card.className = 'draw';
      } else if (iWon) {
        text.textContent = '🎉 Bạn thắng!';
        text.style.color = '#22c55e';
        card.className = 'win';
      } else {
        text.textContent = 'Bạn thua 😅';
        text.style.color = '#f87171';
        card.className = 'lose';
      }
      document.getElementById('winner-score').textContent = 'Bạn ' + myScore + ' – ' + opponentScore + ' Đối thủ';

      if (!isDraw) drawWinLine(winCells);

      clearTimeout(resultTimeout);
      resultTimeout = setTimeout(() => {
        overlay.classList.add('show');
        if (iWon) {
          launchConfetti(2500);
          buzz([40, 60, 40, 60, 40]);
          playTone(523, 120, 'triangle', 0.2);
          setTimeout(() => playTone(659, 120, 'triangle', 0.2), 130);
          setTimeout(() => playTone(784, 220, 'triangle', 0.2), 260);
        }
      }, isDraw ? 0 : RESULT_DELAY_MS);
    }
```

- [ ] **Step 6: Clear the celebration on a new game**

In `receiveData`, in the `game_start` branch, replace `          document.getElementById('winner-overlay').classList.remove('show');` with:

```js
          clearTimeout(resultTimeout);
          clearWinLine();
          document.getElementById('winner-overlay').classList.remove('show');
```

In `requestNewGame`, after `document.getElementById('winner-overlay').classList.remove('show');` add `clearWinLine();`.

- [ ] **Step 7: Run the check**

Run: `cd go-mf/test/caro_ui && npm run check`
Expected: all scenarios PASS. Check `out/win.png` (card + line visible).

- [ ] **Step 8: Commit**

```bash
git add go-mf/static/apps/caro/index.html go-mf/test/caro_ui/check_caro.js
git commit -m "feat(caro): animated win line, confetti and result card"
```

---

### Task 7: Turn timer UI

**Files:**
- Modify: `go-mf/static/apps/caro/index.html`
- Modify: `go-mf/test/caro_ui/check_caro.js`

**Interfaces:**
- Consumes: server `turn_ms_left`, `timeout`, `timed_out` (Task 3); `showToast`, `buzz`, `playTone`, `fx` (Task 5); `setActivePill` (Task 4).
- Produces: `TURN_TOTAL_MS = 20000`, `startTurnTimer(msLeft)`, `stopTurnTimer()`; `svg.timer-ring` inside each pill with classes `warn` (≤10 s) / `danger` (≤5 s); `#turn-timer` text like `· 14s` (empty when stopped); recorded effect `tick`.

- [ ] **Step 1: Add the failing scenario**

In `check_caro.js`, after `new-game-clears-win` add:

```js
scenario('timer', async (page) => {
  const ring = () => page.evaluate(() => {
    const active = document.querySelector('.score-pill.active');
    const r = active && active.querySelector('.timer-ring');
    return {
      pill: active ? active.id : null,
      visible: !!r && getComputedStyle(r).display !== 'none',
      warn: !!r && r.classList.contains('warn'),
      danger: !!r && r.classList.contains('danger'),
      text: document.getElementById('turn-timer').textContent,
    };
  });

  await send(page, gameStart('X', { turn_ms_left: 20000 }));
  await page.waitForTimeout(200);
  let st = await ring();
  check('ring visible on my pill', st.pill === 'pill-me' && st.visible, JSON.stringify(st));
  check('timer text starts at 20s', st.text === '· 20s' || st.text === '· 19s', st.text);

  await send(page, gameStart('X', { turn_ms_left: 8000 }));
  await page.waitForTimeout(150);
  st = await ring();
  check('amber at <=10s', st.warn && !st.danger, JSON.stringify(st));

  await send(page, gameStart('X', { turn_ms_left: 4000 }));
  await page.waitForTimeout(150);
  st = await ring();
  check('red at <=5s', st.danger, JSON.stringify(st));

  await send(page, {
    service: 'caro', status: 'update_board', board: emptyBoard(), next_turn: 'O', move_count: 0,
    score_x: 0, score_o: 0, winner: '', draw: false, timeout: true, timed_out: 'X', turn_ms_left: 20000,
  });
  await page.waitForTimeout(150);
  st = await ring();
  check('timeout toast for the player who ran out',
    (await page.textContent('#toast')).includes('Hết giờ! Bạn mất lượt'));
  check('ring moves to opponent after timeout', st.pill === 'pill-opp' && st.visible, JSON.stringify(st));

  await send(page, winMessage());
  await page.waitForTimeout(150);
  st = await ring();
  check('no ring when the game is over', st.pill === null && st.text === '', JSON.stringify(st));
});

scenario('timer-opponent-timeout', async (page) => {
  await send(page, gameStart('O', { next_turn: 'X' }));
  await send(page, {
    service: 'caro', status: 'update_board', board: emptyBoard(), next_turn: 'O', move_count: 0,
    score_x: 0, score_o: 0, winner: '', draw: false, timeout: true, timed_out: 'X', turn_ms_left: 20000,
  });
  check('opponent-timeout toast', (await page.textContent('#toast')).includes('Đối thủ hết giờ'));
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd go-mf/test/caro_ui && npm run check`
Expected: `timer` and `timer-opponent-timeout` FAIL; earlier scenarios PASS.

- [ ] **Step 3: Add CSS**

Before `</style>` add:

```css
    .timer-ring {
      position: absolute;
      inset: -3px;
      width: calc(100% + 6px);
      height: calc(100% + 6px);
      pointer-events: none;
      overflow: visible;
      display: none;
    }

    .score-pill.active .timer-ring {
      display: block;
    }

    .timer-ring rect {
      x: 1.5px;
      y: 1.5px;
      width: calc(100% - 3px);
      height: calc(100% - 3px);
      rx: 18px;
      fill: none;
      stroke: #22c55e;
      stroke-width: 3;
      stroke-dasharray: 100;
      stroke-dashoffset: 0;
      transition: stroke 0.3s;
    }

    .timer-ring.warn rect {
      stroke: #f59e0b;
    }

    .timer-ring.danger rect {
      stroke: #ef4444;
    }
```

- [ ] **Step 4: Add the ring markup**

In `#pill-me`, directly after `<div class="score-pill" id="pill-me">` add:

```html
          <svg class="timer-ring"><rect pathLength="100"></rect></svg>
```

In `#pill-opp`, directly after `<div class="score-pill" id="pill-opp">` add the same line.

- [ ] **Step 5: Add the timer code**

After `launchConfetti` (Task 6) add:

```js
    // ---- Turn timer (display only; DCAS decides when a turn times out)
    const TURN_TOTAL_MS = 20000;
    let turnDeadline = 0;
    let timerRaf = 0;
    let lastTickSecond = -1;
    let savedTurnDeadline = 0; // restored if the server rejects an optimistic move

    function startTurnTimer(msLeft) {
      stopTurnTimer();
      if (!msLeft || msLeft <= 0) return;
      turnDeadline = performance.now() + msLeft;
      lastTickSecond = -1;
      tickTimer();
    }

    function stopTurnTimer() {
      if (timerRaf) cancelAnimationFrame(timerRaf);
      timerRaf = 0;
      turnDeadline = 0;
      document.querySelectorAll('.timer-ring').forEach(r => r.classList.remove('warn', 'danger'));
      document.getElementById('turn-timer').textContent = '';
    }

    function tickTimer() {
      const left = Math.max(0, turnDeadline - performance.now());
      const frac = Math.min(1, left / TURN_TOTAL_MS);
      const secs = Math.ceil(left / 1000);
      document.querySelectorAll('.timer-ring').forEach(r => {
        r.querySelector('rect').style.strokeDashoffset = String(100 * (1 - frac));
        r.classList.toggle('warn', secs <= 10 && secs > 5);
        r.classList.toggle('danger', secs <= 5);
      });
      document.getElementById('turn-timer').textContent = '· ' + secs + 's';
      if (secs !== lastTickSecond) {
        if (secs > 0 && secs <= 3) {
          playTone(1200, 50, 'square', 0.05);
          fx('tick');
        }
        lastTickSecond = secs;
      }
      timerRaf = left > 0 ? requestAnimationFrame(tickTimer) : 0;
    }
```

- [ ] **Step 6: Hook it into the game flow**

1. In `showWinner`, after `setActivePill(null);` add `stopTurnTimer();`.
2. In `receiveData`, `game_start` branch: after `updateTurn(data.next_turn);` add `startTurnTimer(data.turn_ms_left);`.
3. In `receiveData`, `update_board` branch, replace

   ```js
          } else {
            gameActive = true;
            updateTurn(data.next_turn);
          }
   ```

   with

   ```js
          } else {
            gameActive = true;
            updateTurn(data.next_turn);
            startTurnTimer(data.turn_ms_left);
            if (data.timeout) {
              showToast(data.timed_out === mySymbol ? '⏰ Hết giờ! Bạn mất lượt' : 'Đối thủ hết giờ — đến lượt bạn');
              buzz([60, 40, 60]);
            }
          }
   ```

4. In `onCellClick`, directly before `sendToServer({`, add:

   ```js
      savedTurnDeadline = turnDeadline;
      startTurnTimer(TURN_TOTAL_MS);
   ```

5. In `onServerError`, after `if (lastServerTurn) updateTurn(lastServerTurn);` add:

   ```js
      if (savedTurnDeadline) startTurnTimer(savedTurnDeadline - performance.now());
      savedTurnDeadline = 0;
   ```

- [ ] **Step 7: Run the check**

Run: `cd go-mf/test/caro_ui && npm run check`
Expected: every scenario PASS. Look at `out/timer.png`.

- [ ] **Step 8: Commit**

```bash
git add go-mf/static/apps/caro/index.html go-mf/test/caro_ui/check_caro.js
git commit -m "feat(caro): countdown ring and timeout toasts for the 20s turn timer"
```

---

### Task 8: Sync the Android copy, full verification, changelog

**Files:**
- Modify: `android-client/app/src/main/assets/miniapps/caro/index.html` (overwrite)
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: final `go-mf/static/apps/caro/index.html`.

- [ ] **Step 1: Copy the page into the APK assets**

Run: `cp go-mf/static/apps/caro/index.html android-client/app/src/main/assets/miniapps/caro/index.html && cmp go-mf/static/apps/caro/index.html android-client/app/src/main/assets/miniapps/caro/index.html && echo identical`
Expected: `identical`.

- [ ] **Step 2: Full verification**

Run:

```bash
cd go-mf && gofmt -l internal/dcas/ && go vet ./internal/dcas/ && go test ./internal/dcas/ -v && go build ./...
cd test/caro_ui && npm run check
```

Expected: `gofmt -l` prints nothing; all Go tests PASS; build OK; `All checks passed`.

- [ ] **Step 3: Changelog**

At the top of `CHANGELOG.md`, directly after the intro line `All notable changes to this project will be documented in this file.` and its blank line, add:

```markdown
## [Unreleased] - 2026-10-25

### Added & Enhanced
- **Caro Mini-App Demo Upgrade:**
  - New layout: board in a frosted bottom panel, remote video clearly visible in the top part of the screen (`caro/index.html`).
  - Move feedback: pop animation, synthesized sound, vibration; rejected moves now shake the board and show a toast, with optimistic moves rolled back.
  - Win celebration: animated winning line, confetti on the winner's phone, result card with score.
  - 20 s turn timer enforced by DCAS (`caro_game.go`); turn passes to the opponent on timeout. New additive message fields `turn_ms_left`, `timeout`, `timed_out`.

### Fixed
- Garbled (mojibake) Vietnamese error messages sent by DCAS for invalid Caro moves.
```

- [ ] **Step 4: Commit**

```bash
git add android-client/app/src/main/assets/miniapps/caro/index.html CHANGELOG.md
git commit -m "chore(caro): sync bundled page and update changelog"
```

- [ ] **Step 5: Hand off device testing (needs the user's cluster and phones)**

Report to the user; do not run without them:

1. `cd go-mf && docker build -t go-mf:local . && kind load docker-image go-mf:local --name ims-dc-cluster`
2. `kubectl rollout restart -n demo-mini-app deployment/dcas deployment/dcar`
3. `cd android-client && gradlew.bat assembleDebug`, install on both S24s.
4. Regression: `run_caro_proof.py`, `test_long_caro_play.py` (they move every ~2 s, under the 20 s timeout).
5. Manual: wait 20 s → turn passes on both phones with toasts; tap on the opponent's turn → shake + toast; win a round → line, confetti only on the winner, card. Confirm the remote face is visible above the board; if not, tune `--video-gap` in `index.html` (e.g. `30vh`).
