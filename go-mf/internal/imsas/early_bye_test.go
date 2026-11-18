package imsas

import (
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
