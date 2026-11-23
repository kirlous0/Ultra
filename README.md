# UltraCam — Computational Photography & Camera2 Pro Studio

UltraCam is a native Kotlin + Jetpack Compose + Camera2 computational photography application engineered specifically around the **Samsung Galaxy A05s (SM-A057 series, Snapdragon 680 4G, Adreno 610 GPU, 6GB RAM, Android 15)** while dynamically discovering all capabilities at runtime without hardcoding camera IDs, resolutions, ISO/shutter limits, or unsupported modes.

## Non-Negotiable Hardware Accuracy
- Discovers all physical and logical camera IDs via `CameraManager` + `CameraCharacteristics` + `MediaCodecList` at runtime.
- Never fabricates 4K video, optical zoom, OIS, 50MP RAW, or autofocus on fixed-focus cameras (such as the 13MP front lens).
- Digital zoom via `SCALER_CROP_REGION` is always labeled as digital sensor crop.
- Super-Resolution only performs `1.5x` subpixel Drizzle reconstruction when genuine burst micro-shift diversity and sharpness thresholds pass; otherwise it preserves and reports native sensor resolution.

## Architecture Modules
- `camera/CameraHardwareScanner.kt`: Full Camera2 + `CameraExtensionCharacteristics` + `MediaCodecList` capability discovery.
- `camera/CameraSessionManager.kt` & `CameraController.kt`: HandlerThread Camera2 session lifecycle (regular & constrained high-speed 120fps sessions), live YUV telemetry, sensors, and multi-frame orchestration.
- `camera/ManualCameraController.kt`: True manual ISO, shutter, focus distance, Kelvin/AWB, AE/AF lock, tap-to-focus metering, 50Hz Egypt anti-banding default.
- `camera/RawCaptureEngine.kt`: Authentic Adobe DNG capture via `DngCreator` (RAW-only and RAW+JPEG).
- `camera/PhotoCaptureEngine.kt`: Single-shot, burst, exposure bracketing (AEB), intervalometer, self-timer, portrait bokeh, document perspective scan, and horizontal panorama stitching.
- `processing/`:
  - `ImageAlignmentEngine.kt` (subpixel coarse-to-fine block alignment + sharpness + tripod/shake detection)
  - `HDRProcessor.kt` (Mertens exposure fusion + moving-object ghost rejection)
  - `NightProcessor.kt` (smart frame selection + Huber temporal merge + chroma denoise)
  - `SuperResolutionProcessor.kt` (1.5x subpixel Drizzle with honest native fallback)
  - `DetailAndMotionEngines.kt` (`DetailEngine`, `MotionEngine`, `PortraitProcessor`, `PanoramaEngine`, `DocumentScannerEngine`)
  - `GlImageProcessor.kt` (OpenGL ES 2.0 Adreno 610 offscreen shader grading + CPU fallback)
  - `AIPhotographyEngine.kt` (100% offline 10-scene classifier & exposure preset advisor)
  - `QrScannerEngine.kt` (offline ML Kit barcode/QR scanner)
  - `ImageEditorEngine.kt` (non-destructive Studio editor & recipe persistence)
- `video/VideoRecordingEngine.kt`: `MediaCodec` (H.264/HEVC) + `AudioRecord` + `MediaMuxer` with bitrate slider, EIS, live audio meter, 720p120 slow-motion timestamp scaling, and time-lapse.
- `system/`: `DiagnosticsManager` (JSON export), `ThermalMonitor` (thermal + battery + 6GB RAM adaptive budgets), `StorageManager` (MediaStore + SAF SD card), `GalleryManager` (EXIF inspector + RAW/JPEG pairing), `SettingsManager` (DataStore), `LocaleManager` (Arabic default + English with RTL/LTR).

## Build & Test Instructions
1. Open the project in Android Studio (AGP 9.x / Kotlin 2.x / Compile SDK 36).
2. Run unit & Robolectric tests:
   ```bash
   gradle :app:testDebugUnitTest
   ```
3. Build Debug APK:
   ```bash
   gradle :app:assembleDebug
   ```
4. Build Release APK (requires signing environment variables `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`):
   ```bash
   gradle :app:assembleRelease
   ```
5. On a physical Galaxy A05s (SM-A057), open **Diagnostics** first to inspect and export the device's real `ultracam.diagnostics.v1` JSON report before running field validation across all discovered lenses, resolutions, RAW DNG, 1080p60/30, and 720p120 slow-motion modes.
