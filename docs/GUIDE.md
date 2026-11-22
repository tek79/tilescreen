# Tile Screen user guide

Tile Screen makes an Android tablet show up in Home Assistant as a Tessera touch screen. Tessera
(github.com/MaxGramser/homeassistant_espscreen) normally drives ESP32 panels. This app speaks the
same protocol, so you lay out pages and tiles in Tessera's editor and they appear on the tablet.

It is built for low-memory tablets such as the 5th-gen Fire 7 running LineageOS. There is no web
view and no Home Assistant app inside it: a small native app draws the tiles itself. It should run
on Android 4.2 and later.

This guide covers version 0.20.0.

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

Android grants the app's permissions when it is installed; the README's **Other Android permissions** says what each is
for. One of them is the microphone, for Talk, calls and the voice assistant. With the assistant's wake word on, other
apps on the tablet may not be able to record: see **Sharing the microphone** below.

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
- **Loop pages** (on by default): after the last page comes the first, and before the first the last, by swipe and by
  the Previous and Next keys

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
- The tablet reports itself to Tessera as one of five boards with every Tessera feature, one per screen shape, so the
  editor's preview fits and Tessera offers everything (plugins that need a speaker too). On its first start the app
  picks the shape nearest the tablet's screen and keeps it; **Tablet settings → Screen shape** changes it (the shape
  found is marked *this tablet's shape*):

  | Shape | Board | For |
  |---|---|---|
  | Square, 720 x 720 (1:1) | `wavesharep4` | square panels (480 x 480 or 720 x 720, such as the Sonoff NSPanel Pro) |
  | 1280 x 800 (16:10) | `jc8012p4a1` | 16:10 tablets, 1920 x 1200 too, and 4:3 ones |
  | 800 x 480 (5:3) | `jc4880p443` | 5:3 tablets |
  | 1024 x 600 (1.71) | `jc1060p470v2` | most 7-inch tablets, and anything wider than 16:9 (the Echo Show 5) |
  | 1280 x 720 (16:9) | `tab5` | 16:9 tablets |

  The shape only sets the editor's preview and what Tessera offers: the tablet draws at its own size, so the editor's
  text can look larger or smaller than on the tablet. Changing the shape doesn't change the grid. Tessera also offers
  plugins for hardware a tablet lacks (Screen camera); added, they show as not running. Voice assistant and Bluetooth
  proxy are ones Tile Screen carries, on a tablet with a microphone or Bluetooth LE (see **The voice assistant** and
  **The Bluetooth proxy**).
- A square screen has one grid whichever way it hangs, as Tessera's square boards, and starts at Tessera's 2×3.
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
| Bluetooth proxy | 0.2.0 | ESPHome's Bluetooth proxy on the tablet; see **The Bluetooth proxy**. Only on a tablet with Bluetooth LE (Android 4.3 and later). |
| Voice assistant | 0.4.0 | Home Assistant's Assist on the tablet: the **Assistant** tile, its top bar item, the popup, and the wake word heard on the tablet itself. Only on a tablet with a microphone; see **The voice assistant**. |

A plugin's tiles use the tablet's permission to perform Home Assistant actions, as every control does.

## Alarm panels and locks with a code

An alarm panel's card has a key for each mode the panel supports (Home, Away, Night, Vacation, Custom, and Disarm),
the current one lit in the state's colour. A mode that asks for a code opens a keypad, as in Home Assistant's own
dialog: only where the panel has a code format, and not when Home Assistant keeps a default code for it. The keypad's
keys are as large as the card allows, up to a phone keypad's size; **Delete** takes back the last digit and **OK**
sends the code. The code goes to Home Assistant as the action's `code` and is wiped from the screen at once; it is never
logged or kept. After three wrong codes the keypad locks for 30 seconds, then twice as long after each further one, up
to 15 minutes. A lock with a code gets the same keypad for Lock, Unlock and Open.

When the alarm counts someone in (pending) or goes off (triggered), the tablet wakes with the keypad open on red and
the time left, so you can disarm at once. No automation is needed. In Tessera's **Events → Alarm** tab you can:

- choose which screens open the keypad by themselves. A tablet that is left out stays quiet, but its tile still opens
  the card;
- have the code send itself after 4 to 8 digits, so there is no OK key and the keypad shows exactly that many dots.
  Choose this only when every code of that alarm has that many digits.

A tablet only opens the keypad for an alarm that is on one of its pages.

## Sound: the speaker, Talk and calls

**Media player.** The screen's device in Home Assistant has a **Media Player**, as a Tessera ESP32-P4 screen does. Send
it text to speech, a chime, a radio stream or a file (`media_player.play_media`, `tts.speak`, or an announcement) and the
tablet plays it with Android's own player. An announcement pauses the music, which carries on after it. Its volume is the
tablet's media volume, so the tablet's own keys and Home Assistant's slider move the same thing.

**Talk to the doorbell.** When Home Assistant offers two-way audio for the doorbell's camera (Settings, Labs, Two-way
audio, 2026.11 and later) and the tablet has a microphone, the doorbell's view has a **Talk** key. A tap turns it on and a
tap turns it off. It turns blue while it connects and says **Talking** with the level of your voice; it stops when the
view closes or after a minute without a voice. Some doorbells play the voice a few seconds late; that delay is the
camera's.

**Calls between screens.** A **Call** tile (from the library's built-in tiles) opens the list of screens that take calls,
tablets and ESP32-P4 screens alike, or calls one chosen screen at once. A call coming in rings, wakes the screen and shows
who calls, with Answer and Decline. In a call:

- **Mute** turns your microphone off and on, and **Hang up** ends the call.
- Two small keys at the top right are voice switching (one person speaks at a time, so the voice doesn't echo back) and
  echo cancelling. Either one changes it for both screens.
- When the other screen has a camera (Tessera's Screen camera plugin with Video), its picture shows beside the keys. The
  tablet doesn't send its own camera yet.

The screen's switches **Call ringtone** (off: a call lights the screen up without a sound), **Call voice switching** and
**Do not disturb** (calls to it are refused, and the list says so) are on its settings page and in Home Assistant. Home
Assistant gets the add-on's events `esp_screens_call_started`, `_answered`, `_missed` and `_ended`.

The microphone is open only while Talk or a call is on, a question to the voice assistant listens, or the wake word is
on (see **The voice assistant**). For Talk and calls, Android's own echo canceller and noise suppressor are used where
the tablet has them, and the tablet tells Tessera whether it has a microphone and an echo canceller. A call or Talk takes
the microphone from the wake word and gives it back when it ends.

## The voice assistant

On a tablet with a microphone, Home Assistant's **Assist** works as Tessera's **Voice assistant** plugin does on an
ESP32-P4 screen: by a tap, or by a wake word the tablet hears itself. Add the plugin to the tablet on Tessera's
**Plugins** page (it records it, nothing is built), then place its **Assistant** tile and, if you like, its item in the
top bar.

- **Ask** by saying the wake phrase (with **Wake word** on), with a tap on the tile, or with **Ask the assistant** on the
  settings page. The tablet listens until Home Assistant hears the end of what you said, then thinks and answers on the
  tablet's speaker. A tap while it runs stops it.
- **A popup** comes over the screen while it listens, thinks and answers, with what it heard and the answer word by word
  as it is spoken. It stays 30 seconds after the answer; a tap beside it or its close key closes it sooner.
- **Settings**, on the settings page (under Screen) and in Home Assistant: **Wake word**, **Wake phrase**, **Wake
  sound** (a short sound when it starts to listen), **Thinking sound** (a soft sound while Home Assistant works out the
  answer) and **Show popup**.
- In Home Assistant the tablet is a voice assistant device. Choose its **Assist pipeline** on its device page; speech
  to text, the conversation and text to speech are whatever that pipeline uses. When Home Assistant asks something
  back, the tablet listens again. `assist_satellite.announce` plays on it too.

A tablet without a working microphone doesn't offer the assistant.

### The wake word

Turn on **Wake word** and the tablet listens for its **Wake phrase**: **Okay Nabu**, **Hey Jarvis** or **Hey Mycroft**,
the three Tessera's plugin carries. The tile then says *Say "Okay Nabu"*.

Home Assistant also shows its own **Wake word** and **Wake word 2** choices on the voice assistant device, as for any
ESPHome voice device (a Tessera screen has them too). Choosing a phrase in Home Assistant's **Wake word** changes **Wake
phrase** on the tablet, but not the other way round: the ESPHome protocol has no message to tell Home Assistant, so after
you change Wake phrase, Home Assistant's Wake word shows the old phrase until it reconnects. The tablet listens for one
phrase at a time, so leave **Wake word 2** on *No wake word*.

- **It is heard on the tablet.** The tablet runs ESPHome's microWakeWord models itself, the same models and the same
  rules an ESP32 screen uses, so it hears the phrase as well as one does. Nothing leaves the tablet until it hears the
  phrase; then what you say goes to Home Assistant, as after a tap. A second model listens for a voice, so a sound
  that only resembles the phrase is left alone.
- **It is off at first.** Tessera's plugin starts with it on; here you switch it on per tablet, so an update never
  leaves every tablet listening.
- **When it listens:** while Wake word is on, Home Assistant is connected, and the tiles are on the glass, also dimmed
  in Tessera's standby or under its screensaver. It rests while another app or Android's screensaver is in front, while
  Android has turned the screen off (with **Use Android screensaver**), and while a call or the doorbell's Talk has the
  microphone.
- **How much it costs:** about 150 KB in the app and a little processor time, all the time it listens. The log says how
  much once, a minute after it starts listening (*wake: hearing takes 2.0% of one core*). On a slow old tablet, if that
  is too much, leave Wake word off there; a tap still asks.
- **The log** (Tablet settings → Log) shows *wake: listening for "Okay Nabu"*, each phrase it heard with how sure it was,
  and why it rests.

### Sharing the microphone

**Android before version 10 lets only one recording use the microphone at a time.** While the wake word listens, the
tablet holds the microphone the whole time, so on Android 9 and older:

- **other apps can't record**: another voice assistant (the Home Assistant app's Assist, Google's), a voice recorder, a
  video call app. They fail to start or record silence;
- **the wake word gives way inside Tile Screen**: calls and the doorbell's Talk take the microphone from it and give it
  back when they end, and a question uses the wake word's own recording;
- **another app in front gets it**: the wake word rests whenever another app (or Android's screensaver) is in front, so
  an app you open on the tablet can record. It listens again when the tiles come back.

On Android 10 and later two apps can record at once and Android gives the sound to the one in front: while the tiles
are on the glass, an app recording in the background gets silence.

If you use another voice assistant on the same tablet, or an app that records in the background, leave **Wake word**
off on that tablet. Tapping the Assistant tile still works.

## The Bluetooth proxy

On a tablet with Bluetooth LE (Android 4.3 and later), Tile Screen is an ESPHome **Bluetooth proxy**, as Tessera's
**Bluetooth proxy** plugin makes an ESP32-P4 screen one. Home Assistant hears Bluetooth devices near the tablet
(thermometers, plant sensors, tags for presence with Bermuda) and can connect to up to three at a time through it (a
lock, a light), with pairing.

- **Turn it on** with **Bluetooth proxy** on the settings page (under Screen) or its switch in Home Assistant. It is off
  until you do, unlike Tessera's plugin. The tablet then drops its connection to Home Assistant for a second, so Home
  Assistant connects again and sees the proxy; it appears in Home Assistant's Bluetooth settings like any ESPHome proxy.
  Add the plugin to the tablet on Tessera's **Plugins** page too if you like (it records it, nothing is built).
- **Bluetooth status** (the settings page, and a diagnostic sensor in Home Assistant) says *Listening*, *Off*,
  *Starting*, *Waiting for Home Assistant*, or what stops it: *Bluetooth is off*, *Location is off*.
- **Bluetooth** comes on by itself when you turn the proxy on.
- **Location must be on** (Android 6 and later). Android hears no Bluetooth devices while Location is off, whatever the
  app's permissions; that is Android's rule. On a rooted tablet the app turns Location on itself, in battery saving mode
  (no GPS). Without root, turn it on in Android's settings.
- **It listens while the screen is on**, also dimmed in standby. Android 8.1 and later stop a Bluetooth scan without
  filters while the screen is off, so with **Use Android screensaver** the proxy may hear nothing while Android has the
  screen off.
- **Differences from an ESP32 proxy:** Android always scans actively, so Home Assistant's passive mode isn't available.
  Before Android 14 Android doesn't say whether a device's address is public or random; the tablet connects by address
  either way.
- **Connections** depend on the tablet's Bluetooth. Advertisements work on almost any tablet; connecting to devices
  is less dependable on old Android versions, which may fail now and then with error 133 (Android's catch-all GATT
  error). The log (Tablet settings → Log) shows each connection with *bluetooth:* lines.
- **Bluetooth scan** (the settings page and Home Assistant) sets how much of the time it listens: **Balanced** (a
  quarter of the time, the default), **Fast** (all the time: devices are heard more often, which suits presence with
  Bermuda, at the cost of Wi-Fi and some processor) or **Low power** (a tenth). Android 4.3 and 4.4 have one speed only.
- **Wi-Fi:** many tablets use one chip for Wi-Fi and Bluetooth. If camera streams stutter, choose Low power or turn the
  proxy off on that tablet.

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

The view has the keys this ring and this tablet can use, as on a P4 screen: **Door** (above), **Talk** with Home
Assistant's two-way audio (see **Sound: the speaker, Talk and calls**), and **Sound**:

- **Sound** plays the doorbell camera's own sound on the tablet's speaker, at the media volume. It is on when the view
  opens, unless the automation says `sound: false` (Tessera's wizard asks); a tap turns it off and on.
- The picture and the sound come in one stream, the one Tessera makes for the tablet as the ring comes, so the view
  opens live at once.
- While you talk (Talk on and your voice heard), the doorbell's sound waits, so it doesn't echo back to the door.
- The sound comes only when the camera's stream in Home Assistant has it. Without a stream from Tessera (no address for
  it on the tablet's network), the view shows the camera's pictures as before, with no Sound key.

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

## Back up and restore

**This screen → Tablet settings → Back up or restore settings** saves everything the tablet keeps of its own to a file,
and puts such a file back:

- **Export** asks where to save it (Android's file picker: the tablet, an SD card, Google Drive). Android 4.2 and 4.3
  have no picker; there it goes to the Download folder as `tilescreen-<name>.json`.
- The file holds the tablet's own settings (name, grid, screen shape, live camera tiles, the widget page, open at
  boot), its **encryption key** and MAC, and the screen's Tessera settings (brightness, standby, night, Back to page 1,
  the assistant's, the Bluetooth proxy's). Not the layout: Tessera sends that again by itself.
- **Import** reads such a file, says which screen it came from and when, and after you confirm replaces this tablet's
  settings with it and starts the screen again. With the key and MAC back, Home Assistant knows it as the same screen,
  so a reinstalled tablet comes back without being added again.
- The file has the encryption key in it: keep it as you would a password. Imported on another tablet, that tablet
  becomes the same screen in Home Assistant; don't run both at once.

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
- `test/alarm_test.py`, `test/call_test.py` and `test/assist_test.py` drive the alarm keypad, calls between screens and
  the voice assistant with the add-on's code and `aioesphomeapi`.
- `test/doorbell_sound_test.py` rings with a stream of pictures and sound and drives the Sound key.
- `test/ble_test.py` drives the Bluetooth proxy as Home Assistant does (`aioesphomeapi`'s Bluetooth calls), against a
  stand-in radio in `RenderMain`.
- `test/wake_test.py` drives the wake word end to end (Home Assistant's wake word choice, Wake word and Wake phrase, a
  question the phrase starts). `test/WakeMain.java` checks the wake word's sums against the originals: the audio front
  end against TensorFlow Lite Micro's C code, every value, and the models against TensorFlow Lite Micro's own
  interpreter, every output. `test/wake_tables.py` makes `core/MicroTables.java` from the C code's tables.

## Credits and license

The Tessera receiver (`core/Tessera.java`) is a port of `pkg/tessera` from libcountertop
(github.com/ygelfand/libcountertop) by ygelfand, the author of EchoLocal and LANovo. It follows
the screen protocol of Tessera by MaxGramser. Both are AGPL-3.0, and so is this app (see
`LICENSE`). Icons are Material Design Icons by Pictogrammers, under the Pictogrammers Free
License (see `assets/MDI-LICENSE`).
