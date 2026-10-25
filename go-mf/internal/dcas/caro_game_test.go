package dcas

import (
	"strings"
	"testing"
	"time"
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
