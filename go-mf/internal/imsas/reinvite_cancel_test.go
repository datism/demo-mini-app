package imsas

import (
	"net"
	"strconv"
	"strings"
	"testing"
)

func cancelReq(callID string, cseq int) *SIPMessage {
	raw := "CANCEL sip:bob@ims SIP/2.0\r\n" +
		"Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>;tag=b\r\n" +
		"Call-ID: " + callID + "\r\nCSeq: " + strconv.Itoa(cseq) + " CANCEL\r\n" +
		"Content-Length: 0\r\n\r\n"
	msg, err := ParseSIPMessage(raw)
	if err != nil {
		panic(err)
	}
	return msg
}

func TestIsReInviteCancel(t *testing.T) {
	answered := &CallSession{CallID: "c1", TermAnswered: true, InitialCSeq: 1, LastCalleeCSeq: 7}
	ringing := &CallSession{CallID: "c1", InitialCSeq: 1}
	cases := []struct {
		name string
		s    *CallSession
		msg  *SIPMessage
		want bool
	}{
		{"caller re-INVITE", answered, cancelReq("c1", 2), true},
		{"callee re-INVITE", answered, cancelReq("c1", 7), true},
		{"initial INVITE while ringing", ringing, cancelReq("c1", 1), false},
		{"initial CSeq after answer", answered, cancelReq("c1", 1), false},
		{"no session", nil, cancelReq("c1", 2), false},
		// The callee's CSeq counter is its own: a low CSeq on its leg is still a re-INVITE CANCEL.
		{"callee leg, low CSeq", answered, cancelReq("b2b_c1", 1), true},
		{"callee leg while ringing", ringing, cancelReq("b2b_c1", 1), false},
	}
	for _, c := range cases {
		if got := isReInviteCancel(c.s, c.msg); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}

// A CANCEL of a pending re-INVITE (a content-share request withdrawn by its
// sender) is forwarded and acknowledged, and the call survives.
func TestCancelOfReInviteKeepsCall(t *testing.T) {
	r := newFailureRig(t)
	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", ContextID: "c1",
		TermAnswered: true, InitialCSeq: 1, LastCSeq: 2, IsAnchorCtxCreated: true, ActiveAppID: "photo-share"}
	r.addSession(s)

	from, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer from.Close()

	r.b.handleCancel(cancelReq("c1", 2), from.LocalAddr().(*net.UDPAddr))

	if got := r.received(t); !strings.HasPrefix(got, "CANCEL ") || !strings.Contains(got, "Call-ID: b2b_c1") {
		t.Fatalf("CANCEL not forwarded to the terminating leg: %q", got)
	}
	if _, ok := r.b.sessions["c1"]; !ok {
		t.Fatalf("session removed by a CANCEL of a re-INVITE")
	}
	if d := r.deleted(); len(d) != 0 {
		t.Fatalf("MF/DCSF released by a CANCEL of a re-INVITE: %v", d)
	}
}

// The initial-INVITE CANCEL still ends the session.
func TestCancelOfInitialInviteEndsCall(t *testing.T) {
	r := newFailureRig(t)
	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", ContextID: "c1", InitialCSeq: 1, LastCSeq: 1}
	r.addSession(s)
	from, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	defer from.Close()

	r.b.handleCancel(cancelReq("c1", 1), from.LocalAddr().(*net.UDPAddr))

	if got := r.received(t); !strings.HasPrefix(got, "CANCEL ") {
		t.Fatalf("CANCEL not forwarded: %q", got)
	}
	if _, ok := r.b.sessions["c1"]; ok {
		t.Fatalf("session kept after CANCEL of the initial INVITE")
	}
}
