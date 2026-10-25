package dcsf

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestImsEESubscription(t *testing.T) {
	dcsfServer := NewDCSFServer("127.0.0.1:0", nil)
	
	subReqBody := `{"eventId":"DC_INTERWORKING_REQUIRED","dcInterworkingInfo":{"appIds":["caro"]},"notificationUri":"http://dcas/notifications"}`
	req, err := http.NewRequest("POST", "/nimsas-imsee/v1/subscriptions", strings.NewReader(subReqBody))
	if err != nil {
		t.Fatal(err)
	}

	w := httptest.NewRecorder()
	dcsfServer.HandleImsEESubscribe(w, req)

	if w.Code != http.StatusCreated {
		t.Errorf("Expected status 201 Created, got %d", w.Code)
	}

	if len(dcsfServer.subscriptions) != 1 {
		t.Errorf("Expected 1 subscription, got %d", len(dcsfServer.subscriptions))
	}
	if dcsfServer.subscriptions[0].EventId != "DC_INTERWORKING_REQUIRED" {
		t.Errorf("Expected eventId DC_INTERWORKING_REQUIRED, got %s", dcsfServer.subscriptions[0].EventId)
	}
}
