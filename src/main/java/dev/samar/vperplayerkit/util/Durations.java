package dev.samar.vperplayerkit.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Durations {
    private static final Pattern PART = Pattern.compile("(\\d+)([smhdSMHD]?)");

    private Durations() {}

    /** "30" / "30s" / "10m" / "2h" / "1d" / "1h30m" in seconds, or -1 when it is not a duration. */
    public static long parseSeconds(String input) {
        if (input == null || input.isBlank()) return -1;
        Matcher m = PART.matcher(input.trim());
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return -1;
            long n = Long.parseLong(m.group(1));
            switch (m.group(2).toLowerCase()) {
                case "m" -> total += n * 60;
                case "h" -> total += n * 3600;
                case "d" -> total += n * 86400;
                default -> total += n;
            }
            end = m.end();
        }
        return (end == input.trim().length() && end > 0) ? total : -1;
    }

    /** 3700 -> "1h 1m" */
    public static String format(long seconds) {
        if (seconds < 60) return seconds + "s";
        StringBuilder sb = new StringBuilder();
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 && d == 0 && h == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
