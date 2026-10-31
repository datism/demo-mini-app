package imsas

import (
	"strings"
	"testing"
)

type fakeMF struct{ created, deleted []string }

func (f *fakeMF) apply(s *CallSession, act appAction, appID string) {
	switch act {
	case appClose:
		f.deleted = append(f.deleted, appContextID(s.CallID))
		s.AppSlot = slotState{}
		s.AppMfA, s.AppMfB = nil, nil
	case appOpen:
		if s.AppSlot.CtxCreated {
			f.deleted = append(f.deleted, appContextID(s.CallID))
		}
		f.created = append(f.created, appContextID(s.CallID))
		s.AppSlot = slotState{AppID: appID, CtxCreated: true}
		s.AppMfA = &SlotMF{Port: 40100 + len(f.created), IceUfrag: "u" + appID, IcePwd: "pw", Fingerprint: "sha-256 AA", Setup: "actpass"}
	}
}

func offer(homeOpen bool, appPort, appID string) string {
	s := "v=0\r\nm=audio 4000 RTP/AVP 0\r\nm=video 4002 RTP/AVP 96\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:2\r\na=dcmap:10 subprotocol=\"http\"\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:3\r\na=dcmap:1000 subprotocol=\"fun-calling-app\"\r\na=3gpp-req-app:\"fun-calling-app\";1000-UE\r\n" +
		"m=application " + appPort + " UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:4\r\n"
	if appID != "" {
		s += "a=dcmap:1000 subprotocol=\"" + appID + "\"\r\na=3gpp-req-app:\"" + appID + "\";1000-UE\r\n"
	}
	return s
}

func TestScenario_OneReInvitePerStep_HomeNeverDeleted(t *testing.T) {
	s := &CallSession{CallID: "call1"}
	mf := &fakeMF{}
	// Port/IceUfrag/... are the BDC transport; without a non-zero Port the BDC
	// m-line would render as "m=application 0" and confuse the App-line check.
	home := &MFAllocResponse{SessionID: "call1", Port: 40000, IceUfrag: "bu", IcePwd: "bp", DtlsFingerprint: "sha-256 BB", AdcPort: 40001, AdcIceUfrag: "hu", AdcIcePwd: "hp", AdcDtlsFingerprint: "sha-256 HH"}

	steps := []struct {
		name, appPort, appID string
		wantAct              appAction
		wantCreated, wantDel int
	}{
		{"open caro", "9", "caro", appOpen, 1, 0},
		{"back to emotes", "0", "", appClose, 1, 1},
		{"open photo-share", "9", "photo-share", appOpen, 2, 1},
		{"caro directly", "9", "caro", appOpen, 3, 2}, // replace in place: delete + create
		{"resend same offer", "9", "caro", appNone, 3, 2},
		{"back to emotes again", "0", "", appClose, 3, 3},
	}
	var prevHomeLine string
	for _, st := range steps {
		sdp := offer(true, st.appPort, st.appID)
		lines := parseAdcLines(sdp)
		if len(lines) != 2 {
			t.Fatalf("%s: want 2 ADC lines, got %d", st.name, len(lines))
		}
		act := decideAppAction(s.AppSlot, adcLineFor(lines, SlotApp))
		if act != st.wantAct {
			t.Fatalf("%s: action %v want %v", st.name, act, st.wantAct)
		}
		mf.apply(s, act, st.appID)
		if len(mf.created) != st.wantCreated || len(mf.deleted) != st.wantDel {
			t.Fatalf("%s: created=%d deleted=%d want %d/%d", st.name, len(mf.created), len(mf.deleted), st.wantCreated, st.wantDel)
		}
		out := RewriteSDP(sdp, s.withSlots(home, s.AppMfA), s.AppSlot.AppID)

		// Home context is never the one being deleted.
		for _, d := range mf.deleted {
			if strings.HasSuffix(d, "-home") || d == "call1" {
				t.Fatalf("%s: Home context deleted: %s", st.name, d)
			}
		}
		// Home line is byte-identical across every step.
		i := strings.Index(out, "a=mid:3")
		j := strings.Index(out[i:], "m=application")
		homeLine := out[i : i+j]
		if prevHomeLine != "" && homeLine != prevHomeLine {
			t.Fatalf("%s: Home section changed:\n--- before\n%s\n--- after\n%s", st.name, prevHomeLine, homeLine)
		}
		prevHomeLine = homeLine
		// BDC stays untouched and the App section is closed exactly when no app is open.
		if !strings.Contains(out, "a=dcmap:10 subprotocol=\"http\"") {
			t.Fatalf("%s: BDC lost", st.name)
		}
		if (s.AppSlot.CtxCreated) == strings.Contains(out, "m=application 0 ") {
			t.Fatalf("%s: App line state does not match slot state:\n%s", st.name, out)
		}
	}
}
