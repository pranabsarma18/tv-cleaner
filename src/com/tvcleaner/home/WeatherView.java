package com.tvcleaner.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Non-focusable weather panel: now, today's range, sunrise and sunset, and the next three days. */
final class WeatherView extends View {
    private static final int TEXT = 0xFFE6EDF3, SOFT = 0xFFB7C0CA, DIM = 0xFF9AA4AF;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final float scale;
    private Weather.Data data;
    private boolean failed;
    private boolean compact;

    WeatherView(Context context, float scale) {
        super(context);
        this.scale = scale;
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setContentDescription("Weather");
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    void show(Weather.Data data) { this.data = data; failed = false; invalidate(); }

    void unavailable() { failed = true; invalidate(); }

    /** Two rows of apps leave less room: just now, today's range and a strip for the next days. */
    void setCompact(boolean compact) { this.compact = compact; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(scale, scale);
        float width = getWidth() / scale;
        if (data == null) {
            text(canvas, failed ? "Weather unavailable · check network" : "Loading weather…", 0, 38, 20, regular, DIM);
            canvas.restore();
            return;
        }
        boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());

        // The forecast's first day is the day it was fetched; a cache from yesterday starts one later.
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int t = 0;
        for (int i = 0; i < data.days.length; i++) if (data.days[i].date.equals(today)) { t = i; break; }
        Weather.Day day = data.days[t];

        if (compact) { drawCompact(canvas, day, t, width); canvas.restore(); return; }

        text(canvas, data.place, 0, 28, 24, medium, TEXT);
        boolean fresh = System.currentTimeMillis() - data.fetchedAt < 6L * 60L * 60L * 1000L;
        String updated = (fresh ? "Updated " : "Cached · ")
                + new SimpleDateFormat(h24 ? "H:mm" : "h:mm a", Locale.getDefault()).format(new Date(data.fetchedAt));
        paint.setTextSize(13);
        paint.setTypeface(regular);
        text(canvas, updated, width - paint.measureText(updated), 26, 13, regular, DIM);

        glyph(canvas, data.code, data.day, 48, 92, 92);
        paint.setTextSize(92);
        paint.setTypeface(medium);
        String temp = data.temp + "°";
        text(canvas, temp, 112, 128, 92, medium, 0xFFFFFFFF);
        float col = 112 + paint.measureText(temp) + 28;
        text(canvas, Weather.describe(data.code), col, 74, 22, medium, TEXT);
        text(canvas, "Feels like " + data.feels + "°  ·  " + data.humidity + "% humidity", col, 101, 17, regular, SOFT);
        text(canvas, "High " + day.max + "°  ·  Low " + day.min + "°"
                + (day.rain >= 0 ? "  ·  Rain " + day.rain + "%" : ""), col, 127, 17, regular, SOFT);

        text(canvas, "Sunrise " + Weather.formatClock(day.sunrise, h24) + "     Sunset "
                + Weather.formatClock(day.sunset, h24), 0, 178, 17, regular, SOFT);

        SimpleDateFormat dayName = new SimpleDateFormat("EEE", Locale.getDefault());
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        float colW = width / 3f;
        for (int k = 1; k <= 3 && t + k < data.days.length; k++) {
            Weather.Day d = data.days[t + k];
            float x = (k - 1) * colW;
            String name;
            try { name = k == 1 ? "Tomorrow" : dayName.format(iso.parse(d.date)); } catch (Exception e) { name = d.date; }
            text(canvas, name, x, 222, 17, medium, TEXT);
            glyph(canvas, d.code, true, x + 22, 256, 44);
            text(canvas, d.max + "° / " + d.min + "°", x + 56, 252, 17, regular, SOFT);
            text(canvas, d.rain >= 0 ? "Rain " + d.rain + "%" : Weather.describe(d.code), x + 56, 274, 14, regular, DIM);
        }
        canvas.restore();
    }

    private void drawCompact(Canvas canvas, Weather.Day day, int t, float width) {
        text(canvas, data.place, 0, 22, 20, medium, TEXT);
        if (System.currentTimeMillis() - data.fetchedAt >= 6L * 60L * 60L * 1000L) {
            paint.setTextSize(20);
            paint.setTypeface(medium);
            text(canvas, "· cached", paint.measureText(data.place) + 10, 22, 14, regular, DIM);
        }
        glyph(canvas, data.code, data.day, 30, 72, 58);
        paint.setTextSize(60);
        paint.setTypeface(medium);
        String temp = data.temp + "°";
        text(canvas, temp, 70, 94, 60, medium, 0xFFFFFFFF);
        float col = 70 + paint.measureText(temp) + 20;
        text(canvas, Weather.describe(data.code), col, 66, 19, medium, TEXT);
        text(canvas, "High " + day.max + "°  ·  Low " + day.min + "°"
                + (day.rain >= 0 ? "  ·  Rain " + day.rain + "%" : ""), col, 91, 16, regular, SOFT);

        SimpleDateFormat dayName = new SimpleDateFormat("EEE", Locale.getDefault());
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        float colW = width / 3f, h = getHeight() / scale; // the next days sit along the bottom
        for (int k = 1; k <= 3 && t + k < data.days.length; k++) {
            Weather.Day d = data.days[t + k];
            float x = (k - 1) * colW;
            String name;
            try { name = k == 1 ? "Tomorrow" : dayName.format(iso.parse(d.date)); } catch (Exception e) { name = d.date; }
            text(canvas, name, x, h - 48, 15, medium, DIM);
            glyph(canvas, d.code, true, x + 16, h - 22, 30);
            text(canvas, d.max + "° / " + d.min + "°", x + 40, h - 16, 15, regular, SOFT);
        }
    }

    private void text(Canvas c, String s, float x, float y, float size, Typeface tf, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(size);
        paint.setTypeface(tf);
        paint.setColor(color);
        c.drawText(s, x, y, paint);
    }

    /** A line icon for a WMO weather code, centred on (cx, cy), about s wide. */
    private void glyph(Canvas c, int code, boolean day, float cx, float cy, float s) {
        line.setColor(TEXT);
        line.setStrokeWidth(Math.max(2f, s * 0.055f));
        boolean clear = code <= 1, partly = code == 2;
        if (clear) { sky(c, day, cx, cy, s * 0.5f); return; }
        if (code == 45 || code == 48) {
            for (int i = -1; i <= 1; i++) c.drawLine(cx - s * 0.38f, cy + i * s * 0.2f, cx + s * 0.38f, cy + i * s * 0.2f, line);
            return;
        }
        if (partly) sky(c, day, cx - s * 0.18f, cy - s * 0.18f, s * 0.32f);
        boolean wet = code >= 51;
        float cloudY = wet ? cy - s * 0.12f : cy + (partly ? s * 0.06f : 0);
        cloud(c, partly ? cx + s * 0.06f : cx, cloudY, partly ? s * 0.78f : s * 0.9f);
        if (!wet) return;
        float top = cloudY + s * 0.26f;
        if (code >= 95) { // a bolt
            Path p = new Path();
            p.moveTo(cx + s * 0.06f, top);
            p.lineTo(cx - s * 0.08f, top + s * 0.16f);
            p.lineTo(cx + s * 0.06f, top + s * 0.16f);
            p.lineTo(cx - s * 0.06f, top + s * 0.32f);
            c.drawPath(p, line);
        } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) { // snow
            line.setStyle(Paint.Style.FILL);
            for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.2f, top + s * 0.14f + (i == 0 ? s * 0.08f : 0), s * 0.04f, line);
            line.setStyle(Paint.Style.STROKE);
        } else { // rain or drizzle
            int n = code >= 61 ? 3 : 2;
            for (int i = 0; i < n; i++) {
                float x = cx + (i - (n - 1) / 2f) * s * 0.2f;
                c.drawLine(x + s * 0.05f, top + s * 0.04f, x - s * 0.04f, top + s * 0.26f, line);
            }
        }
    }

    private void sky(Canvas c, boolean day, float cx, float cy, float r) {
        if (day) {
            c.drawCircle(cx, cy, r * 0.42f, line);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                c.drawLine(cx + cos * r * 0.62f, cy + sin * r * 0.62f, cx + cos * r * 0.85f, cy + sin * r * 0.85f, line);
            }
        } else { // a crescent
            Path moon = new Path(), bite = new Path();
            moon.addCircle(cx, cy, r * 0.62f, Path.Direction.CW);
            bite.addCircle(cx + r * 0.36f, cy - r * 0.26f, r * 0.52f, Path.Direction.CW);
            moon.op(bite, Path.Op.DIFFERENCE);
            c.drawPath(moon, line);
        }
    }

    private void cloud(Canvas c, float cx, float cy, float w) {
        Path p = new Path(), part = new Path();
        p.addRoundRect(new RectF(cx - w * 0.45f, cy, cx + w * 0.45f, cy + w * 0.26f), w * 0.13f, w * 0.13f, Path.Direction.CW);
        part.addCircle(cx - w * 0.16f, cy + w * 0.02f, w * 0.18f, Path.Direction.CW);
        p.op(part, Path.Op.UNION);
        part.reset();
        part.addCircle(cx + w * 0.1f, cy - w * 0.04f, w * 0.24f, Path.Direction.CW);
        p.op(part, Path.Op.UNION);
        c.drawPath(p, line);
    }
}
