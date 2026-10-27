# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased] - 2026-10-25

### Added & Enhanced
- **Caro Mini-App Demo Upgrade:**
  - New layout: board in a frosted bottom panel, remote video clearly visible in the top part of the screen (`caro/index.html`).
  - Move feedback: pop animation, synthesized sound, vibration; rejected moves now shake the board and show a toast, with optimistic moves rolled back.
  - Win celebration: animated winning line, confetti on the winner's phone, result card with score.
  - 20 s turn timer enforced by DCAS (`caro_game.go`); turn passes to the opponent on timeout. New additive message fields `turn_ms_left`, `timeout`, `timed_out`.

- **SkyLotus Air Flight Booking Mini-App (new, P2A):**
  - Light bottom-sheet app (`flight-booking/index.html`) with 4 steps: search, results with inline fare choice, seat map (A321, 3-3), review & mock payment, plus a confirmation ticket with PNR and QR. Can be minimized to a pill so the call stays visible.
  - DCAS handler (`flight_booking.go`) acts as the agent: returns flights, suggests SL 213 and seat 4A, validates the booking and issues the PNR. Page falls back to local data if no reply arrives.
  - Routed as P2A in DCSF / IMS-AS; listed in the operator menu.
  - Booked seats are shared between callers (per flight and date, in memory until DCAS restarts). If two callers confirm the same seat, the second gets `code: "seat_taken"` with a fresh seat map and a new agent suggestion. On a phone a missing confirm reply is an error after 10 s; only the browser preview books locally.
  - One seat per passenger: with 2+ passengers the seat map takes that many seats (tap to add or remove; a new tap replaces the oldest once all are chosen), and Continue waits until all are picked. DCAS suggests seats side by side in one row, books the whole group or none (`seats` list; old `seat` field still accepted), and charges the extra-legroom fee per seat.
  - Airline hotline **1900**: dialing it is answered by the network like 198 (voice-only) and auto-opens flight-booking. Hotline numbers now live in one table (`go-mf/internal/config/hotline.go`, mirrored in `CallActivity.HOTLINE_APPS`).

- **Messenger-style call screen (Android):** compact top bar (avatar, name, status · timer), large pulsing avatar while connecting, and a frosted control pill with round Mute / Speaker / Hold / End buttons that turn white when active. The speaker button now reflects the real speaker state. The exit button is a small "‹ Menu" pill. SkyLotus Air now uses the full screen above the controls.

### Fixed
- Hotline app re-INVITE (e.g. 1900 → flight-booking): IMS-AS answered the phone with the callee-leg ADC credentials, so the ADC stayed CONNECTING and DCAS never got search/select/confirm. The answer now carries MF's caller-side ADC transport.
- Declining an incoming call from the notification left MT on the ringing screen while MO had already hung up. Declining now ends the call locally on MT too.
- Video sent from some phones (e.g. OPPO / MediaTek) showed black-and-white on the far end: the encoder is now fed through `getInputImage()` so each chip's colour layout is honoured.
- Choppy received video: incomplete FU-A NALUs are dropped instead of decoded, the decoder gets more time for input, the video socket buffer is larger, and outgoing RTP now has correct sequence numbers and marker bits.
- Smeared/blocky video after packet loss: the receiver now counts every RTP gap and freezes on the last good frame until the next keyframe instead of decoding broken frames; failed sends are logged and the video socket send buffer is larger.
- Blocky video on movement: the network delivers video RTP out of order (e.g. 1504, 1506, 1507, 1505), and fragments were joined in arrival order. The receiver now holds early packets for up to 60 ms and puts them back in sequence order. Only a gap that never fills counts as loss and triggers the keyframe freeze.
- Long freezes after real packet loss: the receiver now sends an RTCP PLI keyframe request (repeated every 300 ms while frozen) and the sender forces a keyframe, so video recovers in about 0.1 s instead of up to 1 s. Large frames are also paced (1 ms pause every 4 packets) so keyframe bursts are not dropped by Wi-Fi/VPN queues.
- Garbled (mojibake) Vietnamese error messages sent by DCAS for invalid Caro moves.

## [16.0.0] - 2026-08-20

### Added & Enhanced
- **Android Native Collapsible Dialpad & Call History Expansion:**
  - Implemented smooth dynamic layout transition in `MainActivity.kt` and `activity_main.xml`. Tapping the call history automatically collapses the dialpad and shows a floating dialpad button (`fabShowDialpad`), maximizing call history display matching native flagship dialers.
- **E2E Simulation Test Suite (`dc-test-tool`) 100% Feature Coverage:**
  - Expanded `cmd/dc-test-tool`, `internal/dctest`, and `deploy/test_scenarios.json` to 9 comprehensive scenarios covering all manual S24 testing flows:
    1. Whiteboard Draw & FunCalling Emoji & Exit Mini-App (ADC Release).
    2. Voice/Video Only RTP assertion.
    3. Caro Game P2P (Turn synchronization & state validation).
    4. Early Dialog SIP CANCEL.
    5. Call Rejection (SIP 486 Busy Here).
    6. Whiteboard Callee Hangup.
    7. Callee-Initiated Mini-App Launching.
    8. Hotline 198 CSKH Smart Menu UAS Interaction (BDC Stream 0 payload verification).
    9. Enterprise Caller ID & Brand Promotion.
  - Achieved **103/103 Assertions Passed (100% Success Rate)** on Kubernetes.
- **Golang Microservices Architecture & Developer Guide:**
  - Created master documentation in `go-mf/README.md` and GoDoc package comments across all 8 microservices and 11 internal packages.
  - Provided step-by-step developer guides for creating new Mini-Apps, adding game handlers, and configuring test scenarios.
- **Network Deployment Architecture Clarification (Tailscale vs. Production Telco Core):**
  - Documented that Tailscale is strictly an optional addon for remote internet testing/lab environments (bypassing NAT/CGNAT).
  - Clarified that Production Telco environments route directly via 3GPP 5G UPF / N6 interfaces without Tailscale.
- **Early Media Announcement for Unreachable Subscribers (3GPP TS 24.186):**
  - Intercepts 480/404 on IMS-AS, emits SIP `183 Session Progress` with `P-Early-Media: sendrecv`, streams `unreachable.pcma` audio from MF, and cleanly tears down call with SIP `487 Request Terminated`.

## [15.0.3] - 2026-08-16

### Added & Enhanced
- **Smart Customer Service 198 (Hotline UAS / Enterprise 5G New Calling):**
  - Designed and deployed the interactive Smart Customer Service hotline `198` per GSMA 5G New Calling enterprise specifications.
  - **Core Network Terminating UAS:** IMS-AS and DCAS act as the terminating hotline UAS automatically answering calls to `198` with `200 OK`, establishing conversational voice/video and Bootstrap Data Channel (BDC).
  - **P2A Application Data Channel (ADC - Stream 1000):** On-demand ADC negotiation with `UDP_PROXY` policy routed to DCAS.
  - **3 Core Interactive Features:**
    1. 🎧 **Gặp tổng đài viên:** Sends `"agent"` action to DCAS, returning greeting *"Xin chào, tôi có thể giúp gì cho bạn"*, activating real-time multi-color soundwave visualizer (Waveform) and Vietnamese Web Speech TTS synthesis.
    2. 🎬 **Xem 1 video:** Sends `"video"` action to DCAS, dynamically retrieving a random video URL and opening an embedded HTML5 Video Player Modal inside the active call view.
    3. ⏰ **Thời gian hệ thống:** Sends `"time"` action to DCAS, fetching accurate server timestamp and displaying it dynamically on the UI panel.
- **Hotline Call UI Optimization:**
  - `CallActivity` automatically hides local/remote camera preview boxes when calling hotline `198` to provide an uncluttered full-screen interactive service layout.
- **End-to-End Regression Testing & Visual Verification:**
  - Automated regression testing across all 4 applications (TuYaApp Whiteboard, Caro 9x9, Fun Calling, Smart Menu 198) on physical Samsung Galaxy S24 devices (`R5CY54VL09X` & `R5CY54VL13Y`), visually verifying 100% two-way synchronization and zero-regression compliance with 3GPP TS 26.114/24.186/29.175/29.176 & GSMA NG.134/TS.66.

## [15.0.2] - 2026-08-15

### Added & Enhanced
- **Screen-Off Call Handling & Deep Doze Wakeup:**
  - Implemented `WakeLock`, `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, and `KeyguardManager.requestDismissKeyguard()` in `CallActivity` and `CallNotificationManager`.
  - Enables incoming calls to instantly wake devices from `DOZE_SUSPEND` state (<1.6s) and display full-screen incoming call UI without requiring user unlock.
- **Slide-to-Answer UX (Samsung One UI Standard):**
  - Created `SlideToAnswerView` widget with smooth swipe-to-answer gesture, glowing handle animations, and haptic feedback matching modern Android dialers.
- **Ringback Tone & Call Progress Signaling:**
  - Caller screen displays "Đang kết nối..." initially, transitioning to "Đang đổ chuông..." upon receiving SIP `180 Ringing` accompanied by local ringback tone playback (`ToneGenerator.TONE_SUP_RINGTONE`).
- **2-Way Synchronized Mini-Apps Ecosystem:**
  - **TuYaApp (Whiteboard / `sketch-sync-app`):** Real-time 2-way stroke synchronization over WebRTC ADC (Stream 1000) on HTML5 canvas with dynamic resolution scaling.
  - **Game Cờ Caro 9x9 (`caro`):** 9x9 board with full 2-way turn synchronization (X/O) and turn indicators.
  - **Fun Calling (`fun-calling-app`):** Interactive 5-emoji floating bar (❤️, 👍, 😊, 😡, ✋) and live toast feedback upon receiving emoji animations.
- **GSMA & 3GPP Architecture Compliance:**
  - Enforced plain SIP/TCP transport, RTP/UDP media streams (PCMA audio, H264 video), HTTP/2 Cleartext h2c SBA, and WebRTC BDC (Stream 10) / ADC (Stream 1000) isolation.

## [15.0.1] - 2026-08-14

### Added / Fixed
- **Samsung One UI 6.1 Dialer Integration:** Pixel-perfect dialer icon and incoming call layout adjustments.
- **BDC Caching Validation:** ETag and If-None-Match HTTP caching mechanism verification over Bootstrap Data Channel.

## [15.0.0] - 2026-08-12

### Added / Fixed
- **2-Way Video RTP Streaming:** Video call integration with PCMA audio and H264 video.
- **Callee-Initiated Mini-App Launching:** Support for Callee or Caller to launch Mini-Apps during active calls.
- **Incoming Call UI Refactoring:** Overhauled incoming banner and full-screen call management.

## [14.0.0] - 2026-08-02

### Added / Fixed
- **Android WebRTC Tailscale Binding:** Fixed an issue where WebRTC failed to establish ICE connections over the Tailscale VPN. Implemented `PeerConnectionFactory.Options.networkIgnoreMask = 14` in `WebRtcManager.kt` to force the native C++ WebRTC layer to route traffic over the VPN interface rather than Wi-Fi or Cellular networks.
- **Tailscale Router MASQUERADE:** Updated `k8s-tailscale.yaml` to include an `iptables` rule that masquerades outbound traffic on the `net1` interface. This ensures that Media Function (MF) pods on the macvlan network (10.202.0.x) can successfully route UDP/RTP return traffic back to the Android clients via the Tailscale subnet (100.x.x.x).
- **Redis Stability Fix:** Resolved an issue where Redis instances occasionally entered a read-only state. Removed the unnecessary `redis-sentinel` sidecar for single-node deployments and updated the startup script in `k8s-redis.yaml` to ensure the pod consistently starts as the `master` node, eliminating replica demotion conflicts.
- **Android App Persistence:** Fixed SharedPreferences logic in `MainActivity.kt` to properly save and load the customized SIP login information (`username` and `remote_user`), replacing the previous hardcoded defaults (1001 and 1002).
