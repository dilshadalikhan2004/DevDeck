# Task 8: Wire Model Settings Button in MainActivity

## Requirements

Add a click listener to the existing model status container in `MainActivity.kt` to launch the `ModelSettingsActivity` when tapped. This is the final integration task.

## Files to Modify

- Modify: `app/src/main/java/com/devdeck/app/ui/MainActivity.kt`

## Interface Contract

**Consumes (from Task 7):**
- `ModelSettingsActivity` class

**Produces:**
- Click listener on `binding.modelStatusContainer` that launches ModelSettingsActivity
- Uses standard Android Intent mechanism

## Global Constraints

- Must use vibrate() for haptic feedback (consistent with other buttons)
- Intent must be simple: no extras, just launch activity
- Activity will be in back stack (user can press back to return)

## Implementation Details

**Location:**
- Find `setupActionButtons()` function or `onCreate()` in MainActivity.kt
- Add after existing button setup code (around line 100-126)

**Code to add:**
```kotlin
binding.modelStatusContainer.setOnClickListener {
    vibrate()
    startActivity(Intent(this, ModelSettingsActivity::class.java))
}
```

**No import needed:**
- Intent already imported in MainActivity
- ModelSettingsActivity in same package, no explicit import required

**UI Element:**
- `modelStatusContainer` is the LinearLayout that contains:
  - `modelStatus` TextView ("LOCAL AI: READY")
  - `modelDot` colored indicator
- Already exists in activity_main.xml
- Currently displays model status but has no click behavior

## Testing

**Gradle build verification:**
- Run: `./gradlew assembleDebug`
- Expected: BUILD SUCCESSFUL
- No syntax errors

**Runtime verification (if device available):**
- Install APK: `./gradlew installDebug`
- Launch app
- Tap model status bar at top
- Expected: Model Manager screen opens

## Success Criteria

1. Gradle build succeeds
2. Code compiles without errors
3. Click listener correctly references existing UI element
4. Intent launches ModelSettingsActivity
5. Haptic feedback fires on tap (vibrate() call)
