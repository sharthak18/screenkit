# ScreenKit (v1.3.0)

A native, high-performance Android capture suite built with Modern Android Architecture (Jetpack Compose, CameraX, Foreground Services, MediaProjection, OpenGL ES, and MediaCodec). ScreenKit delivers screen recording with custom cropping, screenshot capture with auto-hidden overlays, live interactive brush annotation, on-screen touch visualization, a draggable floating camera lens, amplified sound recording (Mic / Device Audio / Mixed), a built-in Black-Screen Bypass Web Capture Browser, and zero cloud uploads.

---

## Download and Install

[⬇️ **Download the latest ScreenKit v1.3.0 APK**](https://github.com/sharthak18/ScreenKit/releases/latest/download/ScreenKit.apk)

- **Current Version**: `v1.3.0` (`versionCode = 5`)
- Compatible with **Android 7.0 (API 24) to Android 15 (API 35)**.
- Device Audio & Mixed (`Mic + Device`) playback capture supported on **Android 10+**.
- Full screen scope and single-app exclusion selection supported on **Android 14+**.
- On Android, open the downloaded APK and follow the system prompt. Allow "Install unknown apps" for your browser or file manager if prompted.

---

## Key Features

### 1. Compact Messenger-Style Floating Overlay
- **Automatic Pop-Up on App Launch**: Automatically launches whenever you open ScreenKit — even if you previously closed the floating bubble, returning to the app brings it right back.
- **Balanced, Unobtrusive Design**: Sleek `42dp` circular floating icon with a crisp `34dp` inner icon and no bulky border frame when collapsed, expanding into a compact 2×6 quick-action menu (`Full`, `Crop SS`, `Screen`, `Crop Rec`, `Camera`, `Mute/Mic/Device/Mix`, `Brush`, `Touch`, `Lens`, `Flip`, `Bypass`, `Home`).
- **Messenger-Style Drag-to-Bottom Close**: Drag the floating bubble down into the bottom `✕` target (with magnetic snap-in feedback) to close it, or release anywhere else to smoothly snap to the nearest screen edge.

### 2. Auto-Hiding Overlay for Clean Screenshots
- **No Bubble in Screenshots**: Immediately upon tapping Full or Crop Screenshot, ScreenKit hides the floating bubble, touch ripples, and brush toolbars (`View.GONE`) before the display frame is captured, restoring visibility immediately after capture.
- **Full-Resolution Capture**: Clean, untouched PNG output saved directly to your chosen destination.

### 3. Screen Brush & Touch Feedback on Floating Window
- **On-Screen Canvas Annotation (`✎ Brush`)**: Toggle the drawing tool directly from the floating bubble or main app. Includes 7 vibrant colors, a **`👆 Tap App` / `✎ Draw`** pass-through toggle (so you can interact with the underlying app without losing your drawings), eraser, undo, and clear.
- **Show Touches During Recording (`⊙ Touch`)**: Toggle touch visualization directly from the floating bubble or main app (`TouchFeedbackOverlay`). Renders smooth animated touch circles and pointer trails during screen recording without interrupting your session.

### 4. Amplified Sound Recording & Flexible Camera Overlay
- **4.0× (+12 dB) Amplified Microphone Engine**: Built-in DSP pre-amplifier with a smooth rational soft-knee limiter, hardware `AutomaticGainControl` (AGC), and `NoiseSuppressor` boosts voiceover clarity without digital clipping.
- **Synchronized 48 kHz AAC Audio Modes** (cycle live on the floating window or in the main app):
  - **Mute / Silent** (`Off`): Video only.
  - **Microphone** (`Mic`): 4.0× amplified voiceover & room commentary.
  - **Device Audio** (`Device`): Internal app and game sound via Android `AudioPlaybackCapture`.
  - **Mixed Audio** (`Mix` — `Mic + Device`): Simultaneous 4.0× amplified microphone commentary and internal device sound mixed in real time at 48 kHz into AAC (`128 kbps`).
- **Compact Live Camera Lens (`116dp × 154dp`)**: Launch a draggable live CameraX preview overlay anywhere on screen before or during screen recording, with one-tap front/back camera flip (`⇄ Flip`).

### 5. Black-Screen Bypass & Protected Site Capture
- **Hardware OpenGL ES (`GL_TEXTURE_EXTERNAL_OES`) Pipeline**: Routes screen recording and screenshots through GPU OES textures (`GRALLOC_USAGE_HW_TEXTURE`), uses resilient public/presentation `VirtualDisplay` flags, automatically skips initial black compositor warmup frames, and provides a hardware framebuffer (`screencap -p`) fallback.
- **Built-in Web Capture Bypass Browser (`🌐 Bypass`)**: Open websites that turn black in external browsers directly via the `🌐 Bypass` button on the floating bubble, the main app, or Android's Share menu (`ScreenKit Bypass Capture`):
  - Strips `FLAG_SECURE` on the window.
  - Injects JS hooks to block `blur`/`visibilitychange` anti-capture blackouts, downgrade `HW_SECURE` EME/DRM robustness to software (`SW_SECURE_CRYPTO`), and preserve WebGL drawing buffers (`preserveDrawingBuffer: true`).
  - Includes one-tap **Direct Hardware `PixelCopy` Screenshot (`📸 Direct Shot`)** that captures the webpage directly from hardware memory without needing `MediaProjection`.

### 6. Crop Area Recording & Cropped Screenshots
- **Area Selection Overlay**: Interactive bounding box selection to specify any sub-region of the screen.
- **Real-Time OpenGL ES Cropping**: Cropped screen recording utilizes an internal EGL texture and OpenGL ES external OES shader pipeline (`CropSurfaceRenderer`), rendering only the specified coordinates directly into the hardware H.264 `MediaCodec` input surface.
- **Crop Screenshots**: Saves only the selected sub-rectangle with zero padding.

### 7. Custom Storage & Privacy
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
│    FloatingOverlayService    │  │  BrushOverlay & TouchOverlay │  │      WebCaptureActivity      │
│ - 42dp Messenger-Style Bubble│  │ - Interactive Drawing Canvas │  │ - Anti-Black-Screen WebView  │
│ - Drag-to-Bottom (✕) Close   │  │ - Pass-Through Tap Toggle    │  │ - JS/EME/WebGL Bypass Hooks  │
│ - Auto-Hide on Screenshot    │  │ - Live Touch Ripple Feedback │  │ - Direct PixelCopy Capture   │
└──────────────┬───────────────┘  └──────────────────────────────┘  └──────────────────────────────┘
               │
               ▼
┌──────────────────────────────┐  ┌──────────────────────────────┐
│    CaptureRequestActivity    │  │     CameraCaptureService     │
│ - Transparent Permission Hop │◄─┤ - CameraX Video & Preview    │
│ - AreaSelectionOverlay Crop  │  │ - Compact 116x154dp Window   │
└──────────────┬───────────────┘  └──────────────────────────────┘
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
