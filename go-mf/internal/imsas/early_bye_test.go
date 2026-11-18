package imsas

import (
	"net"
	"strings"
	"testing"
	"time"
)

// GD_42: the caller hung up while the media was being set up. Its BYE removed the session, so the
// INVITE must not be forwarded to the callee afterwards (the callee would ring for a dead call).
func TestInviteNotForwardedAfterEarlyBye(t *testing.T) {
	r := newFailureRig(t)
	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", Callee: "bob", ContextID: "ctx1"}
	r.addSession(s)
	delete(r.b.sessions, "c1") // what handleBye does

	if r.b.forwardInviteToCallee(s, "INVITE sip:bob@ims SIP/2.0\r\nCall-ID: b2b_c1\r\n\r\n") {
		t.Fatalf("INVITE forwarded for a call that already ended")
	}
	buf := make([]byte, 2048)
	_ = r.scscf.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	if n, _, err := r.scscf.ReadFromUDP(buf); err == nil {
		t.Fatalf("something reached the S-CSCF: %q", string(buf[:n]))
	}
	if d := r.deleted(); len(d) != 1 || !strings.Contains(d[0], "ctx1") {
		t.Fatalf("media context allocated after the BYE not released: %v", d)
	}
}

func TestInviteForwardedWhileCallAlive(t *testing.T) {
	r := newFailureRig(t)
	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", Callee: "bob"}
	r.addSession(s)
	if !r.b.forwardInviteToCallee(s, "INVITE sip:bob@ims SIP/2.0\r\nCall-ID: b2b_c1\r\n\r\n") {
		t.Fatalf("INVITE not forwarded for a live call")
	}
	if got := r.received(t); !strings.HasPrefix(got, "INVITE ") {
		t.Fatalf("want INVITE at the S-CSCF, got %q", got)
	}
}

// A CANCEL during media setup removed the session, then a lookup (DCSF's media instruction) loaded a
// copy back from Redis. That copy must not count as the call being alive: B would ring for a call A
// already cancelled, and no CANCEL would ever follow.
func TestInviteNotForwardedForSessionReloadedAfterCancel(t *testing.T) {
	r := newFailureRig(t)
	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", Callee: "bob"}
	r.addSession(s)
	stored := s.ToSerializable()
	r.b.sessions["c1"] = stored.ToCallSession() // what getSession does after the CANCEL dropped the map entry

	if r.b.forwardInviteToCallee(s, "INVITE sip:bob@ims SIP/2.0\r\nCall-ID: b2b_c1\r\n\r\n") {
		t.Fatalf("INVITE forwarded for a cancelled call reloaded from Redis")
	}
	buf := make([]byte, 2048)
	_ = r.scscf.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
	if n, _, err := r.scscf.ReadFromUDP(buf); err == nil {
		t.Fatalf("something reached the S-CSCF: %q", string(buf[:n]))
	}
}

// The caller cancelled so quickly that its CANCEL reached the IMS-AS before the INVITE: the INVITE is
// answered 487 and nothing is set up, instead of ringing the callee for a call nobody is on.
func TestInviteAfterItsCancelIsTerminated(t *testing.T) {
	r := newFailureRig(t)
	from, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	defer from.Close()
	addr := from.LocalAddr().(*net.UDPAddr)

	cancel, _ := ParseSIPMessage("CANCEL sip:bob@ims SIP/2.0\r\nVia: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>\r\nCall-ID: early1\r\nCSeq: 1 CANCEL\r\nContent-Length: 0\r\n\r\n")
	r.b.handleCancel(cancel, addr)

	invite, _ := ParseSIPMessage("INVITE sip:bob@ims SIP/2.0\r\nVia: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK1\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>\r\nCall-ID: early1\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n")
	r.b.handleInvite(invite, addr)

	buf := make([]byte, 2048)
	var got []string
	_ = from.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	for {
		n, _, err := from.ReadFromUDP(buf)
		if err != nil {
			break
		}
		got = append(got, strings.SplitN(string(buf[:n]), "\r\n", 2)[0])
	}
	if len(got) != 2 || !strings.Contains(got[0], "200") || !strings.Contains(got[1], "487") {
		t.Fatalf("want 200 for the CANCEL then 487 for the INVITE, got %q", got)
	}
	if _, ok := r.b.sessions["early1"]; ok {
		t.Fatalf("a call was set up for an INVITE that came after its CANCEL")
	}
	r.nothingMore(t) // nothing towards the callee
}
