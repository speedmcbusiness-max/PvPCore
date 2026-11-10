package dev.samar.vperplayerkit.placeholder;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.rtpq.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placeholders are requested on the main thread (scoreboards ask every tick), so they never touch the database.
 * This cache is filled in the background: on join, after matches/kit changes and once a minute.
 */
public final class PlayerCache {
    public static final String[] CATEGORIES = {"overall", "kills", "victory", "defeat", "matchplayed"};

    public static final class Data {
        public PlayerStats stats;
        public int selectedKit;          // 0 = none
        public int savedKits;
        public String region = "";
        public final int[] ranks = new int[CATEGORIES.length];     // -1 = unranked
    }

    private final VPerPlayerKit plugin;
    private final Map<UUID, Data> data = new ConcurrentHashMap<>();
    private volatile Map<String, List<PlayerStats>> boards = new HashMap<>();

    public PlayerCache(VPerPlayerKit plugin) {
        this.plugin = plugin;
        int seconds = Math.max(15, plugin.getConfig().getInt("placeholders.refresh-seconds", 60));
        new BukkitRunnable() {
            @Override
            public void run() {
                refreshAll();
            }
        }.runTaskTimer(plugin, 40L, seconds * 20L);
    }

    public Data get(UUID id) {
        return data.get(id);
    }

    /** Top 10 of a category (empty until the first refresh finished). */
    public List<PlayerStats> board(String category) {
        List<PlayerStats> list = boards.get(category);
        return list == null ? List.of() : list;
    }

    public void remove(UUID id) {
        data.remove(id);
    }

    public void refresh(UUID id, String name) {
        plugin.async(() -> load(id, name));
    }

    private void load(UUID id, String name) {
        Data d = new Data();
        d.stats = plugin.storage().loadStats(id, name);
        String owner = id.toString();
        byte[] selected = plugin.storage().get(owner, "selected", "main");
        if (selected != null) {
            try {
                d.selectedKit = Integer.parseInt(new String(selected, StandardCharsets.UTF_8).trim());
            } catch (NumberFormatException ignored) {
                d.selectedKit = 0;
            }
        }
        d.savedKits = plugin.storage().ids(owner, "kit").size();
        d.region = plugin.results().region(id);
        boolean ranked = d.stats.played > 0 || d.stats.tier > 0;
        for (int i = 0; i < CATEGORIES.length; i++) {
            d.ranks[i] = ranked ? plugin.storage().rank(CATEGORIES[i], d.stats) : -1;
        }
        data.put(id, d);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.nametags().update(p);       // the tier in the nametag
        });
    }

    private void refreshAll() {
        List<UUID> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ids.add(p.getUniqueId());
            names.add(p.getName());
        }
        data.keySet().removeIf(id -> !ids.contains(id));
        plugin.async(() -> {
            for (int i = 0; i < ids.size(); i++) load(ids.get(i), names.get(i));
            Map<String, List<PlayerStats>> fresh = new HashMap<>();
            for (String category : CATEGORIES) fresh.put(category, plugin.storage().top(category, 10));
            boards = fresh;
        });
    }
}
