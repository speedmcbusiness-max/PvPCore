package dev.samar.vperplayerkit.totemprac;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class TotemPracListener implements Listener {
    private final VPerPlayerKit plugin;
    private final TotemPrac prac;

    public TotemPracListener(VPerPlayerKit plugin, TotemPrac prac) {
        this.plugin = plugin;
        this.prac = prac;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        Player player = event.getPlayer();

        // Admin tools
        String tool = prac.toolKind(event.getItem());
        if (tool != null && player.hasPermission("perplayerkit.totemprac.admin")) {
            Block block = event.getClickedBlock();
            if (block == null) return;
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            event.setCancelled(true);

            if (tool.equals(TotemPrac.TOOL_REGION)) {
                if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
                int corner = action == Action.LEFT_CLICK_BLOCK ? 1 : 2;
                plugin.msg().send(player, "totemprac-corner", "{n}", String.valueOf(corner),
                        "{x}", String.valueOf(block.getX()), "{y}", String.valueOf(block.getY()),
                        "{z}", String.valueOf(block.getZ()));
                if (prac.select(player, corner, block.getLocation())) {
                    plugin.msg().send(player, "totemprac-area-set");
                }
            } else if (tool.equals(TotemPrac.TOOL_BUTTON) && action == Action.RIGHT_CLICK_BLOCK) {
                if (!Tag.BUTTONS.isTagged(block.getType())) {
                    plugin.msg().send(player, "totemprac-not-a-button");
                    return;
                }
                prac.setButton(block.getLocation());
                plugin.msg().send(player, "totemprac-button-set");
            }
            return;
        }

        // Players pressing the registered button. It only starts/stops practice for the player who pressed it,
        // so the vanilla button effect (redstone, sound) is blocked.
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && prac.isButton(event.getClickedBlock().getLocation())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            if (!prac.inPracWorld(player) || prac.onCooldown(player)) return;
            prac.toggle(player);
        }
    }

    /** Lethal damage without a totem in hand resets the player instead of killing them. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!prac.isRunning(player.getUniqueId())) return;
        if (event.isCancelled()) return;
        boolean totem = player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
        if (player.getHealth() - event.getFinalDamage() <= 0 && !totem) {
            event.setCancelled(true);
            prac.stop(player, true);
            plugin.msg().send(player, "totemprac-died");
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!prac.hasSession(player.getUniqueId())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        prac.stop(player, false);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (prac.inPracWorld(player)) {
            prac.hideFromPracPlayers(player);                       // arrived
        } else if (prac.spawn() != null && event.getFrom().equals(prac.spawn().getWorld())) {
            prac.showToEveryone(player);                            // left
            prac.aim().stop(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (prac.inPracWorld(player)) prac.hideFromPracPlayers(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        prac.showToEveryone(player);
        if (prac.hasSession(player.getUniqueId()) || prac.inPracWorld(player)) {
            prac.leave(player);
        }
    }

    /** Leaving the practice world by any means (spawn command, other plugin) cleans the player up. */
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Location spawn = prac.spawn();
        if (spawn == null) return;
        Player player = event.getPlayer();
        if (event.getFrom().getWorld() == null || event.getTo() == null || event.getTo().getWorld() == null) return;
        boolean leaving = event.getFrom().getWorld().equals(spawn.getWorld())
                && !event.getTo().getWorld().equals(spawn.getWorld());
        if (!leaving) return;
        prac.stop(player, false);
        prac.aim().stop(player);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.getInventory().clear();
            plugin.kits().heal(player);
        });
    }
}
