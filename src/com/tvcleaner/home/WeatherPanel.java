package com.tvcleaner.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The optional weather panel on Home: its view, its data and its refresh timer, like GraphPanel.
 * Off until install.sh passes --es weather "lat,lon,Name". Weather owns the "every 30 minutes" rule.
 */
final class WeatherPanel {
    private static final long ASK_EVERY_MS = 10L * 60L * 1000L;
    private final WeatherView view;
    private final Weather client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final Weather.Listener listener = new Weather.Listener() {
        @Override public void onData(Weather.Data data) { view.show(data); }
        @Override public void onUnavailable() { view.unavailable(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            client.loadAndRefresh(listener);
            handler.postDelayed(this, ASK_EVERY_MS);
        }
    };

    WeatherPanel(Context context, float scale) {
        view = new WeatherView(context, scale);
        view.setVisibility(View.GONE);
        client = new Weather(context);
    }

    View view() { return view; }

    boolean enabled() { return client.enabled(); }

    void start() {
        stop();
        if (!client.enabled()) return;
        running = true;
        handler.post(tick);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    /** Shows it at the given place (screen pixels), or hides it when it's off. */
    void place(boolean compact, int left, int top, int width, int height) {
        if (!client.enabled()) { view.setVisibility(View.GONE); return; }
        view.setCompact(compact);
        view.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.width = width;
        lp.height = height;
        view.setLayoutParams(lp);
    }
}
