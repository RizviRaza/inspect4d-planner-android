# Inspect4D Planner (Android)

Plan Mavic 3E mapping missions on the DJI RC Pro Enterprise and hand them to DJI Pilot 2.

## Build the APK

**Option A: GitHub (no Android Studio needed)**
1. Create a new private repository on github.com and upload this whole folder (keep the `.github` folder).
2. Open the repository's **Actions** tab. The "Build APK" run starts on every push; wait for the green check (about 5 minutes).
3. Open the run and download **inspect4d-planner-apk** under Artifacts. Unzip it to get `app-debug.apk`.

**Option B: Android Studio**
1. File → Open → select this folder. Let Gradle sync finish.
2. Build → Build App Bundle(s) / APK(s) → Build APK(s).
3. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the RC Pro Enterprise
1. Copy `app-debug.apk` to a microSD card and put it in the controller (or download it in the controller's browser).
2. Open the file manager on the controller, tap the APK, and allow installing from this source when asked.
3. Open **Inspect4D Planner** from the app list. Allow location when prompted so "My location" works.

## Field workflow
1. **Before leaving (with Wi-Fi or a phone hotspot):** open the site on the map, zoom in, and tap **Save this area** under Offline map.
2. **On site:** plan the grid or orbit, check it in **3D view**, tap **Save KMZ to controller**.
3. Files land in `Download/Inspect4D/`. In DJI Pilot 2: **Flight Route → Import Route (KMZ/KML)** and pick the file.
4. Review the route in Pilot 2 before flying. Your plan in Inspect4D is saved automatically, so you can switch apps freely.

## Project layout
- `app/src/main/assets/index.html`: the planner itself (same code as the desktop HTML, with libraries and fonts bundled for offline use)
- `app/src/main/java/.../MainActivity.kt`: hosts the planner, saves KMZ files, location permission
- `app/src/main/java/.../TileCache.kt`: stores map tiles on the controller for offline use

## Updating the planner
Replace `app/src/main/assets/index.html` with a new version, keeping the local `lib/` and `fonts/` links, bump `versionCode` in `app/build.gradle.kts`, and rebuild.
