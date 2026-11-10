package dev.samar.vperplayerkit.ffa;

import dev.samar.vperplayerkit.VPerPlayerKit;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import dev.samar.vperplayerkit.util.Totems;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class FfaListener implements Listener {
    private final VPerPlayerKit plugin;

    public FfaListener(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private FfaManager.Match matchOf(Player player) {
        return plugin.ffa().matchOf(player.getUniqueId());
    }

    // ------------------------------------------------------------------ combat

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        FfaManager.Match m = matchOf(player);
        if (m == null) return;
        if (m.state() != FfaManager.Match.State.ACTIVE || !m.isAlive(player.getUniqueId())) {
            event.setCancelled(true);          // countdown, finished, or already knocked out
            return;
        }
        if (event.isCancelled()) return;
        // Lethal hit: a totem in hand pops normally, otherwise one is moved in from the inventory (auto totem)
        if (player.getHealth() - event.getFinalDamage() <= 0 && !Totems.protectedByTotem(plugin, player)) {
            event.setCancelled(true);
            plugin.ffa().eliminate(m, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        FfaManager.Match m = matchOf(victim);
        if (m == null) return;
        Object source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            source = shooter;
        }
        // everyone inside the match may fight each other, outsiders may not interfere
        if (source instanceof Player attacker && matchOf(attacker) != m) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        FfaManager.Match m = matchOf(player);
        if (m == null) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        plugin.ffa().eliminate(m, player);
        plugin.getServer().getScheduler().runTask(plugin, () -> player.spigot().respawn());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.COMMAND) return;
        if (matchOf(event.getPlayer()) != null) {
            event.setCancelled(true);
            plugin.msg().send(event.getPlayer(), "no-teleport-in-duel");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.ffa().handleQuit(event.getPlayer());
    }

    // ------------------------------------------------------------------ arena protection

    private boolean protectedFor(Player player) {
        FfaManager.Match m = matchOf(player);
        return m != null && !m.rtp && !plugin.getConfig().getBoolean("ffa.allow-building", false);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent event) {
        if (protectedFor(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        if (protectedFor(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        FfaManager.Match m = plugin.ffa().matchAt(event.getLocation());
        if (m != null && !m.rtp && !plugin.getConfig().getBoolean("ffa.allow-building", false)) {
            event.blockList().clear();          // crystals hurt players but do not destroy the arena
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        FfaManager.Match m = plugin.ffa().matchAt(event.getBlock().getLocation());
        if (m != null && !m.rtp && !plugin.getConfig().getBoolean("ffa.allow-building", false)) {
            event.blockList().clear();
        }
    }

    // ------------------------------------------------------------------ admin wand and chat prompt

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!plugin.ffa().isWand(event.getItem()) || !player.hasPermission("perplayerkit.ffa.admin")) return;
        event.setCancelled(true);

        Action action = event.getAction();
        if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) && player.isSneaking()) {
            plugin.ffa().promptName(player);                                   // shift + right click = finish
        } else if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            plugin.ffa().toggle(player, event.getClickedBlock());
        } else if (action == Action.LEFT_CLICK_BLOCK && event.getClickedBlock() != null) {
            plugin.ffa().remove(player, event.getClickedBlock());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.ffa().isAwaitingName(player.getUniqueId())) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.ffa().handleName(player, text));
    }
}
