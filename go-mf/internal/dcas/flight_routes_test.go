package dcas

import "testing"

func TestFlightSearchNewRoute(t *testing.T) {
	r := buildFlightBookingReply(FlightBookingRequest{Action: "search", From: "DAD", To: "DLI", Adults: 1}, fixedPNR, newFlightSeatStore())
	if r.Status != "success" || len(r.Flights) != 4 {
		t.Fatalf("DAD→DLI: %+v", r)
	}
	f := r.Flights[0]
	if f.Dep != "06:00" || f.Arr != "07:10" || f.Dur != "1g 10p" {
		t.Errorf("first flight %+v, want 06:00→07:10, 1g 10p", f)
	}
	if r.AgentPick != r.Flights[1].No || !r.Flights[1].Pick {
		t.Errorf("agent pick should be the 08:30 flight, got %q", r.AgentPick)
	}
	// Same flights the other way.
	back := buildFlightBookingReply(FlightBookingRequest{Action: "search", From: "DLI", To: "DAD"}, fixedPNR, newFlightSeatStore())
	if len(back.Flights) != 4 || back.Flights[0].No != f.No {
		t.Errorf("DLI→DAD should fly the same flights, got %+v", back.Flights)
	}
}

func TestFlightSearchRouteWithoutFlights(t *testing.T) {
	for _, pair := range [][2]string{{"HAN", "HPH"}, {"VDH", "DAD"}, {"HPH", "VDH"}, {"HAN", "HAN"}, {"HAN", "XYZ"}} {
		r := buildFlightBookingReply(FlightBookingRequest{Action: "search", From: pair[0], To: pair[1]}, fixedPNR, newFlightSeatStore())
		if r.Status != "error" || len(r.Flights) != 0 {
			t.Errorf("%s→%s should have no flights, got %+v", pair[0], pair[1], r)
		}
	}
}

func TestFlightSearchDefaultsToHanSgn(t *testing.T) {
	r := buildFlightBookingReply(FlightBookingRequest{Action: "search"}, fixedPNR, newFlightSeatStore())
	if r.AgentPick != "SL 213" || len(r.Flights) != 4 || r.Flights[0].No != "SL 201" || r.Flights[0].Price != 1479000 {
		t.Fatalf("empty route should give HAN→SGN undated: %+v", r)
	}
}

func TestFlightPriceFollowsWeekday(t *testing.T) {
	cases := map[string]int{
		"2026-11-02": 1479000, // Monday 100%
		"2026-11-05": 1257000, // Thursday 85%
		"2026-11-07": 1775000, // Saturday 120%
		"bad":        1479000,
	}
	for date, want := range cases {
		if got := dayPrice(1479000, date); got != want {
			t.Errorf("dayPrice(1479000, %s) = %d, want %d", date, got, want)
		}
	}
	r := buildFlightBookingReply(FlightBookingRequest{Action: "search", From: "HAN", To: "SGN", Date: "2026-11-07"}, fixedPNR, newFlightSeatStore())
	if r.Flights[0].Price != 1775000 {
		t.Errorf("search on Saturday: first fare %d, want 1775000", r.Flights[0].Price)
	}
}

func TestFlightNumbersAreUnique(t *testing.T) {
	n := 0
	for _, day := range flightDays {
		n += len(day)
	}
	if n != len(flightByNo) || n != 4*(len(flightRoutes)+1) {
		t.Fatalf("%d flights, %d distinct numbers", n, len(flightByNo))
	}
	for no, f := range flightByNo {
		if f.Price < flightMinPrice {
			t.Errorf("%s costs %d, below the minimum", no, f.Price)
		}
	}
}

func TestFlightConfirmNewRouteIsRecorded(t *testing.T) {
	seats, accts := newFlightSeatStore(), newFlightAccountStore()
	search := buildFlightBookingReply(FlightBookingRequest{Action: "search", From: "SGN", To: "VDH", Date: "2026-11-06"}, fixedPNR, seats)
	f := search.Flights[0]
	req := FlightBookingRequest{Action: "confirm", Flight: f.No, From: "SGN", To: "VDH", Date: "2026-11-06", Fare: "saver", Seats: []string{"5A"}, Adults: 1}
	r := reply(req, "1002", seats, accts)
	if r.Status != "confirmed" || r.Total != f.Price {
		t.Fatalf("confirm %s: %+v, want total %d (the searched price)", f.No, r, f.Price)
	}
	prof := reply(FlightBookingRequest{Action: "profile"}, "1002", seats, accts)
	top := prof.Bookings[0]
	if top.Flight != f.No || top.From != "SGN" || top.To != "VDH" || top.Dep != f.Dep || top.Arr != f.Arr {
		t.Errorf("history top %+v", top)
	}
}
