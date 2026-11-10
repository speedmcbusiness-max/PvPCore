package dev.samar.vperplayerkit.moderation;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Banned" players (from /offend) are not kicked: they can still play but cannot see any other player, and a red
 * "BANNED" (banned.tag) floats above their nametag for everybody else. /unoffend lifts it. Stored in the database.
 */
public final class BanManager {

    public record BanInfo(String reason, String staff, long time) {
    }

    private static final String SEP = "\u001f";

    private final VPerPlayerKit plugin;
    private final Map<UUID, BanInfo> banned = new ConcurrentHashMap<>();
    private final Map<UUID, TextDisplay> tags = new HashMap<>();

    public BanManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override
            public void run() {
                followTags();
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    public boolean isBanned(UUID id) {
        return banned.containsKey(id);
    }

    public BanInfo info(UUID id) {
        return banned.get(id);
    }

    // ------------------------------------------------------------------ ban / unban

    public void ban(CommandSender staff, UUID uuid, String name, String reason) {
        BanInfo info = new BanInfo(reason, staff.getName(), System.currentTimeMillis());
        banned.put(uuid, info);
        byte[] data = String.join(SEP, reason, staff.getName(), String.valueOf(info.time())).getBytes(StandardCharsets.UTF_8);
        plugin.async(() -> plugin.storage().put(uuid.toString(), "banned", "main", data));
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            apply(online);
            plugin.msg().send(online, "banned-you", "{reason}", reason);
        }
    }

    /** Returns false when the player was not banned. */
    public boolean unban(UUID uuid) {
        if (banned.remove(uuid) == null) return false;
        plugin.async(() -> plugin.storage().delete(uuid.toString(), "banned", "main"));
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) release(online);
        return true;
    }

    // ------------------------------------------------------------------ join / quit

    public void handleJoin(Player player) {
        // nobody who is banned can see the new player
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player) && banned.containsKey(other.getUniqueId()) && hidesOthers()) {
                other.hidePlayer(plugin, player);
            }
        }
        UUID id = player.getUniqueId();
        String owner = id.toString();
        plugin.async(() -> plugin.storage().get(owner, "banned", "main"), (byte[] data) -> {
            if (data == null || !player.isOnline()) return;
            String[] p = new String(data, StandardCharsets.UTF_8).split(SEP, -1);
            long time = 0;
            try {
                time = p.length > 2 ? Long.parseLong(p[2]) : 0;
            } catch (NumberFormatException ignored) {
                // keep 0
            }
            banned.put(id, new BanInfo(p.length > 0 ? p[0] : "", p.length > 1 ? p[1] : "", time));
            apply(player);
            plugin.msg().send(player, "banned-you", "{reason}", p.length > 0 ? p[0] : "");
        });
    }

    public void handleQuit(Player player) {
        removeTag(player.getUniqueId());
    }

    private boolean hidesOthers() {
        return plugin.getConfig().getBoolean("banned.hide-others", true);
    }

    // ------------------------------------------------------------------ the restrictions

    private void apply(Player player) {
        if (hidesOthers()) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(player)) player.hidePlayer(plugin, other);
            }
        }
        spawnTag(player);
        plugin.nametags().update(player);
    }

    private void release(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) player.showPlayer(plugin, other);
        }
        removeTag(player.getUniqueId());
        plugin.nametags().update(player);
        plugin.msg().send(player, "banned-lifted");
    }

    /** Other features that show players again (e.g. leaving the totem practice world) call this to hide them from banned players. */
    public void reapplyHiding() {
        if (!hidesOthers()) return;
        for (UUID id : banned.keySet()) {
            Player b = Bukkit.getPlayer(id);
            if (b == null) continue;
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.equals(b)) b.hidePlayer(plugin, other);
            }
        }
    }

    // ------------------------------------------------------------------ the BANNED text above the nametag

    private double offset() {
        return plugin.getConfig().getDouble("banned.tag-offset", 0.55);
    }

    private void spawnTag(Player player) {
        removeTag(player.getUniqueId());
        String text = plugin.getConfig().getString("banned.tag", "&#FF0000&nBANNED");
        if (text == null || text.isBlank()) return;
        TextDisplay display = player.getWorld().spawn(player.getLocation().add(0, player.getHeight() + offset(), 0),
                TextDisplay.class, d -> {
                    d.text(Text.legacy(text));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setPersistent(false);
                    d.setTeleportDuration(2);
                });
        player.hideEntity(plugin, display);                 // the banned player does not see it above their own head
        tags.put(player.getUniqueId(), display);
    }

    private void removeTag(UUID id) {
        TextDisplay display = tags.remove(id);
        if (display != null && display.isValid()) display.remove();
    }

    private void followTags() {
        for (Map.Entry<UUID, TextDisplay> e : new HashMap<>(tags).entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            TextDisplay d = e.getValue();
            if (p == null || !banned.containsKey(e.getKey()) || !d.isValid()) {
                removeTag(e.getKey());
                continue;
            }
            d.teleport(p.getLocation().add(0, p.getHeight() + offset(), 0));
        }
    }

    public void shutdown() {
        for (UUID id : new java.util.ArrayList<>(tags.keySet())) removeTag(id);
    }
}
