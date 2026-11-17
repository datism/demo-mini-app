package imsas

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"strings"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/sba"
)

func (b *B2BUA) handle200OKInvite(msg *SIPMessage, remoteAddr *net.UDPAddr) {
	rawCallID := msg.GetHeader("Call-ID")
	callID := rawCallID
	if strings.HasPrefix(callID, "b2b_") {
		callID = strings.TrimPrefix(callID, "b2b_")
	}
	log.Printf("[IMS-AS] Received 200 OK Answer for call %s (rawCallID=%s)", callID, rawCallID)

	session, exists := b.getSession(callID)

	if !exists {
		log.Printf("[IMS-AS] ⚠️ Session %s not found for 200 OK, forwarding transparently", callID)
		b.forwardToSCSCF(msg.Raw)
		return
	}

	if config.IsHotline(session.Callee) {
		log.Printf("[IMS-AS] Ignoring 200 OK response for Hotline call %s", callID)
		return
	}

	cseqVal, _ := msg.GetCSeq()

	// Distinguish which leg the 200 OK came from:
	// - Terminating leg (callee answers): Call-ID has the "b2b_" prefix
	// - Originating leg (caller answers a callee-initiated re-INVITE): Call-ID is the original one
	isCalleeSideAnswer := strings.HasPrefix(rawCallID, "b2b_")
	// Callee-initiated re-INVITE answer: 200 OK comes from the CALLER (OrigCallID, no b2b_ prefix)
	// echoing the callee's own independent CSeq counter, which may be <= InitialCSeq.
	isCalleeInitiatedAnswer := !isCalleeSideAnswer && session.LastCalleeCSeq > 0 && cseqVal == session.LastCalleeCSeq
	if isCalleeInitiatedAnswer {
		log.Printf("[IMS-AS] Detected 200 OK answer for callee-initiated re-INVITE (CSeq %d, LastCalleeCSeq %d)", cseqVal, session.LastCalleeCSeq)
	}

	// B2BUA: Deduplicate 200 OK retransmissions on initial INVITE
	if cseqVal == session.InitialCSeq && session.TermAnswered && !isCalleeInitiatedAnswer {
		log.Printf("[IMS-AS] Ignoring retransmitted 200 OK for call %s (already answered)", callID)
		return
	}
	if cseqVal > session.InitialCSeq || isCalleeInitiatedAnswer {
		log.Printf("[IMS-AS] Detected 200 OK response for re-INVITE (CSeq %d, Initial CSeq %d)", cseqVal, session.InitialCSeq)

		dcsfHost := resolveAndHashHost(b.cfg.Services.Dcsf.Host, session.CallID)
		dcsfURL := fmt.Sprintf("http://%s:%d", dcsfHost, b.cfg.Services.Dcsf.Port)

		// Answer to a slotted (Home + App ADC) Re-INVITE, including one that left
		// the App slot unchanged (the legacy path would forward it raw). A two-ADC
		// offer sent before the Home slot existed went the legacy way, and so does
		// its answer.
		session.mu.Lock()
		slotted := session.SlottedAnswerPending || session.AppAnswerPending
		session.mu.Unlock()
		if slotted {
			b.handleSlottedAnswer(session, msg, dcsfURL, !isCalleeInitiatedAnswer)
			return
		}

		// ADC Release passthrough: operator-menu Re-INVITE 200 OK — just forward back unchanged
		if session.IsADCReleasing {
			log.Printf("[IMS-AS] ADC Release 200 OK received. Forwarding to caller, resetting ADC state.")
			session.IsADCReleasing = false
			session.IsAnchorCtxCreated = false
			session.IsAnchorPatched = false

			// Delete the ADC Media Context from MF (closing the ADC stream 1000)
			mfHost := session.MfHost
			if mfHost == "" {
				mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, session.CallID)
			}
			mfURL := fmt.Sprintf("http://%s:%d", mfHost, b.cfg.Services.Mf.Port)

			// ADC Release doesn't require deleting the MF context anymore because
			// the ADC is anchored on the main BDC context. The Re-INVITE with port 0
			// will gracefully close the ADC stream when forwarded to MF.

			log.Printf("[IMS-AS] Resetting MF context %s ProxyMode to HTTP_PROXY...", session.CallID)
			patches := []sba.PatchDocument{
				{
					Op:    "replace",
					Path:  "/terminations/0/proxyMode",
					Value: json.RawMessage(`"HTTP_PROXY"`),
				},
			}
			if err := b.CallNmfUpdateMediaContext(mfURL, session.CallID, patches); err != nil {
				log.Printf("[IMS-AS] Failed to reset MF ProxyMode: %v", err)
			}

			headers := map[string]string{
				"Content-Type": "application/sdp",
				"To":           msg.GetHeader("To"),
			}
			if contact := msg.GetHeader("Contact"); contact != "" {
				headers["Contact"] = contact
			}
			resp := b.BuildSIPResponse(session.OrigMsg, 200, "OK", headers, msg.Body)
			b.forwardToSCSCF(resp)
			return
		}

		if session.IsAnchorPatched {
			log.Printf("[IMS-AS] ADC Answer already patched. Forwarding 200 OK unchanged.")
			b.forwardToSCSCF(msg.Raw)
			return
		}

		if session.IsP2P {
			// Notify DCSF of MediaChangeSuccessEvent
			successNotification := sba.SessionEventNotification{
				SessionID: session.CallID,
				NotificationEvent: sba.NotificationEvent{
					ReferenceID:    12,
					EventType:      "MediaChangeSuccessEvent",
					EventInitiator: "served IMS subscriber",
				},
				PsDataOff: session.PsDataOff,
				SessionInfo: sba.SessionInfo{
					CallingIdentity: "sip:" + session.Caller + "@" + b.cfg.Domains.Ims,
					CalledIdentity:  "sip:" + session.Callee + "@" + b.cfg.Domains.Ims,
					SessionCase:     "ORIGINATING_IMS_SESSION",
				},
			}
			go func() {
				log.Printf("[IMS-AS] P2P: Notifying DCSF of MediaChangeSuccessEvent")
				_ = b.CallNimsasSessionEventNotify(dcsfURL, successNotification)
			}()

			// Forward 200 OK response back to S-CSCF unchanged
			headers := map[string]string{
				"Content-Type": "application/sdp",
				"To":           msg.GetHeader("To"),
			}
			if contact := msg.GetHeader("Contact"); contact != "" {
				headers["Contact"] = contact
			}
			resp := b.BuildSIPResponse(session.OrigMsg, 200, "OK", headers, msg.Body)
			b.forwardToSCSCF(resp)
			return
		}

		// Anchored flow (P2A or P2A2P)
		mfHost := session.MfHost
		if mfHost == "" {
			mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, session.CallID)
		}
		mfURL := fmt.Sprintf("http://%s:%d", mfHost, b.cfg.Services.Mf.Port)

		isCallerInitiator := !isCalleeInitiatedAnswer

		if session.AdcType == "P2A" {
			// Patch MF context (termination 0) with DC AS details
			dcasHost := resolveAndHashHost(b.cfg.Services.Dcas.Host, session.CallID)
			dcasPort := b.cfg.Services.Dcas.Port

			mdc2Info := sba.Mdc2Info{
				Mdc2Protocol: "TCP",
				RemoteMdc2Endpoint: &sba.MdcEndpoint{
					IP:   dcasHost,
					Port: dcasPort,
				},
			}
			mdc2Bytes, _ := json.Marshal(mdc2Info)
			patches := []sba.PatchDocument{
				{
					Op:    "replace",
					Path:  "/terminations/0/dcMedia/mdc2Info",
					Value: json.RawMessage(mdc2Bytes),
				},
			}
			log.Printf("[IMS-AS] P2A: Patching MF context %s with DC AS endpoint...", session.ContextID)
			if err := b.CallNmfUpdateMediaContext(mfURL, session.ContextID, patches); err != nil {
				log.Printf("[IMS-AS] Nmf Context Update failed for P2A ADC: %v", err)
			}
		} else { // P2A2P
			targetTermPath := "/terminations/1/sdp"
			if !isCallerInitiator {
				targetTermPath = "/terminations/0/sdp"
			}
			patches := []sba.PatchDocument{
				{
					Op:    "replace",
					Path:  targetTermPath,
					Value: json.RawMessage(fmt.Sprintf("%q", msg.Body)),
				},
			}
			adcCtxID := session.CallID
			log.Printf("[IMS-AS] P2A2P: Patching MF context %s at %s with answer SDP...", adcCtxID, targetTermPath)
			if err := b.CallNmfUpdateMediaContext(mfURL, adcCtxID, patches); err != nil {
				log.Printf("[IMS-AS] Nmf Context Update failed for P2A2P ADC: %v", err)
			}
		}

		// Notify DCSF of MediaChangeSuccessEvent
		successNotification := sba.SessionEventNotification{
			SessionID: session.CallID,
			NotificationEvent: sba.NotificationEvent{
				ReferenceID:    12,
				EventType:      "MediaChangeSuccessEvent",
				EventInitiator: "served IMS subscriber",
			},
			PsDataOff: session.PsDataOff,
			SessionInfo: sba.SessionInfo{
				CallingIdentity: "sip:" + session.Caller + "@" + b.cfg.Domains.Ims,
				CalledIdentity:  "sip:" + session.Callee + "@" + b.cfg.Domains.Ims,
				SessionCase:     "ORIGINATING_IMS_SESSION",
			},
		}
		go func() {
			log.Printf("[IMS-AS] Notifying DCSF of MediaChangeSuccessEvent")
			_ = b.CallNimsasSessionEventNotify(dcsfURL, successNotification)
		}()

		// Rewrite SDP Answer pointing to the correct MF info (MF-A or MF-B) and send to S-CSCF
		var rewrittenSDP string
		var targetMfInfo *MFAllocResponse
		if isCallerInitiator {
			targetMfInfo = session.MfInfoA
		} else {
			targetMfInfo = session.MfInfoB
		}
		log.Printf("[IMS-AS] P2A2P handle200OKInvite: isCallerInitiator=%v, session.Caller=%s", isCallerInitiator, session.Caller)
		if targetMfInfo != nil {
			targetMfInfo.AdcSetupRole = "passive"
			log.Printf("[IMS-AS] P2A2P handle200OKInvite: targetMfInfo.AdcPort=%d, targetMfInfo.SessionID=%s, AdcSetupRole=%s", targetMfInfo.AdcPort, targetMfInfo.SessionID, targetMfInfo.AdcSetupRole)
			rewrittenSDP = RewriteSDP(msg.Body, targetMfInfo, session.ActiveAppID)
		} else {
			rewrittenSDP = msg.Body
		}
		log.Printf("[IMS-AS] P2A2P: Rewrote terminating ADC SDP Answer pointing to MF. Result:\n%s", rewrittenSDP)

		b.sendAnswerToInitiator(session, msg, isCallerInitiator, rewrittenSDP)
		session.IsAnchorPatched = true
		return
	}

	mfHost := session.MfHost
	if mfHost == "" {
		mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, callID)
	}
	mfURL := fmt.Sprintf("http://%s:%d", mfHost, b.cfg.Services.Mf.Port)

	// Check if terminating UE rejected the Data Channel (port 0) -> Interworking case
	isRejected := false
	for _, line := range strings.Split(strings.ReplaceAll(msg.Body, "\r\n", "\n"), "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=application ") {
			parts := strings.Fields(trimmed)
			if len(parts) >= 2 && parts[1] == "0" {
				isRejected = true
				break
			}
		}
	}

	var patches []sba.PatchDocument
	if isRejected {
		log.Printf("[IMS-AS] Terminating UE rejected BDC (port 0). MTSI Interworking Active. Releasing terminating-side MF resource...")
		session.IsInterworking = true

		// Patch MF context to remove callee termination (termination 1)
		patches = []sba.PatchDocument{
			{
				Op:   "remove",
				Path: "/terminations/1",
			},
		}

		// Notify DCSF of interworking state
		dcsfHost := resolveAndHashHost(b.cfg.Services.Dcsf.Host, session.CallID)
		dcsfURL := fmt.Sprintf("http://%s:%d", dcsfHost, b.cfg.Services.Dcsf.Port)
		successNotification := sba.SessionEventNotification{
			SessionID:      session.CallID,
			IsInterworking: true,
			NotificationEvent: sba.NotificationEvent{
				ReferenceID: 10,
				EventType:   "SESSION_ESTABLISHMENT_REQUEST",
			},
			PsDataOff: session.PsDataOff,
			SessionInfo: sba.SessionInfo{
				CallingIdentity: "sip:" + session.Caller + "@" + b.cfg.Domains.Ims,
				CalledIdentity:  "sip:" + session.Callee + "@" + b.cfg.Domains.Ims,
				SessionCase:     "ORIGINATING_IMS_SESSION",
			},
		}
		go func() {
			log.Printf("[IMS-AS] Interworking: notifying DCSF of interworking state")
			_ = b.CallNimsasSessionEventNotify(dcsfURL, successNotification)
		}()
	} else {
		// Normal case: Patch MF context (termination 1) with callee answer SDP
		patches = []sba.PatchDocument{
			{
				Op:    "replace",
				Path:  "/terminations/1/sdp",
				Value: json.RawMessage(fmt.Sprintf("%q", msg.Body)),
			},
		}
	}

	var rewrittenSDP string
	if session.ContextID != "" && session.MfInfoA != nil {
		log.Printf("[IMS-AS] Patching MF context %s for answer...", session.ContextID)
		if err := b.CallNmfUpdateMediaContext(mfURL, session.ContextID, patches); err != nil {
			log.Printf("[IMS-AS] Nmf Context Update failed: %v", err)
		}
		rewrittenSDP = RewriteSDP(msg.Body, session.MfInfoA, session.ActiveAppID)
	} else {
		log.Printf("[IMS-AS] Skipping MF context patch and SDP rewrite (fallback mode or context not created)")
		rewrittenSDP = msg.Body
	}

	// Build 200 OK response and send to A (via S-CSCF) with OrigCallID
	headers := map[string]string{
		"Content-Type": "application/sdp",
		"To":           msg.GetHeader("To"),
	}
	if contact := msg.GetHeader("Contact"); contact != "" {
		headers["Contact"] = contact
	}
	resp := b.BuildSIPResponse(session.OrigMsg, 200, "OK", headers, rewrittenSDP)
	b.forwardToSCSCF(resp)
	log.Printf("[IMS-AS] Forwarded rewritten 200 OK Answer (pointing to MF-A) back to S-CSCF")

	// B2BUA: Mark session as answered to prevent retransmission loops
	session.TermAnswered = true

	// B2BUA: Send ACK on terminating leg to stop callee retransmitting 200 OK
	ackHeaders := map[string]string{
		"Call-ID":    session.TermCallID,
		"Session-ID": session.SessionID + ";remote=00000000000000000000000000000000",
	}
	ackReq := b.BuildSIPRequest("ACK", msg.RURI, msg, ackHeaders, "")
	b.forwardToSCSCF(ackReq)
	log.Printf("[IMS-AS] B2BUA: Sent ACK on terminating leg (TermCallID=%s)", session.TermCallID)
}

func (b *B2BUA) handleBye(msg *SIPMessage, remoteAddr *net.UDPAddr) {
	msgCallID := msg.GetHeader("Call-ID")
	log.Printf("[IMS-AS] Received BYE for call %s", msgCallID)

	b.mu.Lock()
	session, ok := b.sessions[msgCallID]
	isCaller := true
	if !ok {
		for _, s := range b.sessions {
			if s.TermCallID == msgCallID {
				session = s
				ok = true
				isCaller = false
				break
			}
		}
	}
	callID := msgCallID
	reInviteCancel := ok && isReInviteCancel(session, msg)
	if ok && session != nil {
		callID = session.OrigCallID
		if !reInviteCancel {
			delete(b.sessions, callID)
		}
	}
	b.mu.Unlock()

	// Reply 200 OK and tell the other side first: releasing the MF/DCSF
	// resources takes HTTP round trips and must not delay the peer's hang-up.
	resp := b.BuildSIPResponse(msg, 200, "OK", nil, "")
	b.sendUDP(resp, remoteAddr)
	if ok && session != nil {
		defer func() { go b.releaseCallResources(session, callID) }()
	}

	// If it's Hotline 198, consume it locally
	if ok && session != nil && config.IsHotline(session.Callee) {
		log.Printf("[IMS-AS] Consumed BYE locally for Hotline 198 (call=%s)", callID)
		return
	}

	// Forward BYE to S-CSCF to notify the other side with ims-as Via
	var headers map[string]string
	if ok && session != nil {
		targetCallID := session.TermCallID
		if !isCaller {
			targetCallID = session.OrigCallID
		}
		headers = map[string]string{"Call-ID": targetCallID}
	}
	byeReq := b.BuildSIPRequest("BYE", msg.RURI, msg, headers, "")
	b.forwardToSCSCF(byeReq)
}

func (b *B2BUA) handleCancel(msg *SIPMessage, remoteAddr *net.UDPAddr) {
	msgCallID := msg.GetHeader("Call-ID")
	log.Printf("[IMS-AS] Received CANCEL for call %s", msgCallID)

	b.mu.Lock()
	session, ok := b.sessions[msgCallID]
	isCaller := true
	if !ok {
		for _, s := range b.sessions {
			if s.TermCallID == msgCallID {
				session = s
				ok = true
				isCaller = false
				break
			}
		}
	}
	callID := msgCallID
	reInviteCancel := ok && isReInviteCancel(session, msg)
	if ok && session != nil {
		callID = session.OrigCallID
		if !reInviteCancel {
			delete(b.sessions, callID)
		}
	}
	b.mu.Unlock()

	// Reply 200 OK to CANCEL immediately
	headers := map[string]string{
		"To": msg.GetHeader("To"),
	}
	if contact := msg.GetHeader("Contact"); contact != "" {
		headers["Contact"] = contact
	}
	resp := b.BuildSIPResponse(msg, 200, "OK", headers, "")
	b.sendUDP(resp, remoteAddr)

	// CANCEL of a pending re-INVITE (e.g. a content-share request withdrawn by
	// its sender): forward it and keep the call, its MF/DCSF state and the App
	// slot as they were. The peer's 487 for the re-INVITE comes back through
	// handleErrorResponse, which rolls back any half-open App slot.
	if reInviteCancel {
		log.Printf("[IMS-AS] CANCEL targets a pending re-INVITE of call %s; keeping the call", callID)
		targetCallID := session.TermCallID
		if !isCaller {
			targetCallID = session.OrigCallID
		}
		cancelReq := b.BuildSIPRequest("CANCEL", msg.RURI, msg, map[string]string{"Call-ID": targetCallID}, "")
		b.forwardToSCSCF(cancelReq)
		return
	}

	if ok && session != nil && session.IsPlayingAnnouncement {
		log.Printf("[IMS-AS] User cancelled call %s during announcement playback", callID)
		// 1. Release MF announcement resources
		go b.callAnnouncementStop(session)

		// 2. Send 487 Request Terminated to INVITE
		if session.OrigMsg != nil {
			resp487 := b.BuildSIPResponse(session.OrigMsg, 487, "Request Terminated", nil, "")
			b.sendUDP(resp487, session.ScscfAddr)
		}
		return
	}

	// Release MF/DCSF resources after the CANCEL is forwarded (see handleBye).
	if ok && session != nil {
		defer func() { go b.releaseCallResources(session, callID) }()
	}

	// Forward CANCEL to S-CSCF (only if not Hotline 198)
	if ok && session != nil && !config.IsHotline(session.Callee) {
		targetCallID := session.TermCallID
		if !isCaller {
			targetCallID = session.OrigCallID
		}
		headers := map[string]string{"Call-ID": targetCallID}
		cancelReq := b.BuildSIPRequest("CANCEL", msg.RURI, msg, headers, "")
		b.forwardToSCSCF(cancelReq)
	}
}

func (b *B2BUA) handleAck(msg *SIPMessage, remoteAddr *net.UDPAddr) {
	msgCallID := msg.GetHeader("Call-ID")
	session, exists := b.getSession(msgCallID)
	isCaller := true
	if !exists {
		origID := strings.TrimPrefix(msgCallID, "b2b_")
		session, exists = b.getSession(origID)
		if exists {
			isCaller = false
		}
	}

	if exists && config.IsHotline(session.Callee) {
		log.Printf("[IMS-AS] Consumed ACK locally for Hotline 198 (call=%s)", msgCallID)
		return
	}

	var headers map[string]string
	if exists && session != nil {
		targetCallID := session.TermCallID
		if !isCaller {
			targetCallID = session.OrigCallID
		}
		headers = map[string]string{"Call-ID": targetCallID}
	}
	ackReq := b.BuildSIPRequest("ACK", msg.RURI, msg, headers, "")
	b.forwardToSCSCF(ackReq)
}

// sendAnswerToInitiator returns a re-INVITE 200 OK with the given SDP to the
// leg that sent the re-INVITE.
func (b *B2BUA) sendAnswerToInitiator(session *CallSession, msg *SIPMessage, isCallerInitiator bool, sdp string) {
	headers := map[string]string{
		"Content-Type": "application/sdp",
		"To":           msg.GetHeader("To"),
	}
	if contact := msg.GetHeader("Contact"); contact != "" {
		headers["Contact"] = contact
	}
	origMsgForResp := session.OrigMsg
	if !isCallerInitiator && session.LastCalleeReInviteMsg != nil {
		origMsgForResp = session.LastCalleeReInviteMsg
	}
	resp := b.BuildSIPResponse(origMsgForResp, 200, "OK", headers, sdp)
	b.forwardToSCSCF(resp)
	log.Printf("[IMS-AS] Forwarded rewritten 200 OK Answer back to S-CSCF")
}

// releaseCallResources frees what a finished call holds outside the IMS-AS:
// the App-slot and main MF contexts, the DCSF event subscription and the dc2
// sessions. Run it after the BYE/CANCEL has been forwarded.
func (b *B2BUA) releaseCallResources(session *CallSession, callID string) {
	mfHost := session.MfHost
	if mfHost == "" {
		mfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, callID)
	}
	b.closeAppSlot(session)
	if session.ContextID != "" {
		log.Printf("[IMS-AS] Releasing MF Context %s", session.ContextID)
		mfURL := fmt.Sprintf("http://%s:%d", mfHost, b.cfg.Services.Mf.Port)
		_ = b.CallNmfDeleteMediaContext(mfURL, session.ContextID)
	}

	dcsfHost := resolveAndHashHost(b.cfg.Services.Dcsf.Host, callID)
	dcsfURL := fmt.Sprintf("http://%s:%d", dcsfHost, b.cfg.Services.Dcsf.Port)
	log.Printf("[IMS-AS] Releasing DCSF Event Subscription for call %s", callID)
	_ = b.CallNimsasSessionEventRelease(dcsfURL, callID)

	if session.SessionIDA != "" {
		go b.callDc2Release(mfHost, session.SessionIDA)
	}
	if session.SessionIDB != "" {
		go b.callDc2Release(mfHost, session.SessionIDB)
	}
}
