package mf

import "strings"

// isAdcContext reports whether a context/call id names an ADC sub-context.
// "-adc" is the legacy name (the IMS-AS no longer creates it); "-app" is the
// App slot. The Home ADC lives on the parent context and has no suffix.
func isAdcContext(id string) bool {
	return strings.HasSuffix(id, "-adc") || strings.HasSuffix(id, "-app")
}

func appParentID(contextID string) (string, bool) {
	if !strings.HasSuffix(contextID, "-app") {
		return "", false
	}
	return strings.TrimSuffix(contextID, "-app"), true
}
