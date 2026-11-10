package dev.samar.vperplayerkit.rtpq;

import java.util.UUID;

public final class PlayerStats {
    public final UUID uuid;
    public String name;
    public int kills;
    public int wins;
    public int losses;
    public int played;
    public int tier;               // Tier ordinal
    public double progress;        // 0-100 towards the next tier test
    public int chances = 2;        // tier test attempts left

    public PlayerStats(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public Tier tier() {
        return Tier.fromIndex(tier);
    }

    /** True when the next queue match for this player is a tier test. */
    public boolean testReady() {
        return tier().next() != null && progress >= 100.0;
    }
}
