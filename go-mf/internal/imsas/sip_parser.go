package imsas

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
)

type SIPMessage struct {
	Raw          string
	IsResponse   bool
	Method       string
	RURI         string
	Proto        string
	StatusCode   int
	ReasonPhrase string
	Headers      map[string]string
	Body         string
}

func ParseSIPMessage(raw string) (*SIPMessage, error) {
	parts := strings.SplitN(raw, "\r\n\r\n", 2)
	if len(parts) < 1 {
		return nil, fmt.Errorf("invalid message format")
	}
	headersPart := parts[0]
	var bodyPart string
	if len(parts) == 2 {
		bodyPart = parts[1]
	}

	lines := strings.Split(headersPart, "\r\n")
	if len(lines) == 0 || lines[0] == "" {
		return nil, fmt.Errorf("empty request line")
	}

	msg := &SIPMessage{
		Raw:     raw,
		Headers: make(map[string]string),
		Body:    bodyPart,
	}

	firstLine := lines[0]
	tokens := strings.Split(firstLine, " ")
	if len(tokens) < 3 {
		return nil, fmt.Errorf("invalid start line: %s", firstLine)
	}

	if strings.HasPrefix(tokens[0], "SIP/") {
		msg.IsResponse = true
		msg.Proto = tokens[0]
		code, _ := strconv.Atoi(tokens[1])
		msg.StatusCode = code
		msg.ReasonPhrase = strings.Join(tokens[2:], " ")
	} else {
		msg.IsResponse = false
		msg.Method = tokens[0]
		msg.RURI = tokens[1]
		msg.Proto = tokens[2]
	}

	for _, line := range lines[1:] {
		if line == "" {
			continue
		}
		hParts := strings.SplitN(line, ":", 2)
		if len(hParts) == 2 {
			key := strings.ToLower(strings.TrimSpace(hParts[0]))
			val := strings.TrimSpace(hParts[1])
			if existing, ok := msg.Headers[key]; ok {
				msg.Headers[key] = existing + ", " + val
			} else {
				msg.Headers[key] = val
			}
		}
	}

	return msg, nil
}

func (msg *SIPMessage) GetHeader(name string) string {
	return msg.Headers[strings.ToLower(name)]
}

func (msg *SIPMessage) ExtractUsername(headerName string) string {
	val := msg.GetHeader(headerName)
	if val == "" {
		return ""
	}
	// Extract from "<sip:user@domain>" or "sip:user@domain"
	idx := strings.Index(val, "sip:")
	if idx == -1 {
		return ""
	}
	sub := val[idx+4:]
	endIdx := strings.Index(sub, "@")
	if endIdx == -1 {
		return sub
	}
	return sub[:endIdx]
}

func (b *B2BUA) BuildSIPResponse(orig *SIPMessage, statusCode int, reason string, headers map[string]string, body string) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("SIP/2.0 %d %s\r\n", statusCode, reason))

	// Helper to check if a header is overridden in custom headers map
	isOverridden := func(name string) bool {
		for k := range headers {
			if strings.EqualFold(k, name) {
				return true
			}
		}
		return false
	}

	// Copy mandatory headers from origin if not overridden
	if via := orig.GetHeader("Via"); via != "" && !isOverridden("Via") {
		// Write each Via value on its own line for proper Via stack handling
		for _, v := range strings.Split(via, ", ") {
			sb.WriteString(fmt.Sprintf("Via: %s\r\n", strings.TrimSpace(v)))
		}
	}
	if from := orig.GetHeader("From"); from != "" && !isOverridden("From") {
		sb.WriteString(fmt.Sprintf("From: %s\r\n", from))
	}
	// RFC 3261 §8.2.6.2: UAS MUST add to-tag for non-100 responses
	if to := orig.GetHeader("To"); to != "" && !isOverridden("To") {
		if statusCode > 100 && !strings.Contains(to, ";tag=") {
			tag := generateSIPTag()
			sb.WriteString(fmt.Sprintf("To: %s;tag=%s\r\n", to, tag))
		} else {
			sb.WriteString(fmt.Sprintf("To: %s\r\n", to))
		}
	}
	if callID := orig.GetHeader("Call-ID"); callID != "" && !isOverridden("Call-ID") {
		sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", callID))
	}
	if cseq := orig.GetHeader("CSeq"); cseq != "" && !isOverridden("CSeq") {
		sb.WriteString(fmt.Sprintf("CSeq: %s\r\n", cseq))
	}
	if recRoute := orig.GetHeader("Record-Route"); recRoute != "" && !isOverridden("Record-Route") {
		sb.WriteString(fmt.Sprintf("Record-Route: %s\r\n", recRoute))
	}

	// RFC 7989: Passthrough Session-ID
	if sessionID := orig.GetHeader("Session-ID"); sessionID != "" && !isOverridden("Session-ID") {
		sb.WriteString(fmt.Sprintf("Session-ID: %s\r\n", sessionID))
	}

	// Add custom headers
	for k, v := range headers {
		sb.WriteString(fmt.Sprintf("%s: %s\r\n", k, v))
	}

	if statusCode == 200 {
		cseq := orig.GetHeader("CSeq")
		if strings.Contains(cseq, "INVITE") || strings.Contains(cseq, "REGISTER") || strings.Contains(cseq, "UPDATE") {
			sb.WriteString("Feature-Caps: *;+g.3gpp.datachannel\r\n")
		}
	}

	sb.WriteString(fmt.Sprintf("Content-Length: %d\r\n", len(body)))
	sb.WriteString("\r\n")
	sb.WriteString(body)

	return sb.String()
}

func (b *B2BUA) BuildSIPRequest(method, ruri string, orig *SIPMessage, headers map[string]string, body string) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("%s %s SIP/2.0\r\n", method, ruri))

	// Helper to check if a header is overridden in custom headers map
	isOverridden := func(name string) bool {
		for k := range headers {
			if strings.EqualFold(k, name) {
				return true
			}
		}
		return false
	}

	// Copy headers (respecting overrides from headers map)
	if via := orig.GetHeader("Via"); via != "" {
		// Add IMS-AS Via on its own line, then original Via(s) on separate lines
		// RFC 3261 §7.3.1: Multiple header field values can be on separate lines
		viaIP := b.localIP
		if viaIP == "" {
			viaIP = "ims-as"
		}
		sb.WriteString(fmt.Sprintf("Via: SIP/2.0/UDP %s:5060;branch=z9hG4bK-ims-as-%s\r\n", viaIP, generateSIPTag()))
		// Write each original Via value on its own line
		for _, v := range strings.Split(via, ", ") {
			sb.WriteString(fmt.Sprintf("Via: %s\r\n", strings.TrimSpace(v)))
		}
	}
	if from := orig.GetHeader("From"); from != "" && !isOverridden("From") {
		sb.WriteString(fmt.Sprintf("From: %s\r\n", from))
	}
	if to := orig.GetHeader("To"); to != "" && !isOverridden("To") {
		sb.WriteString(fmt.Sprintf("To: %s\r\n", to))
	}
	if callID := orig.GetHeader("Call-ID"); callID != "" && !isOverridden("Call-ID") {
		sb.WriteString(fmt.Sprintf("Call-ID: %s\r\n", callID))
	}
	if cseq := orig.GetHeader("CSeq"); cseq != "" && !isOverridden("CSeq") {
		sb.WriteString(fmt.Sprintf("CSeq: %s\r\n", cseq))
	}
	if route := orig.GetHeader("Route"); route != "" && !isOverridden("Route") {
		sb.WriteString(fmt.Sprintf("Route: %s\r\n", route))
	}
	if recRoute := orig.GetHeader("Record-Route"); recRoute != "" && !isOverridden("Record-Route") {
		sb.WriteString(fmt.Sprintf("Record-Route: %s\r\n", recRoute))
	}
	if contact := orig.GetHeader("Contact"); contact != "" && !isOverridden("Contact") {
		sb.WriteString(fmt.Sprintf("Contact: %s\r\n", contact))
	}

	// RFC 7989: Passthrough Session-ID (if not overridden)
	if sessionID := orig.GetHeader("Session-ID"); sessionID != "" && !isOverridden("Session-ID") {
		sb.WriteString(fmt.Sprintf("Session-ID: %s\r\n", sessionID))
	}

	for k, v := range headers {
		sb.WriteString(fmt.Sprintf("%s: %s\r\n", k, v))
	}

	sb.WriteString(fmt.Sprintf("Content-Length: %d\r\n", len(body)))
	sb.WriteString("\r\n")
	sb.WriteString(body)

	return sb.String()
}

// generateSIPTag creates a random tag string for SIP headers
func generateSIPTag() string {
	bytes := make([]byte, 8)
	_, _ = rand.Read(bytes)
	return hex.EncodeToString(bytes)
}

func (msg *SIPMessage) GetCSeq() (int, string) {
	cseq := msg.GetHeader("CSeq")
	if cseq == "" {
		return 0, ""
	}
	parts := strings.Fields(cseq)
	if len(parts) < 2 {
		return 0, ""
	}
	num, _ := strconv.Atoi(parts[0])
	return num, parts[1]
}
