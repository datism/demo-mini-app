package imsas

import "testing"

const sdpBase = "v=0\r\no=- 1 1 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\n" +
	"m=audio 9 UDP/TLS/RTP/SAVPF 8\r\nc=IN IP4 1.1.1.1\r\n" +
	"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=dcmap:10 subprotocol=\"http\"\r\n"

func TestParseAdcLines_LegacySingleAdcMapsToHome(t *testing.T) {
	sdp := sdpBase + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=3gpp-req-app:\"caro\";1000-UE\r\n"
	got := parseAdcLines(sdp)
	if len(got) != 1 || got[0].Slot != SlotHome || got[0].Binding == nil || got[0].Binding.ReqAppId != "caro" {
		t.Fatalf("got %+v", got)
	}
}

func TestParseAdcLines_HomeAndApp(t *testing.T) {
	sdp := sdpBase +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"fun-calling-app\";1000-UE\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"caro\";1000-UE\r\n"
	got := parseAdcLines(sdp)
	if len(got) != 2 || got[0].Slot != SlotHome || got[1].Slot != SlotApp {
		t.Fatalf("got %+v", got)
	}
	if got[0].Binding.ReqAppId != "fun-calling-app" || got[1].Binding.ReqAppId != "caro" {
		t.Fatalf("apps: %+v %+v", got[0].Binding, got[1].Binding)
	}
}

func TestParseAdcLines_AppPortZeroIsInactive(t *testing.T) {
	sdp := sdpBase +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"fun-calling-app\";1000-UE\r\n" +
		"m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\n"
	got := parseAdcLines(sdp)
	if len(got) != 2 || !got[0].Active() || got[1].Active() || got[1].Binding != nil {
		t.Fatalf("got %+v", got)
	}
	if adcLineFor(got, SlotApp) == nil || adcLineFor(got, SlotHome) == nil {
		t.Fatal("adcLineFor must find both")
	}
}

func TestParseAdcLines_NoAdc(t *testing.T) {
	if got := parseAdcLines(sdpBase); len(got) != 0 {
		t.Fatalf("got %+v", got)
	}
}

func TestParseAdcLines_IgnoresBdcStreamReqApp(t *testing.T) {
	sdp := sdpBase + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"x\";10-UE\r\n"
	got := parseAdcLines(sdp)
	if len(got) != 1 || got[0].Binding != nil {
		t.Fatalf("got %+v", got)
	}
}

func TestSlotSuffix(t *testing.T) {
	if slotSuffix(SlotHome) != "-home" || slotSuffix(SlotApp) != "-app" {
		t.Fatal("suffix")
	}
}
