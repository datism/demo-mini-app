package imsas

import (
	"strings"
	"testing"
)

// A hotline has no far-end leg, so IMS-AS answers the caller's app re-INVITE itself. The
// answer must carry MF's caller-side (A) ADC transport, the one MF actually listens with,
// not the callee-side (B) offer that would go to a real peer.
func TestHotlineReInviteAnswerUsesCallerLeg(t *testing.T) {
	offer := "v=0\r\no=- 1 2 IN IP4 10.0.0.9\r\ns=-\r\nt=0 0\r\n" +
		"m=audio 4000 RTP/AVP 8\r\nm=video 4002 RTP/AVP 97\r\n" +
		"m=application 4004 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"m=application 4006 UDP/DTLS/SCTP webrtc-datachannel\r\n"
	a := &MFAllocResponse{SessionID: "ctx-A", IP: "10.204.101.55", Port: 40000, SetupRole: "passive",
		IceUfrag: "bdcA", IcePwd: "pwdA", DtlsFingerprint: "sha-256 AA",
		AdcPort: 40005, AdcIceUfrag: "adcA", AdcIcePwd: "adcPwdA", AdcDtlsFingerprint: "sha-256 A1", AdcSetupRole: "passive"}
	b := &MFAllocResponse{SessionID: "ctx-B", IP: "10.204.101.55", Port: 40000, SetupRole: "actpass",
		IceUfrag: "bdcB", IcePwd: "pwdB", DtlsFingerprint: "sha-256 BB",
		AdcPort: 40005, AdcIceUfrag: "adcB", AdcIcePwd: "adcPwdB", AdcDtlsFingerprint: "sha-256 B1", AdcSetupRole: "actpass"}

	answer := hotlineReInviteAnswer(offer, a, b, true, "flight-booking")

	adc := answer[strings.LastIndex(answer, "m=application"):]
	for _, want := range []string{"a=ice-ufrag:adcA", "a=fingerprint:sha-256 A1", "a=setup:passive", `a=dcmap:1000 subprotocol="flight-booking"`} {
		if !strings.Contains(adc, want) {
			t.Errorf("ADC answer missing %q:\n%s", want, adc)
		}
	}
	if strings.Contains(answer, "adcB") || strings.Contains(answer, "actpass") {
		t.Errorf("answer leaks the callee-leg offer:\n%s", answer)
	}
}
