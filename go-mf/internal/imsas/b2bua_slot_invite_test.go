package imsas

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"

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
	if strings.Contains(app, "ice-ufrag") || strings.Contains(got, getTLSId("sa-adc")) {
		t.Fatalf("App line borrowed Home credentials: %q", app)
	}
}
