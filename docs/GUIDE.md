# Tile Screen user guide

Tile Screen makes an Android tablet show up in Home Assistant as a Tessera touch screen. Tessera
(github.com/MaxGramser/homeassistant_espscreen) normally drives ESP32 panels. This app speaks the
same protocol, so you lay out pages and tiles in Tessera's editor and they appear on the tablet.

It is built for low-memory tablets such as the 5th-gen Fire 7 running LineageOS. There is no web
view and no Home Assistant app inside it: a small native app draws the tiles itself. It should run
on Android 4.2 and later.

This guide covers version 0.13.0.

## 1. Install it on the tablet

You need to sideload the APK once per tablet. Pick whichever way is easier.

**Without a computer**
1. Get `TileScreen.apk` onto the tablet: email it to yourself, put it on a USB stick, or download it.
2. On LineageOS, open Settings → Security and turn on **Unknown sources**. On newer versions,
   Android asks when you open the file and lets you allow your file manager.
3. Open the APK in the Files app and tap **Install**. On Android 6, the file browser in Settings → Storage may say
   it can't open the file; open it from the tablet maker's own file manager instead.

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
- **This screen → Tablet settings** opens this app's own settings: name, grid size, orientation, the screen shape
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
  to the night brightness instead. 0 turns the glass dark. As on a Tessera panel, night brightness is a standby level
  only: the lit screen keeps Brightness at night. With **Android brightness** on and the tablet charging, standby
  takes Android's level instead, so night brightness then applies only off the charger.
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

### Android brightness

Turn on **Android brightness** (on the screen's settings page or its switch in Home Assistant) and the app leaves the
brightness to Android: its slider, or auto-brightness on a tablet with a light sensor (Display → Adaptive brightness).
Tessera's **Brightness** is then not used. Awake, the brightness is always Android's; in standby it is Android's while
the tablet is on its charger (the screensaver dims itself in a dark room), and the standby brightness on battery.

### Screen off in standby on battery

For a tablet you carry around: with **Screen off in standby on battery** on, standby off the charger is always a dark
screen with the backlight off, whatever the brightness settings say, and no screensaver is drawn. Put it on the charger
and standby shows the screensaver again; the change takes effect at once, also in standby. With Lift to wake, picking it
up still wakes it.

### Lift to wake

Turn on **Lift to wake** and picking the tablet up, tilting it or knocking it wakes it from standby, as a touch does. The
accelerometer is read only while the screen is in standby.

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

**On Android 4.2–4.4** everything works, with three differences. Android only shares the next alarm from Android 5, so
the **Next alarm** sensor stays unknown. Before Android 4.4 the navigation bar can't be hidden for good: the app hides
the status bar and dims the navigation bar instead. And landscape and portrait turn with the tablet even when
Auto-rotate is off, since Android 4.2 has no setting to tell the app otherwise. Old tablets are slow too, so live
camera tiles may update less often.

Drawn: lights (with their colour, white shade and effects page: effect, palette, preset, speed and the like, and their presets), switches, fans, input booleans, scenes, scripts, buttons, automations, covers
(open/stop/close, position, tilt), climate (−/+, the big two-row setpoint, the mode bar), humidifiers,
calendars (every view of the tile, the full screen with Up next, Day, Week and Month, Free or busy, and a room's sign with its booking keys), sensors with the meter display (ring, tape, bar), sensors and binary sensors (their card shows the history of the last hour, day or week, a line for numbers and a timeline for states), numbers and selects, media players (volume and mute, playback keys,
power and input in the card, its library behind the key at the top right), favourite tiles (a tap plays or pauses what the favourite holds), locks, robot vacuums (the keys the robot has on its tile, or on a tile two rows high its map, rooms or routines; its card with the map, cleaning by area, routines, and suction and mop), template tiles, alarm panels, timers, people, the sun, weather, the
clock tile (dial, analog, flip and digital), the energy card (live power from Home Assistant's Energy settings, moving dots or calm lines; a tap on a circle opens its sensor's history), "go to page" tiles, camera tiles set to **Live picture** (streaming when set to Live), players set to **Album cover**,
people set to **Map**, a camera full size in its card, pastel tile colors, top bar items, multiple
pages, hidden detail pages, custom tap actions, alerts, the doorbell, the screensaver (with a player's play and volume keys over its cover), and Tessera's screen settings.

Plugins: the ones listed under **Plugins** below. A tile of any other plugin shows its icon, its name and "Plugin missing".

Other limits:

- Pictures need **Allow the device to perform Home Assistant actions**, since the screen asks for
  them with an event.
- The tablet reports firmware 0.58.0, the version whose features it draws. Tessera may offer a newer firmware
  update for the screen; ignore it, there is no ESP32 to flash, and Tessera stops at "no profile" anyway.
- The tablet reports itself to Tessera as one of four boards that have every Tessera feature, the one nearest its
  screen's shape, so the editor's preview fits and Tessera offers everything (plugins that need a speaker too).
  **Tablet settings → Screen shape** is **Automatic** unless you pick one:

  | Shape | Board | For |
  |---|---|---|
  | 1024 x 600 (1.71) | `jc1060p470v2` | most 7-inch tablets, and anything wider than 16:9 (the Echo Show 5) |
  | 800 x 480 (5:3) | `jc4880p443` | 5:3 tablets |
  | 1280 x 800 (16:10) | `jc8012p4a1` | 16:10 tablets, 1920 x 1200 too |
  | 1280 x 720 (16:9) | `tab5` | 16:9 tablets |

  The editor draws its preview at that board's size, so on the two small boards (4.3 and 5 inches) its text looks larger
  than on the tablet. The grid is the tablet's own whatever the board. Tessera also offers plugins for hardware a tablet
  lacks (Voice assist, Bluetooth proxy, Screen camera); added, they show as not running.
- The grid defaults to 4×3 in landscape (3×4 in portrait). Choose it in Tessera's editor (the screen's Settings → Grid, from 1×1 to 8×8, and Landscape or Portrait), as for an ESP32 screen; the tablet takes it when you save. The tablet's own settings set the landscape grid too.
- **Deleting the screen from Home Assistant:** the tablet keeps its encryption key. To add it again,
  long-press the title, then **This screen → Tablet settings → Forget encryption key**. The settings also show the key, in case you
  add the screen by hand.

## Plugins

Tessera's plugins are code built into an ESP32 screen's firmware, so Tile Screen carries its own versions of the ones
below, each drawing what the plugin draws. Add a plugin to the tablet as to any screen: in Tessera's **Plugins** page,
choose the plugin and tick the tablet. Tessera builds nothing for a tablet (it has no firmware profile of it); it records
the plugin as on and reads from the tablet's hello that it runs it. Then place its tiles from the library's Plugins
group, or pick its face in the Screensaver card.

| Plugin | Version | What you get |
|---|---|---|
| Flip clock | 0.1.0 | A face of the screensaver's clock: hours and minutes on two flip cards, a seconds dot, the date and the outside temperature. Pick it under Face in the Clock's drawer of the Screensaver card. |
| Tap sound | 0.2.1 | A soft tick for every tap, a softer one for each step of a dragged slider or a held key, on Android's system volume. Switch it off with **Tap sound** (the screen's settings page, or its switch in Home Assistant). Tessera offers it only to a screen whose board has a speaker, which every board the tablet reports has. |
| Waste collection | 1.0.7 | A tile with the next collection ("Paper", "Tomorrow"), a card with four weeks of collections (a tap on the tile, or a calendar tile whose tap is set to it), and a top bar item ("Tomorrow: Paper") with **Waste in top bar** and **Waste days ahead**. The top bar reads the calendar of the first Next collection tile of the layout. |
| Thermo dial | 0.2.0 | A climate tile with the target large and - / mode / + keys, and a card with a dial to drag; the target goes to Home Assistant when you let go (the keys after 600 ms). |
| AV wall panel controls | 0.1.1 | Five tiles of keys: transport, D-pad, volume or channel, a number pad and a selector grid. Each key runs the action typed for it in the editor, such as `remote.send_command command=UP`. |

A plugin's tiles use the tablet's permission to perform Home Assistant actions, as every control does.

## Live cameras

A camera tile streams when its **Refresh** is set to **Live** in Tessera's editor (a refresh of 5 s or more keeps a still
that comes again at that pace). A tablet streams **2** camera tiles at once by default; the others on the page show a
still every 15 seconds. Change it under **Tablet settings → Live camera tiles at once** (0 to 8). The add-on allows eight
streams across all screens together, and only cameras on the glass count: a camera on another page streams nothing.
A camera opened full screen (a tap on its tile) streams too, at up to eight pictures a second.

The pictures come as JPEG, about a tenth of the bytes they took before.

## The doorbell

Tessera's **Events** page has a wizard for a doorbell automation (it fires `esp_screens_doorbell` with the doorbell's
camera). When it rings, the tablet wakes, comes to the front (from the widget page or another app too), plays a two-note
chime on the notification volume and opens the camera live, full screen:

- the first touch **answers** the ring, and Tessera closes it on the other screens; the ✕ closes it;
- nobody touched it: it closes after the automation's timeout, and the top bar keeps the **missed ring** (the doorbell
  icon and "2 min ago" in orange) until someone taps it, which opens the camera again;
- with a lock or a door action in the automation, a **Door** key stands beside the picture: tap once ("Tap again to
  open"), then again within five seconds. It shows the lock's state, and "Unlocking" until the lock answers.

The tablet has no Talk or Sound key: the picture comes without the doorbell's sound.

## Light presets

Choose **Presets** (or **Brightness slider and presets** on a tile two rows high) under the light's controls in Tessera's
editor. The tile shows up to four round keys, each a white shade or a colour with a brightness, a brightness alone, or
an effect (its first two letters). The one the light shows now has a ring; one tapped shows a spinner until Home
Assistant reports it. The light's card has them too, in a row, or in a column at its right on a wide screen.

## Template tiles

Add **Template** from the screen's own cards in Tessera's library and write Home Assistant templates with a little
markup (`#` headings, `-` bullets, `**bold**`, `:good[...]`, `:alert[...]`, `:accent[...]`, tables). Home Assistant fills it
in and the tile follows every change. A template that comes out empty hides the tile. A tap on it does nothing.

## Calendar events

A tap on an event in a calendar's full screen opens its sheet at once, and the tablet asks for the whole title, place and
notes, which follow a moment later. (Tessera now sends events short, so a busy week fits.)

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

On a tile two rows high (2×2 or larger), choose **Map**, **Rooms to pick**, **Routines** or **Map and rooms** under the
tile's controls in Tessera's editor. The tile then shows the robot's state and battery at the top, that view in a frame,
and the cleaning mode and its keys at the bottom. Tap rooms in the order you want them, then the play key, which shows
how many it will clean. "+N" opens the card for the rest; a tap anywhere else opens the card too.

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

## The battery

A tablet with a battery has three diagnostic sensors on its device in Home Assistant, as the Home Assistant app has:
**Battery level** (%), **Battery state** (charging, discharging, full, not_charging) and **Charger type** (ac, usb,
wireless, none). Tessera also learns that the tablet has a battery, so its top bar offers the battery item.

## Sending alerts from Home Assistant

The tablet's `show_alert` and `show_alert_choice` actions are ESPHome actions, and Home Assistant makes every field of
an ESPHome action required, as on a Tessera screen: fill in each one (an empty text, `timeout: 0`, `flash: false`).
Easier is Tessera's own event, which needs only a title and finds the screens by name:

```yaml
- event: esp_screens_show_alert
  event_data:
    title: Laundry is done
    screen: portable-tablet
```

`screen_message` is the channel Tessera's add-on talks to the screen through, not an action to call yourself.

## The next alarm

The screen's device in Home Assistant has a **Next alarm** sensor, a timestamp like the Home Assistant app's
`next_alarm`, from the tablet's alarm clock (any alarm app that sets Android's alarm clock), and a **Next alarm app**
diagnostic with the app that set it. With no alarm set, it is unknown. Android only shares the next alarm from
Android 5 on, so on Android 4 it stays unknown.

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
