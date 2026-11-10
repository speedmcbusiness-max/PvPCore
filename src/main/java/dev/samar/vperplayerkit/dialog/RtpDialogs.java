package dev.samar.vperplayerkit.dialog;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.gui.KitMenu;
import dev.samar.vperplayerkit.rtpq.DuelManager;
import dev.samar.vperplayerkit.rtpq.HistoryRow;
import dev.samar.vperplayerkit.rtpq.PlayerStats;
import dev.samar.vperplayerkit.rtpq.QueueManager;
import dev.samar.vperplayerkit.rtpq.Tier;
import dev.samar.vperplayerkit.util.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.function.BiConsumer;

/**
 * RTP queue dialogs (Paper dialog API, needs Paper 1.21.7+): RTP-Queue, Join Queue, State, Leaderboard,
 * Match History and Tier Info. All text comes from dialogs.yml.
 */
public final class RtpDialogs {
    private static final ClickCallback.Options OPTIONS = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private static final String[] CATEGORIES = {"overall", "kills", "victory", "defeat", "matchplayed"};

    private final VPerPlayerKit plugin;

    public RtpDialogs(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private DialogText t() {
        return plugin.dialogText();
    }

    // ------------------------------------------------------------------ helpers

    /** Returns the normalized leaderboard category or null. */
    public static String normalizeCategory(String raw) {
        if (raw == null) return null;
        String clean = raw.toLowerCase().replace("-", "").replace("_", "");
        return switch (clean) {
            case "overall", "tier" -> "overall";
            case "kills", "kill" -> "kills";
            case "victory", "wins", "win" -> "victory";
            case "defeat", "losses", "loss" -> "defeat";
            case "matchplayed", "played", "matches", "playedmatch", "playedmatches" -> "matchplayed";
            default -> null;
        };
    }

    private ActionButton button(String label, String tooltip, BiConsumer<Player, DialogResponseView> action) {
        return button(label, tooltip, t().integer("common.button-width", 150), action);
    }

    private ActionButton wideButton(String label, String tooltip, BiConsumer<Player, DialogResponseView> action) {
        return button(label, tooltip, t().integer("common.wide-button-width", 200), action);
    }

    private ActionButton button(String label, String tooltip, int width, BiConsumer<Player, DialogResponseView> action) {
        DialogAction click = DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player player)) return;
            if (Bukkit.isPrimaryThread()) {
                action.accept(player, view);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> action.accept(player, view));
            }
        }, OPTIONS);
        return ActionButton.create(Text.mm(label), tooltip == null ? null : Text.mm(tooltip), width, click);
    }

    private ActionButton closeButton() {
        return ActionButton.create(Text.mm(t().get("common.close")), null,
                t().integer("common.button-width", 150), null);   // null action just closes
    }

    private ActionButton backButton(BiConsumer<Player, DialogResponseView> action) {
        return button(t().get("common.back"), null, action);
    }

    private Dialog dialog(String title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
        DialogBase base = DialogBase.builder(Text.mm(title)).body(body).inputs(inputs).build();
        return Dialog.create(builder -> builder.empty().base(base).type(type));
    }

    private DialogBody text(String mini) {
        return DialogBody.plainMessage(Text.mm(mini), t().integer("common.body-width", 300));
    }

    private DialogBody head(OfflinePlayer player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(player);
        item.setItemMeta(meta);
        return DialogBody.item(item, null, false, false, 32, 32);
    }

    private void show(Player player, Dialog dialog) {
        if (player.isOnline()) player.showDialog(dialog);
    }

    private String categoryName(String category) {
        return t().get("leaderboard.category-names." + category);
    }

    private String value(PlayerStats s, String category) {
        return switch (category) {
            case "kills" -> String.valueOf(s.kills);
            case "victory" -> String.valueOf(s.wins);
            case "defeat" -> String.valueOf(s.losses);
            case "matchplayed" -> String.valueOf(s.played);
            default -> t().get("leaderboard.value-points", "{points}", s.tier().pointsText());
        };
    }

    // ------------------------------------------------------------------ /rtp

    /** The RTP biome picker. Buttons come from dialogs.yml (rtp.buttons), biomes from config.yml (rtp.biomes). */
    public void openRtp(Player player) {
        List<DialogBody> body = new ArrayList<>();
        for (String line : t().lines("rtp.body")) body.add(text(line));

        List<ActionButton> buttons = new ArrayList<>();
        for (String key : t().keys("rtp.buttons")) {
            buttons.add(button(t().get("rtp.buttons." + key + ".label"), t().opt("rtp.buttons." + key + ".tooltip"),
                    (p, v) -> plugin.rtp().toBiome(p, key)));
        }
        if (buttons.isEmpty()) return;
        show(player, dialog(t().title("rtp.title"), body, List.of(),
                DialogType.multiAction(buttons, closeButton(), t().integer("rtp.columns", 3))));
    }

    // ------------------------------------------------------------------ RTP-Queue (main)

    public void openMain(Player player) {
        boolean queued = plugin.queue().isQueued(player.getUniqueId());
        List<DialogBody> body = new ArrayList<>();
        body.add(text(queued ? t().get("queue.body-queued", "{count}", String.valueOf(plugin.queue().size()))
                : t().get("queue.body-idle")));

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(t().get("queue.select-kit.label"), t().opt("queue.select-kit.tooltip"),
                (p, v) -> KitMenu.openSelect(plugin, p)));
        if (queued) {
            buttons.add(button(t().get("queue.leave.label"), t().opt("queue.leave.tooltip"),
                    (p, v) -> plugin.queue().leave(p, true)));
            QueueManager.Entry entry = plugin.queue().get(player.getUniqueId());
            boolean locked = entry != null && entry.biome.equals("auto");     // random biome cannot be changed
            buttons.add(button(t().get(locked ? "queue.biome.label-locked" : "queue.biome.label"),
                    t().opt(locked ? "queue.biome.tooltip-locked" : "queue.biome.tooltip"), (p, v) -> openBiome(p)));
        } else {
            buttons.add(button(t().get("queue.join.label"), t().opt("queue.join.tooltip"), (p, v) -> openJoin(p)));
        }
        buttons.add(button(t().get("queue.state.label"), t().opt("queue.state.tooltip"), (p, v) -> openStats(p)));
        buttons.add(button(t().get("queue.leaderboard.label"), t().opt("queue.leaderboard.tooltip"),
                (p, v) -> openLeaderboardMenu(p)));
        buttons.add(button(t().get("queue.history.label"), t().opt("queue.history.tooltip"), (p, v) -> openHistory(p)));
        buttons.add(button(t().get("queue.tier-info.label"), t().opt("queue.tier-info.tooltip"), (p, v) -> openTierInfo(p)));

        show(player, dialog(t().title("queue.title"), body, List.of(),
                DialogType.multiAction(buttons, closeButton(), t().integer("queue.columns", 2))));
    }

    // ------------------------------------------------------------------ change the biome while queued

    public void openBiome(Player player) {
        QueueManager.Entry entry = plugin.queue().get(player.getUniqueId());
        if (entry == null) {
            openMain(player);
            return;
        }
        if (entry.biome.equals("auto")) {
            plugin.msg().send(player, "queue-biome-locked");
            openMain(player);
            return;
        }
        List<SingleOptionDialogInput.OptionEntry> biomes = new ArrayList<>();
        for (String key : plugin.locator().biomes()) {
            String custom = t().opt("join.biome-names." + key);
            biomes.add(SingleOptionDialogInput.OptionEntry.create(key, Text.mm(custom != null ? custom : Text.pretty(key)),
                    key.equals(entry.biome)));
        }
        biomes.add(SingleOptionDialogInput.OptionEntry.create("auto", Text.mm(t().get("join.auto")), false));

        List<DialogBody> body = new ArrayList<>();
        body.add(text(t().get("biome-change.body", "{biome}", Text.pretty(entry.biome))));
        body.add(text(t().get("biome-change.warning")));
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.singleOption("biome", Text.mm(t().get("biome-change.label")), biomes).build());

        ActionButton cancel = button(t().get("biome-change.cancel"), null, (p, v) -> openMain(p));
        ActionButton apply = button(t().get("biome-change.apply.label"), t().opt("biome-change.apply.tooltip"), (p, v) -> {
            plugin.queue().changeBiome(p, v.getText("biome"));
            openMain(p);
        });
        show(player, dialog(t().title("biome-change.title"), body, inputs,
                DialogType.multiAction(List.of(cancel, apply), null, 2)));
    }

    // ------------------------------------------------------------------ Join Queue

    public void openJoin(Player player) {
        if (plugin.queue().isQueued(player.getUniqueId())) {
            openMain(player);
            return;
        }
        String owner = player.getUniqueId().toString();
        plugin.async(() -> new Object[] {
                plugin.storage().ids(owner, "kit"),
                plugin.storage().get(owner, "selected", "main"),
                plugin.storage().loadStats(player.getUniqueId(), player.getName())
        }, raw -> {
            if (!player.isOnline()) return;
            @SuppressWarnings("unchecked")
            List<String> rawKits = (List<String>) raw[0];
            List<Integer> kits = new ArrayList<>();
            for (String id : rawKits) {
                try {
                    kits.add(Integer.parseInt(id));
                } catch (NumberFormatException ignored) {
                    // stray row
                }
            }
            Collections.sort(kits);
            Integer selected = null;
            if (raw[1] != null) {
                try {
                    selected = Integer.parseInt(new String((byte[]) raw[1], StandardCharsets.UTF_8).trim());
                } catch (NumberFormatException ignored) {
                    // bad value
                }
            }
            if (selected != null && !kits.contains(selected)) selected = null;
            PlayerStats stats = (PlayerStats) raw[2];

            if (kits.isEmpty()) {
                List<DialogBody> body = List.of(text(t().get("join.no-kits")));
                show(player, dialog(t().title("join.title"), body, List.of(),
                        DialogType.multiAction(List.of(backButton((p, v) -> openMain(p))), closeButton(), 1)));
                return;
            }

            // Biome input: Auto + configured worlds
            List<SingleOptionDialogInput.OptionEntry> biomes = new ArrayList<>();
            biomes.add(SingleOptionDialogInput.OptionEntry.create("auto", Text.mm(t().get("join.auto")), true));
            {
                for (String key : plugin.locator().biomes()) {
                    String custom = t().opt("join.biome-names." + key);
                    biomes.add(SingleOptionDialogInput.OptionEntry.create(key.toLowerCase(),
                            Text.mm(custom != null ? custom : Text.pretty(key)), false));
                }
            }

            // Kit input: Selected Kit first (when there is one), then every saved kit
            List<SingleOptionDialogInput.OptionEntry> kitEntries = new ArrayList<>();
            if (selected != null) {
                kitEntries.add(SingleOptionDialogInput.OptionEntry.create("selected",
                        Text.mm(t().get("join.selected-kit", "{n}", String.valueOf(selected))), true));
            }
            for (int i = 0; i < kits.size(); i++) {
                kitEntries.add(SingleOptionDialogInput.OptionEntry.create("kit:" + kits.get(i),
                        Text.mm(t().get("join.kit", "{n}", String.valueOf(kits.get(i)))), selected == null && i == 0));
            }

            List<DialogInput> inputs = new ArrayList<>();
            inputs.add(DialogInput.singleOption("biome", Text.mm(t().get("join.biome-label")), biomes).build());
            inputs.add(DialogInput.singleOption("kit", Text.mm(t().get("join.kit-label")), kitEntries).build());

            List<DialogBody> body = new ArrayList<>();
            body.add(head(player));
            if (stats.testReady()) {
                body.add(text(t().get("join.test-notice", "{tier}", stats.tier().colored())));
            }

            ActionButton cancel = button(t().get("join.cancel"), null, (p, v) -> openMain(p));
            ActionButton join = button(t().get("join.join.label"), t().opt("join.join.tooltip"), (p, v) -> {
                String biome = v.getText("biome");
                String kit = v.getText("kit");
                plugin.queue().join(p, biome == null ? "auto" : biome, kit == null ? "selected" : kit);
            });

            show(player, dialog(t().title("join.title"), body, inputs,
                    DialogType.multiAction(List.of(cancel, join), null, 2)));
        });
    }

    // ------------------------------------------------------------------ State

    public void openStats(Player player) {
        plugin.async(() -> plugin.storage().loadStats(player.getUniqueId(), player.getName()), (PlayerStats stats) -> {
            if (!player.isOnline()) return;
            List<DialogBody> body = new ArrayList<>();
            body.add(head(player));
            body.add(text(t().get("state.line", "{player}", player.getName(), "{tier}", stats.tier().colored())));
            body.add(text(t().get("state.hint")));

            List<ActionButton> buttons = new ArrayList<>();
            String[] keys = {"kills", "victory", "defeat", "matchplayed"};
            int[] values = {stats.kills, stats.wins, stats.losses, stats.played};
            for (int i = 0; i < keys.length; i++) {
                String key = keys[i];
                buttons.add(wideButton(t().get("state." + key + ".label", "{value}", String.valueOf(values[i])),
                        t().opt("state." + key + ".tooltip"), (p, v) -> openLeaderboard(p, key)));
            }
            show(player, dialog(t().title("state.title"), body, List.of(),
                    DialogType.multiAction(buttons, closeButton(), 1)));
        });
    }

    // ------------------------------------------------------------------ Leaderboard

    public void openLeaderboardMenu(Player player) {
        List<DialogBody> body = List.of(text(t().get("leaderboard-menu.body")));
        List<ActionButton> buttons = new ArrayList<>();
        for (String key : CATEGORIES) {
            buttons.add(wideButton(t().get("leaderboard-menu." + key), null, (p, v) -> openLeaderboard(p, key)));
        }
        show(player, dialog(t().title("leaderboard-menu.title"), body, List.of(),
                DialogType.multiAction(buttons, closeButton(), 1)));
    }

    public void openLeaderboard(Player player, String category) {
        String cat = normalizeCategory(category);
        if (cat == null) {
            openLeaderboardMenu(player);
            return;
        }
        plugin.async(() -> {
            PlayerStats me = plugin.storage().loadStats(player.getUniqueId(), player.getName());
            return new Object[] {plugin.storage().top(cat, 10), me, plugin.storage().rank(cat, me)};
        }, raw -> {
            if (!player.isOnline()) return;
            @SuppressWarnings("unchecked")
            List<PlayerStats> top = (List<PlayerStats>) raw[0];
            PlayerStats me = (PlayerStats) raw[1];
            int rank = (Integer) raw[2];

            List<DialogBody> body = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String rankText = String.valueOf(i + 1);
                if (i < top.size()) {
                    PlayerStats s = top.get(i);
                    String color = t().get(i == 0 ? "leaderboard.colors.first" : (i == 1 ? "leaderboard.colors.second"
                            : (i == 2 ? "leaderboard.colors.third" : "leaderboard.colors.other")));
                    body.add(text(t().get("leaderboard.entry", "{color}", color, "{rank}", rankText, "{name}", s.name,
                            "{value}", value(s, cat), "{tier}", s.tier().colored())));
                } else {
                    body.add(text(t().get("leaderboard.empty", "{rank}", rankText)));
                }
            }
            body.add(text(t().get("leaderboard.separator")));
            body.add(text(t().get("leaderboard.own", "{rank}", rank < 0 ? "?" : String.valueOf(rank),
                    "{name}", me.name, "{value}", value(me, cat), "{tier}", me.tier().colored())));

            show(player, dialog(t().title("leaderboard.title", "{category}", categoryName(cat)), body, List.of(),
                    DialogType.multiAction(List.of(backButton((p, v) -> openLeaderboardMenu(p))), closeButton(), 1)));
        });
    }

    // ------------------------------------------------------------------ Match history

    private String ago(long time) {
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (s < 60) return t().get("history.ago.seconds", "{n}", String.valueOf(s));
        if (s < 3600) return t().get("history.ago.minutes", "{n}", String.valueOf(s / 60));
        if (s < 86400) return t().get("history.ago.hours", "{n}", String.valueOf(s / 3600));
        return t().get("history.ago.days", "{n}", String.valueOf(s / 86400));
    }

    public void openHistory(Player player) {
        plugin.async(() -> plugin.storage().history(player.getUniqueId(), 10), (List<HistoryRow> rows) -> {
            if (!player.isOnline()) return;
            SimpleDateFormat format;
            try {
                format = new SimpleDateFormat(t().get("history.date-format"));
            } catch (IllegalArgumentException ex) {
                format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            }
            format.setTimeZone(TimeZone.getTimeZone("UTC"));

            List<DialogBody> body = new ArrayList<>();
            if (rows.isEmpty()) body.add(text(t().get("history.empty")));
            for (HistoryRow row : rows) {
                String color = t().get(row.myScore() > row.oppScore() ? "history.win-color" : "history.loss-color");
                String line = t().get("history.line", "{color}", color, "{my}", String.valueOf(row.myScore()),
                        "{opp}", String.valueOf(row.oppScore()), "{mytier}", Tier.fromIndex(row.myTier()).colored(),
                        "{myname}", row.myName(), "{opptier}", Tier.fromIndex(row.oppTier()).colored(),
                        "{oppname}", row.oppName(), "{test}", row.test() ? t().get("history.test-suffix") : "");
                Component hover = Text.mm(t().get("history.hover", "{ago}", ago(row.time()),
                        "{date}", format.format(new Date(row.time()))));
                body.add(DialogBody.plainMessage(Text.mm(line).hoverEvent(HoverEvent.showText(hover)),
                        t().integer("common.body-width", 300) + 40));
            }
            show(player, dialog(t().title("history.title"), body, List.of(), DialogType.notice(closeButton())));
        });
    }

    // ------------------------------------------------------------------ Tier info

    private String bar(double percent) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, percent)) / 10.0);
        String ch = t().get("tier-info.bar.character");
        return t().get("tier-info.bar.filled-color") + ch.repeat(filled)
                + t().get("tier-info.bar.empty-color") + ch.repeat(10 - filled);
    }

    public void openTierInfo(Player player) {
        plugin.async(() -> plugin.storage().loadStats(player.getUniqueId(), player.getName()), (PlayerStats stats) -> {
            if (!player.isOnline()) return;
            Tier tier = stats.tier();
            Tier next = tier.next();

            List<DialogBody> body = new ArrayList<>();
            body.add(head(player));
            body.add(text(t().get("tier-info.player", "{player}", player.getName(), "{tier}", tier.colored())));
            if (next == null) {
                body.add(text(t().get("tier-info.max-tier")));
            } else {
                body.add(text(t().get("tier-info.test", "{bar}", bar(stats.progress),
                        "{percent}", DuelManager.fmt(stats.progress))));
                if (stats.testReady()) {
                    body.add(text(t().get("tier-info.test-ready", "{chances}", String.valueOf(stats.chances))));
                }
            }
            body.add(text(t().get("tier-info.current", "{tier}", tier.colored(), "{points}", tier.pointsText())));
            body.add(text(next == null ? t().get("tier-info.next-none")
                    : t().get("tier-info.next", "{tier}", next.colored(), "{points}", next.pointsText())));

            show(player, dialog(t().title("tier-info.title"), body, List.of(), DialogType.notice(closeButton())));
        });
    }
}
