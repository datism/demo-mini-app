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

