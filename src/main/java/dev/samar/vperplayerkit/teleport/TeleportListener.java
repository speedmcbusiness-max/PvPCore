package dev.samar.vperplayerkit.teleport;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class TeleportListener implements Listener {
    private final VPerPlayerKit plugin;

    public TeleportListener(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    /** /back also returns to the place where you died. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.getConfig().getBoolean("back.track-death", true)) return;
        if (plugin.inMatch(player)) return;                 // match deaths are cancelled/handled by the match
        plugin.teleports().remember(player, player.getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.teleports().handleQuit(event.getPlayer());
    }
}
