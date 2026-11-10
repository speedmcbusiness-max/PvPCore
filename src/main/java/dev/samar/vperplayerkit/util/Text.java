package dev.samar.vperplayerkit.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final java.util.regex.Pattern LEGACY =
            java.util.regex.Pattern.compile("&(#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])");
    private static final java.util.Map<Character, String> LEGACY_TAGS = java.util.Map.ofEntries(
            java.util.Map.entry('0', "<black>"), java.util.Map.entry('1', "<dark_blue>"),
            java.util.Map.entry('2', "<dark_green>"), java.util.Map.entry('3', "<dark_aqua>"),
            java.util.Map.entry('4', "<dark_red>"), java.util.Map.entry('5', "<dark_purple>"),
            java.util.Map.entry('6', "<gold>"), java.util.Map.entry('7', "<gray>"),
            java.util.Map.entry('8', "<dark_gray>"), java.util.Map.entry('9', "<blue>"),
            java.util.Map.entry('a', "<green>"), java.util.Map.entry('b', "<aqua>"),
            java.util.Map.entry('c', "<red>"), java.util.Map.entry('d', "<light_purple>"),
            java.util.Map.entry('e', "<yellow>"), java.util.Map.entry('f', "<white>"),
            java.util.Map.entry('k', "<obfuscated>"), java.util.Map.entry('l', "<bold>"),
            java.util.Map.entry('m', "<strikethrough>"), java.util.Map.entry('n', "<underlined>"),
            java.util.Map.entry('o', "<italic>"), java.util.Map.entry('r', "<reset>"));

    private Text() {}

    /** MiniMessage without the default italic that item names get. */
    public static Component item(String raw) {
        return MM.deserialize(raw).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** "&#FF0000&nBANNED" or "&7| &f" turned into MiniMessage ("<#FF0000><underlined>BANNED"). */
    public static String legacyToMini(String raw) {
        String in = raw.replace('§', '&');
        java.util.regex.Matcher m = LEGACY.matcher(in);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String code = m.group(1);
            String tag = code.startsWith("#") ? "<" + code + ">" : LEGACY_TAGS.get(Character.toLowerCase(code.charAt(0)));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(tag));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A component from text that uses &-colors (including &#RRGGBB hex) and/or MiniMessage. */
    public static Component legacy(String raw) {
        return MM.deserialize(legacyToMini(raw));
    }

    public static Component mm(String raw) {
        return MM.deserialize(raw);
    }

    public static String replace(String raw, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace(kv[i], kv[i + 1]);
        }
        return raw;
    }

    /** 90 seconds -> "1m", 5 hours -> "5h", 2 days -> "2d". */
    public static String agoShort(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }

    /** "stripe_bottom" -> "Stripe Bottom" */
    public static String pretty(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.toLowerCase().split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
