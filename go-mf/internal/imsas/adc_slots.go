package imsas

import (
	"strconv"
	"strings"

	"github.com/demo/ims-dc-mf/internal/sba"
)

type AdcSlot string

const (
	SlotHome AdcSlot = "home"
	SlotApp  AdcSlot = "app"
)

type adcLine struct {
	Slot    AdcSlot
	Port    int
	Binding *sba.AppBindingInfo
}

func (l adcLine) Active() bool { return l.Port != 0 }

func slotSuffix(s AdcSlot) string { return "-" + string(s) }

func adcLineFor(lines []adcLine, s AdcSlot) *adcLine {
	for i := range lines {
		if lines[i].Slot == s {
			return &lines[i]
		}
	}
	return nil
}

// parseAdcLines returns the ADC m-lines of an SDP. The first m=application is
// the BDC and is skipped; the second is the Home slot, the third the App slot.
func parseAdcLines(sdp string) []adcLine {
	var out []adcLine
	appIdx := -1
	for _, raw := range strings.Split(sdp, "\n") {
		line := strings.TrimSpace(raw)
		if strings.HasPrefix(line, "m=") {
			if !strings.HasPrefix(line, "m=application ") {
				appIdx = -2 // inside a non-application section: ignore attributes
				continue
			}
			if appIdx == -2 {
				appIdx = -1
			}
			appIdx++
			if appIdx == 0 {
				appIdx = 0
				continue
			}
			l := adcLine{}
			switch appIdx {
			case 1:
				l.Slot = SlotHome
			case 2:
				l.Slot = SlotApp
			default:
				appIdx = 3
				continue
			}
			if f := strings.Fields(line); len(f) >= 2 {
				l.Port, _ = strconv.Atoi(f[1])
			}
			out = append(out, l)
			continue
		}
		if appIdx >= 1 && appIdx <= 2 && len(out) > 0 && strings.HasPrefix(line, "a=3gpp-req-app:") {
			if b := parseReqApp(line); b != nil {
				out[len(out)-1].Binding = b
			}
		}
	}
	return out
}
