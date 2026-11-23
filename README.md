# Alarm Tap

A Kiosk Satellite plugin. While an alarm rings, a tap anywhere on the screen runs one action and a double tap runs another, for example snooze and stop.

## How it works

- You choose a Home Assistant entity that is `on` while the alarm rings, such as the kiosk's **Alarm ringing** binary sensor.
- While it is `on`, the plugin shows a black full screen overlay with the time, a big snooze label with the snooze count and a short hint. It takes every touch. It draws over the screensaver, the menu and the camera views. KS draws its own alarm screen above all plugin overlays, so the plugin works when a screensaver shows the ring (the **Let alarms take over** option of the Clock and Weather Mood screensavers).
- One tap runs the **Tap action** after a short wait. Two quick taps run the **Double tap action** instead.
- Both are Kiosk Satellite action settings, so you pick anything the Gestures page offers, such as the KS actions for snooze and stop, a Home Assistant service or a script. The plugin does not know what they do.
- The plugin counts the snoozes itself. With **Maximum snoozes** set, a tap that would exceed it speaks the limit text instead of snoozing, so the alarm keeps ringing. A double tap stops the alarm and resets the count. The count also starts again when the alarm rings after a long pause.
- Each step can speak a text through a Home Assistant service you name (default: the kiosk's own announce service). `{n}` in the text becomes the snooze count and `{max}` the limit.
- When the entity turns `off`, the overlay goes away.

## Settings

| Setting | Meaning |
| --- | --- |
| Alarm ringing entity | Binary sensor that is `on` while the alarm rings |
| Snooze label | The big text, default Snooze |
| Show the time | Current time above the label |
| Tap action | Runs on a single tap, for example snooze |
| Speak service / Extra service data | Home Assistant service that speaks and the fields it needs besides the text |
| Text on tap / double tap / at the limit | Spoken texts, `{n}` = snooze count, `{max}` = limit |
| Double tap action | Runs on a double tap, for example stop |
| Maximum snoozes | 0 for unlimited; from this count on, a tap is refused and the limit text is spoken |
| New alarm after | Pause in minutes after which the count starts again |
| Double tap window | How long a single tap waits for a second one, 200 to 600 ms |
| Hint text | Shown below the label, empty for none |

A single tap runs after the double tap window, so a shorter window makes snoozing feel quicker.

## Requirements

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
