package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Base for every plugin menu. The listener routes events to the menu that owns the inventory. */
public abstract class Menu implements InventoryHolder {
    protected final VPerPlayerKit plugin;
    protected Inventory inv;

    protected Menu(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    protected void create(int size, Component title) {
        this.inv = Bukkit.createInventory(this, size, title);
    }

    protected void fillGlass() {
        ItemStack glass = plugin.menus().glass();
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, glass.clone());
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public void open(Player player) {
        player.openInventory(inv);
    }

    /** Called for every click while this menu is the open top inventory. Default: locked. */
    public void click(InventoryClickEvent event) {
        event.setCancelled(true);
    }

    public void drag(InventoryDragEvent event) {
        for (int raw : event.getRawSlots()) {
            if (raw < inv.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    public void close(InventoryCloseEvent event) {
        // nothing by default
    }
}
