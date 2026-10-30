# Tile Screen user guide

Tile Screen makes an Android tablet show up in Home Assistant as a Tessera touch screen. Tessera
(github.com/MaxGramser/homeassistant_espscreen) normally drives ESP32 panels. This app speaks the
same protocol, so you lay out pages and tiles in Tessera's editor and they appear on the tablet.

It is built for low-memory tablets such as the 5th-gen Fire 7 running LineageOS. There is no web
view and no Home Assistant app inside it: a small native app draws the tiles itself. It should run
on Android 5.0 and later.

This guide covers version 0.9.3.

## 1. Install it on the tablet

You need to sideload the APK once per tablet. Pick whichever way is easier.

**Without a computer**
1. Get `TileScreen.apk` onto the tablet: email it to yourself, put it on a USB stick, or download it.
2. On LineageOS, open Settings → Security and turn on **Unknown sources**. On newer versions,
   Android asks when you open the file and lets you allow your file manager.
3. Open the APK in the Files app and tap **Install**.

**With a computer (adb)**
1. On the tablet, open Settings → About tablet and tap **Build number** seven times. Then open
   Settings → Developer options and turn on **USB debugging** (or **ADB over network**).
2. On the computer, install Android's platform tools and run:
   `adb install -r TileScreen.apk`
   (or over Wi-Fi: `adb connect <tablet-ip>:5555` first). `-r` lets you install over an older build.

Open **Tile Screen**. The first time you press the Home button, Android asks which home app to
use. Choose Tile Screen and **Always** to make it a kiosk that starts at boot. It also starts by
itself after a reboot.

## 2. Add it to Home Assistant

1. Home Assistant should discover the tablet under Settings → Devices & services as an ESPHome
   device with the name shown on the tablet. Click **Add** and confirm. There is no encryption
   key to enter: Home Assistant sets one by itself right after adding the screen, and every connection after
   that is encrypted.
   - If it isn't discovered, choose **Add integration → ESPHome** and type the address shown on
     the tablet, port `6053`.
2. Open the new device's ESPHome entry, click **Configure** (the gear), and turn on **Allow the
   device to perform Home Assistant actions**. Without it, taps on tiles do nothing.

## 3. Design it in Tessera

Open Tessera (the ESP Screen Manager add-on). The tablet appears as a screen. Add pages and
tiles, and save. They show up on the tablet within a few seconds, and the tablet keeps the last
layout through restarts.

## Using it

- **Tap** a tile to toggle it, run it, or open its card, as on a Tessera panel.
  **Long-press** any tile for its card (sliders, modes, buttons).
- **Swipe** left or right to change pages, or use the Previous and Next keys in the bar at the bottom.
  The house icon at top left goes back to the home page.
- **Long-press the title** at top left, or **swipe down from the top edge** (with Swipe between pages on), for the
  screen's settings page, as on a Tessera panel: Brightness, Night, Screen and This screen. A `screen.settings` tile
  and Home Assistant's `esphome.<name>_open_settings` open it too.
- **This screen → Tablet settings** opens this app's own settings: name, grid size, orientation, the board
  reported to Tessera, the encryption key, a log, and a way out to Android's settings.
- Tessera's alerts (`esphome.<name>_show_alert` and `_show_alert_choice`) and `_show_page` work.
  So does `esphome.screen_alert` when an alert is answered.

## Icon toggles

Tile Screen's own setting **Icon toggles, tap opens controls** (on the settings page under Screen, or the switch of
that name on the screen's device in Home Assistant) changes how a tile takes a tap:

- **The round icon** does what a tap did: switches a light, switch or fan on and off, turns a TV or receiver on and off, plays or pauses a speaker (or turns
  it on), opens or closes a blind, starts or pauses a robot or a timer, plays a favourite.
- **Anywhere else on the tile** opens its card, with the sliders and keys, without a long press.

Scenes, scripts, buttons, page tiles and tiles with an action of their own work as before. Off is Tessera's way.

## Screen settings, standby and the screensaver

These are the same settings a Tessera ESP32 screen has, with the same entities on the screen's ESPHome
device. Change them in Tessera (open the screen, then **Settings**) or from Home Assistant:

- **Normal brightness**, and **Dark mode** (off gives Tessera's light look)
- **Auto standby** and **Standby after**: after that long without a touch the screen dims to **Standby
  brightness** and shows the screensaver Tessera's **Screensaver** card picks (the music that plays, a
  camera, photos, or the clock), or the dimmed tiles when none is picked. A touch wakes it, and that
  first touch does nothing else.
- **Night mode**, **Night starts**, **Night ends**, **Night brightness**: standby in the night hours dims
  to the night brightness instead. 0 turns the glass dark.
- **Back to page 1** and **Back to page 1 after**, and **Back to page 1 on standby**
- **Swipe between pages**, **Page buttons** (the Previous and Next bar), **Show home button**, **Rotation**
  (0° or 180°)

The **Wake** and **Sleep** buttons are for automations. Wake lights a dimmed screen, and on a lit one
the standby time starts again from then. Sleep dims the screen now, without the screensaver, even with
Auto standby off, until a tap, Wake or an alert. An alert always wakes the screen.

The clock on the screensaver has Tessera's faces: Digital, **Glass**, **Classic** and **Bauhaus**, with the
look (dark, light, or following Dark mode), the numbers on Glass and the seconds hand chosen in Tessera's
**Screensaver** card. Glass and Classic drift a millimetre along a slow circle every minute; Bauhaus changes
one square every minute and the whole picture every ten, and its clock moves with it.

### Use Android screensaver

This switch is the app's own (Tessera's settings panel doesn't show it; find it on the device's page in Home
Assistant, under Configuration). Turn it on to let Android handle sleep instead of Tessera's standby:

- The app stops keeping the screen on, and Tessera's Auto standby no longer dims it. Android's **Screen
  timeout** and **Daydream / Screen saver** (Settings → Display) take over. Set the timeout, pick the
  screensaver (the Clock app's, say), and set **When to start** to *Either* or *While charging*.
- **Sleep** starts Android's screensaver right away. If it can't, the app tries again through root, and
  without a screensaver it turns the screen off.
- **Wake** and every alert turn the glass on, end Android's screensaver, and bring the tiles back.
- Normal brightness still sets the brightness while the screen is on, and Back to page 1 still works.
- The app keeps the tablet's processor and Wi-Fi awake, so Home Assistant can still reach it and wake it
  while Android has the screen off.

Turn the switch off to get Tessera's standby and screensaver back.

A new screen starts from Tessera's defaults (standby after 10 minutes at 20%, night 22:00 to 07:00 at
10%), except that Dark mode and Swipe between pages start on, as this app always had them.

## What works and what doesn't yet

Drawn: lights (with their colour, white shade and effects page: effect, palette, preset, speed and the like), switches, fans, input booleans, scenes, scripts, buttons, automations, covers
(open/stop/close, position, tilt), climate (−/+, the big two-row setpoint, the mode bar), humidifiers,
calendars (every view of the tile, the full screen with Up next, Day, Week and Month, Free or busy, and a room's sign with its booking keys), sensors with the meter display (ring, tape, bar), sensors and binary sensors (their card shows the history of the last hour, day or week, a line for numbers and a timeline for states), numbers and selects, media players (volume and mute, playback keys,
power and input in the card, its library behind the key at the top right), favourite tiles (a tap plays or pauses what the favourite holds), locks, robot vacuums (the keys the robot has on its tile; its card with the map, cleaning by area, routines, and suction and mop), alarm panels, timers, people, the sun, weather, the
clock tile (dial, analog, flip and digital), the energy card (live power from Home Assistant's Energy settings, moving dots or calm lines; a tap on a circle opens its sensor's history), "go to page" tiles, camera tiles set to **Live picture**, players set to **Album cover**,
people set to **Map**, a camera full size in its card, pastel tile colors, top bar items, multiple
pages, hidden detail pages, custom tap actions, alerts, the screensaver (with a player's play and volume keys over its cover), and Tessera's screen settings.

Not drawn yet: plugin tiles. Those tiles show their name, and their card says so.

Other limits:

- Pictures need **Allow the device to perform Home Assistant actions**, since the screen asks for
  them with an event.
- The tablet reports firmware 0.57.0, the version whose features it draws. Tessera may offer a newer firmware
  update for the screen; ignore it, there is no ESP32 to flash, and Tessera stops at "no profile" anyway.
- The tablet reports itself to Tessera as a `waveshare7b` (a 1024×600 board), so the editor's
  preview fits and Tessera sends it pictures. Change it under settings if you like.
- The grid defaults to 4×3 in landscape (3×4 in portrait). Choose it in Tessera's editor (the screen's Settings → Grid, from 1×1 to 8×8, and Landscape or Portrait), as for an ESP32 screen; the tablet takes it when you save. The tablet's own settings set the landscape grid too.
- **Deleting the screen from Home Assistant:** the tablet keeps its encryption key. To add it again,
  long-press the title, then **This screen → Tablet settings → Forget encryption key**. The settings also show the key, in case you
  add the screen by hand.

## Robot vacuums

A vacuum tile shows the keys the robot has: Play or Pause, Stop and Dock. A tap opens its card, as on a Tessera screen:

- its state and battery, and its **map** when the robot's integration has one (Roborock, Ecovacs, Neato, Tuya and
  others), redrawn by Tessera in the screen's colours; while it cleans, the room, how far it is and the area done;
- **Clean** (or Pause, Resume, Stop) and **Dock**, and **Locate**;
- **By area**: tap rooms in the order you want them cleaned, then **Clean (2)** cleans just those. This uses Home
  Assistant's *clean by area* (2026.3 or later) and needs the robot's segments linked to your areas once: in Home
  Assistant open the vacuum's entity settings and choose **Map vacuum segments to areas**;
- **Suction & mop** (the fan key at the top): the cleaning mode, the suction and the water, where the robot has them;
- **Routines**: the robot's own (Roborock's from its app, an Ecovacs station's actions), one tap each.

What the card has no room for gets a key that opens its own page; the round key at the top left goes back.

## The widget page, Android's apps and its launcher

Turn on **Widget page** in the tablet's settings (long-press the title, **This screen → Tablet settings**). A swipe right
from page 1 (with **Swipe between pages** on) then opens a page of Android widgets on the same grid as the tiles:

- **+ Widget** adds one from the apps on the tablet, such as the Clock app's. The first time, Android asks to let Tile
  Screen add widgets; on a rooted tablet the app grants itself that once instead. A widget with a setup screen shows it.
- **Long-press** a widget to make it wider, narrower, taller or shorter, move it first, or remove it.
- **Apps** opens any app; **Launcher** opens Android's own home screen (Trebuchet, say). Home comes back to Tile Screen.
- **Tiles**, a swipe left, or Back return to the tiles. **Back to page 1** returns from the widget page too.

Tile Screen stays the tablet's home app, so Home Assistant keeps control. The screen's **Showing** select says what is
on the glass (Tiles, Widgets or Android, for any other app) and switches it; `show_page` brings the tiles back. When
another app or an alarm comes to the front, the backlight goes back to Android's brightness, so an alarm at night
brightness 0 doesn't ring on a dark screen.

## The next alarm

The screen's device in Home Assistant has a **Next alarm** sensor, a timestamp like the Home Assistant app's
`next_alarm`, from the tablet's alarm clock (any alarm app that sets Android's alarm clock), and a **Next alarm app**
diagnostic with the app that set it. With no alarm set, it is unknown.

## If something goes wrong

Long-press the title, then **This screen → Tablet settings → Log**: it shows connections, layouts received, refusals and
discovery. Over adb, `adb logcat -s tilescreen` shows the same.

## Building it yourself

The app uses only the Android framework. `build.sh` builds `build/TileScreen.apk` with the AOSP
tools that Ubuntu packages:
`apt install aapt apksigner zipalign android-sdk-platform-23 dalvik-exchange openjdk-17-jdk`.

The protocol code (`src/app/tilescreen/core`) is plain Java. `test/` holds harnesses that run it
on a desktop JVM against the real clients:
- `test/drive.py` speaks to it with `aioesphomeapi`, the library Home Assistant uses, and with
  the Tessera add-on's own `page_delivery.Sender`.
- `test/encryption.py` runs Home Assistant's key provisioning against it; `test/CryptoTest.java` checks
  X25519 and ChaCha20-Poly1305 against RFC 7748 and RFC 8439.
- `test/faces.py` renders each clock face from the add-on's own saver messages; `test/android_saver.py`
  drives the Use Android screensaver switch.
- `test/settings.py` checks the settings entities against Tessera's own tables and drives standby,
  night, Sleep and Wake.
- `test/pictures.py` answers its picture requests through the add-on's own validators and sender.
- `test/render.py` with `RenderMain` runs the real tile renderer on Java2D stand-ins for Android's
  drawing classes. It renders frames and replays touches.

## Credits and license

The Tessera receiver (`core/Tessera.java`) is a port of `pkg/tessera` from libcountertop
(github.com/ygelfand/libcountertop) by ygelfand, the author of EchoLocal and LANovo. It follows
the screen protocol of Tessera by MaxGramser. Both are AGPL-3.0, and so is this app (see
`LICENSE`). Icons are Material Design Icons by Pictogrammers, under the Pictogrammers Free
License (see `assets/MDI-LICENSE`).
