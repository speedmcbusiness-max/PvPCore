package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.moderation.ReportManager;
import dev.samar.vperplayerkit.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /sus: a large chest with the head, name and reasons of every reported player. All text/materials are in menus.yml ("sus"). */
public final class SusMenu extends Menu {
    private static final int PAGE_SIZE = 45;
    private static final int PREV = 45;
    private static final int REFRESH = 49;
    private static final int NEXT = 53;

    private record Group(UUID target, String name, List<ReportManager.Report> reports) {
    }

    private final List<Group> groups;
    private final int page;
    private final int pages;
    private final Map<Integer, Group> slots = new HashMap<>();

    private SusMenu(VPerPlayerKit plugin, List<Group> groups, int requestedPage) {
        super(plugin);
        this.groups = groups;
        this.pages = Math.max(1, (groups.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        this.page = Math.max(0, Math.min(requestedPage, pages - 1));
        create(54, plugin.menus().title("sus.title", "{page}", String.valueOf(page + 1), "{pages}", String.valueOf(pages)));
        render();
    }

    public static void open(VPerPlayerKit plugin, Player staff, int page) {
        plugin.reports().loadAll(reports -> {
            if (!staff.isOnline()) return;
            Map<UUID, Group> byTarget = new LinkedHashMap<>();       // newest reported player first
            for (ReportManager.Report r : reports) {
                byTarget.computeIfAbsent(r.target(), k -> new Group(r.target(), r.targetName(), new ArrayList<>()))
                        .reports().add(r);
            }
            new SusMenu(plugin, new ArrayList<>(byTarget.values()), page).open(staff);
        });
    }

    private void render() {
        inv.clear();
        slots.clear();
        if (groups.isEmpty()) inv.setItem(22, plugin.menus().item("sus.empty"));
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= groups.size()) break;
            Group g = groups.get(index);
            slots.put(i, g);
            inv.setItem(i, entry(g));
        }
        if (page > 0) inv.setItem(PREV, plugin.menus().item("sus.prev"));
        inv.setItem(REFRESH, plugin.menus().item("sus.refresh"));
        if (page < pages - 1) inv.setItem(NEXT, plugin.menus().item("sus.next"));
    }

    private ItemStack entry(Group g) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(g.target()));

        ReportManager.Report last = g.reports().get(0);
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (ReportManager.Report r : g.reports()) reasons.merge(r.reason(), 1, Integer::sum);

        String[] kv = {"{player}", g.name(), "{count}", String.valueOf(g.reports().size()),
                "{last-reporter}", last.reporterName(),
                "{ago}", Text.agoShort(System.currentTimeMillis() - last.time())};
        meta.displayName(Text.item(Text.replace(plugin.menus().string("sus.entry.name", "<red>{player}"), kv)));

        List<Component> lore = new ArrayList<>();
        int maxLines = Math.max(1, plugin.menus().integer("sus.max-reason-lines", 6));
        for (String line : plugin.menus().list("sus.entry.lore")) {
            if (line.equals("{reasons}")) {                              // one line per reason
                int shown = 0;
                for (Map.Entry<String, Integer> e : reasons.entrySet()) {
                    if (shown++ >= maxLines) break;
                    lore.add(Text.item(Text.replace(plugin.menus().string("sus.reason-line", "<dark_gray>- <white>{reason}"),
                            "{reason}", e.getKey(), "{count}", String.valueOf(e.getValue()))));
                }
            } else {
                lore.add(Text.item(Text.replace(line, kv)));
            }
        }
        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    @Override
    public void click(InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inv.getSize()) return;
        if (!(event.getWhoClicked() instanceof Player staff)) return;

        if (raw == PREV && page > 0) {
            open(plugin, staff, page - 1);
        } else if (raw == NEXT && page < pages - 1) {
            open(plugin, staff, page + 1);
        } else if (raw == REFRESH) {
            open(plugin, staff, page);
        } else if (slots.containsKey(raw)) {
            Group g = slots.get(raw);
            Player target = Bukkit.getPlayer(g.target());
            if (event.getClick().isRightClick()) {
                plugin.reports().clearTarget(g.target(), () -> {
                    plugin.msg().send(staff, "sus-cleared", "{player}", g.name());
                    open(plugin, staff, page);
                });
            } else if (target == null) {
                plugin.msg().send(staff, "sus-offline", "{player}", g.name());
            } else if (event.getClick().isShiftClick()) {
                if (staff.hasPermission("perplayerkit.ss")) plugin.freeze().toggleScreenshare(staff, target);
                else plugin.msg().send(staff, "no-permission");
            } else {
                staff.closeInventory();
                staff.teleport(target.getLocation());
                plugin.msg().send(staff, "sus-teleported", "{player}", g.name());
            }
        }
    }
}
