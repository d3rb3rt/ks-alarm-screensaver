# Snooze Screensaver

A Kiosk Satellite plugin. While an alarm rings, a tap anywhere on the screen snoozes. While snoozed the screen stays, shows when the alarm rings again and a Stop alarm button. Hardware keys snooze with a short press and stop when held.

## How it works

- The plugin follows the kiosk's own alarms (**Settings > Alarms**) through the KS alarm API. Ringing, snoozing and stopping work without Home Assistant.
- While an alarm rings, the plugin shows a black full screen overlay with the time, a big snooze label with the snooze count and a short hint. It takes every touch. KS draws its own alarm screen above all plugin overlays, so the plugin needs a screensaver that shows the ring: the Clock or Weather Mood screensaver with **Let alarms take over** turned on.
- A tap anywhere snoozes while the alarm rings. The screen then stays, shows when the alarm rings again, how many snoozes are left and a Stop alarm button. Only that button reacts while snoozed; a plain tap on it stops the alarm.
- Right after a snooze every touch, key and the button are ignored for a short pause (default 2.5 s), so a second finger or a quick second tap cannot stop the alarm by mistake. Only the first finger down and the last finger up count, so two fingers make one press.
- The plugin counts the snoozes of each ring, also snoozes made elsewhere, such as on KS's own alarm screen. With **Maximum snoozes** set, the last allowed snooze says so, and after it a tap no longer snoozes and the ring screen shows the Stop alarm button.
- After a snooze and after a stop the plugin says a short text with the alarm's voice and volume from **Settings > Alarms > Text to Speech**. KS cannot speak while the alarm rings, so the words come right after the snooze. The text fields start with default texts in English or German (**Language**: Auto follows the device). Overwrite them with your own text, or with a single `-` to silence a step. A cleared field gets its default back. Placeholders: `{n}` number of this snooze, `{total}` snooze minutes so far, `{length}` minutes of one snooze, `{max}` the limit, `{time}` the current time (for example `7:25`), `{until}` the time the alarm rings again (empty when not snoozed). Speech comes from Home Assistant's text to speech, as the alarm's own phrase does, and is cached by KS. Each text has its own volume as a percentage of the Alarm volume (KS 2026.11.31 or newer; older KS says it at the alarm volume). Optionally set **Speech through** to Home Assistant, enter this kiosk's announce action (`esphome.<name>_announce`, see Developer tools > Actions) and use its own volume.
- Hardware keys: a short press of a snooze key snoozes while the alarm rings, holding a stop key for the hold time (default 1 s) always stops it, also while snoozed. A short press never stops. KS does not let plugins hold back a key, so a volume key still changes the volume.

## Settings

| Setting | Meaning |
| --- | --- |
| Snooze label | The big text, default Snooze |
| Show the time | Current time above the label |
| Maximum snoozes | 0 for unlimited; also published as a number entity (`Maximum snoozes`) that Home Assistant can set, for example from a dashboard button |
| Hold key to stop | How long a stop key is held to stop, default 1000 ms |
| Pause after snooze | Touches, keys and the stop button ignored after a snooze, default 2500 ms |
| Hint text | Shown below the label, empty for the default hint, `-` for none |
| Snooze keys / Stop keys | Android key names, default both volume keys in both lists; a short press snoozes, holding always stops |
| Language | Language of the default texts |
| Speech through / Home Assistant speak action / Extra action data | KS alarm voice, or a Home Assistant action with its own volume |
| Volume of snooze / stop / last snooze text | Per text, percent of the Alarm volume (KS 2026.11.31+); with Home Assistant speech the announce volume |
| Text on snooze (tap) / stop / last snooze | Texts said after a snooze, after a stop and on the last allowed snooze |

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
