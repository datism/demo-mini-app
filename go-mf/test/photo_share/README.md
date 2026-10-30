Photo/video sharing regression checks
====================================

Run from the repository root:

    python3 go-mf/test/photo_share/check_native.py
    python3 go-mf/test/photo_share/check_photo_share.py

The native check compiles the actual Android-independent SharedMediaFiles and
SharedMediaRange implementations with a locally installed/cached Kotlin compiler.
Set JAVA_HOME and KOTLIN_LIB if they are not discoverable. It checks exact binary
reassembly, checksums, full media IDs, malformed frames, bounded partial files,
retry/cleanup, and the range streams used for video seeking. No downloads occur.

The browser check requires Chrome/Chromium, ffmpeg and Python websocket-client.
It opens two independent pages with different viewport sizes and pixel densities,
relays their JSON bridge messages, and simulates native import/transfer completion.
It checks repeat portrait/landscape navigation, stale image loads, selection races,
live/final drawing, clear, high-DPI alignment, video playback/seeking and teardown.
Screenshots go to a temporary directory printed at the end.

These checks do not establish that camera, microphone, Android document providers,
or WebRTC/SCTP work on real devices. Full APK compilation is also required.

Device validation before release
-------------------------------

1. Build/install the updated APK on BOTH UEs and serve the updated DCAR app/menu.
   The new native file sender uses wire version 2 (full IDs and verified files).
   Old type-1 photo reception remains supported, but an old APK cannot receive v2.
2. Establish a voice/video call. Open Photo & Video Share on one UE.
3. Open the picker, then cancel. Confirm both microphone and camera recover.
4. Repeat, choosing a large camera image. Talk while importing and receiving it.
   Confirm A/V continues and no full-size base64 payload crosses the JS bridge.
5. Share portrait, landscape and EXIF-rotated/reflected images. Switch repeatedly
   from both UEs. Confirm aspect ratio and full-image detail remain unchanged.
6. Enable the pen, draw from each UE, clear, and navigate during an unfinished
   stroke. Confirm alignment, no stray marks on another photo, and working swipes
   after disabling the pen.
7. Share MP4/WebM/3GP clips up to 50 MiB. Confirm receive completion, playback and
   seek, then return to a photo. Clip playback is deliberately muted to preserve
   call audio; play/pause/seek are local controls, not synchronized commands.
8. Reject a file over 50 MiB, repeat picker cancellation/import, interrupt a
   transfer, retry, and end the call from either UE. Check sockets, temporary
   files, camera, microphone and WebView resources are released.

Photos are normalized once to JPEG (long edge <=1920, quality 80). Revisiting
an image reuses that same file; it does not transcode or downgrade to its thumbnail.
Video files are streamed/copied without transcoding; codec playback support is
provided by the Android WebView. No SIP/RTP codec or SDP changes are involved.
