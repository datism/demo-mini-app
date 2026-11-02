package mf

import "testing"

func TestAdcStreamsIncludeContentShare(t *testing.T) {
	want := map[uint16][2]string{
		1000: {"app-dc", "whiteboard"},
		1001: {"app-dc-2", "emoji"},
		1002: {"app-dc-3", "content-share"},
	}
	if len(adcStreamIDs) != len(want) {
		t.Fatalf("adcStreamIDs=%v", adcStreamIDs)
	}
	for _, id := range adcStreamIDs {
		label, proto := adcStreamSpec(id)
		w, ok := want[id]
		if !ok || label != w[0] || proto != w[1] {
			t.Errorf("stream %d: got %q/%q want %v", id, label, proto, w)
		}
	}
}
