package dev.samar.vperplayerkit.moderation;

import dev.samar.vperplayerkit.VPerPlayerKit;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /freeze, /unfreeze and /ss. A frozen player cannot move or do anything. /ss <player> freezes the player, saves where they
 * were and teleports them to the ss spawn (/ssspawn set), running /ss <player> again sends them back to the same place.
 */
public final class FreezeManager {

    private static final class Ss {
        Location back;
        GameMode mode;
        UUID staff;
        Location staffBack;
    }

    private final VPerPlayerKit plugin;
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Ss> sessions = new HashMap<>();

    public FreezeManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID id : frozen) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.sendActionBar(plugin.msg().get("freeze-actionbar"));
                }
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    public boolean isFrozen(UUID id) {
        return frozen.contains(id);
    }

    public boolean isScreensharing(UUID id) {
        return sessions.containsKey(id);
    }

    // ------------------------------------------------------------------ /freeze, /unfreeze

    private void persist(UUID id, boolean on) {
        String owner = id.toString();
        if (on) plugin.async(() -> plugin.storage().put(owner, "freeze", "on", new byte[] {1}));
        else plugin.async(() -> plugin.storage().delete(owner, "freeze", "on"));
    }

    public void freeze(CommandSender by, Player target) {
        if (frozen.contains(target.getUniqueId())) {
            plugin.msg().send(by, "freeze-already", "{player}", target.getName());
            return;
        }
        if (plugin.inMatch(target)) {
            plugin.msg().send(by, "freeze-in-match", "{player}", target.getName());
            return;
        }
        frozen.add(target.getUniqueId());
        persist(target.getUniqueId(), true);
        announceFrozen(target);
        plugin.msg().send(by, "freeze-done", "{player}", target.getName());
    }

    private void announceFrozen(Player target) {
        plugin.msg().send(target, "freeze-you");
        target.showTitle(Title.title(plugin.msg().get("freeze-title"), plugin.msg().get("freeze-subtitle")));
    }

    public void unfreeze(CommandSender by, Player target) {
        if (!frozen.remove(target.getUniqueId())) {
            plugin.msg().send(by, "freeze-not-frozen", "{player}", target.getName());
            return;
        }
        persist(target.getUniqueId(), false);
        plugin.msg().send(target, "freeze-released");
        plugin.msg().send(by, "unfreeze-done", "{player}", target.getName());
    }

    /** Frozen players stay frozen after relogging. */
    public void handleJoin(Player player) {
        UUID id = player.getUniqueId();
        String owner = id.toString();
        plugin.async(() -> plugin.storage().exists(owner, "freeze", "on"), (Boolean was) -> {
            if (!was || !player.isOnline()) return;
            frozen.add(id);
            announceFrozen(player);
        });
    }

    /** Logging out while frozen can run a command (freeze.logout-command, e.g. a ban). Blank = nothing. */
    public void handleQuit(Player player) {
        if (!frozen.contains(player.getUniqueId())) return;
        String command = plugin.getConfig().getString("freeze.logout-command", "");
        if (command == null || command.isBlank()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                command.replace("{player}", player.getName()));
        Bukkit.broadcast(plugin.msg().get("freeze-logout", "{player}", player.getName()));
    }

    // ------------------------------------------------------------------ /ss and /ssspawn

    public Location ssSpawn() {
        return plugin.data().getLocation("ss.spawn");
    }

    public void setSsSpawn(Player staff) {
        plugin.data().setLocation("ss.spawn", staff.getLocation());
        plugin.msg().send(staff, "ss-spawn-set");
    }

    /** First time: takes the player to the ss spawn and freezes them. Again: sends them back to where they were. */
    public void toggleScreenshare(Player staff, Player target) {
        UUID id = target.getUniqueId();
        Ss session = sessions.remove(id);
        if (session != null) {
            frozen.remove(id);
            persist(id, false);
            target.setGameMode(session.mode == null ? GameMode.SURVIVAL : session.mode);
            if (session.back != null) target.teleport(session.back);
            plugin.msg().send(target, "ss-ended");
            plugin.msg().send(staff, "ss-ended-staff", "{player}", target.getName());
            if (plugin.getConfig().getBoolean("ss.teleport-staff", true) && session.staff != null
                    && session.staff.equals(staff.getUniqueId()) && session.staffBack != null) {
                staff.teleport(session.staffBack);
            }
            return;
        }

        Location spawn = ssSpawn();
        if (spawn == null) {
            plugin.msg().send(staff, "ss-no-spawn");
            return;
        }
        if (plugin.inMatch(target) || plugin.queue().isQueued(id) || plugin.duels().isBusy(id)
                || plugin.gladiator().isBusy(id) || plugin.ffa().isBusy(id)) {
            plugin.msg().send(staff, "ss-busy", "{player}", target.getName());
            return;
        }
        Ss s = new Ss();
        s.back = target.getLocation().clone();
        s.mode = target.getGameMode();
        s.staff = staff.getUniqueId();
        s.staffBack = staff.getLocation().clone();
        sessions.put(id, s);
        frozen.add(id);
        persist(id, true);

        target.teleport(spawn);
        if (plugin.getConfig().getBoolean("ss.teleport-staff", true)) staff.teleport(spawn);
        plugin.msg().send(target, "ss-you");
        target.showTitle(Title.title(plugin.msg().get("ss-title"), plugin.msg().get("ss-subtitle")));
        plugin.msg().send(staff, "ss-started", "{player}", target.getName());
    }
}
