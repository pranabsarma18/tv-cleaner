package com.tvcleaner.home;

/** Plain-JVM checks of the weather helpers that don't need Android. Run with: sh ci/unit-test.sh */
public class WeatherTest {
    public static void main(String[] args) {
        ParseTest.check("sunrise time from ISO", Weather.clockPart("2026-10-24T05:32").equals("05:32"));
        ParseTest.check("bad time is blank", Weather.clockPart("2026-10-24").isEmpty());
        ParseTest.check("12-hour morning", Weather.formatClock("05:32", false).equals("5:32 AM"));
        ParseTest.check("12-hour evening", Weather.formatClock("16:52", false).equals("4:52 PM"));
        ParseTest.check("midnight and noon", Weather.formatClock("00:05", false).equals("12:05 AM")
                && Weather.formatClock("12:00", false).equals("12:00 PM"));
        ParseTest.check("24-hour as is", Weather.formatClock("16:52", true).equals("16:52"));
        ParseTest.check("weather codes", Weather.describe(0).equals("Clear") && Weather.describe(63).equals("Rain")
                && Weather.describe(95).equals("Thunderstorm") && Weather.describe(45).equals("Fog"));
        if (ParseTest.failed > 0) { System.out.println(ParseTest.failed + " failed."); System.exit(1); }
        System.out.println("All good.");
    }
}
