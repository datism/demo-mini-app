package imsas

import (
	"net"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/demo/ims-dc-mf/internal/sba"
)

func hotlineReInvite(t *testing.T, appID, dir string) *SIPMessage {
	t.Helper()
	sdp := "v=0\r\no=- 1 2 IN IP4 10.0.0.5\r\ns=-\r\nt=0 0\r\n" +
		"m=audio 40002 RTP/AVP 8\r\nc=IN IP4 10.0.0.5\r\na=rtpmap:8 PCMA/8000\r\na=" + dir + "\r\n" +
		"m=application 50000 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 10.0.0.5\r\n" +
		"a=dcmap:1000 subprotocol=\"" + appID + "\"\r\na=3gpp-req-app:\"" + appID + "\";1000-UE\r\na=" + dir + "\r\n"
	msg, err := ParseSIPMessage("INVITE sip:1900@ims SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK7\r\n" +
		"From: <sip:1002@ims>;tag=a\r\nTo: <sip:1900@ims>;tag=b\r\nCall-ID: h1\r\nCSeq: 5 INVITE\r\n" +
		"Content-Type: application/sdp\r\nContent-Length: " + strconv.Itoa(len(sdp)) + "\r\n\r\n" + sdp)
	if err != nil {
		t.Fatal(err)
	}
	return msg
}

func hotlineRig(t *testing.T) (*failureRig, *CallSession, *net.UDPConn) {
	r := newFailureRig(t)
	s := &CallSession{CallID: "h1", OrigCallID: "h1", TermCallID: "b2b_h1", Caller: "1002", Callee: "1900",
		IsAnchorCtxCreated: true, ActiveAppID: "flight-booking", AdcType: "P2A",
		InstructionChan: make(chan *sba.MediaInstructionData, 1), ResponseChan: make(chan *sba.MediaInstructionData, 1)}
	r.addSession(s)
	ue, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ue.Close() })
	return r, s, ue
}

// dcsfAnswersP2A plays the DCSF: flight-booking is P2A. The instruction is sent after the
// IMS-AS drains stale ones, as the real DCSF answer would arrive.
func dcsfAnswersP2A(s *CallSession) {
	go func() {
		time.Sleep(100 * time.Millisecond)
		select {
		case s.InstructionChan <- &sba.MediaInstructionData{MediaInstructionSet: []sba.MediaInstructions{{
			MediaResourceType:    "DC",
			DcMediaSpecification: &sba.DcMediaSpecification{MediaProxyConfig: "HTTP_PROXY", AdcType: "P2A"},
		}}}:
		default:
		}
	}()
}

func readUDP(c *net.UDPConn, wait time.Duration) (string, bool) {
	buf := make([]byte, 8192)
	_ = c.SetReadDeadline(time.Now().Add(wait))
	n, _, err := c.ReadFromUDP(buf)
	if err != nil {
		return "", false
	}
	return string(buf[:n]), true
}

// Hold and resume on 1900 are answered by the IMS-AS itself; they used to be taken for an
// app switch and forwarded toward "1900", which failed and made the phone undo the hold.
func TestHotlineHoldAndResumeAnsweredLocally(t *testing.T) {
	for _, dir := range []string{"inactive", "sendrecv"} {
		r, s, ue := hotlineRig(t)
		dcsfAnswersP2A(s)
		r.b.handleReInvite(s, hotlineReInvite(t, "flight-booking", dir), ue.LocalAddr().(*net.UDPAddr))

		got, ok := readUDP(ue, time.Second)
		if !ok || !strings.HasPrefix(got, "SIP/2.0 200") {
			t.Fatalf("%s: want 200 OK to the phone, got %q", dir, got)
		}
		if !strings.Contains(got, "a="+dir) {
			t.Fatalf("%s: answer lost the direction: %q", dir, got)
		}
		if fwd, ok := readUDP(r.scscf, 300*time.Millisecond); ok {
			t.Fatalf("%s: re-INVITE forwarded to the S-CSCF: %q", dir, fwd)
		}
		if s.ActiveAppID != "flight-booking" || !s.IsAnchorCtxCreated {
			t.Fatalf("%s: session app state changed: %q %v", dir, s.ActiveAppID, s.IsAnchorCtxCreated)
		}
	}
}

// Opening a different app on a hotline is not a hold: it is not answered by the new branch.
func TestHotlineOtherAppNotTakenForHold(t *testing.T) {
	r, s, ue := hotlineRig(t)
	go r.b.handleReInvite(s, hotlineReInvite(t, "smart-menu", "sendrecv"), ue.LocalAddr().(*net.UDPAddr))
	if got, ok := readUDP(ue, 300*time.Millisecond); ok && strings.Contains(got, "a=sendrecv") && strings.HasPrefix(got, "SIP/2.0 200") {
		t.Fatalf("app change answered as a hold: %q", got)
	}
}
