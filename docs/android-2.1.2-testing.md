# Android 2.1.2 regression checklist

Automated policy cases: ad-label flicker between consecutive ads with a 0.2s timer;
full viewing delay after two-second ad-end confirmation; Hold and unsupported-window
interruptions; visible Skip-ad cooldown. Existing policy/parser and streaming tests remain.

Physical-device checks (not run in the sandbox):
- Floating menu has no Zoom action; Long SS, Script, Badge, scrolling and recording remain usable.
- Smart off performs no player inspection; Smart on can use a visible Skip ad label
  on the button itself or within up to two small non-scrollable parent containers.
- Hidden, disabled, different-package, broad and scrollable parents must not be clicked.
- YouTube/Facebook ad pods: flickering labels must not trigger Next; after stable
  disappearance, content receives a fresh timer. Test long mid-roll ads and resumption.
- Skip cooldown, Hold/Resume, manual Next, app switching and accessibility loss.
- No root/network filtering or new Android permissions; unskippable ads remain unskippable.

These heuristics cannot identify every app version, language, or ad. Hidden ad controls
may still cause timed fallback. Use manual Hold/Next when detection is unavailable.
