package dev.samar.vperplayerkit.moderation;

import dev.samar.vperplayerkit.VPerPlayerKit;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.Locale;

/** /sfmode game mode lock and everything a frozen / screensharing player is not allowed to do. */
public final class ModerationListener implements Listener {
    private final VPerPlayerKit plugin;

    public ModerationListener(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private boolean frozen(Player player) {
        return plugin.freeze().isFrozen(player.getUniqueId());
    }

    // ------------------------------------------------------------------ sfmode and join/quit

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (plugin.sfmode().blocks(event.getPlayer(), event.getNewGameMode())) {
            event.setCancelled(true);
            plugin.msg().send(event.getPlayer(), "sfmode-blocked");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.sfmode().restoreOnJoin(event.getPlayer());
        plugin.freeze().handleJoin(event.getPlayer());
        plugin.bans().handleJoin(event.getPlayer());
        plugin.ranks().handleJoin(event.getPlayer());
        plugin.nametags().update(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.freeze().handleQuit(event.getPlayer());
        plugin.sfmode().forget(event.getPlayer().getUniqueId());
        plugin.bans().handleQuit(event.getPlayer());
        plugin.ranks().handleQuit(event.getPlayer());
        plugin.nametags().remove(event.getPlayer());
    }

    // ------------------------------------------------------------------ frozen players

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen(event.getPlayer()) || event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location stay = from.clone();                 // they can still look around
            stay.setYaw(to.getYaw());
            stay.setPitch(to.getPitch());
            event.setTo(stay);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (frozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && frozen(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && frozen(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && frozen(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && frozen(player)) {
            event.setCancelled(true);
            return;
        }
        // banned players cannot see anybody, so they must not be able to hurt anybody either
        if (event.getDamager() instanceof Player attacker && event.getEntity() instanceof Player
                && plugin.bans().isBanned(attacker.getUniqueId())
                && !plugin.getConfig().getBoolean("banned.can-hurt-players", false)) {
            event.setCancelled(true);
        }
    }

    /** The chat of banned players only reaches other banned players and staff (banned.hide-chat). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBannedChat(AsyncChatEvent event) {
        if (!plugin.bans().isBanned(event.getPlayer().getUniqueId())
                || !plugin.getConfig().getBoolean("banned.hide-chat", true)) return;
        event.viewers().removeIf(v -> v instanceof Player viewer && !viewer.equals(event.getPlayer())
                && !plugin.bans().isBanned(viewer.getUniqueId()) && !viewer.hasPermission("perplayerkit.sus"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (frozen(event.getPlayer()) && !plugin.getConfig().getBoolean("freeze.allow-chat", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!frozen(player) || player.hasPermission("perplayerkit.freeze")) return;
        String message = event.getMessage();
        String label = message.length() < 2 ? "" : message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        for (String allowed : plugin.getConfig().getStringList("freeze.allowed-commands")) {
            if (allowed.equalsIgnoreCase(label)) return;
        }
        event.setCancelled(true);
        plugin.msg().send(player, "freeze-command-blocked");
    }
}
