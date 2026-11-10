package dev.samar.vperplayerkit.event;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class EventListener implements Listener {
    private final VPerPlayerKit plugin;

    public EventListener(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    /** Nobody gets hurt in the event lobby. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (plugin.events().state() == EventManager.State.OPEN && plugin.events().isBusy(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.events().handleQuit(event.getPlayer());
    }
}
