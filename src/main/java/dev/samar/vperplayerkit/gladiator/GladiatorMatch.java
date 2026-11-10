package dev.samar.vperplayerkit.gladiator;

import dev.samar.vperplayerkit.rtpq.DuelManager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One Gladiator match: the host's team (A) against the opponent team (B). */
public final class GladiatorMatch {
    public enum State { SETUP, OPEN, STARTING, ACTIVE, ENDED }

    public enum Blast { SINGLE, DOUBLE }

    public static final int MAX_A = 5;
    public static final int MAX_B = 15;

    public final UUID host;
    public final String hostName;
    public final List<UUID> teamA = new ArrayList<>();
    public final List<UUID> teamB = new ArrayList<>();
    public final Map<UUID, String> names = new HashMap<>();
    public final Set<UUID> invited = new HashSet<>();

    public int sizeA = 1;
    public int sizeB = 5;
    public Blast blastA = Blast.SINGLE;
    public Blast blastB = Blast.SINGLE;
    public boolean elytraA = false;
    public boolean elytraB = false;
    public String biome = "auto";          // "auto" or a key from rtp.biomes
    public String kitSpec = "selected";    // "selected" or "kit:N", chosen by the host
    public State state = State.SETUP;

    // runtime, filled when the fight starts
    DuelManager.Duel arena;
    final Set<UUID> alive = new HashSet<>();
    final Map<UUID, Integer> outside = new HashMap<>();
    final Map<UUID, ItemStack[]> kitsUsed = new HashMap<>();
    BukkitTask zoneTask;

    GladiatorMatch(UUID host, String hostName) {
        this.host = host;
        this.hostName = hostName;
        this.names.put(host, hostName);
        this.teamA.add(host);
    }

    /** 0 = host team, 1 = opponent team, -1 = not in this match. */
    public int teamOf(UUID id) {
        if (teamA.contains(id)) return 0;
        if (teamB.contains(id)) return 1;
        return -1;
    }

    public List<UUID> participants() {
        List<UUID> all = new ArrayList<>(teamA);
        all.addAll(teamB);
        return all;
    }

    public String name(UUID id) {
        return names.getOrDefault(id, "?");
    }

    public boolean elytra(int team) {
        return team == 0 ? elytraA : elytraB;
    }

    public Blast blast(int team) {
        return team == 0 ? blastA : blastB;
    }

    public boolean fighting() {
        return arena != null;
    }

    public boolean contains(org.bukkit.Location loc) {
        return arena != null && arena.contains(loc);
    }

    public final Map<UUID, Integer> tiers = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean isAlive(UUID id) {
        return alive.contains(id);
    }
}
