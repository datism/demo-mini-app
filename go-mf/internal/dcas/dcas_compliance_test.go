package dcas

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestImsEENotification(t *testing.T) {
	dcasServer := NewDCASServer("127.0.0.1:0", "127.0.0.1:0", "127.0.0.1:0")
	notifyReqBody := `{"eventId":"DC_INTERWORKING_REQUIRED","sessionId":"session-abc","ue2Information":{"calledIdentity":"sip:1002@ims.mnc001.mcc001.3gppnetwork.org"}}`
	reqNotify, err := http.NewRequest("POST", "/dcas-imsee/v1/notifications", strings.NewReader(notifyReqBody))
	if err != nil {
		t.Fatal(err)
	}

	wNotify := httptest.NewRecorder()
	dcasServer.HandleImsEENotification(wNotify, reqNotify)

	if wNotify.Code != http.StatusNoContent {
		t.Errorf("Expected status 204 NoContent, got %d", wNotify.Code)
	}

	dcasServer.mu.RLock()
	peer, exists := dcasServer.interworkingSessions["session-abc"]
	dcasServer.mu.RUnlock()

	if !exists {
		t.Errorf("Expected session-abc to exist in interworking map")
	}
	if peer != "sip:1002@ims.mnc001.mcc001.3gppnetwork.org" {
		t.Errorf("Expected CalledIdentity to match, got %s", peer)
	}
}
