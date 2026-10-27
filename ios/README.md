# ScreenKit for iPhone and iPad — 1.0.0 (build 1)

Native SwiftUI app, iOS **17.0+**, with a ReplayKit Broadcast Upload extension.
This is a separate implementation, not an APK conversion. Android remains at 2.1.0.

## Implemented

- **Record:** ReplayKit in-app recording with optional microphone, timer, and MP4 output
  in the app's local library. It records **ScreenKit only**, not other apps.
- **Library:** import selected movies from Photos (system picker; no full-library grant)
  or Files, share, delete, and optionally save exports to Photos with add-only consent.
- **Studio:** preview, trim/re-encode MP4, extract PNG frames, and export GIFs
  (up to 15 seconds, 10 fps, 480 px). All processing stays on device.
- **Go Live:** user-approved screen broadcasting, including other apps, to one
  RTMP/RTMPS destination through the bundled ReplayKit extension. Editable YouTube,
  Facebook and custom presets; 480p/720p, 24/30 fps, 500–6000 kbps; device/mic/mixed
  audio selection; mute/end commands, connection status and elapsed time.
- Rotation and resizing before encoding, bounded sample queues and pixel-buffer pool,
  sanitized errors, no payload logging, timeout/disconnection cleanup, no auto-reconnect.

The first iOS release does **not** claim complete Android parity. No cross-app auto
clicker/macros, floating windows over other apps, color triggers, protected-content
bypass, permission bypass, or system-wide automatic scrolling. No simultaneous local
recording while broadcasting, no pause/resume for local MP4s, and no camera capture UI.
Use iOS Control Center recording for other-app MP4s, then import them into ScreenKit.

## Open and build on a Mac

Requirements: Xcode **16+** (Swift 6 compiler), an iOS 17+ SDK/device, and XcodeGen 2.42+.
The app source uses Swift 5 language mode; the pinned transport requires Swift 6 tools.

```sh
brew install xcodegen
./ios/scripts/generate.sh
open ios/ScreenKit.xcodeproj
```

Project settings live in `project.yml`; the generated `.xcodeproj` is ignored. Do not
edit the generated project as the only source of a change. Dependencies are pinned to
HaishinKit 2.1.0 and Logboard 2.5.0. The BSD-3-Clause licenses are bundled in the app.

### Signing and installation (required for real iPhones)

1. Choose your Apple development team for **both** targets in Xcode, or set
   `DEVELOPMENT_TEAM` in your local XcodeGen settings.
2. Register your own unique app ID and extension ID, e.g. `com.yourname.screenkit`
   and `com.yourname.screenkit.broadcast`. Keep the extension's bundle ID under the app's.
3. Update `PRODUCT_BUNDLE_IDENTIFIER`, `SCREENKIT_BROADCAST_ID`,
   `SCREENKIT_APP_GROUP`, and `SCREENKIT_KEYCHAIN_GROUP` in `project.yml` to your IDs.
4. Enable the **same App Group** and **Keychain Sharing access group** on both targets
   and their provisioning profiles. Keep `$(AppIdentifierPrefix)` in the Keychain group.
5. Regenerate, select an iPhone/iPad, build and run. For TestFlight/App Store, archive,
   sign and distribute with your Apple Developer account.

**There is no universal unsigned IPA download that installs like an Android APK.**
Full broadcast provisioning uses App Groups/Keychain Sharing and requires a team that
supports those capabilities (normally paid Apple Developer Program membership).
Free personal-team signing is not a replacement for the full extension setup.
No signing certificates, profiles, passwords, or Apple account credentials belong in Git.

## Use Go Live

1. Create a private/unlisted test broadcast on your streaming platform.
2. Paste its server URL and key separately into ScreenKit. RTMPS is preferred.
3. Choose quality/audio and **Prepare broadcast** after reading the privacy warning.
4. Tap Apple's broadcast icon, choose **ScreenKit Live**, enable its microphone if
   your audio mode uses it, and explicitly tap **Start Broadcast**.
5. Check the platform preview. A connected encoder does not mean the stream is public;
   the platform may require its own final **Go Live** confirmation.
6. Stop through iOS's recording indicator/Control Center or ScreenKit's End button.
   If ScreenKit cannot reach the extension, use the system indicator immediately.

Do not put stream keys into screenshots, issues, repository files, or console output.
Unlike Android's same-process implementation, iOS extensions cannot receive an
in-memory object from the app. The one-use key therefore goes into a shared Keychain
item using **WhenUnlockedThisDeviceOnly**, and is deleted on consumption/cancellation.
Configuration has a 5-minute validity window. Expired unused items are removed on the
next app refresh or extension attempt; expiry is not a guarantee of background deletion
while the app is terminated. Destination settings/control/status JSON is protected,
excluded from backup, and contains no stream key. Recordings themselves may be backed up.

The library uses Apple's secure networking for RTMPS; no trust-all callback is installed.
Transport log appenders discard messages, since server responses can contain keys.
Microphone approval is controlled by iOS. Device audio and DRM restrictions are controlled
by source apps and the OS. App/mixed audio relies on ReplayKit app-audio sample delivery;
test silent-source and muted-device behavior on your actual device and destination.

## Validation

```sh
swift test --package-path ios/ScreenKitCore
xcodebuild -project ios/ScreenKit.xcodeproj -scheme ScreenKit \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

The `iOS Build` workflow runs core tests and builds the app + extension for device
(unsigned) and simulator. Its simulator ZIP is for a Mac's iOS Simulator only; unsigned
device products are build-verification artifacts, **not installable iPhone downloads**.
ReplayKit broadcasting, microphone/Photos consent, App Group provisioning, extension
memory limits, and real streaming require a signed physical-device test. See TESTING.md.
