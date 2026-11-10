package dev.samar.vperplayerkit.nametag;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.placeholder.PapiHook;
import dev.samar.vperplayerkit.placeholder.PlayerCache;
import dev.samar.vperplayerkit.rtpq.Tier;
import dev.samar.vperplayerkit.util.Text;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom nametag (nametag.format): "{tier} &7| &f{player} &7| {flag}  {rank}". Everything before {player} becomes the team prefix,
 * everything after it the team suffix, so it also shows in the tab list. {flag} comes from the placeholder you set in
 * nametag.flag-placeholder (e.g. from your flags plugin), {rank} from /v:rank, {tier} is the queue tier.
 */
public final class NametagManager {
    private static final NamedTextColor[] LEGACY = {
            NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA,
            NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE, NamedTextColor.GOLD, NamedTextColor.GRAY,
            NamedTextColor.DARK_GRAY, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA,
            NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE};
    private static final Pattern COLOR = Pattern.compile("&(#[0-9a-fA-F]{6}|[0-9a-fA-F])");
    private static final Pattern DANGLING = Pattern.compile("(?:\\s|\\||&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR])+$");

    private final VPerPlayerKit plugin;

    public NametagManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
        // drop teams a crash may have left behind
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team team : new java.util.ArrayList<>(board.getTeams())) {
            if (team.getName().startsWith("vpk")) team.unregister();
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) update(p);
            }
        }.runTaskTimer(plugin, 200L, 200L);
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("nametag.enabled", true);
    }

    private static String teamName(Player player) {
        return "vpk" + player.getUniqueId().toString().replace("-", "").substring(0, 12);
    }

    private String flagOf(Player player) {
        String placeholder = plugin.getConfig().getString("nametag.flag-placeholder", "");
        if (placeholder == null || placeholder.isBlank()) return "";
        if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                return PapiHook.parse(player, placeholder);
            } catch (Throwable ex) {
                return "";
            }
        }
        return "";
    }

    private static String fix(String text) {
        return DANGLING.matcher(text).replaceAll("");        // no hanging "|" when the flag or rank is empty
    }

    public void update(Player player) {
        if (!enabled() || !player.isOnline()) return;
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = teamName(player);
        Team team = board.getTeam(name);
        if (team == null) team = board.registerNewTeam(name);
        if (!team.hasEntry(player.getName())) team.addEntry(player.getName());

        String format = plugin.getConfig().getString("nametag.format", "{tier} &7| &f{player} &7| {flag}  {rank}");
        int at = format.indexOf("{player}");
        String before = at < 0 ? "" : format.substring(0, at);
        String after = at < 0 ? "" : format.substring(at + "{player}".length());

        PlayerCache.Data data = plugin.cache().get(player.getUniqueId());
        Tier tier = data == null || data.stats == null ? Tier.UNRANKED : data.stats.tier();
        String flag = flagOf(player);
        String rank = plugin.ranks().tagOf(player.getUniqueId());

        before = before.replace("{tier}", tier.colored()).replace("{flag}", flag).replace("{rank}", rank);
        after = fix(after.replace("{tier}", tier.colored()).replace("{flag}", flag).replace("{rank}", rank));

        team.prefix(Text.legacy(before));
        team.suffix(Text.legacy(after));
        team.color(nameColor(before));
    }

    /** The color right before {player} in the format ("&f" -> white). */
    private static NamedTextColor nameColor(String before) {
        Matcher m = COLOR.matcher(before);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last == null) return NamedTextColor.WHITE;
        if (last.startsWith("#")) {
            TextColor hex = TextColor.fromHexString(last);
            return hex == null ? NamedTextColor.WHITE : NamedTextColor.nearestTo(hex);
        }
        return LEGACY[Character.digit(last.charAt(0), 16)];
    }

    public void remove(Player player) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName(player));
        if (team != null) team.unregister();
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) remove(p);
    }
}
