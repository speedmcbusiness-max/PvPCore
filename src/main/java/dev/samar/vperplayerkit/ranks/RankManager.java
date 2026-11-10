package dev.samar.vperplayerkit.ranks;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.Durations;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server ranks (youtuber, staff, admin, owner, helper, manager, tester ...). The rank definitions (tag text, priority, optional commands)
 * are edited in the web editor (/v:rank editor), players get ranks with /v:rank <player> <set|add|remove> <rank> [time].
 * The highest priority rank of a player is shown in their nametag. Everything is stored in the database.
 */
public final class RankManager {

    public record RankDef(String key, String tag, int priority, String onAdd, String onRemove) {
    }

    private record Change(Map<String, Long> ranks, List<String> added, List<String> removed) {
    }

    private static final String SEP = "\u001f";

    private final VPerPlayerKit plugin;
    private volatile Map<String, RankDef> defs = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> online = new ConcurrentHashMap<>();   // rank -> expiry millis, 0 = permanent

    public RankManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
        plugin.async(this::reloadDefs);
        new BukkitRunnable() {
            @Override
            public void run() {
                sweepExpired();
                plugin.async(RankManager.this::reloadDefs);      // picks up edits made on another server
            }
        }.runTaskTimer(plugin, 1200L, 1200L);
    }

    // ------------------------------------------------------------------ definitions

    public static String cleanKey(String raw) {
        if (raw == null) return null;
        String key = raw.trim().toLowerCase();
        return key.matches("[a-z0-9_-]{1,24}") ? key : null;
    }

    private static List<RankDef> defaults() {
        return List.of(
                new RankDef("owner", "&#FF0000&lOWNER", 100, "", ""),
                new RankDef("admin", "&#FF5555&lADMIN", 90, "", ""),
                new RankDef("manager", "&#FFAA00&lMANAGER", 85, "", ""),
                new RankDef("staff", "&#55FFFF&lSTAFF", 80, "", ""),
                new RankDef("helper", "&#55FF55&lHELPER", 70, "", ""),
                new RankDef("tester", "&#B388FF&lTESTER", 60, "", ""),
                new RankDef("youtuber", "&#FF3B3B&lYOUTUBER", 50, "", ""));
    }

    private static byte[] encode(RankDef d) {
        return String.join(SEP, d.tag(), String.valueOf(d.priority()), d.onAdd(), d.onRemove())
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Blocking: reads the definitions from the database, seeding the default ranks the first time. */
    public void reloadDefs() {
        Map<String, byte[]> rows = plugin.storage().all("global", "rankdef");
        if (rows.isEmpty()) {
            for (RankDef d : defaults()) plugin.storage().put("global", "rankdef", d.key(), encode(d));
            rows = plugin.storage().all("global", "rankdef");
        }
        Map<String, RankDef> fresh = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : rows.entrySet()) {
            String[] p = new String(e.getValue(), StandardCharsets.UTF_8).split(SEP, -1);
            if (p.length < 4) continue;
            int priority;
            try {
                priority = Integer.parseInt(p[1]);
            } catch (NumberFormatException ex) {
                priority = 0;
            }
            fresh.put(e.getKey(), new RankDef(e.getKey(), p[0], priority, p[2], p[3]));
        }
        defs = fresh;
    }

    /** Blocking: replaces all definitions (used by the web editor). */
    public void saveDefs(List<RankDef> list) {
        Map<String, RankDef> fresh = new LinkedHashMap<>();
        for (RankDef d : list) fresh.put(d.key(), d);
        for (String old : plugin.storage().ids("global", "rankdef")) {
            if (!fresh.containsKey(old)) plugin.storage().delete("global", "rankdef", old);
        }
        for (RankDef d : fresh.values()) plugin.storage().put("global", "rankdef", d.key(), encode(d));
        defs = fresh;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) plugin.nametags().update(p);
        });
    }

    public List<RankDef> definitions() {
        List<RankDef> list = new ArrayList<>(defs.values());
        list.sort(Comparator.comparingInt(RankDef::priority).reversed());
        return list;
    }

    public RankDef def(String key) {
        return key == null ? null : defs.get(key.toLowerCase());
    }

    // ------------------------------------------------------------------ player ranks

    private static Map<String, Long> parse(byte[] data) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (data == null) return out;
        for (String part : new String(data, StandardCharsets.UTF_8).split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            try {
                out.put(kv[0], Long.parseLong(kv[1]));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    private Map<String, Long> loadRanks(UUID uuid) {
        return parse(plugin.storage().get(uuid.toString(), "ranks", "main"));
    }

    private void saveRanks(UUID uuid, Map<String, Long> ranks) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : ranks.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        if (sb.length() == 0) plugin.storage().delete(uuid.toString(), "ranks", "main");
        else plugin.storage().put(uuid.toString(), "ranks", "main", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        plugin.async(() -> loadRanks(id), (Map<String, Long> ranks) -> {
            if (!player.isOnline()) return;
            online.put(id, ranks);
            sweepExpired();
            plugin.nametags().update(player);
        });
    }

    public void handleQuit(Player player) {
        online.remove(player.getUniqueId());
    }

    /** The key of the player's highest priority, not expired rank, or null. */
    public String topKey(UUID id) {
        Map<String, Long> ranks = online.get(id);
        if (ranks == null) return null;
        long now = System.currentTimeMillis();
        RankDef best = null;
        for (Map.Entry<String, Long> e : ranks.entrySet()) {
            RankDef d = defs.get(e.getKey());
            if (d == null || (e.getValue() != 0 && e.getValue() < now)) continue;
            if (best == null || d.priority() > best.priority()) best = d;
        }
        return best == null ? null : best.key();
    }

    /** The tag text (with & colors) of the top rank, empty without a rank. */
    public String tagOf(UUID id) {
        RankDef d = def(topKey(id));
        return d == null ? "" : d.tag();
    }

    private void runCommand(String template, String playerName) {
        if (template == null || template.isBlank()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), template.replace("{player}", playerName));
    }

    /** Removes ranks that ran out and runs their on-remove command. */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<String, Long>> entry : online.entrySet()) {
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, Long> r : entry.getValue().entrySet()) {
                if (r.getValue() != 0 && r.getValue() < now) expired.add(r.getKey());
            }
            if (expired.isEmpty()) continue;
            UUID id = entry.getKey();
            Player p = Bukkit.getPlayer(id);
            for (String key : expired) {
                entry.getValue().remove(key);
                RankDef d = defs.get(key);
                if (p != null) {
                    if (d != null) runCommand(d.onRemove(), p.getName());
                    plugin.msg().send(p, "rank-expired", "{rank}", key);
                }
            }
            Map<String, Long> copy = new HashMap<>(entry.getValue());
            plugin.async(() -> saveRanks(id, copy));
            if (p != null) plugin.nametags().update(p);
        }
    }

    // ------------------------------------------------------------------ /v:rank <player> <set|add|remove> <rank> [time]

    public void modify(CommandSender sender, String targetName, String mode, String rankKey, String timeArg) {
        String action = mode.toLowerCase();
        if (!action.equals("set") && !action.equals("add") && !action.equals("remove")) {
            plugin.msg().send(sender, "rank-usage");
            return;
        }
        RankDef def = def(rankKey);
        if (def == null) {
            List<String> keys = new ArrayList<>();
            for (RankDef d : definitions()) keys.add(d.key());
            plugin.msg().send(sender, "rank-unknown", "{list}", String.join(", ", keys));
            return;
        }
        long expiry = 0;
        if (timeArg != null && !timeArg.equalsIgnoreCase("perm") && !timeArg.equalsIgnoreCase("permanent")
                && !timeArg.equals("0")) {
            long seconds = Durations.parseSeconds(timeArg);
            if (seconds <= 0) {
                plugin.msg().send(sender, "rank-bad-time");
                return;
            }
            expiry = System.currentTimeMillis() + seconds * 1000;
        }
        UUID uuid = plugin.reports().resolve(targetName);
        if (uuid == null) {
            plugin.msg().send(sender, "rank-player-unknown", "{player}", targetName);
            return;
        }
        final long until = expiry;
        final String key = def.key();
        plugin.async(() -> {
            Map<String, Long> ranks = loadRanks(uuid);
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            switch (action) {
                case "set" -> {
                    for (String old : ranks.keySet()) {
                        if (!old.equals(key)) removed.add(old);
                    }
                    if (!ranks.containsKey(key)) added.add(key);
                    ranks.clear();
                    ranks.put(key, until);
                }
                case "add" -> {
                    if (!ranks.containsKey(key)) added.add(key);
                    ranks.put(key, until);
                }
                default -> {
                    if (ranks.remove(key) != null) removed.add(key);
                }
            }
            saveRanks(uuid, ranks);
            return new Change(ranks, added, removed);
        }, (Change change) -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                online.put(uuid, new ConcurrentHashMap<>(change.ranks()));
                plugin.nametags().update(p);
            }
            for (String k : change.added()) {
                RankDef d = defs.get(k);
                if (d != null) runCommand(d.onAdd(), targetName);
            }
            for (String k : change.removed()) {
                RankDef d = defs.get(k);
                if (d != null) runCommand(d.onRemove(), targetName);
            }
            plugin.msg().send(sender, "rank-done-" + action, "{player}", targetName, "{rank}", key,
                    "{time}", until == 0 ? plugin.msg().raw("rank-permanent") : Durations.format((until - System.currentTimeMillis()) / 1000));
            if (p != null && !p.equals(sender)) {
                plugin.msg().send(p, "rank-notify-" + action, "{rank}", key);
            }
        });
    }

    public void sendList(CommandSender to) {
        plugin.msg().send(to, "rank-list-header");
        for (RankDef d : definitions()) {
            plugin.msg().send(to, "rank-list-line", "{rank}", d.key(), "{priority}", String.valueOf(d.priority()),
                    "{tag}", dev.samar.vperplayerkit.util.Text.legacyToMini(d.tag()));
        }
    }
}
