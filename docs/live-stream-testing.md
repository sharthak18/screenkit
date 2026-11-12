# Go Live validation checklist

## Automated

`gradle :stream-common:testDebugUnitTest :stream-rtmp:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`
runs endpoint/quality/dimension, real local TLS handshake, escaped token, and AVC
packetization tests and compiles the APK. Tests must pass before the APK publish step in CI.

## Device / private-server tests (manual, require an Android device)

Do not use real keys in logs or test fixtures. Prefer a local RTMP receiver or an
unlisted/private platform broadcast. Verify at the receiver, not just the sender UI.

- Android 7–9: microphone/silent; Android 10+: device and mixed audio.
- Android 14/15: fresh consent for every session; whole-screen and single-app sharing;
  refuse consent, deny microphone, deny notifications, rotate while consent is visible.
- RTMP test receiver and RTMPS platform receiver: picture, audible audio, A/V sync
  at startup and after 10 minutes; 480p/720p/1080p and 24/30/60 fps where supported.
- Sound off still produces a silent AAC track. Mute/unmute both sources while connected;
  inspect receiver latency and verify no fresh audio after queued data drains.
- Invalid key, unreachable host, TLS failure, connection timeout, network loss, slow uplink,
  server disconnect: clear sanitized error, no saved phantom MP4, no running projection,
  microphone indicator, or orphan notification; starting a new stream succeeds.
- Stop while connecting and after publishing via app, floating controls, notification,
  Android sharing chip, shake and volume-down. Verify resources release and no reconnect.
- Open app while streaming, background app, dismiss bubble, lock/unlock, rotate, kill process.
  Status remains correct while process is alive; process death never automatically restarts.
- Try screenshot, screen recording and camera recording during a stream: no second capture.
- Regression: record MP4 with sound on/off; pause/resume; save screenshot; camera recording.
- Verify screen-sharing privacy notice, masked key, no key in preferences/saved state or
  ScreenKit UI errors. Inspect logcat for any third-party transport leakage before release use.
- Check dashboard preview and public Go Live separately. Encoder success is not public-live status.

Automated tests do not substitute for these Android/network/platform checks.
