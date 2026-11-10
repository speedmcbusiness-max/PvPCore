package dev.samar.vperplayerkit.rtpq;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ranked RTP queue. Pairs players every second. */
public final class QueueManager {

    public static final class Entry {
        public final UUID uuid;
        public final String name;
        public volatile String biome;  // "auto" (random, locked) or a biome key from rtp.biomes
        public final int kitId;
        public final PlayerStats stats;
        public final boolean test;     // tier test: only matched with the same tier, first to 3

        Entry(UUID uuid, String name, String biome, int kitId, PlayerStats stats) {
            this.uuid = uuid;
            this.name = name;
            this.biome = biome;
            this.kitId = kitId;
            this.stats = stats;
            this.test = stats.testReady();
        }
    }

    private final VPerPlayerKit plugin;
    private final Map<UUID, Entry> queue = new LinkedHashMap<>();

    public QueueManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public boolean isQueued(UUID uuid) {
        return queue.containsKey(uuid);
    }

    public Entry get(UUID uuid) {
        return queue.get(uuid);
    }

    public int size() {
        return queue.size();
    }

    /** kitSpec is "selected" or "kit:N". */
    public void join(Player player, String biome, String kitSpec) {
        UUID uuid = player.getUniqueId();
        if (plugin.duels().isBusy(uuid) || plugin.gladiator().isBusy(uuid) || plugin.ffa().isBusy(uuid) || plugin.busy(uuid)) {
            plugin.msg().send(player, "queue-busy");
            return;
        }
        if (queue.containsKey(uuid)) {
            plugin.msg().send(player, "queue-already");
            return;
        }
        String owner = uuid.toString();
        String name = player.getName();
        String cleanBiome = (biome == null || biome.isEmpty()) ? "auto" : biome.toLowerCase();

        plugin.async(() -> {
            Integer kit = null;
            if (kitSpec != null && kitSpec.startsWith("kit:")) {
                try {
                    kit = Integer.parseInt(kitSpec.substring(4));
                } catch (NumberFormatException ignored) {
                    // falls through to null
                }
            } else {
                byte[] sel = plugin.storage().get(owner, "selected", "main");
                if (sel != null) {
                    try {
                        kit = Integer.parseInt(new String(sel, StandardCharsets.UTF_8).trim());
                    } catch (NumberFormatException ignored) {
                        // no selection
                    }
                }
            }
            boolean hasKit = kit != null && plugin.storage().exists(owner, "kit", String.valueOf(kit));
            PlayerStats stats = plugin.storage().loadStats(uuid, name);
            return new Object[] {hasKit ? kit : null, stats};
        }, result -> {
            if (!player.isOnline()) return;
            Integer kit = (Integer) result[0];
            if (kit == null) {
                plugin.msg().send(player, "queue-no-kit");
                return;
            }
            if (queue.containsKey(uuid) || plugin.duels().isBusy(uuid) || plugin.ffa().isBusy(uuid) || plugin.busy(uuid)) return;
            Entry entry = new Entry(uuid, name, cleanBiome, kit, (PlayerStats) result[1]);
            queue.put(uuid, entry);
            plugin.msg().send(player, "queue-joined", "{n}", String.valueOf(kit),
                    "{biome}", cleanBiome.equals("auto") ? "Auto" : cleanBiome);
            if (entry.test) plugin.msg().send(player, "queue-test-notice");
        });
    }

    /**
     * Changes the biome of a queued player. A random biome ("auto") is locked: once you queued with Auto it cannot be changed.
     * Picking Auto while waiting is allowed but locks it as well.
     */
    public void changeBiome(Player player, String biome) {
        Entry entry = queue.get(player.getUniqueId());
        if (entry == null) {
            plugin.msg().send(player, "queue-not-in");
            return;
        }
        if (entry.biome.equals("auto")) {
            plugin.msg().send(player, "queue-biome-locked");
            return;
        }
        String key = biome == null ? "" : biome.toLowerCase();
        if (key.equals("auto")) {
            entry.biome = "auto";
            plugin.msg().send(player, "queue-biome-auto");
        } else if (plugin.locator().isBiome(key)) {
            entry.biome = key;
            plugin.msg().send(player, "queue-biome-changed", "{biome}", dev.samar.vperplayerkit.util.Text.pretty(key));
        } else {
            plugin.msg().send(player, "queue-biome-invalid", "{list}", String.join(", ", plugin.locator().biomes()));
        }
    }

    public void leave(Player player, boolean message) {
        if (queue.remove(player.getUniqueId()) != null && message) {
            plugin.msg().send(player, "queue-left");
        }
    }

    public void remove(UUID uuid) {
        queue.remove(uuid);
    }

    private static boolean compatible(Entry a, Entry b) {
        boolean biomeOk = a.biome.equals("auto") || b.biome.equals("auto") || a.biome.equals(b.biome);
        if (!biomeOk) return false;
        // Tier tests need two players of the same tier.
        if ((a.test || b.test) && a.stats.tier != b.stats.tier) return false;
        return true;
    }

    private void tick() {
        // drop offline players
        queue.keySet().removeIf(id -> {
            Player p = Bukkit.getPlayer(id);
            return p == null || !p.isOnline();
        });

        List<Entry> entries = new ArrayList<>(queue.values());
        for (int i = 0; i < entries.size(); i++) {
            Entry a = entries.get(i);
            if (!queue.containsKey(a.uuid)) continue;
            for (int j = i + 1; j < entries.size(); j++) {
                Entry b = entries.get(j);
                if (!queue.containsKey(b.uuid)) continue;
                if (!compatible(a, b)) continue;
                queue.remove(a.uuid);
                queue.remove(b.uuid);
                plugin.duels().startDuel(a, b);
                break;
            }
        }
    }
}
