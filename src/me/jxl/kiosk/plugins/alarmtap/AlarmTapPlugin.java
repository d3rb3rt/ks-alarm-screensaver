// SPDX-License-Identifier: MIT
package me.jxl.kiosk.plugins.alarmtap;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import java.util.regex.Pattern;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * While the chosen entity says the alarm rings, a black full screen overlay takes every touch and shows
 * the time, a big snooze label with the snooze count and a short hint. One tap runs the tap action, two
 * quick taps run the double tap action. The plugin counts the snoozes itself and, from a chosen maximum on,
 * refuses further snoozes. Hardware keys can snooze and stop as well. It never decides what the actions do: the user picks them
 * (KS actions, Home Assistant services, scripts) in the action settings. Spoken texts go through a Home
 * Assistant service of your choice; {n}, {total}, {length} and {max} in them become the snooze count, the total snooze minutes, the minutes of one snooze and the limit.
 */
public final class AlarmTapPlugin implements KioskPlugin {
    static final String OVERLAY = "ring";
    static final String TAP = "tapAction";
    static final String DOUBLE_TAP = "doubleTapAction";
    private static final Pattern ENTITY = Pattern.compile("[a-z0-9_]+\\.[a-z0-9_]+");

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile PluginHost host;
    private String entity = "";
    private String hint = "";
    private String label = "Snooze";
    private boolean showClock = true;
    private int count;
    private volatile int maxSnooze;
    private volatile int snoozeMinutes = 5;
    private volatile int tapVolume = 50;
    private volatile int stopVolume = 100;
    private volatile int maxVolume = 100;
    private volatile long resetMs = 30L * 60000L;
    private long lastSnooze;
    private volatile Set<String> snoozeKeys = new HashSet<String>();
    private volatile Set<String> stopKeys = new HashSet<String>();
    private volatile int keyWindowMs = 600;
    /** Main thread only: when a key last snoozed, for the second press that stops. */
    private long lastKeySnooze;
    private volatile String sayService = "";
    private volatile String language = "en";
    private volatile String sayData = "";
    private volatile String tapText = "";
    private volatile String stopText = "";
    private volatile String maxText = "";
    private volatile int windowMs = 350;
    private boolean showing;
    /** Main thread only: the single tap that waits to see whether a second one follows. */
    private Runnable pendingTap;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        host.log("alarm-tap started");
        configure(settings);
        host.subscribe("device.key");
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

        String next = entityId(settings.get("ringing"));
        Object max = settings.get("maxSnooze");
        maxSnooze = max instanceof Number ? Math.max(0, ((Number) max).intValue()) : 0;
        tapVolume = percent(settings.get("tapVolume"), 50);
        stopVolume = percent(settings.get("stopVolume"), 100);
        maxVolume = percent(settings.get("maxVolume"), 100);
        snoozeKeys = parseKeys(text(settings.get("snoozeKeys")));
        stopKeys = parseKeys(text(settings.get("stopKeys")));
        Object kw = settings.get("keyWindowMs");
        keyWindowMs = kw instanceof Number ? ((Number) kw).intValue() : 600;
        Object length = settings.get("snoozeMinutes");
        snoozeMinutes = length instanceof Number ? Math.max(0, ((Number) length).intValue()) : 5;
        Object reset = settings.get("resetMinutes");
        resetMs = (reset instanceof Number ? Math.max(1, ((Number) reset).intValue()) : 30) * 60000L;
        sayService = text(settings.get("sayService"));
        sayData = text(settings.get("sayData"));
        tapText = text(settings.get("tapText"));
        stopText = text(settings.get("stopText"));
        maxText = text(settings.get("maxText"));
        boolean changed = !next.equals(entity);
        if (changed) {
            if (!entity.isEmpty()) host.unsubscribe("ha.entity." + entity);
            entity = next;
            hide();
            if (!entity.isEmpty()) host.subscribe("ha.entity." + entity);
        } else if (textChanged && showing) {
            show();
        }
        host.status(entity.isEmpty() ? "Choose the alarm ringing entity in the settings." : "Watching " + entity, entity.isEmpty());
    }

    private static int percent(Object value, int fallback) {
        return value instanceof Number ? Math.max(0, Math.min(100, ((Number) value).intValue())) : fallback;
    }

    /** "auto" follows the device language; German and English are built in, everything else is English. */
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
            case "tap": return de ? "Schlummer {n}, {total} Minuten" : "Snooze {n}, {total} minutes";
            case "stop": return de ? "Wecker beendet" : "Alarm stopped";
            case "max": return de ? "Maximale Schlummeranzahl erreicht" : "Maximum snoozes reached";
            default: return "";
        }
    }

    /** Empty: the built-in text in the chosen language. A single dash: nothing. Anything else: as typed. */
    private String pick(String own, String key) {
        if (own.equals("-")) return "";
        return own.isEmpty() ? builtin(key, language) : own;
    }

    static final String ESPHOME_DEFAULTS = "{\"url\": \"\", \"repeat\": 0, \"repeat_pause\": 0, \"chime\": false, "
        + "\"chime_file\": \"\", \"tts_engine\": \"\", \"tts_language\": \"\", \"tts_voice\": \"\", \"audio_only\": true}";

    /** A kiosk announce action of the ESPHome API: it takes the volume and the defaults above. */
    static boolean isKioskAnnounce(String service) {
        return service.startsWith("esphome.") && service.endsWith("_announce");
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

    private static String entityId(Object value) {
        String id = text(value);
        return ENTITY.matcher(id).matches() ? id : "";
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {}

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if (entity.isEmpty()) return;
        if (event.equals("ks.device.key")) {
            onKey(payload);
        } else if (event.equals("ks.ha.entity." + entity)) {
            boolean ringing = "on".equals(payload.get("state"));
            host.log("alarm-tap event " + payload.get("state") + " showing=" + showing);
            if (ringing == showing) return;
            if (ringing) {
                // A snooze makes the alarm ring again: only a long pause means a new alarm.
                if (System.currentTimeMillis() - lastSnooze > resetMs) count = 0;
                show();
                checkTakeover();
            } else {
                hide();
            }
        }
    }

    /**
     * KS draws its own alarm screen above plugin overlays unless a screensaver takes the alarm over. Shortly after
     * the alarm starts, look whether a screensaver is on and say so on the plugin page if it is not.
     */
    private void checkTakeover() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                final PluginHost current = host;
                if (current == null || !showing) return;
                try {
                    current.executeCommand("isScreensaverActive", new HashMap<String, Object>(), new PluginHost.CommandCallback() {
                        @Override public void onResult(boolean ok, Object data, String error) {
                            if (!ok) return;
                            if (Boolean.TRUE.equals(data)) {
                                current.status("Alarm shown through the screensaver takeover", false);
                            } else {
                                current.status("The alarm rang without a screensaver, so KS's alarm screen covered this "
                                    + "plugin. Use the Clock or Weather Mood screensaver with Let alarms take over.", true);
                            }
                        }
                    });
                } catch (RuntimeException ignored) {
                    // Older hosts: nothing to check.
                }
            }
        }, 3000);
    }

    /**
     * A hardware key while the alarm rings or is snoozed. A key can be a snooze key, a stop key or both.
     * Both: the first press snoozes at once, a second press within the key window stops. Stop key: a press
     * stops, also while snoozed. Snooze key: a press snoozes; a second press within the window is ignored.
     * Otherwise keys are left alone.
     */
    private void onKey(Map<String, Object> payload) {
        if (!"down".equals(payload.get("action"))) return;
        Object repeat = payload.get("repeat");
        if (repeat instanceof Number && ((Number) repeat).intValue() != 0) return;
        String key = String.valueOf(payload.get("key")).toUpperCase();
        final boolean snoozeKey = snoozeKeys.contains(key);
        final boolean stopKey = stopKeys.contains(key);
        if (!snoozeKey && !stopKey) return;
        final boolean ringing = showing;
        main.post(new Runnable() {
            @Override public void run() { handleKey(ringing, snoozeKey, stopKey); }
        });
    }

    /** Main thread. */
    private void handleKey(boolean ringing, boolean snoozeKey, boolean stopKey) {
        long now = System.currentTimeMillis();
        boolean secondPress = lastKeySnooze > 0 && now - lastKeySnooze <= keyWindowMs;
        boolean snoozed = !ringing && lastSnooze > 0
            && now < lastSnooze + snoozeMinutes * 60000L + 120000L;
        if (stopKey && (secondPress || snoozed || (ringing && !snoozeKey))) {
            cancelPending();
            lastKeySnooze = 0;
            doStop();
        } else if (snoozeKey && ringing && !secondPress) {
            cancelPending();
            int before = count;
            doSnooze();
            lastKeySnooze = count > before ? now : 0;
        }
    }

    @Override
    public synchronized void stop() {
        showing = false;
        main.removeCallbacksAndMessages(null);
        host = null;
        entity = "";
    }

    private void show() {
        showing = true;
        OverlaySpec spec = OverlaySpec.fullScreen().closeOnBack(false);
        try {
            spec = spec.layer(OverlaySpec.LAYER_TOP);
        } catch (LinkageError olderHost) {
            // Older KS versions have no layers: the overlay then draws over the dashboard only.
        }
        final String shownHint = hint;
        final String shownLabel = count > 0 ? label + " ×" + count : label;
        final boolean shownClock = showClock;
        try {
            host.showOverlay(OVERLAY, spec, new OverlayFactory() {
                @Override public View create(Context context, KsTheme theme) {
                    return build(context, theme, shownClock, shownLabel, shownHint);
                }
            });
        } catch (RuntimeException failure) {
            host.log("alarm-tap overlay failed: " + failure);
            host.status("Overlay failed: " + failure.getMessage(), true);
        }
    }

    private void hide() {
        showing = false;
        main.post(new Runnable() { @Override public void run() { cancelPending(); } });
        if (host != null) host.hideOverlay(OVERLAY);
    }

    private void cancelPending() {
        if (pendingTap != null) main.removeCallbacks(pendingTap);
        pendingTap = null;
    }

    private View build(Context context, KsTheme theme, boolean clock, String title, String shownHint) {
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
            theme.styleText(time, 72, 300, "onSurface");
            time.setTextColor(0xB3FFFFFF);
            column.addView(time, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        TextView big = new TextView(context);
        big.setText(title);
        big.setGravity(Gravity.CENTER);
        theme.styleText(big, 96, 700, "onSurface");
        big.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams bigParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bigParams.topMargin = theme.px(clock ? 24 : 0);
        column.addView(big, bigParams);
        if (!shownHint.isEmpty()) {
            TextView small = new TextView(context);
            small.setText(shownHint);
            small.setGravity(Gravity.CENTER);
            theme.styleText(small, 24, 400, "onSurface");
            small.setTextColor(0xB3FFFFFF);
            LinearLayout.LayoutParams smallParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            smallParams.topMargin = theme.px(20);
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
        return root;
    }

    /** Main thread. The first tap waits for the window, a second one inside it runs the double tap action. */
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

    /** Main thread. Tap: snooze and count, or at the limit only the limit action. */
    private void doSnooze() {
        if (maxSnooze > 0 && count >= maxSnooze) {
            say(pick(maxText, "max"), maxVolume);
            return;
        }
        count++;
        lastSnooze = System.currentTimeMillis();
        say(pick(tapText, "tap"), tapVolume);
        run(TAP);
        refresh();
    }

    /** Main thread. Double tap: say, stop, and start counting from zero. */
    private void doStop() {
        say(pick(stopText, "stop"), stopVolume);
        run(DOUBLE_TAP);
        count = 0;
        lastSnooze = 0;
    }

    private synchronized void refresh() {
        if (showing && host != null) show();
    }

    /** Speaks the text through the chosen Home Assistant service. {n} is the snooze count, {max} the limit. */
    private void say(String template, int volumePercent) {
        PluginHost current = host;
        final String service = sayService;
        if (current == null || template.isEmpty() || service.isEmpty()) return;
        int dot = service.indexOf('.');
        if (dot <= 0 || dot == service.length() - 1) {
            current.status("Speak service must look like domain.service", true);
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
            current.status("Speak data is not valid JSON: " + badJson.getMessage(), true);
            return;
        }
        if (volumePercent > 0 && isKioskAnnounce(service)) data.put("volume", volumePercent / 100.0);
        data.put("message", template.replace("{n}", String.valueOf(count)).replace("{max}", String.valueOf(maxSnooze))
            .replace("{total}", String.valueOf(count * snoozeMinutes))
            .replace("{min}", String.valueOf(count * snoozeMinutes))
            .replace("{length}", String.valueOf(snoozeMinutes)));
        Map<String, Object> message = new HashMap<String, Object>();
        message.put("type", "call_service");
        message.put("domain", service.substring(0, dot));
        message.put("service", service.substring(dot + 1));
        message.put("service_data", data);
        final PluginHost report = current;
        try {
            current.haCall(message, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object result, String error) {
                    if (!ok) report.status("Speaking failed: " + error, true);
                }
            });
        } catch (RuntimeException failure) {
            current.status("Speaking failed: " + failure.getMessage(), true);
        }
    }

    private void run(final String setting) {
        final PluginHost current = host;
        if (current == null) return;
        try {
            current.runAction(setting, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) {
                    if (!ok) current.status(setting + ": " + error, true);
                }
            });
        } catch (RuntimeException failure) {
            current.status(setting + ": " + failure.getMessage(), true);
        }
    }
}
