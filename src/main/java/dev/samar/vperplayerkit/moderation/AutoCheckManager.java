package dev.samar.vperplayerkit.moderation;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /damage <player> <amount> hurts a player on purpose, /autocheck <player> <totems> pops that many of their totems and measures
 * how many ticks they need to put a new one in their hand. Humans need ~6+ ticks, an AutoTotem client does it in 1-3.
 */
public final class AutoCheckManager {
    private final VPerPlayerKit plugin;
    private final Set<UUID> running = new HashSet<>();

    public AutoCheckManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private static int handTotems(Player p) {
        int count = 0;
        if (p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING) count++;
        if (p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) count++;
        return count;
    }

    private boolean testable(CommandSender sender, Player target) {
        if (plugin.inMatch(target)) {
            plugin.msg().send(sender, "autocheck-in-match", "{player}", target.getName());
            return false;
        }
        if (target.getGameMode() != GameMode.SURVIVAL && target.getGameMode() != GameMode.ADVENTURE) {
            plugin.msg().send(sender, "autocheck-gamemode", "{player}", target.getName());
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ /damage

    public void damage(CommandSender sender, Player target, double amount) {
        if (!testable(sender, target)) return;
        target.setNoDamageTicks(0);
        target.damage(amount);
        plugin.msg().send(sender, "damage-done", "{player}", target.getName(), "{amount}", String.valueOf(amount));
    }

    // ------------------------------------------------------------------ /autocheck

    public void autocheck(CommandSender sender, Player target, int totems) {
        UUID id = target.getUniqueId();
        if (running.contains(id)) {
            plugin.msg().send(sender, "autocheck-running", "{player}", target.getName());
            return;
        }
        if (!testable(sender, target)) return;
        if (handTotems(target) == 0) {
            plugin.msg().send(sender, "autocheck-no-totem", "{player}", target.getName());
            return;
        }
        final int count = Math.max(1, Math.min(plugin.getConfig().getInt("autocheck.max-pops", 10), totems));
        final int interval = Math.max(10, plugin.getConfig().getInt("autocheck.interval-ticks", 30));
        final int maxWait = Math.max(10, plugin.getConfig().getInt("autocheck.max-wait-ticks", 60));
        final int suspicious = plugin.getConfig().getInt("autocheck.suspicious-ticks", 4);
        final int needed = Math.max(1, plugin.getConfig().getInt("autocheck.suspicious-count", 1));

        running.add(id);
        plugin.msg().send(sender, "autocheck-start", "{player}", target.getName(), "{count}", String.valueOf(count));
        final List<Integer> results = new ArrayList<>();

        new BukkitRunnable() {
            int state = 0;       // 0 = hit now, 1 = measuring, 2 = pause
            int ticks = 0;
            int spare = 0;

            private void finish(String abortKey) {
                cancel();
                running.remove(id);
                if (target.isOnline() && !target.isDead()) {
                    plugin.kits().heal(target);
                }
                if (abortKey != null) plugin.msg().send(sender, abortKey, "{player}", target.getName());
                int measured = 0;
                int sum = 0;
                int flagged = 0;
                for (int i = 0; i < results.size(); i++) {
                    int t = results.get(i);
                    String note;
                    if (t == -1) note = plugin.msg().raw("autocheck-note-none");
                    else if (t == -2) note = plugin.msg().raw("autocheck-note-spare");
                    else {
                        measured++;
                        sum += t;
                        boolean bad = t <= suspicious;
                        if (bad) flagged++;
                        note = plugin.msg().raw(bad ? "autocheck-note-suspicious" : "autocheck-note-ok");
                    }
                    plugin.msg().send(sender, "autocheck-pop", "{n}", String.valueOf(i + 1),
                            "{ticks}", t > 0 ? String.valueOf(t) : "-", "{ms}", t > 0 ? String.valueOf(t * 50) : "-",
                            "{note}", note);
                }
                String verdict = plugin.msg().raw(flagged >= needed ? "autocheck-verdict-suspicious" : "autocheck-verdict-ok");
                plugin.msg().send(sender, "autocheck-summary", "{player}", target.getName(),
                        "{avg}", measured == 0 ? "-" : String.valueOf(Math.round(sum * 10.0 / measured) / 10.0),
                        "{verdict}", verdict);
            }

            @Override
            public void run() {
                if (!target.isOnline() || target.isDead()) {
                    finish("autocheck-aborted");
                    return;
                }
                switch (state) {
                    case 0 -> {
                        int inHands = handTotems(target);
                        if (inHands == 0) {                      // never kill the player: stop without a totem in hand
                            finish("autocheck-no-totem");
                            return;
                        }
                        spare = inHands - 1;                      // a second totem in hand would give a fake fast result
                        target.setNoDamageTicks(0);
                        target.damage(1000.0);
                        state = 1;
                        ticks = 0;
                    }
                    case 1 -> {
                        ticks++;
                        boolean back = handTotems(target) > 0;
                        if (spare > 0) {
                            results.add(-2);
                        } else if (back) {
                            results.add(ticks);
                        } else if (ticks > maxWait) {
                            results.add(-1);
                        } else {
                            return;
                        }
                        state = 2;
                        ticks = 0;
                        if (results.size() >= count) finish(null);
                    }
                    default -> {
                        ticks++;
                        if (ticks >= interval) state = 0;
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
