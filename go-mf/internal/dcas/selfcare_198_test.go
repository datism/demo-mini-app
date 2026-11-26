package dcas

import (
	"reflect"
	"testing"
)

var testSelfcareIDs = selfcareIDs{
	Txn:    func() string { return "GD-TEST01" },
	Ticket: func() string { return "PA-99999" },
	Today:  func() string { return "26/11" },
}

func scReply(st *selfcareStore, caller string, req SelfcareRequest) SelfcareResponse {
	req.Service = "smart-menu"
	return buildSelfcareReply(req, caller, st, testSelfcareIDs)
}

func hasRow(r *SelfcareReceipt, label, value string) bool {
	if r == nil {
		return false
	}
	for _, row := range r.Rows {
		if row.Label == label && row.Value == value {
			return true
		}
	}
	return false
}

func TestSelfcareProfileSeed(t *testing.T) {
	st := newSelfcareStore()
	r := scReply(st, "1001", SelfcareRequest{Action: "profile"})
	if r.Status != "ok" || r.Service != "smart-menu" || r.Action != "profile" {
		t.Fatalf("bad envelope: %+v", r)
	}
	want := SelfcareAccount{MSISDN: "1001", Balance: 148500, Expiry: "30/12/2026", Package: "MAX90", DataLeftMB: 3200, DataTotalMB: 5000, BillMonth: "11", BillTotal: 86400}
	if r.Account == nil || *r.Account != want {
		t.Fatalf("account = %+v", r.Account)
	}
	var codes []string
	for _, p := range r.Packages {
		codes = append(codes, p.Code)
	}
	if !reflect.DeepEqual(codes, []string{"SPEED120", "MAX90", "DAY10"}) {
		t.Fatalf("packages = %v", codes)
	}
	if !reflect.DeepEqual(r.Reports, []SelfcareReport{{"PA-20431", "Mất sóng", "18/11", "Đã xong"}}) {
		t.Fatalf("reports = %+v", r.Reports)
	}
}

func TestSelfcareSubscribeOK(t *testing.T) {
	st := newSelfcareStore()
	r := scReply(st, "1001", SelfcareRequest{Action: "subscribe", Code: "DAY10"})
	if r.Status != "ok" || r.Txn != "GD-TEST01" {
		t.Fatalf("reply = %+v", r)
	}
	a := r.Account
	if a.Balance != 138500 || a.Package != "DAY10" || a.DataLeftMB != 2000 || a.DataTotalMB != 2000 || a.BillTotal != 96400 {
		t.Fatalf("account = %+v", a)
	}
	if r.Receipt == nil || r.Receipt.Title != "Đăng ký thành công" {
		t.Fatalf("receipt = %+v", r.Receipt)
	}
	for _, row := range [][2]string{{"Gói", "DAY10 · 2 GB · 24 giờ"}, {"Đã trừ", "10.000đ"}, {"Mã giao dịch", "GD-TEST01"}} {
		if !hasRow(r.Receipt, row[0], row[1]) {
			t.Errorf("missing row %v in %+v", row, r.Receipt.Rows)
		}
	}
}

func TestSelfcareSubscribeInsufficient(t *testing.T) {
	st := newSelfcareStore()
	if r := scReply(st, "1001", SelfcareRequest{Action: "subscribe", Code: "SPEED120"}); r.Status != "ok" {
		t.Fatalf("first = %+v", r)
	}
	r := scReply(st, "1001", SelfcareRequest{Action: "subscribe", Code: "SPEED120"})
	if r.Status != "error" || r.Code != "insufficient_balance" || r.Message != "Số dư không đủ, vui lòng nạp thêm" {
		t.Fatalf("second = %+v", r)
	}
	p := scReply(st, "1001", SelfcareRequest{Action: "profile"})
	if p.Account.Balance != 28500 || p.Account.Package != "SPEED120" {
		t.Fatalf("after = %+v", p.Account)
	}
}

func TestSelfcareSubscribeExactBalance(t *testing.T) {
	st := newSelfcareStore()
	st.state("1001").acct.Balance = 10000
	if r := scReply(st, "1001", SelfcareRequest{Action: "subscribe", Code: "DAY10"}); r.Status != "ok" || r.Account.Balance != 0 {
		t.Fatalf("exact = %+v", r)
	}
	if r := scReply(st, "1001", SelfcareRequest{Action: "subscribe", Code: "DAY10"}); r.Code != "insufficient_balance" {
		t.Fatalf("empty = %+v", r)
	}
	scReply(st, "1001", SelfcareRequest{Action: "topup", Amount: 100000})
	if r := scReply(st, "1001", SelfcareRequest{Action: "subscribe", Code: "DAY10"}); r.Status != "ok" || r.Account.Package != "DAY10" {
		t.Fatalf("renew = %+v", r)
	}
}

func TestSelfcareSubscribeUnknown(t *testing.T) {
	st := newSelfcareStore()
	r := scReply(st, "1001", SelfcareRequest{Action: "subscribe", Code: "XYZ"})
	if r.Status != "error" || r.Code != "unknown_package" {
		t.Fatalf("reply = %+v", r)
	}
	if p := scReply(st, "1001", SelfcareRequest{Action: "profile"}); p.Account.Balance != 148500 {
		t.Fatalf("balance changed: %+v", p.Account)
	}
}

func TestSelfcareTopup(t *testing.T) {
	st := newSelfcareStore()
	r := scReply(st, "1001", SelfcareRequest{Action: "topup", Amount: 100000})
	if r.Status != "ok" || r.Account.Balance != 248500 || r.Account.BillTotal != 86400 {
		t.Fatalf("reply = %+v / %+v", r, r.Account)
	}
	if r.Receipt == nil || r.Receipt.Title != "Nạp tiền thành công" || !hasRow(r.Receipt, "Số tiền", "100.000đ") {
		t.Fatalf("receipt = %+v", r.Receipt)
	}
	if r := scReply(st, "1001", SelfcareRequest{Action: "topup", Amount: 70000}); r.Status != "error" || r.Code != "bad_amount" {
		t.Fatalf("bad = %+v", r)
	}
}

func TestSelfcareReportValidation(t *testing.T) {
	st := newSelfcareStore()
	bad := []SelfcareRequest{
		{Action: "report", Location: "Hà Nội"},
		{Action: "report", Issue: "Khác", Location: "Hà Nội"},
		{Action: "report", Issue: "Mất sóng", Location: "   "},
	}
	for _, req := range bad {
		if r := scReply(st, "1001", req); r.Status != "error" || r.Code != "missing_field" {
			t.Errorf("%+v → %+v", req, r)
		}
	}
	r := scReply(st, "1001", SelfcareRequest{Action: "report", Issue: "Data chậm", Location: "12 Trần Thái Tông"})
	if r.Status != "ok" || r.Ticket != "PA-99999" || len(r.Reports) != 2 {
		t.Fatalf("ok = %+v", r)
	}
	if r.Reports[0] != (SelfcareReport{"PA-99999", "Data chậm", "26/11", "Đang xử lý"}) {
		t.Fatalf("newest = %+v", r.Reports[0])
	}
}

func TestSelfcareAccountsArePerCaller(t *testing.T) {
	st := newSelfcareStore()
	scReply(st, "1001", SelfcareRequest{Action: "subscribe", Code: "DAY10"})
	if p := scReply(st, "1002", SelfcareRequest{Action: "profile"}); p.Account.Balance != 148500 || p.Account.MSISDN != "1002" {
		t.Fatalf("1002 = %+v", p.Account)
	}
	if p := scReply(st, "1001", SelfcareRequest{Action: "profile"}); p.Account.Balance != 138500 {
		t.Fatalf("1001 = %+v", p.Account)
	}
}

func TestSelfcareUnknownAction(t *testing.T) {
	r := scReply(newSelfcareStore(), "1001", SelfcareRequest{Action: "ask_time"})
	if r.Status != "error" || r.Code != "unknown_action" {
		t.Fatalf("reply = %+v", r)
	}
}

func TestFormatVND(t *testing.T) {
	for n, want := range map[int]string{148500: "148.500đ", 0: "0đ", 1200000: "1.200.000đ"} {
		if got := formatVND(n); got != want {
			t.Errorf("formatVND(%d) = %q want %q", n, got, want)
		}
	}
}

func TestSmartMenuRoutingLeavesOtherServices(t *testing.T) {
	cases := map[string]string{
		`{"service":"smart-menu","action":"profile"}`:    "selfcare",
		`{"service":"caro","action":"move"}`:             "caro",
		`{"service":"flight-booking","action":"search"}`: "flight-booking",
		`{"service":"enterprise-callerid"}`:              "enterprise-callerid",
		`{"type":"draw","x":1}`:                          "relay",
		`{"type":"clear"}`:                               "relay",
		`{"action":"heart"}`:                             "drop",
		`{"service":"whatever","action":"x"}`:            "drop",
		`not json`:                                       "drop",
	}
	for in, want := range cases {
		if got := smartMenuRoute([]byte(in)); got != want {
			t.Errorf("%s: got %q want %q", in, got, want)
		}
	}
}
