# Snooze Screensaver

A Kiosk Satellite plugin. While an alarm rings, a tap anywhere on the screen runs one action and a double tap runs another, for example snooze and stop.

## How it works

- You choose a Home Assistant entity that is `on` while the alarm rings, such as the kiosk's **Alarm ringing** binary sensor.
- While it is `on`, the plugin shows a black full screen overlay with the time, a big snooze label with the snooze count and a short hint. It takes every touch. It draws over the screensaver, the menu and the camera views. KS draws its own alarm screen above all plugin overlays, so the plugin needs a screensaver that shows the ring: the Clock or Weather Mood screensaver with **Let alarms take over** turned on.
- One tap runs the **Tap action** after a short wait. Two quick taps run the **Double tap action** instead.
- Both are Kiosk Satellite action settings, so you pick anything the Gestures page offers, such as the KS actions for snooze and stop, a Home Assistant service or a script. The plugin does not know what they do.
- The plugin counts the snoozes itself. With **Maximum snoozes** set, a tap that would exceed it speaks the limit text instead of snoozing, so the alarm keeps ringing. A double tap stops the alarm and resets the count. The count also starts again when the alarm rings after a long pause.
- Each step can speak a text through a Home Assistant service. Set **Speak service** to the kiosk's own announce action (see below), or to `tts.speak` / `assist_satellite.announce`. Built-in texts come in English and German (**Language**: auto follows the device), your own texts replace them, a single `-` silences a step. Placeholders: `{n}` number of this snooze, `{total}` total snooze minutes (`{n}` × **Snooze length**), `{length}` minutes of one snooze, `{max}` the limit.
- Hardware keys: each key can snooze, stop or both. A key that does both snoozes at once and stops on a second press within the key window. A stop key also stops the alarm while it is snoozed (the plugin remembers its own snooze and the snooze length). Keys work without the overlay. Turn on Kiosk Satellite's option to swallow the volume keys if they should not change the volume.
- When the entity turns `off`, the overlay goes away.

## Settings

| Setting | Meaning |
| --- | --- |
| Alarm ringing entity | Binary sensor that is `on` while the alarm rings |
| Snooze label | The big text, default Snooze |
| Show the time | Current time above the label |
| Tap action | Runs on a single tap, for example snooze |
| Speak service / Extra service data | Action that speaks, and the fields it needs besides the text (filled in by itself for a kiosk announce action) |
| Volume per text | Percent, for a kiosk announce action only; 0 sends none |
| Text on tap / double tap / at the limit | Spoken texts, `{n}`, `{total}`, `{length}`, `{max}` (see above) |
| Double tap action | Runs on a double tap, for example stop |
| Snooze keys / Stop keys | Android key names, default both volume keys in both lists; a key in both lists snoozes first and stops on a quick second press |
| Second key press window | Default 600 ms |
| Snooze length | Length of one snooze as set in KS, for `{total}` and `{length}` |
| Maximum snoozes | 0 for unlimited; from this count on, a tap is refused and the limit text is spoken |
| New alarm after | Pause in minutes after which the count starts again |
| Double tap window | How long a single tap waits for a second one, 200 to 600 ms |
| Hint text | Shown below the label, empty for none |

A single tap runs after the double tap window, so a shorter window makes snoozing feel quicker.

## Finding the speak action

Kiosk Satellite gives every kiosk an announce action in Home Assistant, named after its ESPHome device: `esphome.<name>_announce`.

1. In Home Assistant open **Developer tools > Actions**.
2. Type `announce` in the action field.
3. Pick the entry for this kiosk. The name under it, such as `esphome.bedroom_clock_announce`, goes into **Speak service**.
4. Leave **Extra service data** empty: the plugin fills in the fields this action needs and sends the volume from the volume settings.

To speak through another speaker, use `tts.speak` and put its fields under **Extra service data**, for example `{"entity_id": "tts.google_translate_en_com", "media_player_entity_id": "media_player.kitchen"}`. The volume settings then do nothing.

## Requirements

The ringing entity must reach the kiosk. If you filter entity updates for wall panels (for example with Lite Resources), add it to the kept entities.


Kiosk Satellite 2026.11.14 or newer. The plugin needs the `overlay`, `host.read` and `ha` capabilities. `ha` is used for two things: watching the one entity you pick and calling the speak service you name. Nothing else is sent to Home Assistant.

## Build

Needs Python 3, JDK 17 or newer and an Android SDK with a platform and build tools.

```sh
python3 tools/test.py
python3 tools/build.py
```

Releases are built by the GitHub Actions workflow on every push to `main`. If the version in the manifest has no release yet, the workflow builds the plugin, tags the commit `v<version>` and creates the release. Bump the version to release again.

## License

MIT. The files under `sdk/` and the build tools under `tools/` come from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) and are Apache-2.0.
