package com.tvcleaner.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The optional GitHub contribution graph on Home: its view, its data and its refresh timer.
 * Off until install.sh passes --es github <user>; while off nothing is fetched or shown.
 * GitHubContributions owns the "not more than every 10 minutes" rule; this only asks while Home is in front.
 */
final class GraphPanel {
    private static final long ASK_EVERY_MS = 10L * 60L * 1000L;
    private final ContributionGraphView view;
    private final GitHubContributions client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final GitHubContributions.Listener listener = new GitHubContributions.Listener() {
        @Override public void onData(GitHubContributions.Data data, boolean fresh) { view.show(data, fresh); }
        @Override public void onUnavailable() { view.unavailable(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            client.loadAndRefresh(listener);
            handler.postDelayed(this, ASK_EVERY_MS);
        }
    };

    GraphPanel(Context context, float scale) {
        view = new ContributionGraphView(context, scale);
        view.setVisibility(View.GONE);
        client = new GitHubContributions(context);
    }

    View view() { return view; }

    boolean enabled() { return client.enabled(); }

    /** Home is in front: show the cache, refresh if due, and keep asking while it stays there. */
    void start() {
        stop();
        if (!enabled()) return;
        running = true;
        handler.post(tick);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    /** Places the graph, or hides it when it's off. Sizes are in screen pixels. */
    void place(boolean compact, int left, int top, int height) {
        if (!enabled()) { view.setVisibility(View.GONE); return; }
        view.setCompact(compact);
        view.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        lp.height = height;
        lp.leftMargin = left;
        lp.topMargin = top;
        view.setLayoutParams(lp);
    }
}
