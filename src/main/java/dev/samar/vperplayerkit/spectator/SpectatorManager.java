package dev.samar.vperplayerkit.spectator;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.ffa.FfaManager;
import dev.samar.vperplayerkit.gladiator.GladiatorMatch;
import dev.samar.vperplayerkit.rtpq.DuelManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /spec <player> (/spectator): watch a running duel, FFA or Gladiator in spectator mode. The player returns to where they
 * were when they leave, when the match ends or when they disconnect. Settings: spectator.* in config.yml.
 */
public final class SpectatorManager {

    private static final class Session {
        UUID target;
        Object match;
        Location back;
        GameMode mode;
    }

    private final VPerPlayerKit plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public SpectatorManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("spectator.enabled", true);
    }

    public boolean isSpectating(UUID id) {
        return sessions.containsKey(id);
    }

    public int total() {
        return sessions.size();
    }

    public String targetName(UUID spectator) {
        Session s = sessions.get(spectator);
        if (s == null) return "";
        Player target = Bukkit.getPlayer(s.target);
        return target == null ? "" : target.getName();
    }

    /** The running match this player fights in (a Duel, FfaManager.Match or GladiatorMatch), or null. */
    public Object matchOf(UUID id) {
        DuelManager.Duel duel = plugin.duels().duelOf(id);
        if (duel != null) return duel;
        FfaManager.Match ffa = plugin.ffa().matchOf(id);
        if (ffa != null) return ffa;
        GladiatorMatch glad = plugin.gladiator().matchOf(id);
        if (glad != null && glad.fighting()) return glad;
        return null;
    }

    private static String typeOf(Object match) {
        if (match instanceof DuelManager.Duel) return "duel";
        if (match instanceof FfaManager.Match) return "ffa";
        return "gladiator";
    }

    public int countFor(Object match) {
        int count = 0;
        for (Session s : sessions.values()) {
            if (s.match == match) count++;
        }
        return count;
    }

    /** Spectators of the match a player fights in, or of the match a spectator is watching. */
    public int countForPlayer(UUID id) {
        Session own = sessions.get(id);
        Object match = own != null ? own.match : matchOf(id);
        return match == null ? 0 : countFor(match);
    }

    public List<UUID> spectatorsOf(Object match) {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (e.getValue().match == match) out.add(e.getKey());
        }
        return out;
    }

    // ------------------------------------------------------------------ start / stop

    public void start(Player player, String targetName) {
        if (!enabled()) {
            plugin.msg().send(player, "spec-disabled");
            return;
        }
        UUID id = player.getUniqueId();
        Player target = targetName == null ? null : Bukkit.getPlayerExact(targetName);
        if (target == null) {
            plugin.msg().send(player, "spec-not-found", "{player}", String.valueOf(targetName));
            return;
        }
        if (target.equals(player)) {
            plugin.msg().send(player, "spec-self");
            return;
        }
        Object match = matchOf(target.getUniqueId());
        if (match == null) {
            plugin.msg().send(player, "spec-not-in-match", "{player}", target.getName());
            return;
        }
        if (!plugin.getConfig().getBoolean("spectator.allow-" + typeOf(match), true)) {
            plugin.msg().send(player, "spec-type-disabled");
            return;
        }

        Session session = sessions.get(id);
        if (session == null) {
            if (plugin.busy(id) || plugin.inMatch(player) || plugin.queue().isQueued(id)
                    || plugin.duels().isBusy(id) || plugin.gladiator().isBusy(id) || plugin.ffa().isBusy(id)) {
                plugin.msg().send(player, "spec-busy");
                return;
            }
            session = new Session();
            session.back = player.getLocation().clone();
            session.mode = player.getGameMode();
            sessions.put(id, session);
        }
        session.target = target.getUniqueId();
        session.match = match;

        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(target.getLocation());
        if (match instanceof DuelManager.Duel duelMatch) plugin.scorebar().showFor(player, duelMatch);
        plugin.msg().send(player, "spec-started", "{player}", target.getName());
        if (plugin.getConfig().getBoolean("spectator.notify-target", false)) {
            plugin.msg().send(target, "spec-notify", "{spectator}", player.getName());
        }
    }

    /** Returns false when the player was not spectating. */
    public boolean stop(Player player, boolean message) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return false;
        plugin.scorebar().hideFor(player);
        player.setGameMode(session.mode == null ? GameMode.SURVIVAL : session.mode);
        if (session.back != null && session.back.getWorld() != null) player.teleport(session.back);
        else plugin.sendToLobby(player);
        if (message) plugin.msg().send(player, "spec-stopped");
        return true;
    }

    /** The match is over: everybody who watched it goes back. */
    public void matchEnded(Object match) {
        for (UUID id : spectatorsOf(match)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                sessions.remove(id);
                continue;
            }
            stop(p, false);
            plugin.msg().send(p, "spec-ended");
        }
    }

    private void tick() {
        double max = plugin.getConfig().getDouble("spectator.max-distance", 200.0);
        for (Map.Entry<UUID, Session> e : new HashMap<>(sessions).entrySet()) {
            Player spectator = Bukkit.getPlayer(e.getKey());
            if (spectator == null) {
                sessions.remove(e.getKey());
                continue;
            }
            Session s = e.getValue();
            Player target = Bukkit.getPlayer(s.target);
            if (target == null || matchOf(target.getUniqueId()) != s.match) {
                stop(spectator, true);
                continue;
            }
            if (!spectator.getWorld().equals(target.getWorld())
                    || spectator.getLocation().distance(target.getLocation()) > max) {
                spectator.teleport(target.getLocation());              // stay close to the fight
            }
        }
    }

    public void shutdown() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) stop(p, false);
        }
        sessions.clear();
    }
}
