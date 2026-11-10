package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.kit.KitLayout;
import dev.samar.vperplayerkit.kit.KitService;
import dev.samar.vperplayerkit.util.ItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** Read-only preview of a shared kit (/kit share-code <code>) with an import button. */
public final class ImportMenu extends Menu {
    private static final int CLOSE = 45;
    private static final int IMPORT = 49;

    private final ItemStack[] kit;

    private ImportMenu(VPerPlayerKit plugin, ItemStack[] kit) {
        super(plugin);
        this.kit = kit;
        create(KitLayout.EDITOR_SIZE, plugin.menus().title("import.title"));
        KitLayout.fill(plugin, inv, kit);
        ItemStack glass = plugin.menus().glass();
        for (int i = 45; i < 54; i++) inv.setItem(i, glass.clone());
        inv.setItem(CLOSE, plugin.menus().item("import.close"));
        inv.setItem(IMPORT, plugin.menus().item("import.import"));
    }

    public static void open(VPerPlayerKit plugin, Player player, String code) {
        String clean = code.toUpperCase();
        if (!clean.matches("[A-Z0-9]{1,16}")) {
            plugin.msg().send(player, "share-invalid");
            return;
        }
        plugin.async(() -> plugin.storage().get(KitService.GLOBAL, "share", clean), data -> {
            if (!player.isOnline()) return;
            ItemStack[] items = ItemUtil.fromBytes(data);
            if (items == null) {
                plugin.msg().send(player, "share-invalid");
                return;
            }
            new ImportMenu(plugin, items).open(player);
        });
    }

    @Override
    public void click(InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (raw == CLOSE) player.closeInventory();
        else if (raw == IMPORT) KitMenu.open(plugin, player, kit);
    }
}
