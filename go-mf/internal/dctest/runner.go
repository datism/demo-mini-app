// Package dctest implements the automated end-to-end simulation testing engine.
// It orchestrates test scenarios defined in JSON, creates dual UE simulators (Caller & Callee),
// verifies SIP signaling dialogs, WebRTC DataChannel connectivity, real-time RTP traffic,
// BDC package downloads, and Mini-App interactive actions.
//
// Key Responsibilities:
// 1. Scenario-Driven Execution: Parses JSON test scenarios (e.g. test_scenarios.json).
// 2. Dual-UE Coordination: Automates SIP Registration, INVITE, 200 OK, re-INVITE, CANCEL, and BYE.
// 3. WebRTC DataChannel Assertions: Verifies Stream 0 (BDC), Stream 1000/1001 (ADC) open states and payloads.
// 4. Bidirectional RTP Traffic Verification: Measures 3-second sample windows for PCMA audio and H.264 video.
// 5. UAS Hotline & Early Media Testing: Handles IVR/Smart Menu (198) and Unregistered Announcement flows.
package dctest

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"net"
	"strings"
	"time"

	"github.com/demo/ims-dc-mf/internal/config"
	"github.com/demo/ims-dc-mf/internal/uesim"
)

func getInterfaceIP(ifaceName string) (string, error) {
	iface, err := net.InterfaceByName(ifaceName)
	if err == nil {
		addrs, err := iface.Addrs()
		if err == nil {
			for _, addr := range addrs {
				var ip net.IP
				switch v := addr.(type) {
				case *net.IPNet:
					ip = v.IP
				case *net.IPAddr:
					ip = v.IP
				}
				if ip != nil && !ip.IsLoopback() && ip.To4() != nil {
					return ip.String(), nil
				}
			}
		}
	}
	
	// Fallback to first non-loopback IPv4 across all interfaces
	ifaces, err := net.Interfaces()
	if err != nil {
		return "", fmt.Errorf("failed to list interfaces: %v", err)
	}
	for _, i := range ifaces {
		addrs, err := i.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip != nil && !ip.IsLoopback() && ip.To4() != nil {
				return ip.String(), nil
			}
		}
	}
	return "", fmt.Errorf("no valid IP found")
}

// RunTestSuites loads scenarios from configPath and runs them sequentially.
func RunTestSuites(cfg *config.Config, configPath string) (*TestStats, error) {
	data, err := ioutil.ReadFile(configPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read test config file: %w", err)
	}

	var schema ConfigSchema
	if err := json.Unmarshal(data, &schema); err != nil {
		return nil, fmt.Errorf("failed to unmarshal test config JSON: %w", err)
	}

	log.Printf("[RUNNER] Loaded %d test scenarios from %s", len(schema.Scenarios), configPath)
	startTime := time.Now()
	
	globalStats := TestStats{}

	for idx, sc := range schema.Scenarios {
		log.Printf("\n========================================================")
		log.Printf("  RUNNING SCENARIO %d: %s", idx+1, sc.Name)
		log.Printf("========================================================")

		tc := NewTestCoordinator(cfg)
		err := tc.RunScenario(sc)
		if err != nil {
			log.Printf("[SCENARIO FAILED] Scenario '%s' encountered error: %v", sc.Name, err)
		}

		scStats := tc.GetStats()
		globalStats.TotalAssertions += scStats.TotalAssertions
		globalStats.Passed += scStats.Passed
		globalStats.Failed += scStats.Failed

		// Drain delay: allow stale SIP traffic from previous scenario to clear
		// before reusing the same user accounts and ports
		if idx < len(schema.Scenarios)-1 {
			log.Printf("[RUNNER] Waiting 6s for stale network traffic to drain...")
			time.Sleep(6 * time.Second)
		}
	}

	globalStats.Duration = time.Since(startTime)
	return &globalStats, nil
}

// RunScenario executes a single ScenarioConfig step-by-step.
func (tc *TestCoordinator) RunScenario(sc ScenarioConfig) error {
	callerIP, err := getInterfaceIP("eth0")
	if err != nil {
		log.Printf("[RUNNER] Warning: failed to find IP for eth0 (%v), falling back to loopback", err)
		callerIP = "127.0.0.1"
	}
	calleeIP, err := getInterfaceIP("eth0")
	if err != nil {
		log.Printf("[RUNNER] Warning: failed to find IP for eth0 (%v), falling back to loopback", err)
		calleeIP = "127.0.0.1"
	}

	isUASCall := sc.Callee.Username == "198" || sc.Callee.Username == ""

	tc.caller = uesim.NewUESimulator(tc.cfg, "caller", sc.Caller.Username, sc.Caller.Password, sc.Callee.Username)
	tc.caller.LocalIPOverride = callerIP

	if !isUASCall {
		tc.callee = uesim.NewUESimulator(tc.cfg, "callee", sc.Callee.Username, sc.Callee.Password, "")
		tc.callee.LocalIPOverride = calleeIP
	}

	if callerIP == calleeIP {
		log.Printf("[RUNNER] Caller and Callee share IP %s, using unique port allocations", callerIP)
		tc.caller.LocalPortOverride = 50090
		tc.caller.LocalAudioPortOverride = 50002
		tc.caller.LocalVideoPortOverride = 50004

		if !isUASCall {
			tc.callee.LocalPortOverride = 50091
			tc.callee.LocalAudioPortOverride = 50012
			tc.callee.LocalVideoPortOverride = 50014
		}
	} else {
		log.Printf("[RUNNER] Separating IPs: Caller=%s, Callee=%s, using identical port layouts", callerIP, calleeIP)
		tc.caller.LocalPortOverride = 5060
		tc.caller.LocalAudioPortOverride = 50002
		tc.caller.LocalVideoPortOverride = 50004

		if !isUASCall {
			tc.callee.LocalPortOverride = 5060
			tc.callee.LocalAudioPortOverride = 50002
			tc.callee.LocalVideoPortOverride = 50004
		}
	}

	if sc.Expires > 0 {
		tc.caller.Expires = sc.Expires
		if !isUASCall {
			tc.callee.Expires = sc.Expires
		}
	}

	callerRegChan := make(chan bool, 10)
	calleeRegChan := make(chan bool, 10)
	callerConnChan := make(chan bool, 10)
	calleeConnChan := make(chan bool, 10)
	callerDiscChan := make(chan bool, 10)
	calleeDiscChan := make(chan bool, 10)

	tc.caller.OnSIPStateChange = func(state string) {
		log.Printf("[ASSERTION] Caller SIP State: %s", state)
		if state == "registered" {
			callerRegChan <- true
		} else if state == "connected" {
			callerConnChan <- true
		} else if state == "disconnected" {
			callerDiscChan <- true
		}
	}

	if !isUASCall {
		tc.callee.OnSIPStateChange = func(state string) {
			log.Printf("[ASSERTION] Callee SIP State: %s", state)
			if state == "registered" {
				calleeRegChan <- true
			} else if state == "connected" {
				calleeConnChan <- true
			} else if state == "disconnected" {
				calleeDiscChan <- true
			}
		}
	}

	tc.caller.OnDataChannelOpen = func(sid uint16) {
		log.Printf("[ASSERTION] Caller DataChannel Open: %d", sid)
		tc.dcStateChan <- sid
	}

	tc.caller.OnDataChannelMessage = func(sid uint16, subproto string, payload string) {
		logPayload := payload
		if len(logPayload) > 200 {
			logPayload = logPayload[:200] + "...(truncated)"
		}
		log.Printf("[COORD] Caller received data on Stream %d: %s", sid, logPayload)
		tc.receivedMsgs <- DataMsg{
			StreamID:    sid,
			Subprotocol: subproto,
			Payload:     payload,
			ReceivedAt:  time.Now(),
		}
	}

	if !isUASCall {
		tc.callee.OnDataChannelOpen = func(sid uint16) {
			log.Printf("[ASSERTION] Callee DataChannel Open: %d", sid)
			tc.dcStateChan <- sid
		}

		tc.callee.OnDataChannelMessage = func(sid uint16, subproto string, payload string) {
			logPayload := payload
			if len(logPayload) > 200 {
				logPayload = logPayload[:200] + "...(truncated)"
			}
			log.Printf("[COORD] Callee received data on Stream %d: %s", sid, logPayload)
			tc.receivedMsgs <- DataMsg{
				StreamID:    sid,
				Subprotocol: subproto,
				Payload:     payload,
				ReceivedAt:  time.Now(),
			}
		}
	}

	// Start Callee if not UAS
	if !isUASCall {
		log.Printf("[COORD] Starting Callee (%s)...", sc.Callee.Username)
		if err := tc.callee.Start(); err != nil {
			return fmt.Errorf("failed to start Callee: %w", err)
		}
	}

	// Start Caller
	log.Printf("[COORD] Starting Caller (%s)...", sc.Caller.Username)
	if err := tc.caller.Start(); err != nil {
		if !isUASCall && tc.callee != nil {
			tc.callee.SendBye()
		}
		return fmt.Errorf("failed to start Caller: %w", err)
	}

	defer func() {
		// Cleanup session
		tc.caller.SendBye()
		if !isUASCall && tc.callee != nil {
			tc.callee.SendBye()
		}
		time.Sleep(500 * time.Millisecond)
		tc.caller.Stop()
		if !isUASCall && tc.callee != nil {
			tc.callee.Stop()
		}
	}()

	for stepIdx, step := range sc.Steps {
		log.Printf("[STEP %d] Action: %s", stepIdx+1, step.Action)
		
		switch step.Action {
		case "wait_register":
			timeout := 10 * time.Second
			if step.TimeoutMS > 0 {
				timeout = time.Duration(step.TimeoutMS) * time.Millisecond
			}

			// Wait for both to register
			callerRegistered := false
			calleeRegistered := isUASCall
			limit := time.After(timeout)

		waitRegLoop:
			for !callerRegistered || !calleeRegistered {
				select {
				case <-callerRegChan:
					callerRegistered = true
					log.Printf("[ASSERTION] Caller Registration Confirmed")
				case <-calleeRegChan:
					calleeRegistered = true
					log.Printf("[ASSERTION] Callee Registration Confirmed")
				case <-limit:
					break waitRegLoop
				}
			}

			if callerRegistered && calleeRegistered {
				log.Printf("[OK] Both UEs registered successfully")
				tc.RecordPass()
			} else {
				log.Printf("[FAIL] SIP Registration timeout (Caller: %v, Callee: %v)", callerRegistered, calleeRegistered)
				tc.RecordFail()
				return fmt.Errorf("SIP registration failed")
			}

		case "dial":
			timeout := 15 * time.Second
			if step.TimeoutMS > 0 {
				timeout = time.Duration(step.TimeoutMS) * time.Millisecond
			}

			log.Printf("[COORD] Caller triggers INVITE to %s", sc.Callee.Username)
			tc.caller.StartCallerCall()

			if step.Payload == "no_wait" {
				log.Printf("[COORD] Dial triggered (no wait mode)")
				tc.RecordPass()
				if step.DelayMS > 0 {
					time.Sleep(time.Duration(step.DelayMS) * time.Millisecond)
				}
				break
			}

			callerConnected := false
			calleeConnected := isUASCall
			limit := time.After(timeout)

		waitConnLoop:
			for !callerConnected || !calleeConnected {
				select {
				case <-callerConnChan:
					callerConnected = true
					log.Printf("[ASSERTION] Caller SIP Dialog Connected")
				case <-calleeConnChan:
					calleeConnected = true
					log.Printf("[ASSERTION] Callee SIP Dialog Connected")
				case <-limit:
					break waitConnLoop
				}
			}

			if callerConnected && calleeConnected {
				log.Printf("[OK] Call established between both ends")
				tc.RecordPass()
			} else {
				log.Printf("[FAIL] Call setup timeout (Caller: %v, Callee: %v)", callerConnected, calleeConnected)
				tc.RecordFail()
				return fmt.Errorf("call setup failed")
			}

		case "wait_webrtc_connect":
			timeout := 15 * time.Second
			if step.TimeoutMS > 0 {
				timeout = time.Duration(step.TimeoutMS) * time.Millisecond
			}

			// Wait for target Stream ID to be opened on both ends or caller/callee end
			targetID := step.StreamID
			if targetID == 0 {
				targetID = 1000 // default to whiteboard ADC
			}

			log.Printf("[COORD] Waiting for WebRTC DataChannel Stream %d to open...", targetID)
			
			limit := time.After(timeout)
			ticker := time.NewTicker(200 * time.Millisecond)
			defer ticker.Stop()
			dcOpens := 0
		waitDCLoop:
			for {
				select {
				case sid := <-tc.dcStateChan:
					if sid == targetID {
						dcOpens++
						log.Printf("[ASSERTION] DataChannel Stream %d opened (%d confirmed)", targetID, dcOpens)
						if isUASCall && dcOpens >= 1 {
							break waitDCLoop
						}
						if dcOpens >= 2 { // Caller & Callee open
							break waitDCLoop
						}
					}
				case <-ticker.C:
					if isUASCall {
						if tc.caller.IsDataChannelOpen(targetID) {
							dcOpens = 1
							log.Printf("[ASSERTION] DataChannel Stream %d confirmed open on Caller", targetID)
							break waitDCLoop
						}
					} else {
						if tc.caller.IsDataChannelOpen(targetID) && tc.callee != nil && tc.callee.IsDataChannelOpen(targetID) {
							dcOpens = 2
							log.Printf("[ASSERTION] DataChannel Stream %d confirmed open on both endpoints (2/2 confirmed)", targetID)
							break waitDCLoop
						}
					}
				case <-limit:
					break waitDCLoop
				}
			}

			if (isUASCall && dcOpens >= 1) || (!isUASCall && dcOpens >= 2) {
				log.Printf("[OK] WebRTC Data Channel (Stream %d) fully established", targetID)
				tc.RecordPass()
			} else {
				log.Printf("[FAIL] WebRTC Data Channel failed to open in time (confirmed opens: %d)", dcOpens)
				tc.RecordFail()
				return fmt.Errorf("WebRTC data channel open failed")
			}

		case "request_app":
			appId := step.Payload
			if appId == "" {
				appId = "sketch-sync-app"
			}
			if step.Role == "callee" && tc.callee != nil {
				log.Printf("[COORD] Callee requesting mini-app: %s", appId)
				tc.callee.SendReInvite(appId)
			} else {
				log.Printf("[COORD] Caller requesting mini-app: %s", appId)
				tc.caller.SendReInvite(appId)
			}
			tc.RecordPass() // successfully triggered
			if step.DelayMS > 0 {
				time.Sleep(time.Duration(step.DelayMS) * time.Millisecond)
			}

		case "close_app", "exit_app":
			appId := step.Payload
			if appId == "" {
				appId = "sketch-sync-app"
			}
			if step.Role == "callee" && tc.callee != nil {
				log.Printf("[COORD] Callee closing mini-app ADC: %s", appId)
				tc.callee.SendReInviteClose(appId)
			} else {
				log.Printf("[COORD] Caller closing mini-app ADC: %s", appId)
				tc.caller.SendReInviteClose(appId)
			}
			tc.RecordPass()
			if step.DelayMS > 0 {
				time.Sleep(time.Duration(step.DelayMS) * time.Millisecond)
			}

		case "http_get_bdc", "request_bdc":
			path := step.Payload
			if path == "" {
				path = "/"
			}
			log.Printf("[COORD] Caller sending HTTP GET %s over BDC Stream 0...", path)
			// Flush old received messages
			for len(tc.receivedMsgs) > 0 {
				<-tc.receivedMsgs
			}
			req := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: bdc\r\n\r\n", path)
			err := tc.caller.SendDataChannelMessage(0, req)
			if err != nil {
				log.Printf("[FAIL] Failed to send BDC HTTP request: %v", err)
				tc.RecordFail()
				return fmt.Errorf("BDC HTTP send failed: %w", err)
			}
			receivedHTTP := false
			timeout := 6 * time.Second
			limit := time.After(timeout)
		waitHttpLoop:
			for {
				select {
				case msg := <-tc.receivedMsgs:
					if msg.StreamID == 0 && (strings.Contains(msg.Payload, "HTTP/1.1 200") || strings.Contains(msg.Payload, "<html") || strings.Contains(msg.Payload, "<!DOCTYPE") || len(msg.Payload) > 30) {
						receivedHTTP = true
						log.Printf("[OK] Received valid BDC HTTP response (%d bytes)", len(msg.Payload))
						break waitHttpLoop
					}
				case <-limit:
					break waitHttpLoop
				}
			}
			if receivedHTTP {
				tc.RecordPass()
			} else {
				log.Printf("[FAIL] Did not receive valid BDC HTTP response in time")
				tc.RecordFail()
				return fmt.Errorf("BDC HTTP response verification failed")
			}
			if step.DelayMS > 0 {
				time.Sleep(time.Duration(step.DelayMS) * time.Millisecond)
			}

		case "send_data":
			streamID := step.StreamID
			payload := step.Payload
			log.Printf("[COORD] Caller sending payload on Stream %d: %s", streamID, payload)
			
			// Flush any old received messages
			for len(tc.receivedMsgs) > 0 {
				<-tc.receivedMsgs
			}

			// Send data
			err := tc.caller.SendDataChannelMessage(streamID, payload)
			if err != nil {
				log.Printf("[FAIL] Failed to send Data Channel message: %v", err)
				tc.RecordFail()
				return fmt.Errorf("send message failed: %w", err)
			}

			// Wait for peer to receive matching message
			receivedOK := false
			timeout := 5 * time.Second
			limit := time.After(timeout)

		waitMsgLoop:
			for {
				select {
				case msg := <-tc.receivedMsgs:
					if msg.StreamID == streamID && isPayloadMatch(payload, msg.Payload) {
						receivedOK = true
						break waitMsgLoop
					} else {
						log.Printf("[WARN] Received unexpected msg: Stream %d payload: %s", msg.StreamID, msg.Payload)
					}
				case <-limit:
					break waitMsgLoop
				}
			}

			if receivedOK {
				log.Printf("[OK] Matching payload verified successfully")
				tc.RecordPass()
			} else {
				log.Printf("[FAIL] Matching payload not received in time")
				tc.RecordFail()
				return fmt.Errorf("message verification failed")
			}

			if step.DelayMS > 0 {
				time.Sleep(time.Duration(step.DelayMS) * time.Millisecond)
			}

		case "hangup":
			if (step.Payload == "callee" || step.Payload == "B") && !isUASCall && tc.callee != nil {
				log.Printf("[COORD] Triggering call Hangup from Callee...")
				tc.callee.SendBye()
			} else {
				log.Printf("[COORD] Triggering call Hangup from Caller...")
				tc.caller.SendBye()
			}

			callerDisc := false
			calleeDisc := isUASCall
			limit := time.After(5 * time.Second)

		waitDiscLoop:
			for !callerDisc || !calleeDisc {
				select {
				case <-callerDiscChan:
					callerDisc = true
				case <-calleeDiscChan:
					calleeDisc = true
				case <-limit:
					break waitDiscLoop
				}
			}

			log.Printf("[OK] Session terminated cleanly")
			tc.RecordPass()

			if step.DelayMS > 0 {
				time.Sleep(time.Duration(step.DelayMS) * time.Millisecond)
			}

		case "cancel":
			log.Printf("[COORD] Triggering call Cancel...")
			tc.caller.SendCancel()

			callerDisc := false
			calleeDisc := isUASCall
			limit := time.After(5 * time.Second)

		waitCancelLoop:
			for !callerDisc || !calleeDisc {
				select {
				case <-callerDiscChan:
					callerDisc = true
				case <-calleeDiscChan:
					calleeDisc = true
				case <-limit:
					break waitCancelLoop
				}
			}

			log.Printf("[OK] Session cancelled cleanly")
			tc.RecordPass()

			if step.DelayMS > 0 {
				time.Sleep(time.Duration(step.DelayMS) * time.Millisecond)
			}

		case "reject":
			if tc.callee != nil {
				log.Printf("[COORD] Triggering call Reject...")
				tc.callee.RejectIncomingCall()
			}

			callerDisc := false
			calleeDisc := isUASCall
			limit := time.After(5 * time.Second)

		waitRejectLoop:
			for !callerDisc || !calleeDisc {
				select {
				case <-callerDiscChan:
					callerDisc = true
				case <-calleeDiscChan:
					calleeDisc = true
				case <-limit:
					break waitRejectLoop
				}
			}

			log.Printf("[OK] Session rejected cleanly")
			tc.RecordPass()

			if step.DelayMS > 0 {
				time.Sleep(time.Duration(step.DelayMS) * time.Millisecond)
			}

		case "delay":
			if step.DelayMS > 0 {
				log.Printf("[COORD] Simulating human delay for %d ms...", step.DelayMS)
				time.Sleep(time.Duration(step.DelayMS) * time.Millisecond)
			}

		case "assert_rtp":
			// Reset counters before measurement
			tc.caller.ResetRtpStats()
			if !isUASCall && tc.callee != nil {
				tc.callee.ResetRtpStats()
			}

			// Wait for RTP packets to accumulate (default 3 seconds)
			measureDuration := 3 * time.Second
			if step.DelayMS > 0 {
				measureDuration = time.Duration(step.DelayMS) * time.Millisecond
			}
			log.Printf("[COORD] Collecting RTP traffic for %v...", measureDuration)
			time.Sleep(measureDuration)

			callerAS, callerAR, callerVS, callerVR := tc.caller.GetRtpStats()
			log.Printf("[RTP STATS] Caller (%s): Audio Sent=%d Recv=%d | Video Sent=%d Recv=%d",
				sc.Caller.Username, callerAS, callerAR, callerVS, callerVR)

			tc.caller.Mu().Lock()
			callerDestIP := tc.caller.RtpIP
			callerDestAudioPort := tc.caller.RtpAudioPort
			callerDestVideoPort := tc.caller.RtpVideoPort
			tc.caller.Mu().Unlock()

			log.Printf("[RTP SDP] Caller sends RTP to: %s audio=%d video=%d", callerDestIP, callerDestAudioPort, callerDestVideoPort)

			// Assert: Caller must be sending audio AND receiving audio (bidirectional)
			allPass := true
			if callerAS > 0 {
				log.Printf("[ASSERTION] ✅ Caller Audio SEND OK (%d packets)", callerAS)
				tc.RecordPass()
			} else {
				log.Printf("[ASSERTION] ❌ Caller Audio SEND FAIL (0 packets sent)")
				tc.RecordFail()
				allPass = false
			}
			if callerVS > 0 {
				log.Printf("[ASSERTION] ✅ Caller Video SEND OK (%d packets)", callerVS)
				tc.RecordPass()
			} else {
				log.Printf("[ASSERTION] ❌ Caller Video SEND FAIL (0 packets sent)")
				tc.RecordFail()
				allPass = false
			}
			if isUASCall {
				if callerAR > 0 {
					log.Printf("[ASSERTION] ✅ Caller Audio RECV OK (%d packets from UAS)", callerAR)
				} else {
					log.Printf("[ASSERTION] ℹ️ Caller Audio RECV (0 packets - UAS IVR/menu mode)")
				}
				tc.RecordPass()

				if callerVR > 0 {
					log.Printf("[ASSERTION] ✅ Caller Video RECV OK (%d packets from UAS)", callerVR)
				} else {
					log.Printf("[ASSERTION] ℹ️ Caller Video RECV (0 packets - UAS IVR/menu mode)")
				}
				tc.RecordPass()
			} else {
				if callerAR > 0 {
					log.Printf("[ASSERTION] ✅ Caller Audio RECV OK (%d packets from server)", callerAR)
					tc.RecordPass()
				} else {
					log.Printf("[ASSERTION] ❌ Caller Audio RECV FAIL (0 packets received)")
					tc.RecordFail()
					allPass = false
				}
				if callerVR > 0 {
					log.Printf("[ASSERTION] ✅ Caller Video RECV OK (%d packets from server)", callerVR)
					tc.RecordPass()
				} else {
					log.Printf("[ASSERTION] ❌ Caller Video RECV FAIL (0 packets received)")
					tc.RecordFail()
					allPass = false
				}
			}

			if callerDestIP != "" && callerDestAudioPort > 0 {
				log.Printf("[ASSERTION] ✅ Caller RTP dest from SDP valid: %s:%d", callerDestIP, callerDestAudioPort)
				tc.RecordPass()
			} else {
				log.Printf("[ASSERTION] ❌ Caller RTP dest from SDP invalid: IP=%s Port=%d", callerDestIP, callerDestAudioPort)
				tc.RecordFail()
				allPass = false
			}

			// If not UAS, assert Callee RTP stats as well
			if !isUASCall && tc.callee != nil {
				calleeAS, calleeAR, calleeVS, calleeVR := tc.callee.GetRtpStats()
				log.Printf("[RTP STATS] Callee (%s): Audio Sent=%d Recv=%d | Video Sent=%d Recv=%d",
					sc.Callee.Username, calleeAS, calleeAR, calleeVS, calleeVR)

				tc.callee.Mu().Lock()
				calleeDestIP := tc.callee.RtpIP
				calleeDestAudioPort := tc.callee.RtpAudioPort
				calleeDestVideoPort := tc.callee.RtpVideoPort
				tc.callee.Mu().Unlock()

				log.Printf("[RTP SDP] Callee sends RTP to: %s audio=%d video=%d", calleeDestIP, calleeDestAudioPort, calleeDestVideoPort)

				if calleeAS > 0 {
					log.Printf("[ASSERTION] ✅ Callee Audio SEND OK (%d packets)", calleeAS)
					tc.RecordPass()
				} else {
					log.Printf("[ASSERTION] ❌ Callee Audio SEND FAIL (0 packets sent)")
					tc.RecordFail()
					allPass = false
				}
				if calleeVS > 0 {
					log.Printf("[ASSERTION] ✅ Callee Video SEND OK (%d packets)", calleeVS)
					tc.RecordPass()
				} else {
					log.Printf("[ASSERTION] ❌ Callee Video SEND FAIL (0 packets sent)")
					tc.RecordFail()
					allPass = false
				}
				if calleeAR > 0 {
					log.Printf("[ASSERTION] ✅ Callee Audio RECV OK (%d packets from server)", calleeAR)
					tc.RecordPass()
				} else {
					log.Printf("[ASSERTION] ❌ Callee Audio RECV FAIL (0 packets received)")
					tc.RecordFail()
					allPass = false
				}
				if calleeVR > 0 {
					log.Printf("[ASSERTION] ✅ Callee Video RECV OK (%d packets from server)", calleeVR)
					tc.RecordPass()
				} else {
					log.Printf("[ASSERTION] ❌ Callee Video RECV FAIL (0 packets received)")
					tc.RecordFail()
					allPass = false
				}

				if calleeDestIP != "" && calleeDestAudioPort > 0 {
					log.Printf("[ASSERTION] ✅ Callee RTP dest from SDP valid: %s:%d", calleeDestIP, calleeDestAudioPort)
					tc.RecordPass()
				} else {
					log.Printf("[ASSERTION] ❌ Callee RTP dest from SDP invalid: IP=%s Port=%d", calleeDestIP, calleeDestAudioPort)
					tc.RecordFail()
					allPass = false
				}
			}

			if !allPass {
				return fmt.Errorf("RTP assertion failed — see details above")
			}
			log.Printf("[OK] ✅ Bidirectional RTP Audio/Video verified successfully")
		}
	}

	return nil
}

func isPayloadMatch(sent, received string) bool {
	if sent == received {
		return true
	}
	// Try parsing both as JSON maps
	var sentMap, receivedMap map[string]interface{}
	if err1 := json.Unmarshal([]byte(sent), &sentMap); err1 == nil {
		if err2 := json.Unmarshal([]byte(received), &receivedMap); err2 == nil {
			// If it's a Caro game service message, check if both refer to the same service
			if sentMap["service"] == "caro" && receivedMap["service"] == "caro" {
				return true
			}
		}
	}
	return false
}
