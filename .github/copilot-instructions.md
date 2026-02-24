# HandyCam – Copilot Instructions

HandyCam is an Android app (Kotlin) that turns a smartphone into a webcam compatible with the [Droidcam OBS Plugin](https://github.com/dev47apps/droidcam-obs-plugin). It captures camera frames and streams them over TCP to OBS on the host machine.

## Build & Test Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew testDebugUnitTest

# Run a single unit test class
./gradlew testDebugUnitTest --tests "com.example.handycam.ExampleUnitTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedDebugAndroidTest
```

## Architecture

Clean Architecture with three distinct layers. Single Activity + Jetpack Compose UI.

### Presentation layer
- `MainActivity` – Single `@AndroidEntryPoint` `ComponentActivity` with Compose `NavHost`
- `presentation/main/MainScreen.kt` – Compose settings UI (host/port/camera/codec/FPS/HTTPS controls)
- `presentation/main/MainViewModel.kt` – `@HiltViewModel`, delegates to use cases and services
- `presentation/cameracontrol/CameraControlScreen.kt` – Full-screen Compose camera preview with tap-to-focus, zoom slider, torch, exposure
- `presentation/cameracontrol/CameraControlViewModel.kt` – `@HiltViewModel`, controls CameraX via `CameraStateHolder`

### Domain layer
- `domain/usecase/` – `StartStreamUseCase`, `StopStreamUseCase`, `GetAvailableCamerasUseCase`, `SwitchCameraUseCase`, `ToggleTorchUseCase`

### Data layer
- `data/preferences/PreferencesManager` – DataStore-backed persistent settings (Hilt-injected)
- `data/repository/` – `SettingsRepository`, `StreamRepository`, `CameraRepository`
- `data/model/` – `AppSettings`, `CameraInfo`, `StreamConfig`
- `data/model/api/ApiModels.kt` – Serializable types for the HTTPS REST API

### Service infrastructure
- `service/StreamStateHolder` – `@Singleton` replacing the old `SettingsManager`. Holds all runtime state as `StateFlow`. **This is the single source of truth for in-memory state** shared between Activity, services, and REST API
- `service/CameraStateHolder` – `@Singleton` replacing the old `SharedSurfaceProvider`. Holds `CameraControl`, `CameraInfo`, and preview surface references bridging `StreamService` ↔ `CameraControlScreen`
- `di/AppModule` – provides `PreferencesManager`, `StreamStateHolder`, `CameraStateHolder`, all repositories

### Background services
- `StreamService` – `@AndroidEntryPoint LifecycleService` (type: `camera`). Opens TCP socket on port **4747** (Droidcam protocol). Injects `StreamStateHolder` and `CameraStateHolder`. Observes state changes via `lifecycleScope + StateFlow.collect`
- `KtorHttpsServerService` – `@AndroidEntryPoint LifecycleService` (type: `dataSync`). Runs Ktor/Netty HTTPS on port **8443**. Injects `StreamStateHolder` for REST API responses. Self-signed TLS cert via BouncyCastle stored in `filesDir/keystore.jks`
- `MdnsResponder` – Raw mDNS unicast responder for DroidCam OBS plugin auto-discovery

## Key Conventions

### State management
`StreamStateHolder` is the **only** runtime state store. Do not create new `SharedPreferences`, `LiveData`, or global `object`s. `PreferencesManager` (DataStore) handles persistent settings.

### Two streaming paths
Controlled by the `useAvc` flag in `StreamStateHolder`:
- **MJPEG** (default) – CameraX `ImageAnalysis` → JPEG → TCP socket framing
- **AVC/H.264** – Camera2 `MediaCodec` surface → encoder → TCP. `avcBitrate == -1` means auto

### Camera bridge pattern
`CameraStateHolder` is set by `StreamService` after the camera opens (`cameraControl`, `cameraInfo`). `CameraControlScreen` reads `cameraStateHolder.previewSurfaceProvider` and writes it when attaching the `PreviewView`. The ViewModel mediates via `setPreviewSurfaceProvider()`.

### Dependency injection
All components are Hilt-injected. Services use `@AndroidEntryPoint`. ViewModels use `@HiltViewModel`. Never use manual singletons.

### REST API (HTTPS server on port 8443)
Routes defined in `KtorHttpsServerService.configureRouting()`. All reads from `streamStateHolder.xxx.value`, all writes via `streamStateHolder.setXxx()`. Camera control web UI served from `assets/camera_control.html`.

### Build config
- `compileSdk 36`, `minSdk 29` (Android 10+), `targetSdk 36`
- Full Compose UI — no XML layouts, no ViewBinding
- `@Serializable` via kotlinx.serialization (used in Ktor and `data/model/api/`)
