package com.tvcleaner.home;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Plain-JVM checks of the GitHub calendar parser. Run with: sh ci/unit-test.sh */
public class ParseTest {
    static int failed;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) failed++;
    }

    /** Builds markup shaped like github.com/users/<user>/contributions: one <td> and one <tool-tip> per day. */
    static String calendar(int days, String tipText) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.add(Calendar.DAY_OF_YEAR, -(days - 1));
        StringBuilder b = new StringBuilder("<table><tbody>");
        for (int i = 0; i < days; i++) {
            String id = "contribution-day-component-" + (i % 7) + "-" + (i / 7);
            b.append("<tr><td tabindex=\"-1\" data-ix=\"").append(i / 7).append("\" aria-selected=\"false\" ")
             .append("data-date=\"").append(f.format(c.getTime())).append("\" id=\"").append(id)
             .append("\" data-level=\"").append(i % 5).append("\" class=\"ContributionCalendar-day\"></td></tr>\n");
            b.append("<tool-tip id=\"tooltip-").append(i).append("\" for=\"").append(id)
             .append("\" popover=\"manual\" data-direction=\"n\">").append(tipText.replace("N", Integer.toString(i % 3)))
             .append("</tool-tip>\n");
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return b.append("</tbody></table>").toString();
    }

    public static void main(String[] args) throws Exception {
        GitHubContributions.Data d = GitHubContributions.parse(calendar(371, "N contributions on May 1st."), 1L);
        check("reads every day", d.days.size() == 371);
        check("reads levels 0-4", d.days.get(4).level == 4 && d.days.get(5).level == 0);
        check("reads counts", d.days.get(1).count == 1 && d.days.get(2).count == 2 && d.days.get(3).count == 0);
        check("a recent day is counted in the last year", d.lastYearTotal() > 0);

        d = GitHubContributions.parse(calendar(371, "1,234 contributions on May 1st."), 1L);
        check("thousands separators", d.days.get(0).count == 1234);

        d = GitHubContributions.parse(calendar(371, "No contributions on May 1st."), 1L);
        check("'No contributions' is zero", d.days.get(0).count == 0 && d.days.get(0).level == 0);

        try { GitHubContributions.parse(calendar(100, "N contributions"), 1L); check("rejects a partial calendar", false); }
        catch (Exception e) { check("rejects a partial calendar", true); }

        try { GitHubContributions.parse("<html>Rate limited</html>", 1L); check("rejects a page without a calendar", false); }
        catch (Exception e) { check("rejects a page without a calendar", true); }

        // GitHub renaming the cell attributes must fail loudly, not draw an empty graph.
        try { GitHubContributions.parse(calendar(371, "N contributions").replace("data-level", "data-lvl"), 1L); check("rejects changed markup", false); }
        catch (Exception e) { check("rejects changed markup", true); }

        if (failed > 0) { System.out.println(failed + " failed."); System.exit(1); }
        System.out.println("All good.");
    }
}
