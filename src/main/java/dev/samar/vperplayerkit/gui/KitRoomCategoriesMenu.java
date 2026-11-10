package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The Kitroom main screen: one icon per category on an otherwise empty 6 row menu. */
public final class KitRoomCategoriesMenu extends Menu {
    private static final List<Integer> DEFAULT_SLOTS = List.of(11, 13, 15, 29, 31, 33);

    private final Map<Integer, String> slotToCategory = new HashMap<>();

    private KitRoomCategoriesMenu(VPerPlayerKit plugin) {
        super(plugin);
        create(54, plugin.menus().title("kit-room.title-normal"));

        List<Integer> configured = plugin.menus().integers("kit-room.category-slots");
        List<Integer> slots = configured.isEmpty() ? DEFAULT_SLOTS : configured;
        List<String> categories = KitRoomMenu.categories(plugin);
        for (int i = 0; i < categories.size() && i < slots.size(); i++) {
            int slot = slots.get(i);
            if (slot < 0 || slot >= inv.getSize()) continue;
            String key = categories.get(i);
            slotToCategory.put(slot, key);
            inv.setItem(slot, plugin.menus().item("kit-room.categories." + key));
        }
    }

    public static void open(VPerPlayerKit plugin, Player player) {
        new KitRoomCategoriesMenu(plugin).open(player);
    }

    @Override
    public void click(InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inv.getSize()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String category = slotToCategory.get(raw);
        if (category != null) KitRoomMenu.open(plugin, player, category, false);
    }
}
