# ScreenKit (v2.1.0)

A native, high-performance Android capture, creator studio & screen automation suite built with Modern Android Architecture (Jetpack Compose, CameraX, Foreground Services, Accessibility Gesture Engine, MediaProjection, OpenGL ES, MediaMuxer/MediaExtractor, and MediaCodec). ScreenKit v2.1.0 delivers screen recording with **Pause & Resume**, custom area cropping, **Scrolling Long Screenshots ("Stitch Full Page")**, built-in **MP4 Video Trimmer, Animated GIF Maker & PNG Frame Extractor**, sequential multi-target **Auto Clicking (`1 → 2 → 3 → 4…`)**, directional **Auto Scrolling (`↑ / ↓ / ← / →`)**, **Touch Macro Recorder & Replay**, **Saved Auto-Clicker Profiles (Slots 1–3)**, **Anti-Detection Human Jitter**, **Smart Color / Pixel Trigger (`#HEX`)**, **Floating Teleprompter**, **On-Screen Magnifier Loupe & Color Eyedropper**, **Creator Watermark Badge**, **Shake / Volume-Down Emergency Stop**, **Live Usage Analytics & One-Tap In-App Updater**, live interactive brush annotation, on-screen touch visualization, a draggable floating camera lens, amplified sound recording (Mic / Device Audio / Mixed), and a built-in Black-Screen Bypass Web Capture Browser.

---

## Download and Install

[⬇️ **Download the latest ScreenKit v2.1.0 APK**](https://github.com/sharthak18/ScreenKit/releases/latest/download/ScreenKit.apk)

- **Current Version**: `v2.1.0` (`versionCode = 7`)
- Compatible with **Android 7.0 (API 24) to Android 15 (API 35)**.
- Device Audio & Mixed (`Mic + Device`) playback capture supported on **Android 10+**.
- Full screen scope and single-app exclusion selection supported on **Android 14+**.
- On Android, open the downloaded APK and follow the system prompt. Allow "Install unknown apps" for your browser or file manager if prompted.

---

## What's New in v2.1.0 (13 Major Features)

1. **Touch Macro Recorder ("Record My Actions & Replay")**: Tap `● Macro` in the Auto tool, press `● Rec`, perform any real-time sequence of finger taps and swipes across the screen, tap `■ Done`, and replay your exact touch choreography (`1×` to `Infinite ∞`).
2. **Saved Auto-Clicker Profiles & Presets (Slots 1–3)**: Save and load named pin positions, delays, speeds, and modes into 3 instant profile slots (pre-loaded with **TikTok/Reels Scroll**, **Game Farming 1→4**, and **Reader Slow Scroll**).
3. **Smart Color / Pixel Trigger ("Click When Color Appears")**: Wait to trigger Pin `#1` until the underlying screen pixel matches your chosen `#HEX` color (within RGB tolerance), or bind a live screen color in 1 tap from the Magnifier Eyedropper.
4. **Anti-Detection Randomization (Human Jitter)**: Optional human-like randomization (`±4px` coordinate jitter, `±12%` tap hold duration jitter, and `±15%` interval jitter) so automated taps and scrolls never hit the exact same pixel or millisecond twice.
5. **Pause & Resume Screen Recording**: Pause and resume active screen recordings seamlessly into a single continuous MP4 file from the floating bubble, notification shade, or main app.
6. **Built-in Video Studio (MP4 Trimmer, Animated GIF Maker & PNG Frame Grabber)**: Tap **✂ Studio** on any recorded video in Recent Captures to losslessly trim start/end times (`MediaExtractor` + `MediaMuxer`), convert clips into looping animated `.gif` files, or save high-resolution `.png` still frames.
7. **Scrolling Long Screenshot ("Stitch Full Page")**: Tap **⇅ Long Stitch** to automatically capture consecutive frames while scrolling down and stitch them into a single tall full-page PNG.
8. **Floating Teleprompter / Script Overlay**: Draggable, semi-transparent auto-scrolling script window with adjustable scroll speed and font size (`A-` / `A+`) that automatically hides during screenshots.
9. **On-Screen Magnifier Loupe & Color Picker (Eyedropper)**: Draggable crosshair reticle with a `3×` live zoom loupe showing exact `(X, Y)` coordinates, `#HEX`, and `RGB` values — copy `#HEX` to clipboard or send directly to the Auto-Clicker Color Trigger.
10. **Custom Creator Watermark Badge**: Optional draggable corner watermark badge (e.g., `@YourHandle` or `Recorded with ScreenKit`) during recordings.
11. **Shake Phone or Volume-Down Shortcut to Stop**: Shake your phone (accelerometer) to immediately stop screen recording or auto-clicking, or press physical **Volume Down** at any time as an instant hardware emergency stop for the Auto Clicker / Scroller / Macro.
12. **In-App "Check for Updates" & One-Tap Updater**: Queries the GitHub Releases API (`sharthak18/screenkit/releases`) to compare your installed version against the latest release and download the newest `ScreenKit.apk` in one tap.
13. **Live Usage Counter & GitHub Download Analytics**: Tracks local usage telemetry (App Opens, Captures Saved, Auto Gestures Executed) alongside live GitHub Release APK download counts across every published version.

---

## Key Features

### 1. Sequential Multi-Point Auto Clicker (`1 → 2 → 3 → 4…`) & Auto Scroller (`↑ / ↓ / ← / →`)
- **Multi-Target Sequential Auto Clicker (`⊕ AutoTap`)**:
  - Place up to **20 numbered draggable target pins (`1`, `2`, `3`, `4`…)** anywhere on screen and run them in order (`1 → 2 → 3 → 4…`).
  - Set the **delay between clicks** (`100ms` to `5s`), **clicking speed / tap hold duration** (`Turbo 25ms`, `Fast 55ms`, `Normal 110ms`, `Slow 250ms`, `Long Hold 600ms`), **how many times each position taps** before advancing to the next position (`1×`, `2×`, `3×`, `5×`, `10×`, `20×` — or tap any pin directly to set a custom tap count just for that pin), and **total repeat cycles** (`1×` to `100×` or `Infinite ∞`).
- **Timed Directional Auto Scroller (`⇅ Scroll`)**:
  - Automatically scroll **Down (`⬇`)**, **Up (`⬆`)**, **Left (`⬅`)**, or **Right (`➡`)** across any app, feed, browser, or reader.
  - Customize **how fast or slow the scroll swipes** (`⚡ Very Fast 160ms`, `Fast 300ms`, `Normal 450ms`, `Slow 850ms`, `🐢 Reading 1400ms`), **pause delay between scrolls** (`0.6s` to `8s`), **how long scrolling runs** (`30s`, `1m`, `2m`, `5m`, `10m`, `30m`, or `Infinite ∞`), and **scroll swipe distance** (`Short 30%`, `Medium 55%`, `Long 75%`).
- **Configurable from the Main App or Live Floating Bar**: Adjust settings in the main app dashboard or tap `⚙` on the floating Auto-Click/Scroll bar without leaving your current app.

### 2. Compact Messenger-Style Floating Overlay
- **Automatic Pop-Up on App Launch**: Automatically launches whenever you open ScreenKit — even if you previously closed the floating bubble, returning to the app brings it right back.
- **Balanced, Unobtrusive Design**: Sleek `42dp` circular floating icon with a crisp `34dp` inner icon and no bulky border frame when collapsed, expanding into a compact 2×7 quick-action menu (`Full`, `Crop SS`, `Screen`, `Crop Rec`, `Camera`, `Mute/Mic/Device/Mix`, `AutoTap`, `Brush`, `Touch`, `Scroll`, `Lens`, `Flip`, `Bypass`, `Home`).
- **Messenger-Style Drag-to-Bottom Close**: Drag the floating bubble down into the bottom `✕` target (with magnetic snap-in feedback) to close it, or release anywhere else to smoothly snap to the nearest screen edge.

### 3. Auto-Hiding Overlay for Clean Screenshots
- **No Bubble in Screenshots**: Immediately upon tapping Full or Crop Screenshot, ScreenKit hides the floating bubble, auto-clicker pins, touch ripples, and brush toolbars (`View.GONE`) before the display frame is captured, restoring visibility immediately after capture.
- **Full-Resolution Capture**: Clean, untouched PNG output saved directly to your chosen destination.

### 4. Screen Brush & Touch Feedback on Floating Window
- **On-Screen Canvas Annotation (`✎ Brush`)**: Toggle the drawing tool directly from the floating bubble or main app. Includes 7 vibrant colors, a **`👆 Tap App` / `✎ Draw`** pass-through toggle (so you can interact with the underlying app without losing your drawings), eraser, undo, and clear.
- **Show Touches During Recording (`⊙ Touch`)**: Toggle touch visualization directly from the floating bubble or main app (`TouchFeedbackOverlay`). Renders smooth animated touch circles and pointer trails during screen recording and auto-clicking without interrupting your session.

### 5. Amplified Sound Recording & Flexible Camera Overlay
- **4.0× (+12 dB) Amplified Microphone Engine**: Built-in DSP pre-amplifier with a smooth rational soft-knee limiter, hardware `AutomaticGainControl` (AGC), and `NoiseSuppressor` boosts voiceover clarity without digital clipping.
- **Synchronized 48 kHz AAC Audio Modes** (cycle live on the floating window or in the main app):
  - **Mute / Silent** (`Off`): Video only.
  - **Microphone** (`Mic`): 4.0× amplified voiceover & room commentary.
  - **Device Audio** (`Device`): Internal app and game sound via Android `AudioPlaybackCapture`.
  - **Mixed Audio** (`Mix` — `Mic + Device`): Simultaneous 4.0× amplified microphone commentary and internal device sound mixed in real time at 48 kHz into AAC (`128 kbps`).
- **Compact Live Camera Lens (`116dp × 154dp`)**: Launch a draggable live CameraX preview overlay anywhere on screen before or during screen recording, with one-tap front/back camera flip (`⇄ Flip`).

### 6. Black-Screen Bypass & Protected Site Capture
- **Hardware OpenGL ES (`GL_TEXTURE_EXTERNAL_OES`) Pipeline**: Routes screen recording and screenshots through GPU OES textures (`GRALLOC_USAGE_HW_TEXTURE`), uses resilient public/presentation `VirtualDisplay` flags, automatically skips initial black compositor warmup frames, and provides a hardware framebuffer (`screencap -p`) fallback.
- **Built-in Web Capture Bypass Browser (`🌐 Bypass`)**: Open websites that turn black in external browsers directly via the `🌐 Bypass` button on the floating bubble, the main app, or Android's Share menu (`ScreenKit Bypass Capture`):
  - Strips `FLAG_SECURE` on the window.
  - Injects JS hooks to block `blur`/`visibilitychange` anti-capture blackouts, downgrade `HW_SECURE` EME/DRM robustness to software (`SW_SECURE_CRYPTO`), and preserve WebGL drawing buffers (`preserveDrawingBuffer: true`).
  - Includes one-tap **Direct Hardware `PixelCopy` Screenshot (`📸 Direct Shot`)** that captures the webpage directly from hardware memory without needing `MediaProjection`.

### 7. Crop Area Recording & Cropped Screenshots
- **Area Selection Overlay**: Interactive bounding box selection to specify any sub-region of the screen.
- **Real-Time OpenGL ES Cropping**: Cropped screen recording utilizes an internal EGL texture and OpenGL ES external OES shader pipeline (`CropSurfaceRenderer`), rendering only the specified coordinates directly into the hardware H.264 `MediaCodec` input surface.
- **Crop Screenshots**: Saves only the selected sub-rectangle with zero padding.

### 8. Custom Storage & Privacy
- **Configurable Save Destinations**:
  - Device MediaStore gallery (`Pictures/ScreenCapture` and `Movies/ScreenCapture`).
  - Secondary storage volumes (SD cards) with volume-aware MediaStore indexing.
  - Custom SAF folder chosen through Android's Storage Access Framework.
- **Local & Private**: All encoding and saving is strictly on-device. No telemetry, no background cloud uploads.

---

## Technical Architecture

```
                       ┌─────────────────────────────────────────┐
                       │          MainActivity (Compose)         │
                       └───────────────────┬─────────────────────┘
                                           │
        ┌──────────────────────────────────┼──────────────────────────────────┐
        ▼                                  ▼                                  ▼
┌──────────────────────────────┐  ┌──────────────────────────────┐  ┌──────────────────────────────┐
│    FloatingOverlayService    │  │  Brush, Touch & AutoOverlay  │  │      WebCaptureActivity      │
│ - 42dp Messenger-Style Bubble│  │ - Auto Clicker (1 → 2 → 3…)  │  │ - Anti-Black-Screen WebView  │
│ - Drag-to-Bottom (✕) Close   │  │ - Auto Scroller (↑ / ↓ / ←/→)│  │ - JS/EME/WebGL Bypass Hooks  │
│ - Auto-Hide on Screenshot    │  │ - Interactive Brush & Touch  │  │ - Direct PixelCopy Capture   │
└──────────────┬───────────────┘  └──────────────┬───────────────┘  └──────────────────────────────┘
               │                                 │
               ▼                                 ▼
┌──────────────────────────────┐  ┌──────────────────────────────┐  ┌──────────────────────────────┐
│    CaptureRequestActivity    │  │     ScreenKitAutoService     │  │     CameraCaptureService     │
│ - Transparent Permission Hop │  │ - Accessibility Gesture API  │  │ - CameraX Video & Preview    │
│ - AreaSelectionOverlay Crop  │  │ - Tap & Swipe Dispatcher     │  │ - Compact 116x154dp Window   │
└──────────────┬───────────────┘  └──────────────────────────────┘  └──────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          ScreenCaptureService                           │
│ - Resilient VirtualDisplay + BlackScreenBypass Pipeline                 │
│ - CropSurfaceRenderer (EGL14 + GLES20 OES Hardware Texture Pipeline)    │
│ - ScreenRecorderEngine (H.264 + 48kHz AAC, 4x Mic Pre-Amp + Device Mix) │
│ - Multi-Frame Anti-Black Screenshot Reader + Framebuffer Fallback       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/sharthak18/ScreenKit.git
   cd ScreenKit
   ```
2. Open in **Android Studio Ladybug (or newer)** with **JDK 17**.
3. Compile SDK: **35**, Target SDK: **34**, Minimum SDK: **24**.
4. Build debug APK:
   ```bash
   ./gradlew assembleDebug
   ```

---

## License

This project is licensed under the Apache License 2.0. See the LICENSE file for details.

## Go Live — v2.1.0

ScreenKit can send your **screen** as H.264 video and AAC audio to a single RTMP/RTMPS
server. This is an encoder connection, not YouTube/Facebook OAuth or a guarantee
that your account has live-streaming access.

1. In YouTube Live Control Room, Facebook Live Producer, or another compatible
   platform, create a live broadcast and select streaming/encoder software.
2. In ScreenKit's **Go Live** card choose a destination. Paste the exact **server
   URL** and **stream key** from that platform as separate fields. Presets are
   editable starting points. Prefer `rtmps://` (TLS) over unencrypted `rtmp://`.
3. Choose 480p/720p/1080p, 24/30/60 fps, a video bitrate (500–12000 kbps), and sound:
   off, microphone, device audio, or mixed. Resolution preserves screen aspect ratio,
   does not upscale, and is capped at a 1920-pixel long edge; actual frame rate depends
   on your device and screen updates. A good starting point is 720p / 30 fps / 2500 kbps.
4. Enable Do Not Disturb, tap **Go Live**, review the privacy warning, and approve
   Android screen sharing. Everything visible in the chosen screen/app can be sent,
   including overlays and notifications. Protected/DRM content cannot be streamed.
5. Wait for **LIVE · Sending to server**, then check the platform's preview. You may
   need to click **Go Live** there to make it public. ScreenKit's timer and outgoing
   bitrate describe the encoder connection, not viewer count or public visibility.
6. **Mute audio** silences both microphone and device sound while preserving the AAC
   track; unmute restores the sources chosen at startup. Use **End stream**, the
   floating red LIVE bubble's Stop control, or the capture notification to stop.
   Android's stop-sharing action and enabled shake/volume stop shortcuts also stop it.

### Streaming limitations and privacy

- Screen streaming is exclusive with screen recording, screenshots, and camera recording.
  This version does not save a simultaneous local MP4, stream the camera as a separate source,
  or send to multiple destinations at once. A visible camera overlay can appear in screen sharing.
- Sound sources are selected before starting. Device playback capture needs Android 10+
  and a source app that permits it. Denying audio permission gives a silent stream.
- Watermarks and the teleprompter are ordinary screen overlays: if included in the shared
  screen, viewers see them too. They are not private presenter-only layers.
- Keys/destinations are held in process memory only, not saved in preferences, bundles,
  backups, or analytics. The key field is masked and cleared when starting. Re-enter the key
  for the next broadcast. Never share keys in screenshots, bug reports, or chat.
- A 25-second connection timeout, bounded outgoing queue, and server disconnect/error
  handling end failed sessions and release screen/audio capture. Reconnection is manual;
  no stream silently restarts after a process death or network failure.
- Network throughput, thermal limits, codec support, and platform account eligibility affect
  compatibility. Test with a private/unlisted broadcast before going public.

Transport uses the Apache-2.0 licensed [RootEncoder RTMP module, version 2.5.3](https://github.com/pedroSG94/RootEncoder/tree/2.5.3/rtmp),
pinned for compatibility with this project's Kotlin toolchain. ScreenKit reuses its own
MediaCodec screen encoder and mixed-audio capture pipeline; streaming does not write a temporary MP4.
