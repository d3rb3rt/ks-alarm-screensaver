# Alarm Tap

A Kiosk Satellite plugin. While an alarm rings, a tap anywhere on the screen runs one action and a double tap runs another, for example snooze and stop.

## How it works

- You choose a Home Assistant entity that is `on` while the alarm rings, such as the kiosk's **Alarm ringing** binary sensor.
- While it is `on`, the plugin lays a transparent full screen overlay over everything, including the alarm screen. It takes every touch.
- One tap runs the **Tap action** after a short wait. Two quick taps run the **Double tap action** instead.
- Both are Kiosk Satellite action settings, so you pick anything the Gestures page offers, such as a Home Assistant script. The plugin does not know what they do.
- When the entity turns `off`, the overlay goes away.

## Settings

| Setting | Meaning |
| --- | --- |
| Alarm ringing entity | Binary sensor that is `on` while the alarm rings |
| Tap action | Runs on a single tap |
| Double tap action | Runs on a double tap |
| Double tap window | How long a single tap waits for a second one, 200 to 600 ms |
| Hint text | Shown at the bottom while ringing, empty for none |

A single tap runs after the double tap window, so a shorter window makes snoozing feel quicker.

## Requirements

Kiosk Satellite 2026.11.14 or newer. The plugin needs the `overlay`, `host.read` and `ha` capabilities. `ha` is only used to watch the one entity you pick; the plugin sends no commands to Home Assistant itself.

## Build

Needs Python 3, JDK 17 or newer and an Android SDK with a platform and build tools.

```sh
python3 tools/test.py
python3 tools/build.py
```

Releases are built by the GitHub Actions workflow when a tag like `v1.0.1` is pushed. The tag must match the version in the manifest, and the workflow creates the release.

## License

MIT. The files under `sdk/` and the build tools under `tools/` come from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) and are Apache-2.0.
