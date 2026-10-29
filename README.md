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

## Home screen

Modelled on an Apple-TV-style reference layout, on a fixed 960x540dp
stage (the 1920x1080 reference at 2x) that scales to fit any screen:

- **Wallpaper**: real mountain/lake photos from Unsplash (free under the
  Unsplash License), crossfading every 5 minutes with a slow zoom. The list
  of photo IDs is at the top of `WallpaperRotator.kt`. Photos are cached on
  the TV after the first download. Until the first photo loads (or with no
  internet) a bundled painted scene shows instead
  (`res/drawable-nodpi/wallpaper_*.webp`, made with `tools/wallpaper/`).
- **Profile selector** (top-left): Person 1 / Person 2 by default; add,
  rename or delete profiles in Settings. Each profile has its own dock apps.
- **Date and clock**: centred. The clock uses Roboto Flex tuned (width 72,
  weight 480) to match the tall numerals of Apple's SF Pro Condensed, which
  can't be bundled: Apple's licence only allows SF fonts in mock-ups of
  Apple-platform software. Follows the TV's 12/24-hour setting.
- **Night mode** (moon, top-right): dims the wallpaper.
- **Keep watching**: the two apps you most recently opened, with their TV
  banner as the thumbnail. It can't show real episode progress: Android
  doesn't let third-party launchers read other apps' playback state.
- **Dock**: up to 10 apps (your pinned apps, or the first 10 installed),
  then Settings and More (all apps). Real app icons, drawn full-bleed.
- **Govee lights**: once a Govee API key is saved in Settings, each device
  appears as a pill next to the moon button; select it to toggle on/off.

- **Open automatically**: Google TV doesn't let you change the home app, so
  `HomeRedirectService` (an accessibility service, the approach Projectivy
  uses) opens this launcher whenever the stock Google TV home appears: at
  power-on, on Home, and when leaving an app. Turn it on from Settings →
  "Open accessibility settings". If Android says "Restricted setting", allow
  restricted settings for the app first (App info, ⋮ menu).

Fonts: Roboto Flex, Oswald and Inter (SIL Open Font License). Icons: Lucide (ISC).
