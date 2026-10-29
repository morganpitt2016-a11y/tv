# Minimal TV Launcher

A bare-bones Android TV launcher: full-screen clock + date, and a plain
grid of your installed apps. No ads row, no recommendations shelf, no
search bar — because none of that code exists in it.

## What this actually is

This is a real Android app that declares itself as a `HOME` /
`LEANBACK_LAUNCHER` candidate. Once installed, your Chromecast will ask
you (or let you choose in Settings) whether to use this as the default
home screen instead of the stock Google TV launcher. It does **not**
replace the underlying Android TV OS — it just replaces the home screen
app, which is exactly what "make it look like Apple TV" actually means
in practice.

## Build it (two options)

### Option A — Android Studio (easiest)
1. Install [Android Studio](https://developer.android.com/studio).
2. Open this folder as a project (`File > Open`, select the
   `MinimalTVLauncher` folder).
3. Let Gradle sync (it'll download the Android TV / Leanback libraries
   automatically — needs internet access).
4. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
5. The APK lands in
   `app/build/outputs/apk/debug/app-debug.apk`.

### Option B — command line (if you don't want Studio)
From the project root, with the Android SDK + command-line tools
installed and `ANDROID_HOME` set:
```
./gradlew assembleDebug
```
(You'll need to generate the Gradle wrapper first if it's missing:
`gradle wrapper` from inside the project, run once with a local Gradle
install.)

## Install it on your Chromecast

With ADB already connected (`adb connect <TV_IP>:5555` — same steps as
before):

```
adb install app-debug.apk
```

Then on the TV, go to:
**Settings → Apps → See all apps → Minimal Launcher → Set as default**
(exact wording varies by firmware — look for "open by default" /
"set as home app").

Or trigger the picker directly:
```
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```
This pops the "Select Home app" dialog if more than one HOME app is
installed — pick Minimal Launcher.

## Reverting back to stock

Same menu, just pick the original Google TV launcher instead, or:
```
adb uninstall com.morgan.minimallauncher
```

## What to customize next

- `MainActivity.kt` — clock format, date format, grid column count
  (`GridLayoutManager(this, 6)` — change `6` to however many columns
  you want)
- `activity_main.xml` — padding, font, background color/image
- `item_app.xml` / `focusable_item_bg.xml` — icon size, focus
  highlight style, rounded corners
- To add a background photo instead of flat black: add an `ImageView`
  behind everything in `activity_main.xml` pointing at a drawable, or
  load one dynamically from local storage

## What's new: background photo (Apple TV style)

Full-screen photo behind everything, with a slow zoom/pan ("Ken Burns"
effect) over the time it's shown, a dark gradient scrim so the clock/
app icons stay readable, and a new photo swapped in every 5 minutes
with a crossfade.

**Photo source right now**: `picsum.photos` — a free, keyless
placeholder-photo service. This gets the actual effect working
end-to-end without needing any account, API key, or photo library
hookup. It is **not** your own photos — it's random stock images, just
there to prove out the zoom/pan/crossfade mechanics.

**To use your own photos instead**, the only thing to change is
`fetchRandomPhoto()` in `BackgroundPhotoHelper.kt` — swap the
`picsum.photos` URL for:
- A Google Photos album (needs OAuth + the Photos Library API — more
  setup, real auth flow)
- A local folder on the TV's storage (no internet needed at all, just
  point `BitmapFactory.decodeFile()` at files under
  `/storage/emulated/0/...`)
- Any other image URL you control

Everything else — the crossfade, the Ken Burns animation, the 5-minute
rotation timer — stays exactly the same regardless of where the bytes
come from.

Change the rotation interval by editing `rotateEveryMs` in
`BackgroundPhotoHelper.kt`. Change how much it zooms by editing the
`1.12f` target scale in `showPhoto()`.

## What's new: weather + quick settings

- **Weather**: top-right of the screen. Uses two free, keyless APIs —
  `ip-api.com` guesses a rough location from the device's public IP
  (no GPS permission needed, which matters since most TV boxes don't
  have a GPS chip), then `open-meteo.com` turns that into a current
  temperature. It's city-level accurate, not pinpoint — fine for a
  home screen, not for anything precise.
- **Quick settings row** (Wi-Fi / Display / Apps / Power): these open
  real Android system settings screens via `Intent`, not fake buttons.
  One honest caveat: **Power doesn't actually power off the TV**. A
  normal app — including this one — isn't allowed to do that; shutting
  down or rebooting a device needs system/signature-level permissions
  that Android reserves for the OS itself. The Power button opens the
  closest settings screen a regular app can legally reach instead of
  pretending to do something it can't.

## Known limitations of this minimal version

- No app reordering/favorites yet — it just lists every launchable TV
  app alphabetically
- No background photo/ambient rotation (flat black background) —
  that's a next step if you want it
- Icons come from each app's own launcher icon, so visual consistency
  depends on how well those apps' icons are designed
- Weather needs an internet connection on first load; if both lookups
  fail (no connection, API down) it shows "Weather unavailable"
  instead of a temperature
