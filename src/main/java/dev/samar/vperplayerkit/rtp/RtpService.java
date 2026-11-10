package dev.samar.vperplayerkit.rtp;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * /rtp <biome> teleports to a random spot in that biome of the RTP world (rtpq.world), /qrtp picks a new random spot
 * in the world the player is already in. Only those worlds work, nether/end/lobby are rejected.
 */
public final class RtpService {
    private final VPerPlayerKit plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> searching = new HashSet<>();

    public RtpService(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    /** Seconds until the player may use /rtp or /qrtp again (0 = ready). */
    public long cooldownLeft(UUID id) {
        int cooldown = plugin.getConfig().getInt("rtp.cooldown-seconds", 5);
        Long last = cooldowns.get(id);
        if (cooldown <= 0 || last == null) return 0;
        return Math.max(0, cooldown - (System.currentTimeMillis() - last) / 1000);
    }

    /** The biome keys players can pick (keys of rtp.biomes). */
    public List<String> biomes() {
        return plugin.locator().biomes();
    }

    private boolean allowed(Player player) {
        UUID id = player.getUniqueId();
        if (plugin.inMatch(player) || plugin.queue().isQueued(id) || plugin.duels().isBusy(id)
                || plugin.gladiator().isBusy(id) || plugin.ffa().isBusy(id) || plugin.busy(id)) {
            plugin.msg().send(player, "rtp-busy");
            return false;
        }
        if (searching.contains(id)) {
            plugin.msg().send(player, "rtp-searching");
            return false;
        }
        int cooldown = plugin.getConfig().getInt("rtp.cooldown-seconds", 5);
        if (cooldown > 0 && !player.hasPermission("perplayerkit.rtp.bypass")) {
            Long last = cooldowns.get(id);
            if (last != null) {
                long remaining = cooldown - (System.currentTimeMillis() - last) / 1000;
                if (remaining > 0) {
                    plugin.msg().send(player, "rtp-cooldown", "{seconds}", String.valueOf(remaining));
                    return false;
                }
            }
        }
        return true;
    }

    /** /rtp <biome> and the dialog buttons. */
    public void toBiome(Player player, String raw) {
        String key = raw == null ? "" : raw.toLowerCase();
        if (key.equals("mushrooms")) key = "mushroom";
        if (!plugin.locator().isBiome(key)) {
            plugin.msg().send(player, "rtp-invalid", "{list}", String.join(", ", biomes()));
            return;
        }
        World world = plugin.locator().worldFor(key);
        if (world == null) {
            plugin.msg().send(player, "rtp-no-world", "{biome}", Text.pretty(key));
            return;
        }
        if (!allowed(player)) return;
        start(player, world, null, Text.pretty(key), key);
    }

    /** /qrtp: a different place in the same world. */
    public void quick(Player player) {
        World world = player.getWorld();
        if (!plugin.duels().isRtpWorld(world) && !plugin.getConfig().getBoolean("rtp.quick-any-world", false)) {
            plugin.msg().send(player, "rtp-world-not-allowed", "{list}", String.join(", ", biomes()));
            return;
        }
        if (!allowed(player)) return;
        start(player, world, player.getLocation(), Text.pretty(world.getName()), "auto");
    }

    private void start(Player player, World world, Location avoid, String label, String biome) {
        UUID id = player.getUniqueId();
        searching.add(id);
        plugin.msg().send(player, "rtp-searching");
        find(world, biome, avoid, Math.max(5, plugin.getConfig().getInt("rtp.attempts", 40)), loc -> {
            searching.remove(id);
            if (!player.isOnline()) return;
            if (plugin.inMatch(player) || plugin.duels().isBusy(id)) return;     // joined something meanwhile
            player.teleport(loc);
            cooldowns.put(id, System.currentTimeMillis());
            plugin.msg().send(player, "rtp-teleported", "{biome}", label, "{x}", String.valueOf(loc.getBlockX()),
                    "{y}", String.valueOf(loc.getBlockY()), "{z}", String.valueOf(loc.getBlockZ()));
        }, () -> {
            searching.remove(id);
            if (player.isOnline()) plugin.msg().send(player, "rtp-fail");
        });
    }

    private void find(World world, String biome, Location avoid, int attempts, Consumer<Location> done, Runnable fail) {
        if (attempts <= 0) {
            fail.run();
            return;
        }
        Runnable retry = () -> find(world, biome, avoid, attempts - 1, done, fail);
        int[] picked = plugin.locator().pick(world, biome, plugin.getConfig().getInt("rtp.radius", 5000));
        if (picked == null) {                                      // biome not found around that random point
            retry.run();
            return;
        }
        int x = picked[0];
        int z = picked[1];
        if (plugin.duels().tooClose(world, x, z)) {                // never next to someone's arena
            retry.run();
            return;
        }
        if (avoid != null) {
            double half = world.getWorldBorder().getSize() / 2.0 - 16;
            int min = (int) Math.min(plugin.getConfig().getInt("rtp.min-distance", 500), Math.max(16, half));
            if (Math.hypot(x - avoid.getX(), z - avoid.getZ()) < min) {
                retry.run();
                return;
            }
        }
        plugin.duels().surfaceAt(world, x, z, loc -> {
            if (loc == null || !plugin.locator().matches(loc, biome)) retry.run();
            else done.accept(loc);
        });
    }
}
