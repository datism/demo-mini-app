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
