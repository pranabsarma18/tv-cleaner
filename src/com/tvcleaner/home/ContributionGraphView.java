package com.tvcleaner.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Lightweight, non-focusable GitHub-style year calendar, drawn directly on Canvas. */
final class ContributionGraphView extends View {
    private static final int[] LEVELS = {0xFF20252B, 0xFF0E4429, 0xFF006D32, 0xFF26A641, 0xFF39D353};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float scale;
    private GitHubContributions.Data data;
    private boolean failed;
    private boolean compact;

    ContributionGraphView(Context context, float scale) {
        super(context);
        this.scale = scale;
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setContentDescription("GitHub contribution activity");
    }

    void show(GitHubContributions.Data data, boolean fresh) {
        this.data = data;
        this.failed = false;
        invalidate();
    }

    void setCompact(boolean compact) { this.compact = compact; invalidate(); }

    void unavailable() { failed = true; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(scale, scale);
        if (compact) {
            drawCurrentMonth(canvas);
            canvas.restore();
            return;
        }
        float width = getWidth() / scale;
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setTextSize(24);
        paint.setColor(0xFFE6EDF3);
        if (data == null) {
            paint.setTextSize(20);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            paint.setColor(0xFFA8B0BA);
            canvas.drawText(failed ? "GitHub activity unavailable · check network" : "Loading GitHub activity…", 96, 38, paint);
            canvas.restore();
            return;
        }

        // The current month and the two before it, each a Monday-first calendar with its dates.
        final int cell = 28, gap = 6, step = cell + gap;
        final int monthW = 7 * step - gap, monthGap = 56;
        final float gridX = 96, monthY = 66, gridY = 94;
        Calendar local = Calendar.getInstance();
        int[] years = new int[3], months = new int[3];
        Calendar m = Calendar.getInstance();
        m.set(Calendar.DAY_OF_MONTH, 1);
        m.add(Calendar.MONTH, -2);
        for (int i = 0; i < 3; i++) {
            years[i] = m.get(Calendar.YEAR);
            months[i] = m.get(Calendar.MONTH);
            m.add(Calendar.MONTH, 1);
        }
        Map<Long, GitHubContributions.Day> byDate = new HashMap<>();
        int total = 0;
        Calendar utcDay = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        for (GitHubContributions.Day d : data.days) {
            byDate.put(d.date, d);
            utcDay.setTimeInMillis(d.date);
            for (int i = 0; i < 3; i++)
                if (utcDay.get(Calendar.YEAR) == years[i] && utcDay.get(Calendar.MONTH) == months[i]) total += d.count;
        }

        canvas.drawText("GitHub activity", 96, 28, paint);
        paint.setTextSize(18);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        paint.setColor(0xFFB7C0CA);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        String commitLine;
        if (today.equals(data.commitDate) && data.publicPushCommits >= 0) {
            commitLine = "Public commits today: " + data.publicPushCommits;
        } else if (data.publicPushCommits >= 0 && !data.commitDate.isEmpty()) {
            commitLine = "Public commits on " + new SimpleDateFormat("d MMM", Locale.US)
                    .format(parseLocalDay(data.commitDate)) + ": " + data.publicPushCommits + " (cached)";
        } else {
            commitLine = "Public commits today: unavailable";
        }
        paint.setTextSize(24);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        float statsX = 96 + paint.measureText("GitHub activity") + 24;
        paint.setTextSize(18);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        paint.setColor(0xFFB7C0CA);
        canvas.drawText(String.format(Locale.US, "%,d contributions in the last 3 months", total) + "  ·  " + commitLine,
                statsX, 28, paint);

        SimpleDateFormat monthFmt = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
        String[] weekday = {"M", "T", "W", "T", "F", "S", "S"};
        int todayYear = local.get(Calendar.YEAR), todayMonth = local.get(Calendar.MONTH), todayDate = local.get(Calendar.DAY_OF_MONTH);
        for (int i = 0; i < 3; i++) {
            float x0 = gridX + i * (monthW + monthGap);
            Calendar first = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
            first.clear();
            first.set(years[i], months[i], 1);
            Calendar label = Calendar.getInstance();
            label.clear();
            label.set(years[i], months[i], 1);

            paint.setTextSize(18);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            paint.setColor(0xFFE6EDF3);
            canvas.drawText(monthFmt.format(label.getTime()), x0, monthY, paint);

            paint.setTextSize(13);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            paint.setColor(0xFF9AA4AF);
            for (int col = 0; col < 7; col++)
                canvas.drawText(weekday[col], x0 + col * step + (cell - paint.measureText(weekday[col])) / 2f, gridY - 8, paint);

            int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);
            int offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday first.
            for (int dayNumber = 1; dayNumber <= daysInMonth; dayNumber++) {
                int index = offset + dayNumber - 1;
                float x = x0 + (index % 7) * step, y = gridY + (index / 7) * step;
                boolean isToday = years[i] == todayYear && months[i] == todayMonth && dayNumber == todayDate;
                boolean future = years[i] == todayYear && months[i] == todayMonth && dayNumber > todayDate;
                GitHubContributions.Day d = byDate.get(first.getTimeInMillis() + (dayNumber - 1) * 24L * 60L * 60L * 1000L);
                int level = d == null ? 0 : Math.max(0, Math.min(4, d.level));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(future ? 0x6620252B : LEVELS[level]);
                canvas.drawRoundRect(x, y, x + cell, y + cell, 4, 4, paint);
                if (isToday) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2);
                    paint.setColor(0xFFE6EDF3);
                    canvas.drawRoundRect(x + 1, y + 1, x + cell - 1, y + cell - 1, 4, 4, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                paint.setTextSize(13);
                paint.setColor(future ? 0x669AA4AF : level >= 3 ? 0xFFE6EDF3 : 0xFF9AA4AF);
                String n = Integer.toString(dayNumber);
                canvas.drawText(n, x + (cell - paint.measureText(n)) / 2f, y + 19, paint);
            }
        }

        // Last update and the colour key, to the right of the months.
        paint.setTextSize(13);
        paint.setColor(0xFF9AA4AF);
        String state = System.currentTimeMillis() - data.fetchedAt < 24L * 60L * 60L * 1000L ? "Updated " : "Cached · ";
        String updated = state + new SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(data.fetchedAt);
        float footerX = gridX + 3 * monthW + 2 * monthGap + 56;
        canvas.drawText(updated, footerX, gridY + 15, paint);
        float legendY = gridY + step + 15;
        float lx = footerX;
        canvas.drawText("Less", lx, legendY, paint);
        lx += paint.measureText("Less") + 4;
        for (int i = 0; i < 5; i++) {
            paint.setColor(LEVELS[i]);
            float swatchY = gridY + step + (cell - 10) / 2f;
            canvas.drawRoundRect(lx, swatchY, lx + 10, swatchY + 10, 2, 2, paint);
            lx += 14;
        }
        paint.setColor(0xFF9AA4AF);
        canvas.drawText("More", lx + 2, legendY, paint);
        canvas.restore();
    }

    /** A small calendar for crowded pages: no annual labels, refresh text, or legend. */
    private void drawCurrentMonth(Canvas canvas) {
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setColor(0xFFE6EDF3);
        paint.setTextSize(22);
        Calendar local = Calendar.getInstance();
        int year = local.get(Calendar.YEAR), month = local.get(Calendar.MONTH);
        Calendar first = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        first.clear();
        first.set(year, month, 1);
        int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday first.
        String title = new SimpleDateFormat("MMMM", Locale.getDefault()).format(local.getTime());
        canvas.drawText(title + " contributions", 96, 23, paint);
        // The month's numbers sit just right of the title and calendar (7 cells of 24 with gaps of 6).
        float statsX = Math.max(96 + paint.measureText(title + " contributions"), 96 + 7 * 30 - 6) + 32;
        paint.setTextSize(19);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        paint.setColor(0xFFB7C0CA);

        Map<Long, GitHubContributions.Day> byDate = new HashMap<>();
        int monthTotal = 0;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        if (data != null) {
            for (GitHubContributions.Day d : data.days) {
                byDate.put(d.date, d);
                Calendar day = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
                day.setTimeInMillis(d.date);
                if (day.get(Calendar.YEAR) == year && day.get(Calendar.MONTH) == month) {
                    monthTotal += d.count;
                }
            }
        }
        if (data == null) {
            canvas.drawText(failed ? "GitHub data unavailable" : "Loading GitHub data…", 96, 48, paint);
        } else {
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            canvas.drawText(String.format(Locale.US, "%,d this month", monthTotal), statsX, 22, paint);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            String commits = today.equals(data.commitDate) && data.publicPushCommits >= 0
                    ? "Today: " + data.publicPushCommits + " public commit" + (data.publicPushCommits == 1 ? "" : "s")
                    : "Today: public commits unavailable";
            canvas.drawText(commits, statsX, 47, paint);
        }

        final int cell = 24, gap = 6, step = cell + gap;
        final int gridX = 96, gridY = 70;
        String[] weekday = {"M", "T", "W", "T", "F", "S", "S"};
        paint.setTextSize(13);
        paint.setColor(0xFF9AA4AF);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        for (int col = 0; col < 7; col++) canvas.drawText(weekday[col], gridX + col * step + 6, 64, paint);

        paint.setStyle(Paint.Style.FILL);
        for (int dayNumber = 1; dayNumber <= daysInMonth; dayNumber++) {
            int index = offset + dayNumber - 1;
            int row = index / 7, col = index % 7;
            Calendar day = (Calendar) first.clone();
            day.add(Calendar.DAY_OF_MONTH, dayNumber - 1);
            GitHubContributions.Day contribution = byDate.get(day.getTimeInMillis());
            int level = contribution == null ? 0 : Math.max(0, Math.min(4, contribution.level));
            float x = gridX + col * step, y = gridY + row * step;
            paint.setColor(LEVELS[level]);
            canvas.drawRoundRect(x, y, x + cell, y + cell, 4, 4, paint);
            paint.setTextSize(12);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            paint.setColor(level >= 3 ? 0xFFE6EDF3 : 0xFF9AA4AF);
            String n = Integer.toString(dayNumber);
            canvas.drawText(n, x + (cell - paint.measureText(n)) / 2f, y + 15, paint);
        }
    }

    private static java.util.Date parseLocalDay(String day) {
        try { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day); }
        catch (Exception ignored) { return new java.util.Date(); }
    }
}
