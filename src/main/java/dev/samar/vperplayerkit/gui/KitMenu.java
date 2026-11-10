package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.ItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The /kit menu. NORMAL: load / edit / enderchest. IMPORT: pick a slot for a shared kit.
 * SELECT: opened from the RTP dialog, left click selects the kit for the queue.
 * The selected kit is always shown as paper with "Selected" in the lore.
 */
public final class KitMenu extends Menu {
    public enum Mode { NORMAL, IMPORT, SELECT }

    private final Mode mode;
    private final ItemStack[] pending;
    private final Set<Integer> existing;
    private final Map<Integer, ItemStack> icons;
    private final Map<Integer, Integer> slotToKit = new HashMap<>();
    private final List<Integer> kitSlots;
    private final int kitRoomSlot;
    private final int rtpSlot;
    private final int publicSlot;
    private final int backSlot;
    private Integer selected;

    private KitMenu(VPerPlayerKit plugin, Mode mode, Set<Integer> existing, Map<Integer, ItemStack> icons,
                    Integer selected, ItemStack[] pending) {
        super(plugin);
        this.mode = mode;
        this.pending = pending;
        this.existing = existing;
        this.icons = icons;
        this.selected = selected;
        List<Integer> configured = plugin.menus().integers("kit-menu.slots");
        this.kitSlots = configured.size() >= 14 ? configured
                : List.of(10, 12, 14, 16, 29, 31, 33, 19, 21, 23, 25, 38, 40, 42);
        this.kitRoomSlot = plugin.menus().integer("kit-menu.kit-room.slot", 49);
        this.rtpSlot = plugin.menus().integer("kit-menu.rtp-queue.slot", -1);
        this.publicSlot = plugin.menus().integer("kit-menu.public-kits.slot", -1);
        this.backSlot = plugin.menus().integer("kit-menu.back.slot", 49);
        String titleKey = switch (mode) {
            case IMPORT -> "kit-menu.import-title";
            case SELECT -> "kit-menu.select-title";
            default -> "kit-menu.title";
        };
        create(54, plugin.menus().title(titleKey));
        render();
    }

    private void put(String path, int slot) {
        if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, plugin.menus().item(path));
    }

    private void render() {
        inv.clear();                       // no filler panes, empty slots stay empty
        slotToKit.clear();
        for (int n = 1; n <= plugin.maxKits(); n++) {
            int slot = kitSlots.get(n - 1);
            slotToKit.put(slot, n);
            inv.setItem(slot, kitItem(n));
        }
        switch (mode) {
            case NORMAL -> {
                put("kit-menu.kit-room", kitRoomSlot);
                put("kit-menu.rtp-queue", rtpSlot);
                put("kit-menu.public-kits", publicSlot);
            }
            case SELECT -> put("kit-menu.back", backSlot);
            case IMPORT -> put("kit-menu.cancel", backSlot);
        }
    }

    private ItemStack kitItem(int n) {
        String num = String.valueOf(n);
        if (mode == Mode.IMPORT) {
            ItemStack base = plugin.menus().item("kit-menu.kit-import", "{n}", num);
            ItemStack icon = icons.get(n);
            return icon == null ? base : ItemUtil.relabel(icon, base);
        }
        if (selected != null && selected == n) {
            return plugin.menus().item("kit-menu.kit-selected", "{n}", num);
        }
        ItemStack base = plugin.menus().item(existing.contains(n) ? "kit-menu.kit" : "kit-menu.kit-empty", "{n}", num);
        ItemStack icon = icons.get(n);
        return icon == null ? base : ItemUtil.relabel(icon, base);
    }

    public static void open(VPerPlayerKit plugin, Player player, ItemStack[] pending) {
        open(plugin, player, pending == null ? Mode.NORMAL : Mode.IMPORT, pending);
    }

    public static void openSelect(VPerPlayerKit plugin, Player player) {
        open(plugin, player, Mode.SELECT, null);
    }

    private static void open(VPerPlayerKit plugin, Player player, Mode mode, ItemStack[] pending) {
        String owner = player.getUniqueId().toString();
        plugin.async(() -> new Object[] {
                plugin.storage().ids(owner, "kit"),
                plugin.storage().all(owner, "icon"),
                plugin.storage().get(owner, "selected", "main")
        }, raw -> {
            if (!player.isOnline()) return;
            Set<Integer> existing = new HashSet<>();
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) raw[0];
            for (String id : ids) {
                try {
                    existing.add(Integer.parseInt(id));
                } catch (NumberFormatException ignored) {
                    // stray row
                }
            }
            Map<Integer, ItemStack> icons = new HashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, byte[]> iconData = (Map<String, byte[]>) raw[1];
            for (Map.Entry<String, byte[]> entry : iconData.entrySet()) {
                try {
                    ItemStack icon = ItemUtil.singleFromBytes(entry.getValue());
                    if (icon != null) icons.put(Integer.parseInt(entry.getKey()), icon);
                } catch (NumberFormatException ignored) {
                    // stray row
                }
            }
            Integer selected = null;
            if (raw[2] != null) {
                try {
                    selected = Integer.parseInt(new String((byte[]) raw[2], StandardCharsets.UTF_8).trim());
                } catch (NumberFormatException ignored) {
                    // bad value
                }
            }
            new KitMenu(plugin, mode, existing, icons, selected, pending).open(player);
        });
    }

    @Override
    public void click(InventoryClickEvent event) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= inv.getSize()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Integer kit = slotToKit.get(slot);
        if (kit != null) {
            ClickType type = event.getClick();
            switch (mode) {
                case IMPORT -> {
                    plugin.kits().saveKit(player, kit, pending);
                    plugin.msg().send(player, "share-imported", "{n}", String.valueOf(kit));
                    player.closeInventory();
                }
                case SELECT -> {
                    if (!type.isLeftClick() || type.isShiftClick()) return;
                    if (!existing.contains(kit)) {
                        plugin.msg().send(player, "kit-empty", "{n}", String.valueOf(kit));
                        return;
                    }
                    selected = kit;
                    plugin.kits().selectKit(player, kit);
                    render();
                }
                default -> {
                    if (type.isShiftClick()) {
                        EnderChestMenu.open(plugin, player, kit);
                    } else if (type.isRightClick()) {
                        EditorMenu.openKit(plugin, player, kit);
                    } else {
                        player.closeInventory();
                        plugin.kits().loadKit(player, kit);
                    }
                }
            }
            return;
        }

        switch (mode) {
            case IMPORT -> {
                if (slot == backSlot) player.closeInventory();
            }
            case SELECT -> {
                if (slot == backSlot) plugin.dialogs().openMain(player);
            }
            default -> {
                if (slot == kitRoomSlot) KitRoomCategoriesMenu.open(plugin, player);
                else if (slot == rtpSlot) plugin.dialogs().openMain(player);
                else if (slot == publicSlot) PublicKitsMenu.open(plugin, player);
            }
        }
    }
}
