# Tile Screen

**Tessera dashboards on old Android tablets.**

Tile Screen is an Android app that makes a tablet appear in Home Assistant as a
[Tessera](https://github.com/MaxGramser/homeassistant_espscreen) touch screen. Tessera (the "ESP Screen Manager"
add-on) is built to drive ESP32 touch panels. You design pages and tiles in its editor, and the panel draws them. Tile
Screen speaks the same protocol over the ESPHome native API, so a tablet can take an ESP32 panel's place. You add it
to Home Assistant like any ESPHome device and design it in Tessera like any other screen.

> **Unofficial.** Tile Screen is an independent project. It is not made, endorsed or supported by Tessera's author,
> Home Assistant or ESPHome. Please don't report Tile Screen problems to those projects. Open an issue here instead.

## Why it exists

Many people have old tablets in a drawer: early Fire tablets and cheap Android 5–7 devices with 1 GB of RAM or less.
Modern browsers and the Home Assistant app are too heavy for them. A web dashboard on these tablets lags, leaks memory
and eventually freezes.

Tile Screen avoids all of that. It has no web view, no browser and no Home Assistant app inside it. It is a small
native app with no libraries beyond the Android framework, and it draws Tessera's tiles itself with Android's canvas.
The APK is about 1.2 MB, and it runs on a 5th-gen Fire 7.

It also suits people who already use Tessera on ESP32 panels and want a tablet or two to join them. Every screen is
then laid out in one place.

## What you get

- Tessera's tiles and cards, drawn the way its firmware draws them:
  - lights (with color and effects), switches, covers, climate, media players (with their library and favorites),
    sensors with history graphs and meters, calendars, weather, the clock tile, the energy flow card, cameras (live streaming), the doorbell, light presets, template tiles, and Tessera plugins (flip clock, tap sound, waste collection, thermo dial, AV wall panel controls, voice assistant, Bluetooth proxy, screen camera),
    robot vacuums (map, cleaning by room, routines, suction and mop), alarm panels with a keypad, alerts and more;
  - the screensaver with Tessera's clock faces;
  - the on-screen settings page.
- Tessera's screen settings (brightness, standby, night mode and the rest) as ordinary entities in Home Assistant.
- An optional **Use Android screensaver** mode, which hands sleep over to Android's own Daydream / screen saver.
- Encrypted connection: Home Assistant sets the ESPHome encryption key automatically when you add the tablet.
- It runs as the tablet's home screen (kiosk) and starts at boot.
- An optional **widget page** left of page 1, for Android widgets (a clock with your alarms, say), with keys to
  Android's apps and its own launcher. Home Assistant can switch between the tiles, the widget page and Android with
  the screen's **Showing** select.
- A **Next alarm** sensor in Home Assistant, like the Home Assistant app's, from the tablet's alarm clock.
- Sound, as on Tessera's ESP32-P4 screens: a **media player** for text to speech and music, **Talk** to the doorbell,
  **calls between screens**, and Home Assistant's **Assist** with a **wake word heard on the tablet itself** (on a tablet
  with a microphone).
- An ESPHome **Bluetooth proxy**, with connections, on a tablet with Bluetooth LE.
- The tablet's **camera** in Home Assistant (Tessera's Screen camera): pictures, and **live video with sound** through
  Tessera's Video in Home Assistant, from the front camera; **video calls** between screens; its own camera on a
  tile; optionally the room's **light level** and **motion**, which
  can wake the screen. A green dot shows while it's in use.
- **Back up and restore** of the tablet's settings, encryption key included, to a file.

**Plugins:** Tile Screen carries its own versions of eight Tessera plugins (see the guide). Any other plugin's tile shows its name and "Plugin missing".

## Compatibility

| | |
|---|---|
| **Android** | 4.2 (API 17) or later. Built for and tested on Android 5–7 era devices; see the guide for what Android 4 lacks. |
| **Tested on** | Amazon Fire 7 (5th gen, 2015) running LineageOS 14.1, rooted with Magisk. |
| | Amazon Fire 10 (7th gen, 2017) running LineageOS 14.1, rooted with Magisk. |
| | Amazon Fire 8 (8th gen, 2018) running LineageOS 16, rooted with Magisk. |
| | Amazon Echo Show 5 (1st gen, 2019) running LineageOS 18.1, rooted with Magisk. |
| | Samsung Galaxy Tab E (SM-T560NU, 2015) running LineageOS 20, rooted with Magisk. |
| | Asus ZenPad 8.0 (Z380M) running Android 7.0. |
| | Asus ZenPad 8 S (Z580C) running Android 6.0.1. |
| | Grandstream GXV3275 running Android 4.2.2. |
| **Should work on** | Most Android tablets and phones. Landscape is the default; portrait is supported. |
| **Memory** | Designed for 1 GB of RAM and less. |
| **Home Assistant** | Any recent version with the ESPHome integration. |
| **Tessera** | The ESP Screen Manager add-on (screen protocol v2). The tablet reports firmware **0.58.0**, the version whose features it draws. |
| **Root** | Optional (see the next section). |

Tile Screen is not on the Play Store. You sideload the APK.

### Firmware version and updates

Tessera uses a screen's firmware version to decide which features to send it. Tile Screen reports the newest version
whose features it actually draws, and never a version newer than Tessera's own. This way Tessera never sends anything
the app can't show.

Tessera may still offer a firmware update for the screen. Ignore it: there is no ESP32 to flash, and the update stops
harmlessly.

## Root: optional, and why it is requested

Tile Screen works fully without root. On a rooted tablet (Magisk, for example), the app asks for root **once**,
the first time it needs it, and Magisk shows its usual prompt. You can deny it.

Root is used for six things:

1. **Turning the backlight fully off.** Without root, Android can only set the screen to its dimmest level. That isn't
   dark, and on many tablets it is quite bright. With root, the app writes the brightness straight to the backlight
   driver (`/sys/class/backlight/…` or `/sys/class/leds/lcd-backlight/brightness`). That way a night brightness of 0
   really turns the glass dark, and the low end of the brightness slider follows a perceptual curve.
2. **Starting Android's screensaver.** With *Use Android screensaver* on, the **Sleep** button starts Android's
   screensaver. If Android refuses to start it normally, the app retries with `am start`, and as a last resort it turns
   the screen off with `input keyevent 223`.
3. **Diagnostics.** When something keeps the screen from sleeping, the app reads `dumpsys power` and writes the
   relevant lines (wake locks, Stay awake, screensaver settings) to its log.
4. **Adding widgets.** The first time you add a widget to the widget page, the app grants itself Android's permission to
   place widgets (`appwidget grantbind`), as tapping *Always allow* would. Without root, Android asks instead.
5. **Turning Location on for the Bluetooth proxy.** Android 6 and later hear no Bluetooth devices while Location is off.
   When the proxy is switched on and Location is off, the app turns it on in battery saving mode (no GPS):
   `settings put secure location_mode 2`, or `cmd location set-location-enabled true` on Android 10 and later.
6. **Ending Android's screensaver.** When the wake word (or Home Assistant's Wake) wakes a tablet that uses Android's
   screensaver, the app ends the screensaver with a wake key press (`input keyevent 224`); Android has no other way for
   an app to end it while the screen is on.

Root is never used for anything else. The app doesn't install anything, change system files, or run commands
it gets from the network. Every root command is in [`Root.java`](src/app/tilescreen/Root.java),
[`MainActivity.java`](src/app/tilescreen/MainActivity.java), [`WidgetPage.java`](src/app/tilescreen/WidgetPage.java) and
[`Bluetooth.java`](src/app/tilescreen/Bluetooth.java).
The microphone needs no root.

### Other Android permissions

| Permission | Why |
|---|---|
| Internet, network state, Wi-Fi state | To run the ESPHome API server and show the tablet's address. |
| Wi-Fi multicast | To announce the tablet over mDNS, so Home Assistant discovers it. |
| Wake lock | To keep the processor and Wi-Fi awake, so Home Assistant can always reach the tablet, and to wake the screen. |
| Run at startup | To start again after a reboot. |
| Microphone (record audio) | For the doorbell's Talk, calls between screens, the voice assistant (a question, and the wake word while it is on) and the camera's video while someone watches it. Nothing is recorded or kept; the sound goes only where the feature sends it. A tablet without a microphone simply doesn't offer these. |
| Change audio settings | To switch Android to its call mode with the speakerphone during a call between screens, and back after. |
| Bluetooth, Bluetooth admin | For the Bluetooth proxy, only while its switch is on: to hear Bluetooth devices near the tablet and connect to them for Home Assistant, and to turn Bluetooth on. |
| Camera | For Screen camera: pictures while Home Assistant asks for one or streams, the video while someone watches, and the light level and motion while that setting is on. The camera is off otherwise, and a green dot in the top right corner shows whenever it's on. Pictures go only to Home Assistant; for light level and motion only the two values leave the tablet. A tablet without a camera simply doesn't offer it. |
| Storage (Android 4.3 and older only) | To export and import the settings in the Download folder, where Android has no file picker yet. |
| Location (approximate and precise) | Android lets an app hear Bluetooth devices only with a location permission, since they can reveal where you are. The app never reads the tablet's location. |

Android grants these when the app is installed (it doesn't ask at run time). On Android 6 and later you can still turn
the microphone or the camera off in Android's settings for the app; Talk, calls and the assistant then hear silence,
and the camera gives no pictures. Android 6 and later
also need **Location** switched on for any app to hear Bluetooth devices (the guide's *The Bluetooth proxy*).

**The microphone is shared.** Android before version 10 lets only one recording use the microphone at a time. With the
voice assistant's **Wake word** on, Tile Screen holds the microphone the whole time its tiles are on the glass (or under
Android's screensaver), so other apps on the tablet (another voice assistant, a recorder) can't record then. It lets go
whenever another app is in front, and calls and Talk take it over inside the app. Leave Wake word off on a tablet where another
app needs the microphone. The guide's [Sharing the microphone](docs/GUIDE.md#sharing-the-microphone) has the details.

## Getting started

1. Install `TileScreen.apk` on the tablet (sideload it, or run `adb install -r TileScreen.apk`). Open the app, then
   press Home and choose Tile Screen → **Always**.
2. In Home Assistant, add the discovered ESPHome device. Then open its options and turn on **Allow the device to
   perform Home Assistant actions**. Without this, taps do nothing and pictures don't load.
3. In Tessera, the tablet appears as a screen. Lay out its pages and save.

The [user guide](docs/GUIDE.md) covers each step in detail. It also explains the settings, the screensaver modes,
troubleshooting and the test harnesses.

## Security notes

- The tablet starts unencrypted. Home Assistant gives it an encryption key automatically right after you add it,
  and every connection after that is encrypted. Add new tablets on a network you trust.
- **The camera.** Anyone who can open the tablet's device or a dashboard with its camera in Home Assistant can see
  through it while they look. To stop that for a while, disable the Camera entity in Home Assistant; to keep the
  app from ever using the camera, turn its Camera permission off in Android's settings (Android 6 and later).
  The video (port 8554) asks for a password worked out from the tablet's encryption key, and serves nothing while the
  tablet has no key.
- **Allow the device to perform Home Assistant actions** lets the tablet call Home Assistant actions, as any Tessera
  screen can. Anyone who can touch the tablet can use the tiles you put on it, so lay it out with that in mind.

## Building

There is no Gradle and no Android Studio project. `build.sh` builds `build/TileScreen.apk` with the AOSP command-line
tools that Ubuntu packages:

```sh
sudo apt install aapt apksigner zipalign android-sdk-platform-23 dalvik-exchange openjdk-17-jdk
./build.sh
```

The first build creates a signing key (`tilescreen.jks`). **Keep it, and don't commit it** (it is in `.gitignore`).
Android only installs an update over an existing install when both are signed with the same key. You can use your own
key by pointing `KEYSTORE` at it.

`test/` has desktop harnesses that run the app's code on a regular JVM:

- They test it against Home Assistant's own ESPHome client (`aioesphomeapi`) and the Tessera add-on's own code.
- They render the tile renderer to PNG files and replay touches on it.

The [user guide](docs/GUIDE.md#building-it-yourself) lists them.

## Project layout

```
src/app/tilescreen/        Android side: activity, service, root helper, settings dialog
src/app/tilescreen/core/   plain Java: ESPHome API server, Noise encryption, Tessera receiver, screen settings,
                           the voice assistant and its wake word (audio front end, model runner), the camera's motion
src/app/tilescreen/ui/     the renderer: tiles, cards, clock faces, energy, meters, calendar, settings page
assets/                    Material Design Icons font, the sounds, the wake word models (assets/wake)
test/                      desktop harnesses and stand-ins for Android's drawing classes
docs/GUIDE.md              the full user guide
```

## License and credits

Tile Screen is free software under the **GNU Affero General Public License v3.0** or later (see [`LICENSE`](LICENSE)).

It is a derivative of:

- [Tessera](https://github.com/MaxGramser/homeassistant_espscreen) by Max Gramser and contributors (AGPL-3.0).
  Its screen protocol and firmware views are reimplemented here in Java.
- [libcountertop](https://github.com/ygelfand/libcountertop) by Yuri Gelfand (AGPL-3.0). Its Tessera receiver was ported
  to Java.

It also draws on Yuri Gelfand's [EchoLocal](https://github.com/ygelfand/echolocal) and
[go-esphome-device](https://github.com/ygelfand/go-esphome-device) (MIT). The icons are
[Material Design Icons](https://pictogrammers.com) (Apache 2.0). The wake word uses ESPHome's microWakeWord models
(Apache 2.0) and Java ports of TensorFlow Lite Micro's audio front end and kernels (Apache 2.0) and kissfft (BSD).

[`NOTICE`](NOTICE) lists every credit and licence.
