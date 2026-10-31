package mf

import (
	"net"
	"strings"
	"time"
)

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

func (s *SessionInfo) adcBlock() int {
	if s.AdcBlock == 0 {
		return 1
	}
	return s.AdcBlock
}

// newShadowSession builds a session that carries only the ADC side of a call
// slot. It shares nothing closable with its parent (no BdcPC, no media sockets).
func newShadowSession(parent *SessionInfo, id, callID string, adcBlock int) *SessionInfo {
	parent.mu.Lock()
	defer parent.mu.Unlock()
	return &SessionInfo{
		ID: id, CallID: callID, UserID: parent.UserID,
		LocalIP: parent.LocalIP, LocalPort: parent.LocalPort, BdcLocalPort: parent.BdcLocalPort,
		LocalAudioPort: parent.LocalAudioPort, LocalVideoPort: parent.LocalVideoPort, RtpIP: parent.RtpIP,
		ProxyMode: parent.ProxyMode, Mdc2Protocol: parent.Mdc2Protocol,
		DcasIP: parent.DcasIP, DcasPort: parent.DcasPort, ReplaceHttpUrls: parent.ReplaceHttpUrls,
		AdcBlock: adcBlock, CreatedAt: time.Now(), tcpConns: map[uint16]net.Conn{},
	}
}

// adcParentID returns the parent context id for either ADC suffix ("-adc" or "-app").
func adcParentID(id string) string {
	if p, ok := appParentID(id); ok {
		return p
	}
	return strings.TrimSuffix(id, "-adc")
}
