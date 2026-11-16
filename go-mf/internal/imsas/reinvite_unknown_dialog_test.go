package imsas

import (
	"strings"
	"testing"
)

// A re-INVITE (To has a tag) for a call the IMS-AS no longer knows is answered 481 and is not set up as a
// new call to the To user (a phone renegotiating after the other side hung up got the wrong-number
// announcement that way).
func TestReInviteForUnknownDialogGets481(t *testing.T) {
	r := newFailureRig(t)
	raw := "INVITE sip:bob@ims SIP/2.0\r\n" +
		"Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK9\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>;tag=b\r\n" +
		"Call-ID: gone1\r\nCSeq: 5 INVITE\r\n" +
		"Contact: <sip:alice@10.0.0.1:5060>;+sip.app-subtype=\"webrtc-datachannel\"\r\n" +
		"Content-Length: 0\r\n\r\n"
	msg, err := ParseSIPMessage(raw)
	if err != nil {
		t.Fatal(err)
	}
	r.b.handleInvite(msg, r.b.scscfAddr)

	got := r.received(t)
	if !strings.HasPrefix(got, "SIP/2.0 481") {
		t.Fatalf("want 481, got %q", got)
	}
	if _, ok := r.b.sessions["gone1"]; ok {
		t.Fatalf("a session was created for a re-INVITE of an unknown call")
	}
}
