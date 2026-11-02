package imsas

import (
	"strings"
	"testing"
)

func shareSDP(withShare bool) string {
	s := threeAppSDP("9")
	if withShare {
		s += "a=dcmap:1002 subprotocol=\"content-share\"\r\n"
	}
	return s
}

func TestContentShareBlocks(t *testing.T) {
	got := contentShareBlocks(shareSDP(true))
	if len(got) != 1 || !got[3] {
		t.Fatalf("share stream must be reported on the 3rd m=application, got %v", got)
	}
	if len(contentShareBlocks(shareSDP(false))) != 0 {
		t.Fatal("no share stream expected")
	}
}

// The App block keeps the content-share stream through the rewrite (the callee
// needs it in the offer to know a share is requested) and drops it when absent.
func TestRewriteSDP_KeepsContentShareStreamOnAppBlock(t *testing.T) {
	with := RewriteSDP(shareSDP(true), testMf(), "photo-share")
	if strings.Count(with, contentShareDcmap) != 1 {
		t.Fatalf("expected one content-share dcmap:\n%s", with)
	}
	// It must sit after the App m-line, not in the Home block.
	if strings.Index(with, contentShareDcmap) < strings.Index(with, "m=application 40002 ") {
		t.Fatalf("content-share dcmap is not in the App block:\n%s", with)
	}
	if strings.Contains(RewriteSDP(shareSDP(false), testMf(), "photo-share"), "dcmap:1002") {
		t.Fatal("a=dcmap:1002 must not appear when the offer has none")
	}
}
