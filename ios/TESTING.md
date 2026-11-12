# iOS acceptance checklist

CI compilation is not proof of a working signed device broadcast. Use a physical iPhone
and iPad running iOS 17+ and a private test destination. Never commit real keys.

## Signed build / privacy
- Both targets install and the ScreenKit Live provider appears in Apple's system picker.
- Mismatched/missing App Group or Keychain Sharing profiles show a clear setup error.
- Verify temporary Keychain key deletion on consume/cancel, expired setup rejection,
  and cleanup when reopening the app. No key in shared JSON, logs, backups or analytics.
- iOS consent always remains user-controlled. Decline mic/Photos permissions; other tools
  remain usable. Test screen-sharing privacy labels, Focus mode, and protected screens.

## Local recording & editing
- Start/stop an in-app recording with mic off and on; play saved MP4 and verify sound/sync.
- App interruption, cancellation, screen lock and low storage produce truthful status.
- Import from Photos including an iCloud movie, and from Files/security-scoped storage.
- Trim rotated and landscape videos; inspect output orientation and sound. PNG frame
  time is accurate. GIF loops and respects 15s/480px limits. Cancel processing, dismiss
  editor, save to Photos, share to Files, delete capture, relaunch and confirm library.
- Unsupported/corrupt/over-1GB files and invalid/NaN/reversed ranges fail safely.

## Broadcast
- RTMPS to private YouTube/Facebook broadcast; RTMP to a private compatible test receiver.
- Device, mic, mixed and silent audio; mic disabled in Apple's picker; no app sound;
  muted device and source apps that prohibit playback capture. Check audio/video sync
  at startup and after 10 minutes. Mute/unmute; no fresh audio after queued data drains.
- Portrait/landscape rotation, 480p/720p, 24/30fps, low/high bitrate. Verify actual receiver
  video (including SPS changes), not just ScreenKit's connected state.
- Track extension memory on high-resolution iPhones/iPads. The OS may terminate extensions
  around its memory budget. Validate that queues stay bounded over a 30-minute stream.
- Stop via app, red system indicator and Control Center while connecting and while live.
  Cancel consent, lock screen, background/terminate the host app, pause/resume sharing.
- Invalid key, unreachable server, invalid TLS certificate/hostname, network change/drop,
  slow uplink and server rejection must end cleanly. No silent automatic reconnection.
- Host status uses heartbeat freshness, never assumes public-live or viewer count. Status
  must not remain active after extension termination. New setup must work after failure.

## Before public distribution
- Developer-owned signing IDs, App Groups and privacy/support URLs, App Store privacy
  answers, screenshots and age rating, export compliance and dependency review.
- Check current platform policies; no accessibility or protected-capture bypass claims.
- Record physical-device outcomes here before describing platform compatibility as verified.
