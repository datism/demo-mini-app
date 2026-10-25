package imsas

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"

	"github.com/demo/ims-dc-mf/internal/sba"
)

type DCSFNotifyRequest struct {
	CallID string `json:"call_id"`
	From   string `json:"from"`
	To     string `json:"to"`
}

type MFCreateRequest struct {
	CallID    string `json:"call_id"`
	UserID    string `json:"user_id"`
	IsHotline bool   `json:"is_hotline"`
	SDPOffer  string `json:"sdp_offer,omitempty"`
}

type MFSetAnswerRequest struct {
	SessionID string `json:"session_id"`
	SDPAnswer string `json:"sdp_answer"`
}

type MFLinkRequest struct {
	CallID   string `json:"call_id"`
	SessionA string `json:"session_a"`
	SessionB string `json:"session_b"`
}

func (b *B2BUA) CallDCSFNotify(dcsfURL string, callID, from, to string) error {
	reqBody, _ := json.Marshal(DCSFNotifyRequest{
		CallID: callID,
		From:   from,
		To:     to,
	})
	resp, err := b.httpClient.Post(fmt.Sprintf("%s/dc1/notify", dcsfURL), "application/json", bytes.NewBuffer(reqBody))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("DCSF notify failed: status %d", resp.StatusCode)
	}
	return nil
}

func (b *B2BUA) CallMFCreate(mfURL string, callID, userID string, isHotline bool, sdpOffer string) (*MFAllocResponse, error) {
	reqBody, _ := json.Marshal(MFCreateRequest{
		CallID:    callID,
		UserID:    userID,
		IsHotline: isHotline,
		SDPOffer:  sdpOffer,
	})
	resp, err := b.httpClient.Post(fmt.Sprintf("%s/dc2/create", mfURL), "application/json", bytes.NewBuffer(reqBody))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("MF create failed: status %d", resp.StatusCode)
	}
	var mfResp MFAllocResponse
	if err := json.NewDecoder(resp.Body).Decode(&mfResp); err != nil {
		return nil, err
	}
	return &mfResp, nil
}

func (b *B2BUA) CallMFSetAnswer(mfURL string, sessionID, sdpAnswer string) error {
	reqBody, _ := json.Marshal(MFSetAnswerRequest{
		SessionID: sessionID,
		SDPAnswer: sdpAnswer,
	})
	resp, err := b.httpClient.Post(fmt.Sprintf("%s/dc2/set_remote_answer", mfURL), "application/json", bytes.NewBuffer(reqBody))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("MF set answer failed: status %d", resp.StatusCode)
	}
	return nil
}

func (b *B2BUA) CallMFLink(mfURL string, callID, sessionA, sessionB string) error {
	reqBody, _ := json.Marshal(MFLinkRequest{
		CallID:   callID,
		SessionA: sessionA,
		SessionB: sessionB,
	})
	resp, err := b.httpClient.Post(fmt.Sprintf("%s/dc2/link_sessions", mfURL), "application/json", bytes.NewBuffer(reqBody))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("MF link sessions failed: status %d", resp.StatusCode)
	}
	return nil
}

func (b *B2BUA) CallMFRelease(mfURL string, sessionID string) error {
	reqBody, _ := json.Marshal(map[string]string{
		"session_id": sessionID,
	})
	resp, err := b.httpClient.Post(fmt.Sprintf("%s/dc2/release", mfURL), "application/json", bytes.NewBuffer(reqBody))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("MF release failed: status %d", resp.StatusCode)
	}
	return nil
}

// --- 3GPP SBA REST Clients ---

func (b *B2BUA) CallNimsasSessionEventNotify(dcsfURL string, notification sba.SessionEventNotification) error {
	reqBody, _ := json.Marshal(notification)
	url := fmt.Sprintf("%s/nimsas-sec/v1/session-event-subscriptions", dcsfURL)
	log.Printf("[IMS-AS] CallNimsasSessionEventNotify: Sending POST to %s", url)
	resp, err := b.httpClient.Post(url, "application/json", bytes.NewBuffer(reqBody))
	if err != nil {
		log.Printf("[IMS-AS] CallNimsasSessionEventNotify: POST failed: %v", err)
		return err
	}
	defer resp.Body.Close()
	log.Printf("[IMS-AS] CallNimsasSessionEventNotify: POST returned status %d", resp.StatusCode)
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("Nimsas notify failed: status %d", resp.StatusCode)
	}
	return nil
}

func (b *B2BUA) CallNimsasSessionEventRelease(dcsfURL string, sessionID string) error {
	req, err := http.NewRequest(http.MethodDelete, fmt.Sprintf("%s/nimsas-sec/v1/session-event-subscriptions/%s", dcsfURL, sessionID), nil)
	if err != nil {
		return err
	}
	resp, err := b.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("Nimsas release failed: status %d", resp.StatusCode)
	}
	return nil
}

func (b *B2BUA) CallNmfCreateMediaContext(mfURL string, ctx sba.MediaContext) (*sba.MediaContext, error) {
	reqBody, _ := json.Marshal(ctx)
	url := fmt.Sprintf("%s/nmf-mrm/v1/contexts", mfURL)
	log.Printf("[IMS-AS] CallNmfCreateMediaContext: Sending POST to %s", url)
	resp, err := b.httpClient.Post(url, "application/json", bytes.NewBuffer(reqBody))
	if err != nil {
		log.Printf("[IMS-AS] CallNmfCreateMediaContext: POST failed: %v", err)
		return nil, err
	}
	defer resp.Body.Close()
	log.Printf("[IMS-AS] CallNmfCreateMediaContext: POST returned status %d", resp.StatusCode)
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Nmf contexts create failed: status %d", resp.StatusCode)
	}
	var res sba.MediaContext
	if err := json.NewDecoder(resp.Body).Decode(&res); err != nil {
		log.Printf("[IMS-AS] CallNmfCreateMediaContext: JSON decode failed: %v", err)
		return nil, err
	}
	return &res, nil
}

func (b *B2BUA) CallNmfUpdateMediaContext(mfURL string, contextID string, patches []sba.PatchDocument) error {
	reqBody, _ := json.Marshal(patches)
	req, err := http.NewRequest(http.MethodPatch, fmt.Sprintf("%s/nmf-mrm/v1/contexts/%s", mfURL, contextID), bytes.NewBuffer(reqBody))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json-patch+json")
	resp, err := b.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("Nmf context update failed: status %d", resp.StatusCode)
	}
	return nil
}

func (b *B2BUA) CallNmfDeleteMediaContext(mfURL string, contextID string) error {
	req, err := http.NewRequest(http.MethodDelete, fmt.Sprintf("%s/nmf-mrm/v1/contexts/%s", mfURL, contextID), nil)
	if err != nil {
		return err
	}
	resp, err := b.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		return fmt.Errorf("Nmf context release failed: status %d", resp.StatusCode)
	}
	return nil
}
