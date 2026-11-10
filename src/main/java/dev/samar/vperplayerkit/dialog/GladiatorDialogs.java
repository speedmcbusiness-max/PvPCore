package dev.samar.vperplayerkit.dialog;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.gladiator.GladiatorMatch;
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
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Gladiator dialogs. All text comes from dialogs.yml (section "gladiator"). */
public final class GladiatorDialogs {
    private static final ClickCallback.Options OPTIONS = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(10))
            .build();
    private static final int OPPONENTS_PER_PAGE = 6;

    private final VPerPlayerKit plugin;

    public GladiatorDialogs(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private DialogText t() {
        return plugin.dialogText();
    }

    // ------------------------------------------------------------------ helpers

    private ActionButton button(String label, String tooltip, BiConsumer<Player, DialogResponseView> action) {
        DialogAction click = DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player player)) return;
            if (Bukkit.isPrimaryThread()) {
                action.accept(player, view);
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> action.accept(player, view));
            }
        }, OPTIONS);
        return ActionButton.create(Text.mm(label), tooltip == null ? null : Text.mm(tooltip),
                t().integer("common.button-width", 150), click);
    }

    private ActionButton closeButton() {
        return ActionButton.create(Text.mm(t().get("common.close")), null, t().integer("common.button-width", 150), null);
    }

    private ActionButton backButton(String tooltip, BiConsumer<Player, DialogResponseView> action) {
        return button(t().get("common.back"), tooltip, action);
    }

    private Dialog dialog(String title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
        DialogBase base = DialogBase.builder(Text.mm(title)).body(body).inputs(inputs).build();
        return Dialog.create(builder -> builder.empty().base(base).type(type));
    }

    private DialogBody text(String mini) {
        return DialogBody.plainMessage(Text.mm(mini), t().integer("common.body-width", 300));
    }

    /** Player head on the left, text on the right. */
    private DialogBody headLine(UUID uuid, String line) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(player);
        item.setItemMeta(meta);
        return DialogBody.item(item, DialogBody.plainMessage(Text.mm(line), 220), false, false, 16, 16);
    }

    private void show(Player player, Dialog dialog) {
        if (player.isOnline()) player.showDialog(dialog);
    }

    private GladiatorMatch hostMatch(Player player) {
        GladiatorMatch m = plugin.gladiator().matchOf(player.getUniqueId());
        if (m == null || !m.host.equals(player.getUniqueId())) {
            plugin.msg().send(player, "gladiator-none");
            return null;
        }
        return m;
    }

    // ------------------------------------------------------------------ Gladiator (main)

    /** Remembers the Biome/Kit inputs whenever a button of the main dialog is pressed. */
    private void saveChoices(GladiatorMatch m, DialogResponseView view) {
        if (m.state != GladiatorMatch.State.SETUP) return;
        String biome = view.getText("biome");
        if (biome != null) m.biome = biome;
        String kit = view.getText("kit");
        if (kit != null) m.kitSpec = kit;
    }

    public void openMain(Player host) {
        GladiatorMatch m = hostMatch(host);
        if (m == null) return;
        String owner = host.getUniqueId().toString();
        plugin.async(() -> new Object[] {
                plugin.storage().ids(owner, "kit"),
                plugin.storage().get(owner, "selected", "main")
        }, raw -> {
            if (host.isOnline()) buildMain(host, m, raw);
        });
    }

    private void buildMain(Player host, GladiatorMatch m, Object[] raw) {
        boolean setup = m.state == GladiatorMatch.State.SETUP;
        String p = "gladiator.main.";

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

        List<DialogBody> body = new ArrayList<>();
        body.add(text(t().get(p + "teams", "{a}", String.valueOf(m.teamA.size()), "{sizeA}", String.valueOf(m.sizeA),
                "{b}", String.valueOf(m.teamB.size()), "{sizeB}", String.valueOf(m.sizeB))));
        body.add(text(t().get(p + "mode-line", "{mode}", t().get(m.sizeA == 1 ? p + "solo" : p + "team"),
                "{status}", t().get(setup ? p + "status-setup" : p + "status-open"))));

        List<DialogInput> inputs = new ArrayList<>();
        if (setup) {
            body.add(text(t().get(p + "kit-note")));

            List<SingleOptionDialogInput.OptionEntry> biomes = new ArrayList<>();
            biomes.add(SingleOptionDialogInput.OptionEntry.create("auto", Text.mm(t().get(p + "auto")), m.biome.equals("auto")));
            {
                for (String key : plugin.locator().biomes()) {
                    String id = key.toLowerCase();
                    String custom = t().opt("join.biome-names." + key);
                    biomes.add(SingleOptionDialogInput.OptionEntry.create(id,
                            Text.mm(custom != null ? custom : Text.pretty(key)), m.biome.equals(id)));
                }
            }
            inputs.add(DialogInput.singleOption("biome", Text.mm(t().get(p + "biome-label")), biomes).build());

            if (kits.isEmpty()) {
                body.add(text(t().get(p + "no-kits")));
            } else {
                List<String> ids = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                if (selected != null) {
                    ids.add("selected");
                    labels.add(t().get(p + "selected-kit", "{n}", String.valueOf(selected)));
                }
                for (int kit : kits) {
                    ids.add("kit:" + kit);
                    labels.add(t().get(p + "kit", "{n}", String.valueOf(kit)));
                }
                String initial = ids.contains(m.kitSpec) ? m.kitSpec : ids.get(0);
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                for (int i = 0; i < ids.size(); i++) {
                    entries.add(SingleOptionDialogInput.OptionEntry.create(ids.get(i), Text.mm(labels.get(i)),
                            ids.get(i).equals(initial)));
                }
                inputs.add(DialogInput.singleOption("kit", Text.mm(t().get(p + "kit-label")), entries).build());
            }
        } else {
            String kitText = m.kitSpec.startsWith("kit:")
                    ? t().get(p + "kit", "{n}", m.kitSpec.substring(4))
                    : t().get(p + "selected-kit", "{n}", "?");
            body.add(text(t().get(p + "locked-info", "{biome}", Text.pretty(m.biome), "{kit}", kitText)));
        }

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(t().get(p + "your-team.label"), t().opt(p + "your-team.tooltip"), (pl, v) -> {
            saveChoices(m, v);
            openTeam(pl);
        }));
        buttons.add(button(t().get(p + "opponent.label"), t().opt(p + "opponent.tooltip"), (pl, v) -> {
            saveChoices(m, v);
            openOpponent(pl, 0);
        }));
        buttons.add(button(t().get(p + "settings.label"), t().opt(p + "settings.tooltip"), (pl, v) -> {
            saveChoices(m, v);
            openSettings(pl);
        }));
        if (setup) {
            buttons.add(button(t().get(p + "create.label"), t().opt(p + "create.tooltip"), (pl, v) -> {
                saveChoices(m, v);
                plugin.gladiator().publish(pl);
            }));
        } else {
            buttons.add(button(t().get(p + "disband.label"), t().opt(p + "disband.tooltip"),
                    (pl, v) -> plugin.gladiator().disband(pl)));
        }
        show(host, dialog(t().title(p + "title"), body, inputs, DialogType.multiAction(buttons, closeButton(), 1)));
    }

    // ------------------------------------------------------------------ YourTeam

    public void openTeam(Player host) {
        GladiatorMatch m = hostMatch(host);
        if (m == null) return;
        String p = "gladiator.team.";

        List<DialogBody> body = new ArrayList<>();
        for (int i = 0; i < m.sizeA; i++) {
            if (i < m.teamA.size()) {
                UUID id = m.teamA.get(i);
                body.add(headLine(id, t().get(p + "teammate", "{n}", String.valueOf(i + 1), "{name}", m.name(id),
                        "{host}", id.equals(m.host) ? t().get(p + "host-marker") : "")));
            } else {
                body.add(text(t().get(p + "empty", "{n}", String.valueOf(i + 1))));
            }
        }
        if (!m.invited.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (UUID id : m.invited) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                names.add(op.getName() == null ? "?" : op.getName());
            }
            body.add(text(t().get(p + "invited", "{names}", String.join(", ", names))));
        }

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(t().get(p + "add-friend.label"), t().opt(p + "add-friend.tooltip"), (pl, v) -> openAddFriend(pl)));
        buttons.add(backButton(null, (pl, v) -> openMain(pl)));
        show(host, dialog(t().title(p + "title"), body, List.of(), DialogType.multiAction(buttons, closeButton(), 1)));
    }

    public void openAddFriend(Player host) {
        GladiatorMatch m = hostMatch(host);
        if (m == null) return;
        String p = "gladiator.add-friend.";
        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("name", 200, Text.mm(t().get(p + "input-label")), true, "", 16, null));
        List<DialogBody> body = List.of(text(t().get(p + "body")));

        ActionButton cancel = button(t().get(p + "cancel"), null, (pl, v) -> openTeam(pl));
        ActionButton add = button(t().get(p + "add.label"), t().opt(p + "add.tooltip"), (pl, v) -> {
            plugin.gladiator().invite(pl, v.getText("name"));
            openTeam(pl);
        });
        show(host, dialog(t().title(p + "title"), body, inputs, DialogType.multiAction(List.of(cancel, add), null, 2)));
    }

    // ------------------------------------------------------------------ OpponentTeam

    public void openOpponent(Player viewer, int page) {
        GladiatorMatch m = plugin.gladiator().matchOf(viewer.getUniqueId());
        if (m == null) {
            plugin.msg().send(viewer, "gladiator-none");
            return;
        }
        String p = "gladiator.opponent.";
        int total = m.teamB.size();
        int pages = Math.max(1, (total + OPPONENTS_PER_PAGE - 1) / OPPONENTS_PER_PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));

        List<DialogBody> body = new ArrayList<>();
        if (total == 0) body.add(text(t().get(p + "none", "{size}", String.valueOf(m.sizeB))));
        for (int i = current * OPPONENTS_PER_PAGE; i < Math.min(total, (current + 1) * OPPONENTS_PER_PAGE); i++) {
            UUID id = m.teamB.get(i);
            body.add(headLine(id, t().get(p + "line", "{n}", String.valueOf(i + 1), "{name}", m.name(id))));
        }
        body.add(text(t().get(p + "page", "{page}", String.valueOf(current + 1), "{pages}", String.valueOf(pages),
                "{joined}", String.valueOf(total), "{size}", String.valueOf(m.sizeB))));

        List<ActionButton> buttons = new ArrayList<>();
        if (pages > 1) {
            int next = (current + 1) % pages;
            buttons.add(button(t().get(p + "more.label"), t().opt(p + "more.tooltip"), (pl, v) -> openOpponent(pl, next)));
        }
        buttons.add(backButton(null, (pl, v) -> {
            if (m.host.equals(pl.getUniqueId())) openMain(pl);
        }));
        show(viewer, dialog(t().title(p + "title"), body, List.of(), DialogType.multiAction(buttons, closeButton(), 2)));
    }

    // ------------------------------------------------------------------ Settings

    public void openSettings(Player host) {
        GladiatorMatch m = hostMatch(host);
        if (m == null) return;
        String p = "gladiator.settings.";
        List<DialogBody> body = new ArrayList<>();
        if (m.state != GladiatorMatch.State.SETUP) body.add(text(t().get(p + "locked")));

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(t().get(p + "your-team.label"), t().opt(p + "your-team.tooltip"), (pl, v) -> openTeamSettings(pl, 0)));
        buttons.add(button(t().get(p + "opponent.label"), t().opt(p + "opponent.tooltip"), (pl, v) -> openTeamSettings(pl, 1)));
        ActionButton back = backButton(null, (pl, v) -> openMain(pl));
        show(host, dialog(t().title(p + "title"), body, List.of(), DialogType.multiAction(buttons, back, 1)));
    }

    public void openTeamSettings(Player host, int team) {
        GladiatorMatch m = hostMatch(host);
        if (m == null) return;
        if (m.state != GladiatorMatch.State.SETUP) {
            plugin.msg().send(host, "gladiator-settings-locked");
            return;
        }
        String p = "gladiator.team-settings.";
        boolean mine = team == 0;

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.numberRange("size", Text.mm(t().get(mine ? p + "size-your" : p + "size-opponent")),
                        mine ? 1f : 2f, mine ? (float) GladiatorMatch.MAX_A : (float) GladiatorMatch.MAX_B)
                .initial((float) (mine ? m.sizeA : m.sizeB)).step(1f).build());

        GladiatorMatch.Blast blast = m.blast(team);
        inputs.add(DialogInput.singleOption("blast", Text.mm(t().get(p + "blast-label")), List.of(
                SingleOptionDialogInput.OptionEntry.create("single", Text.mm(t().get(p + "single")), blast == GladiatorMatch.Blast.SINGLE),
                SingleOptionDialogInput.OptionEntry.create("double", Text.mm(t().get(p + "double")), blast == GladiatorMatch.Blast.DOUBLE)
        )).build());

        boolean elytra = m.elytra(team);
        inputs.add(DialogInput.singleOption("elytra", Text.mm(t().get(p + "elytra-label")), List.of(
                SingleOptionDialogInput.OptionEntry.create("no", Text.mm(t().get(p + "no")), !elytra),
                SingleOptionDialogInput.OptionEntry.create("yes", Text.mm(t().get(p + "yes")), elytra)
        )).build());

        List<DialogBody> body = List.of(text(t().get(p + "note")));
        ActionButton back = backButton(t().opt(p + "back-tooltip"), (pl, v) -> {
            save(pl, m, team, v);
            openSettings(pl);
        });
        show(host, dialog(t().title(mine ? p + "title-your" : p + "title-opponent"), body, inputs,
                DialogType.confirmation(back, closeButton())));
    }

    private void save(Player host, GladiatorMatch m, int team, DialogResponseView view) {
        if (m.state != GladiatorMatch.State.SETUP) {
            plugin.msg().send(host, "gladiator-settings-locked");
            return;
        }
        Float raw = view.getFloat("size");
        if (raw != null) {
            int size = Math.round(raw);
            if (team == 0) {
                m.sizeA = Math.max(Math.max(1, m.teamA.size()), Math.min(GladiatorMatch.MAX_A, size));
            } else {
                m.sizeB = Math.max(Math.max(2, m.teamB.size()), Math.min(GladiatorMatch.MAX_B, size));
            }
        }
        GladiatorMatch.Blast blast = "double".equals(view.getText("blast"))
                ? GladiatorMatch.Blast.DOUBLE : GladiatorMatch.Blast.SINGLE;
        boolean elytra = "yes".equals(view.getText("elytra"));
        if (team == 0) {
            m.blastA = blast;
            m.elytraA = elytra;
        } else {
            m.blastB = blast;
            m.elytraB = elytra;
        }
    }

    // ------------------------------------------------------------------ /gladiator join (no name)

    public void openMatchList(Player player) {
        String p = "gladiator.match-list.";
        List<GladiatorMatch> matches = plugin.gladiator().matchesFor(player);
        List<DialogBody> body = new ArrayList<>();
        if (matches.isEmpty()) {
            body.add(text(t().get(p + "empty")));
            show(player, dialog(t().title(p + "title"), body, List.of(), DialogType.notice(closeButton())));
            return;
        }
        body.add(text(t().get(p + "body")));
        List<ActionButton> buttons = new ArrayList<>();
        for (GladiatorMatch m : matches) {
            boolean invited = m.invited.contains(player.getUniqueId());
            String label = t().get(p + "button", "{host}", m.hostName, "{a}", String.valueOf(m.sizeA),
                    "{b}", String.valueOf(m.sizeB));
            String tip = invited ? t().get(p + "tip-invited")
                    : t().get(p + "tip-open", "{joined}", String.valueOf(m.teamB.size()), "{size}", String.valueOf(m.sizeB));
            buttons.add(button(label, tip, (pl, v) -> plugin.gladiator().join(pl, m.hostName)));
        }
        show(player, dialog(t().title(p + "title"), body, List.of(), DialogType.multiAction(buttons, closeButton(), 1)));
    }
}
