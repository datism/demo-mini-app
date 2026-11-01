package imsas

import (
	"strings"
	"testing"
)

func threeAppSDP(appPort string) string {
	return "v=0\r\no=- 1 1 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\n" +
		"m=audio 9 UDP/TLS/RTP/SAVPF 8\r\nc=IN IP4 1.1.1.1\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=dcmap:10 subprotocol=\"http\"\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=3gpp-req-app:\"fun-calling-app\";1000-UE\r\n" +
		"m=application " + appPort + " UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=3gpp-req-app:\"caro\";1000-UE\r\n"
}

func testMf() *MFAllocResponse {
	return &MFAllocResponse{
		SessionID: "call-A", IP: "10.0.0.1", Port: 40000,
		IceUfrag: "bu", IcePwd: "bp", DtlsFingerprint: "sha-256 BB", SetupRole: "passive",
		Slots: map[AdcSlot]*SlotMF{
			SlotHome: {Port: 40001, IceUfrag: "hu", IcePwd: "hp", Fingerprint: "sha-256 HH", Setup: "actpass"},
			SlotApp:  {Port: 40002, IceUfrag: "au", IcePwd: "ap", Fingerprint: "sha-256 AA", Setup: "actpass"},
		},
	}
}

func TestGetTLSId_Deterministic(t *testing.T) {
	if getTLSId("x-home") != getTLSId("x-home") {
		t.Fatal("must be stable")
	}
	if getTLSId("x-home") == getTLSId("x-app") {
		t.Fatal("different keys must differ")
	}
	if n := len(getTLSId("x")); n < 20 {
		t.Fatalf("tls-id too short: %d", n)
	}
}

func TestRewriteSDP_UnchangedLinesAreByteIdentical(t *testing.T) {
	first := RewriteSDP(threeAppSDP("9"), testMf(), "caro")
	second := RewriteSDP(threeAppSDP("9"), testMf(), "caro")
	if first != second {
		t.Fatalf("two rewrites of the same input differ:\n%s\n---\n%s", first, second)
	}
}

func TestRewriteSDP_SlotsUseOwnMfEndpoints(t *testing.T) {
	out := RewriteSDP(threeAppSDP("9"), testMf(), "caro")
	for _, want := range []string{
		"m=application 40001 ", "a=ice-ufrag:hu", "a=fingerprint:sha-256 HH",
		"m=application 40002 ", "a=ice-ufrag:au", "a=fingerprint:sha-256 AA",
		"a=3gpp-req-app:\"caro\";1000-UE",
	} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in:\n%s", want, out)
		}
	}
	if !strings.Contains(out, "a=tls-id:"+getTLSId("call-A-adc")) || !strings.Contains(out, "a=tls-id:"+getTLSId("call-A-app-au")) {
		t.Fatalf("tls-ids not per slot:\n%s", out)
	}
}

func TestRewriteSDP_AppPortZeroStaysZero(t *testing.T) {
	out := RewriteSDP(threeAppSDP("0"), testMf(), "")
	if !strings.Contains(out, "m=application 0 UDP/DTLS/SCTP") {
		t.Fatalf("App slot must stay port 0:\n%s", out)
	}
	if strings.Contains(out, "m=application 40002") {
		t.Fatalf("inactive App slot must not get an MF port:\n%s", out)
	}
}

func TestRewriteSDP_LegacyFieldsStillWork(t *testing.T) {
	legacy := &MFAllocResponse{SessionID: "c-A", IP: "10.0.0.1", Port: 40000, AdcPort: 40009,
		AdcIceUfrag: "u", AdcIcePwd: "p", AdcDtlsFingerprint: "sha-256 LL", AdcSetupRole: "actpass"}
	sdp := "v=0\r\no=- 1 1 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
	out := RewriteSDP(sdp, legacy, "caro")
	if !strings.Contains(out, "m=application 40009 ") || !strings.Contains(out, "a=tls-id:"+getTLSId("c-A-adc")) {
		t.Fatalf("legacy path broken:\n%s", out)
	}
}

func TestExcludeADC_ThreeLinesDisablesOnlyApp(t *testing.T) {
	out := ExcludeADCFromSDP(threeAppSDP("9"))
	lines := parseAdcLines(out)
	if len(lines) != 2 || !lines[0].Active() || lines[1].Active() {
		t.Fatalf("got %+v\n%s", lines, out)
	}
}

func TestExcludeADC_LegacyOneAdcStillDisabled(t *testing.T) {
	sdp := "v=0\r\ns=-\r\nt=0 0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"x\";1000-UE\r\n"
	out := ExcludeADCFromSDP(sdp)
	if l := parseAdcLines(out); len(l) != 1 || l[0].Active() {
		t.Fatalf("got %+v", l)
	}
}
