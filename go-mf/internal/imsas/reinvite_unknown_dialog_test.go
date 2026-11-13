package imsas

import (
	"net"
	"strings"
	"testing"
	"time"
)

// A re-INVITE (To-tag) for a call the IMS-AS no longer knows gets 481 and never becomes a new call.
func TestReInviteForUnknownDialogGets481(t *testing.T) {
	r := newFailureRig(t)
	from, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer from.Close()

	raw := "INVITE sip:bob@ims SIP/2.0\r\n" +
		"Via: SIP/2.0/UDP 10.0.0.1:5060;branch=z9hG4bK9\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>;tag=b\r\n" +
		"Call-ID: gone1\r\nCSeq: 9 INVITE\r\n" +
		"Content-Length: 0\r\n\r\n"
	msg, err := ParseSIPMessage(raw)
	if err != nil {
		t.Fatal(err)
	}
	r.b.handleInvite(msg, from.LocalAddr().(*net.UDPAddr))

	buf := make([]byte, 4096)
	from.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := from.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("no response: %v", err)
	}
	if got := string(buf[:n]); !strings.HasPrefix(got, "SIP/2.0 481") {
		t.Fatalf("want 481, got %q", got)
	}
	if len(r.b.sessions) != 0 {
		t.Fatalf("a session was created for a re-INVITE of an unknown call")
	}
}
