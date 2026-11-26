// SPDX-License-Identifier: MIT
package me.jxl.kiosk.plugins.snoozescreensaver;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import org.json.JSONObject;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Follows this kiosk's own alarms through the KS alarm API, without Home Assistant. While an alarm rings, a
 * black full screen overlay takes every touch and shows the time, a big snooze label with the snooze count
 * and a short hint. While it rings a tap anywhere snoozes. The overlay stays while the alarm is snoozed and shows
 * when it rings again and a stop button; only that button reacts then. Holding a stop key stops the alarm in both
 * phases, a short key press snoozes while it rings. Right after a snooze every input is ignored for a short pause,
 * so a second finger or a quick second tap does nothing. The plugin counts the snoozes of each ring; the last
 * allowed snooze says so, and after it the ring screen shows the stop button instead of snoozing. After a snooze
 * and a stop it speaks a short text with the alarm's voice.
 */
public final class SnoozeScreensaverPlugin implements KioskPlugin {
    static final String OVERLAY = "ring";
    static final String REQUIREMENT = "Needs the Clock or Weather Mood screensaver with Let alarms take over. "
        + "Without it, KS's own alarm screen covers this plugin.";

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile PluginHost host;

    // Alarm state from KS. Changed on the plugin worker, read on the main thread.
    private volatile String phase = "idle";
    private volatile String ringAt = "";
    private volatile long snoozedUntil;
    /** Snoozes of the current ring. */
    private volatile int count;
    /** Minutes of one snooze, learned from snoozedUntil. */
    private volatile int snoozeMinutes;
    /** The plugin asked KS to stop: speak once the stop is confirmed. */
    private volatile boolean stopRequested;

    // Settings.
    private volatile String hint = "";
    private volatile String label = "Snooze";
    private volatile boolean showClock = true;
    private volatile int maxSnooze;
    /** How long a stop key must be held to stop the alarm. */
    private volatile int holdMs = 1000;
    /** Inputs are ignored this long after a snooze. */
    private volatile int pauseMs = 2500;
    private volatile Set<String> snoozeKeys = new HashSet<String>();
    private volatile Set<String> stopKeys = new HashSet<String>();
    private volatile String language = "en";
    private volatile String tapText = "";
    private volatile String stopText = "";
    private volatile String lastText = "";
    /** True: speak through a Home Assistant action with its own volume instead of KS's alarm voice. */
    private volatile boolean speakViaHa;
    private volatile String sayService = "";
    private volatile String sayData = "";
    private volatile int tapVolume = 30;
    private volatile int stopVolume = 60;
    private volatile int lastVolume = 60;

    private volatile boolean showing;
    /** Inputs before this time (ms) are ignored: the pause right after a snooze. */
    private volatile long quietUntil;
    /** Main thread only: a touch or a key is held down; it snoozes on release unless the hold stopped. */
    private boolean pressed;
    private boolean pressStopped;
    private boolean pressCanSnooze;
    /** Main thread only: fires when a press has been held long enough to stop. */
    private Runnable pendingHold;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        host.log("snooze-screensaver started");
        configure(settings);
        try {
            host.subscribe("alarm.state");
        } catch (IllegalArgumentException olderHost) {
            host.status("This plugin needs Kiosk Satellite 2026.11.30 or newer.", true);
            return;
        }
        host.subscribe("device.key");
        publishMax();
        host.executeCommand("getAlarmState", Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
            @Override public void onResult(boolean ok, Object data, String error) {
                if (ok && data instanceof Map) onAlarmState(asMap(data), false);
            }
        });
    }

    @Override
    public synchronized void configure(Map<String, Object> given) {
        if (host == null) return;
        // A copy, because the default texts are filled in below and the host's map may be read only.
        Map<String, Object> settings = new HashMap<String, Object>(given);
        Object hold = settings.get("holdMs");
        holdMs = hold instanceof Number ? Math.max(300, ((Number) hold).intValue()) : 1000;
        Object pause = settings.get("pauseMs");
        pauseMs = pause instanceof Number ? Math.max(0, ((Number) pause).intValue()) : 2500;
        language = language(text(settings.get("language")));
        fillDefaults(settings);
        String nextHint = pick(text(settings.get("hint")), "hint");
        String nextLabel = pick(text(settings.get("snoozeLabel")), "label");
        if (nextLabel.isEmpty()) nextLabel = builtin("label", language);
        boolean nextClock = !Boolean.FALSE.equals(settings.get("showClock"));
        boolean textChanged = !nextHint.equals(hint) || !nextLabel.equals(label) || nextClock != showClock;
        hint = nextHint;
        label = nextLabel;
        showClock = nextClock;
        Object max = settings.get("maxSnooze");
        int nextMax = max instanceof Number ? Math.max(0, ((Number) max).intValue()) : 0;
        boolean maxChanged = nextMax != maxSnooze;
        maxSnooze = nextMax;
        if (maxChanged) publishMax();
        snoozeKeys = parseKeys(text(settings.get("snoozeKeys")));
        stopKeys = parseKeys(text(settings.get("stopKeys")));
        tapText = text(settings.get("tapText"));
        stopText = text(settings.get("stopText"));
        lastText = text(settings.get("lastText"));
        speakViaHa = "Home Assistant".equals(text(settings.get("speechSource")));
        sayService = text(settings.get("sayService"));
        sayData = text(settings.get("sayData"));
        tapVolume = percent(settings.get("tapVolume"), 30);
        stopVolume = percent(settings.get("stopVolume"), 60);
        lastVolume = percent(settings.get("lastVolume"), 60);
        if ((textChanged || maxChanged) && showing) refresh();
        // Shown in red on purpose: the plugin cannot read whether the screensaver takes over alarms.
        host.status(REQUIREMENT, true);
    }

    private static int percent(Object value, int fallback) {
        return value instanceof Number ? Math.max(0, Math.min(100, ((Number) value).intValue())) : fallback;
    }

    static final String ESPHOME_DEFAULTS = "{\"url\": \"\", \"repeat\": 0, \"repeat_pause\": 0, \"chime\": false, "
        + "\"chime_file\": \"\", \"tts_engine\": \"\", \"tts_language\": \"\", \"tts_voice\": \"\", \"audio_only\": true}";

    /** A kiosk announce action of the ESPHome API: it takes the volume and the defaults above. */
    static boolean isKioskAnnounce(String service) {
        return service.startsWith("esphome.") && service.endsWith("_announce");
    }

    /** "Auto" follows the device language; German and English are built in, everything else is English. */
    static String language(String setting) {
        if (setting.equals("Deutsch")) return "de";
        if (setting.equals("English")) return "en";
        return Locale.getDefault().getLanguage().equals("de") ? "de" : "en";
    }

    static String builtin(String key, String lang) {
        boolean de = lang.equals("de");
        switch (key) {
            case "label": return de ? "Schlummern" : "Snooze";
            case "hint": return de ? "Tippen: Schlummern" : "Tap: snooze";
            case "last-hint": return de ? "Kein Schlummern mehr" : "No more snoozing";
            case "snoozed": return de ? "Bis {until}" : "Until {until}";
            case "left": return de ? "Noch {left}× Schlummern" : "{left} snoozes left";
            case "stop-button": return de ? "Wecker beenden" : "Stop alarm";
            case "tap": return de ? "Schlummer {n}, {total} Minuten" : "Snooze {n}, {total} minutes";
            case "last": return de ? "Letzter Schlummer, {total} Minuten" : "Last snooze, {total} minutes";
            case "stop": return de ? "Wecker beendet" : "Alarm stopped";
            default: return "";
        }
    }

    /** Setting key and its default text key. */
    static final String[][] TEXTS = {
        {"snoozeLabel", "label"}, {"hint", "hint"}, {"tapText", "tap"}, {"stopText", "stop"}, {"lastText", "last"},
    };

    /**
     * Shows the default texts in the settings: an empty field, or one that still holds the default of the other
     * language, gets the default of the chosen language. Text the user typed, or "-", stays as it is.
     */
    private void fillDefaults(Map<String, Object> settings) {
        String other = language.equals("de") ? "en" : "de";
        Map<String, Object> change = new HashMap<String, Object>();
        for (String[] text : TEXTS) {
            String value = text(settings.get(text[0]));
            String wanted = builtin(text[1], language);
            boolean legacy = text[1].equals("hint")
                && (value.equals("Doppeltippen: Wecker beenden") || value.equals("Double tap: end alarm")
                    || value.equals("Tippen: Schlummern · Halten: Beenden") || value.equals("Tap: snooze · Hold: stop"));
            if (!value.equals(wanted) && (value.isEmpty() || legacy || value.equals(builtin(text[1], other)))) {
                change.put(text[0], wanted);
                settings.put(text[0], wanted);
            }
        }
        if (change.isEmpty()) return;
        try {
            host.updateSettings(change);
        } catch (RuntimeException failure) {
            host.log("snooze-screensaver: could not fill in the default texts: " + failure);
        }
    }

    /** Empty: the default text in the chosen language. A single dash: nothing. Anything else: as typed. */
    private String pick(String own, String key) {
        if (own.equals("-")) return "";
        return own.isEmpty() ? builtin(key, language) : own;
    }

    static Set<String> parseKeys(String list) {
        Set<String> out = new HashSet<String>();
        for (String part : list.split(",")) {
            String key = part.trim().toUpperCase();
            if (key.startsWith("KEYCODE_")) key = key.substring("KEYCODE_".length());
            if (!key.isEmpty()) out.add(key);
        }
        return out;
    }

    private static String text(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {}

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if (event.equals("number.max_snooze")) {
            setMaxFromHomeAssistant(payload.get("value"));
        } else if (event.equals("ks.alarm.state") || event.equals("alarm.state")) {
            onAlarmState(payload, true);
        } else if (event.equals("ks.device.key")) {
            onKey(payload);
        }
    }

    /**
     * A new alarm state. A new ring time starts the count again. Going from ringing to snoozed counts a snooze
     * (wherever it was snoozed) and says it; going to idle after a stop this plugin asked for says the stop text.
     */
    private synchronized void onAlarmState(Map<String, Object> state, boolean live) {
        String nextPhase = state.get("phase") instanceof String ? (String) state.get("phase") : "idle";
        String nextAt = text(state.get("at"));
        long nextUntil = parseTime(state.get("snoozedUntil"));
        String previous = phase;
        if (!nextAt.isEmpty() && !nextAt.equals(ringAt)) {
            ringAt = nextAt;
            count = 0;
        }
        phase = nextPhase;
        snoozedUntil = nextUntil;
        if (live && "snoozed".equals(nextPhase) && !"snoozed".equals(previous)) {
            quietUntil = Math.max(quietUntil, System.currentTimeMillis() + pauseMs);
            count++;
            long now = System.currentTimeMillis();
            if (nextUntil > now) snoozeMinutes = (int) Math.max(1, Math.round((nextUntil - now) / 60000.0));
            boolean last = maxSnooze > 0 && count >= maxSnooze;
            say(last ? pick(lastText, "last") : pick(tapText, "tap"), last ? lastVolume : tapVolume);
        }
        if ("idle".equals(nextPhase)) {
            if (live && stopRequested && !"idle".equals(previous)) say(pick(stopText, "stop"), stopVolume);
            stopRequested = false;
            count = 0;
            ringAt = "";
        }
        // The overlay stays while snoozed: it shows when the alarm rings again and a stop button.
        boolean active = "ringing".equals(nextPhase) || "snoozed".equals(nextPhase);
        if (active) show(); else if (showing) hide();
    }

    /** ISO 8601 times such as 2026-11-23T19:45:00.123Z; 0 for null or anything else. */
    static long parseTime(Object state) {
        if (!(state instanceof String)) return 0;
        String text = ((String) state).trim().replaceFirst("\\.\\d+", "");
        if (text.endsWith("Z")) text = text.substring(0, text.length() - 1) + "+00:00";
        try {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            return format.parse(text).getTime();
        } catch (Exception notATime) {
            return 0;
        }
    }

    private boolean atLimit() {
        return maxSnooze > 0 && count >= maxSnooze;
    }

    /** Main thread. Snooze, unless the last allowed snooze is used: then the alarm keeps ringing. */
    private void doSnooze() {
        if (atLimit()) return;
        quietUntil = System.currentTimeMillis() + pauseMs;
        command("snoozeAlarm", Collections.<String, Object>emptyMap());
    }

    /** Main thread. */
    private void doStop() {
        stopRequested = true;
        command("stopAlarm", Collections.<String, Object>emptyMap());
    }

    private void command(final String name, Map<String, Object> arguments) {
        final PluginHost current = host;
        if (current == null) return;
        try {
            current.executeCommand(name, arguments, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) {
                    if (!ok) {
                        if ("stopAlarm".equals(name)) stopRequested = false;
                        current.log("snooze-screensaver " + name + " failed: " + error);
                    }
                }
            });
        } catch (RuntimeException failure) {
            current.log("snooze-screensaver " + name + " failed: " + failure);
        }
    }

    /**
     * Speaks the text: with KS's alarm voice (quieter via percent), or through the chosen Home Assistant action at
     * its own volume. {n} snooze count, {total} minutes so far, {length} one snooze, {max} the limit,
     * {time} the current time, {until} when the alarm rings again (H:mm, device time zone; empty when not snoozed).
     */
    private void say(String template, int volumePercent) {
        if (template.isEmpty()) return;
        int length = snoozeMinutes;
        String words = template.replace("{n}", String.valueOf(count))
            .replace("{total}", String.valueOf(count * length))
            .replace("{min}", String.valueOf(count * length))
            .replace("{length}", String.valueOf(length))
            .replace("{max}", String.valueOf(maxSnooze))
            .replace("{time}", new java.text.SimpleDateFormat("H:mm", Locale.US).format(new java.util.Date()))
            .replace("{until}", snoozedUntil > 0
                ? new java.text.SimpleDateFormat("H:mm", Locale.US).format(new java.util.Date(snoozedUntil)) : "");
        if (words.length() > 250) words = words.substring(0, 250);
        if (speakViaHa && !sayService.isEmpty()) {
            sayViaHa(words, volumePercent);
        } else {
            sayAlarm(words, volumePercent);
        }
    }

    /**
     * KS alarm voice. KS 2026.11.31 and newer take percent (5 to 100, as text, never louder than the Alarm volume).
     * Older KS refuses it with "Invalid host command arguments"; then the text is said once without it.
     */
    private void sayAlarm(final String words, int volumePercent) {
        final PluginHost current = host;
        if (current == null) return;
        Map<String, Object> arguments = new HashMap<String, Object>();
        arguments.put("text", words);
        if (volumePercent > 0) arguments.put("percent", String.valueOf(Math.max(5, Math.min(100, volumePercent))));
        final boolean withPercent = arguments.containsKey("percent");
        try {
            current.executeCommand("sayAlarm", arguments, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) {
                    if (ok) return;
                    if (withPercent && error != null && error.contains("Invalid host command arguments")) {
                        current.log("snooze-screensaver: this KS has no percent for sayAlarm, saying at alarm volume");
                        command("sayAlarm", Collections.<String, Object>singletonMap("text", words));
                    } else {
                        current.log("snooze-screensaver sayAlarm failed: " + error);
                    }
                }
            });
        } catch (RuntimeException failure) {
            current.log("snooze-screensaver sayAlarm failed: " + failure);
        }
    }

    private void sayViaHa(String words, int volumePercent) {
        final PluginHost current = host;
        if (current == null) return;
        final String service = sayService;
        int dot = service.indexOf('.');
        if (dot <= 0 || dot == service.length() - 1) {
            current.log("snooze-screensaver: speak service must look like domain.action");
            return;
        }
        Map<String, Object> data = new HashMap<String, Object>();
        try {
            String extraData = sayData.isEmpty() && isKioskAnnounce(service) ? ESPHOME_DEFAULTS : sayData;
            if (!extraData.isEmpty()) {
                JSONObject extra = new JSONObject(extraData);
                for (Iterator<String> keys = extra.keys(); keys.hasNext();) {
                    String key = keys.next();
                    Object value = extra.get(key);
                    if (value != JSONObject.NULL) data.put(key, value);
                }
            }
        } catch (Exception badJson) {
            current.log("snooze-screensaver: speak data is not valid JSON: " + badJson.getMessage());
            return;
        }
        if (volumePercent > 0 && isKioskAnnounce(service)) data.put("volume", volumePercent / 100.0);
        data.put("message", words);
        Map<String, Object> message = new HashMap<String, Object>();
        message.put("type", "call_service");
        message.put("domain", service.substring(0, dot));
        message.put("service", service.substring(dot + 1));
        message.put("service_data", data);
        try {
            current.haCall(message, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object result, String error) {
                    if (!ok) current.log("snooze-screensaver: speaking failed: " + error);
                }
            });
        } catch (RuntimeException failure) {
            current.log("snooze-screensaver: speaking failed: " + failure);
        }
    }

    /** Maximum snoozes as a number entity, so Home Assistant (a dashboard button) can read and set it. */
    private void publishMax() {
        PluginHost current = host;
        if (current == null) return;
        Map<String, Object> meta = new HashMap<String, Object>();
        meta.put("min", 0);
        meta.put("max", 20);
        meta.put("step", 1);
        meta.put("mode", "box");
        try {
            current.publishNumber("max_snooze", "Maximum snoozes", meta, Double.valueOf(maxSnooze));
        } catch (RuntimeException unsupported) {
            current.log("snooze-screensaver: number entity unavailable: " + unsupported);
        }
    }

    private void setMaxFromHomeAssistant(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Maximum snoozes requires a number");
        int applied = (int) Math.max(0, Math.min(20, Math.round(((Number) value).doubleValue())));
        maxSnooze = applied;
        Map<String, Object> change = new HashMap<String, Object>();
        change.put("maxSnooze", applied);
        host.updateSettings(change);
        publishMax();
        if (showing) refresh();
    }

    /**
     * A hardware key. While the alarm rings, a short press of a snooze key snoozes (on release). Holding a stop
     * key for the hold time always stops the alarm, ringing or snoozed. A short press while snoozed does nothing.
     */
    private void onKey(Map<String, Object> payload) {
        Object repeat = payload.get("repeat");
        if (repeat instanceof Number && ((Number) repeat).intValue() != 0) return;
        final boolean down = "down".equals(payload.get("action"));
        if (!down && !"up".equals(payload.get("action"))) return;
        String key = String.valueOf(payload.get("key")).toUpperCase();
        final boolean snoozeKey = snoozeKeys.contains(key);
        final boolean stopKey = stopKeys.contains(key);
        if (!snoozeKey && !stopKey) return;
        String current = phase;
        if (!"ringing".equals(current) && !"snoozed".equals(current) && !"sunrise".equals(current)) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (down) press(snoozeKey, stopKey); else release(true);
            }
        });
    }

    /** Main thread. A key or touch goes down: start the hold that stops (keys), unless inside the pause after a snooze. */
    private void press(boolean canSnooze, boolean canStop) {
        cancelHold();
        pressed = false;
        if (System.currentTimeMillis() < quietUntil) return;
        pressed = true;
        pressStopped = false;
        pressCanSnooze = canSnooze;
        if (!canStop) return;
        pendingHold = new Runnable() {
            @Override public void run() {
                pendingHold = null;
                pressStopped = true;
                doStop();
            }
        };
        main.postDelayed(pendingHold, holdMs);
    }

    /** Main thread. The key or touch is let go: a short press snoozes while the alarm rings. */
    private void release(boolean counts) {
        cancelHold();
        if (!pressed) return;
        pressed = false;
        if (pressStopped || !counts || !pressCanSnooze) return;
        if ("ringing".equals(phase) && System.currentTimeMillis() >= quietUntil) doSnooze();
    }

    /** Main thread. The stop button: stops at once, unless inside the pause after a snooze. */
    private void onStopButton() {
        if (System.currentTimeMillis() < quietUntil) return;
        cancelHold();
        pressed = false;
        doStop();
    }

    @Override
    public synchronized void stop() {
        showing = false;
        main.removeCallbacksAndMessages(null);
        host = null;
    }

    private void refresh() {
        if (showing) show();
    }

    private void show() {
        final PluginHost current = host;
        if (current == null) return;
        showing = true;
        OverlaySpec spec = OverlaySpec.fullScreen().closeOnBack(false);
        try {
            spec = spec.layer(OverlaySpec.LAYER_TOP);
        } catch (LinkageError olderHost) {
            // Older KS versions have no layers: the overlay then draws over the dashboard only.
        }
        boolean snoozed = "snoozed".equals(phase);
        String left = maxSnooze > 0 ? builtin("left", language).replace("{left}", String.valueOf(Math.max(0, maxSnooze - count))) : "";
        final String shownHint;
        final String shownLabel;
        // The stop button: while snoozed, and while ringing once no snooze is left.
        final boolean stopButton = snoozed || atLimit();
        if (snoozed) {
            shownLabel = builtin("snoozed", language).replace("{until}", clockTime(snoozedUntil));
            shownHint = left;
        } else {
            shownLabel = count > 0 ? label + " ×" + count : label;
            shownHint = atLimit() ? builtin("last-hint", language) : hint;
        }
        final boolean shownClock = showClock;
        try {
            current.showOverlay(OVERLAY, spec, new OverlayFactory() {
                @Override public View create(Context context, KsTheme theme) {
                    return build(context, theme, shownClock, shownLabel, shownHint, stopButton);
                }
            });
        } catch (RuntimeException failure) {
            current.log("snooze-screensaver overlay failed: " + failure);
        }
    }

    private void hide() {
        showing = false;
        main.post(new Runnable() { @Override public void run() { cancelHold(); pressed = false; } });
        PluginHost current = host;
        if (current != null) current.hideOverlay(OVERLAY);
    }

    private void cancelHold() {
        if (pendingHold != null) main.removeCallbacks(pendingHold);
        pendingHold = null;
    }

    private View build(Context context, KsTheme theme, boolean clock, String title, String shownHint, boolean stopButton) {
        // Size the text to the screen: small clocks are only about 360 dp high.
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float heightDp = metrics.heightPixels / metrics.density;
        float scale = Math.max(0.5f, Math.min(1.5f, heightDp / 480f));
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        if (clock) {
            TextClock time = new TextClock(context);
            time.setFormat24Hour("HH:mm");
            time.setFormat12Hour("HH:mm");
            time.setGravity(Gravity.CENTER);
            theme.styleText(time, Math.round(64 * scale), 300, "onSurface");
            time.setTextColor(0xB3FFFFFF);
            time.setIncludeFontPadding(false);
            column.addView(time, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        TextView big = new TextView(context);
        big.setText(title);
        big.setGravity(Gravity.CENTER);
        theme.styleText(big, Math.round(80 * scale), 700, "onSurface");
        big.setTextColor(Color.WHITE);
        big.setIncludeFontPadding(false);
        big.setMaxLines(1);
        LinearLayout.LayoutParams bigParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bigParams.topMargin = theme.px(clock ? Math.round(16 * scale) : 0);
        column.addView(big, bigParams);
        if (!shownHint.isEmpty()) {
            TextView small = new TextView(context);
            small.setText(shownHint);
            small.setGravity(Gravity.CENTER);
            theme.styleText(small, Math.round(32 * scale), 400, "onSurface");
            small.setTextColor(0xE6FFFFFF);
            small.setIncludeFontPadding(false);
            LinearLayout.LayoutParams smallParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            smallParams.topMargin = theme.px(Math.round(18 * scale));
            column.addView(small, smallParams);
        }
        if (stopButton) {
            // A real button: a plain tap stops. The rest of the screen does not react while snoozed.
            TextView stop = new TextView(context);
            stop.setText(builtin("stop-button", language));
            theme.stylePill(stop, true);
            theme.styleText(stop, Math.round(30 * scale), 600, "onPrimary");
            stop.setPadding(theme.px(Math.round(48 * scale)), theme.px(Math.round(20 * scale)),
                theme.px(Math.round(48 * scale)), theme.px(Math.round(20 * scale)));
            stop.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { onStopButton(); }
            });
            LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            stopParams.topMargin = theme.px(Math.round(26 * scale));
            column.addView(stop, stopParams);
        }
        root.addView(column, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                // Only the first finger down and the last finger up count: two fingers make one press. The screen
                // only snoozes (while ringing); stopping is the button's job.
                if (action == MotionEvent.ACTION_DOWN) press(true, false);
                else if (action == MotionEvent.ACTION_UP) release(true);
                else if (action == MotionEvent.ACTION_CANCEL) release(false);
                return true;
            }
        });
        // Report the measured layout once, so its fit on a screen can be checked in the KS log.
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                PluginHost current = host;
                if (current != null && (b - t) != (ob - ot)) {
                    current.log("snooze-screensaver layout screen=" + (b - t) + "px content=" + column.getHeight()
                        + "px top=" + column.getTop() + " scale=" + scale);
                }
            }
        });
        return root;
    }

    /** Local HH:mm of a time in ms; empty for 0. */
    private static String clockTime(long millis) {
        if (millis <= 0) return "";
        return new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(millis));
    }
}
