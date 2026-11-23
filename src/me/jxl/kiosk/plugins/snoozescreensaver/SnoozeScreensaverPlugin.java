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
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Follows this kiosk's own alarms through the KS alarm API, without Home Assistant. While an alarm rings, a
 * black full screen overlay takes every touch and shows the time, a big snooze label with the snooze count
 * and a short hint. One tap snoozes, two quick taps stop. Hardware keys can do the same. The plugin counts the
 * snoozes of each ring; the last allowed snooze says so, and after it a tap no longer snoozes, so the alarm
 * keeps ringing until it is stopped. After a snooze and a stop it speaks a short text with the alarm's voice.
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
    private volatile int windowMs = 350;
    private volatile Set<String> snoozeKeys = new HashSet<String>();
    private volatile Set<String> stopKeys = new HashSet<String>();
    private volatile int keyWindowMs = 600;
    private volatile String language = "en";
    private volatile String tapText = "";
    private volatile String stopText = "";
    private volatile String lastText = "";

    private volatile boolean showing;
    /** Main thread only: the single tap that waits to see whether a second one follows. */
    private Runnable pendingTap;
    /** Main thread only: when a key last snoozed, for the second press that stops. */
    private long lastKeySnooze;

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
    public synchronized void configure(Map<String, Object> settings) {
        if (host == null) return;
        Object ms = settings.get("doubleTapMs");
        windowMs = ms instanceof Number ? ((Number) ms).intValue() : 350;
        language = language(text(settings.get("language")));
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
        Object kw = settings.get("keyWindowMs");
        keyWindowMs = kw instanceof Number ? ((Number) kw).intValue() : 600;
        tapText = text(settings.get("tapText"));
        stopText = text(settings.get("stopText"));
        lastText = text(settings.get("lastText"));
        if ((textChanged || maxChanged) && showing) refresh();
        // Shown in red on purpose: the plugin cannot read whether the screensaver takes over alarms.
        host.status(REQUIREMENT, true);
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
            case "hint": return de ? "Doppeltippen: Wecker beenden" : "Double tap: end alarm";
            case "last-hint": return de ? "Kein Schlummern mehr. Doppeltippen: Wecker beenden" : "No more snoozing. Double tap: end alarm";
            case "tap": return de ? "Schlummer {n}, {total} Minuten" : "Snooze {n}, {total} minutes";
            case "last": return de ? "Letzter Schlummer, {total} Minuten" : "Last snooze, {total} minutes";
            case "stop": return de ? "Wecker beendet" : "Alarm stopped";
            default: return "";
        }
    }

    /** Empty: the built-in text in the chosen language. A single dash: nothing. Anything else: as typed. */
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
            count++;
            long now = System.currentTimeMillis();
            if (nextUntil > now) snoozeMinutes = (int) Math.max(1, Math.round((nextUntil - now) / 60000.0));
            boolean last = maxSnooze > 0 && count >= maxSnooze;
            say(last ? pick(lastText, "last") : pick(tapText, "tap"));
        }
        if ("idle".equals(nextPhase)) {
            if (live && stopRequested && !"idle".equals(previous)) say(pick(stopText, "stop"));
            stopRequested = false;
            count = 0;
            ringAt = "";
        }
        boolean ringing = "ringing".equals(nextPhase);
        if (ringing) show(); else if (showing) hide();
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

    /** Speaks with the alarm's voice and volume. {n} snooze count, {total} minutes so far, {length}, {max}. */
    private void say(String template) {
        if (template.isEmpty()) return;
        int length = snoozeMinutes;
        String words = template.replace("{n}", String.valueOf(count))
            .replace("{total}", String.valueOf(count * length))
            .replace("{min}", String.valueOf(count * length))
            .replace("{length}", String.valueOf(length))
            .replace("{max}", String.valueOf(maxSnooze));
        if (words.length() > 250) words = words.substring(0, 250);
        command("sayAlarm", Collections.<String, Object>singletonMap("text", words));
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
     * A hardware key. A key can be a snooze key, a stop key or both. Both: the first press snoozes at once, a
     * second press within the key window stops. Stop key: a press stops, also while snoozed. Snooze key: a
     * press snoozes; a second press within the window is ignored. Otherwise keys are left alone.
     */
    private void onKey(Map<String, Object> payload) {
        if (!"down".equals(payload.get("action"))) return;
        Object repeat = payload.get("repeat");
        if (repeat instanceof Number && ((Number) repeat).intValue() != 0) return;
        String key = String.valueOf(payload.get("key")).toUpperCase();
        final boolean snoozeKey = snoozeKeys.contains(key);
        final boolean stopKey = stopKeys.contains(key);
        if (!snoozeKey && !stopKey) return;
        final String current = phase;
        if (!"ringing".equals(current) && !"snoozed".equals(current) && !"sunrise".equals(current)) return;
        main.post(new Runnable() {
            @Override public void run() { handleKey(current, snoozeKey, stopKey); }
        });
    }

    /** Main thread. */
    private void handleKey(String current, boolean snoozeKey, boolean stopKey) {
        long now = System.currentTimeMillis();
        boolean ringing = "ringing".equals(current);
        boolean secondPress = lastKeySnooze > 0 && now - lastKeySnooze <= keyWindowMs;
        if (stopKey && (secondPress || !ringing || !snoozeKey || atLimit())) {
            cancelPending();
            lastKeySnooze = 0;
            doStop();
        } else if (snoozeKey && ringing && !secondPress && !atLimit()) {
            cancelPending();
            lastKeySnooze = now;
            doSnooze();
        }
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
        final String shownHint = atLimit() && !hint.isEmpty() ? builtin("last-hint", language) : hint;
        final String shownLabel = count > 0 ? label + " ×" + count : label;
        final boolean shownClock = showClock;
        try {
            current.showOverlay(OVERLAY, spec, new OverlayFactory() {
                @Override public View create(Context context, KsTheme theme) {
                    return build(context, theme, shownClock, shownLabel, shownHint);
                }
            });
        } catch (RuntimeException failure) {
            current.log("snooze-screensaver overlay failed: " + failure);
        }
    }

    private void hide() {
        showing = false;
        main.post(new Runnable() { @Override public void run() { cancelPending(); } });
        PluginHost current = host;
        if (current != null) current.hideOverlay(OVERLAY);
    }

    private void cancelPending() {
        if (pendingTap != null) main.removeCallbacks(pendingTap);
        pendingTap = null;
    }

    private View build(Context context, KsTheme theme, boolean clock, String title, String shownHint) {
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
            theme.styleText(small, Math.round(22 * scale), 400, "onSurface");
            small.setTextColor(0xB3FFFFFF);
            small.setIncludeFontPadding(false);
            LinearLayout.LayoutParams smallParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            smallParams.topMargin = theme.px(Math.round(16 * scale));
            column.addView(small, smallParams);
        }
        root.addView(column, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) onTap();
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

    /** Main thread. The first tap waits for the window, a second one inside it stops. */
    private void onTap() {
        if (pendingTap != null) {
            cancelPending();
            doStop();
            return;
        }
        pendingTap = new Runnable() {
            @Override public void run() {
                pendingTap = null;
                doSnooze();
            }
        };
        main.postDelayed(pendingTap, windowMs);
    }
}
