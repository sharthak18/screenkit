# Android 2.2.2 bubble crash hotfix

## Failure path under investigation

The 2.2.1 terminal touch handler called rebuildOverlay() synchronously. This calls
root.removeAllViews() while the touched child can still own a ViewGroup touch target.
Android can synchronously dispatch CANCEL during removal, re-entering the listener while
its local dragging state remains true. A second rebuild then mutates the same hierarchy
inside the first rebuild. UI corruption/re-entrant dispatch is unsafe even when window
update exceptions are caught. There is no user-device crash trace yet, so this should not
be treated as the proven cause of every reported force close.

## Fix

- Tokenized gesture lifecycle: terminal event consumes and clears state once.
- UP/CANCEL view changes posted to the main looper after dispatch returns.
- Old CANCEL events, queued terminal actions, rotation and teardown are guarded against
  stale gestures/windows. Snap updates cannot move a different/detached window or an active drag.
- Remove-target ownership cleared before removing its window; no delayed cleanup/dismiss callback.
- Release must actually be inside the displayed close circle with a 12dp tolerance. No broad
  bottom-strip shortcut, invisible-target dismissal, sticky magnetic snap or stale hover dismissal.

## Automated validation

Robolectric Android API 28 and 34:
- Direct framework test that removal during UP can synthesize CANCEL.
- Real service overlay touch dispatch: drag/release without synchronous child replacement.
- Repeated expand/drag/collapse and surviving WindowManager attachment.
- Cancelled tap, deferred state refresh, and removal before a queued release executes.

Pure tests: terminal idempotence, stale callback/cancel rejection, teardown invalidation,
cancel semantics, and close-target bounds/invalid coordinates. Earlier placement tests retained.

## Device follow-up (not available in the sandbox)

Install over 2.2.1, enable overlay, drag immediately on the home screen and over another app.
Repeat left/right/top/bottom; expand and drag, tap during snap, and change recording/camera
state while dragging. Drag near—but outside—the X; it must stay open. Deliberately drop on
X; it should close. Cancel a touch stream, rotate, lock/unlock and revoke overlay permission.
Check gesture navigation/status-bar insets, OEM battery controls, accessibility and Show touches.

If it still closes, collect phone model, Android version, exact action and a redacted crash
stack (package com.screenkit.screenrecorder). Do not include stream keys or browsing content.
