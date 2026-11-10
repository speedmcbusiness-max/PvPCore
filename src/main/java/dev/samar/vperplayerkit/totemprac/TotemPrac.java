package dev.samar.vperplayerkit.totemprac;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.config.DataStore;
import dev.samar.vperplayerkit.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Totem practice: players teleport to a practice world and press an admin-registered button to start/stop.
 * While running they are hit a configurable number of times per cycle inside the admin-defined damage area.
 */
public final class TotemPrac {
    public static final String TOOL_REGION = "region";
    public static final String TOOL_BUTTON = "button";

    private static final class Session {
        BukkitTask countdown;
        BukkitTask damage;
        boolean running;
    }

    private final VPerPlayerKit plugin;
    private final DataStore data;
    private final NamespacedKey toolKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, Long> lastPress = new HashMap<>();
    private final AimTarget aim;

    public TotemPrac(VPerPlayerKit plugin) {
        this.plugin = plugin;
        this.data = plugin.data();
        this.toolKey = new NamespacedKey(plugin, "totemprac_tool");
        this.aim = new AimTarget(plugin);
    }

    public AimTarget aim() {
        return aim;
    }

    // ------------------------------------------------------------------ settings

    public double damage() {
        return data.yml().getDouble("totemprac.settings.damage", 100.0);
    }

    public int hits() {
        return Math.max(1, data.yml().getInt("totemprac.settings.hits", 2));
    }

    public int intervalTicks() {
        return Math.max(10, data.yml().getInt("totemprac.settings.interval-ticks", 50));   // 2.5 seconds
    }

    public int gapTicks() {
        return Math.max(1, data.yml().getInt("totemprac.settings.gap-ticks", 10));
    }

    public int prepareSeconds() {
        return Math.max(0, data.yml().getInt("totemprac.settings.prepare-seconds", 3));
    }

    public void setSetting(String key, Object value) {
        data.yml().set("totemprac.settings." + key, value);
        data.save();
    }

    // ------------------------------------------------------------------ admin tools

    public ItemStack tool(String kind) {
        ItemStack item = new ItemStack(kind.equals(TOOL_REGION) ? Material.GOLDEN_AXE : Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item(kind.equals(TOOL_REGION)
                ? "<gold>Totem Area Selector" : "<red>Totem Button Selector"));
        meta.lore(java.util.List.of(Text.item(kind.equals(TOOL_REGION)
                ? "<gray>Left click: position 1, right click: position 2"
                : "<gray>Right click the button players will press")));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, kind);
        item.setItemMeta(meta);
        return item;
    }

    public String toolKind(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    /** Stores a selection corner (1 or 2); saves the area once both exist. Returns true when the area was saved. */
    public boolean select(Player admin, int corner, Location block) {
        Location[] pair = selections.computeIfAbsent(admin.getUniqueId(), k -> new Location[2]);
        pair[corner - 1] = block;
        if (pair[0] == null || pair[1] == null) return false;
        if (!pair[0].getWorld().equals(pair[1].getWorld())) {
            plugin.msg().send(admin, "totemprac-same-world");
            return false;
        }
        var yml = data.yml();
        yml.set("totemprac.region.world", pair[0].getWorld().getName());
        yml.set("totemprac.region.min-x", Math.min(pair[0].getBlockX(), pair[1].getBlockX()));
        yml.set("totemprac.region.min-y", Math.min(pair[0].getBlockY(), pair[1].getBlockY()));
        yml.set("totemprac.region.min-z", Math.min(pair[0].getBlockZ(), pair[1].getBlockZ()));
        yml.set("totemprac.region.max-x", Math.max(pair[0].getBlockX(), pair[1].getBlockX()));
        yml.set("totemprac.region.max-y", Math.max(pair[0].getBlockY(), pair[1].getBlockY()));
        yml.set("totemprac.region.max-z", Math.max(pair[0].getBlockZ(), pair[1].getBlockZ()));
        data.save();
        selections.remove(admin.getUniqueId());
        return true;
    }

    public void deleteArea() {
        data.clear("totemprac.region");
    }

    public boolean hasArea() {
        return data.yml().contains("totemprac.region.world");
    }

    public boolean inArea(Location loc) {
        var yml = data.yml();
        if (!hasArea() || loc.getWorld() == null || !loc.getWorld().getName().equals(yml.getString("totemprac.region.world"))) {
            return false;
        }
        return loc.getX() >= yml.getInt("totemprac.region.min-x") && loc.getX() < yml.getInt("totemprac.region.max-x") + 1
                && loc.getY() >= yml.getInt("totemprac.region.min-y") && loc.getY() < yml.getInt("totemprac.region.max-y") + 1
                && loc.getZ() >= yml.getInt("totemprac.region.min-z") && loc.getZ() < yml.getInt("totemprac.region.max-z") + 1;
    }

    public void setButton(Location block) {
        data.setLocation("totemprac.button", block);
    }

    public boolean isButton(Location block) {
        Location button = data.getLocation("totemprac.button");
        return button != null && button.getWorld().equals(block.getWorld())
                && button.getBlockX() == block.getBlockX() && button.getBlockY() == block.getBlockY()
                && button.getBlockZ() == block.getBlockZ();
    }

    // ------------------------------------------------------------------ privacy

    /** Players in the practice world cannot see each other, everybody practices alone. */
    public void hideFromPracPlayers(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player) || !inPracWorld(other)) continue;
            player.hidePlayer(plugin, other);
            other.hidePlayer(plugin, player);
        }
    }

    /** Called when someone leaves the practice world: makes them visible to (and able to see) everybody again. */
    public void showToEveryone(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(player)) continue;
            player.showPlayer(plugin, other);
            other.showPlayer(plugin, player);
        }
        plugin.bans().reapplyHiding();
    }

    /** One press per second per player, so the button cannot be spammed into start/stop loops. */
    public boolean onCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastPress.get(player.getUniqueId());
        if (last != null && now - last < 1000) return true;
        lastPress.put(player.getUniqueId(), now);
        return false;
    }

    // ------------------------------------------------------------------ players

    public Location spawn() {
        return data.getLocation("totemprac.spawn");
    }

    public boolean inPracWorld(Player player) {
        Location spawn = spawn();
        return spawn != null && player.getWorld().equals(spawn.getWorld());
    }

    /** /totemprac: go to the practice world, or back to spawn when already there. */
    public void enterOrLeave(Player player) {
        Location spawn = spawn();
        if (spawn == null) {
            plugin.msg().send(player, "totemprac-not-set");
            return;
        }
        if (inPracWorld(player)) {
            leave(player);
            return;
        }
        player.teleport(spawn);
        plugin.msg().send(player, "totemprac-welcome");
    }

    /** Back to spawn: inventory cleared, full health and saturation. */
    public void leave(Player player) {
        aim.stop(player);
        stop(player, false);
        plugin.sendToLobby(player);
    }

    public boolean hasSession(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    public boolean isRunning(UUID uuid) {
        Session s = sessions.get(uuid);
        return s != null && s.running;
    }

    public void toggle(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            stop(player, true);
            plugin.msg().send(player, "totemprac-stopped");
        } else {
            start(player);
        }
    }

    private void start(Player player) {
        if (!hasArea()) {
            plugin.msg().send(player, "totemprac-no-area");
            return;
        }
        Session session = new Session();
        sessions.put(player.getUniqueId(), session);
        fillTotems(player);
        plugin.kits().heal(player);

        final int[] left = {prepareSeconds()};
        session.countdown = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
                    cancel();
                    return;
                }
                if (left[0] > 0) {
                    plugin.msg().send(player, "totemprac-countdown", "{n}", String.valueOf(left[0]));
                    left[0]--;
                    return;
                }
                cancel();
                session.running = true;
                plugin.msg().send(player, "totemprac-started");
                beginDamage(player, session);
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void beginDamage(Player player, Session session) {
        session.damage = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || sessions.get(player.getUniqueId()) != session) {
                    cancel();
                    return;
                }
                if (!inArea(player.getLocation())) return;
                for (int i = 0; i < hits(); i++) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> hit(player, session), (long) i * gapTicks());
                }
            }
        }.runTaskTimer(plugin, 0L, intervalTicks());
    }

    private void hit(Player player, Session session) {
        if (!player.isOnline() || sessions.get(player.getUniqueId()) != session || !inArea(player.getLocation())) return;
        player.setNoDamageTicks(0);
        player.damage(damage());
    }

    /** Stops the session. With restore the player is healed to full and gets totems everywhere again. */
    public void stop(Player player, boolean restore) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            if (session.countdown != null) session.countdown.cancel();
            if (session.damage != null) session.damage.cancel();
        }
        if (restore && player.isOnline()) {
            plugin.kits().heal(player);
            fillTotems(player);
        }
    }

    public void fillTotems(Player player) {
        for (int i = 0; i < 36; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.TOTEM_OF_UNDYING));
        }
        player.getInventory().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        player.updateInventory();
    }

    public void shutdown() {
        for (UUID id : new java.util.ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) stop(p, false);
        }
        for (Player p : Bukkit.getOnlinePlayers()) showToEveryone(p);
        aim.shutdown();
    }
}
