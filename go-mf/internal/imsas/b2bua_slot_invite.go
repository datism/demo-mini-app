package imsas

import (
	"encoding/json"
	"fmt"
	"log"
	"net"
	"strconv"
	"strings"
	"time"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/sba"
)

// appAction is what a slotted Re-INVITE asks of the App slot.
type appAction int

const (
	appNone appAction = iota
	appOpen
	appClose
)

// decideAppAction diffs the App m-line of an offer against the current App slot.
func decideAppAction(app slotState, line *adcLine) appAction {
	if line == nil {
		return appNone
	}
	if !line.Active() {
		if app.CtxCreated {
			return appClose
		}
		return appNone
	}
	if line.Binding == nil {
		return appNone
	}
	if app.CtxCreated && app.AppID == line.Binding.ReqAppId {
		return appNone
	}
	return appOpen
}

// slotStateFor returns a snapshot of the given slot's state.
func (s *CallSession) slotStateFor(slot AdcSlot) slotState {
	s.mu.Lock()
	defer s.mu.Unlock()
	if slot == SlotHome {
		return slotState{AppID: s.ActiveAppID, CtxCreated: s.IsAnchorCtxCreated}
	}
	return s.AppSlot
}

// slotsFor builds the per-slot MF view toward one leg (the caller leg when
// towardCaller) under the session lock. The result aliases the App SlotMF and
// must be treated as read-only.
func (s *CallSession) slotsFor(towardCaller bool) *MFAllocResponse {
	s.mu.Lock()
	defer s.mu.Unlock()
	if towardCaller {
		return s.withSlots(s.MfInfoA, s.AppMfA)
	}
	return s.withSlots(s.MfInfoB, s.AppMfB)
}

func (b *B2BUA) mfURLFor(session *CallSession) string {
	session.mu.Lock()
	if session.MfHost == "" {
		session.MfHost = resolveAndHashHost(b.cfg.Services.Mf.Host, session.CallID)
	}
	host := session.MfHost
	session.mu.Unlock()
	return fmt.Sprintf("http://%s:%d", host, b.cfg.Services.Mf.Port)
}

func (b *B2BUA) forwardReInvite(session *CallSession, msg *SIPMessage, sdp string) {
	targetCallID := session.TermCallID
	if msg.ExtractUsername("From") != session.Caller {
		targetCallID = session.OrigCallID
	}
	headers := map[string]string{"Content-Type": "application/sdp", "Call-ID": targetCallID}
	b.forwardToSCSCF(b.BuildSIPRequest("INVITE", msg.RURI, msg, headers, sdp))
}

// closeAppSlot clears the App slot's state and deletes its MF context
// ("<callID>-app", which also removes the MF shadow sessions). No-op when the
// App slot was never created.
func (b *B2BUA) closeAppSlot(session *CallSession) {
	session.mu.Lock()
	created := session.AppSlot.CtxCreated
	session.AppSlot = slotState{}
	session.AppMfA, session.AppMfB = nil, nil
	session.mu.Unlock()
	if !created {
		return
	}
	log.Printf("[IMS-AS] App slot: deleting MF context %s", appContextID(session.CallID))
	if err := b.CallNmfDeleteMediaContext(b.mfURLFor(session), appContextID(session.CallID)); err != nil {
		log.Printf("[IMS-AS] App slot: delete MF context failed: %v", err)
	}
}

// slotOffer rewrites the offer for the peer: Home line unchanged, App line from appMf.
func (b *B2BUA) slotOffer(session *CallSession, msg *SIPMessage, isCallerInitiator bool, activeApp string) string {
	// The offer goes to the peer: toward the callee when the caller initiated.
	target := session.slotsFor(!isCallerInitiator)
	if target == nil {
		return msg.Body
	}
	return RewriteSDP(msg.Body, target, activeApp)
}

func (b *B2BUA) handleSlottedReInvite(session *CallSession, msg *SIPMessage, remoteAddr *net.UDPAddr, lines []adcLine) {
	isCallerInitiator := msg.ExtractUsername("From") == session.Caller
	session.mu.Lock()
	session.SlottedAnswerPending = true
	session.mu.Unlock()
	// Every slotted answer is returned against the request that started it.
	if isCallerInitiator {
		session.LastCallerReInviteMsg = msg
	} else {
		session.LastCalleeReInviteMsg = msg
	}
	line := adcLineFor(lines, SlotApp)
	app := session.slotStateFor(SlotApp)
	action := decideAppAction(app, line)
	log.Printf("[IMS-AS] Slotted re-INVITE: app action=%d (current app=%q created=%v)", action, app.AppID, app.CtxCreated)

	switch action {
	case appNone:
		b.forwardReInvite(session, msg, b.slotOffer(session, msg, isCallerInitiator, app.AppID))
		return

	case appClose:
		b.closeAppSlot(session)
		session.mu.Lock()
		session.AppAnswerPending = false
		session.mu.Unlock()
		b.forwardReInvite(session, msg, b.slotOffer(session, msg, isCallerInitiator, ""))
		return
	}

	// appOpen: bind the app with the DCSF, then (re)create the App-slot MF context.
	binding := line.Binding
	b.notifyMediaChangeRequest(session, binding, binding.ReqAppId)
	select {
	case instruction := <-session.InstructionChan:
		var mSpec *sba.DcMediaSpecification
		for _, mi := range instruction.MediaInstructionSet {
			if mi.MediaResourceType == "DC" {
				mSpec = mi.DcMediaSpecification
				break
			}
		}
		select {
		case session.ResponseChan <- instruction:
		default:
		}
		if mSpec == nil {
			log.Printf("[IMS-AS] App slot: no DC media instruction; disabling the App line")
			b.rejectAppLine(session, msg, isCallerInitiator)
			return
		}
		if mSpec.AdcType == "P2P" {
			// No MF anchoring for a P2P App line in the slotted model: reject it
			// rather than forward the raw offer (DCSF maps unknown apps to P2P).
			log.Printf("[IMS-AS] App slot: %q is P2P; disabling the App line", binding.ReqAppId)
			b.rejectAppLine(session, msg, isCallerInitiator)
			return
		}

		if session.slotStateFor(SlotApp).CtxCreated {
			b.closeAppSlot(session)
			time.Sleep(500 * time.Millisecond) // let the shared ADC UDP mux release the old ICE ufrag
		}

		dcMedia := &sba.DcMedia{MediaProxyConfig: mSpec.MediaProxyConfig, Streams: mSpec.Streams, ReplaceHttpUrls: mSpec.ReplaceHttpUrls}
		if mSpec.Mdc2EndpointInfo != nil {
			dcMedia.Mdc2Info = &sba.Mdc2Info{Mdc2Protocol: mSpec.Mdc2EndpointInfo.Mdc2Protocol,
				RemoteMdc2Endpoint: mSpec.Mdc2EndpointInfo.Mdc2EndpointDcAs, LocalMdc2Endpoint: mSpec.Mdc2EndpointInfo.Mdc2EndpointMf}
		}
		var sdpA, sdpB string
		if isCallerInitiator {
			sdpA = msg.Body
		} else {
			sdpB = msg.Body
		}
		item := func(sdp string) []sba.MediaItem {
			return []sba.MediaItem{{MediaID: "adc-media", MediaResourceType: "DC", SDP: sdp, DcMedia: dcMedia}}
		}
		req := sba.MediaContext{
			ContextID: appContextID(session.CallID), IsCoreNetwork: true,
			Terminations: []sba.Termination{
				{TerminationID: "term-caller-a", Medias: item(sdpA)},
				{TerminationID: "term-callee-b", Medias: item(sdpB)},
			},
		}
		allocated, err := b.CallNmfCreateMediaContext(b.mfURLFor(session), req)
		if err != nil {
			log.Printf("[IMS-AS] App slot: MF create failed: %v", err)
			b.sendError(msg, 503, "Service Unavailable", remoteAddr)
			return
		}
		appA, appB := slotMFFromContext(allocated)
		session.mu.Lock()
		session.AppMfA, session.AppMfB = appA, appB
		session.AppSlot = slotState{AppID: binding.ReqAppId, CtxCreated: true, AdcType: mSpec.AdcType}
		session.AppAnswerPending = true
		session.mu.Unlock()

		offer := b.slotOffer(session, msg, isCallerInitiator, binding.ReqAppId)
		if mSpec.AdcType == "P2A" {
			offer = ExcludeADCFromSDP(offer) // P2A: the peer gets no App line (port 0)
		}
		b.forwardReInvite(session, msg, offer)

	case <-time.After(5 * time.Second):
		log.Printf("[IMS-AS] App slot: no media instruction in 5 s; disabling the App line [TS 24.186 9.4.4]")
		b.rejectAppLine(session, msg, isCallerInitiator)
	}
}

// rejectAppLine closes the App slot and forwards the offer with the App line at
// port 0. Every other line is still rewritten to the MF, so the initiator's own
// addresses never reach the peer.
func (b *B2BUA) rejectAppLine(session *CallSession, msg *SIPMessage, isCallerInitiator bool) {
	b.closeAppSlot(session)
	b.forwardReInvite(session, msg, rejectedAppOffer(msg.Body, session.slotsFor(!isCallerInitiator)))
}

// rejectedAppOffer sets the App line to port 0 and, when the peer's MF view is
// known, rewrites the rest of the offer to the MF.
func rejectedAppOffer(sdp string, target *MFAllocResponse) string {
	body := setAppLinePort(sdp, 0)
	if target != nil {
		body = RewriteSDP(body, target, "")
	}
	return body
}

// answerSlots returns the per-slot MF view for an answer: every slot passive
// toward the answering UE. The App SlotMF is copied, never mutated (withSlots
// aliases the session's pointer).
func answerSlots(view *MFAllocResponse) *MFAllocResponse {
	if view == nil {
		return nil
	}
	for k, sm := range view.Slots {
		c := *sm
		c.Setup = "passive" // MF answers: passive toward the answering UE
		view.Slots[k] = &c
	}
	return view
}

// handleSlottedAnswer finishes a Re-INVITE that changed the App slot: patch the
// App-slot MF context with the answer, notify the DCSF, and return the answer to
// the initiator with the App line rewritten to the MF.
func (b *B2BUA) handleSlottedAnswer(session *CallSession, msg *SIPMessage, dcsfURL string, isCallerInitiator bool) {
	session.mu.Lock()
	pending := session.AppAnswerPending
	app := session.AppSlot
	session.AppAnswerPending = false
	session.SlottedAnswerPending = false
	session.mu.Unlock()

	if pending && app.CtxCreated && app.AdcType != "P2A" && !appLineActive(msg.Body) {
		// The peer refused the new App line: drop the App context and give the
		// initiator port 0 for it. No MF patch, no success event.
		log.Printf("[IMS-AS] App slot: peer answered the App line with port 0; closing %s", appContextID(session.CallID))
		b.closeAppSlot(session)
		pending, app = false, slotState{}
	}

	if pending && app.CtxCreated && app.AdcType != "P2A" {
		path := "/terminations/1/sdp"
		if !isCallerInitiator {
			path = "/terminations/0/sdp"
		}
		patches := []sba.PatchDocument{{Op: "replace", Path: path, Value: json.RawMessage(fmt.Sprintf("%q", msg.Body))}}
		if err := b.CallNmfUpdateMediaContext(b.mfURLFor(session), appContextID(session.CallID), patches); err != nil {
			log.Printf("[IMS-AS] App slot: answer patch failed: %v", err)
		}
	}
	if pending {
		note := sba.SessionEventNotification{
			SessionID:         session.CallID,
			NotificationEvent: sba.NotificationEvent{ReferenceID: 12, EventType: "MediaChangeSuccessEvent", EventInitiator: "served IMS subscriber"},
			PsDataOff:         session.PsDataOff,
			SessionInfo: sba.SessionInfo{
				CallingIdentity: "sip:" + session.Caller + "@" + b.cfg.Domains.Ims,
				CalledIdentity:  "sip:" + session.Callee + "@" + b.cfg.Domains.Ims,
				SessionCase:     "ORIGINATING_IMS_SESSION",
			},
		}
		go func() { _ = b.CallNimsasSessionEventNotify(dcsfURL, note) }()
	}

	// The answer returns to the initiator: toward the caller when the caller initiated.
	body := msg.Body
	if app.CtxCreated && app.AdcType == "P2A" {
		// P2A: the peer was offered the App line at port 0; the MF terminates it,
		// so the initiator must still get an active App line.
		body = setAppLinePort(body, 9)
	}
	out := msg.Body
	if view := answerSlots(session.slotsFor(isCallerInitiator)); view != nil {
		out = RewriteSDP(body, view, app.AppID)
	}
	b.sendAnswerToInitiator(session, msg, isCallerInitiator, out)
}

// appLineActive reports whether the SDP has an App ADC line with a non-zero port.
func appLineActive(sdp string) bool {
	l := adcLineFor(parseAdcLines(sdp), SlotApp)
	return l != nil && l.Active()
}

// useSlottedPath reports whether a re-INVITE goes to the slotted handler: two
// ADC lines, an existing Home context, and not a hotline call. An App line
// offered before the Home slot exists takes the legacy path.
func useSlottedPath(session *CallSession, lines []adcLine) bool {
	return len(lines) == 2 && session.IsAnchorCtxCreated && !config.IsHotline(session.Callee)
}

// setAppLinePort sets the port of the App ADC m-line (the third m=application)
// and leaves every other line untouched.
func setAppLinePort(sdp string, port int) string {
	lines := strings.Split(sdp, "\n")
	n := 0
	for i, l := range lines {
		if !strings.HasPrefix(strings.TrimSpace(l), "m=application ") {
			continue
		}
		if n++; n != 3 {
			continue
		}
		f := strings.Fields(l)
		if len(f) >= 2 {
			f[1] = strconv.Itoa(port)
			out := strings.Join(f, " ")
			if strings.HasSuffix(l, "\r") {
				out += "\r"
			}
			lines[i] = out
		}
		break
	}
	return strings.Join(lines, "\n")
}

// isReInviteFailure reports whether a final non-2xx response answers an
// in-dialog re-INVITE of an answered call rather than the initial INVITE.
// Re-INVITE detection mirrors handle200OKInvite.
func isReInviteFailure(session *CallSession, msg *SIPMessage) bool {
	if session == nil || !msg.IsResponse || msg.StatusCode < 300 {
		return false
	}
	return isReInviteTransaction(session, msg, "INVITE")
}

// isReInviteCancel reports whether a CANCEL targets a pending in-dialog
// re-INVITE of an answered call. Such a CANCEL withdraws only the re-INVITE
// (the peer answers it with 487); the call itself stays up.
func isReInviteCancel(session *CallSession, msg *SIPMessage) bool {
	if session == nil || msg.IsResponse {
		return false
	}
	return isReInviteTransaction(session, msg, "CANCEL")
}

// isReInviteTransaction tells an in-dialog re-INVITE transaction (or its CANCEL)
// from the initial INVITE by its CSeq number, for both caller- and
// callee-initiated re-INVITEs.
func isReInviteTransaction(session *CallSession, msg *SIPMessage, method string) bool {
	if !session.TermAnswered {
		return false
	}
	cseq, m := msg.GetCSeq()
	if m != method {
		return false
	}
	calleeInitiated := !strings.HasPrefix(msg.GetHeader("Call-ID"), "b2b_") &&
		session.LastCalleeCSeq > 0 && cseq == session.LastCalleeCSeq
	return cseq > session.InitialCSeq || calleeInitiated
}

// rollbackAppSlotChange undoes the App-slot change of a failed re-INVITE. Only
// an App open/replace leaves AppAnswerPending set (a close already deleted the
// context), so only then is the '<id>-app' context released. Home/BDC are untouched.
func (b *B2BUA) rollbackAppSlotChange(session *CallSession) {
	session.mu.Lock()
	pending := session.AppAnswerPending
	session.AppAnswerPending = false
	session.SlottedAnswerPending = false
	session.mu.Unlock()
	if pending {
		b.closeAppSlot(session)
	}
}
