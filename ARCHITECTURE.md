# HandyCam - Refactored Architecture

## Overview
This app has been completely rebuilt following **Modern Android Development (MAD)** principles with clean architecture, MVVM pattern, and proper dependency injection.

## Architecture

### Layers

#### 1. **Presentation Layer** (`presentation/`)
- **Activities**: Thin UI controllers that observe ViewModels
  - `MainActivityNew.kt` - Main screen for configuring and starting streams
  - `CameraControlActivityNew.kt` - Camera preview and control screen
- **ViewModels**: Manage UI state and coordinate business logic
  - `MainViewModel.kt` - Handles streaming controls and settings
  - `CameraControlViewModel.kt` - Manages camera controls and preview

#### 2. **Domain Layer** (`domain/usecase/`)
Business logic encapsulated in use cases following Single Responsibility Principle:
- `StartStreamUseCase` - Start video streaming
- `StopStreamUseCase` - Stop video streaming
- `SwitchCameraUseCase` - Switch between cameras during streaming
- `GetAvailableCamerasUseCase` - Retrieve available cameras
- `ToggleTorchUseCase` - Toggle camera flash/torch

#### 3. **Data Layer** (`data/`)
- **Models** (`data/model/`)
  - `AppSettings` - Type-safe app settings
  - `StreamConfig` - Video streaming configuration
  - `CameraInfo` - Camera device metadata
  
- **Repositories** (`data/repository/`)
  - `SettingsRepository` - Manages app preferences
  - `CameraRepository` - Handles camera hardware access
  - `StreamRepository` - Tracks streaming state
  
- **Preferences** (`data/preferences/`)
  - `PreferencesManager` - Centralized preferences using DataStore (replaces SharedPreferences)

#### 4. **Dependency Injection** (`di/`)
- `AppModule` - Hilt module providing dependencies
- `HandyCamApplication` - Application class with Hilt integration

## Key Improvements

### 1. **Centralized Preferences Management**
**Before:** SharedPreferences calls scattered across Activities and Services
```kotlin
// Old approach - scattered everywhere
val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
prefs.edit().putString("camera", "back").apply()
```

**After:** Single source of truth with reactive updates
```kotlin
// New approach - centralized, reactive, type-safe
class PreferencesManager(context: Context) {
    val appSettingsFlow: Flow<AppSettings>
    suspend fun updateCamera(camera: String)
}
```

**Benefits:**
- ✅ Type-safe access to preferences
- ✅ Reactive updates via Kotlin Flow
- ✅ Settings automatically sync across all Activities
- ✅ No more hardcoded string keys
- ✅ Default values defined in one place

### 2. **MVVM Pattern**
**Before:** Business logic in Activities
```kotlin
// Old - direct service calls from Activity
val intent = Intent(this, StreamService::class.java)
startService(intent)
```

**After:** ViewModels handle all business logic
```kotlin
// New - ViewModel coordinates everything
viewModel.startStreaming(host, port, width, height, camera, jpegQuality, fps, useAvc)
```

**Benefits:**
- ✅ Testable business logic
- ✅ Survives configuration changes
- ✅ Clear separation of concerns
- ✅ Activities only handle UI updates

### 3. **Dependency Injection with Hilt**
**Before:** Manual dependency creation
```kotlin
// Old - manually creating dependencies
val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
```

**After:** Automatic dependency injection
```kotlin
// New - dependencies injected automatically
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val startStreamUseCase: StartStreamUseCase
) : ViewModel()
```

**Benefits:**
- ✅ Easy to test (can inject mocks)
- ✅ Singleton management handled automatically
- ✅ Reduced boilerplate
- ✅ Clear dependency graph

### 4. **Reactive State Management**
**Before:** Manual state tracking and broadcasts
```kotlin
// Old - broadcast receivers for state updates
registerReceiver(streamStateReceiver, IntentFilter("com.example.handycam.STREAM_STATE"))
```

**After:** Reactive Flows with automatic updates
```kotlin
// New - observe state changes with Flow
viewModel.streamState.collect { state ->
    updateUI(state)
}
```

**Benefits:**
- ✅ Automatic UI updates when state changes
- ✅ Lifecycle-aware (no memory leaks)
- ✅ Thread-safe state management
- ✅ No more broadcast receivers for internal state

### 5. **Clean Architecture with Use Cases**
**Before:** Business logic mixed with UI and data access
```kotlin
// Old - everything in one place
fun startStream() {
    val prefs = getSharedPreferences(...)
    prefs.edit().putBoolean("isStreaming", true).apply()
    startService(intent)
    // validation, error handling all mixed together
}
```

**After:** Dedicated use cases for each operation
```kotlin
// New - single responsibility, easy to test
class StartStreamUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val streamRepository: StreamRepository
) {
    suspend operator fun invoke(...): Result<Unit> {
        // Clean business logic
    }
}
```

**Benefits:**
- ✅ Each use case has one responsibility
- ✅ Easy to test in isolation
- ✅ Reusable across different screens
- ✅ Clear business logic separation

## Migration Guide

### Using the New Architecture

#### For Main Screen:
```kotlin
// In MainActivity, use MainActivityNew as reference
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Observe settings - they automatically sync everywhere!
        viewModel.appSettings.collect { settings ->
            updateUI(settings)
        }
    }
}
```

#### For Camera Control:
```kotlin
// In CameraControlActivity, use CameraControlActivityNew as reference
@AndroidEntryPoint
class CameraControlActivity : AppCompatActivity() {
    private val viewModel: CameraControlViewModel by viewModels()
    
    // Settings are automatically shared with MainActivity
    // No more manual SharedPreferences syncing!
}
```

### Accessing Settings Anywhere
```kotlin
// Inject SettingsRepository in any ViewModel
class MyViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    // Get current settings
    val settings = settingsRepository.appSettings
    
    // Update settings
    viewModelScope.launch {
        settingsRepository.updateCamera("front")
        // This change automatically propagates everywhere!
    }
}
```

## Testing

The new architecture is highly testable:

```kotlin
class MainViewModelTest {
    @Test
    fun `starting stream updates settings`() = runTest {
        // Arrange
        val mockRepository = mockk<SettingsRepository>()
        val viewModel = MainViewModel(mockRepository, ...)
        
        // Act
        viewModel.startStreaming(...)
        
        // Assert
        coVerify { mockRepository.updateStreamingState(true) }
    }
}
```

## File Structure

```
app/src/main/java/com/example/handycam/
├── HandyCamApplication.kt                    # Hilt application
├── data/
│   ├── model/
│   │   ├── AppSettings.kt                    # Settings data class
│   │   ├── StreamConfig.kt                   # Stream config data class
│   │   └── CameraInfo.kt                     # Camera info data class
│   ├── preferences/
│   │   └── PreferencesManager.kt             # DataStore manager
│   └── repository/
│       ├── CameraRepository.kt               # Camera operations
│       ├── SettingsRepository.kt             # Settings operations
│       └── StreamRepository.kt               # Stream state management
├── domain/usecase/
│   ├── StartStreamUseCase.kt                 # Start streaming logic
│   ├── StopStreamUseCase.kt                  # Stop streaming logic
│   ├── SwitchCameraUseCase.kt                # Switch camera logic
│   ├── GetAvailableCamerasUseCase.kt         # Get cameras logic
│   └── ToggleTorchUseCase.kt                 # Toggle torch logic
├── presentation/
│   ├── main/
│   │   ├── MainActivityNew.kt                # Refactored MainActivity
│   │   └── MainViewModel.kt                  # Main screen ViewModel
│   └── cameracontrol/
│       ├── CameraControlActivityNew.kt       # Refactored CameraControlActivity
│       └── CameraControlViewModel.kt         # Camera control ViewModel
└── di/
    └── AppModule.kt                          # Dependency injection module
```

## Dependencies Added

```kotlin
// Hilt for dependency injection
implementation("com.google.dagger:hilt-android:2.50")
kapt("com.google.dagger:hilt-android-compiler:2.50")

// DataStore for preferences
implementation("androidx.datastore:datastore-preferences:1.1.1")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

// Lifecycle components
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
implementation("androidx.activity:activity-ktx:1.9.3")
```

## Next Steps

1. **Replace old Activities**: Update AndroidManifest.xml to use new Activity classes
2. **Migrate StreamService**: Refactor service to use repositories instead of SharedPreferences
3. **Add tests**: Write unit tests for ViewModels and use cases
4. **Remove old code**: Delete old MainActivity and CameraControlActivity once migration is complete

## Benefits Summary

✅ **Settings sync automatically** across all screens
✅ **Type-safe** preferences (no more string keys)
✅ **Testable** architecture with dependency injection
✅ **Lifecycle-aware** (no memory leaks)
✅ **Modern Android** best practices
✅ **Maintainable** - each component has clear responsibility
✅ **Scalable** - easy to add new features

## Questions?

The new architecture follows official Android guidelines:
- [App Architecture Guide](https://developer.android.com/topic/architecture)
- [MVVM Pattern](https://developer.android.com/topic/libraries/architecture/viewmodel)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [Hilt](https://developer.android.com/training/dependency-injection/hilt-android)
