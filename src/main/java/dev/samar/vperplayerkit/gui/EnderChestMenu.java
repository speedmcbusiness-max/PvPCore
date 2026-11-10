package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.ItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/** Enderchest editor for one kit: 27 editable slots plus a button row. */
public final class EnderChestMenu extends Menu {
    private static final int BACK = 27;
    private static final int IMPORT = 29;
    private static final int SAVE = 31;
    private static final int RESET = 33;

    private final int kitId;

    private EnderChestMenu(VPerPlayerKit plugin, int kitId, ItemStack[] data) {
        super(plugin);
        this.kitId = kitId;
        create(36, plugin.menus().title("enderchest.title", "{n}", String.valueOf(kitId)));
        ItemStack glass = plugin.menus().glass();
        for (int i = 27; i < 36; i++) inv.setItem(i, glass.clone());
        inv.setItem(BACK, plugin.menus().item("enderchest.back"));
        inv.setItem(IMPORT, plugin.menus().item("enderchest.import"));
        inv.setItem(SAVE, plugin.menus().item("enderchest.save"));
        inv.setItem(RESET, plugin.menus().item("enderchest.reset"));
        for (int i = 0; i < 27; i++) {
            if (data != null && i < data.length && data[i] != null) inv.setItem(i, data[i].clone());
        }
    }

    public static void open(VPerPlayerKit plugin, Player player, int kitId) {
        String owner = player.getUniqueId().toString();
        plugin.async(() -> plugin.storage().get(owner, "echest", String.valueOf(kitId)), data -> {
            if (!player.isOnline()) return;
            new EnderChestMenu(plugin, kitId, ItemUtil.fromBytes(data)).open(player);
        });
    }

    private ItemStack[] read() {
        ItemStack[] items = new ItemStack[27];
        for (int i = 0; i < 27; i++) {
            ItemStack item = inv.getItem(i);
            items[i] = ItemUtil.isEmpty(item) ? null : item.clone();
        }
        return items;
    }

    @Override
    public void click(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inv.getSize() || raw < 27) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        switch (raw) {
            case BACK -> KitMenu.open(plugin, player, null);
            case IMPORT -> {
                ItemStack[] ender = player.getEnderChest().getContents();
                for (int i = 0; i < 27; i++) {
                    ItemStack item = i < ender.length ? ender[i] : null;
                    inv.setItem(i, ItemUtil.isEmpty(item) ? null : item.clone());
                }
            }
            case SAVE -> plugin.kits().saveEnderChest(player, kitId, read());
            case RESET -> {
                for (int i = 0; i < 27; i++) inv.setItem(i, null);
            }
            default -> { }
        }
    }

    @Override
    public void drag(InventoryDragEvent event) {
        for (int raw : event.getRawSlots()) {
            if (raw >= 27 && raw < inv.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
