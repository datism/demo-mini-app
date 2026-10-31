package mf

import (
	"strings"
	"testing"
)

func TestIsAdcContext(t *testing.T) {
	cases := map[string]bool{"c1": false, "c1-adc": true, "c1-app": true, "c1-home": false, "": false}
	for id, want := range cases {
		if got := isAdcContext(id); got != want {
			t.Errorf("isAdcContext(%q)=%v want %v", id, got, want)
		}
	}
}

func TestAppParentID(t *testing.T) {
	if p, ok := appParentID("c1-app"); !ok || p != "c1" {
		t.Fatalf("got %q %v", p, ok)
	}
	if _, ok := appParentID("c1"); ok {
		t.Fatal("plain id has no parent")
	}
	if _, ok := appParentID("c1-adc"); ok {
		t.Fatal("legacy -adc is not an app context")
	}
}

const threeBlockAnswer = "v=0\r\no=- 1 1 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 2 3 4\r\n" +
	"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=mid:2\r\na=ice-ufrag:bdc\r\na=ice-pwd:bdcpwdbdcpwdbdcpwdbdcpwd\r\na=fingerprint:sha-256 BB\r\na=setup:active\r\na=sctp-port:5000\r\n" +
	"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=mid:3\r\na=ice-ufrag:home\r\na=ice-pwd:homepwdhomepwdhomepwdhome\r\na=fingerprint:sha-256 HH\r\na=setup:active\r\na=sctp-port:5000\r\n" +
	"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\nc=IN IP4 1.1.1.1\r\na=mid:4\r\na=ice-ufrag:app\r\na=ice-pwd:apppwdapppwdapppwdapppwd\r\na=fingerprint:sha-256 AA\r\na=setup:active\r\na=sctp-port:5000\r\n"

func TestSplitSDPAnswerN_PicksRequestedBlock(t *testing.T) {
	_, home := splitSDPAnswerN(threeBlockAnswer, 1)
	_, app := splitSDPAnswerN(threeBlockAnswer, 2)
	if !strings.Contains(home, "a=ice-ufrag:home") || strings.Contains(home, "a=ice-ufrag:app") {
		t.Fatalf("home block wrong:\n%s", home)
	}
	if !strings.Contains(app, "a=ice-ufrag:app") || strings.Contains(app, "a=ice-ufrag:home") {
		t.Fatalf("app block wrong:\n%s", app)
	}
}

func TestSplitSDPAnswerN_MissingBlockIsEmpty(t *testing.T) {
	if _, adc := splitSDPAnswerN(threeBlockAnswer, 3); adc != "" {
		t.Fatalf("want empty, got:\n%s", adc)
	}
}

func TestSplitSDPAnswer_StillMeansHome(t *testing.T) {
	_, a := splitSDPAnswer(threeBlockAnswer)
	_, b := splitSDPAnswerN(threeBlockAnswer, 1)
	if a != b {
		t.Fatal("splitSDPAnswer must equal splitSDPAnswerN(…,1)")
	}
}
