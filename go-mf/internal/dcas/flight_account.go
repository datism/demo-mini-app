package dcas

import (
	"encoding/json"
	"strings"
	"sync"
	"time"
)

// Caller accounts for the 1900 demo. The caller's phone number is the account:
// the IMS-AS already authenticated it, so the page needs no login. Bookings and
// SkyLotus Club points live in memory (cleared when DCAS restarts), like seats.

const (
	flightGuest        = "guest" // used when the caller cannot be resolved
	flightPointsPerVND = 10000   // 1 point per 10.000₫ of the total
	flightGoldPoints   = 5000
	flightPlatPoints   = 15000
)

type FlightBookingRecord struct {
	PNR      string   `json:"pnr"`
	Flight   string   `json:"flight"`
	From     string   `json:"from"`
	To       string   `json:"to"`
	Date     string   `json:"date"`
	Dep      string   `json:"dep"`
	Arr      string   `json:"arr"`
	Fare     string   `json:"fare"`
	Seats    []string `json:"seats"`
	Pax      int      `json:"pax"`
	Total    int      `json:"total"`
	Status   string   `json:"status"` // "upcoming" or "flown"
	BookedAt string   `json:"booked_at"`
}

// FlightMember is the SkyLotus Club standing. NextTier is "" at the top tier.
type FlightMember struct {
	Tier     string `json:"tier"` // "silver", "gold" or "platinum"
	Points   int    `json:"points"`
	NextTier string `json:"next_tier,omitempty"`
	ToNext   int    `json:"to_next,omitempty"`
}

type flightAccount struct {
	points   int
	bookings []FlightBookingRecord // newest first
}

type flightAccountStore struct {
	mu       sync.Mutex
	accounts map[string]*flightAccount
}

func newFlightAccountStore() *flightAccountStore {
	return &flightAccountStore{accounts: make(map[string]*flightAccount)}
}

var flightAccounts = newFlightAccountStore()

func memberFor(points int) FlightMember {
	m := FlightMember{Points: points}
	switch {
	case points >= flightPlatPoints:
		m.Tier = "platinum"
	case points >= flightGoldPoints:
		m.Tier, m.NextTier, m.ToNext = "gold", "platinum", flightPlatPoints-points
	default:
		m.Tier, m.NextTier, m.ToNext = "silver", "gold", flightGoldPoints-points
	}
	return m
}

// account returns the caller's account, creating an empty one on first use.
// The caller must hold st.mu.
func (st *flightAccountStore) account(phone string) *flightAccount {
	a := st.accounts[phone]
	if a == nil {
		a = &flightAccount{}
		st.accounts[phone] = a
	}
	return a
}

// profile returns a copy of the caller's standing and bookings.
func (st *flightAccountStore) profile(phone string) (FlightMember, []FlightBookingRecord) {
	st.mu.Lock()
	defer st.mu.Unlock()
	a := st.account(phone)
	return memberFor(a.points), append([]FlightBookingRecord(nil), a.bookings...)
}

// record adds a confirmed booking to the history and awards its points.
func (st *flightAccountStore) record(phone string, rec FlightBookingRecord) (int, FlightMember) {
	st.mu.Lock()
	defer st.mu.Unlock()
	a := st.account(phone)
	earned := rec.Total / flightPointsPerVND
	a.points += earned
	a.bookings = append([]FlightBookingRecord{rec}, a.bookings...)
	return earned, memberFor(a.points)
}

// buildFlightReplyForCaller adds the account actions (profile, support) and the
// booking history on top of buildFlightBookingReply. Only a confirmed booking
// touches the account; a lost seat or a bad request records nothing.
func buildFlightReplyForCaller(req FlightBookingRequest, caller string, pnr func() string, seats *flightSeatStore, accts *flightAccountStore) FlightBookingResponse {
	resp := FlightBookingResponse{Service: "flight-booking", Action: req.Action, Status: "success"}
	switch req.Action {
	case "profile":
		member, bookings := accts.profile(caller)
		resp.Member, resp.Bookings = &member, bookings
		return resp
	case "support":
		switch req.Topic {
		case "agent":
			// The phone rings the agent on the verified screen and nobody answers (demo).
			resp.Message, resp.MessageEn = "Tổng đài viên hiện không trả lời, vui lòng thử lại sau", "No agent answered, please try again later"
		case "callback":
			resp.Message, resp.MessageEn = "Chúng tôi sẽ gọi lại cho bạn trong 15 phút", "We will call you back within 15 minutes"
		default:
			return flightError(resp, "Yêu cầu hỗ trợ không hợp lệ")
		}
		return resp
	}

	resp = buildFlightBookingReply(req, pnr, seats)
	if req.Action == "confirm" && resp.Status == "confirmed" {
		f := findFlight(req.Flight)
		from, to := req.From, req.To
		if from == "" || to == "" {
			from, to = "HAN", "SGN"
		}
		earned, member := accts.record(caller, FlightBookingRecord{
			PNR: resp.PNR, Flight: f.No, From: from, To: to, Date: req.Date, Dep: f.Dep, Arr: f.Arr,
			Fare: req.Fare, Seats: requestedSeats(req), Pax: paxCount(req), Total: resp.Total,
			Status: "upcoming", BookedAt: time.Now().Format("2006-01-02"),
		})
		resp.PointsEarned, resp.Member = earned, &member
	}
	return resp
}

// flightCaller resolves the phone number that dialled 1900 for this ADC session.
// DCAS only knows the call id; the IMS-AS keeps the caller in the session it
// stores under that id in the shared Redis.
func (s *DCASServer) flightCaller(sessionID string) string {
	s.mu.RLock()
	callID := s.sessionCalls[sessionID]
	s.mu.RUnlock()
	if callID == "" || redisClient == nil {
		return flightGuest
	}
	// MF sends "<Call-ID>@<MF ip>". Try the id as is first, in case the SIP
	// Call-ID itself contains '@', then without the MF route.
	for _, id := range []string{flightTrimSlot(callID), flightBaseCallID(callID)} {
		data, err := redisClient.Get(ctx, "imsas:session:"+id).Result()
		if err == nil {
			if caller := flightCallerFromSession(data); caller != flightGuest {
				return caller
			}
		}
	}
	return flightGuest
}

// flightCallerFromSession reads the caller out of the IMS-AS session JSON.
func flightCallerFromSession(data string) string {
	var s struct {
		Caller string `json:"caller"`
	}
	if json.Unmarshal([]byte(data), &s) != nil || s.Caller == "" {
		return flightGuest
	}
	return s.Caller
}

// flightBaseCallID drops MF's "@<ip>" route and the "-app"/"-adc" suffix of an
// ADC slot context so the id matches the call the IMS-AS stored.
func flightBaseCallID(callID string) string {
	return flightTrimSlot(stripMFRoute(callID))
}

// flightTrimSlot drops the "-app"/"-adc" suffix of an ADC slot context.
func flightTrimSlot(callID string) string {
	callID = strings.TrimSuffix(callID, "-app")
	return strings.TrimSuffix(callID, "-adc")
}
