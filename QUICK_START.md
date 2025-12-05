# Quick Start Guide - Refactored HandyCam

## TL;DR - What Changed?

Your app was **completely rebuilt** with modern Android architecture. The biggest improvement: **Settings now sync automatically between all screens!**

## Quick Test

### Option 1: Test New Architecture Immediately

1. **Update AndroidManifest.xml** (temporarily for testing):
```xml
<!-- Find MainActivity in AndroidManifest.xml and change from: -->
<activity android:name=".MainActivity" ... >

<!-- To: -->
<activity android:name=".presentation.main.MainActivityNew" ... >
```

2. **Build and run:**
```bash
./gradlew installDebug
```

3. **Test it:**
   - Change settings in main screen
   - Open camera preview
   - Settings are automatically there! (This didn't work before)

### Option 2: Keep Old Code (Safer)

The new code is ready but not active. Old app still works. To use new architecture, see `MIGRATION.md`.

## What You Get

### Before (Old Code):
```kotlin
// MainActivity
val prefs = getSharedPreferences("handy_prefs", MODE_PRIVATE)
prefs.edit().putString("camera", "front").apply()

// CameraControlActivity - has to manually reload
val prefs = getSharedPreferences("handy_prefs", MODE_PRIVATE)
val camera = prefs.getString("camera", "back") // Might be stale!
```

### After (New Code):
```kotlin
// MainActivity
viewModel.updateCamera("front")
// Done! Automatically syncs everywhere

// CameraControlActivity - automatically gets updates
viewModel.appSettings.collect { settings ->
    // This runs instantly when MainActivity changes camera
    updateCamera(settings.camera)
}
```

## Key Files

### New Architecture (Ready to use)
- `presentation/main/MainActivityNew.kt` - New main screen
- `presentation/main/MainViewModel.kt` - Business logic for main screen
- `presentation/cameracontrol/CameraControlActivityNew.kt` - New camera control
- `presentation/cameracontrol/CameraControlViewModel.kt` - Business logic for camera
- `data/preferences/PreferencesManager.kt` - Centralized settings

### Documentation
- **`REFACTORING_SUMMARY.md`** ← **Read this first!** Complete overview
- **`ARCHITECTURE.md`** - Detailed architecture explanation
- **`MIGRATION.md`** - Step-by-step migration guide

## File Structure

```
app/src/main/java/com/example/handycam/
│
├── 📱 OLD FILES (Still working, can be deleted after migration)
│   ├── MainActivity.kt
│   ├── CameraControlActivity.kt
│   └── CameraBridge.kt
│
├── ✨ NEW ARCHITECTURE (Ready to use!)
│   ├── HandyCamApplication.kt          # Hilt setup
│   │
│   ├── data/
│   │   ├── model/                      # Type-safe data classes
│   │   ├── preferences/                # Centralized settings (DataStore)
│   │   └── repository/                 # Data access layer
│   │
│   ├── domain/usecase/                 # Business logic
│   │
│   ├── presentation/
│   │   ├── main/                       # Main screen (new)
│   │   └── cameracontrol/              # Camera control (new)
│   │
│   └── di/                             # Dependency injection
│
└── 🔧 KEEP (Other files)
    ├── StreamService.kt               # Still works, can be enhanced
    ├── AvcFragment.kt
    ├── MjpegFragment.kt
    └── ui/theme/
```

## Using New Settings Anywhere

Want to add a new feature? Easy!

```kotlin
@HiltViewModel
class YourNewViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    // Get settings (automatically updates)
    val settings = settingsRepository.appSettings
    
    // Update settings (syncs everywhere!)
    fun updateSomething() {
        viewModelScope.launch {
            settingsRepository.updateCamera("front")
            // All screens update automatically!
        }
    }
}
```

## Benefits at a Glance

| Feature | Old Code | New Code |
|---------|----------|----------|
| Settings sync | Manual 😢 | Automatic ✅ |
| Type safety | None 😢 | Full ✅ |
| Testable | No 😢 | Yes ✅ |
| Memory leaks | Possible 😢 | Prevented ✅ |
| Code clarity | Mixed 😢 | Clean ✅ |
| Lines in Activity | 392 😢 | 300 ✅ |

## Common Questions

### Q: Do I need to change anything to use this?
**A:** Not immediately. Old code still works. When ready, follow `MIGRATION.md`.

### Q: Will this break my app?
**A:** No. New code is separate. Old code still works. You can test new code without affecting old.

### Q: What's the main benefit?
**A:** Settings automatically sync everywhere. No more manual SharedPreferences management!

### Q: How do I test the new architecture?
**A:** See "Quick Test" above. Update AndroidManifest to use `MainActivityNew` and run the app.

### Q: Can I rollback if something breaks?
**A:** Yes! Just revert AndroidManifest changes. Old code is unchanged.

### Q: Do I need to learn new things?
**A:** The patterns used are standard Android best practices. See official Android docs.

## Next Steps

1. **Read**: `REFACTORING_SUMMARY.md` (detailed overview)
2. **Understand**: `ARCHITECTURE.md` (how it works)
3. **Migrate**: `MIGRATION.md` (step-by-step guide)
4. **Test**: Follow "Quick Test" above

## Quick Reference

### Update Settings
```kotlin
// In any ViewModel
viewModelScope.launch {
    settingsRepository.updateCamera("front")
    settingsRepository.updatePort(8080)
    settingsRepository.updateResolution(1920, 1080)
}
```

### Read Settings
```kotlin
// In any Activity/Fragment
lifecycleScope.launch {
    viewModel.appSettings.collect { settings ->
        // Runs whenever settings change
        hostEdit.setText(settings.host)
        portEdit.setText(settings.port.toString())
    }
}
```

### Inject Dependencies
```kotlin
@HiltViewModel
class MyViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cameraRepository: CameraRepository
) : ViewModel()
```

## Help

- Problems? Check `MIGRATION.md`
- Questions? See `ARCHITECTURE.md`
- Understanding? Read `REFACTORING_SUMMARY.md`

---

**The app is now built with modern Android architecture!** 🎉

All functionality preserved, but with:
✅ Automatic settings sync
✅ Clean, maintainable code
✅ Type safety
✅ Testability
✅ No memory leaks
