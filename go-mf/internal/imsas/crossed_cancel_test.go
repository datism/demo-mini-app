package imsas

import (
	"net"
	"strings"
	"testing"
	"time"
)

// A cancels just as B answers: B's 200 OK and A's CANCEL cross. B ignores a CANCEL once it has
// answered, so unless the IMS-AS ends B's leg, B stays in a call nobody is on and turns the next
// call away as busy.

func crossedRig(t *testing.T) (*failureRig, *CallSession, *net.UDPAddr) {
	r := newFailureRig(t)
	invite, err := ParseSIPMessage("INVITE sip:bob@ims SIP/2.0\r\n" +
		"Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>\r\n" +
		"Call-ID: c1\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n")
	if err != nil {
		t.Fatal(err)
	}
	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", Caller: "alice", Callee: "bob",
		OrigMsg: invite, InitialCSeq: 1, LastCSeq: 1}
	r.addSession(s)
	from, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	t.Cleanup(func() { from.Close() })
	return r, s, from.LocalAddr().(*net.UDPAddr)
}

func calleeOK(t *testing.T) *SIPMessage {
	msg, err := ParseSIPMessage("SIP/2.0 200 OK\r\n" +
		"Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>;tag=bob1\r\n" +
		"Call-ID: b2b_c1\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n")
	if err != nil {
		t.Fatal(err)
	}
	return msg
}

func requestLine(raw string) string { return strings.SplitN(raw, "\r\n", 2)[0] }

func (r *failureRig) nothingMore(t *testing.T) {
	t.Helper()
	buf := make([]byte, 4096)
	_ = r.scscf.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	if n, _, err := r.scscf.ReadFromUDP(buf); err == nil {
		t.Fatalf("unexpected message at the S-CSCF: %q", requestLine(string(buf[:n])))
	}
}

func TestCancelAfterCalleeAnsweredSendsBye(t *testing.T) {
	r, s, from := crossedRig(t)
	s.TermAnswered, s.TermTo = true, "<sip:bob@ims>;tag=bob1"

	r.b.handleCancel(cancelReq("c1", 1), from)

	got := r.received(t)
	if !strings.HasPrefix(got, "BYE ") || !strings.Contains(got, "Call-ID: b2b_c1") ||
		!strings.Contains(got, "tag=bob1") || !strings.Contains(got, "CSeq: 2 BYE") {
		t.Fatalf("want a BYE on the callee leg, got %q", got)
	}
	r.nothingMore(t)
}

func TestCalleeOKAfterCancelIsAckedAndEnded(t *testing.T) {
	r, _, from := crossedRig(t)

	r.b.handleCancel(cancelReq("c1", 1), from)
	if got := r.received(t); !strings.HasPrefix(got, "CANCEL ") {
		t.Fatalf("CANCEL not forwarded: %q", requestLine(got))
	}

	r.b.handle200OKInvite(calleeOK(t), from)
	if got := r.received(t); !strings.HasPrefix(got, "ACK ") || !strings.Contains(got, "Call-ID: b2b_c1") {
		t.Fatalf("want ACK for the callee's 200 OK, got %q", got)
	}
	if got := r.received(t); !strings.HasPrefix(got, "BYE ") || !strings.Contains(got, "tag=bob1") {
		t.Fatalf("want BYE on the callee leg, got %q", got)
	}
	r.nothingMore(t) // the 200 OK is not passed on to A

	// A retransmitted 200 OK must not send the callee a second BYE.
	r.b.handle200OKInvite(calleeOK(t), from)
	if got := r.received(t); strings.HasPrefix(got, "BYE ") {
		t.Fatalf("second BYE for a retransmitted 200 OK")
	}
}

func TestCancelBeforeAnswerOnlyCancels(t *testing.T) {
	r, _, from := crossedRig(t)
	r.b.handleCancel(cancelReq("c1", 1), from)
	if got := r.received(t); !strings.HasPrefix(got, "CANCEL ") {
		t.Fatalf("CANCEL not forwarded: %q", requestLine(got))
	}
	r.nothingMore(t)
}
