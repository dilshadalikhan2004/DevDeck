# Task 7: Model Settings UI (Layouts, Activity, Adapter)

## Requirements

Create the complete Model Manager UI screen with RecyclerView for model selection, verification buttons, and custom path input. This is the most complex UI task with 5 files to create/modify.

## Files to Create/Modify

- Create: `app/src/main/res/layout/activity_model_settings.xml`
- Create: `app/src/main/res/layout/item_model_card.xml`
- Create: `app/src/main/java/com/devdeck/app/ui/ModelSettingsActivity.kt`
- Create: `app/src/main/java/com/devdeck/app/ui/ModelListAdapter.kt`
- Modify: `app/src/main/AndroidManifest.xml`

## Interface Contract

**Consumes (from Task 6):**
- `ModelManager` class with all methods
- `ModelConfig` data class
- `ModelTier` enum

**Produces:**
- Full-screen Model Manager UI
- RecyclerView with model cards
- "Use This" button to switch models
- "Verify" button to run canary test
- "Set Custom Model Path" button for user-provided paths

## Global Constraints

- Android Target SDK: 34
- Android Min SDK: 26
- Design must match existing DevDeck visual language:
  - Card radius: 14dp
  - Border: 1dp stroke with color_border
  - Teal accents (#0B8A78) for active/success states
  - Mono font for badges and metadata
- ViewBinding must be used (no findViewById)

## Implementation Details

**activity_model_settings.xml:**
- LinearLayout vertical root
- Header with Back button + "Model Manager" title
- RecyclerView (id: modelRecyclerView) with weight=1
- ProgressBar (id: progressBar, initially gone) for verification
- "Set Custom Model Path" button at bottom

**item_model_card.xml:**
- MaterialCardView with 14dp corner radius
- Model name (TextView: modelName)
- Active badge (TextView: activeBadge, "ACTIVE", initially gone)
- Description (TextView: modelDescription)
- Tier badge (TextView: tierBadge, "FAST" or "ADVANCED")
- Metadata line (TextView: modelMeta, shows "X.XGB • XX tok/s • specialty")
- Two buttons: "Use This" (btnSelect) and "Verify" (btnVerify)

**ModelListAdapter.kt:**
- RecyclerView.Adapter with ViewBinding
- Binds ModelConfig to card views
- Sets activeBadge visibility based on model.isActive
- Tier badge color: FAST = teal (#0B8A78), ADVANCED = blue (#3B6FD1)
- Click listeners call lambda callbacks: onModelSelected, onVerifyClicked
- updateModels() method to refresh list after changes

**ModelSettingsActivity.kt:**
- Uses ViewBinding: ActivityModelSettingsBinding
- onCreate: setup RecyclerView, custom path button, back button
- selectModel(): checks file exists, calls modelManager.setModelPath(), shows toast, refreshes list
- verifyModel(): shows progressBar, launches coroutine, calls modelManager.verifyModel(), shows result dialog
- Verification success dialog offers "Use This" button
- Verification failure dialog shows error message
- Custom path dialog: EditText with current path, saves on "Set" button

**AndroidManifest.xml modification:**
- Add `<activity>` entry inside `<application>` block:
  ```xml
  <activity
      android:name=".ui.ModelSettingsActivity"
      android:label="Model Manager"
      android:parentActivityName=".ui.MainActivity" />
  ```

## Testing

**Gradle build verification:**
- Run: `./gradlew assembleDebug`
- Expected: BUILD SUCCESSFUL
- All ViewBinding classes generated correctly
- No layout inflation errors

## Success Criteria

1. Gradle build succeeds
2. All layouts valid XML with correct IDs
3. Activity compiles with lifecycleScope coroutine support
4. Adapter properly binds ModelConfig to views
5. ViewBinding correctly references all UI elements
6. AndroidManifest.xml valid after adding activity entry
