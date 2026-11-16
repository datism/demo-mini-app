package dcas

import (
	"strings"
	"sync"
	"testing"
)

func fixedPNR() string { return "SL7K2Q" }

func TestFlightSearchMarksAgentPick(t *testing.T) {
	resp := buildFlightBookingReply(FlightBookingRequest{Action: "search", From: "HAN", To: "SGN", Adults: 1}, fixedPNR, newFlightSeatStore())
	if resp.Status != "success" || resp.Service != "flight-booking" {
		t.Fatalf("unexpected reply %+v", resp)
	}
	if len(resp.Flights) != len(flightSchedule) {
		t.Fatalf("expected %d flights, got %d", len(flightSchedule), len(resp.Flights))
	}
	picks := 0
	for _, f := range resp.Flights {
		if f.Pick {
			picks++
			if f.No != flightAgentPick {
				t.Errorf("pick on %s, want %s", f.No, flightAgentPick)
			}
		}
	}
	if picks != 1 {
		t.Errorf("expected exactly one agent pick, got %d", picks)
	}
	for _, f := range flightSchedule {
		if f.Pick {
			t.Fatalf("search must not mutate the shared schedule")
		}
	}
}

func TestFlightSelectReturnsSeatMap(t *testing.T) {
	resp := buildFlightBookingReply(FlightBookingRequest{Action: "select", Flight: "SL 213", Fare: "flex"}, fixedPNR, newFlightSeatStore())
	if resp.Status != "success" || resp.AgentSeat != flightAgentSeat || len(resp.TakenSeats) == 0 {
		t.Fatalf("unexpected reply %+v", resp)
	}
	if seatTaken(resp.AgentSeat) {
		t.Errorf("agent suggested a taken seat %s", resp.AgentSeat)
	}

	bad := buildFlightBookingReply(FlightBookingRequest{Action: "select", Flight: "XX 000"}, fixedPNR, newFlightSeatStore())
	if bad.Status != "error" {
		t.Errorf("unknown flight should fail, got %+v", bad)
	}
}

func TestFlightConfirmTotals(t *testing.T) {
	cases := []struct {
		name string
		req  FlightBookingRequest
		want int
		tax  int
	}{
		{"flex one adult", FlightBookingRequest{Flight: "SL 213", Fare: "flex", Seat: "4A", Adults: 1}, 2370000, 480000},
		{"saver two pax", FlightBookingRequest{Flight: "SL 213", Fare: "saver", Seats: []string{"5A", "5B"}, Adults: 1, Children: 1}, 3540000, 960000},
		{"extra legroom", FlightBookingRequest{Flight: "SL 201", Fare: "saver", Seat: "11B", Adults: 1}, 1629000, 480000},
		{"no pax defaults to one", FlightBookingRequest{Flight: "SL 201", Fare: "saver", Seat: "5A"}, 1479000, 480000},
	}
	for _, c := range cases {
		c.req.Action = "confirm"
		resp := buildFlightBookingReply(c.req, fixedPNR, newFlightSeatStore())
		if resp.Status != "confirmed" || resp.PNR != "SL7K2Q" {
			t.Errorf("%s: unexpected reply %+v", c.name, resp)
			continue
		}
		if resp.Total != c.want || resp.Tax != c.tax {
			t.Errorf("%s: total=%d tax=%d, want %d / %d", c.name, resp.Total, resp.Tax, c.want, c.tax)
		}
	}
}

func TestFlightConfirmRejectsBadInput(t *testing.T) {
	cases := []FlightBookingRequest{
		{Action: "confirm", Flight: "XX 000", Fare: "flex"},
		{Action: "confirm", Flight: "SL 213", Fare: "business"},
		{Action: "confirm", Flight: "SL 213", Fare: "flex", Seat: "1A"},
		{Action: "confirm", Flight: "SL 213", Fare: "flex", Seat: "21A"},
		{Action: "confirm", Flight: "SL 213", Fare: "flex", Seat: "4G"},
		{Action: "refund"},
	}
	for _, req := range cases {
		resp := buildFlightBookingReply(req, fixedPNR, newFlightSeatStore())
		if resp.Status != "error" || resp.PNR != "" || resp.Message == "" {
			t.Errorf("%+v: expected error, got %+v", req, resp)
		}
	}
}

func TestNewFlightPNR(t *testing.T) {
	for i := 0; i < 200; i++ {
		p := newFlightPNR()
		if len(p) != 6 || !strings.HasPrefix(p, "SL") || strings.ContainsAny(p[2:], "01IO") {
			t.Fatalf("bad PNR %q", p)
		}
	}
}

func TestFlightSeatIsSharedBetweenCallers(t *testing.T) {
	seats := newFlightSeatStore()
	book := func(seat string) FlightBookingResponse {
		return buildFlightBookingReply(FlightBookingRequest{Action: "confirm", Flight: "SL 213", Date: "2026-11-12", Fare: "flex", Seat: seat, Adults: 1}, fixedPNR, seats)
	}

	if r := book("4A"); r.Status != "confirmed" {
		t.Fatalf("first booking failed: %+v", r)
	}
	r := book("4A")
	if r.Status != "error" || r.Code != "seat_taken" || r.PNR != "" {
		t.Fatalf("second booking of 4A should fail with seat_taken, got %+v", r)
	}
	if !contains(r.TakenSeats, "4A") || r.AgentSeat == "" || r.AgentSeat == "4A" {
		t.Errorf("error reply should carry a fresh seat map and a new suggestion, got taken=%v agent=%s", r.TakenSeats, r.AgentSeat)
	}

	sel := buildFlightBookingReply(FlightBookingRequest{Action: "select", Flight: "SL 213", Date: "2026-11-12"}, fixedPNR, seats)
	if !contains(sel.TakenSeats, "4A") || sel.AgentSeat == "4A" {
		t.Errorf("select should show 4A taken and suggest another seat, got agent=%s", sel.AgentSeat)
	}

	other := buildFlightBookingReply(FlightBookingRequest{Action: "select", Flight: "SL 213", Date: "2026-11-13"}, fixedPNR, seats)
	if contains(other.TakenSeats, "4A") || other.AgentSeat != "4A" {
		t.Errorf("another date must have its own seats, got agent=%s", other.AgentSeat)
	}

	if r := book(""); r.Status != "error" {
		t.Errorf("booking without a seat must be refused, got %+v", r)
	}
}

func TestFlightSeatClaimIsAtomic(t *testing.T) {
	seats := newFlightSeatStore()
	var wg sync.WaitGroup
	var mu sync.Mutex
	won := 0
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if seats.claim("SL 213|2026-11-12", "5A") {
				mu.Lock()
				won++
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if won != 1 {
		t.Fatalf("exactly one caller should get the seat, got %d", won)
	}
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

func TestFlightSelectSuggestsSeatsTogetherForGroup(t *testing.T) {
	resp := buildFlightBookingReply(FlightBookingRequest{Action: "select", Flight: "SL 213", Date: "2026-11-12", Adults: 2, Children: 1}, fixedPNR, newFlightSeatStore())
	if len(resp.AgentSeats) != 3 {
		t.Fatalf("want 3 suggested seats for 3 passengers, got %v", resp.AgentSeats)
	}
	row := seatRow(resp.AgentSeats[0])
	for _, s := range resp.AgentSeats {
		if seatTaken(s) || seatRow(s) != row || isExtraRow(row) {
			t.Errorf("suggested seats should be free, standard and in one row, got %v", resp.AgentSeats)
		}
	}
	if resp.AgentSeat != resp.AgentSeats[0] {
		t.Errorf("agent_seat should stay the first suggested seat, got %s vs %v", resp.AgentSeat, resp.AgentSeats)
	}

	one := buildFlightBookingReply(FlightBookingRequest{Action: "select", Flight: "SL 213", Adults: 1}, fixedPNR, newFlightSeatStore())
	if len(one.AgentSeats) != 1 || one.AgentSeats[0] != flightAgentSeat {
		t.Errorf("single passenger should still get %s, got %v", flightAgentSeat, one.AgentSeats)
	}
}

func TestFlightConfirmBooksEverySeat(t *testing.T) {
	seats := newFlightSeatStore()
	req := FlightBookingRequest{Action: "confirm", Flight: "SL 201", Date: "2026-11-12", Fare: "saver", Seats: []string{"11B", "11C"}, Adults: 2}
	r := buildFlightBookingReply(req, fixedPNR, seats)
	if r.Status != "confirmed" {
		t.Fatalf("two-seat booking should succeed, got %+v", r)
	}
	if want := dayPrice(1479000, req.Date)*2 + 2*flightExtraSeatFee; r.Total != want {
		t.Errorf("extra legroom fee applies per seat: total %d, want %d", r.Total, want)
	}
	taken := seats.taken(flightKey("SL 201", "2026-11-12"))
	if !contains(taken, "11B") || !contains(taken, "11C") {
		t.Errorf("both seats should now be taken, got %v", taken)
	}
}

func TestFlightConfirmGroupIsAllOrNothing(t *testing.T) {
	seats := newFlightSeatStore()
	key := flightKey("SL 213", "2026-11-12")
	seats.claim(key, "5B")
	r := buildFlightBookingReply(FlightBookingRequest{Action: "confirm", Flight: "SL 213", Date: "2026-11-12", Fare: "flex", Seats: []string{"5A", "5B"}, Adults: 2}, fixedPNR, seats)
	if r.Status != "error" || r.Code != "seat_taken" || len(r.AgentSeats) != 2 {
		t.Fatalf("want seat_taken with 2 new suggestions, got %+v", r)
	}
	if seats.isTaken(key, "5A") {
		t.Errorf("5A must not stay booked when the group booking failed")
	}
}

func TestFlightConfirmRejectsBadSeatLists(t *testing.T) {
	for _, req := range []FlightBookingRequest{
		{Action: "confirm", Flight: "SL 213", Fare: "flex", Seats: []string{"5A", "5A"}, Adults: 2},
		{Action: "confirm", Flight: "SL 213", Fare: "flex", Seats: []string{"5A", "5B"}, Adults: 1},
		{Action: "confirm", Flight: "SL 213", Fare: "flex", Seats: []string{"5A", "21B"}, Adults: 2},
	} {
		if r := buildFlightBookingReply(req, fixedPNR, newFlightSeatStore()); r.Status != "error" {
			t.Errorf("seats %v for %d pax should be rejected, got %+v", req.Seats, req.Adults, r)
		}
	}
}

// SC_26: the search reports each flight's free seats for the date, and a flight without room for the
// whole group is refused at select.
func TestFlightFreeSeatsPerFlight(t *testing.T) {
	store := newFlightSeatStore()
	const date = "2026-11-20"
	base := flightRows*6 - len(flightTakenSeats)
	if !store.claim(flightKey("SL 213", date), "2A") {
		t.Fatal("claim failed")
	}
	resp := buildFlightBookingReply(FlightBookingRequest{Action: "search", From: "HAN", To: "SGN", Date: date, Adults: 1}, fixedPNR, store)
	for _, f := range resp.Flights {
		want := base
		if f.No == "SL 213" {
			want = base - 1
		}
		if f.Free != want {
			t.Errorf("%s: free=%d want %d", f.No, f.Free, want)
		}
	}
	tooMany := buildFlightBookingReply(FlightBookingRequest{Action: "select", Flight: "SL 213", Date: date, Adults: base}, fixedPNR, store)
	if tooMany.Status != "error" {
		t.Fatalf("select for %d passengers on a flight with %d free should fail", base, base-1)
	}
	ok := buildFlightBookingReply(FlightBookingRequest{Action: "select", Flight: "SL 213", Date: date, Adults: base - 1}, fixedPNR, store)
	if ok.Status != "success" || len(ok.AgentSeats) != base-1 {
		t.Fatalf("select for %d passengers: status=%s seats=%d", base-1, ok.Status, len(ok.AgentSeats))
	}
}
