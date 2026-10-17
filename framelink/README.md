# FrameLink (Android)

An independent, native Android remote for compatible GoPro cameras. FrameLink is **not affiliated with GoPro**. It uses BLE and GoPro's local Wi-Fi HTTP APIs; no login or cloud service is required.

## Features in this first build
- Discover and connect to nearby GoPro cameras over BLE while the camera is in pairing mode.
- Remote shutter start/stop, with automatic stop timer.
- Timed shutter pulses for photo/time-lapse workflows (put the camera in Photo mode first).
- Request camera Wi-Fi and connect through Android's system Wi-Fi approval prompt.
- Automatically read the GoPro SSID/password where supported, with manual fallback.
- Browse actual media from the camera over local Wi-Fi, preview thumbnails, and download files into Download/FrameLink.
- Offline, lossless video clip trimming using Android's MediaExtractor + MediaMuxer.
- Native, dark UI and local-only operation.

## Compatibility notes
- Open GoPro over BLE and HTTP is primarily intended for supported newer HERO cameras (generally HERO9 Black and later). Older cameras and firmware may require legacy HTTP endpoints and may not expose BLE controls.
- Camera should be in **Connections > Connect Device > GoPro Quik** (wording varies) when searching.
- The shutter operates on the camera's **currently selected preset**. The app does not silently change video/photo mode.
- Some cameras require enabling Wi-Fi from their own menu. If SSID/password are not exposed via BLE, type them on the Connection screen.
- Connecting Wi-Fi needs Android user approval and may temporarily disconnect the phone from Internet.
- Live moving preview, AI auto-editing, cloud backup, and auto-sync are *not* implemented. No placeholder video or fabricated camera data is shown.
- Timers function while the application remains open. Android background execution can interrupt them.
- Clip trimming copies samples rather than re-encoding, and aligns to codec keyframes.

## Build
Requires Java 17, Android SDK platform 35, Gradle 8.9, and Android Gradle Plugin 8.7.3.

From this subdirectory:
    gradle :app:assembleDebug

APK:
    app/build/outputs/apk/debug/app-debug.apk

The isolated GitHub Actions workflow in the Ye repository builds the APK as an artifact on pushes to the \`agent/framelink-android\` branch.

## Security
- Requests Bluetooth/Location at runtime as Android requires.
- No accounts, telemetry, analytics, or remote servers.
- Cleartext HTTP is needed only for the camera's local 10.5.5.9 network.
- Camera Wi-Fi credentials remain in memory and are not written to preferences.
