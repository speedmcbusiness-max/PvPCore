package dev.samar.vperplayerkit.kit;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Maps a kit (41 raw Bukkit inventory slots: 0-8 hotbar, 9-35 main, 36 boots, 37 leggings,
 * 38 chestplate, 39 helmet, 40 offhand) onto a 54 slot editor inventory:
 * rows 1-3 main inventory, row 4 hotbar, row 5 armor (helmet..boots) + offhand, row 6 buttons.
 */
public final class KitLayout {
    public static final int KIT_SIZE = 41;
    public static final int EDITOR_SIZE = 54;

    private KitLayout() {}

    public static int toEditorSlot(int kitIndex) {
        if (kitIndex <= 8) return 27 + kitIndex;
        if (kitIndex <= 35) return kitIndex - 9;
        if (kitIndex <= 39) return 75 - kitIndex;   // 36 boots -> 39, 39 helmet -> 36
        return 41;                                   // offhand
    }

    public static int toKitIndex(int editorSlot) {
        if (editorSlot <= 26) return editorSlot + 9;
        if (editorSlot <= 35) return editorSlot - 27;
        if (editorSlot <= 39) return 75 - editorSlot;
        if (editorSlot == 41) return 40;
        return -1;
    }

    public static boolean isEditable(int editorSlot) {
        return (editorSlot >= 0 && editorSlot <= 39) || editorSlot == 41;
    }

    /** Fills the item area and the decorative slots (not the bottom button row). */
    public static void fill(VPerPlayerKit plugin, Inventory inv, ItemStack[] kit) {
        ItemStack glass = plugin.menus().glass();
        for (int slot : new int[] {40, 42, 43, 44}) inv.setItem(slot, glass.clone());
        fillItems(inv, kit);
    }

    public static void fillItems(Inventory inv, ItemStack[] kit) {
        for (int i = 0; i < KIT_SIZE; i++) {
            ItemStack item = (kit != null && i < kit.length) ? kit[i] : null;
            inv.setItem(toEditorSlot(i), item == null ? null : item.clone());
        }
    }

    public static ItemStack[] read(Inventory inv) {
        ItemStack[] kit = new ItemStack[KIT_SIZE];
        for (int i = 0; i < KIT_SIZE; i++) {
            ItemStack item = inv.getItem(toEditorSlot(i));
            kit[i] = (item == null || item.getType().isAir()) ? null : item.clone();
        }
        return kit;
    }
}
