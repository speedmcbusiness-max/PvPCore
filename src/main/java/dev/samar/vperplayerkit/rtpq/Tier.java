package dev.samar.vperplayerkit.rtpq;

/** Tier ladder, lowest to highest. The number is the tier's leaderboard points. */
public enum Tier {
    UNRANKED("Unranked", 0, "<gray>"),
    LT6("LT6", 1, "<#8d8d8d>"),
    HT6("HT6", 1.5, "<#a9a9a9>"),
    LT5("LT5", 2, "<#7fbf7f>"),
    HT5("HT5", 2.5, "<#55dd55>"),
    LT4("LT4", 3, "<#5fb7ff>"),
    HT4("HT4", 4, "<#3799DD>"),
    LT3("LT3", 6, "<#c58cff>"),
    HT3("HT3", 10, "<#a35bff>"),
    LT2("LT2", 20, "<#ffb347>"),
    HT2("HT2", 30, "<#ff8c1a>"),
    LT1("LT1", 45, "<#ff5555>"),
    HT1("HT1", 60, "<#ffd700>");

    private final String display;
    private final double points;
    private final String color;

    Tier(String display, double points, String color) {
        this.display = display;
        this.points = points;
        this.color = color;
    }

    public String display() {
        return display;
    }

    public double points() {
        return points;
    }

    public String pointsText() {
        return points == Math.floor(points) ? String.valueOf((int) points) : String.valueOf(points);
    }

    /** MiniMessage coloured tag, e.g. "<#a35bff>HT3</#a35bff>"-style text without closing tag issues. */
    public String colored() {
        return color + display + "<reset>";
    }

    /** "Low Tier 3", "High Tier 4" or "Unranked", as shown in Discord results. */
    public String longName() {
        if (this == UNRANKED) return "Unranked";
        return (display.startsWith("L") ? "Low Tier " : "High Tier ") + display.substring(2);
    }

    public Tier next() {
        Tier[] all = values();
        return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
    }

    public static Tier fromIndex(int index) {
        Tier[] all = values();
        return all[Math.max(0, Math.min(all.length - 1, index))];
    }

    public static Tier fromName(String name) {
        if (name == null) return null;
        String clean = name.trim().toUpperCase();
        if (clean.equals("UR") || clean.equals("NONE")) return UNRANKED;
        for (Tier tier : values()) {
            if (tier.name().equals(clean)) return tier;
        }
        return null;
    }
}
