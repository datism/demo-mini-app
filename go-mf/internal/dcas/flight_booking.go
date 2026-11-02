package dcas

import (
	"encoding/json"
	"log"
	"math/rand"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Flight booking demo (SkyLotus Air, fictional airline). The page sends
// search / select / confirm over ADC stream 1000; DCAS plays the agent:
// it returns the flight list, suggests a flight and a seat, and issues the PNR.
// All data is mock; no real payment is taken. Seats booked during a run are
// shared by every caller (in memory, cleared when DCAS restarts).

const (
	flightFlexSurcharge = 600000 // VND, Flex over Saver
	flightExtraSeatFee  = 150000 // VND, extra-legroom rows
	flightTaxPerPax     = 480000 // VND, already included in listed fares
	flightAgentPick     = "SL 213"
	flightAgentSeat     = "4A"
	flightRows          = 20
)

type FlightBookingRequest struct {
	Service  string   `json:"service"`
	Action   string   `json:"action"`
	From     string   `json:"from,omitempty"`
	To       string   `json:"to,omitempty"`
	Date     string   `json:"date,omitempty"`
	Adults   int      `json:"adults,omitempty"`
	Children int      `json:"children,omitempty"`
	Flight   string   `json:"flight,omitempty"`
	Fare     string   `json:"fare,omitempty"`
	Seat     string   `json:"seat,omitempty"`  // single seat, kept for older pages
	Seats    []string `json:"seats,omitempty"` // one seat per passenger
	Name     string   `json:"name,omitempty"`
	Payment  string   `json:"payment,omitempty"`
	Topic    string   `json:"topic,omitempty"` // support: "agent" or "callback"
}

type FlightOption struct {
	No    string `json:"no"`
	Dep   string `json:"dep"`
	Arr   string `json:"arr"`
	Dur   string `json:"dur"`
	Price int    `json:"price"` // Saver fare per passenger, taxes included
	Pick  bool   `json:"pick,omitempty"`
}

type FlightBookingResponse struct {
	Service    string         `json:"service"`
	Action     string         `json:"action"`
	Status     string         `json:"status"`
	Message    string         `json:"message,omitempty"`
	MessageEn  string         `json:"message_en,omitempty"`
	Flights    []FlightOption `json:"flights,omitempty"`
	AgentPick  string         `json:"agent_pick,omitempty"`
	TakenSeats []string       `json:"taken_seats,omitempty"`
	ExtraRows  []int          `json:"extra_rows,omitempty"`
	AgentSeat  string         `json:"agent_seat,omitempty"`
	AgentSeats []string       `json:"agent_seats,omitempty"` // one per passenger, side by side when possible
	Code       string         `json:"code,omitempty"`        // "seat_taken" when another caller got the seat first
	PNR        string         `json:"pnr,omitempty"`
	Total      int            `json:"total,omitempty"`
	Tax        int            `json:"tax,omitempty"`

	Member       *FlightMember         `json:"member,omitempty"`        // profile and confirm
	Bookings     []FlightBookingRecord `json:"bookings,omitempty"`      // profile: newest first
	PointsEarned int                   `json:"points_earned,omitempty"` // confirm
}

// flightSchedule is the Hà Nội ↔ TP. Hồ Chí Minh day, flown both ways. The
// sample bookings and SL 213 (the agent pick) refer to it, so it stays as is.
var flightSchedule = []FlightOption{
	{No: "SL 201", Dep: "06:00", Arr: "08:10", Dur: "2g 10p", Price: 1479000},
	{No: "SL 213", Dep: "08:30", Arr: "10:40", Dur: "2g 10p", Price: 1770000},
	{No: "SL 225", Dep: "11:15", Arr: "13:20", Dur: "2g 05p", Price: 1670000},
	{No: "SL 241", Dep: "19:45", Arr: "21:55", Dur: "2g 10p", Price: 1570000},
}

// flightAirports: the cities SkyLotus flies to. The page keeps the same list.
var flightAirports = map[string]string{
	"HAN": "Hà Nội", "SGN": "TP. Hồ Chí Minh", "DAD": "Đà Nẵng",
	"HPH": "Hải Phòng", "VDH": "Quảng Bình", "DLI": "Đà Lạt",
}

// flightRoute is one city pair, flown both ways with the same four flights.
// Route k numbers its flights SL 3k1..3k4 (k=10, 11 give SL 401.., SL 411..).
// HAN–HPH, DAD–VDH and HPH–VDH are missing on purpose: too close to fly.
type flightRoute struct {
	A, B string
	Mins int
}

var flightRoutes = []flightRoute{
	{"HAN", "DAD", 80}, {"HAN", "DLI", 110}, {"HAN", "VDH", 70},
	{"SGN", "DAD", 85}, {"SGN", "HPH", 125}, {"SGN", "VDH", 95}, {"SGN", "DLI", 55},
	{"DAD", "HPH", 80}, {"DAD", "DLI", 70}, {"HPH", "DLI", 115}, {"VDH", "DLI", 90},
}

var (
	flightDepartures = []string{"06:00", "08:30", "11:15", "19:45"}
	flightSpread     = []int{0, 291000, 191000, 91000} // over the route's first flight, as on HAN–SGN
)

const (
	flightBaseMins  = 130     // HAN–SGN, whose first flight costs flightBasePrice
	flightBasePrice = 1479000 // VND
	flightMinPrice  = 790000  // VND, cheapest first flight of any route
)

// flightDays maps a route key ("DAD-HAN", sorted) to its flights; flightByNo
// indexes every flight. Built once from flightSchedule and flightRoutes.
var flightDays, flightByNo = buildFlightNetwork()

func routeKey(a, b string) string {
	if a > b {
		a, b = b, a
	}
	return a + "-" + b
}

func buildFlightNetwork() (map[string][]FlightOption, map[string]*FlightOption) {
	days := map[string][]FlightOption{routeKey("HAN", "SGN"): flightSchedule}
	for k, r := range flightRoutes {
		first := flightBasePrice * r.Mins / flightBaseMins
		if first < flightMinPrice {
			first = flightMinPrice
		}
		first = (first + 500) / 1000 * 1000
		list := make([]FlightOption, len(flightDepartures))
		for i, dep := range flightDepartures {
			list[i] = FlightOption{
				No:    "SL " + strconv.Itoa(300+10*(k+1)+i+1),
				Dep:   dep,
				Arr:   addMinutes(dep, r.Mins),
				Dur:   flightDuration(r.Mins),
				Price: first + flightSpread[i],
			}
		}
		days[routeKey(r.A, r.B)] = list
	}
	byNo := map[string]*FlightOption{}
	for key := range days {
		for i := range days[key] {
			byNo[days[key][i].No] = &days[key][i]
		}
	}
	return days, byNo
}

func addMinutes(hhmm string, mins int) string {
	t, _ := time.Parse("15:04", hhmm)
	return t.Add(time.Duration(mins) * time.Minute).Format("15:04")
}

// flightDuration formats 85 as "1g 25p" and 55 as "55p".
func flightDuration(mins int) string {
	if mins < 60 {
		return strconv.Itoa(mins) + "p"
	}
	m := strconv.Itoa(mins % 60)
	if len(m) == 1 {
		m = "0" + m
	}
	return strconv.Itoa(mins/60) + "g " + m + "p"
}

// flightDayPercent: fares follow the weekday (Sunday first). The page uses the
// same table for the lowest fare under each date.
var flightDayPercent = [7]int{115, 100, 95, 90, 85, 110, 120}

// dayPrice is price on date ("2006-01-02"), rounded to 1.000₫; no or a bad date
// leaves it unchanged.
func dayPrice(price int, date string) int {
	d, err := time.Parse("2006-01-02", date)
	if err != nil {
		return price
	}
	return (price*flightDayPercent[d.Weekday()]/100 + 500) / 1000 * 1000
}

var flightTakenSeats = []string{
	"1A", "1B", "1F", "2E", "2F", "3C", "3D", "4B", "4C", "5E", "6A", "6F", "7C", "7D",
	"8B", "9A", "9F", "10C", "10D", "11A", "12B", "12C", "13D", "13E", "14A", "14F",
	"15C", "16B", "16D", "17E", "17F", "18A", "19C", "20D",
}

var flightExtraRows = []int{1, 11}

var flightRand = rand.New(rand.NewSource(time.Now().UnixNano()))

// flightSeatStore holds seats booked by callers, per flight and date.
type flightSeatStore struct {
	mu     sync.Mutex
	booked map[string]map[string]bool
}

func newFlightSeatStore() *flightSeatStore {
	return &flightSeatStore{booked: make(map[string]map[string]bool)}
}

var flightSeats = newFlightSeatStore()

func flightKey(flight, date string) string { return flight + "|" + date }

// taken returns the fixed taken seats plus the ones booked so far.
func (st *flightSeatStore) taken(key string) []string {
	st.mu.Lock()
	defer st.mu.Unlock()
	out := append([]string(nil), flightTakenSeats...)
	for seat := range st.booked[key] {
		out = append(out, seat)
	}
	return out
}

func (st *flightSeatStore) isTaken(key, seat string) bool {
	st.mu.Lock()
	defer st.mu.Unlock()
	return seatTaken(seat) || st.booked[key][seat]
}

// claim books seat if it is still free; check and set happen under one lock.
func (st *flightSeatStore) claim(key, seat string) bool {
	return st.claimAll(key, []string{seat}) == ""
}

// claimAll books every seat or none. It returns the first seat that was
// already taken, or "" on success.
func (st *flightSeatStore) claimAll(key string, seats []string) string {
	st.mu.Lock()
	defer st.mu.Unlock()
	for _, seat := range seats {
		if seatTaken(seat) || st.booked[key][seat] {
			return seat
		}
	}
	if st.booked[key] == nil {
		st.booked[key] = make(map[string]bool)
	}
	for _, seat := range seats {
		st.booked[key][seat] = true
	}
	return ""
}

// agentSeat suggests 4A, or the frontmost free standard window seat once 4A is gone.
func (st *flightSeatStore) agentSeat(key string) string {
	if !st.isTaken(key, flightAgentSeat) {
		return flightAgentSeat
	}
	for row := 2; row <= flightRows; row++ {
		for _, col := range []string{"A", "F"} {
			seat := strconv.Itoa(row) + col
			if !isExtraRow(row) && !st.isTaken(key, seat) {
				return seat
			}
		}
	}
	return ""
}

// agentSeats suggests n seats. One passenger gets agentSeat; a group gets n free
// standard seats next to each other in one row (a block touching a window first),
// or the frontmost free standard seats when no row has room.
func (st *flightSeatStore) agentSeats(key string, n int) []string {
	if n <= 1 {
		if seat := st.agentSeat(key); seat != "" {
			return []string{seat}
		}
		return nil
	}
	cols := []string{"A", "B", "C", "D", "E", "F"}
	if n <= len(cols) {
		for _, windowOnly := range []bool{true, false} {
			for row := 2; row <= flightRows; row++ {
				if isExtraRow(row) {
					continue
				}
				for start := 0; start+n <= len(cols); start++ {
					if windowOnly && start != 0 && start+n != len(cols) {
						continue
					}
					block := make([]string, 0, n)
					for _, col := range cols[start : start+n] {
						seat := strconv.Itoa(row) + col
						if st.isTaken(key, seat) {
							break
						}
						block = append(block, seat)
					}
					if len(block) == n {
						return block
					}
				}
			}
		}
	}
	var out []string
	for row := 2; row <= flightRows && len(out) < n; row++ {
		if isExtraRow(row) {
			continue
		}
		for _, col := range cols {
			seat := strconv.Itoa(row) + col
			if len(out) < n && !st.isTaken(key, seat) {
				out = append(out, seat)
			}
		}
	}
	return out
}

func paxCount(req FlightBookingRequest) int {
	if pax := req.Adults + req.Children; pax > 0 {
		return pax
	}
	return 1
}

// requestedSeats returns the seats list, falling back to the single seat field.
func requestedSeats(req FlightBookingRequest) []string {
	if len(req.Seats) > 0 {
		return req.Seats
	}
	if req.Seat != "" {
		return []string{req.Seat}
	}
	return nil
}

func agentSeatMessage(seats []string) (string, string) {
	if len(seats) == 1 {
		return "Tổng đài viên gợi ý ghế cửa sổ " + seats[0], "Agent suggested this seat"
	}
	return "Tổng đài viên gợi ý ghế " + strings.Join(seats, ", ") + " ngồi cạnh nhau", "Agent suggested these seats"
}

func (s *DCASServer) handleFlightBookingMessage(sessionID string, data []byte) {
	log.Printf("[DCAS] Flight Booking Event from Session %s: %s", sessionID, string(data))

	var req FlightBookingRequest
	if err := json.Unmarshal(data, &req); err != nil {
		log.Printf("[DCAS] Failed to parse Flight Booking JSON: %v", err)
	}

	caller := s.flightCaller(sessionID)
	resp := buildFlightReplyForCaller(req, caller, newFlightPNR, flightSeats, flightAccounts)
	if resp.Status == "confirmed" {
		log.Printf("[DCAS] Caller %s (session %s) booked %s (%s, seats %s) PNR=%s total=%d", caller, sessionID, req.Flight, req.Fare, strings.Join(requestedSeats(req), ","), resp.PNR, resp.Total)
	}
	if req.Action == "support" && resp.Status == "success" {
		log.Printf("[DCAS] Caller %s (session %s) asked for support: %s", caller, sessionID, req.Topic)
	}
	s.sendToSession(sessionID, "1000", resp)
}

// buildFlightBookingReply takes the PNR generator and seat store as arguments
// so tests can pin the code and use a fresh plane.
func buildFlightBookingReply(req FlightBookingRequest, pnr func() string, seats *flightSeatStore) FlightBookingResponse {
	resp := FlightBookingResponse{Service: "flight-booking", Action: req.Action, Status: "success"}

	switch req.Action {
	case "search":
		from, to := req.From, req.To
		if from == "" && to == "" {
			from, to = "HAN", "SGN" // pages before the city picker
		}
		if flightAirports[from] == "" || flightAirports[to] == "" || from == to {
			return flightError(resp, "Tuyến bay không hợp lệ")
		}
		day, ok := flightDays[routeKey(from, to)]
		if !ok {
			resp = flightError(resp, "Chưa có chuyến bay thẳng giữa "+flightAirports[from]+" và "+flightAirports[to])
			resp.MessageEn = "No direct flight on this route"
			return resp
		}
		pick := day[1].No
		if from+to == "HANSGN" || from+to == "SGNHAN" {
			pick = flightAgentPick
		}
		resp.Flights = make([]FlightOption, len(day))
		copy(resp.Flights, day)
		for i := range resp.Flights {
			resp.Flights[i].Price = dayPrice(resp.Flights[i].Price, req.Date)
			resp.Flights[i].Pick = resp.Flights[i].No == pick
		}
		resp.AgentPick = pick
		resp.Message = "Tổng đài viên gợi ý chuyến " + pick
		resp.MessageEn = "Agent suggested this flight"
	case "select":
		if findFlight(req.Flight) == nil {
			return flightError(resp, "Không tìm thấy chuyến bay")
		}
		key := flightKey(req.Flight, req.Date)
		resp.TakenSeats = seats.taken(key)
		resp.ExtraRows = flightExtraRows
		resp.AgentSeats = seats.agentSeats(key, paxCount(req))
		if len(resp.AgentSeats) > 0 {
			resp.AgentSeat = resp.AgentSeats[0]
			resp.Message, resp.MessageEn = agentSeatMessage(resp.AgentSeats)
		}
	case "confirm":
		f := findFlight(req.Flight)
		if f == nil {
			return flightError(resp, "Không tìm thấy chuyến bay")
		}
		if req.Fare != "saver" && req.Fare != "flex" {
			return flightError(resp, "Hạng vé không hợp lệ")
		}
		want := requestedSeats(req)
		if len(want) > paxCount(req) {
			return flightError(resp, "Số ghế nhiều hơn số hành khách")
		}
		seen := map[string]bool{}
		for _, seat := range want {
			if !validSeat(seat) || seen[seat] {
				return flightError(resp, "Ghế không hợp lệ")
			}
			seen[seat] = true
		}
		key := flightKey(req.Flight, req.Date)
		if lost := seats.claimAll(key, want); lost != "" {
			resp = flightError(resp, "Ghế "+lost+" vừa có người đặt, vui lòng chọn ghế khác")
			resp.MessageEn = "Seat just taken, please pick another"
			resp.Code = "seat_taken"
			resp.TakenSeats = seats.taken(key)
			resp.ExtraRows = flightExtraRows
			resp.AgentSeats = seats.agentSeats(key, paxCount(req))
			if len(resp.AgentSeats) > 0 {
				resp.AgentSeat = resp.AgentSeats[0]
			}
			return resp
		}
		resp.Status = "confirmed"
		resp.PNR = pnr()
		resp.Total, resp.Tax = flightTotal(*f, req)
		resp.Message = "Đặt vé thành công · vé đã gửi qua SMS"
		resp.MessageEn = "Booking confirmed"
	default:
		return flightError(resp, "Yêu cầu không được hỗ trợ")
	}
	return resp
}

func flightError(resp FlightBookingResponse, msg string) FlightBookingResponse {
	resp.Status = "error"
	resp.Message = msg
	return resp
}

func findFlight(no string) *FlightOption {
	return flightByNo[no]
}

func seatTaken(seat string) bool {
	for _, t := range flightTakenSeats {
		if t == seat {
			return true
		}
	}
	return false
}

func seatRow(seat string) int {
	n := 0
	for _, c := range seat {
		if c < '0' || c > '9' {
			break
		}
		n = n*10 + int(c-'0')
	}
	return n
}

func isExtraRow(row int) bool {
	for _, r := range flightExtraRows {
		if r == row {
			return true
		}
	}
	return false
}

// validSeat accepts 1A..20F.
func validSeat(seat string) bool {
	row := seatRow(seat)
	if row < 1 || row > flightRows || len(seat) != len(strconv.Itoa(row))+1 {
		return false
	}
	col := seat[len(seat)-1]
	return col >= 'A' && col <= 'F'
}

// flightTotal returns the amount due and the tax part of it.
func flightTotal(f FlightOption, req FlightBookingRequest) (int, int) {
	pax := paxCount(req)
	perPax := dayPrice(f.Price, req.Date)
	if req.Fare == "flex" {
		perPax += flightFlexSurcharge
	}
	total := perPax * pax
	for _, seat := range requestedSeats(req) {
		if isExtraRow(seatRow(seat)) {
			total += flightExtraSeatFee
		}
	}
	return total, flightTaxPerPax * pax
}

// newFlightPNR skips 0/O/1/I so the code reads cleanly over the phone.
func newFlightPNR() string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	b := []byte("SL")
	for i := 0; i < 4; i++ {
		b = append(b, alphabet[flightRand.Intn(len(alphabet))])
	}
	return string(b)
}
