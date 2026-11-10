package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.kit.KitService;
import dev.samar.vperplayerkit.util.ItemUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Kit room category: 45 item slots and a bottom row with refill, fill totem,
 * save (admin) and back. Players copy items out, admins edit the category contents.
 */
public final class KitRoomMenu extends Menu {
    private static final int ITEM_SLOTS = 45;
    private static final int REFILL = 45;
    private static final int FILL_TOTEM = 51;
    private static final int SAVE = 52;
    private static final int BACK = 53;

    private final String category;
    private final boolean admin;

    private KitRoomMenu(VPerPlayerKit plugin, List<String> categories, String category, boolean admin, ItemStack[] data) {
        super(plugin);
        this.category = category;
        this.admin = admin;
        String shown = plugin.menus().string("kit-room.categories." + category + ".display",
                dev.samar.vperplayerkit.util.Text.pretty(category));
        create(54, plugin.menus().title(admin ? "kit-room.title-admin" : "kit-room.title-category",
                "{category}", shown));

        for (int i = 0; i < ITEM_SLOTS; i++) {
            if (data != null && i < data.length && data[i] != null) inv.setItem(i, data[i].clone());
        }

        ItemStack glass = plugin.menus().glass();
        for (int i = 45; i < 54; i++) inv.setItem(i, glass.clone());
        inv.setItem(REFILL, plugin.menus().item("kit-room.refill"));
        inv.setItem(FILL_TOTEM, plugin.menus().item("kit-room.fill-totem"));
        if (admin) inv.setItem(SAVE, plugin.menus().item("kit-room.save"));
        inv.setItem(BACK, plugin.menus().item("kit-room.back"));
    }

    public static List<String> categories(VPerPlayerKit plugin) {
        return plugin.menus().keys("kit-room.categories");
    }

    /** {@code category} may be null for the first category. */
    public static void open(VPerPlayerKit plugin, Player player, String category, boolean admin) {
        List<String> categories = categories(plugin);
        if (categories.isEmpty()) return;
        String chosen = (category != null && categories.contains(category)) ? category : categories.get(0);
        plugin.async(() -> plugin.storage().get(KitService.GLOBAL, "kitroom", chosen), data -> {
            if (!player.isOnline()) return;
            new KitRoomMenu(plugin, categories, chosen, admin, ItemUtil.fromBytes(data)).open(player);
        });
    }

    private ItemStack[] readItems() {
        ItemStack[] items = new ItemStack[ITEM_SLOTS];
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack item = inv.getItem(i);
            items[i] = ItemUtil.isEmpty(item) ? null : item.clone();
        }
        return items;
    }

    @Override
    public void click(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw < 0) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Own inventory
        if (raw >= inv.getSize()) {
            if (!admin && (event.isShiftClick() || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY)) {
                event.setCancelled(true);   // would push items into the kit room
            }
            return;
        }

        // Item area
        if (raw < ITEM_SLOTS) {
            if (admin) return;              // admins edit freely
            event.setCancelled(true);
            ItemStack item = inv.getItem(raw);
            if (ItemUtil.isEmpty(item)) return;
            if (event.isShiftClick()) {
                player.getInventory().addItem(item.clone());
            } else if (ItemUtil.isEmpty(event.getCursor())) {
                player.setItemOnCursor(item.clone());
            }
            return;
        }

        // Button row
        event.setCancelled(true);
        if (raw == REFILL) {
            for (int i = 0; i < 41; i++) {
                ItemStack item = player.getInventory().getItem(i);
                if (!ItemUtil.isEmpty(item)) item.setAmount(item.getMaxStackSize());
            }
            plugin.msg().send(player, "refilled");
        } else if (raw == FILL_TOTEM) {
            for (int i = 0; i < 36; i++) {
                if (ItemUtil.isEmpty(player.getInventory().getItem(i))) {
                    player.getInventory().setItem(i, new ItemStack(Material.TOTEM_OF_UNDYING));
                }
            }
        } else if (raw == SAVE && admin) {
            plugin.kits().saveKitRoom(player, category, readItems());
        } else if (raw == BACK) {
            if (admin) player.closeInventory();
            else KitRoomCategoriesMenu.open(plugin, player);
        }
    }
}
