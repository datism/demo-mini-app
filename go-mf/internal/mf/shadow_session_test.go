package mf

import "testing"

func TestNewShadowSession_CopiesOnlyAdcRelevantFields(t *testing.T) {
	parent := &SessionInfo{
		ID: "pa", CallID: "c1", UserID: "u", LocalIP: "10.0.0.1", LocalPort: 40000, BdcLocalPort: 40000,
		ProxyMode: "UDP_PROXY", Mdc2Protocol: "TCP", DcasIP: "9.9.9.9", DcasPort: 7000,
		AudioConn: nil, LinkedSession: &SessionInfo{ID: "pb"},
	}
	sh := newShadowSession(parent, "pa-app", "c1-app", 2)
	if sh.ID != "pa-app" || sh.CallID != "c1-app" || sh.adcBlock() != 2 {
		t.Fatalf("identity wrong: %+v", sh)
	}
	if sh.UserID != "u" || sh.LocalIP != "10.0.0.1" || sh.ProxyMode != "UDP_PROXY" || sh.DcasPort != 7000 {
		t.Fatalf("fields not copied: %+v", sh)
	}
	if sh.BdcPC != nil || sh.AudioConn != nil || sh.LinkedSession != nil {
		t.Fatal("shadow must not share the parent's BDC PC, audio conn or link")
	}
}

func TestAdcBlockDefaultsToHome(t *testing.T) {
	if (&SessionInfo{}).adcBlock() != 1 {
		t.Fatal("zero AdcBlock must mean the Home block (1)")
	}
}

func TestLinkSessions_PairsShadowsNotParents(t *testing.T) {
	m := &MediaFunction{sessions: map[string]*SessionInfo{}, contexts: map[string]*ContextInfo{}}
	pa, pb := &SessionInfo{ID: "pa", CallID: "c1"}, &SessionInfo{ID: "pb", CallID: "c1"}
	sa, sb := newShadowSession(pa, "pa-app", "c1-app", 2), newShadowSession(pb, "pb-app", "c1-app", 2)
	for _, s := range []*SessionInfo{pa, pb, sa, sb} {
		m.sessions[s.ID] = s
	}
	if err := m.LinkSessions("pa", "pb"); err != nil {
		t.Fatal(err)
	}
	if err := m.LinkSessions("pa-app", "pb-app"); err != nil {
		t.Fatal(err)
	}
	if pa.LinkedSession != pb || sa.LinkedSession != sb {
		t.Fatal("each slot must link to its own peer")
	}
}

func TestCleanupShadow_LeavesParentAlone(t *testing.T) {
	m := &MediaFunction{sessions: map[string]*SessionInfo{},
		latchedAudioSessions: map[string]*SessionInfo{}, latchedVideoSessions: map[string]*SessionInfo{}}
	parent := &SessionInfo{ID: "pa", CallID: "c1"}
	sh := newShadowSession(parent, "pa-app", "c1-app", 2)
	m.sessions["pa"], m.sessions["pa-app"] = parent, sh
	m.CleanupSession("pa-app")
	if _, ok := m.sessions["pa"]; !ok {
		t.Fatal("parent session must survive deleting the app shadow")
	}
	if _, ok := m.sessions["pa-app"]; ok {
		t.Fatal("shadow must be removed")
	}
}

func TestAdcParentID_BothSuffixes(t *testing.T) {
	for in, want := range map[string]string{"c1-adc": "c1", "c1-app": "c1", "c1": "c1"} {
		if got := adcParentID(in); got != want {
			t.Fatalf("adcParentID(%q)=%q want %q", in, got, want)
		}
	}
}
