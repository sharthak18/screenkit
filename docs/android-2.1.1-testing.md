# Android 2.1.1 regression checklist

Automated: `:app:testDebugUnitTest` covers custom delay bounds, first-item wait, Hold,
end/loop detection, long playback vs short timers, sponsored vs mid-roll priority,
skip-button debounce, unsupported apps, timer fallback, and label/time parsing.
These are policy tests with synthetic observations, not proof that a social app exposes them.

Physical Android tests (not available in the build sandbox):
- Bubble third row Long SS at both screen edges and small displays; permission denial,
  Accessibility disabled/disconnected, static web/article, end of page, interrupted swipe.
  Confirm overlays are absent, no early single-frame fallback, no duplicate end-page images,
  and test approximate stitching with sticky headers. Capture at high display resolution.
- Zoom on Android 7–13 and 14+: grant/deny entire-screen sharing, drag panel across a
  changing clock/video/text (not ScreenKit's own panel), compare swatch to known color blocks;
  expect scaling interpolation. Copy/AutoPin unavailable before a fresh feed. Rotation,
  screen lock, service stop, close button, overlay dismissal and permission revocation must
  release the projection and clear pixels. Concurrent recording/capture is rejected.
- Delay 0.2, 8, 30, 90, 300, 3600 seconds in app and overlay; invalid/blank/overflow;
  profile save/load custom interval; first-item delay; runtime finite vs Infinite.
- Smart enabled and disabled, Hold/Resume, Next during Hold, stop/restart mid-swipe;
  switching apps, timer expiry during an ad, accessibility revoked. No old gesture callback
  should schedule a new run's swipe. Disabling smart stops reading content.
- YouTube Shorts / Instagram / Facebook Reels: record exact app versions and languages;
  short loop, long video, paused video, sponsored feed, visible Skip ad, unskippable mid-roll
  and resumed content. Verify the banner reflects observable state. Hidden progress or
  unknown/localised ad labels use the timer/manual controls, not fabricated detection.
- Regression: local MP4 (silent/mic/device/mixed), screenshots, Go Live start/stop;
  all capture-mode service actions must route correctly after the dispatch fix.
