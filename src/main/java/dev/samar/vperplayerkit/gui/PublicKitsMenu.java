package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.kit.KitService;
import dev.samar.vperplayerkit.util.ItemUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lists public kits. The icon is the first hotbar item of the kit. */
public final class PublicKitsMenu extends Menu {
    private static final int MAX_SHOWN = 45;
    private static final int BACK = 49;

    private final Map<Integer, String> slotToName = new HashMap<>();

    private PublicKitsMenu(VPerPlayerKit plugin, Map<String, ItemStack[]> kits) {
        super(plugin);
        create(54, plugin.menus().title("public-kits.title"));
        ItemStack glass = plugin.menus().glass();
        for (int i = 45; i < 54; i++) inv.setItem(i, glass.clone());
        inv.setItem(BACK, plugin.menus().item("public-kits.back"));

        List<String> names = new ArrayList<>(kits.keySet());
        Collections.sort(names);
        int slot = 0;
        for (String name : names) {
            if (slot >= MAX_SHOWN) break;
            ItemStack label = plugin.menus().item("public-kits.kit", "{name}", name);
            ItemStack icon = new ItemStack(iconMaterial(kits.get(name)));
            slotToName.put(slot, name);
            inv.setItem(slot, ItemUtil.relabel(icon, label));
            slot++;
        }
    }

    private static Material iconMaterial(ItemStack[] items) {
        if (items != null) {
            for (int i = 0; i < 9 && i < items.length; i++) {
                if (!ItemUtil.isEmpty(items[i])) return items[i].getType();
            }
            for (ItemStack item : items) {
                if (!ItemUtil.isEmpty(item)) return item.getType();
            }
        }
        return Material.BOOK;
    }

    public static void open(VPerPlayerKit plugin, Player player) {
        plugin.async(() -> plugin.storage().all(KitService.GLOBAL, "public"), raw -> {
            if (!player.isOnline()) return;
            Map<String, ItemStack[]> kits = new HashMap<>();
            for (Map.Entry<String, byte[]> entry : raw.entrySet()) {
                kits.put(entry.getKey(), ItemUtil.fromBytes(entry.getValue()));
            }
            new PublicKitsMenu(plugin, kits).open(player);
        });
    }

    @Override
    public void click(InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inv.getSize()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (raw == BACK) {
            KitMenu.open(plugin, player, null);
            return;
        }
        String name = slotToName.get(raw);
        if (name != null) {
            player.closeInventory();
            plugin.kits().loadPublic(player, name);
        }
    }
}
