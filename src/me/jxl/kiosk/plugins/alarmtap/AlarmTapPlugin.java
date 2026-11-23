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
import android.widget.TextView;
import java.util.Map;
import java.util.regex.Pattern;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * While the chosen entity says the alarm rings, a transparent full screen overlay takes every touch.
 * One tap runs the tap action, two quick taps run the double tap action. The plugin never decides what
 * the actions do: the user picks them in the action settings.
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
    private volatile int windowMs = 350;
    private boolean showing;
    /** Main thread only: the single tap that waits to see whether a second one follows. */
    private Runnable pendingTap;

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        host.log("alarm-tap started");
        configure(settings);
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        if (host == null) return;
        Object ms = settings.get("doubleTapMs");
        windowMs = ms instanceof Number ? ((Number) ms).intValue() : 350;
        Object text = settings.get("hint");
        String nextHint = text instanceof String ? ((String) text).trim() : "";
        Object value = settings.get("ringing");
        String next = value instanceof String ? ((String) value).trim() : "";
        if (!next.isEmpty() && !ENTITY.matcher(next).matches()) next = "";
        boolean changed = !next.equals(entity);
        boolean hintChanged = !nextHint.equals(hint);
        hint = nextHint;
        if (changed) {
            if (!entity.isEmpty()) host.unsubscribe("ha.entity." + entity);
            entity = next;
            hide();
            if (!entity.isEmpty()) host.subscribe("ha.entity." + entity);
        } else if (hintChanged && showing) {
            show();
        }
        host.status(entity.isEmpty() ? "Choose the alarm ringing entity in the settings." : "Watching " + entity, entity.isEmpty());
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {}

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null || entity.isEmpty()) return;
        if (!event.equals("ks.ha.entity." + entity)) {
            host.log("alarm-tap ignored event " + event);
            return;
        }
        boolean ringing = "on".equals(payload.get("state"));
        host.status("Watching " + entity + " - last " + payload.get("state") + " at "
            + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()), false);
        host.log("alarm-tap event " + payload.get("state") + " showing=" + showing);
        if (ringing == showing) return;
        if (ringing) show(); else hide();
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
        try {
            host.showOverlay(OVERLAY, spec, new OverlayFactory() {
                @Override public View create(Context context, KsTheme theme) {
                    host.log("alarm-tap overlay created");
                    return build(context, theme, shownHint);
                }
            });
            host.log("alarm-tap overlay requested");
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

    private View build(Context context, KsTheme theme, String shownHint) {
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.TRANSPARENT);
        if (!shownHint.isEmpty()) {
            TextView label = new TextView(context);
            label.setText(shownHint);
            label.setBackground(theme.card());
            label.setPadding(theme.px(20), theme.px(10), theme.px(20), theme.px(10));
            theme.styleText(label, 14, 500, "onSurface");
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            params.bottomMargin = theme.px(16);
            root.addView(label, params);
        }
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
            run(DOUBLE_TAP);
            return;
        }
        pendingTap = new Runnable() {
            @Override public void run() {
                pendingTap = null;
                AlarmTapPlugin.this.run(TAP);
            }
        };
        main.postDelayed(pendingTap, windowMs);
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
