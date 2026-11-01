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
	"github.com/demo/ims-dc-mf/internal/sba"
)

func bind(id string) *sba.AppBindingInfo {
	return &sba.AppBindingInfo{ReqAppId: id, AppDcInfo: "1000-UE"}
}

func TestDecideAppAction(t *testing.T) {
	cases := []struct {
		name string
		app  slotState
		line *adcLine
		want appAction
	}{
		{"no line", slotState{}, nil, appNone},
		{"port0 and nothing open", slotState{}, &adcLine{Slot: SlotApp, Port: 0}, appNone},
		{"port0 closes open app", slotState{AppID: "caro", CtxCreated: true}, &adcLine{Slot: SlotApp, Port: 0}, appClose},
		{"first open", slotState{}, &adcLine{Slot: SlotApp, Port: 9, Binding: bind("caro")}, appOpen},
		{"same app unchanged", slotState{AppID: "caro", CtxCreated: true}, &adcLine{Slot: SlotApp, Port: 9, Binding: bind("caro")}, appNone},
		{"switch app", slotState{AppID: "caro", CtxCreated: true}, &adcLine{Slot: SlotApp, Port: 9, Binding: bind("photo-share")}, appOpen},
		{"active without req-app", slotState{}, &adcLine{Slot: SlotApp, Port: 9}, appNone},
		{"reopen after close", slotState{AppID: "caro", CtxCreated: false}, &adcLine{Slot: SlotApp, Port: 9, Binding: bind("caro")}, appOpen},
	}
	for _, c := range cases {
		if got := decideAppAction(c.app, c.line); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}

func TestAnswerSlots_PassiveCopyDoesNotMutateSession(t *testing.T) {
	s := &CallSession{MfInfoA: &MFAllocResponse{AdcPort: 40001}, AppMfA: &SlotMF{Port: 40002, Setup: "actpass"}}
	view := answerSlots(s.slotsFor(true))
	if view.Slots[SlotHome].Setup != "passive" || view.Slots[SlotApp].Setup != "passive" {
		t.Fatalf("want passive slots, got home=%+v app=%+v", view.Slots[SlotHome], view.Slots[SlotApp])
	}
	if s.AppMfA.Setup != "actpass" {
		t.Fatalf("session AppMfA mutated: %+v", s.AppMfA)
	}
	if answerSlots(nil) != nil {
		t.Fatal("nil in, nil out")
	}
}

func TestSetAppLinePort(t *testing.T) {
	sdp := "v=0\r\nm=audio 5000 RTP/AVP 8\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nm=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:4\r\n"
	got := setAppLinePort(sdp, 9)
	want := strings.Replace(sdp, "m=application 0 ", "m=application 9 ", 1)
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
	two := "m=application 9 x\r\nm=application 0 x\r\n"
	if setAppLinePort(two, 9) != two {
		t.Fatal("without an App line the SDP must be unchanged")
	}
}

func TestCloseAppSlot(t *testing.T) {
	var deleted []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete {
			deleted = append(deleted, r.URL.Path)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	u, _ := url.Parse(srv.URL)
	port, _ := strconv.Atoi(u.Port())
	cfg := &config.Config{}
	cfg.Services.Mf.Port = port
	b := &B2BUA{cfg: cfg, httpClient: srv.Client()}

	s := &CallSession{CallID: "c1", MfHost: u.Hostname()}
	b.closeAppSlot(s) // never created: no-op
	if len(deleted) != 0 {
		t.Fatalf("unexpected deletes %v", deleted)
	}

	s.AppSlot = slotState{AppID: "caro", CtxCreated: true}
	s.AppMfA, s.AppMfB = &SlotMF{Port: 1}, &SlotMF{Port: 2}
	b.closeAppSlot(s)
	if len(deleted) != 1 || deleted[0] != "/nmf-mrm/v1/contexts/c1-app" {
		t.Fatalf("deletes=%v", deleted)
	}
	if s.AppSlot != (slotState{}) || s.AppMfA != nil || s.AppMfB != nil {
		t.Fatalf("state not reset: %+v %v %v", s.AppSlot, s.AppMfA, s.AppMfB)
	}
}

const slottedOffer = "v=0\r\no=- 1 1 IN IP4 10.0.0.5\r\ns=-\r\nt=0 0\r\n" +
	"m=audio 5000 RTP/AVP 8\r\nc=IN IP4 10.0.0.5\r\n" +
	"m=application 5001 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 10.0.0.5\r\na=mid:2\r\n" +
	"m=application 5002 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 10.0.0.5\r\na=mid:3\r\n" +
	"m=application 5003 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 10.0.0.5\r\na=mid:4\r\n" +
	"a=3gpp-req-app:\"caro\";1000-UE\r\n"

func homeSection(sdp string) string {
	parts := strings.Split(sdp, "m=application ")
	return parts[2] // [0] session+audio, [1] BDC, [2] Home, [3] App
}

func TestRejectedAppOffer(t *testing.T) {
	s := &CallSession{MfInfoB: &MFAllocResponse{SessionID: "sb", IP: "1.1.1.1", Port: 40000, AdcPort: 40001, AdcIceUfrag: "hu"}}
	target := s.slotsFor(false)
	got := rejectedAppOffer(slottedOffer, target)
	if !strings.Contains(got, "m=application 0 UDP/DTLS/SCTP webrtc-datachannel") {
		t.Fatalf("App line not port 0:\n%s", got)
	}
	if strings.Contains(got, "10.0.0.5") || strings.Contains(got, "caro") {
		t.Fatalf("initiator address or App binding leaked:\n%s", got)
	}
	if want := homeSection(RewriteSDP(slottedOffer, s.slotsFor(false), "")); homeSection(got) != want {
		t.Fatalf("Home line changed:\ngot  %q\nwant %q", homeSection(got), want)
	}
	if out := rejectedAppOffer(slottedOffer, nil); !strings.Contains(out, "m=application 0 ") {
		t.Fatalf("nil target must still close the App line:\n%s", out)
	}
}

func TestRewriteSDP_SlotsWithoutAppMFClosesAppLine(t *testing.T) {
	s := &CallSession{MfInfoA: &MFAllocResponse{SessionID: "sa", IP: "1.1.1.1", Port: 40000, AdcPort: 40001, AdcIceUfrag: "hu"}}
	got := RewriteSDP(slottedOffer, s.slotsFor(true), "caro")
	app := strings.Split(got, "m=application ")[3]
	if !strings.HasPrefix(app, "0 UDP/DTLS/SCTP webrtc-datachannel") {
		t.Fatalf("App line must be port 0, got %q", app)
	}
	if strings.Contains(app, "ice-ufrag") || strings.Contains(app, "tls-id") {
		t.Fatalf("App line borrowed Home credentials: %q", app)
	}
}

func TestRewriteSDP_AppTLSIdFollowsContext(t *testing.T) {
	base := &MFAllocResponse{SessionID: "sa", IP: "1.1.1.1", Port: 40000, AdcPort: 40001}
	s := &CallSession{}
	appTLS := func(ufrag string) string {
		out := RewriteSDP(slottedOffer, s.withSlots(base, &SlotMF{Port: 40002, IceUfrag: ufrag}), "caro")
		app := strings.Split(out, "m=application ")[3]
		i := strings.Index(app, "a=tls-id:")
		return strings.SplitN(app[i:], "\r\n", 2)[0]
	}
	if appTLS("caro1") != appTLS("caro1") {
		t.Fatal("same App context must keep its tls-id")
	}
	if appTLS("caro1") == appTLS("photo1") {
		t.Fatal("a new App context must get a new tls-id")
	}
}

func TestUseSlottedPath(t *testing.T) {
	two := parseAdcLines(slottedOffer)
	one := two[:1]
	cases := []struct {
		name  string
		s     *CallSession
		lines []adcLine
		want  bool
	}{
		{"home open, two lines", &CallSession{IsAnchorCtxCreated: true, Callee: "2099"}, two, true},
		{"app before home", &CallSession{Callee: "2099"}, two, false},
		{"one line", &CallSession{IsAnchorCtxCreated: true, Callee: "2099"}, one, false},
		{"hotline", &CallSession{IsAnchorCtxCreated: true, Callee: "198"}, two, false},
	}
	for _, c := range cases {
		if got := useSlottedPath(c.s, c.lines); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}

type slotRig struct {
	b     *B2BUA
	mu    sync.Mutex
	calls []string
	scscf *net.UDPConn
}

func (r *slotRig) seen() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.calls...)
}

func (r *slotRig) received(t *testing.T) string {
	t.Helper()
	buf := make([]byte, 8192)
	_ = r.scscf.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := r.scscf.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("nothing forwarded to S-CSCF: %v", err)
	}
	return string(buf[:n])
}

func newSlotRig(t *testing.T) *slotRig {
	r := &slotRig{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if strings.Contains(req.URL.Path, "/nmf-mrm/") {
			r.mu.Lock()
			r.calls = append(r.calls, req.Method+" "+req.URL.Path)
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

func sipMsg(t *testing.T, startLine, callID, cseq, body string) *SIPMessage {
	t.Helper()
	raw := startLine + "\r\n" +
		"Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>;tag=b\r\n" +
		"Call-ID: " + callID + "\r\nCSeq: " + cseq + "\r\n" +
		"Content-Type: application/sdp\r\nContent-Length: " + strconv.Itoa(len(body)) + "\r\n\r\n" + body
	m, err := ParseSIPMessage(raw)
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func slotSession(r *slotRig) *CallSession {
	home := func(id string) *MFAllocResponse {
		return &MFAllocResponse{SessionID: id, IP: "1.1.1.1", Port: 40000, AdcPort: 40001, AdcIceUfrag: "hu"}
	}
	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", ContextID: "c1",
		Caller: "alice", Callee: "bob", MfHost: r.b.cfg.Services.Mf.Host,
		IsAnchorCtxCreated: true, ActiveAppID: "fun-calling-app", TermAnswered: true, InitialCSeq: 1,
		MfInfoA: home("c1-A"), MfInfoB: home("c1-B"),
		AppSlot: slotState{AppID: "caro", CtxCreated: true, AdcType: "P2A2P"},
		AppMfA:  &SlotMF{Port: 40002, IceUfrag: "ua"}, AppMfB: &SlotMF{Port: 40003, IceUfrag: "ub"},
		InstructionChan: make(chan *sba.MediaInstructionData), // unbuffered: survives the stale drain
	}
	r.b.sessions[s.CallID] = s
	return s
}

func TestSlottedReInvite_P2PAppRejectsAppLine(t *testing.T) {
	r := newSlotRig(t)
	s := slotSession(r)
	body := strings.Replace(slottedOffer, "\"caro\"", "\"unknown-app\"", 1)
	msg := sipMsg(t, "INVITE sip:bob@ims SIP/2.0", "c1", "2 INVITE", body)
	go func() {
		s.InstructionChan <- &sba.MediaInstructionData{MediaInstructionSet: []sba.MediaInstructions{{
			MediaResourceType: "DC", DcMediaSpecification: &sba.DcMediaSpecification{AdcType: "P2P"}}}}
	}()
	r.b.handleSlottedReInvite(s, msg, nil, parseAdcLines(body))

	got := r.received(t)
	if !strings.Contains(got, "m=application 0 UDP/DTLS/SCTP") || strings.Contains(got, "10.0.0.5") {
		t.Fatalf("want App line port 0 and the offer rewritten to the MF:\n%s", got)
	}
	if c := r.seen(); len(c) != 1 || c[0] != "DELETE /nmf-mrm/v1/contexts/c1-app" {
		t.Fatalf("MF calls=%v want only the -app delete", c)
	}
	if s.AppSlot.CtxCreated || s.AppMfA != nil {
		t.Fatalf("App slot not closed: %+v", s.AppSlot)
	}
}

func TestSlottedAnswer_PeerRefusesAppLine(t *testing.T) {
	r := newSlotRig(t)
	s := slotSession(r)
	s.AppAnswerPending, s.SlottedAnswerPending = true, true
	s.OrigMsg = sipMsg(t, "INVITE sip:bob@ims SIP/2.0", "c1", "2 INVITE", slottedOffer)
	answer := strings.Replace(slottedOffer, "m=application 5003 ", "m=application 0 ", 1)
	msg := sipMsg(t, "SIP/2.0 200 OK", "b2b_c1", "2 INVITE", answer)

	r.b.handleSlottedAnswer(s, msg, "http://127.0.0.1:1", true)

	got := r.received(t)
	if !strings.Contains(got, "m=application 0 UDP/DTLS/SCTP") || strings.Contains(got, "caro") {
		t.Fatalf("initiator must get App port 0:\n%s", got)
	}
	if c := r.seen(); len(c) != 1 || c[0] != "DELETE /nmf-mrm/v1/contexts/c1-app" {
		t.Fatalf("MF calls=%v want only the -app delete (no PATCH)", c)
	}
	if s.AppSlot.CtxCreated || s.AppAnswerPending || s.SlottedAnswerPending {
		t.Fatalf("state not reset: slot=%+v pending=%v slotted=%v", s.AppSlot, s.AppAnswerPending, s.SlottedAnswerPending)
	}
}
