package imsas

import (
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/demo/ims-dc-mf/internal/config"
)

func failResp(code int, reason, callID string, cseq int) *SIPMessage {
	raw := "SIP/2.0 " + strconv.Itoa(code) + " " + reason + "\r\n" +
		"Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>;tag=b\r\n" +
		"Call-ID: " + callID + "\r\nCSeq: " + strconv.Itoa(cseq) + " INVITE\r\n" +
		"Content-Length: 0\r\n\r\n"
	msg, err := ParseSIPMessage(raw)
	if err != nil {
		panic(err)
	}
	return msg
}

func TestIsReInviteFailure(t *testing.T) {
	answered := &CallSession{CallID: "c1", TermAnswered: true, InitialCSeq: 1, LastCalleeCSeq: 7}
	ringing := &CallSession{CallID: "c1", InitialCSeq: 1}
	cases := []struct {
		name string
		s    *CallSession
		msg  *SIPMessage
		want bool
	}{
		{"491 caller re-INVITE", answered, failResp(491, "Request Pending", "b2b_c1", 2), true},
		{"488 caller re-INVITE", answered, failResp(488, "Not Acceptable Here", "b2b_c1", 3), true},
		{"callee re-INVITE failure from caller", answered, failResp(486, "Busy Here", "c1", 7), true},
		{"initial INVITE busy", ringing, failResp(486, "Busy Here", "b2b_c1", 1), false},
		{"late failure of initial CSeq", answered, failResp(486, "Busy Here", "b2b_c1", 1), false},
		{"no session", nil, failResp(491, "Request Pending", "b2b_c1", 2), false},
	}
	for _, c := range cases {
		if got := isReInviteFailure(c.s, c.msg); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
	bye := failResp(481, "Call Does Not Exist", "b2b_c1", 2)
	bye.Headers["cseq"] = "2 BYE"
	if isReInviteFailure(answered, bye) {
		t.Errorf("non-INVITE failure treated as re-INVITE failure")
	}
}

type failureRig struct {
	b       *B2BUA
	mu      sync.Mutex
	deletes []string
	scscf   *net.UDPConn
}

func (r *failureRig) deleted() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.deletes...)
}

func (r *failureRig) received(t *testing.T) string {
	t.Helper()
	buf := make([]byte, 4096)
	_ = r.scscf.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := r.scscf.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("response not forwarded to S-CSCF: %v", err)
	}
	return string(buf[:n])
}

func newFailureRig(t *testing.T) *failureRig {
	r := &failureRig{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Method == http.MethodDelete {
			r.mu.Lock()
			r.deletes = append(r.deletes, req.URL.Path)
			r.mu.Unlock()
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(srv.Close)
	u, _ := url.Parse(srv.URL)
	port, _ := strconv.Atoi(u.Port())

	scscf, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { scscf.Close() })
	own, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { own.Close() })

	cfg := &config.Config{}
	cfg.Services.Mf.Host, cfg.Services.Mf.Port = u.Hostname(), port
	cfg.Services.Dcsf.Host, cfg.Services.Dcsf.Port = u.Hostname(), port
	cfg.ImsCore.Scscf.Host = "127.0.0.1"
	cfg.ImsCore.Scscf.Port = scscf.LocalAddr().(*net.UDPAddr).Port
	r.b = &B2BUA{cfg: cfg, httpClient: srv.Client(), conn: own, scscfAddr: scscf.LocalAddr().(*net.UDPAddr),
		sessions: map[string]*CallSession{}}
	r.scscf = scscf
	return r
}

func (r *failureRig) addSession(s *CallSession) {
	s.MfHost = r.b.cfg.Services.Mf.Host
	r.b.sessions[s.CallID] = s
}

func TestErrorResponse_491ReInviteKeepsCall(t *testing.T) {
	r := newFailureRig(t)
	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", ContextID: "c1",
		TermAnswered: true, InitialCSeq: 1, LastCSeq: 2,
		AppSlot: slotState{AppID: "caro", CtxCreated: true}, AppMfA: &SlotMF{Port: 1}}
	r.addSession(s)

	msg := failResp(491, "Request Pending", "b2b_c1", 2)
	r.b.handleErrorResponse(msg, "c1", msg.Raw)

	if got := r.received(t); !strings.HasPrefix(got, "SIP/2.0 491") {
		t.Fatalf("forwarded %q", got)
	}
	if _, ok := r.b.sessions["c1"]; !ok {
		t.Fatalf("session removed by a re-INVITE failure")
	}
	if d := r.deleted(); len(d) != 0 {
		t.Fatalf("unexpected MF/DCSF deletes %v", d)
	}
	if !s.AppSlot.CtxCreated {
		t.Fatalf("App slot closed by a 491 to a non-App re-INVITE")
	}
}

func TestErrorResponse_488AppOpenClosesOnlyAppContext(t *testing.T) {
	r := newFailureRig(t)
	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", ContextID: "c1",
		TermAnswered: true, InitialCSeq: 1, LastCSeq: 3, IsAnchorCtxCreated: true, ActiveAppID: "fun-calling-app",
		AppSlot: slotState{AppID: "caro", CtxCreated: true, AdcType: "P2A2P"}, AppMfA: &SlotMF{Port: 1},
		AppAnswerPending: true}
	r.addSession(s)

	msg := failResp(488, "Not Acceptable Here", "b2b_c1", 3)
	r.b.handleErrorResponse(msg, "c1", msg.Raw)

	if got := r.received(t); !strings.HasPrefix(got, "SIP/2.0 488") {
		t.Fatalf("forwarded %q", got)
	}
	if _, ok := r.b.sessions["c1"]; !ok {
		t.Fatalf("session removed by a re-INVITE failure")
	}
	if d := r.deleted(); len(d) != 1 || d[0] != "/nmf-mrm/v1/contexts/c1-app" {
		t.Fatalf("deletes=%v want only the -app context", d)
	}
	if s.AppAnswerPending || s.AppSlot.CtxCreated || s.AppMfA != nil {
		t.Fatalf("App slot not rolled back: pending=%v slot=%+v", s.AppAnswerPending, s.AppSlot)
	}
	if !s.IsAnchorCtxCreated || s.ActiveAppID != "fun-calling-app" {
		t.Fatalf("Home slot touched")
	}
}

func TestErrorResponse_486InitialInviteEndsCall(t *testing.T) {
	r := newFailureRig(t)
	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", ContextID: "c1",
		InitialCSeq: 1, LastCSeq: 1}
	r.addSession(s)

	msg := failResp(486, "Busy Here", "b2b_c1", 1)
	r.b.handleErrorResponse(msg, "c1", msg.Raw)

	if got := r.received(t); !strings.HasPrefix(got, "SIP/2.0 486") {
		t.Fatalf("forwarded %q", got)
	}
	if _, ok := r.b.sessions["c1"]; ok {
		t.Fatalf("session kept after initial INVITE failure")
	}
	// The release runs after the 486 is relayed, in the background.
	found := false
	for deadline := time.Now().Add(2 * time.Second); !found && time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		for _, d := range r.deleted() {
			if d == "/nmf-mrm/v1/contexts/c1" {
				found = true
			}
		}
	}
	if !found {
		t.Fatalf("parent MF context not deleted: %v", r.deleted())
	}
}
