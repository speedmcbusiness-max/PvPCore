package dev.samar.vperplayerkit.announce;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.rtpq.Tier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Server announcements for matches: high tier players joining (duel / ffa / gladiator) and the
 * "Player Won" action bar. All text is in config.yml (messages: highlight-*, win-actionbar, spect-button).
 */
public final class Announcements {
    private final VPerPlayerKit plugin;

    public Announcements(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    /**
     * Broadcasts "[tier] player V/S opponents [Spect]" when the player's tier is at least announce.high-tier.min-tier.
     * mode is duel, ffa or gladiator (picks the message highlight-<mode>).
     */
    public void highTier(String mode, String name, int tierIndex, List<String> opponents, int count) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("announce.high-tier.enabled", true)) return;
        Tier min = Tier.fromName(cfg.getString("announce.high-tier.min-tier", "LT2"));
        if (min == null) min = Tier.LT2;
        if (tierIndex < min.ordinal()) return;

        Tier tier = Tier.fromIndex(tierIndex);
        int max = Math.max(1, cfg.getInt("announce.high-tier.max-names", 5));
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < Math.min(max, opponents.size()); i++) {
            if (i > 0) joined.append(", ");
            joined.append(opponents.get(i));
        }
        if (opponents.size() > max) {
            joined.append(plugin.msg().raw("highlight-more").replace("{n}", String.valueOf(opponents.size() - max)));
        }
        String spect = plugin.msg().raw("spect-button").replace("{player}", name);
        Component line = plugin.msg().get("highlight-" + mode, "{player}", name, "{tier}", tier.colored(),
                "{opponent}", joined.toString(), "{opponents}", joined.toString(),
                "{count}", String.valueOf(count), "{spect}", spect);
        Bukkit.broadcast(line);
    }

    /** "Player Won" above the hotbar for a few seconds. */
    public void winBar(Collection<UUID> viewers, String winnerName) {
        if (!plugin.getConfig().getBoolean("announce.win-actionbar", true)) return;
        Component bar = plugin.msg().get("win-actionbar", "{player}", winnerName);
        List<UUID> ids = new ArrayList<>(viewers);
        new BukkitRunnable() {
            int shown = 0;

            @Override
            public void run() {
                if (shown++ >= 5) {
                    cancel();
                    return;
                }
                for (UUID id : ids) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.sendActionBar(bar);
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    public boolean broadcastEnabled(String mode) {
        return plugin.getConfig().getBoolean("announce.win-broadcast-" + mode, true);
    }
}
