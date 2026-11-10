package dev.samar.vperplayerkit.teleport;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.Durations;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /tpa, /tpahere, /tpaauto, /tpaaccept, /tpadeny, /tpacancel, /tpaignore and /back.
 * /back returns to where you were before a tpa teleport, or to where you died. Settings: tpa.* and back.* in config.yml.
 */
public final class TeleportManager {

    public record Request(UUID from, UUID to, boolean here, long expires) {
    }

    private final VPerPlayerKit plugin;
    private final Map<UUID, Map<UUID, Request>> incoming = new HashMap<>();   // recipient -> sender -> request
    private final Map<UUID, Request> outgoing = new HashMap<>();              // sender -> request
    private final Set<UUID> auto = new HashSet<>();
    private final Map<UUID, Long> ignoreUntil = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Map<UUID, Location> back = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();

    public TeleportManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ sending requests

    private boolean ready(Player player) {
        return plugin.isFree(player);
    }

    public void request(Player from, String targetName, boolean here) {
        Player target = targetName == null ? null : Bukkit.getPlayerExact(targetName);
        if (target == null) {
            plugin.msg().send(from, "tpa-not-found", "{player}", String.valueOf(targetName));
            return;
        }
        if (target.equals(from)) {
            plugin.msg().send(from, "tpa-self");
            return;
        }
        if (!ready(from)) {
            plugin.msg().send(from, "tpa-busy-self");
            return;
        }
        if (!ready(target)) {
            plugin.msg().send(from, "tpa-busy-target", "{player}", target.getName());
            return;
        }
        long now = System.currentTimeMillis();
        Long until = ignoreUntil.get(target.getUniqueId());
        if (until != null && until > now && !from.hasPermission("perplayerkit.tpa.bypass-ignore")) {
            plugin.msg().send(from, "tpa-ignored", "{player}", target.getName());
            return;
        }
        int wait = plugin.getConfig().getInt("tpa.cooldown-seconds", 5);
        Long last = cooldown.get(from.getUniqueId());
        if (wait > 0 && last != null && (now - last) / 1000 < wait) {
            plugin.msg().send(from, "tpa-cooldown", "{seconds}", String.valueOf(wait - (now - last) / 1000));
            return;
        }
        cooldown.put(from.getUniqueId(), now);
        dropOutgoing(from.getUniqueId());

        long expire = Math.max(10, plugin.getConfig().getInt("tpa.expire-seconds", 60));
        Request request = new Request(from.getUniqueId(), target.getUniqueId(), here, now + expire * 1000);

        if (auto.contains(target.getUniqueId())) {
            plugin.msg().send(from, "tpa-sent-auto", "{player}", target.getName());
            plugin.msg().send(target, "tpa-auto-accepted", "{player}", from.getName());
            perform(request);
            return;
        }
        incoming.computeIfAbsent(target.getUniqueId(), k -> new LinkedHashMap<>()).put(from.getUniqueId(), request);
        outgoing.put(from.getUniqueId(), request);
        plugin.msg().send(from, here ? "tpahere-sent" : "tpa-sent", "{player}", target.getName());
        plugin.msg().send(target, here ? "tpahere-request" : "tpa-request", "{player}", from.getName(),
                "{seconds}", String.valueOf(expire));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (outgoing.get(from.getUniqueId()) == request) {
                dropOutgoing(from.getUniqueId());
                Player sender = Bukkit.getPlayer(from.getUniqueId());
                if (sender != null) plugin.msg().send(sender, "tpa-expired", "{player}", target.getName());
            }
        }, expire * 20L);
    }

    private void dropOutgoing(UUID sender) {
        Request old = outgoing.remove(sender);
        if (old == null) return;
        Map<UUID, Request> map = incoming.get(old.to());
        if (map != null) {
            map.remove(sender);
            if (map.isEmpty()) incoming.remove(old.to());
        }
    }

    // ------------------------------------------------------------------ answering

    private Request find(Player recipient, String senderName) {
        Map<UUID, Request> map = incoming.get(recipient.getUniqueId());
        if (map == null || map.isEmpty()) return null;
        long now = System.currentTimeMillis();
        map.values().removeIf(r -> r.expires() < now);
        if (senderName != null) {
            Player sender = Bukkit.getPlayerExact(senderName);
            return sender == null ? null : map.get(sender.getUniqueId());
        }
        Request latest = null;
        for (Request r : map.values()) latest = r;
        return latest;
    }

    public void accept(Player recipient, String senderName) {
        Request r = find(recipient, senderName);
        if (r == null) {
            plugin.msg().send(recipient, "tpa-none");
            return;
        }
        dropOutgoing(r.from());
        Player sender = Bukkit.getPlayer(r.from());
        if (sender == null) {
            plugin.msg().send(recipient, "tpa-not-found", "{player}", String.valueOf(senderName));
            return;
        }
        plugin.msg().send(recipient, "tpa-accepted-you", "{player}", sender.getName());
        plugin.msg().send(sender, "tpa-accepted", "{player}", recipient.getName());
        perform(r);
    }

    public void deny(Player recipient, String senderName) {
        Request r = find(recipient, senderName);
        if (r == null) {
            plugin.msg().send(recipient, "tpa-none");
            return;
        }
        dropOutgoing(r.from());
        Player sender = Bukkit.getPlayer(r.from());
        plugin.msg().send(recipient, "tpa-denied-you", "{player}", sender == null ? "?" : sender.getName());
        if (sender != null) plugin.msg().send(sender, "tpa-denied", "{player}", recipient.getName());
    }

    public void cancel(Player sender) {
        if (!outgoing.containsKey(sender.getUniqueId())) {
            plugin.msg().send(sender, "tpa-nothing-to-cancel");
            return;
        }
        Request r = outgoing.get(sender.getUniqueId());
        dropOutgoing(sender.getUniqueId());
        Player target = Bukkit.getPlayer(r.to());
        plugin.msg().send(sender, "tpa-cancelled", "{player}", target == null ? "?" : target.getName());
        if (target != null) plugin.msg().send(target, "tpa-cancelled-target", "{player}", sender.getName());
    }

    // ------------------------------------------------------------------ /tpaauto, /tpaignore

    public void toggleAuto(Player player) {
        if (auto.remove(player.getUniqueId())) {
            plugin.msg().send(player, "tpaauto-off");
        } else {
            auto.add(player.getUniqueId());
            plugin.msg().send(player, "tpaauto-on");
        }
    }

    public void ignore(Player player, String time) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (time == null) {
            Long until = ignoreUntil.get(id);
            if (until != null && until > now) {
                plugin.msg().send(player, "tpaignore-status", "{time}", Durations.format((until - now) / 1000));
            } else {
                plugin.msg().send(player, "tpaignore-usage");
            }
            return;
        }
        if (time.equalsIgnoreCase("off") || time.equalsIgnoreCase("0")) {
            ignoreUntil.remove(id);
            plugin.msg().send(player, "tpaignore-off");
            return;
        }
        long seconds = Durations.parseSeconds(time);
        long max = Math.max(60, plugin.getConfig().getLong("tpa.ignore-max-seconds", 86400L * 7));
        if (seconds <= 0) {
            plugin.msg().send(player, "tpaignore-usage");
            return;
        }
        seconds = Math.min(seconds, max);
        ignoreUntil.put(id, now + seconds * 1000);
        incoming.remove(id);                       // pending requests to you are dropped
        outgoing.values().removeIf(r -> r.to().equals(id));
        plugin.msg().send(player, "tpaignore-on", "{time}", Durations.format(seconds));
    }

    // ------------------------------------------------------------------ teleporting

    private void perform(Request r) {
        Player sender = Bukkit.getPlayer(r.from());
        Player recipient = Bukkit.getPlayer(r.to());
        if (sender == null || recipient == null) return;
        Player mover = r.here() ? recipient : sender;
        Player destination = r.here() ? sender : recipient;
        if (!ready(mover) || !ready(destination) || teleporting.contains(mover.getUniqueId())) {
            plugin.msg().send(sender, "tpa-failed-busy");
            plugin.msg().send(recipient, "tpa-failed-busy");
            return;
        }
        int delay = plugin.getConfig().getInt("tpa.delay-seconds", 3);
        if (delay <= 0 || mover.hasPermission("perplayerkit.tpa.bypass-delay")) {
            go(mover, destination);
            return;
        }
        teleporting.add(mover.getUniqueId());
        plugin.msg().send(mover, "tpa-teleporting", "{seconds}", String.valueOf(delay), "{player}", destination.getName());
        Location start = mover.getLocation().clone();
        boolean cancelOnMove = plugin.getConfig().getBoolean("tpa.cancel-on-move", true);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            teleporting.remove(mover.getUniqueId());
            if (!mover.isOnline() || !destination.isOnline()) return;
            if (cancelOnMove && (!start.getWorld().equals(mover.getWorld())
                    || start.distanceSquared(mover.getLocation()) > 1.0)) {
                plugin.msg().send(mover, "tpa-moved");
                return;
            }
            if (!ready(mover) || !ready(destination)) {
                plugin.msg().send(mover, "tpa-failed-busy");
                return;
            }
            go(mover, destination);
        }, delay * 20L);
    }

    private void go(Player mover, Player destination) {
        remember(mover, mover.getLocation());
        mover.teleport(destination.getLocation());
        plugin.msg().send(mover, "tpa-teleported", "{player}", destination.getName());
    }

    // ------------------------------------------------------------------ /back

    /** Remembers where the player was (before a tpa teleport or where they died). */
    public void remember(Player player, Location where) {
        if (where != null && where.getWorld() != null) back.put(player.getUniqueId(), where.clone());
    }

    public void back(Player player) {
        Location target = back.get(player.getUniqueId());
        if (target == null) {
            plugin.msg().send(player, "back-none");
            return;
        }
        if (!ready(player)) {
            plugin.msg().send(player, "back-busy");
            return;
        }
        back.put(player.getUniqueId(), player.getLocation().clone());    // running /back again returns you here
        player.teleport(target);
        plugin.msg().send(player, "back-done");
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        dropOutgoing(id);
        incoming.remove(id);
        teleporting.remove(id);
        if (!plugin.getConfig().getBoolean("back.keep-after-logout", false)) back.remove(id);
    }

    public List<String> names(Player except) {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(except)) out.add(p.getName());
        }
        return out;
    }
}
