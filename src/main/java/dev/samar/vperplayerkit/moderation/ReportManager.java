package dev.samar.vperplayerkit.moderation;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** /report, /offend and the data behind /sus. Reports live in the database (type "report"), so staff on every server see them. */
public final class ReportManager {

    public record Report(String id, UUID reporter, String reporterName, UUID target, String targetName,
                         String reason, long time) {
    }

    private static final String SEP = "\u001f";

    private final VPerPlayerKit plugin;
    private final Map<UUID, Long> lastReport = new HashMap<>();

    public ReportManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ reasons

    public List<String> reasons() {
        List<String> list = plugin.getConfig().getStringList("report.reasons");
        return list.isEmpty() ? List.of("Cheating", "Macro", "AutoTotem", "Hacks") : list;
    }

    public List<String> offendReasons() {
        List<String> list = plugin.getConfig().getStringList("offend.reasons");
        return list.isEmpty() ? reasons() : list;
    }

    private static String match(List<String> options, String input) {
        for (String option : options) {
            if (option.equalsIgnoreCase(input)) return option;
        }
        return null;
    }

    // ------------------------------------------------------------------ /report

    /** Looks a player up by name: online first, then anybody the server has seen. Null when unknown. */
    public UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        var cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    public void submit(Player reporter, UUID target, String targetName, String reasonRaw) {
        if (reporter.getUniqueId().equals(target)) {
            plugin.msg().send(reporter, "report-self");
            return;
        }
        String reason = reasonRaw == null ? "" : reasonRaw.trim();
        if (reason.isEmpty()) {
            plugin.msg().send(reporter, "report-empty");
            return;
        }
        int max = Math.max(8, plugin.getConfig().getInt("report.max-reason-length", 64));
        if (reason.length() > max) reason = reason.substring(0, max);

        long now = System.currentTimeMillis();
        int cooldown = plugin.getConfig().getInt("report.cooldown-seconds", 60);
        Long last = lastReport.get(reporter.getUniqueId());
        if (cooldown > 0 && last != null && (now - last) / 1000 < cooldown && !reporter.hasPermission("perplayerkit.report.bypass")) {
            plugin.msg().send(reporter, "report-cooldown", "{seconds}", String.valueOf(cooldown - (now - last) / 1000));
            return;
        }
        lastReport.put(reporter.getUniqueId(), now);

        String id = now + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        final String finalReason = reason;
        byte[] data = String.join(SEP, reporter.getUniqueId().toString(), reporter.getName(), target.toString(),
                targetName, finalReason, String.valueOf(now)).getBytes(StandardCharsets.UTF_8);
        plugin.async(() -> plugin.storage().put("global", "report", id, data), (Boolean ok) -> {
            if (!reporter.isOnline()) return;
            if (!ok) {
                plugin.msg().send(reporter, "database-error");
                return;
            }
            plugin.msg().send(reporter, "report-sent", "{player}", targetName);
            if (plugin.getConfig().getBoolean("report.notify-staff", true)) {
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("perplayerkit.sus")) {
                        plugin.msg().send(staff, "report-staff", "{reporter}", reporter.getName(),
                                "{player}", targetName, "{reason}", finalReason);
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------------ data for /sus

    private static Report parse(String id, byte[] data) {
        String[] p = new String(data, StandardCharsets.UTF_8).split(SEP, -1);
        if (p.length < 6) return null;
        try {
            return new Report(id, UUID.fromString(p[0]), p[1], UUID.fromString(p[2]), p[3], p[4], Long.parseLong(p[5]));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Newest first. The callback runs on the main thread. */
    public void loadAll(Consumer<List<Report>> done) {
        plugin.async(() -> {
            List<Report> list = new ArrayList<>();
            for (Map.Entry<String, byte[]> e : plugin.storage().all("global", "report").entrySet()) {
                Report r = parse(e.getKey(), e.getValue());
                if (r != null) list.add(r);
            }
            list.sort(Comparator.comparingLong(Report::time).reversed());
            return list;
        }, (List<Report> list) -> done.accept(list));
    }

    /** Deletes every report about a player. {@code after} runs on the main thread (may be null). */
    public void clearTarget(UUID target, Runnable after) {
        plugin.async(() -> {
            for (Map.Entry<String, byte[]> e : plugin.storage().all("global", "report").entrySet()) {
                Report r = parse(e.getKey(), e.getValue());
                if (r != null && r.target().equals(target)) plugin.storage().delete("global", "report", e.getKey());
            }
            return true;
        }, (Boolean done) -> {
            if (after != null) after.run();
        });
    }

    // ------------------------------------------------------------------ /offend

    /** Bans a player with the configured ban command (offend.ban-command). */
    public void offend(CommandSender sender, String targetName, String reasonRaw) {
        String reason = match(offendReasons(), reasonRaw);
        if (reason == null) {
            if (!plugin.getConfig().getBoolean("offend.allow-custom-reason", false)) {
                plugin.msg().send(sender, "offend-bad-reason", "{list}", String.join(", ", offendReasons()));
                return;
            }
            reason = reasonRaw;
        }
        UUID uuid = resolve(targetName);
        if (uuid == null) {
            plugin.msg().send(sender, "report-unknown", "{player}", targetName);
            return;
        }
        plugin.bans().ban(sender, uuid, targetName, reason);          // can play, cannot see others, BANNED above the nametag

        // optional: also run a real ban command of your punishment plugin (blank = only the restriction above)
        String template = plugin.getConfig().getString("offend.commands." + reason,
                plugin.getConfig().getString("offend.ban-command", ""));
        if (template != null && !template.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), template.replace("{player}", targetName)
                    .replace("{reason}", reason).replace("{staff}", sender.getName()));
        }
        clearTarget(uuid, null);
        plugin.msg().send(sender, "offend-done", "{player}", targetName, "{reason}", reason);
        if (plugin.getConfig().getBoolean("offend.broadcast", true)) {
            Bukkit.broadcast(plugin.msg().get("offend-broadcast", "{player}", targetName, "{reason}", reason,
                    "{staff}", sender.getName()));
        }
    }
}
