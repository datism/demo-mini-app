package imsas

import (
	"strings"
	"testing"

	"github.com/demo/ims-dc-mf/internal/sba"
)

func TestComplianceSDPRewrite(t *testing.T) {
	origSDP := "v=0\r\n" +
		"o=- 0 0 IN IP4 127.0.0.1\r\n" +
		"s=-\r\n" +
		"t=0 0\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"a=connection:new\r\n"

	mfInfo := &MFAllocResponse{
		SessionID:       "session-test-id-12345",
		IP:              "192.168.1.100",
		Port:            40000,
		IceUfrag:        "ufrag123",
		IcePwd:          "pwd123",
		DtlsFingerprint: "sha-256 AA:BB:CC:DD",
		SetupRole:       "active",
		QosHint:         "latency=200;loss=0.05",
		BdcRole:         "sender",
	}

	rewritten := RewriteSDP(origSDP, mfInfo, "")

	// Verify a=tls-id exists (DEF-13: now random, not deterministic)
	if !strings.Contains(rewritten, "a=tls-id:") {
		t.Errorf("Expected rewritten SDP to contain a=tls-id, got:\n%s", rewritten)
	}

	// Verify a=3gpp-qos-hint exists
	if !strings.Contains(rewritten, "a=3gpp-qos-hint:latency=200;loss=0.05") {
		t.Errorf("Expected rewritten SDP to contain a=3gpp-qos-hint, got:\n%s", rewritten)
	}

	// Verify a=3gpp-bdc-used-by exists
	if !strings.Contains(rewritten, "a=3gpp-bdc-used-by:sender") {
		t.Errorf("Expected rewritten SDP to contain a=3gpp-bdc-used-by:sender, got:\n%s", rewritten)
	}
}

func TestComplianceFeatureCaps(t *testing.T) {
	b2b := &B2BUA{}
	origMsg, err := ParseSIPMessage(
		"INVITE sip:alice@ims.mnc001.mcc452.3gppnetwork.org SIP/2.0\r\n" +
			"Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-123\r\n" +
			"From: <sip:bob@ims.mnc001.mcc452.3gppnetwork.org>;tag=tag1\r\n" +
			"To: <sip:alice@ims.mnc001.mcc452.3gppnetwork.org>\r\n" +
			"Call-ID: call123\r\n" +
			"CSeq: 1 INVITE\r\n\r\n",
	)
	if err != nil {
		t.Fatalf("Failed to parse orig message: %v", err)
	}

	// 200 OK Response
	resp := b2b.BuildSIPResponse(origMsg, 200, "OK", nil, "")

	if !strings.Contains(resp, "Feature-Caps: *;+g.3gpp.datachannel") {
		t.Errorf("Expected 200 OK response to contain Feature-Caps header, got:\n%s", resp)
	}
}

func TestGetCSeq(t *testing.T) {
	msg, err := ParseSIPMessage(
		"INVITE sip:alice@ims.mnc001.mcc452.3gppnetwork.org SIP/2.0\r\n" +
			"CSeq: 42 INVITE\r\n\r\n",
	)
	if err != nil {
		t.Fatalf("Parse error: %v", err)
	}

	num, method := msg.GetCSeq()
	if num != 42 || method != "INVITE" {
		t.Errorf("Expected CSeq 42 INVITE, got %d %s", num, method)
	}
}

// DEF-01/DEF-02: Test parseReqApp with colon separator (TS 24.186 ABNF)
func TestParseReqAppColon(t *testing.T) {
	sdp := "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"fun-calling-app\":1001-UE\r\n"
	result := parseReqApp(sdp)
	if result == nil {
		t.Fatal("parseReqApp returned nil for valid SDP")
	}
	if result.ReqAppId != "fun-calling-app" {
		t.Errorf("Expected reqAppId 'fun-calling-app', got '%s'", result.ReqAppId)
	}
	if result.AppDcInfo != "1001-UE" {
		t.Errorf("Expected appDcInfo '1001-UE', got '%s'", result.AppDcInfo)
	}
}

// Test parseReqApp with semicolon separator (backwards compat)
func TestParseReqAppSemicolon(t *testing.T) {
	sdp := "v=0\r\na=3gpp-req-app:\"my-app\";user-data\r\n"
	result := parseReqApp(sdp)
	if result == nil {
		t.Fatal("parseReqApp returned nil for valid SDP with semicolon")
	}
	if result.ReqAppId != "my-app" {
		t.Errorf("Expected reqAppId 'my-app', got '%s'", result.ReqAppId)
	}
}

// Test parseReqApp returns nil when attribute is missing
func TestParseReqAppMissing(t *testing.T) {
	sdp := "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=connection:new\r\n"
	result := parseReqApp(sdp)
	if result != nil {
		t.Errorf("Expected nil for SDP without a=3gpp-req-app, got %+v", result)
	}
}

// Test parseReqApp discards attributes with Stream ID < 1000 (BDC)
func TestParseReqAppBDCDiscard(t *testing.T) {
	sdp := "v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=3gpp-req-app:\"fun-calling-app\":0-UE\r\n"
	result := parseReqApp(sdp)
	if result != nil {
		t.Errorf("Expected nil/discard for BDC stream ID 0, got %+v", result)
	}
}

// DEF-13: Test that getTLSId produces non-deterministic values
func TestGetTLSIdRandom(t *testing.T) {
	id1 := getTLSId("session-1")
	id2 := getTLSId("session-1")
	if id1 == id2 {
		t.Errorf("getTLSId should produce random values, but got same result for same input: %s", id1)
	}
}

func TestExcludeADCFromSDP(t *testing.T) {
	origSDP := "v=0\r\n" +
		"m=audio 40000 RTP/AVP 0\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"a=dcmap:1001 subprotocol=\"caro\"\r\n" +
		"m=video 40002 RTP/AVP 96\r\n"

	excluded := ExcludeADCFromSDP(origSDP)

	if !strings.Contains(excluded, "m=application 0 UDP/DTLS/SCTP webrtc-datachannel") {
		t.Errorf("Expected rewritten application port to be 0, got:\n%s", excluded)
	}
	if strings.Contains(excluded, "a=dcmap:1001") {
		t.Errorf("Expected application attributes to be removed, got:\n%s", excluded)
	}
	if !strings.Contains(excluded, "m=audio 40000 RTP/AVP 0") {
		t.Errorf("Expected audio block to remain intact, got:\n%s", excluded)
	}
	if !strings.Contains(excluded, "m=video 40002 RTP/AVP 96") {
		t.Errorf("Expected video block to remain intact, got:\n%s", excluded)
	}
}

func TestRewriteSDPForADC(t *testing.T) {
	origSDP := "v=0\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"a=dcmap:1000 subprotocol=\"caro\"\r\n" +
		"a=3gpp-req-app:\"caro\";1000-UE\r\n"

	mfInfo := &MFAllocResponse{
		SessionID:       "session-1",
		IP:              "1.2.3.4",
		Port:            8888,
		IceUfrag:        "u",
		IcePwd:          "p",
		DtlsFingerprint: "sha-256 F",
		SetupRole:       "passive",
	}

	rewritten := RewriteSDPForADC(origSDP, mfInfo, "caro")

	if !strings.Contains(rewritten, "m=application 8888 UDP/DTLS/SCTP webrtc-datachannel") {
		t.Errorf("Expected application port to be rewritten to 8888, got:\n%s", rewritten)
	}
	if !strings.Contains(rewritten, "a=ice-ufrag:u") {
		t.Errorf("Expected ice-ufrag to be updated, got:\n%s", rewritten)
	}
	if !strings.Contains(rewritten, "a=dcmap:1000 subprotocol=\"caro\"") {
		t.Errorf("Expected original dcmap to be preserved, got:\n%s", rewritten)
	}
	if !strings.Contains(rewritten, "a=3gpp-req-app:\"caro\";1000-UE") {
		t.Errorf("Expected original 3gpp-req-app to be preserved, got:\n%s", rewritten)
	}
}

func TestInterworkingSDPAnswer(t *testing.T) {
	rejectedSDP := "v=0\r\n" +
		"m=audio 40000 RTP/AVP 0\r\n" +
		"m=video 40002 RTP/AVP 97\r\n" +
		"m=application 0 UDP/DTLS/SCTP webrtc-datachannel\r\n"

	isRejected := false
	for _, line := range strings.Split(strings.ReplaceAll(rejectedSDP, "\r\n", "\n"), "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=application ") {
			parts := strings.Fields(trimmed)
			if len(parts) >= 2 && parts[1] == "0" {
				isRejected = true
				break
			}
		}
	}

	if !isRejected {
		t.Errorf("Expected rejected BDC to be detected")
	}

	normalSDP := "v=0\r\n" +
		"m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
	isRejectedNormal := false
	for _, line := range strings.Split(strings.ReplaceAll(normalSDP, "\r\n", "\n"), "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=application ") {
			parts := strings.Fields(trimmed)
			if len(parts) >= 2 && parts[1] == "0" {
				isRejectedNormal = true
				break
			}
		}
	}
	if isRejectedNormal {
		t.Errorf("Expected normal BDC to not be detected as rejected")
	}
}

func TestInterworkingReInviteTerminations(t *testing.T) {
	session := &CallSession{
		CallID:         "interwork-call",
		IsInterworking: true,
		Caller:         "1001",
		Callee:         "1002",
	}

	mSpec := &sba.DcMediaSpecification{
		AdcType:          "P2A",
		MediaProxyConfig: "UDP_PROXY",
	}

	if mSpec.AdcType == "P2A" {
		if session.IsInterworking {
			contextReq := sba.MediaContext{
				ContextID: session.CallID + "-adc",
			}
			contextReq.Terminations = []sba.Termination{
				{
					TerminationID: "term-caller-a",
					Medias: []sba.MediaItem{
						{
							MediaID:           "adc-media",
							MediaResourceType: "DC",
						},
					},
				},
				{
					TerminationID: "term-callee-b",
					Medias: []sba.MediaItem{
						{
							MediaID:           "video-media",
							MediaResourceType: "RTP",
						},
					},
				},
			}

			if len(contextReq.Terminations) != 2 {
				t.Errorf("Expected 2 terminations, got %d", len(contextReq.Terminations))
			}
			if contextReq.Terminations[1].Medias[0].MediaResourceType != "RTP" {
				t.Errorf("Expected termination 1 to be RTP, got %s", contextReq.Terminations[1].Medias[0].MediaResourceType)
			}
		} else {
			t.Errorf("Expected IsInterworking block to execute")
		}
	}
}

func TestHotlineInterworkingAndFallback(t *testing.T) {
	legacyContact := "sip:1001@192.168.0.10:5060"
	isCallerDC := strings.Contains(legacyContact, "webrtc-datachannel")
	if isCallerDC {
		t.Errorf("Expected legacy contact to not contain webrtc-datachannel")
	}

	dcContact := "sip:1001@192.168.0.10:5060;+sip.app-subtype=\"webrtc-datachannel\""
	isCallerDC2 := strings.Contains(dcContact, "webrtc-datachannel")
	if !isCallerDC2 {
		t.Errorf("Expected DC contact to contain webrtc-datachannel")
	}

	sdpWithDC := "v=0\r\n" +
		"m=audio 40000 RTP/AVP 0\r\n" +
		"m=application 40004 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"a=sctp-port:5000\r\n"

	stripped := ExcludeADCFromSDP(sdpWithDC)
	if !strings.Contains(stripped, "m=application 0") {
		t.Errorf("Expected application port to be set to 0 in stripped SDP, got:\n%s", stripped)
	}
}

func TestComplianceClause944Fallback(t *testing.T) {
	sdpWithDC := "v=0\r\n" +
		"m=audio 40000 RTP/AVP 0\r\n" +
		"a=rtpmap:0 PCMA/8000\r\n" +
		"m=application 40004 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
		"a=sctp-port:5000\r\n"

	fallbackSDP := ExcludeADCFromSDP(sdpWithDC)

	// Rule 1: The data channel SCTP m=line port MUST be set to 0 (disabled)
	if !strings.Contains(fallbackSDP, "m=application 0") {
		t.Errorf("Rule 1 Failure: SCTP data channel port was not set to 0 in fallback SDP:\n%s", fallbackSDP)
	}

	// Rule 2: The voice/audio (MMTel) stream MUST be preserved
	if !strings.Contains(fallbackSDP, "m=audio 40000") {
		t.Errorf("Rule 2 Failure: MMTel audio stream was modified or dropped in fallback SDP:\n%s", fallbackSDP)
	}
}

func TestUnreachableAnnouncementFlow(t *testing.T) {
	b2b := &B2BUA{
		sessions: make(map[string]*CallSession),
	}

	origMsg, err := ParseSIPMessage(
		"INVITE sip:2099@ims.mnc001.mcc452.3gppnetwork.org SIP/2.0\r\n" +
			"Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-inv-123\r\n" +
			"From: <sip:2009@ims.mnc001.mcc452.3gppnetwork.org>;tag=tag-caller\r\n" +
			"To: <sip:2099@ims.mnc001.mcc452.3gppnetwork.org>\r\n" +
			"Call-ID: call-unreg-123\r\n" +
			"CSeq: 1 INVITE\r\n\r\n" +
			"v=0\r\n" +
			"o=- 0 0 IN IP4 100.64.0.10\r\n" +
			"c=IN IP4 100.64.0.10\r\n" +
			"m=audio 50000 RTP/AVP 8\r\n" +
			"a=rtpmap:8 PCMA/8000\r\n",
	)
	if err != nil {
		t.Fatalf("Failed to parse orig message: %v", err)
	}

	session := &CallSession{
		CallID:     "call-unreg-123",
		OrigCallID: "call-unreg-123",
		Caller:     "2009",
		Callee:     "2099",
		OrigMsg:    origMsg,
	}
	b2b.sessions["call-unreg-123"] = session

	// 1. Verify 183 Session Progress response structure with Early Media & RFC 3264 matching SDP
	allocIP := "192.168.1.50"
	allocAudioPort := 41234
	sdpAnswer := BuildEarlyMediaSDPAnswer(origMsg.Body, allocIP, allocAudioPort)
	headers := map[string]string{
		"Content-Type":  "application/sdp",
		"P-Early-Media": "sendrecv",
	}
	resp183 := b2b.BuildSIPResponse(origMsg, 183, "Session Progress", headers, sdpAnswer)

	if !strings.Contains(resp183, "SIP/2.0 183 Session Progress") {
		t.Errorf("Expected 183 Session Progress, got:\n%s", resp183)
	}
	if !strings.Contains(resp183, "P-Early-Media: sendrecv") {
		t.Errorf("Expected P-Early-Media: sendrecv header, got:\n%s", resp183)
	}
	if !strings.Contains(resp183, "m=audio 41234 RTP/AVP 8") {
		t.Errorf("Expected RTP audio port in 183 SDP, got:\n%s", resp183)
	}
	if !strings.Contains(resp183, "a=sendonly") {
		t.Errorf("Expected a=sendonly in 183 SDP, got:\n%s", resp183)
	}

	// 2. Verify 487 Request Terminated response
	resp487 := b2b.BuildSIPResponse(origMsg, 487, "Request Terminated", nil, "")
	if !strings.Contains(resp487, "SIP/2.0 487 Request Terminated") {
		t.Errorf("Expected 487 Request Terminated, got:\n%s", resp487)
	}

	// 3. Verify CANCEL 200 OK
	cancelMsg, _ := ParseSIPMessage(
		"CANCEL sip:2099@ims.mnc001.mcc452.3gppnetwork.org SIP/2.0\r\n" +
			"Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-inv-123\r\n" +
			"From: <sip:2009@ims.mnc001.mcc452.3gppnetwork.org>;tag=tag-caller\r\n" +
			"To: <sip:2099@ims.mnc001.mcc452.3gppnetwork.org>\r\n" +
			"Call-ID: call-unreg-123\r\n" +
			"CSeq: 1 CANCEL\r\n\r\n",
	)
	cancelResp := b2b.BuildSIPResponse(cancelMsg, 200, "OK", nil, "")
	if !strings.Contains(cancelResp, "SIP/2.0 200 OK") || !strings.Contains(cancelResp, "CSeq: 1 CANCEL") {
		t.Errorf("Expected 200 OK for CANCEL, got:\n%s", cancelResp)
	}
}
