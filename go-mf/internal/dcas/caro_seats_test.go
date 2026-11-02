package dcas

import "testing"

// The call has the Home/emote ADC sessions (A, B) next to the caro App sessions
// (A-app, B-app). Only the caro pages may be seated.
func TestCaroSeatsSkipNonCaroSessions(t *testing.T) {
	active := []string{"A", "B", "A-app", "B-app"}
	caro := []string{"A-app", "B-app"}
	players := filterCaroPlayers(active, caro)

	game := &CaroGame{}
	if sym, _ := seatCaroPlayer(game, players, "A-app"); sym != "X" {
		t.Fatalf("A-app: got %q want X", sym)
	}
	if sym, _ := seatCaroPlayer(game, players, "B-app"); sym != "O" {
		t.Fatalf("B-app: got %q want O", sym)
	}
	if sym, _ := seatCaroPlayer(game, players, "A"); sym != "" {
		t.Fatalf("Home session A got seat %q", sym)
	}
}

// The reported bug: the O seat was held by a session that is not a caro page,
// so the callee got "Bàn cờ đã đủ 2 người chơi" and both phones showed X.
func TestCaroSeatHeldByNonCaroSessionIsFreed(t *testing.T) {
	players := filterCaroPlayers([]string{"A", "A-app", "B-app"}, []string{"A-app", "B-app"})
	game := &CaroGame{PlayerX: "A-app", PlayerO: "A"}

	sym, changed := seatCaroPlayer(game, players, "B-app")
	if sym != "O" || !changed {
		t.Fatalf("B-app: got %q changed=%v, want O changed=true", sym, changed)
	}
	if game.PlayerX != "A-app" || game.PlayerO != "B-app" {
		t.Fatalf("seats X=%q O=%q", game.PlayerX, game.PlayerO)
	}
}

func TestCaroSeatOfGoneSessionIsFreed(t *testing.T) {
	game := &CaroGame{PlayerX: "A-app", PlayerO: "old-app"}
	sym, _ := seatCaroPlayer(game, []string{"A-app", "B-app"}, "B-app")
	if sym != "O" {
		t.Fatalf("got %q want O", sym)
	}
}

func TestCaroThirdPlayerGetsNoSeat(t *testing.T) {
	players := []string{"A-app", "B-app", "C-app"}
	game := &CaroGame{PlayerX: "A-app", PlayerO: "B-app"}
	sym, changed := seatCaroPlayer(game, players, "C-app")
	if sym != "" || changed {
		t.Fatalf("C-app: got %q changed=%v, want no seat", sym, changed)
	}
	if sym, _ := seatCaroPlayer(game, players, "B-app"); sym != "O" {
		t.Fatalf("seated player lost its seat: %q", sym)
	}
}
