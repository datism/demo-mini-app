package sba

import "encoding/json"

// --- Common Data Types (TS 29.571) ---

type IpAddr struct {
	Ipv4Addr string `json:"ipv4Addr,omitempty"`
	Ipv6Addr string `json:"ipv6Addr,omitempty"`
}

type MdcEndpoint struct {
	IP          string `json:"ip"`
	Port        int    `json:"port"`
	SctpPort    int    `json:"sctpPort,omitempty"`
	Fingerprint string `json:"fingerprint,omitempty"`
	TlsID       string `json:"tlsId,omitempty"`
}

type DcStream struct {
	StreamID    int    `json:"streamId"`
	Subprotocol string `json:"subprotocol,omitempty"`
	Order       bool   `json:"order,omitempty"`
	MaxRetry    int    `json:"maxRetry,omitempty"`
	MaxTime     int    `json:"maxTime,omitempty"`
	Priority    int    `json:"priority,omitempty"`
}

// Preserve compatibility alias
type Stream = DcStream

type ReplaceHttpUrl struct {
	StreamID           int    `json:"streamId"`
	ReplacementHttpUrl string `json:"replacementHttpUrl"`
}

// Preserve compatibility alias
type ReplaceUrl = ReplaceHttpUrl

type EndpointIPPort struct {
	IP        string `json:"ip"`
	Port      int    `json:"port"`
	Transport string `json:"transport,omitempty"`
}

// --- DC1 Reference Point (Nimsas Services - TS 29.175) ---

// SessionEventNotification is the schema for notifications sent by IMS-AS to DCSF
type SessionEventNotification struct {
	SessionID         string               `json:"sessionId"`
	NotificationEvent NotificationEvent    `json:"notificationEvent"`
	SessionInfo       SessionInfo          `json:"sessionInfo"`
	AppBindingInfo    *AppBindingInfo      `json:"appBindingInfo,omitempty"`
	DcMediaPara       *DcMediaPara         `json:"dcMediaPara,omitempty"`
	MediaInfoList     map[string]MediaInfo `json:"mediaInfoList,omitempty"`
	IsInterworking    bool                 `json:"isInterworking,omitempty"`
	PsDataOff         string               `json:"psDataOff,omitempty"` // "ACTIVE" or "INACTIVE"
}

// AppBindingInfo carries the parsed a=3gpp-req-app data from UE re-INVITE (TS 24.186 clause 6.2.5)
type AppBindingInfo struct {
	ReqAppId  string `json:"reqAppId"`
	AppDcInfo string `json:"appDcInfo,omitempty"`
	StreamId  int    `json:"streamId,omitempty"`
}

type DcMediaPara struct {
	DcType         string          `json:"dcType"`           // "BDC", "ADC"
	AdcType        string          `json:"adcType,omitempty"` // "P2P", "P2A", "P2A2P"
	AppBindingInfo *AppBindingInfo `json:"appBindingInfo,omitempty"`
}

type NotificationEvent struct {
	ReferenceID    int    `json:"referenceId,omitempty"`
	EventType      string `json:"eventType"`
	EventInitiator string `json:"eventInitiator,omitempty"` // "served IMS subscriber", "remote IMS subscriber"
}

type SessionInfo struct {
	CallingIdentity string `json:"callingIdentity"`
	CalledIdentity  string `json:"calledIdentity"`
	SessionCase     string `json:"sessionCase"`
}

type AvatarMedia struct {
	AvatarInfo string `json:"avatarInfo,omitempty"`
}

type MediaInfo struct {
	MediaID        string       `json:"mediaId"`
	MediaType      string       `json:"mediaType"`
	DcMediaSpec    *DcMediaSpec `json:"dcMediaSpec,omitempty"`
	MediaSuspended bool         `json:"mediaSuspended,omitempty"`
	AvatarMedia    *AvatarMedia `json:"avatarMedia,omitempty"`
}

type DcMediaSpec struct {
	Streams            map[string]DcStream `json:"streams,omitempty"`
	MaxMessageSize     int                 `json:"maxMessageSize,omitempty"`
	ReceivedDcEndpoint *DcEndpoint         `json:"receivedDcEndpoint,omitempty"`
	BdcUsedBy          string              `json:"bdcUsedBy,omitempty"`
}

type QosHintInfo struct {
	Loss         *float64 `json:"loss,omitempty"`
	LocalLoss    *float64 `json:"localLoss,omitempty"`
	Latency      *float64 `json:"latency,omitempty"`
	LocalLatency *float64 `json:"localLatency,omitempty"`
}

type DcEndpoint struct {
	SctpPort      int    `json:"sctpPort"`
	Fingerprint   string `json:"fingerprint"`
	SecuritySetup string `json:"securitySetup"`
	IceUfrag      string `json:"iceUfrag,omitempty"`
	IcePwd        string `json:"icePwd,omitempty"`
}

// MediaInstructionData is the instruction returned from DCSF to IMS-AS (or queried)
type MediaInstructionData struct {
	SessionID           string              `json:"sessionId"`
	MediaInstructionSet []MediaInstructions `json:"mediaInstructionSet"`
}

type MediaInstructions struct {
	MediaID                    string                      `json:"mediaId"`
	MediaResourceType          string                      `json:"mediaResourceType"`
	MediaInstruction           string                      `json:"mediaInstruction,omitempty"`
	DcMediaSpecification       *DcMediaSpecification       `json:"dcMediaSpecification,omitempty"`
	ArMediaSpecification       *ArMediaSpecification       `json:"arMediaSpecification,omitempty"`
	MediaProcessingUrl         string                      `json:"mediaProcessingUrl,omitempty"`
	AudioVideoReNegotiationInd *AudioVideoReNegotiationInd `json:"audioVideoReNegotiationInd,omitempty"`
}

type DcMediaSpecification struct {
	MediaProxyConfig string                    `json:"mediaProxyConfig,omitempty"`
	Streams          map[string]DcStream       `json:"streams,omitempty"`
	ReplaceHttpUrls  map[string]ReplaceHttpUrl `json:"replaceHttpUrls,omitempty"`
	ReplaceHttpUrl   map[string]ReplaceHttpUrl `json:"replaceHttpUrl,omitempty"`
	Mdc1EndpointDcsf *MdcEndpoint              `json:"mdc1EndpointDcsf,omitempty"`
	Mdc1EndpointMf   *MdcEndpoint              `json:"mdc1EndpointMf,omitempty"`
	Mdc2EndpointInfo *Mdc2EndpointInfo         `json:"mdc2EndpointInfo,omitempty"`
	QosHint          string                    `json:"qosHint,omitempty"`
	QosHintInfo      *QosHintInfo              `json:"qosHintInfo,omitempty"`
	AdcType          string                    `json:"adcType,omitempty"` // "P2P", "P2A", "P2A2P"
}

// Mdc2EndpointInfo carries ADC proxy endpoint negotiation per TS 29.176 clause 5.6.2.3
type Mdc2EndpointInfo struct {
	Mdc2Protocol     string       `json:"mdc2Protocol"`              // "TCP", "UDP_DTLS_SCTP", "SCTP_DTLS"
	Mdc2EndpointDcAs *MdcEndpoint `json:"mdc2EndpointDcAs,omitempty"`
	Mdc2EndpointMf   *MdcEndpoint `json:"mdc2EndpointMf,omitempty"`
}

type Mdc2Info struct {
	RemoteMdc2Endpoint *MdcEndpoint `json:"remoteMdc2Endpoint,omitempty"`
	LocalMdc2Endpoint  *MdcEndpoint `json:"localMdc2Endpoint,omitempty"`
	Mdc2Protocol       string       `json:"mdc2Protocol,omitempty"`
}

type ArMediaSpecification struct {
	MediaProcessingSpec string `json:"mediaProcessingSpec"`
}

type AudioVideoReNegotiationInd struct {
	ReNegotiation bool   `json:"reNegotiation"`
	MediaConnSide string `json:"mediaConnSide,omitempty"`
}

// --- DC2 Reference Point (Nmf Services - TS 29.176) ---

// MediaContext is the session allocation resource mapping on the MF
type MediaContext struct {
	ContextID     string        `json:"contextId,omitempty"`
	Terminations  []Termination `json:"terminations,omitempty"`
	IsCoreNetwork bool          `json:"isCoreNetwork,omitempty"`
}

type Termination struct {
	TerminationID string      `json:"terminationId,omitempty"`
	Medias        []MediaItem `json:"medias,omitempty"`
}

type ArMedia struct {
	MediaProcessingSpec string `json:"mediaProcessingSpec,omitempty"`
}

type NonDcMedia struct {
	SdpMLine  string   `json:"sdpMLine"`
	SdpALines []string `json:"sdpALines,omitempty"`
}

type MediaItem struct {
	MediaID           string          `json:"mediaId"`
	MediaResourceType string          `json:"mediaResourceType"`
	LocalMbEndpoint   *EndpointIPPort `json:"localMbEndpoint,omitempty"`
	LocalDcEndpoint   *DcEndpoint     `json:"localDcEndpoint,omitempty"`
	RemoteMbEndpoint  *EndpointIPPort `json:"remoteMbEndpoint,omitempty"`
	RemoteDcEndpoint  *DcEndpoint     `json:"remoteDcEndpoint,omitempty"`
	DcMedia           *DcMedia        `json:"dcMedia,omitempty"`
	ArMedia           *ArMedia        `json:"arMedia,omitempty"`
	AvatarMedia       *AvatarMedia    `json:"avatarMedia,omitempty"`
	NonDcMedia        *NonDcMedia     `json:"nonDcMedia,omitempty"`
	
	// Custom extension to cleanly carry raw SDP Offer/Answer
	SDP               string          `json:"sdp,omitempty"`
}

type DcMedia struct {
	MediaProxyConfig string                    `json:"mediaProxyConfig,omitempty"`
	Streams          map[string]DcStream       `json:"streams,omitempty"`
	Mdc1Info         *Mdc1Info                 `json:"mdc1Info,omitempty"`
	Mdc2EndpointInfo *Mdc2EndpointInfo         `json:"mdc2EndpointInfo,omitempty"`
	Mdc2Info         *Mdc2Info                 `json:"mdc2Info,omitempty"`
	RemoteDcEndpoint *DcEndpoint               `json:"remoteDcEndpoint,omitempty"`
	ReplaceHttpUrls  map[string]ReplaceHttpUrl `json:"replaceHttpUrls,omitempty"`
	ReplaceHttpUrl   map[string]ReplaceHttpUrl `json:"replaceHttpUrl,omitempty"`
}

type Mdc1Info struct {
	RemoteMdc1Endpoint MdcEndpoint `json:"remoteMdc1Endpoint"`
}

// PatchDocument is the schema for RFC 6902 JSON Patch operations
type PatchDocument struct {
	Op    string          `json:"op"`
	Path  string          `json:"path"`
	Value json.RawMessage `json:"value,omitempty"`
}

// ProblemDetails defines the standard 3GPP TS 29.571 error schema
type ProblemDetails struct {
	Type     string `json:"type,omitempty"`
	Title    string `json:"title,omitempty"`
	Status   int    `json:"status,omitempty"`
	Detail   string `json:"detail,omitempty"`
	Instance string `json:"instance,omitempty"`
	Cause    string `json:"cause,omitempty"`
}

