package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.kit.KitLayout;
import dev.samar.vperplayerkit.kit.KitService;
import dev.samar.vperplayerkit.util.ItemUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/** Kit editor (personal kit) and public kit editor. Nothing is stored until Save is pressed. */
public final class EditorMenu extends Menu {
    private static final int IMPORT = 45;
    private static final int TRIM = 46;
    private static final int BANNER = 47;
    private static final int SHARE = 48;
    private static final int RESET = 51;
    private static final int SAVE = 52;
    private static final int BACK = 53;

    private final int kitId;           // personal kit number, or -1 for a public kit
    private final String publicName;   // null for personal kits

    private EditorMenu(VPerPlayerKit plugin, int kitId, String publicName, ItemStack[] data) {
        super(plugin);
        this.kitId = kitId;
        this.publicName = publicName;
        if (publicName == null) {
            create(KitLayout.EDITOR_SIZE, plugin.menus().title("editor.title-kit", "{n}", String.valueOf(kitId)));
        } else {
            create(KitLayout.EDITOR_SIZE, plugin.menus().title("editor.title-public", "{name}", publicName));
        }
        KitLayout.fill(plugin, inv, data);

        ItemStack glass = plugin.menus().glass();
        for (int slot = 45; slot < 54; slot++) inv.setItem(slot, glass.clone());
        inv.setItem(IMPORT, plugin.menus().item("editor.import"));
        inv.setItem(TRIM, plugin.menus().item("editor.trim"));
        if (isKit()) inv.setItem(BANNER, plugin.menus().item("editor.banner"));
        inv.setItem(SHARE, plugin.menus().item("editor.share"));
        inv.setItem(RESET, plugin.menus().item("editor.reset"));
        inv.setItem(SAVE, plugin.menus().item("editor.save"));
        inv.setItem(BACK, plugin.menus().item("editor.back"));
    }

    public boolean isKit() {
        return publicName == null;
    }

    public int kitId() {
        return kitId;
    }

    public static void openKit(VPerPlayerKit plugin, Player player, int kitId) {
        String owner = player.getUniqueId().toString();
        plugin.async(() -> plugin.storage().get(owner, "kit", String.valueOf(kitId)), data -> {
            if (!player.isOnline()) return;
            ItemStack[] items = ItemUtil.fromBytes(data);
            new EditorMenu(plugin, kitId, null, items == null ? new ItemStack[KitLayout.KIT_SIZE] : items).open(player);
        });
    }

    public static void openPublic(VPerPlayerKit plugin, Player player, String name) {
        plugin.async(() -> plugin.storage().get(KitService.GLOBAL, "public", name), data -> {
            if (!player.isOnline()) return;
            ItemStack[] items = ItemUtil.fromBytes(data);
            if (items == null) {
                plugin.msg().send(player, "public-missing", "{name}", name);
                return;
            }
            new EditorMenu(plugin, -1, name, items).open(player);
        });
    }

    @Override
    public void click(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inv.getSize()) return;          // outside or own inventory: normal behavior
        if (KitLayout.isEditable(raw)) return;                 // item slots: normal behavior
        event.setCancelled(true);
        if (raw < 45 || !(event.getWhoClicked() instanceof Player player)) return;

        switch (raw) {
            case IMPORT -> {
                KitLayout.fillItems(inv, KitService.capture(player));
                plugin.msg().send(player, "imported");
            }
            case TRIM -> {
                if (player.hasPermission("perplayerkit.trimmer")) new TrimMenu(plugin, this).open(player);
                else plugin.msg().send(player, "no-permission");
            }
            case BANNER -> {
                if (isKit()) new BannerMenu(plugin, this).open(player);
            }
            case SHARE -> plugin.kits().share(player, KitLayout.read(inv));
            case RESET -> KitLayout.fillItems(inv, null);
            case SAVE -> save(player);
            case BACK -> {
                if (isKit()) KitMenu.open(plugin, player, null);
                else player.closeInventory();
            }
            default -> { }
        }
    }

    private void save(Player player) {
        ItemStack[] items = KitLayout.read(inv);
        if (isKit()) {
            plugin.kits().saveKit(player, kitId, items);
        } else if (player.hasPermission("perplayerkit.kitroom.admin")) {
            plugin.kits().savePublic(player, publicName, items);
        } else {
            plugin.msg().send(player, "no-permission");
        }
    }

    @Override
    public void drag(InventoryDragEvent event) {
        for (int raw : event.getRawSlots()) {
            if (raw < inv.getSize() && !KitLayout.isEditable(raw)) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
