package dev.samar.vperplayerkit.event;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hosted events: an admin opens an event, players join the event lobby with /event and leave with /event leave,
 * then the admin starts it and everybody fights in a FFA (same arena system as /ffartpq). Settings: event.* in config.yml.
 */
public final class EventManager {
    public enum State { CLOSED, OPEN, RUNNING }

    private final VPerPlayerKit plugin;
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Map<UUID, Location> back = new HashMap<>();
    private State state = State.CLOSED;
    private String host = "";

    public EventManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    public State state() {
        return state;
    }

    public int size() {
        return participants.size();
    }

    public boolean isBusy(UUID id) {
        return participants.contains(id);
    }

    public Location spawn() {
        return plugin.data().getLocation("event.spawn");
    }

    private void announce(String key, String... kv) {
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.msg().send(p, key, kv);
        }
    }

    // ------------------------------------------------------------------ players

    public void join(Player player) {
        UUID id = player.getUniqueId();
        if (state == State.CLOSED) {
            plugin.msg().send(player, "event-none");
            return;
        }
        if (state == State.RUNNING) {
            plugin.msg().send(player, "event-running");
            return;
        }
        if (participants.contains(id)) {
            plugin.msg().send(player, "event-already-in");
            return;
        }
        if (plugin.inMatch(player) || plugin.busy(id) || plugin.queue().isQueued(id) || plugin.duels().isBusy(id)
                || plugin.gladiator().isBusy(id) || plugin.ffa().isBusy(id)) {
            plugin.msg().send(player, "event-busy");
            return;
        }
        if (participants.size() >= Math.max(2, plugin.getConfig().getInt("event.max-players", 50))) {
            plugin.msg().send(player, "event-full");
            return;
        }
        Location spawn = spawn();
        if (spawn == null) {
            plugin.msg().send(player, "event-no-spawn");
            return;
        }
        back.put(id, player.getLocation().clone());
        participants.add(id);
        player.teleport(spawn);
        plugin.msg().send(player, "event-joined", "{count}", String.valueOf(participants.size()), "{host}", host);
        announce("event-player-joined", "{player}", player.getName(), "{count}", String.valueOf(participants.size()));
    }

    public void leave(Player player) {
        UUID id = player.getUniqueId();
        if (!participants.contains(id)) {
            plugin.msg().send(player, "event-not-in");
            return;
        }
        boolean fighting = plugin.ffa().fighting(id);
        if (fighting) plugin.ffa().leave(player);                      // knocked out and sent to the lobby
        participants.remove(id);
        Location previous = back.remove(id);
        if (!fighting && previous != null && player.isOnline()) player.teleport(previous);
        plugin.msg().send(player, "event-left");
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        if (!participants.remove(id)) return;
        Location previous = back.remove(id);
        if (previous != null && !plugin.ffa().fighting(id)) player.teleport(previous);
    }

    // ------------------------------------------------------------------ admin

    public void setSpawn(Player admin) {
        plugin.data().setLocation("event.spawn", admin.getLocation());
        plugin.msg().send(admin, "event-spawn-set");
    }

    public void open(Player admin) {
        if (state != State.CLOSED) {
            plugin.msg().send(admin, "event-already-open");
            return;
        }
        if (spawn() == null) {
            plugin.msg().send(admin, "event-no-spawn");
            return;
        }
        state = State.OPEN;
        host = admin.getName();
        Bukkit.broadcast(plugin.msg().get("event-open", "{host}", host));
    }

    public void start(Player admin) {
        if (state != State.OPEN) {
            plugin.msg().send(admin, "event-none");
            return;
        }
        int min = Math.max(2, plugin.getConfig().getInt("event.min-players", 2));
        if (participants.size() < min) {
            plugin.msg().send(admin, "event-not-enough", "{min}", String.valueOf(min));
            return;
        }
        if (!plugin.ffa().launch(new ArrayList<>(participants), true)) {
            plugin.msg().send(admin, "event-start-failed");
            return;
        }
        state = State.RUNNING;
        Bukkit.broadcast(plugin.msg().get("event-started", "{count}", String.valueOf(participants.size())));
    }

    /** Closes an event that has not started yet and sends everybody back. */
    public void close(Player admin) {
        if (state == State.CLOSED) {
            plugin.msg().send(admin, "event-none");
            return;
        }
        if (state == State.RUNNING) {
            plugin.msg().send(admin, "event-cannot-close");
            return;
        }
        for (UUID id : new ArrayList<>(participants)) {
            Player p = Bukkit.getPlayer(id);
            Location previous = back.remove(id);
            if (p != null && previous != null) p.teleport(previous);
            if (p != null) plugin.msg().send(p, "event-closed");
        }
        clear();
        plugin.msg().send(admin, "event-closed");
    }

    // ------------------------------------------------------------------ called by the FFA the event started

    public void startFailed() {
        if (state == State.RUNNING) state = State.OPEN;
    }

    public void finished(String winner) {
        if (winner != null) Bukkit.broadcast(plugin.msg().get("event-winner", "{player}", winner, "{host}", host));
    }

    public void clear() {
        participants.clear();
        back.clear();
        state = State.CLOSED;
        host = "";
    }

    public List<String> participantNames() {
        List<String> out = new ArrayList<>();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) out.add(p.getName());
        }
        return out;
    }
}
