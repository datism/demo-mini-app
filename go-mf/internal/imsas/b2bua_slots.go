package imsas

import "github.com/demo/ims-dc-mf/internal/sba"

type slotState struct {
	AppID      string
	CtxCreated bool
	AdcType    string // App slot only: P2A / P2A2P from the DCSF instruction
}

func appContextID(callID string) string { return callID + slotSuffix(SlotApp) }

func slotMFFromContext(ctx *sba.MediaContext) (a, b *SlotMF) {
	if ctx == nil {
		return nil, nil
	}
	for _, term := range ctx.Terminations {
		for _, mi := range term.Medias {
			if mi.MediaID != "adc-media" || mi.LocalMbEndpoint == nil {
				continue
			}
			sm := &SlotMF{Port: mi.LocalMbEndpoint.Port}
			if dc := mi.LocalDcEndpoint; dc != nil {
				sm.IceUfrag, sm.IcePwd, sm.Fingerprint, sm.Setup = dc.IceUfrag, dc.IcePwd, dc.Fingerprint, dc.SecuritySetup
			}
			switch term.TerminationID {
			case "term-caller-a":
				a = sm
			case "term-callee-b":
				b = sm
			}
		}
	}
	return a, b
}

func (s *CallSession) withSlots(base *MFAllocResponse, appMf *SlotMF) *MFAllocResponse {
	if base == nil {
		return nil
	}
	cp := *base
	cp.Slots = map[AdcSlot]*SlotMF{
		SlotHome: {Port: base.AdcPort, IceUfrag: base.AdcIceUfrag, IcePwd: base.AdcIcePwd,
			Fingerprint: base.AdcDtlsFingerprint, Setup: "actpass"},
	}
	if appMf != nil {
		cp.Slots[SlotApp] = appMf
	}
	return &cp
}
