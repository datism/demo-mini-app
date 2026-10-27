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
}

var flightSchedule = []FlightOption{
	{No: "SL 201", Dep: "06:00", Arr: "08:10", Dur: "2g 10p", Price: 1479000},
	{No: "SL 213", Dep: "08:30", Arr: "10:40", Dur: "2g 10p", Price: 1770000},
	{No: "SL 225", Dep: "11:15", Arr: "13:20", Dur: "2g 05p", Price: 1670000},
	{No: "SL 241", Dep: "19:45", Arr: "21:55", Dur: "2g 10p", Price: 1570000},
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

	resp := buildFlightBookingReply(req, newFlightPNR, flightSeats)
	if resp.Status == "confirmed" {
		log.Printf("[DCAS] Session %s booked %s (%s, seats %s) PNR=%s total=%d", sessionID, req.Flight, req.Fare, strings.Join(requestedSeats(req), ","), resp.PNR, resp.Total)
	}
	s.sendToSession(sessionID, "1000", resp)
}

// buildFlightBookingReply takes the PNR generator and seat store as arguments
// so tests can pin the code and use a fresh plane.
func buildFlightBookingReply(req FlightBookingRequest, pnr func() string, seats *flightSeatStore) FlightBookingResponse {
	resp := FlightBookingResponse{Service: "flight-booking", Action: req.Action, Status: "success"}

	switch req.Action {
	case "search":
		resp.Flights = make([]FlightOption, len(flightSchedule))
		copy(resp.Flights, flightSchedule)
		for i := range resp.Flights {
			resp.Flights[i].Pick = resp.Flights[i].No == flightAgentPick
		}
		resp.AgentPick = flightAgentPick
		resp.Message = "Tổng đài viên gợi ý chuyến " + flightAgentPick
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
	for i := range flightSchedule {
		if flightSchedule[i].No == no {
			return &flightSchedule[i]
		}
	}
	return nil
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
	perPax := f.Price
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
