package dcas

import "testing"

func confirmReq(seat string) FlightBookingRequest {
	return FlightBookingRequest{Action: "confirm", Flight: "SL 213", From: "HAN", To: "SGN", Date: "2026-11-12", Fare: "flex", Seat: seat, Adults: 1}
}

func reply(req FlightBookingRequest, caller string, seats *flightSeatStore, accts *flightAccountStore) FlightBookingResponse {
	return buildFlightReplyForCaller(req, caller, fixedPNR, seats, accts)
}

func TestFlightProfileSeedsNewCaller(t *testing.T) {
	accts := newFlightAccountStore()
	r := reply(FlightBookingRequest{Action: "profile"}, "1001", newFlightSeatStore(), accts)
	if r.Status != "success" || r.Member == nil {
		t.Fatalf("unexpected reply %+v", r)
	}
	if len(r.Bookings) != 2 {
		t.Fatalf("expected 2 seeded bookings, got %d", len(r.Bookings))
	}
	if r.Member.Tier != "silver" || r.Member.NextTier != "gold" || r.Member.ToNext <= 0 {
		t.Errorf("unexpected member %+v", r.Member)
	}
	statuses := map[string]int{}
	for _, b := range r.Bookings {
		statuses[b.Status]++
	}
	if statuses["flown"] != 1 || statuses["upcoming"] != 1 {
		t.Errorf("seed should hold one flown and one upcoming booking, got %v", statuses)
	}
}

func TestFlightTierBoundaries(t *testing.T) {
	cases := []struct {
		points int
		tier   string
		next   string
		toNext int
	}{
		{0, "silver", "gold", 5000},
		{4999, "silver", "gold", 1},
		{5000, "gold", "platinum", 10000},
		{14999, "gold", "platinum", 1},
		{15000, "platinum", "", 0},
		{90000, "platinum", "", 0},
	}
	for _, c := range cases {
		m := memberFor(c.points)
		if m.Tier != c.tier || m.NextTier != c.next || m.ToNext != c.toNext || m.Points != c.points {
			t.Errorf("memberFor(%d) = %+v, want tier=%s next=%s toNext=%d", c.points, m, c.tier, c.next, c.toNext)
		}
	}
}

func TestFlightConfirmRecordsBookingAndPoints(t *testing.T) {
	seats, accts := newFlightSeatStore(), newFlightAccountStore()
	before := reply(FlightBookingRequest{Action: "profile"}, "1001", seats, accts)

	req := confirmReq("4A")
	total := dayPrice(1770000, req.Date) + flightFlexSurcharge // SL 213 Flex, one adult, on that weekday
	r := reply(req, "1001", seats, accts)
	if r.Status != "confirmed" || r.Total != total {
		t.Fatalf("unexpected confirm %+v, want total %d", r, total)
	}
	earned := total / flightPointsPerVND
	if r.PointsEarned != earned {
		t.Errorf("points earned = %d, want %d (1 per 10.000₫)", r.PointsEarned, earned)
	}
	if r.Member == nil || r.Member.Points != before.Member.Points+earned {
		t.Errorf("member after confirm = %+v, before = %+v", r.Member, before.Member)
	}

	after := reply(FlightBookingRequest{Action: "profile"}, "1001", seats, accts)
	if len(after.Bookings) != len(before.Bookings)+1 {
		t.Fatalf("expected one more booking, got %d -> %d", len(before.Bookings), len(after.Bookings))
	}
	top := after.Bookings[0]
	if top.PNR != "SL7K2Q" || top.Flight != "SL 213" || top.Status != "upcoming" || top.Total != total ||
		top.From != "HAN" || top.To != "SGN" || top.Date != "2026-11-12" || top.Dep != "08:30" || top.Arr != "10:40" ||
		len(top.Seats) != 1 || top.Seats[0] != "4A" || top.Pax != 1 {
		t.Errorf("newest booking should lead the history with the confirmed details, got %+v", top)
	}
}

func TestFlightAccountsAreIsolatedPerCaller(t *testing.T) {
	seats, accts := newFlightSeatStore(), newFlightAccountStore()
	reply(confirmReq("4A"), "1001", seats, accts)

	other := reply(FlightBookingRequest{Action: "profile"}, "1002", seats, accts)
	if len(other.Bookings) != 2 {
		t.Errorf("another caller must keep only the seed, got %d bookings", len(other.Bookings))
	}
}

func TestFlightFailedConfirmRecordsNothing(t *testing.T) {
	seats, accts := newFlightSeatStore(), newFlightAccountStore()
	reply(confirmReq("4A"), "1001", seats, accts)
	before := reply(FlightBookingRequest{Action: "profile"}, "1002", seats, accts)

	lost := reply(confirmReq("4A"), "1002", seats, accts)
	if lost.Status != "error" || lost.Code != "seat_taken" || lost.PointsEarned != 0 {
		t.Fatalf("expected seat_taken, got %+v", lost)
	}
	bad := reply(FlightBookingRequest{Action: "confirm", Flight: "SL 213", Fare: "nope", Seat: "5A", Adults: 1}, "1002", seats, accts)
	if bad.Status != "error" {
		t.Fatalf("expected error, got %+v", bad)
	}

	after := reply(FlightBookingRequest{Action: "profile"}, "1002", seats, accts)
	if len(after.Bookings) != len(before.Bookings) || after.Member.Points != before.Member.Points {
		t.Errorf("failed confirms changed the account: %+v -> %+v", before.Member, after.Member)
	}
}

func TestFlightConfirmCanUpgradeTier(t *testing.T) {
	seats, accts := newFlightSeatStore(), newFlightAccountStore()
	r := reply(confirmReq("4A"), "1001", seats, accts)
	if r.Member == nil || r.Member.Tier != "gold" {
		t.Errorf("seed points plus this booking should reach gold, got %+v", r.Member)
	}
}

func TestFlightSupportAcksEachTopic(t *testing.T) {
	for _, topic := range []string{"agent", "callback"} {
		r := reply(FlightBookingRequest{Action: "support", Topic: topic}, "1001", newFlightSeatStore(), newFlightAccountStore())
		if r.Status != "success" || r.Message == "" || r.MessageEn == "" {
			t.Errorf("support/%s: unexpected reply %+v", topic, r)
		}
	}
	bad := reply(FlightBookingRequest{Action: "support", Topic: "refund"}, "1001", newFlightSeatStore(), newFlightAccountStore())
	if bad.Status != "error" {
		t.Errorf("unknown support topic should fail, got %+v", bad)
	}
}

func TestFlightCallerFromIMSSession(t *testing.T) {
	cases := []struct{ name, data, want string }{
		{"caller", `{"callID":"abc","caller":"1001","callee":"1900"}`, "1001"},
		{"empty", ``, flightGuest},
		{"no caller", `{"callID":"abc"}`, flightGuest},
		{"not json", `nope`, flightGuest},
	}
	for _, c := range cases {
		if got := flightCallerFromSession(c.data); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
}

func TestFlightBaseCallID(t *testing.T) {
	cases := map[string]string{"abc": "abc", "abc-app": "abc", "abc-adc": "abc", "app-abc": "app-abc", "": ""}
	for in, want := range cases {
		if got := flightBaseCallID(in); got != want {
			t.Errorf("flightBaseCallID(%q) = %q, want %q", in, got, want)
		}
	}
}
