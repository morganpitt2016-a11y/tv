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

- **Wallpaper**: 15 real mountain/lake photos bundled in the app, cross-fading
  every 5 minutes with a slow zoom (no internet needed). They're listed in
  `res/values/wallpapers.xml`; credits are below and in Settings. Five
  photos also have a land-only cut-out (`wp_XX_front.webp`, made by
  `tools/wallpaper/depth.py`) drawn above the clock, so peaks and trees
  pass in front of the digits.
- **Profile selector** (top-left): Person 1 / Person 2 by default; add,
  rename or delete profiles in Settings. Each profile has its own dock apps.
- **Date and clock**: centred. The clock uses Roboto Flex tuned (width 72,
  weight 480) to match the tall numerals of Apple's SF Pro Condensed, which
  can't be bundled: Apple's licence only allows SF fonts in mock-ups of
  Apple-platform software. Follows the TV's 12/24-hour setting.
- **Night mode** (moon, top-right): fades the wallpaper darker and back.
- **Quick settings** (toggle button, top-right): a Google-TV-style panel
  with Wi-Fi, sound, screensaver, next wallpaper, night mode, Claude,
  Govee lights, search/apps/display/settings shortcuts, and free RAM and
  storage.
- **Claude**: ask Claude (Opus 5.5) questions by typing or by voice, from
  quick settings or Settings → Claude. Uses the official Anthropic Java SDK
  with your own API key from console.anthropic.com (billed per question),
  with server-side fallbacks on, so a request declined by the safety
  filters is retried on another Claude model.
- **Keep watching**: the two apps you most recently opened, with their TV
  banner as the thumbnail. It can't show real episode progress: Android
  doesn't let third-party launchers read other apps' playback state.
- **Dock**: up to 10 apps (your pinned apps, or the first 10 installed),
  then Settings and More (all apps). Real app icons, drawn full-bleed.
- **Govee lights**: once a Govee API key is saved in Settings, each device
  appears as a pill next to the moon button; select it to toggle on/off.

- **Open automatically**: switches in Settings for (1) instead of the Google
  TV home, (2) when the TV turns on (`BootReceiver`), and (3) when it wakes
  from sleep. Google TV doesn't let you change the home app, so
  `HomeRedirectService` (an accessibility service, the approach Projectivy
  uses) opens this launcher whenever the stock Google TV home appears: at
  power-on, on Home, and when leaving an app. Turn it on from Settings →
  "Open accessibility settings". If Android says "Restricted setting", allow
  restricted settings for the app first (App info, ⋮ menu).

### Profiles, widgets and modes

- **Profiles**: each person has their own wallpaper, dock apps and order,
  favourites, watch history (Continue Watching) and recommendations (apps
  they tend to open at this time of day). Hold OK on any app to favourite
  it or move it in the dock.
- **Rails**: below the dock, Favourites / Recommended / Recently Watched /
  All Apps rails; the screen glides up and the wallpaper blurs behind them.
- **Widgets** (plain text on the wallpaper): weather (Open-Meteo), next
  calendar event (private iCal link), now playing (needs Notification
  Access) and Govee lights.
- **Feel**: eased focus scaling with a soft "spotlight" glow, glass blur
  behind dialogs, subtle navigation sounds, app-launch zoom, ambient
  lighting (wallpaper dims in the evening), idle dimming.
- **Ambient / Aerial / Sleep**: picture-frame mode (landscapes, public-domain
  artwork from the Art Institute of Chicago, or your own photos) with a
  large clock, weather and a music visualiser; Aerial drifting landscapes by
  category (Wikimedia Commons featured pictures) with location; a dark
  drifting sleep clock. Starts after a set idle time; also a system
  screensaver (`AerialDream`).
- **Cinema mode** (film button): dims, Do Not Disturb, dims Govee lights,
  cinema clock, opens your streaming app; press again to undo.
- **iPhone remote**: Settings › iPhone Remote shows a QR code. The TV serves
  a web remote (`assets/remote.html`) on the home Wi-Fi: touchpad, buttons,
  volume, typing, apps, modes, photo upload, weather/calendar settings.
  Every request needs the pairing key in the QR code. Controlling other
  apps uses the accessibility service (Android 13+ for the arrows).

### Watch (all your apps in one place)

`WatchActivity` (play tile in the dock; also its own app, "Watch"), tvOS-style:

- **Home**: Continue Watching and My List (per profile), Top 10 on your
  apps, "Because You Watched…", and a row per service - Netflix, Disney+,
  Prime Video, Paramount+, Apple TV+, NOW, iPlayer, ITVX, Channel 4, 5 -
  including ones not installed (offers the Play Store).
- **Films / Shows**: popular, trending and genre rows.
- **Live TV**: free Freeview guide (github.com/dp247/Freeview-EPG):
  On Now cards and a now/next/later grid; plays in TiviMate (which can't be
  told the channel, so it opens on the last one) or the broadcaster's app.
- **My List**, **Search** (typed or voice; films, shows and TV listings).
- **Detail page**: cast, seasons, age rating, runtime, trailer, where to
  watch (service logos, included/free/rent/buy), My List, More Like This.

Catalogue data: JustWatch's public catalogue (`WatchCatalog`).

Fonts: Roboto Flex, Oswald and Inter (SIL Open Font License). Icons: Lucide (ISC).

## Photo credits

All from Wikimedia Commons, cropped to 16:9 and resized to 1920x1080.

- [Matterhorn and Stellisee in Twilight](https://commons.wikimedia.org/wiki/File:Matterhorn_and_Stellisee_in_Twilight.jpg) by Michael Kuhn, CC BY-SA 4.0
- [Reine At Sunrise (139429959)](https://commons.wikimedia.org/wiki/File:Reine_At_Sunrise_(139429959).jpeg) by Russo Francesco F R P I X C O M, CC BY 3.0
- [Lake Louise Sunrise8](https://commons.wikimedia.org/wiki/File:Lake_Louise_Sunrise8.jpg) by David Zhang, CC BY-SA 2.0
- [Schwabacher Sunrise - Grand Teton National Park](https://commons.wikimedia.org/wiki/File:Schwabacher_Sunrise_-_Grand_Teton_National_Park.jpg) by Eric Kilby, CC BY-SA 2.0
- [Jokulsarlon lake, Iceland](https://commons.wikimedia.org/wiki/File:Jokulsarlon_lake,_Iceland.jpg) by Kenny Muir, CC BY 2.0
- [Glacier National Park sunset](https://commons.wikimedia.org/wiki/File:Glacier_National_Park_sunset.jpg) by B D, CC BY 2.0
- [Baker Lake - Skoki Valley - Banff](https://commons.wikimedia.org/wiki/File:Baker_Lake_-_Skoki_Valley_-_Banff.jpg) by Jakub Fryš, CC BY-SA 4.0
- [Alpenglow Maroon Bells, Maroon Lake, Colorado](https://commons.wikimedia.org/wiki/File:Alpenglow_Maroon_Bells,_Maroon_Lake,_Colorado.jpg) by John Fowler from Placitas, NM, USA, CC BY 2.0
- [Sunset at Maligne Lake - Flickr - Jaykhuang](https://commons.wikimedia.org/wiki/File:Sunset_at_Maligne_Lake_-_Flickr_-_Jaykhuang.jpg) by Jay Huang from Pleasanton, USA, CC BY 2.0
- [Mountains Reflecting - Grand Teton National Park](https://commons.wikimedia.org/wiki/File:Mountains_Reflecting_-_Grand_Teton_National_Park.jpg) by Eric Kilby, CC BY-SA 2.0
- [Sunset at General Carrera Lake](https://commons.wikimedia.org/wiki/File:Sunset_at_General_Carrera_Lake.jpg) by Andreizaaa, CC BY-SA 4.0
- [French alps at Lake Geneva during the blue hour with reflections](https://commons.wikimedia.org/wiki/File:French_alps_at_Lake_Geneva_during_the_blue_hour_with_reflections.jpg) by Deralpinbergsteiger, CC BY-SA 4.0
- [Lake Louise Sunrise1](https://commons.wikimedia.org/wiki/File:Lake_Louise_Sunrise1.jpg) by David Zhang, CC BY-SA 2.0
- [Low sunlight at Øksfjorden in Loppa, Troms og Finnmark, Norway, 2022 August](https://commons.wikimedia.org/wiki/File:Low_sunlight_at_%C3%98ksfjorden_in_Loppa,_Troms_og_Finnmark,_Norway,_2022_August.jpg) by Ximonic (Simo Räsänen), CC BY-SA 4.0
- [Nautical twilight sunset over Sandy Lake (DSCF3005)](https://commons.wikimedia.org/wiki/File:Nautical_twilight_sunset_over_Sandy_Lake_(DSCF3005).jpg) by Trougnouf, CC BY 4.0
