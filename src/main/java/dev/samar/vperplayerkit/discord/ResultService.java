package dev.samar.vperplayerkit.discord;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.rtpq.Tier;
import dev.samar.vperplayerkit.util.Text;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the two tier result posts and hands them to the Discord bot.
 * Normal result (both tiers below results.high-min-tier): an embed with Tester, Region, Username, Previous Rank and
 * Rank Earned, the player's skin as thumbnail and a ping above it.
 * High result (HT3 and up): a text post with the tier fights and the outcome.
 * The bot adds the reactions. Every piece of text is in config.yml under "results".
 */
public final class ResultService {
    private final VPerPlayerKit plugin;
    private final DiscordService discord;

    public ResultService(VPerPlayerKit plugin, DiscordService discord) {
        this.plugin = plugin;
        this.discord = discord;
    }

    // ------------------------------------------------------------------ regions (the "Region:" field)

    public List<String> regions() {
        List<String> list = plugin.getConfig().getStringList("results.regions");
        return list.isEmpty() ? List.of("NA", "EU", "AS", "AU", "SA", "AF", "ME") : list;
    }

    public String defaultRegion() {
        return plugin.getConfig().getString("results.default-region", "NA");
    }

    /** The configured spelling of a region, or null when it is not one of them. */
    public String canonical(String input) {
        for (String region : regions()) {
            if (region.equalsIgnoreCase(input)) return region;
        }
        return null;
    }

    /** Blocking database read. */
    public String region(UUID uuid) {
        byte[] data = plugin.storage().get(uuid.toString(), "region", "main");
        return data == null ? defaultRegion() : new String(data, StandardCharsets.UTF_8);
    }

    /** Blocking database write. */
    public void setRegion(UUID uuid, String region) {
        plugin.storage().put(uuid.toString(), "region", "main", region.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ publishing

    /**
     * Posts a result to Discord. {@code fights} are free text lines (e.g. "Lost 1-4 vs. ilmcl") shown in high results.
     * {@code testerUuid} may be null (automatic tier tests), then {@code testerName} is shown as plain text.
     */
    public void publish(UUID uuid, String ign, Tier previous, Tier earned, String testerName, UUID testerUuid,
                        List<String> fights) {
        if (!discord.enabled()) return;
        plugin.async(() -> {
            String region = region(uuid);
            String playerDc = discord.discordId(uuid);
            String testerDc = testerUuid == null ? null : discord.discordId(testerUuid);
            discord.post(build(uuid, ign, previous, earned, testerName, testerDc, playerDc, region, fights));
        });
    }

    private String text(String path, String def, String... kv) {
        return Text.replace(plugin.getConfig().getString(path, def), kv);
    }

    private static int parseColor(String hex) {
        try {
            return Color.decode(hex.startsWith("#") ? hex : "#" + hex).getRGB() & 0xFFFFFF;
        } catch (NumberFormatException ex) {
            return 0xFF0000;
        }
    }

    DiscordService.Result build(UUID uuid, String ign, Tier previous, Tier earned, String testerName, String testerDc,
                                String playerDc, String region, List<String> fights) {
        var cfg = plugin.getConfig();
        Tier min = Tier.fromName(cfg.getString("results.high-min-tier", "HT3"));
        if (min == null) min = Tier.HT3;
        boolean high = Math.max(previous.ordinal(), earned.ordinal()) >= min.ordinal();

        String mention = playerDc != null ? "<@" + playerDc + ">" : "**" + ign + "**";
        List<String> reactions = new ArrayList<>(cfg.getStringList("results.reactions"));
        String channel = cfg.getString(high ? "results.high-channel-id" : "results.normal-channel-id", "");
        if (channel == null || channel.isBlank()) channel = cfg.getString("discord.announce-channel-id", "");

        if (!high) {
            String p = "results.normal.";
            String skin = text("results.skin-url", "https://mc-heads.net/body/{name}/right",
                    "{name}", ign, "{uuid}", uuid.toString());
            String avatar = text("results.avatar-url", "https://mc-heads.net/avatar/{name}/64",
                    "{name}", ign, "{uuid}", uuid.toString());
            String tester = testerDc != null ? "<@" + testerDc + ">" : testerName;
            List<String[]> fields = new ArrayList<>();
            fields.add(new String[] {text(p + "label-tester", "Tester:"), tester});
            fields.add(new String[] {text(p + "label-region", "Region:"), region});
            fields.add(new String[] {text(p + "label-username", "Username:"), ign});
            fields.add(new String[] {text(p + "label-previous", "Previous Rank:"), previous.longName()});
            fields.add(new String[] {text(p + "label-earned", "Rank Earned:"), earned.longName()});
            return new DiscordService.Result(channel, mention, true,
                    text(p + "author-title", "{player}'s Test Results 🏆", "{player}", ign), avatar, skin,
                    parseColor(cfg.getString("results.embed-color", "#FF0000")), fields, "", reactions);
        }

        String p = "results.high.";
        boolean up = earned.ordinal() > previous.ordinal();
        boolean down = earned.ordinal() < previous.ordinal();
        Tier attempted = previous.next() != null ? previous.next() : previous;
        String headline = up
                ? text(p + "headline-passed", "Passed {tier}", "{tier}", earned.longName())
                : text(p + "headline-failed", "Failed {attempted}", "{attempted}", attempted.longName());
        String outcome = up
                ? text(p + "outcome-promoted", "*Promoted To **{tier}***", "{tier}", earned.longName())
                : (down ? text(p + "outcome-demoted", "*Demoted To **{tier}***", "{tier}", earned.longName())
                        : text(p + "outcome-failed", "*Stays **{tier}***", "{tier}", earned.longName()));

        StringBuilder sb = new StringBuilder();
        sb.append(text(p + "header", "{mention} - **{player} - {headline}**",
                "{mention}", mention, "{player}", ign, "{headline}", headline));
        if (fights != null && !fights.isEmpty()) {
            sb.append('\n').append(text(p + "fights-title", "__{tier} Fights:__", "{tier}", previous.display()));
            for (String fight : fights) sb.append('\n').append(text(p + "fight-line", "> {line}", "{line}", fight));
        }
        sb.append('\n').append(outcome);
        return new DiscordService.Result(channel, "", false, "", "", "", 0, List.of(), sb.toString(), reactions);
    }
}
