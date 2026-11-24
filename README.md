# Snooze Screensaver

A Kiosk Satellite plugin. While an alarm rings, a tap anywhere on the screen snoozes and a double tap stops it. Hardware keys can do the same.

## How it works

- The plugin follows the kiosk's own alarms (**Settings > Alarms**) through the KS alarm API. Ringing, snoozing and stopping work without Home Assistant.
- While an alarm rings, the plugin shows a black full screen overlay with the time, a big snooze label with the snooze count and a short hint. It takes every touch. KS draws its own alarm screen above all plugin overlays, so the plugin needs a screensaver that shows the ring: the Clock or Weather Mood screensaver with **Let alarms take over** turned on.
- One tap snoozes after a short wait. Two quick taps stop the alarm.
- The plugin counts the snoozes of each ring, also snoozes made elsewhere, such as on KS's own alarm screen. With **Maximum snoozes** set, the last allowed snooze says so, and after it a tap no longer snoozes: the alarm rings on until it is stopped.
- After a snooze and after a stop the plugin says a short text with the alarm's voice and volume from **Settings > Alarms > Text to Speech**. KS cannot speak while the alarm rings, so the words come right after the snooze. The text fields start with default texts in English or German (**Language**: Auto follows the device). Overwrite them with your own text, or with a single `-` to silence a step. A cleared field gets its default back. Placeholders: `{n}` number of this snooze, `{total}` snooze minutes so far, `{length}` minutes of one snooze, `{max}` the limit, `{time}` the current time (for example `7:25`), `{until}` the time the alarm rings again (empty when not snoozed). Speech comes from Home Assistant's text to speech, as the alarm's own phrase does, and is cached by KS. Each text has its own volume as a percentage of the Alarm volume (KS 2026.11.31 or newer; older KS says it at the alarm volume). Optionally set **Speech through** to Home Assistant, enter this kiosk's announce action (`esphome.<name>_announce`, see Developer tools > Actions) and use its own volume.
- Hardware keys: each key can snooze, stop or both. A key that does both snoozes at once and stops on a second press within the key window. A stop key also stops the alarm while it is snoozed. KS does not let plugins hold back a key, so a volume key still changes the volume.

## Settings

| Setting | Meaning |
| --- | --- |
| Snooze label | The big text, default Snooze |
| Show the time | Current time above the label |
| Maximum snoozes | 0 for unlimited; also published as a number entity (`Maximum snoozes`) that Home Assistant can set, for example from a dashboard button |
| Double tap window | How long a single tap waits for a second one, 200 to 600 ms |
| Hint text | Shown below the label, empty for the default hint, `-` for none |
| Snooze keys / Stop keys | Android key names, default both volume keys in both lists; a key in both lists snoozes first and stops on a quick second press |
| Second key press window | Default 600 ms |
| Language | Language of the default texts |
| Speech through / Home Assistant speak action / Extra action data | KS alarm voice, or a Home Assistant action with its own volume |
| Volume of snooze / stop / last snooze text | Per text, percent of the Alarm volume (KS 2026.11.31+); with Home Assistant speech the announce volume |
| Text on snooze (tap) / stop (double tap) / last snooze | Texts said after a snooze, after a stop and on the last allowed snooze |

A single tap runs after the double tap window, so a shorter window makes snoozing feel quicker.

## Requirements

Kiosk Satellite 2026.11.30 or newer. The plugin needs the `overlay`, `host.read`, `host.control`, `entities` and `ha` capabilities (`entities` for the Maximum snoozes number, `ha` only for speech through Home Assistant).

## Build

Needs Python 3, JDK 17 or newer and an Android SDK with a platform and build tools.

```sh
python3 tools/test.py
python3 tools/build.py
```

Releases are built by the GitHub Actions workflow on every push to `main`. If the version in the manifest has no release yet, the workflow builds the plugin, tags the commit `v<version>` and creates the release. Bump the version to release again.

## License

MIT. The files under `sdk/` and the build tools under `tools/` come from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) and are Apache-2.0.
