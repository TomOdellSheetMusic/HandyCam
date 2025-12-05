# HandyCam Migration Checklist

## Completed ✅

### Architecture Setup
- [x] Added modern dependencies (Hilt, DataStore, Coroutines, ViewModel, Navigation)
- [x] Created data models (AppSettings, StreamConfig, CameraInfo)
- [x] Implemented PreferencesManager with DataStore (replaces SharedPreferences)
- [x] Created repository layer (CameraRepository, SettingsRepository, StreamRepository)
- [x] Implemented domain use cases (StartStream, StopStream, SwitchCamera, etc.)
- [x] Built ViewModels (MainViewModel, CameraControlViewModel)
- [x] Created new Activity implementations (MainActivityNew, CameraControlActivityNew)
- [x] Set up Hilt dependency injection (HandyCamApplication, AppModule)
- [x] Documented new architecture in ARCHITECTURE.md

## To Complete 🚧

### 1. StreamService Refactoring
The StreamService still uses SharedPreferences directly. Update it to use repositories:

**Files to update:**
- `StreamService.kt`

**Changes needed:**
```kotlin
// Replace direct SharedPreferences access:
val prefs = getSharedPreferences("handy_prefs", Context.MODE_PRIVATE)
prefs.edit().putBoolean("isStreaming", true).apply()

// With repository injection (requires adding @AndroidEntryPoint to service):
@Inject lateinit var settingsRepository: SettingsRepository
lifecycleScope.launch {
    settingsRepository.updateStreamingState(true)
}
```

### 2. Switch to New Activities
Update AndroidManifest.xml to use the new Activity classes:

```xml
<!-- Replace: -->
<activity android:name=".MainActivity" />
<activity android:name=".CameraControlActivity" />

<!-- With: -->
<activity android:name=".presentation.main.MainActivityNew" />
<activity android:name=".presentation.cameracontrol.CameraControlActivityNew" />
```

### 3. Update Fragment References
If MainActivity and CameraControlActivity are referenced elsewhere:
- Update all `Intent` creations to use new Activity classes
- Update navigation code
- Update any explicit class references

### 4. Clean Up Old Code
Once migration is verified:
- Delete old `MainActivity.kt`
- Delete old `CameraControlActivity.kt`
- Delete `CameraBridge.kt` if no longer needed
- Rename `MainActivityNew.kt` to `MainActivity.kt`
- Rename `CameraControlActivityNew.kt` to `CameraControlActivity.kt`
- Update AndroidManifest.xml references

### 5. Testing Checklist
Test all functionality with new architecture:

#### Main Screen Tests
- [ ] Settings load correctly from DataStore
- [ ] Host/port can be edited and saved
- [ ] Resolution can be edited and saved
- [ ] FPS selection is saved
- [ ] Camera list populates correctly
- [ ] Camera selection works (both while idle and streaming)
- [ ] MJPEG quality setting works
- [ ] AVC bitrate setting works
- [ ] Start server requests permissions correctly
- [ ] Start server launches StreamService
- [ ] Stop server stops StreamService
- [ ] Settings persist after app restart
- [ ] Settings sync between MainActivity and CameraControlActivity

#### Camera Control Tests
- [ ] Only opens when streaming is active
- [ ] Camera preview displays
- [ ] Camera spinner shows available cameras
- [ ] Switching camera works during streaming
- [ ] Flash toggle works
- [ ] Tap to focus works
- [ ] Exposure adjustment works
- [ ] Settings changes from MainActivity reflect immediately

#### Service Tests
- [ ] Service starts correctly with foreground notification
- [ ] MJPEG streaming works
- [ ] AVC streaming works
- [ ] Camera switching during stream works
- [ ] Service stops correctly
- [ ] Settings persist after service restarts

### 6. Optional Enhancements
Once basic migration is complete:

- [ ] Add loading states to ViewModels
- [ ] Implement proper error handling UI
- [ ] Add analytics/logging
- [ ] Implement data binding in layouts
- [ ] Add unit tests for ViewModels
- [ ] Add unit tests for use cases
- [ ] Add integration tests
- [ ] Migrate to Jetpack Compose (long-term)
- [ ] Add settings screen for advanced options
- [ ] Implement SettingsFragment using Preferences library

## Migration Instructions

### Step 1: Build the Project
```bash
./gradlew assembleDebug
```
This will ensure all new dependencies are downloaded and code compiles.

### Step 2: Test New Activities
Temporarily update AndroidManifest.xml to launch MainActivityNew:
```xml
<activity
    android:name=".presentation.main.MainActivityNew"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

### Step 3: Verify Functionality
Test all features with the new architecture. Use the testing checklist above.

### Step 4: Update Service
Inject repositories into StreamService and remove direct SharedPreferences access.

### Step 5: Complete Migration
Once everything works, delete old files and rename new ones.

## Rollback Plan

If issues occur, the old implementation is preserved:
- Old MainActivity still exists
- Old CameraControlActivity still exists
- Old SharedPreferences logic still works
- Can revert AndroidManifest.xml changes

## Key Benefits of New Architecture

1. **Settings Sync**: Changes in MainActivity automatically reflect in CameraControlActivity
2. **Type Safety**: No more string keys, compile-time checking
3. **Testability**: Can easily mock repositories and test ViewModels
4. **Maintainability**: Clear separation of concerns
5. **Modern**: Follows official Android best practices
6. **Scalability**: Easy to add new features

## Questions?

See `ARCHITECTURE.md` for detailed documentation of the new architecture.
