package imsas

import (
	"strings"
	"testing"
)

func TestSlidesPortFor(t *testing.T) {
	cases := []struct{ port, base, want int }{
		{40002, 40000, 40001}, {40004, 40000, 40003}, // caller leg
		{40006, 40000, 40007}, {40008, 40000, 40009}, // callee leg
		{40005, 40000, 0}, {1234, 40000, 0},
	}
	for _, c := range cases {
		if got := slidesPortFor(c.port, c.base); got != c.want {
			t.Errorf("slidesPortFor(%d,%d)=%d want %d", c.port, c.base, got, c.want)
		}
	}
}

func slidesOffer(slidesPort string) string {
	return "v=0\r\no=- 1 1 IN IP4 1.1.1.1\r\ns=-\r\nt=0 0\r\n" +
		"m=audio 40010 RTP/AVP 8\r\nc=IN IP4 1.1.1.1\r\na=mid:0\r\n" +
		"m=video 40012 RTP/AVP 97\r\nc=IN IP4 1.1.1.1\r\na=mid:1\r\n" +
		"m=video " + slidesPort + " RTP/AVP 97\r\nc=IN IP4 1.1.1.1\r\na=mid:5\r\na=content:slides\r\na=sendonly\r\n" +
		"m=audio " + slidesPort + " RTP/AVP 8\r\nc=IN IP4 1.1.1.1\r\na=mid:6\r\na=content:slides\r\na=sendonly\r\n"
}

func mfFor(leg string) *MFAllocResponse {
	m := testMf()
	m.Port = 40000
	if leg == "callee" {
		m.AudioPort, m.VideoPort = 40006, 40008
	} else {
		m.AudioPort, m.VideoPort = 40002, 40004
	}
	return m
}

// The camera pair keeps the leg's camera ports; the shared-content pair gets the leg's own slides ports.
func TestRewriteSDP_SlidesPairGetsItsOwnMfPorts(t *testing.T) {
	for _, c := range []struct{ leg, a, v, sa, sv string }{
		{"caller", "40002", "40004", "40001", "40003"},
		{"callee", "40006", "40008", "40007", "40009"},
	} {
		out := RewriteSDP(slidesOffer("50000"), mfFor(c.leg), "")
		for _, want := range []string{
			"m=audio " + c.a + " RTP/AVP 8", "m=video " + c.v + " RTP/AVP 97",
			"m=video " + c.sv + " RTP/AVP 97", "m=audio " + c.sa + " RTP/AVP 8",
			"a=content:slides", "a=sendonly",
		} {
			if !strings.Contains(out, want) {
				t.Fatalf("%s leg: missing %q in:\n%s", c.leg, want, out)
			}
		}
	}
}

func TestRewriteSDP_ClosedSlidesPairStaysClosed(t *testing.T) {
	out := RewriteSDP(slidesOffer("0"), mfFor("caller"), "")
	if strings.Count(out, "m=video 0 RTP/AVP 97") != 1 || strings.Count(out, "m=audio 0 RTP/AVP 8") != 1 {
		t.Fatalf("a closed slides pair must stay at port 0:\n%s", out)
	}
	if !strings.Contains(out, "m=audio 40002 RTP/AVP 8") {
		t.Fatalf("camera audio must be untouched:\n%s", out)
	}
}
