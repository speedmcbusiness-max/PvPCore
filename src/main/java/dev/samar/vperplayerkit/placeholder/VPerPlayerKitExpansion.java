package dev.samar.vperplayerkit.placeholder;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.ffa.FfaManager;
import dev.samar.vperplayerkit.gladiator.GladiatorMatch;
import dev.samar.vperplayerkit.rtpq.DuelManager;
import dev.samar.vperplayerkit.rtpq.PlayerStats;
import dev.samar.vperplayerkit.rtpq.QueueManager;
import dev.samar.vperplayerkit.rtpq.Tier;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** %vperplayerkit_<placeholder>%, see README for the full list. */
public final class VPerPlayerKitExpansion extends PlaceholderExpansion {
    private final VPerPlayerKit plugin;

    public VPerPlayerKitExpansion(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "vperplayerkit";
    }

    @Override
    public String getAuthor() {
        return "Samar";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    private static final java.util.regex.Pattern TOP =
            java.util.regex.Pattern.compile("top(\\d{1,2})_(name|uuid|skin|head|tier|value|points)");

    /**
     * One field of a leaderboard entry. Fields: name, uuid, tier, value (the category's number, tier points for overall),
     * skin (URL of the full body skin picture) and head (URL of the head picture), both from config placeholders.*-url.
     */
    private String boardField(String category, int position, String field) {
        if (position < 1 || position > 10) return null;
        List<PlayerStats> board = plugin.cache().board(category);
        if (position > board.size()) {
            return switch (field) {
                case "value", "points" -> "0";
                case "skin", "head" -> plugin.getConfig().getString("placeholders.empty-skin-url", "");
                default -> "-";
            };
        }
        PlayerStats s = board.get(position - 1);
        String name = s.name == null ? "Steve" : s.name;
        return switch (field) {
            case "name" -> name;
            case "uuid" -> s.uuid.toString();
            case "tier" -> s.tier().display();
            case "skin" -> plugin.getConfig().getString("placeholders.skin-url", "https://mc-heads.net/body/{name}/right")
                    .replace("{name}", name).replace("{uuid}", s.uuid.toString());
            case "head" -> plugin.getConfig().getString("placeholders.head-url", "https://mc-heads.net/avatar/{name}/128")
                    .replace("{name}", name).replace("{uuid}", s.uuid.toString());
            case "value", "points" -> switch (category) {
                case "kills" -> String.valueOf(s.kills);
                case "victory" -> String.valueOf(s.wins);
                case "defeat" -> String.valueOf(s.losses);
                case "matchplayed" -> String.valueOf(s.played);
                default -> s.tier().pointsText();
            };
            default -> null;
        };
    }

    private static String yes(boolean value) {
        return value ? "yes" : "no";
    }

    private static String bar(double percent) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, percent)) / 10.0);
        return "█".repeat(filled) + "░".repeat(10 - filled);
    }

    private static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        String p = params.toLowerCase();

        // ------------------------------------------------ top3 shortcuts: top1_name, top2_skin ... (overall leaderboard)
        java.util.regex.Matcher top = TOP.matcher(p);
        if (top.matches()) return boardField("overall", Integer.parseInt(top.group(1)), top.group(2));

        // ------------------------------------------------ leaderboards: lb_<category>_<position>_<field>
        if (p.startsWith("lb_")) {
            String[] parts = p.substring(3).split("_");
            if (parts.length != 3) return null;
            try {
                return boardField(parts[0], Integer.parseInt(parts[1]), parts[2]);
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        // ------------------------------------------------ server-wide (no player needed)
        switch (p) {
            case "queue_size" -> {
                return String.valueOf(plugin.queue().size());
            }
            case "duels_active" -> {
                int count = 0;
                for (DuelManager.Duel d : plugin.duels().activeDuels()) {
                    if (d.state() != DuelManager.State.ENDED && d.spawn(0) != null) count++;
                }
                return String.valueOf(count);
            }
            case "gladiator_open" -> {
                return String.valueOf(plugin.gladiator().openMatches().size());
            }
            case "ffa_queue_size" -> {
                return String.valueOf(plugin.ffa().queueSize());
            }
            case "ffa_matches" -> {
                return String.valueOf(plugin.ffa().activeMatches().size());
            }
            case "ffa_next", "ffa_cooldown" -> {
                return String.valueOf(plugin.ffa().secondsUntilNext());
            }
            case "ffa_next_formatted", "ffa_cooldown_formatted" -> {
                long left = plugin.ffa().secondsUntilNext();
                return left < 0 ? "-" : (left / 60) + ":" + String.format("%02d", left % 60);
            }
            case "event_state" -> {
                return plugin.events().state().name().toLowerCase();
            }
            case "event_players" -> {
                return String.valueOf(plugin.events().size());
            }
            case "spectators_total" -> {
                return String.valueOf(plugin.spectators().total());
            }
            case "ffa_arenas" -> {
                return String.valueOf(plugin.ffa().arenas().size());
            }
            case "rtp_biomes" -> {
                return String.join(", ", plugin.rtp().biomes());
            }
            case "max_kits" -> {
                return String.valueOf(plugin.maxKits());
            }
            default -> { }
        }

        if (offline == null) return "";
        UUID id = offline.getUniqueId();
        Player player = offline.getPlayer();
        PlayerCache.Data d = plugin.cache().get(id);
        PlayerStats s = d == null ? null : d.stats;

        switch (p) {
            // ------------------------------------------------ kits (/kit, /lsk, /loadselectedkit, /kitN)
            case "kit_selected" -> {
                return d == null || d.selectedKit == 0 ? "None" : String.valueOf(d.selectedKit);
            }
            case "region" -> {
                return d == null ? plugin.results().defaultRegion() : d.region;
            }
            case "kits_saved" -> {
                return d == null ? "0" : String.valueOf(d.savedKits);
            }
            // ------------------------------------------------ tier (/tier, /result, tier info dialog)
            case "tier" -> {
                return s == null ? Tier.UNRANKED.display() : s.tier().display();
            }
            case "tier_points" -> {
                return s == null ? "0" : s.tier().pointsText();
            }
            case "tier_next" -> {
                if (s == null) return Tier.LT6.display();
                Tier next = s.tier().next();
                return next == null ? "None" : next.display();
            }
            case "tier_next_points" -> {
                if (s == null) return Tier.LT6.pointsText();
                Tier next = s.tier().next();
                return next == null ? "0" : next.pointsText();
            }
            case "tier_progress" -> {
                return s == null ? "0" : num(s.progress);
            }
            case "tier_progress_bar" -> {
                return bar(s == null ? 0 : s.progress);
            }
            case "tier_test_ready" -> {
                return yes(s != null && s.testReady());
            }
            case "tier_chances" -> {
                return s == null ? "2" : String.valueOf(s.chances);
            }
            // ------------------------------------------------ stats (/state, /leaderboard)
            case "kills" -> {
                return s == null ? "0" : String.valueOf(s.kills);
            }
            case "wins", "victory" -> {
                return s == null ? "0" : String.valueOf(s.wins);
            }
            case "losses", "defeat" -> {
                return s == null ? "0" : String.valueOf(s.losses);
            }
            case "played", "matchplayed" -> {
                return s == null ? "0" : String.valueOf(s.played);
            }
            case "winrate" -> {
                return s == null || s.played == 0 ? "0" : num(s.wins * 100.0 / s.played);
            }
            case "kd", "kdr" -> {
                return s == null || s.losses == 0 ? (s == null ? "0" : String.valueOf(s.kills))
                        : num((double) s.kills / s.losses);
            }
            case "rank_overall", "rank_kills", "rank_victory", "rank_defeat", "rank_matchplayed" -> {
                if (d == null) return "-";
                String category = p.substring(5);
                for (int i = 0; i < PlayerCache.CATEGORIES.length; i++) {
                    if (PlayerCache.CATEGORIES[i].equals(category)) return d.ranks[i] < 0 ? "-" : String.valueOf(d.ranks[i]);
                }
                return "-";
            }
            // ------------------------------------------------ queue (/rtpq)
            case "queue_in" -> {
                return yes(plugin.queue().isQueued(id));
            }
            case "queue_biome" -> {
                QueueManager.Entry e = plugin.queue().get(id);
                return e == null ? "" : e.biome;
            }
            case "queue_kit" -> {
                QueueManager.Entry e = plugin.queue().get(id);
                return e == null ? "" : String.valueOf(e.kitId);
            }
            case "queue_test" -> {
                QueueManager.Entry e = plugin.queue().get(id);
                return yes(e != null && e.test);
            }
            // ------------------------------------------------ generic match state (/leave, /regear, command lock)
            case "in_match" -> {
                return yes(player != null && plugin.inMatch(player));
            }
            case "match_type" -> {
                if (plugin.duels().duelOf(id) != null) return "duel";
                GladiatorMatch g = plugin.gladiator().matchOf(id);
                if (g != null && g.fighting()) return "gladiator";
                if (plugin.ffa().fighting(id)) return "ffa";
                return "none";
            }
            // ------------------------------------------------ duel
            case "duel_opponent" -> {
                DuelManager.Duel duel = plugin.duels().duelOf(id);
                return duel == null ? "" : duel.opponentName(id);
            }
            case "duel_score" -> {
                DuelManager.Duel duel = plugin.duels().duelOf(id);
                return duel == null ? "0" : String.valueOf(duel.score(id));
            }
            case "duel_opponent_score" -> {
                DuelManager.Duel duel = plugin.duels().duelOf(id);
                return duel == null ? "0" : String.valueOf(duel.opponentScore(id));
            }
            case "duel_target" -> {
                DuelManager.Duel duel = plugin.duels().duelOf(id);
                return duel == null ? "0" : String.valueOf(duel.target());
            }
            case "duel_time" -> {
                DuelManager.Duel duel = plugin.duels().duelOf(id);
                return duel == null ? "0" : String.valueOf(duel.elapsedSeconds());
            }
            case "duel_totems" -> {
                DuelManager.Duel duel = plugin.duels().duelOf(id);
                return duel == null ? "0" : String.valueOf(duel.ownPops(id));
            }
            case "duel_tier_test" -> {
                DuelManager.Duel duel = plugin.duels().duelOf(id);
                return yes(duel != null && duel.isTest());
            }
            // ------------------------------------------------ gladiator
            case "gladiator_in" -> {
                return yes(plugin.gladiator().isBusy(id));
            }
            case "gladiator_role" -> {
                GladiatorMatch g = plugin.gladiator().matchOf(id);
                if (g == null) return "none";
                if (g.host.equals(id)) return "host";
                return g.teamOf(id) == 0 ? "teammate" : "opponent";
            }
            case "gladiator_host" -> {
                GladiatorMatch g = plugin.gladiator().matchOf(id);
                return g == null ? "" : g.hostName;
            }
            case "gladiator_state" -> {
                GladiatorMatch g = plugin.gladiator().matchOf(id);
                return g == null ? "none" : g.state.name().toLowerCase();
            }
            case "gladiator_team", "gladiator_team_size" -> {
                GladiatorMatch g = plugin.gladiator().matchOf(id);
                return g == null ? "0" : String.valueOf(g.teamA.size());
            }
            case "gladiator_team_max" -> {
                GladiatorMatch g = plugin.gladiator().matchOf(id);
                return g == null ? "0" : String.valueOf(g.sizeA);
            }
            case "gladiator_opponents" -> {
                GladiatorMatch g = plugin.gladiator().matchOf(id);
                return g == null ? "0" : String.valueOf(g.teamB.size());
            }
            case "gladiator_opponents_max" -> {
                GladiatorMatch g = plugin.gladiator().matchOf(id);
                return g == null ? "0" : String.valueOf(g.sizeB);
            }
            case "gladiator_alive" -> {
                GladiatorMatch g = plugin.gladiator().matchOf(id);
                return g == null ? "0" : String.valueOf(g.isAlive(id) ? 1 : 0);
            }
            // ------------------------------------------------ ffa (/ffartpq, /rtpffa, /rtpqffa)
            case "ffa_in_queue" -> {
                return yes(plugin.ffa().isQueued(id));
            }
            case "ffa_in_match" -> {
                return yes(plugin.ffa().fighting(id));
            }
            case "ffa_arena" -> {
                FfaManager.Match m = plugin.ffa().matchOf(id);
                return m == null ? "" : m.arenaName();
            }
            case "ffa_players" -> {
                FfaManager.Match m = plugin.ffa().matchOf(id);
                return m == null ? "0" : String.valueOf(m.playerCount());
            }
            case "ffa_alive" -> {
                FfaManager.Match m = plugin.ffa().matchOf(id);
                return m == null ? "0" : String.valueOf(m.aliveCount());
            }
            // ------------------------------------------------ totem practice and rtp
            case "totemprac_in" -> {
                return yes(player != null && plugin.totemPrac().inPracWorld(player));
            }
            case "totemprac_running" -> {
                return yes(plugin.totemPrac().isRunning(id));
            }
            case "totemprac_target" -> {
                return yes(plugin.totemPrac().aim().has(id));
            }
            case "vspec_count", "spectators" -> {
                return String.valueOf(plugin.spectators().countForPlayer(id));
            }
            case "spectating" -> {
                return yes(plugin.spectators().isSpectating(id));
            }
            case "spectating_target" -> {
                return plugin.spectators().targetName(id);
            }
            case "event_in" -> {
                return yes(plugin.events().isBusy(id));
            }
            case "rank" -> {
                String key = plugin.ranks().topKey(id);
                return key == null ? "none" : key;
            }
            case "rank_tag" -> {
                return plugin.ranks().tagOf(id);
            }
            case "banned" -> {
                return yes(plugin.bans().isBanned(id));
            }
            case "verified" -> {
                return yes(plugin.discord().cachedTag(id) != null);
            }
            case "discord_name" -> {
                String tag = plugin.discord().cachedTag(id);
                return tag == null ? "" : tag;
            }
            case "rtp_cooldown" -> {
                return String.valueOf(plugin.rtp().cooldownLeft(id));
            }
            default -> {
                return null;
            }
        }
    }
}
