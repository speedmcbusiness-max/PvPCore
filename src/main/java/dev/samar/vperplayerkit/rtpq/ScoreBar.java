package dev.samar.vperplayerkit.rtpq;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The duel score bar at the top of the screen: "[head] yourScore - opponentScore [head]". Shown to both players (each sees their own
 * score first) and to spectators. The look is scorebar.* in config.yml. Player heads in text need a server and client with object
 * components (Paper 1.21.9+), on older servers the names are shown instead.
 */
public final class ScoreBar {
    private static final Pattern HEAD = Pattern.compile("<head:([^>]+)>");
    private static final boolean HEADS_SUPPORTED = headsSupported();

    private final VPerPlayerKit plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, DuelManager.Duel> viewing = new HashMap<>();

    public ScoreBar(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private static boolean headsSupported() {
        try {
            Class.forName("net.kyori.adventure.text.object.ObjectContents");
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("scorebar.enabled", true);
    }

    // ------------------------------------------------------------------ show / update / hide

    /** Both duelists. */
    public void show(DuelManager.Duel d) {
        if (!enabled()) return;
        for (int i = 0; i < 2; i++) {
            if (d.ids[i] == null) continue;
            Player p = Bukkit.getPlayer(d.ids[i]);
            if (p != null) showFor(p, d);
        }
    }

    /** A duelist or a spectator. */
    public void showFor(Player viewer, DuelManager.Duel d) {
        if (!enabled() || d.ids[0] == null) return;
        hideFor(viewer);
        int me = Math.max(0, d.indexOf(viewer.getUniqueId()));
        BossBar bar = BossBar.bossBar(title(d, me), progress(d), color(), overlay());
        bars.put(viewer.getUniqueId(), bar);
        viewing.put(viewer.getUniqueId(), d);
        viewer.showBossBar(bar);
    }

    /** After every point. */
    public void update(DuelManager.Duel d) {
        for (Map.Entry<UUID, DuelManager.Duel> e : new HashMap<>(viewing).entrySet()) {
            if (e.getValue() != d) continue;
            BossBar bar = bars.get(e.getKey());
            if (bar == null) continue;
            bar.name(title(d, Math.max(0, d.indexOf(e.getKey()))));
            bar.progress(progress(d));
        }
    }

    public void hide(DuelManager.Duel d) {
        for (UUID id : new ArrayList<>(viewing.keySet())) {
            if (viewing.get(id) == d) hideFor(id);
        }
    }

    public void hideFor(Player player) {
        hideFor(player.getUniqueId());
    }

    private void hideFor(UUID id) {
        BossBar bar = bars.remove(id);
        viewing.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (bar != null && p != null) p.hideBossBar(bar);
    }

    public void hideAll() {
        for (UUID id : new ArrayList<>(bars.keySet())) hideFor(id);
    }

    // ------------------------------------------------------------------ look

    private Component title(DuelManager.Duel d, int me) {
        int opp = 1 - me;
        String format = plugin.getConfig().getString("scorebar.format",
                "<head:{player}> <white><bold>{score}</bold></white> <dark_gray>-</dark_gray> <white><bold>{opponent_score}</bold></white> <head:{opponent}>");
        String text = format.replace("{player}", d.names[me]).replace("{opponent}", d.names[opp])
                .replace("{score}", String.valueOf(d.score[me])).replace("{opponent_score}", String.valueOf(d.score[opp]))
                .replace("{target}", String.valueOf(d.target));
        boolean heads = HEADS_SUPPORTED && plugin.getConfig().getBoolean("scorebar.heads", true);
        if (!heads) text = HEAD.matcher(text).replaceAll("<white>$1</white>");
        return Text.mm(text);
    }

    private float progress(DuelManager.Duel d) {
        if (!"leader".equalsIgnoreCase(plugin.getConfig().getString("scorebar.progress", "leader"))) return 1f;
        int best = Math.max(d.score[0], d.score[1]);
        return Math.max(0.02f, Math.min(1f, best / (float) Math.max(1, d.target)));
    }

    private BossBar.Color color() {
        try {
            return BossBar.Color.valueOf(plugin.getConfig().getString("scorebar.color", "WHITE").toUpperCase());
        } catch (IllegalArgumentException ex) {
            return BossBar.Color.WHITE;
        }
    }

    private BossBar.Overlay overlay() {
        try {
            return BossBar.Overlay.valueOf(plugin.getConfig().getString("scorebar.overlay", "PROGRESS").toUpperCase());
        } catch (IllegalArgumentException ex) {
            return BossBar.Overlay.PROGRESS;
        }
    }
}
