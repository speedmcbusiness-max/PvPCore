package dev.samar.vperplayerkit.dialog;

import dev.samar.vperplayerkit.VPerPlayerKit;
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
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/** The /report dialog: pick a reason (report.reasons) or type your own, then Cancel / Confirm. Text: dialogs.yml "report". */
public final class ReportDialogs {
    private static final ClickCallback.Options OPTIONS = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private final VPerPlayerKit plugin;

    public ReportDialogs(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private DialogText t() {
        return plugin.dialogText();
    }

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

    public void open(Player reporter, UUID target, String targetName) {
        if (!reporter.isOnline()) return;
        List<String> reasons = plugin.reports().reasons();
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (int i = 0; i < reasons.size(); i++) {
            entries.add(SingleOptionDialogInput.OptionEntry.create(reasons.get(i), Text.mm(reasons.get(i)), i == 0));
        }
        int max = Math.max(8, plugin.getConfig().getInt("report.max-reason-length", 64));

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.singleOption("reason", Text.mm(t().get("report.reason-label")), entries).build());
        inputs.add(DialogInput.text("custom", 220, Text.mm(t().get("report.custom-label")), true, "", max, null));

        List<DialogBody> body = new ArrayList<>();
        for (String line : t().lines("report.body", "{player}", targetName)) {
            body.add(DialogBody.plainMessage(Text.mm(line), t().integer("common.body-width", 300)));
        }

        ActionButton cancel = ActionButton.create(Text.mm(t().get("report.cancel")), null,
                t().integer("common.button-width", 150), null);                      // no action: just closes
        ActionButton confirm = button(t().get("report.confirm.label"), t().opt("report.confirm.tooltip"), (p, v) -> {
            String custom = v.getText("custom");
            String reason = (custom != null && !custom.isBlank()) ? custom : v.getText("reason");
            plugin.reports().submit(p, target, targetName, reason == null ? "" : reason);
        });

        DialogBase base = DialogBase.builder(Text.mm(t().title("report.title", "{player}", targetName)))
                .body(body).inputs(inputs).build();
        Dialog dialog = Dialog.create(builder -> builder.empty().base(base)
                .type(DialogType.multiAction(List.of(cancel, confirm), null, 2)));
        reporter.showDialog(dialog);
    }
}
