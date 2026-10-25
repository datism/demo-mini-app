package config

import (
	"encoding/json"
	"os"
)

type Config struct {
	Domains struct {
		Ims string `json:"ims"`
	} `json:"domains"`
	ImsCore struct {
		Pcscf struct {
			Host string `json:"host"`
			Port int    `json:"port"`
		} `json:"pcscf"`
		Icscf struct {
			Host string `json:"host"`
			Port int    `json:"port"`
		} `json:"icscf"`
		Scscf struct {
			Host string `json:"host"`
			Port int    `json:"port"`
		} `json:"scscf"`
	} `json:"ims_core"`
	Services struct {
		ImsAs struct {
			Host    string `json:"host"`
			Port    int    `json:"port"`
			SipPort int    `json:"sip_port"`
		} `json:"ims_as"`
		Dcsf struct {
			Host string `json:"host"`
			Port int    `json:"port"`
		} `json:"dcsf"`
		Mf struct {
			Host              string `json:"host"`
			Port              int    `json:"port"`
			WebRtcPort        int    `json:"webrtc_port"`
			RingingTimeoutSec int    `json:"ringing_timeout_sec,omitempty"`
		} `json:"mf"`
		Dcar struct {
			Host string `json:"host"`
			Port int    `json:"port"`
		} `json:"dcar"`
		Dcas struct {
			Host   string `json:"host"`
			Port   int    `json:"port"`
			WsPort int    `json:"ws_port"`
		} `json:"dcas"`
		Pcf struct {
			Host string `json:"host"`
			Port int    `json:"port"`
		} `json:"pcf"`
	} `json:"services"`
	Users []struct {
		Impu        string `json:"impu"`
		Username    string `json:"username"`
		Password    string `json:"password"`
		VRBTEnabled bool   `json:"vrbt_enabled,omitempty"`
	} `json:"users"`
	LocalSipPort int
	LocalMediaIP string
}

type FlatConfig struct {
	PcscfIP      string `json:"pcscf_ip"`
	PcscfPort    int    `json:"pcscf_port"`
	LocalSipPort int    `json:"local_sip_port"`
	Username     string `json:"username"`
	Domain       string `json:"domain"`
	Password     string `json:"password"`
	LocalMediaIP string `json:"local_media_ip"`
	AudioCodec   string `json:"audio_codec"`
	VideoCodec   string `json:"video_codec"`
}

func LoadConfig() (*Config, error) {
	// First check if flat config.json exists
	flatPath := "/app/config/config.json"
	flatData, err := os.ReadFile(flatPath)
	if err != nil {
		flatData, err = os.ReadFile("config.json")
	}

	if err == nil {
		var fc FlatConfig
		if err := json.Unmarshal(flatData, &fc); err == nil {
			var cfg Config
			cfg.Domains.Ims = fc.Domain
			cfg.ImsCore.Pcscf.Host = fc.PcscfIP
			if fc.PcscfPort != 0 {
				cfg.ImsCore.Pcscf.Port = fc.PcscfPort
			} else {
				cfg.ImsCore.Pcscf.Port = 5060
			}
			cfg.LocalSipPort = fc.LocalSipPort
			cfg.LocalMediaIP = fc.LocalMediaIP
			cfg.Users = append(cfg.Users, struct {
				Impu        string `json:"impu"`
				Username    string `json:"username"`
				Password    string `json:"password"`
				VRBTEnabled bool   `json:"vrbt_enabled,omitempty"`
			}{
				Impu:     "sip:" + fc.Username + "@" + fc.Domain,
				Username: fc.Username,
				Password: fc.Password,
			})
			return &cfg, nil
		}
	}

	// Fallback to structured sys_config.json
	path := "/app/config/sys_config.json"
	data, err := os.ReadFile(path)
	if err != nil {
		data, err = os.ReadFile("config/sys_config.json")
		if err != nil {
			return nil, err
		}
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	return &cfg, nil
}
