package imsas

import (
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// A slow MF/DCSF must not hold back the BYE: the peer is told first, the
// media context and subscription are released afterwards.
func TestByeForwardedBeforeSlowRelease(t *testing.T) {
	r := newFailureRig(t)

	var mu sync.Mutex
	var deletes []string
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Method == http.MethodDelete {
			time.Sleep(time.Second)
			mu.Lock()
			deletes = append(deletes, req.URL.Path)
			mu.Unlock()
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(slow.Close)
	u, _ := url.Parse(slow.URL)
	port, _ := strconv.Atoi(u.Port())
	r.b.cfg.Services.Mf.Port, r.b.cfg.Services.Dcsf.Port = port, port
	r.b.httpClient = slow.Client()

	ue, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ue.Close() })

	s := &CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", Caller: "alice", Callee: "bob", ContextID: "ctx1"}
	r.addSession(s)
	msg, err := ParseSIPMessage("BYE sip:bob@ims SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK1\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>;tag=b\r\nCall-ID: c1\r\nCSeq: 3 BYE\r\nContent-Length: 0\r\n\r\n")
	if err != nil {
		t.Fatal(err)
	}

	start := time.Now()
	r.b.handleBye(msg, ue.LocalAddr().(*net.UDPAddr))
	if d := time.Since(start); d > 300*time.Millisecond {
		t.Fatalf("handleBye blocked %v on the release", d)
	}
	if got := r.received(t); !strings.HasPrefix(got, "BYE ") || !strings.Contains(got, "b2b_c1") {
		t.Fatalf("want BYE to the callee leg at the S-CSCF, got %q", got)
	}

	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := len(deletes)
		got := strings.Join(deletes, " ")
		mu.Unlock()
		if n >= 2 {
			if !strings.Contains(got, "ctx1") {
				t.Fatalf("MF context not released: %s", got)
			}
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("MF context / DCSF subscription not released after the BYE")
}

// A declined call (603) is relayed to the caller before the slow MF/DCSF release, and the call is
// forgotten at once, so the caller's late CANCEL cannot bring it back.
func TestDeclineForwardedBeforeSlowRelease(t *testing.T) {
	r := newFailureRig(t)

	var mu sync.Mutex
	var deletes []string
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		if req.Method == http.MethodDelete {
			time.Sleep(time.Second)
			mu.Lock()
			deletes = append(deletes, req.URL.Path)
			mu.Unlock()
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(slow.Close)
	u, _ := url.Parse(slow.URL)
	port, _ := strconv.Atoi(u.Port())
	r.b.cfg.Services.Mf.Port, r.b.cfg.Services.Dcsf.Port = port, port
	r.b.httpClient = slow.Client()

	r.addSession(&CallSession{CallID: "c1", OrigCallID: "c1", TermCallID: "b2b_c1", Caller: "alice", Callee: "bob", ContextID: "ctx1"})
	raw := "SIP/2.0 603 Decline\r\nVia: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK1\r\n" +
		"From: <sip:alice@ims>;tag=a\r\nTo: <sip:bob@ims>;tag=b\r\nCall-ID: c1\r\nCSeq: 1 INVITE\r\nContent-Length: 0\r\n\r\n"
	msg, err := ParseSIPMessage(raw)
	if err != nil {
		t.Fatal(err)
	}

	start := time.Now()
	r.b.handleErrorResponse(msg, "c1", raw)
	if d := time.Since(start); d > 300*time.Millisecond {
		t.Fatalf("603 held back %v by the release", d)
	}
	if got := r.received(t); !strings.HasPrefix(got, "SIP/2.0 603") {
		t.Fatalf("want the 603 relayed to the S-CSCF, got %q", got)
	}
	if _, ok := r.b.getSession("c1"); ok {
		t.Fatalf("declined call still known to the IMS-AS")
	}

	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		got := strings.Join(deletes, " ")
		mu.Unlock()
		if strings.Contains(got, "ctx1") {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("MF context not released after the 603")
}
