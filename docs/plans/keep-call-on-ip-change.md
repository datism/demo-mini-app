# Keep the call going when the phone's IP changes


## Context
When the phone changes network during a call (Wi-Fi ↔ mobile data, VPN reconnect), it gets a new IP. Today the call is ended on purpose:
- `SipManager.checkLocalIp()` reconnects SIP and re-REGISTERs from the new IP.
- If a call is up, it then calls `onIpChangedDuringCall`. CallActivity shows "Mạng thay đổi – cuộc gọi đã kết thúc" and calls `endCall()`.

The user wants the call to carry on instead.

**What is tied to the old IP:**
1. **SIP TCP connection.** Already handled by `reconnectFromNewIp()`.
2. **Call audio/video RTP sockets.** In `RtpMediaManager.start()` they are bound to the old local IP (`bindRtpSocket`), and the old address is gone.
3. **The server's relay (go-mf MF).** All RTP goes phone ↔ MF ↔ other phone. `rtp_relay.go` matches incoming packets to a session only when the sender IP equals `sess.RtpIP`. It keeps the first source address it saw ("latched") and sends back there. `RtpIP` is only set when the session is created (`media_function.go:698`). So packets from the new IP are dropped, and replies still go to the old address.
4. **Mini-app data channels** (WebRTC to the MF, which is ICE-lite). The phone's PeerConnections use the default gathering policy, so they never try the new network.

The other phone needs no change. Its media goes to the MF, whose address stays the same.

## Changes

### Android (`android-client/app/src/main/java/com/demo/imsdc`)

**1. `sip/SipManager.kt`, `checkLocalIp()`**
- When `callMoved`:
  - If SIP reconnected, call `onIpChangedDuringCall(newIp)`. The callback now takes the new IP and moves the call instead of ending it.
  - If the SIP reconnect failed, end the call as today (send BYE).
- Set `callLocalIp = newIp` once the move starts, so a second network callback for the same IP doesn't trigger it again.

**2. `media/RtpMediaManager.kt`**
- Make `localIp` a `var`.
- Add `fun moveToIp(ip: String)`:
  - Remember the current local video track, decoder surface and remote destination.
  - Call `stop()`, set `localIp = ip`, then `start(track, surface)` on the same ports.
  - Restore the remote destination with `setRemoteDestination`, which also sends the hole-punch packets that let the MF learn the new address quickly.
- This reuses the tested start/stop path rather than swapping sockets under running threads. The threads keep their socket from start (`val socket = audioSocket`), so a swap wouldn't reach them. The cost is about 0.5 s of silence.
- `lastAudioRxMs` is reset, so the 20 s media watchdog doesn't fire during the move.

**3. `ui/CallActivity.kt`**
- In `onIpChangedDuringCall = { newIp -> ... }` on Main:
  1. Set `localIp = newIp`.
  2. Show the toast "Mạng thay đổi – đang kết nối lại…".
  3. Stop an active content share (photo/video/screen), using the existing stop path in MiniAppManager. Its separate sockets are also bound to the old IP.
  4. `rtpMediaManager?.moveToIp(newIp)`.
  5. `miniAppManager.sendHoldReInvite()`, reused as a "refresh" re-INVITE with the current slots and hold state. It already runs under the negotiation lock and retries on 491. `sendReInviteWithSdp` already builds the SDP `c=`, Via and Contact from `getLocalIp()`, which is now the new IP.
- If that re-INVITE fails (not answered, or 408/481), end the call as today. That means a small result callback on `sendHoldReInvite`, or a variant `refreshReInvite(onFail)`.

**4. `webrtc/WebRtcManager.kt`**
- In the BDC and ADC `RTCConfiguration` (~lines 181 and 254), add `continualGatheringPolicy = GATHER_CONTINUALLY`. libwebrtc then gathers on the new network and runs checks against the MF's candidate.
- The MF is ICE-lite and answers checks from the new address. The phone, which is controlling, nominates the new pair without new signaling.
- The existing `onPeerDisconnected` only logs, so a short DISCONNECTED during the switch is harmless.

### Server (`go-mf/internal/mf`)

**5. Re-learn the phone's RTP address from the re-INVITE**
- New helper in `media_function.go`: `updateRtpAddr(sess, sdp)`.
  - Run `parseSDPMedia(sdp)`. If the IP or audio/video ports changed, update `sess.RtpIP/RtpAudioPort/RtpVideoPort` under `sess.mu`.
  - Under `m.mu`, delete that session's entries from `latchedAudioSessions` and `latchedVideoSessions`.
  - The forwarder then latches the next packet from the new IP, and the other phone's audio is sent there.
- Call it wherever an in-dialog offer or answer from the phone reaches the MF session:
  - `SetRemoteAnswer` (`UpdateMediaContext` `/terminations/{0,1}/sdp`).
  - The re-offer path (`buildAdcTransport` / `CreateMediaContext` with `sdpOfferA`/`sdpOfferB`, sba_handler.go:916ff).
  - While implementing, confirm in the B2BUA (`imsas/b2bua.go`, slotted re-INVITE) which of these a plain refresh re-INVITE goes through. Then hook that path.
- Add a unit test next to the existing ones: a session latched on IP A, `updateRtpAddr` with IP B. The latch map is cleared and a packet from B gets latched.

**6. CHANGELOG line** under the first "### Added & Enhanced".

## Risks to check on the phones
- The B2BUA has to send later in-dialog requests (BYE from the other side, its re-INVITEs) to the new Contact from our re-INVITE. If it keeps the old one, our new REGISTER may still route them through the proxy. Verify with a hang-up from the other phone after a move.
- If the network is down for a while (no IP), nothing happens until a new IP appears. The 20 s watchdog still ends a call that never recovers.

## Verification
- Kotlin syntax check (scratchpad kotlinc) on the changed files. `go build ./...` and `go test ./internal/mf/...` in go-mf.
- On the phones, after an APK rebuild and the go-mf redeploy (`kubectl rollout restart -n demo-mini-app deploy/mf-1 deploy/dcar`):
  1. During a voice call, switch phone A from Wi-Fi to mobile data. The call stays up, and both sides hear each other again within about 2 s. Logcat shows "Local IP changed", the re-INVITE and "Audio Packets Received". The MF log shows "Latched session … on address <new IP>".
  2. Same during a video call: the video resumes after a keyframe.
  3. With Caro open, the moves still sync after the switch (data channel on the new network).
  4. During a screen share, the share stops and the call continues.
  5. After the switch, hang up from phone B. Phone A gets the BYE.
- Commit and push to `claude/cool-noether-4v4860`.
