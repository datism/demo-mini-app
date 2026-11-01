package uesim

import (
	"strings"
	"testing"
)

func TestAppendReqAppToSDPClose(t *testing.T) {
	origSDP := "v=0\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"a=dcmap:10 subprotocol=\"http\"\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"a=dcmap:1001 subprotocol=\"caro\"\r\n" +
		"a=3gpp-req-app:\"caro\";1001-UE\r\n" +
		"m=audio 40000 RTP/AVP 0\r\n"

	closedSDP := appendReqAppToSDPClose(origSDP, "caro")

	if !strings.Contains(closedSDP, "m=application 0 UDP/DTLS/SCTP webrtc-datachannel") {
		t.Errorf("Expected application port to be 0, got:\n%s", closedSDP)
	}
	if strings.Contains(closedSDP, "a=dcmap:1001") {
		t.Errorf("Expected dcmap attribute for closed app to be removed, got:\n%s", closedSDP)
	}
	if strings.Contains(closedSDP, "a=3gpp-req-app") {
		t.Errorf("Expected 3gpp-req-app attribute for closed app to be removed, got:\n%s", closedSDP)
	}
	if !strings.Contains(closedSDP, "m=audio 40000 RTP/AVP 0") {
		t.Errorf("Expected audio block to remain intact, got:\n%s", closedSDP)
	}
	if !strings.Contains(closedSDP, "a=dcmap:10 subprotocol=\"http\"") {
		t.Errorf("Expected BDC dcmap attribute to remain intact, got:\n%s", closedSDP)
	}
}

func TestReorderSDPMedia(t *testing.T) {
	origSDP := "v=0\r\n" +
		"m=audio 9 RTP/AVP 0\r\n" +
		"m=video 9 RTP/AVP 97\r\n" +
		"m=application 54321 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"a=sendonly\r\n" +
		"a=sctp-port:5000\r\n"

	reordered := reorderSDPMedia(origSDP, "127.0.0.1", 50002, 50004)

	if !strings.Contains(reordered, "a=sendrecv") {
		t.Errorf("Expected a=sendrecv in application section, got:\n%s", reordered)
	}
	if strings.Contains(reordered, "a=sendonly") {
		t.Errorf("Expected a=sendonly to be filtered out, got:\n%s", reordered)
	}
}


func threeAppSDP(appPort string) string {
	return "v=0\r\nm=audio 4000 RTP/AVP 0\r\nm=video 4002 RTP/AVP 96\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:2\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:3\r\n" +
		"m=application " + appPort + " UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:4\r\n"
}

func TestSetAppSlotReqApp_Open(t *testing.T) {
	out := setAppSlotReqApp(threeAppSDP("0"), "caro", true)
	i := strings.LastIndex(out, "m=application ")
	tail := out[i:]
	if !strings.HasPrefix(tail, "m=application 9 ") || !strings.Contains(tail, `a=3gpp-req-app:"caro";1000-UE`) || !strings.Contains(tail, `a=dcmap:1000 subprotocol="caro"`) {
		t.Fatalf("app section wrong:\n%s", tail)
	}
	if strings.Count(out, "3gpp-req-app") != 1 {
		t.Fatalf("only the 3rd section may carry req-app:\n%s", out)
	}
}

func TestSetAppSlotReqApp_Close(t *testing.T) {
	open := setAppSlotReqApp(threeAppSDP("0"), "caro", true)
	out := setAppSlotReqApp(open, "caro", false)
	i := strings.LastIndex(out, "m=application ")
	if !strings.HasPrefix(out[i:], "m=application 0 ") || strings.Contains(out[i:], "3gpp-req-app") {
		t.Fatalf("not closed:\n%s", out[i:])
	}
	if !strings.Contains(out, "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:2") {
		t.Fatal("BDC section must be untouched")
	}
}

func TestSetAppSlotReqApp_TwoSectionsUnchanged(t *testing.T) {
	two := "v=0\r\nm=application 9 X\r\nm=application 9 X\r\n"
	if got := setAppSlotReqApp(two, "caro", true); got != two {
		t.Fatalf("changed: %q", got)
	}
}
