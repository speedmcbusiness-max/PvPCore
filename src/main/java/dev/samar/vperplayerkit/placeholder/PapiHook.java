package dev.samar.vperplayerkit.placeholder;

import dev.samar.vperplayerkit.VPerPlayerKit;

/** Kept separate so PlaceholderAPI classes are only loaded when the plugin is installed. */
public final class PapiHook {
    private PapiHook() {}

    /** Replaces PlaceholderAPI placeholders (e.g. from your flags plugin) in a text. Only call when PlaceholderAPI is enabled. */
    public static String parse(org.bukkit.entity.Player player, String text) {
        return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
    }

    public static void register(VPerPlayerKit plugin) {
        new VPerPlayerKitExpansion(plugin).register();
    }
}
