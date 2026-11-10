package dev.samar.vperplayerkit.spectator;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

public final class SpectatorListener implements Listener {
    private final VPerPlayerKit plugin;

    public SpectatorListener(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!plugin.spectators().isSpectating(player.getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("spectator.block-commands", true)) return;
        if (player.hasPermission("perplayerkit.match.bypass")) return;

        String message = event.getMessage();
        String label = message.length() < 2 ? "" : message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        for (String allowed : plugin.getConfig().getStringList("spectator.allowed-commands")) {
            if (allowed.equalsIgnoreCase(label)) return;
        }
        event.setCancelled(true);
        plugin.msg().send(player, "spec-command-blocked");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.spectators().stop(event.getPlayer(), false);
    }
}
