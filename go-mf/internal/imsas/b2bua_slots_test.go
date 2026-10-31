package imsas

import (
	"testing"

	"github.com/demo/ims-dc-mf/internal/sba"
)

func adcItem(ip string, port int, ufrag string) sba.MediaItem {
	return sba.MediaItem{
		MediaID: "adc-media", MediaResourceType: "DC",
		LocalMbEndpoint: &sba.EndpointIPPort{IP: ip, Port: port},
		LocalDcEndpoint: &sba.DcEndpoint{Fingerprint: "sha-256 FF", IceUfrag: ufrag, IcePwd: "pw", SecuritySetup: "actpass"},
	}
}

func TestSlotMFFromContext(t *testing.T) {
	ctx := &sba.MediaContext{Terminations: []sba.Termination{
		{TerminationID: "term-caller-a", Medias: []sba.MediaItem{{MediaID: "audio", MediaResourceType: "RTP"}, adcItem("1.1.1.1", 40010, "ua")}},
		{TerminationID: "term-callee-b", Medias: []sba.MediaItem{adcItem("1.1.1.1", 40011, "ub")}},
	}}
	a, b := slotMFFromContext(ctx)
	if a == nil || a.Port != 40010 || a.IceUfrag != "ua" || a.Fingerprint != "sha-256 FF" || a.Setup != "actpass" {
		t.Fatalf("a=%+v", a)
	}
	if b == nil || b.Port != 40011 {
		t.Fatalf("b=%+v", b)
	}
}

func TestSlotMFFromContext_NoAdc(t *testing.T) {
	a, b := slotMFFromContext(&sba.MediaContext{})
	if a != nil || b != nil {
		t.Fatal("want nil,nil")
	}
}

func TestWithSlots_CopiesAndDoesNotMutate(t *testing.T) {
	base := &MFAllocResponse{SessionID: "s", AdcPort: 40001, AdcIceUfrag: "hu", AdcIcePwd: "hp", AdcDtlsFingerprint: "sha-256 HH"}
	s := &CallSession{}
	out := s.withSlots(base, &SlotMF{Port: 40002, IceUfrag: "au"})
	if base.Slots != nil {
		t.Fatal("base must not be mutated")
	}
	h, a := out.Slots[SlotHome], out.Slots[SlotApp]
	if h == nil || h.Port != 40001 || h.IceUfrag != "hu" || h.Fingerprint != "sha-256 HH" || h.Setup != "actpass" {
		t.Fatalf("home=%+v", h)
	}
	if a == nil || a.Port != 40002 {
		t.Fatalf("app=%+v", a)
	}
	if out2 := s.withSlots(base, nil); out2.Slots[SlotApp] != nil {
		t.Fatal("nil app must omit the App slot")
	}
}

func TestSerializableRoundTripKeepsAppSlot(t *testing.T) {
	s := &CallSession{CallID: "c1", AppSlot: slotState{AppID: "caro", CtxCreated: true}, AppMfA: &SlotMF{Port: 1}, AppMfB: &SlotMF{Port: 2}}
	ser := s.ToSerializable()
	back := ser.ToCallSession()
	if back.AppSlot.AppID != "caro" || !back.AppSlot.CtxCreated || back.AppMfA.Port != 1 || back.AppMfB.Port != 2 {
		t.Fatalf("lost: %+v", back)
	}
	if appContextID("c1") != "c1-app" {
		t.Fatal("appContextID")
	}
}
