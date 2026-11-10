package dev.samar.vperplayerkit.util;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Auto totem: a lethal hit moves a totem from the inventory into the offhand so the normal pop happens. */
public final class Totems {
    private Totems() {}

    public static boolean inHand(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    /** Puts a totem from the inventory into the offhand (the old offhand item takes its slot). */
    public static boolean equipFromInventory(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.TOTEM_OF_UNDYING) {
                ItemStack offhand = inv.getItemInOffHand();
                inv.setItemInOffHand(item);
                inv.setItem(i, offhand.getType().isAir() ? null : offhand);
                return true;
            }
        }
        return false;
    }

    /**
     * True when a totem will save the player from this hit: one in hand, or one that was just moved from the
     * inventory (rtpq.auto-totem). Call it only for lethal hits.
     */
    public static boolean protectedByTotem(VPerPlayerKit plugin, Player player) {
        if (inHand(player)) return true;
        return plugin.getConfig().getBoolean("rtpq.auto-equip-totem", false) && equipFromInventory(player);
    }
}
