package dev.samar.vperplayerkit.discord;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.rtpq.PlayerStats;
import dev.samar.vperplayerkit.rtpq.Tier;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Discord side: /verify, /unverify, /stats, /leaderboard, /tier, /progress as slash commands.
 * Runs on JDA threads, so it only uses the (thread safe) storage and schedules anything that touches Bukkit.
 */
public final class DiscordBot extends ListenerAdapter implements DiscordService.Sink {
    private final VPerPlayerKit plugin;
    private final DiscordService service;
    private JDA jda;

    public DiscordBot(VPerPlayerKit plugin, DiscordService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void start() throws InterruptedException {
        String token = plugin.getConfig().getString("discord.token", "");
        jda = JDABuilder.createLight(token, EnumSet.noneOf(GatewayIntent.class)).addEventListeners(this).build();
        jda.awaitReady();
        registerCommands();
    }

    @Override
    public void shutdown() {
        if (jda != null) jda.shutdown();
    }

    @Override
    public void announce(String message) {
        String channelId = plugin.getConfig().getString("discord.announce-channel-id", "");
        if (jda == null || channelId == null || channelId.isBlank()) return;
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel != null) channel.sendMessage(message).queue();
    }

    /** Normal results go out as an embed, high results as plain text. The bot adds the reactions itself. */
    @Override
    public void post(DiscordService.Result result) {
        String channelId = result.channelId();
        if (jda == null || channelId == null || channelId.isBlank()) return;
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            plugin.getLogger().warning("Discord channel " + channelId + " was not found (results.*-channel-id).");
            return;
        }
        Consumer<Message> react = message -> {
            for (String emoji : result.reactions()) {
                try {
                    message.addReaction(Emoji.fromUnicode(emoji)).queue(null, error -> { });
                } catch (Exception ignored) {
                    // invalid emoji in the config
                }
            }
        };
        if (result.embed()) {
            EmbedBuilder eb = new EmbedBuilder().setColor(result.color());
            if (!result.authorTitle().isBlank()) {
                eb.setAuthor(result.authorTitle(), null, result.authorIconUrl().isBlank() ? null : result.authorIconUrl());
            }
            if (!result.thumbnailUrl().isBlank()) eb.setThumbnail(result.thumbnailUrl());
            for (String[] field : result.fields()) eb.addField(field[0], field[1], false);
            MessageCreateAction action = channel.sendMessageEmbeds(eb.build());
            if (!result.content().isBlank()) action = action.setContent(result.content());
            action.queue(react);
        } else {
            channel.sendMessage(result.text()).queue(react);
        }
    }

    private void registerCommands() {
        OptionData category = new OptionData(OptionType.STRING, "category", "Leaderboard category", true)
                .addChoice("overall", "overall")
                .addChoice("kills", "kills")
                .addChoice("victory", "victory")
                .addChoice("playedmatch", "matchplayed")
                .addChoice("defeat", "defeat");
        OptionData type = new OptionData(OptionType.STRING, "type", "Look up by Minecraft name or by Discord user", true)
                .addChoice("playername", "playername")
                .addChoice("dc", "dc");

        var commands = List.of(
                Commands.slash("verify", "Link your Minecraft account")
                        .addOption(OptionType.STRING, "code", "The code you got from /verify in game", true),
                Commands.slash("unverify", "Unlink your Minecraft account")
                        .addOption(OptionType.STRING, "account", "Minecraft name or Discord ID (admins only)", false),
                Commands.slash("stats", "Your kills, wins and losses"),
                Commands.slash("leaderboard", "Show a leaderboard").addOptions(category),
                Commands.slash("tier", "Look up a player's tier").addOptions(type)
                        .addOption(OptionType.STRING, "target", "Minecraft name, or a Discord @mention / ID", true),
                Commands.slash("progress", "Your tier test progress (only you see it)"));

        String guildId = plugin.getConfig().getString("discord.guild-id", "");
        Guild guild = (guildId == null || guildId.isBlank()) ? null : jda.getGuildById(guildId);
        CommandListUpdateAction action = guild != null ? guild.updateCommands() : jda.updateCommands();
        action.addCommands(commands).queue();
    }

    // ------------------------------------------------------------------ helpers

    private String nameOf(PlayerStats stats, UUID uuid) {
        if (stats != null && stats.name != null) return stats.name;
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? "Unknown" : name;
    }

    private static String bar(double percent) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, percent)) / 10.0);
        return "█".repeat(filled) + "░".repeat(10 - filled);
    }

    private static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    private EmbedBuilder statsEmbed(PlayerStats s, String name) {
        Tier tier = s.tier();
        return new EmbedBuilder()
                .setTitle(name)
                .addField("Tier", tier.display() + " (" + tier.pointsText() + " pts)", true)
                .addField("Kills", String.valueOf(s.kills), true)
                .addField("Victories", String.valueOf(s.wins), true)
                .addField("Defeats", String.valueOf(s.losses), true)
                .addField("Matches played", String.valueOf(s.played), true);
    }

    // ------------------------------------------------------------------ slash commands

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        try {
            switch (event.getName()) {
                case "verify" -> verify(event);
                case "unverify" -> unverify(event);
                case "stats" -> stats(event);
                case "leaderboard" -> leaderboard(event);
                case "tier" -> tier(event);
                case "progress" -> progress(event);
                default -> { }
            }
        } catch (Exception ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Discord command failed", ex);
            if (!event.isAcknowledged()) event.reply("Something went wrong.").setEphemeral(true).queue();
        }
    }

    private void verify(SlashCommandInteractionEvent event) {
        String code = event.getOption("code").getAsString().trim().toUpperCase();
        if (service.uuidOfDiscord(event.getUser().getId()) != null) {
            event.reply("Your Discord account is already linked. Use `/unverify` first.").setEphemeral(true).queue();
            return;
        }
        DiscordService.CodeEntry entry = service.peek(code);
        if (entry == null) {
            event.reply("That code is invalid or expired. Run `/verify` in game to get a new one.").setEphemeral(true).queue();
            return;
        }
        event.reply("Is this really you? You are about to link the Minecraft account **" + entry.name()
                        + "** to " + event.getUser().getAsMention() + ".")
                .setEphemeral(true)
                .addActionRow(Button.success("vpk-yes:" + code, "Yes, that's me"), Button.danger("vpk-no:" + code, "No"))
                .queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("vpk-")) return;
        if (id.startsWith("vpk-no:")) {
            event.editMessage("Cancelled, nothing was linked.").setComponents().queue();
            return;
        }
        String code = id.substring("vpk-yes:".length());
        DiscordService.CodeEntry entry = service.peek(code);
        if (entry == null) {
            event.editMessage("That code expired. Run `/verify` in game again.").setComponents().queue();
            return;
        }
        service.consume(code);
        String tag = event.getUser().getName();
        service.link(entry.uuid(), event.getUser().getId(), tag);
        event.editMessage("✅ Linked **" + entry.name() + "** to " + event.getUser().getAsMention() + ".")
                .setComponents().queue();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(entry.uuid());
            if (player != null) plugin.msg().send(player, "verify-linked", "{discord}", tag);
        });
    }

    private void unverify(SlashCommandInteractionEvent event) {
        var account = event.getOption("account");
        UUID target;
        if (account == null) {
            target = service.uuidOfDiscord(event.getUser().getId());
        } else {
            if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
                event.reply("Only server admins can unlink other accounts.").setEphemeral(true).queue();
                return;
            }
            String value = account.getAsString().trim();
            String digits = value.replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && digits.length() >= 15) {
                target = service.uuidOfDiscord(digits);
            } else {
                PlayerStats stats = plugin.storage().loadStatsByName(value);
                target = stats == null ? null : stats.uuid;
            }
        }
        if (target == null || !service.unlink(target)) {
            event.reply("No linked account found.").setEphemeral(true).queue();
            return;
        }
        event.reply("🔓 Unlinked.").setEphemeral(true).queue();
    }

    private UUID linkedOrReply(SlashCommandInteractionEvent event) {
        UUID uuid = service.uuidOfDiscord(event.getUser().getId());
        if (uuid == null) {
            event.reply("Your account is not linked. Run `/verify` in game, then `/verify <code>` here.")
                    .setEphemeral(true).queue();
        }
        return uuid;
    }

    private void stats(SlashCommandInteractionEvent event) {
        UUID uuid = linkedOrReply(event);
        if (uuid == null) return;
        PlayerStats s = plugin.storage().loadStats(uuid, null);
        event.replyEmbeds(statsEmbed(s, nameOf(s, uuid)).build()).queue();
    }

    private void progress(SlashCommandInteractionEvent event) {
        UUID uuid = linkedOrReply(event);
        if (uuid == null) return;
        PlayerStats s = plugin.storage().loadStats(uuid, null);
        Tier next = s.tier().next();
        EmbedBuilder eb = new EmbedBuilder().setTitle(nameOf(s, uuid) + " – progress")
                .addField("Tier", s.tier().display(), true)
                .addField("Next tier", next == null ? "None" : next.display(), true);
        if (next != null) {
            eb.addField("Tier test progress", bar(s.progress) + " " + num(s.progress) + "%", false);
            eb.addField("Status", s.testReady() ? "✅ Tier test ready (" + s.chances + " chances)" : "Keep winning queue matches", false);
        }
        event.replyEmbeds(eb.build()).setEphemeral(true).queue();       // only the player sees it
    }

    private void leaderboard(SlashCommandInteractionEvent event) {
        String category = event.getOption("category").getAsString();
        List<PlayerStats> top = plugin.storage().top(category, 10);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < top.size(); i++) {
            PlayerStats s = top.get(i);
            String value = switch (category) {
                case "kills" -> s.kills + " kills";
                case "victory" -> s.wins + " wins";
                case "defeat" -> s.losses + " defeats";
                case "matchplayed" -> s.played + " matches";
                default -> s.tier().pointsText() + " pts";
            };
            sb.append("`").append(i + 1).append(".` **").append(s.name == null ? "?" : s.name).append("** – ")
                    .append(value).append(" (").append(s.tier().display()).append(")\n");
        }
        if (sb.length() == 0) sb.append("Nobody is ranked yet.");
        String title = category.equals("matchplayed") ? "Leaderboard – played matches" : "Leaderboard – " + category;
        event.replyEmbeds(new EmbedBuilder().setTitle(title).setDescription(sb.toString()).build()).queue();
    }

    private void tier(SlashCommandInteractionEvent event) {
        String type = event.getOption("type").getAsString();
        String target = event.getOption("target").getAsString().trim();
        PlayerStats stats;
        if (type.equals("dc")) {
            String digits = target.replaceAll("[^0-9]", "");
            UUID uuid = digits.isEmpty() ? null : service.uuidOfDiscord(digits);
            if (uuid == null) {
                event.reply("That Discord user has not linked a Minecraft account.").setEphemeral(true).queue();
                return;
            }
            stats = plugin.storage().loadStats(uuid, null);
        } else {
            stats = plugin.storage().loadStatsByName(target);
            if (stats == null) {
                event.reply("No ranked player named **" + target + "**.").setEphemeral(true).queue();
                return;
            }
        }
        Tier tier = stats.tier();
        Tier next = tier.next();
        EmbedBuilder eb = new EmbedBuilder().setTitle(nameOf(stats, stats.uuid) + " – " + tier.display())
                .addField("Points", tier.pointsText(), true)
                .addField("Next tier", next == null ? "None" : next.display(), true);
        if (next != null) eb.addField("Progress", bar(stats.progress) + " " + num(stats.progress) + "%", false);
        event.replyEmbeds(eb.build()).queue();
    }
}
