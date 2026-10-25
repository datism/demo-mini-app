package sba_test

import (
	"encoding/json"
	"testing"

	"github.com/demo/ims-dc-mf/internal/sba"
)

func TestMediaInstructionDataJSON(t *testing.T) {
	// 1. JSON representation containing array-based mediaInstructionSet conforming to TS 29.175
	rawJSON := `{
		"sessionId": "session-12345",
		"mediaInstructionSet": [
			{
				"mediaId": "3",
				"mediaResourceType": "DC",
				"mediaInstruction": "OriginateMedia",
				"dcMediaSpecification": {
					"mediaProxyConfig": "HTTP_PROXY",
					"streams": {
						"10": {
							"streamId": 10,
							"subprotocol": "http",
							"order": true
						}
					},
					"replaceHttpUrls": {
						"10": {
							"streamId": 10,
							"replacementHttpUrl": "http://dcar-service:8082/198.html"
						}
					},
					"mdc1EndpointDcsf": {
						"ip": "192.168.82.45",
						"port": 8080
					}
				}
			}
		]
	}`

	var data sba.MediaInstructionData
	err := json.Unmarshal([]byte(rawJSON), &data)
	if err != nil {
		t.Fatalf("Failed to unmarshal MediaInstructionData: %v", err)
	}

	if data.SessionID != "session-12345" {
		t.Errorf("Expected sessionId 'session-12345', got '%s'", data.SessionID)
	}

	if len(data.MediaInstructionSet) == 0 {
		t.Fatal("Expected mediaInstructionSet to have at least one element")
	}
	inst := data.MediaInstructionSet[0]

	if inst.MediaResourceType != "DC" {
		t.Errorf("Expected mediaResourceType 'DC', got '%s'", inst.MediaResourceType)
	}

	if inst.DcMediaSpecification == nil {
		t.Fatal("Expected DcMediaSpecification to be non-nil")
	}

	if inst.DcMediaSpecification.MediaProxyConfig != "HTTP_PROXY" {
		t.Errorf("Expected mediaProxyConfig 'HTTP_PROXY', got '%s'", inst.DcMediaSpecification.MediaProxyConfig)
	}

	stream, streamExists := inst.DcMediaSpecification.Streams["10"]
	if !streamExists {
		t.Fatal("Expected Stream '10' to exist")
	}

	if stream.StreamID != 10 || stream.Subprotocol != "http" || !stream.Order {
		t.Errorf("Unexpected stream properties: %+v", stream)
	}

	replace, replaceExists := inst.DcMediaSpecification.ReplaceHttpUrls["10"]
	if !replaceExists {
		t.Fatal("Expected ReplaceHttpUrl '10' to exist")
	}

	if replace.ReplacementHttpUrl != "http://dcar-service:8082/198.html" {
		t.Errorf("Unexpected replacement URL: %s", replace.ReplacementHttpUrl)
	}

	endpoint := inst.DcMediaSpecification.Mdc1EndpointDcsf
	if endpoint == nil {
		t.Fatal("Expected mdc1EndpointDcsf to be non-nil")
	}

	if endpoint.IP != "192.168.82.45" || endpoint.Port != 8080 {
		t.Errorf("Unexpected endpoint properties: %+v", endpoint)
	}

	// 2. Validate Marshalling matches standard format
	marshaledBytes, err := json.Marshal(data)
	if err != nil {
		t.Fatalf("Failed to marshal MediaInstructionData: %v", err)
	}

	var remap map[string]interface{}
	err = json.Unmarshal(marshaledBytes, &remap)
	if err != nil {
		t.Fatalf("Failed to unmarshal back to map: %v", err)
	}

	if remap["sessionId"] != "session-12345" {
		t.Errorf("Marshaled json key 'sessionId' mismatch")
	}
}

func TestSessionEventNotificationJSON(t *testing.T) {
	rawJSON := `{
		"sessionId": "session-54321",
		"notificationEvent": {
			"eventType": "SessionCreated"
		},
		"sessionInfo": {
			"callingIdentity": "sip:1001@ims.mnc001.mcc452.3gppnetwork.org",
			"calledIdentity": "sip:1002@ims.mnc001.mcc452.3gppnetwork.org",
			"sessionCase": "Originating"
		},
		"mediaInfoList": {
			"3": {
				"mediaId": "3",
				"mediaType": "DC",
				"dcMediaSpec": {
					"streams": {
						"10": {
							"streamId": 10,
							"subprotocol": "http"
						}
					},
					"bdcUsedBy": "SENDER"
				}
			}
		}
	}`

	var notif sba.SessionEventNotification
	err := json.Unmarshal([]byte(rawJSON), &notif)
	if err != nil {
		t.Fatalf("Failed to unmarshal SessionEventNotification: %v", err)
	}

	if notif.SessionID != "session-54321" {
		t.Errorf("Expected sessionId 'session-54321', got '%s'", notif.SessionID)
	}

	if notif.NotificationEvent.EventType != "SessionCreated" {
		t.Errorf("Expected eventType 'SessionCreated', got '%s'", notif.NotificationEvent.EventType)
	}

	if notif.SessionInfo.CallingIdentity != "sip:1001@ims.mnc001.mcc452.3gppnetwork.org" {
		t.Errorf("Unexpected calling identity: %s", notif.SessionInfo.CallingIdentity)
	}

	mInfo, exists := notif.MediaInfoList["3"]
	if !exists {
		t.Fatal("Expected MediaInfo key '3' to exist")
	}

	if mInfo.MediaType != "DC" {
		t.Errorf("Unexpected mediaType: %s", mInfo.MediaType)
	}

	if mInfo.DcMediaSpec == nil || mInfo.DcMediaSpec.BdcUsedBy != "SENDER" {
		t.Errorf("Unexpected dcMediaSpec: %+v", mInfo.DcMediaSpec)
	}
}

func TestMediaContextJSON(t *testing.T) {
	rawJSON := `{
		"contextId": "ctx-9999",
		"terminations": [
			{
				"terminationId": "term-A",
				"medias": [
					{
						"mediaId": "1",
						"mediaResourceType": "AV",
						"localMbEndpoint": {
							"ip": "192.168.82.44",
							"port": 40002
						}
					}
				]
			}
		]
	}`

	var ctx sba.MediaContext
	err := json.Unmarshal([]byte(rawJSON), &ctx)
	if err != nil {
		t.Fatalf("Failed to unmarshal MediaContext: %v", err)
	}

	if ctx.ContextID != "ctx-9999" {
		t.Errorf("Expected contextId 'ctx-9999', got '%s'", ctx.ContextID)
	}

	if len(ctx.Terminations) != 1 || ctx.Terminations[0].TerminationID != "term-A" {
		t.Fatalf("Unexpected terminations structure: %+v", ctx.Terminations)
	}

	media := ctx.Terminations[0].Medias[0]
	if media.MediaID != "1" || media.MediaResourceType != "AV" || media.LocalMbEndpoint.Port != 40002 {
		t.Errorf("Unexpected media properties: %+v", media)
	}
}

func TestQosHintInfoJSON(t *testing.T) {
	rawJSON := `{
		"loss": 0.005,
		"localLoss": 0.001,
		"latency": 50.0,
		"localLatency": 10.0
	}`

	var qos sba.QosHintInfo
	err := json.Unmarshal([]byte(rawJSON), &qos)
	if err != nil {
		t.Fatalf("Failed to unmarshal QosHintInfo: %v", err)
	}

	if qos.Loss == nil || *qos.Loss != 0.005 {
		t.Errorf("Expected loss 0.005, got %v", qos.Loss)
	}
	if qos.LocalLoss == nil || *qos.LocalLoss != 0.001 {
		t.Errorf("Expected localLoss 0.001, got %v", qos.LocalLoss)
	}
}

func TestAvatarMediaJSON(t *testing.T) {
	rawJSON := `{
		"mediaId": "avatar-1",
		"mediaType": "VIDEO",
		"avatarMedia": {
			"avatarInfo": "rel-19-avatar-stream-active"
		}
	}`

	var mInfo sba.MediaInfo
	err := json.Unmarshal([]byte(rawJSON), &mInfo)
	if err != nil {
		t.Fatalf("Failed to unmarshal MediaInfo with AvatarMedia: %v", err)
	}

	if mInfo.MediaID != "avatar-1" {
		t.Errorf("Expected mediaId avatar-1, got %s", mInfo.MediaID)
	}
	if mInfo.AvatarMedia == nil || mInfo.AvatarMedia.AvatarInfo != "rel-19-avatar-stream-active" {
		t.Errorf("Expected avatarInfo 'rel-19-avatar-stream-active', got %+v", mInfo.AvatarMedia)
	}
}

func TestArMediaJSON(t *testing.T) {
	rawJSON := `{
		"mediaId": "ar-1",
		"mediaResourceType": "AR",
		"arMedia": {
			"mediaProcessingSpec": "rel-19-ar-processing-active"
		}
	}`

	var mItem sba.MediaItem
	err := json.Unmarshal([]byte(rawJSON), &mItem)
	if err != nil {
		t.Fatalf("Failed to unmarshal MediaItem with ArMedia: %v", err)
	}

	if mItem.MediaID != "ar-1" {
		t.Errorf("Expected mediaId ar-1, got %s", mItem.MediaID)
	}
	if mItem.ArMedia == nil || mItem.ArMedia.MediaProcessingSpec != "rel-19-ar-processing-active" {
		t.Errorf("Expected mediaProcessingSpec 'rel-19-ar-processing-active', got %+v", mItem.ArMedia)
	}
}

func TestNonDcMediaJSON(t *testing.T) {
	rawJSON := `{
		"mediaId": "non-dc-1",
		"mediaResourceType": "VIDEO",
		"nonDcMedia": {
			"sdpMLine": "video 5004 RTP/AVPF 96",
			"sdpALines": [
				"a=rtpmap:96 H264/90000",
				"a=fmtp:96 packetization-mode=1"
			]
		}
	}`

	var mItem sba.MediaItem
	err := json.Unmarshal([]byte(rawJSON), &mItem)
	if err != nil {
		t.Fatalf("Failed to unmarshal MediaItem with NonDcMedia: %v", err)
	}

	if mItem.MediaID != "non-dc-1" {
		t.Errorf("Expected mediaId non-dc-1, got %s", mItem.MediaID)
	}
	if mItem.NonDcMedia == nil || mItem.NonDcMedia.SdpMLine != "video 5004 RTP/AVPF 96" {
		t.Errorf("Expected sdpMLine 'video 5004 RTP/AVPF 96', got %+v", mItem.NonDcMedia)
	}
	if len(mItem.NonDcMedia.SdpALines) != 2 || mItem.NonDcMedia.SdpALines[0] != "a=rtpmap:96 H264/90000" {
		t.Errorf("Expected 2 sdpALines, got %+v", mItem.NonDcMedia.SdpALines)
	}
}