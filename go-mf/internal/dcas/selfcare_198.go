package dcas

import (
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Hotline 198 self-service back office. The caller's number is the account
// (the IMS-AS already knows it). Everything lives in memory and is cleared
// when DCAS restarts.

type SelfcareRequest struct {
	Service  string `json:"service"`
	Action   string `json:"action"`
	Code     string `json:"code,omitempty"`
	Amount   int    `json:"amount,omitempty"`
	Issue    string `json:"issue,omitempty"`
	Location string `json:"location,omitempty"`
	Note     string `json:"note,omitempty"`
	ReqID    string `json:"req_id,omitempty"` // page's id for this tap; a retry reuses it
}

type SelfcareAccount struct {
	MSISDN      string `json:"msisdn"`
	Balance     int    `json:"balance"`
	Expiry      string `json:"expiry"`
	Package     string `json:"package"`
	DataLeftMB  int    `json:"data_left_mb"`
	DataTotalMB int    `json:"data_total_mb"`
	BillMonth   string `json:"bill_month"`
	BillTotal   int    `json:"bill_total"`
}

type SelfcarePackage struct {
	Code  string `json:"code"`
	Name  string `json:"name"`
	Desc  string `json:"desc"`
	Price int    `json:"price"`
	alloc int    // MB granted on subscribe
}

type SelfcareReport struct {
	Ticket string `json:"ticket"`
	Issue  string `json:"issue"`
	Sent   string `json:"sent"`
	Status string `json:"status"`
}

type SelfcareRow struct {
	Label string `json:"label"`
	Value string `json:"value"`
}

type SelfcareReceipt struct {
	Title string        `json:"title"`
	Rows  []SelfcareRow `json:"rows"`
}

type SelfcareResponse struct {
	Service  string            `json:"service"`
	Action   string            `json:"action"`
	Status   string            `json:"status"` // "ok" or "error"
	ReqID    string            `json:"req_id,omitempty"`
	Code     string            `json:"code,omitempty"`
	Message  string            `json:"message,omitempty"`
	Txn      string            `json:"txn,omitempty"`
	Ticket   string            `json:"ticket,omitempty"`
	Account  *SelfcareAccount  `json:"account,omitempty"`
	Packages []SelfcarePackage `json:"packages,omitempty"`
	Reports  []SelfcareReport  `json:"reports,omitempty"`
	Receipt  *SelfcareReceipt  `json:"receipt,omitempty"`
}

var selfcarePackages = []SelfcarePackage{
	{Code: "SPEED120", Name: "SPEED120", Desc: "4 GB/ngày · 30 ngày", Price: 120000, alloc: 4000},
	{Code: "MAX90", Name: "MAX90", Desc: "5 GB/tháng · 30 ngày", Price: 90000, alloc: 5000},
	{Code: "DAY10", Name: "DAY10", Desc: "2 GB · 24 giờ", Price: 10000, alloc: 2000},
}

var selfcareTopups = map[int]bool{50000: true, 100000: true, 200000: true}

var selfcareIssues = map[string]bool{"Data chậm": true, "Mất sóng": true, "Rớt cuộc gọi": true, "Không gửi được SMS": true}

type selfcareState struct {
	acct    SelfcareAccount
	reports []SelfcareReport // newest first
	// last request and its reply, so a retry of a slow request (same req_id)
	// is answered again instead of charging twice.
	lastReqID string
	lastResp  SelfcareResponse
}

type selfcareStore struct {
	mu       sync.Mutex
	accounts map[string]*selfcareState
}

func newSelfcareStore() *selfcareStore {
	return &selfcareStore{accounts: make(map[string]*selfcareState)}
}

var selfcareAccounts = newSelfcareStore()

// state returns the caller's account, seeding it on first use. Caller holds mu
// (tests call it single-threaded).
func (st *selfcareStore) state(caller string) *selfcareState {
	s := st.accounts[caller]
	if s == nil {
		s = &selfcareState{
			acct: SelfcareAccount{MSISDN: caller, Balance: 148500, Expiry: "30/12/2026", Package: "MAX90",
				DataLeftMB: 3200, DataTotalMB: 5000, BillMonth: "11", BillTotal: 86400},
			reports: []SelfcareReport{{Ticket: "PA-20431", Issue: "Mất sóng", Sent: "18/11", Status: "Đã xong"}},
		}
		st.accounts[caller] = s
	}
	return s
}

type selfcareIDs struct {
	Txn    func() string
	Ticket func() string
	Today  func() string
}

var defaultSelfcareIDs = selfcareIDs{
	Txn: func() string {
		const abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
		b := make([]byte, 6)
		for i := range b {
			b[i] = abc[rand.Intn(len(abc))]
		}
		return "GD-" + string(b)
	},
	Ticket: func() string { return fmt.Sprintf("PA-%05d", 20432+rand.Intn(79000)) },
	Today:  func() string { return time.Now().Format("02/01") },
}

// formatVND renders 148500 as "148.500đ".
func formatVND(n int) string {
	s := strconv.Itoa(n)
	var b strings.Builder
	for i, c := range s {
		if i > 0 && (len(s)-i)%3 == 0 {
			b.WriteByte('.')
		}
		b.WriteRune(c)
	}
	return b.String() + "đ"
}

func buildSelfcareReply(req SelfcareRequest, caller string, st *selfcareStore, ids selfcareIDs) SelfcareResponse {
	if caller == "" {
		caller = flightGuest
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	s := st.state(caller)
	if req.ReqID != "" && req.ReqID == s.lastReqID {
		return s.lastResp
	}
	resp := SelfcareResponse{Service: "smart-menu", Action: req.Action, Status: "ok", ReqID: req.ReqID}
	fail := func(code, msg string) {
		resp.Status, resp.Code, resp.Message = "error", code, msg
	}

	switch req.Action {
	case "profile":
		resp.Packages = selfcarePackages
	case "subscribe":
		var pkg *SelfcarePackage
		for i := range selfcarePackages {
			if selfcarePackages[i].Code == req.Code {
				pkg = &selfcarePackages[i]
			}
		}
		switch {
		case pkg == nil:
			fail("unknown_package", "Gói cước không tồn tại")
		case pkg.Price > s.acct.Balance:
			fail("insufficient_balance", "Số dư không đủ, vui lòng nạp thêm")
		default:
			s.acct.Balance -= pkg.Price
			s.acct.BillTotal += pkg.Price
			s.acct.Package = pkg.Code
			s.acct.DataLeftMB, s.acct.DataTotalMB = pkg.alloc, pkg.alloc
			resp.Txn = ids.Txn()
			resp.Receipt = &SelfcareReceipt{Title: "Đăng ký thành công", Rows: []SelfcareRow{
				{"Gói", pkg.Code + " · " + pkg.Desc},
				{"Đã trừ", formatVND(pkg.Price)},
				{"Số dư còn lại", formatVND(s.acct.Balance)},
				{"Mã giao dịch", resp.Txn},
			}}
		}
	case "topup":
		if !selfcareTopups[req.Amount] {
			fail("bad_amount", "Số tiền nạp không hợp lệ")
			break
		}
		s.acct.Balance += req.Amount
		resp.Txn = ids.Txn()
		resp.Receipt = &SelfcareReceipt{Title: "Nạp tiền thành công", Rows: []SelfcareRow{
			{"Số tiền", formatVND(req.Amount)},
			{"Số dư mới", formatVND(s.acct.Balance)},
			{"Mã giao dịch", resp.Txn},
		}}
	case "report":
		if !selfcareIssues[req.Issue] || strings.TrimSpace(req.Location) == "" {
			fail("missing_field", "Vui lòng chọn vấn đề và nhập địa điểm")
			break
		}
		resp.Ticket = ids.Ticket()
		r := SelfcareReport{Ticket: resp.Ticket, Issue: req.Issue, Sent: ids.Today(), Status: "Đang xử lý"}
		s.reports = append([]SelfcareReport{r}, s.reports...)
	default:
		fail("unknown_action", "Thao tác không được hỗ trợ")
	}

	acct := s.acct
	resp.Account = &acct
	resp.Reports = append([]SelfcareReport(nil), s.reports...)
	if req.ReqID != "" {
		s.lastReqID, s.lastResp = req.ReqID, resp
	}
	return resp
}

// handleSelfcareMessage answers one 198 page request. conn is the stream-1000
// WebSocket when the message came that way; nil means reply via sendToSession.
func (s *DCASServer) handleSelfcareMessage(sessionID string, data []byte, conn *websocket.Conn) {
	var req SelfcareRequest
	if err := json.Unmarshal(data, &req); err != nil {
		log.Printf("[DCAS] 198: bad JSON from session %s: %v", sessionID, err)
		return
	}
	caller := s.flightCaller(sessionID)
	resp := buildSelfcareReply(req, caller, selfcareAccounts, defaultSelfcareIDs)
	log.Printf("[DCAS] 198: caller %s (session %s) %s → %s %s%s%s", caller, sessionID, req.Action, resp.Status, resp.Code, resp.Txn, resp.Ticket)
	if conn != nil {
		b, _ := json.Marshal(resp)
		if err := conn.WriteMessage(websocket.TextMessage, b); err != nil {
			log.Printf("[DCAS] 198: write failed for session %s: %v", sessionID, err)
		}
		return
	}
	s.sendToSession(sessionID, "1000", resp)
}
