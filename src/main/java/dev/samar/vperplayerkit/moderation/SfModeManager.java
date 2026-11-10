package dev.samar.vperplayerkit.moderation;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** /sfmode: puts you in spectator and blocks switching to creative, survival or adventure until you turn it off. */
public final class SfModeManager {
    private final VPerPlayerKit plugin;
    private final Set<UUID> active = new HashSet<>();
    private final Map<UUID, GameMode> previous = new HashMap<>();
    private final Set<UUID> releasing = new HashSet<>();

    public SfModeManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    public boolean isActive(UUID id) {
        return active.contains(id);
    }

    /** True when this game mode change must be cancelled. */
    public boolean blocks(Player player, GameMode newMode) {
        return active.contains(player.getUniqueId()) && newMode != GameMode.SPECTATOR
                && !releasing.contains(player.getUniqueId());
    }

    public void toggle(Player player) {
        UUID id = player.getUniqueId();
        String owner = id.toString();
        if (active.contains(id)) {
            active.remove(id);
            GameMode back = previous.remove(id);
            releasing.add(id);
            player.setGameMode(back == null ? GameMode.SURVIVAL : back);
            releasing.remove(id);
            plugin.async(() -> plugin.storage().delete(owner, "sfmode", "on"));
            plugin.msg().send(player, "sfmode-off");
            return;
        }
        GameMode mode = player.getGameMode();
        previous.put(id, mode == GameMode.SPECTATOR ? GameMode.SURVIVAL : mode);
        active.add(id);
        player.setGameMode(GameMode.SPECTATOR);
        byte[] data = previous.get(id).name().getBytes(StandardCharsets.UTF_8);
        plugin.async(() -> plugin.storage().put(owner, "sfmode", "on", data));
        plugin.msg().send(player, "sfmode-on");
    }

    /** Staying in sfmode across relogs. */
    public void restoreOnJoin(Player player) {
        UUID id = player.getUniqueId();
        String owner = id.toString();
        plugin.async(() -> plugin.storage().get(owner, "sfmode", "on"), (byte[] data) -> {
            if (data == null || !player.isOnline()) return;
            GameMode back;
            try {
                back = GameMode.valueOf(new String(data, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ex) {
                back = GameMode.SURVIVAL;
            }
            previous.put(id, back);
            active.add(id);
            if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
            plugin.msg().send(player, "sfmode-on");
        });
    }

    public void forget(UUID id) {
        active.remove(id);
        previous.remove(id);
    }
}
