package dev.samar.vperplayerkit.gladiator;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.Totems;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class GladiatorListener implements Listener {
    private final VPerPlayerKit plugin;

    public GladiatorListener(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private GladiatorMatch fightOf(Player player) {
        GladiatorMatch m = plugin.gladiator().matchOf(player.getUniqueId());
        return (m != null && m.fighting()) ? m : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        GladiatorMatch m = fightOf(player);
        if (m == null) return;
        if (m.state != GladiatorMatch.State.ACTIVE || !m.isAlive(player.getUniqueId())) {
            event.setCancelled(true);        // countdown, finished or already knocked out
            return;
        }
        if (event.isCancelled()) return;
        // Lethal hit: a totem in hand pops normally, otherwise one is moved in from the inventory (auto totem)
        if (player.getHealth() - event.getFinalDamage() <= 0 && !Totems.protectedByTotem(plugin, player)) {
            event.setCancelled(true);
            plugin.gladiator().eliminate(m, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        GladiatorMatch m = fightOf(victim);
        if (m == null) return;
        Object source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            source = shooter;
        }
        if (source instanceof Player attacker) {
            int a = m.teamOf(attacker.getUniqueId());
            // no friendly fire, and nobody outside the match can interfere
            if (a < 0 || a == m.teamOf(victim.getUniqueId())) event.setCancelled(true);
        }
    }

    @EventHandler
    public void onGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding() || !(event.getEntity() instanceof Player player)) return;
        GladiatorMatch m = fightOf(player);
        if (m == null) return;
        if (!m.elytra(m.teamOf(player.getUniqueId()))) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GladiatorMatch m = fightOf(player);
        if (m == null) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        plugin.gladiator().eliminate(m, player);
        plugin.getServer().getScheduler().runTask(plugin, () -> player.spigot().respawn());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.COMMAND) return;
        if (fightOf(event.getPlayer()) != null) {
            event.setCancelled(true);
            plugin.msg().send(event.getPlayer(), "no-teleport-in-duel");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.gladiator().handleQuit(event.getPlayer());
    }
}
