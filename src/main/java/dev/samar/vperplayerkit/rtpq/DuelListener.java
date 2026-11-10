package dev.samar.vperplayerkit.rtpq;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.Totems;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Iterator;

/** Duel rules: lethal hits become round wins, arena blocks are recorded for restoring, quitters forfeit. */
public final class DuelListener implements Listener {
    private final VPerPlayerKit plugin;

    public DuelListener(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private DuelManager.Duel duelOf(Player player) {
        return plugin.duels().duelOf(player.getUniqueId());
    }

    private static boolean hasTotem(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    // ------------------------------------------------------------------ combat

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        DuelManager.Duel duel = duelOf(player);
        if (duel == null) return;

        if (duel.state() != DuelManager.State.ACTIVE) {
            event.setCancelled(true);       // countdown, between rounds and after the match
            return;
        }
        if (event.isCancelled()) return;
        // A lethal hit without a totem ends the round instead of killing the player.
        if (player.getHealth() - event.getFinalDamage() <= 0 && !Totems.protectedByTotem(plugin, player)) {
            event.setCancelled(true);
            plugin.duels().onKill(duel, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        DuelManager.Duel duel = duelOf(victim);
        if (duel == null) return;
        Object source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            source = shooter;
        }
        if (source instanceof Player attacker && duel.indexOf(attacker.getUniqueId()) < 0) {
            event.setCancelled(true);       // outsiders cannot interfere
        }
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player player)) return;
        DuelManager.Duel duel = duelOf(player);
        if (duel == null) return;
        int index = duel.indexOf(player.getUniqueId());
        if (index >= 0 && duel.state() == DuelManager.State.ACTIVE) duel.pops()[index]++;
    }

    /** Fallback: something killed the player without going through the damage event (e.g. /kill). */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        DuelManager.Duel duel = duelOf(player);
        if (duel == null) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        plugin.duels().onKill(duel, player);
        plugin.getServer().getScheduler().runTask(plugin, () -> player.spigot().respawn());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        DuelManager.Duel duel = duelOf(event.getPlayer());
        if (duel == null) return;
        int index = duel.indexOf(event.getPlayer().getUniqueId());
        if (index >= 0) event.setRespawnLocation(duel.spawn(index));
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.COMMAND) return;
        DuelManager.Duel duel = duelOf(event.getPlayer());
        if (duel != null && duel.state() != DuelManager.State.ENDED) {
            event.setCancelled(true);
            plugin.msg().send(event.getPlayer(), "no-teleport-in-duel");
        }
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.queue().remove(event.getPlayer().getUniqueId());
        plugin.duels().handleQuit(event.getPlayer());
        plugin.cache().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Was in a match when the server stopped or crashed: clear the kit leftovers and send them to the lobby.
        String owner = player.getUniqueId().toString();
        plugin.async(() -> plugin.storage().exists(owner, "inmatch", "on"), (Boolean stale) -> {
            if (!stale || !player.isOnline() || plugin.inMatch(player)) return;
            plugin.sendToLobby(player);
            plugin.msg().send(player, "match-stale");
        });
        plugin.cache().refresh(player.getUniqueId(), player.getName());
        // keep leaderboard names current
        plugin.async(() -> {
            plugin.discord().loadTag(player.getUniqueId());
            PlayerStats stats = plugin.storage().loadStats(player.getUniqueId(), player.getName());
            if (stats.played > 0 || stats.tier > 0) plugin.storage().saveStats(stats);
        });
    }

    // ------------------------------------------------------------------ arena block recording

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel == null) return;
        duel.recordState(event.getBlockReplacedState().getLocation(), event.getBlockReplacedState().getBlockData());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel == null) return;
        if (duel.isProtected(event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        duel.record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.getLocation(), event.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.getBlock().getLocation(), event.blockList().iterator());
    }

    private void handleExplosion(org.bukkit.Location where, Iterator<Block> blocks) {
        DuelManager.Duel duel = plugin.duels().duelAt(where);
        if (duel == null) return;
        while (blocks.hasNext()) {
            Block block = blocks.next();
            if (duel.isProtected(block)) {
                blocks.remove();
            } else {
                duel.record(block);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel != null) duel.record(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel != null) duel.record(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel != null) duel.record(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel != null) duel.record(event.getBlock());
    }

    // ------------------------------------------------------------------ more block changes (for the rebuild)

    @EventHandler(ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel == null) return;
        for (BlockState state : event.getReplacedBlockStates()) duel.recordState(state.getLocation(), state.getBlockData());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel != null) duel.record(event.getBlock());
    }

    /** Water and lava flowing. */
    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getToBlock().getLocation());
        if (duel != null) duel.record(event.getToBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel != null) duel.record(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel != null) duel.record(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeaves(LeavesDecayEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel != null) duel.record(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel == null) return;
        for (Block moved : event.getBlocks()) {
            duel.record(moved);
            duel.record(moved.getRelative(event.getDirection()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        DuelManager.Duel duel = plugin.duels().duelAt(event.getBlock().getLocation());
        if (duel == null) return;
        for (Block moved : event.getBlocks()) {
            duel.record(moved);
            duel.record(moved.getRelative(event.getDirection()));
        }
    }
}
