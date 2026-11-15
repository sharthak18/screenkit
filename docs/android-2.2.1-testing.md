# Android 2.2.1 regression checklist

Automated: BubblePlacement tests for left/right anchors, 100 repeated cycles, interior
anchors, corner clipping, narrow screens, live-status height changes, rotation geometry,
and preserving menu order during a drag. LiveDestination tests cover choices, presets,
TikTok's deliberately unset ingest URL, and credential-free HTTPS hand-off URLs.

Physical Android checks (not run in this sandbox):
- Dock on left/right at top/middle/bottom, expand/collapse repeatedly; no mid-screen drift.
- Start/stop capture, sound/touch updates, hide/restore screenshots and live status changes
  while docked right. Anchor should survive every menu rebuild.
- Drag both collapsed/expanded bubble across the screen. No lost UP event; test rapid tap
  during snap, ACTION_CANCEL, bottom remove target, and moving away from the remove target.
- Rotate at either edge, split-screen, display/font scaling, 240dp-wide screens, gesture
  navigation/status-bar insets; all controls accessible and no important off-screen content.
- Facecam on/off from bubble with permission granted/denied; Flip while previewing and while
  screen recording/broadcasting with preview. Separate camera-file recording must refuse
  flip without modifying the stored selection. Front-only/back-only devices and camera busy.
- Go Live from app and bubble (cold/warm MainActivity); chooser opens directly, including
  TikTok. Select each platform, open installed app or browser, return and enter credentials.
  Hand-off alone must never show an authenticated/connected state or start capture.
- No platform app/browser installed, denied screen sharing, invalid key/server, busy capture,
  back/dismiss/rotation, returning from external app, repeated Go Live taps and active stream.
  Keys must not survive dismissal/platform switch or enter saved-instance-state/logs.
- End/mute live stream and camera preview behavior remain functional. DNS filtering and
  automation regression checks from earlier releases remain applicable.

One-tap account OAuth/publishing is NOT implemented: no registered OAuth clients,
redirects, platform token exchange/storage or publishing API permissions are configured.
The UX now begins with a platform rather than an encoder form, but screen broadcasting
still uses RTMP/RTMPS credentials. Do not describe the hand-off as Connect account.
