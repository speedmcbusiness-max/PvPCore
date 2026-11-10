package dev.samar.vperplayerkit.listener;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * During a queue duel or a Gladiator fight only a few commands work (default: /regear, /leave, /state).
 * The kit commands are always blocked there; other commands too unless match-commands.block-all-others is false.
 * Players with perplayerkit.match.bypass are never restricted.
 */
public final class MatchCommandListener implements Listener {
    private final VPerPlayerKit plugin;
    private final Set<String> kitCommands = new HashSet<>(List.of(
            "kit", "k", "kitroom", "pk", "publickit", "loadselectedkit", "lsk"));

    public MatchCommandListener(VPerPlayerKit plugin) {
        this.plugin = plugin;
        for (int i = 1; i <= 14; i++) {
            kitCommands.add("kit" + i);
            kitCommands.add("k" + i);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!plugin.inMatch(player) || player.hasPermission("perplayerkit.match.bypass")) return;

        String message = event.getMessage();
        if (message.length() < 2) return;
        String label = message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);          // /plugin:command

        for (String allowed : plugin.getConfig().getStringList("match-commands.allowed")) {
            if (allowed.equalsIgnoreCase(label)) return;
        }
        boolean blockAll = plugin.getConfig().getBoolean("match-commands.block-all-others", true);
        if (blockAll || kitCommands.contains(label)) {
            event.setCancelled(true);
            plugin.msg().send(player, "match-command-blocked");
        }
    }
}
