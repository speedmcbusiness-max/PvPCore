package dev.samar.vperplayerkit.rtpq;

import java.util.concurrent.ThreadLocalRandom;

/** Pure tier maths, no Bukkit calls, safe to run on any thread. */
public final class TierLogic {
    private TierLogic() {}

    public record Params(double base, double perTier, double popBonus, double popBonusMax,
                         double ownPopPenalty, double killBonus, double min, double max) {
    }

    public static final class Outcome {
        public boolean won;
        public boolean testing;
        public double gain;
        public double progress;
        public int chancesLeft;
        public boolean readyForTest;
        public boolean testFailed;
        public boolean chancesReset;
        public Tier promoted;
        public Tier current;          // the player's tier after this match
    }

    /**
     * Progress the winner earns. Depends on both tiers, totems popped on the opponent and by the winner,
     * how fast the match was won and how many kills the winner scored.
     */
    public static double gain(Params p, Tier winner, Tier loser, int opponentPops, int ownPops, int kills, long seconds) {
        double gain = p.base();
        gain += p.perTier() * (loser.ordinal() - winner.ordinal());
        gain += Math.min(p.popBonusMax(), opponentPops * p.popBonus());
        gain -= ownPops * p.ownPopPenalty();
        gain += kills * p.killBonus();
        if (seconds < 90) gain += 5;
        else if (seconds < 180) gain += 3;
        else if (seconds < 300) gain += 1;
        else if (seconds > 600) gain -= 2;
        gain = Math.max(p.min(), Math.min(p.max(), gain));
        return Math.round(gain * 10.0) / 10.0;
    }

    /** Applies a finished match to one player's stats and reports what changed. */
    public static Outcome apply(PlayerStats s, boolean won, boolean testing, int myScore, double gain) {
        s.played++;
        s.kills += myScore;
        if (won) s.wins++;
        else s.losses++;

        Outcome out = new Outcome();
        out.won = won;
        out.testing = testing;

        if (testing) {
            if (won) {
                s.tier = Math.min(Tier.values().length - 1, s.tier + 1);
                s.progress = 0;
                s.chances = 2;
                out.promoted = s.tier();
            } else {
                out.testFailed = true;
                s.chances--;
                if (s.chances <= 0) {
                    s.progress = 25 + ThreadLocalRandom.current().nextInt(56);   // 25-80
                    s.chances = 2;
                    out.chancesReset = true;
                }
            }
        } else if (won && s.tier().next() != null) {
            s.progress = Math.min(100.0, Math.round((s.progress + gain) * 10.0) / 10.0);
            out.gain = gain;
            out.readyForTest = s.progress >= 100.0;
        }
        out.current = s.tier();
        out.progress = s.progress;
        out.chancesLeft = s.chances;
        return out;
    }
}
