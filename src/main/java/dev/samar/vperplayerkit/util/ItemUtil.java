package dev.samar.vperplayerkit.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class ItemUtil {
    private ItemUtil() {}

    public static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    public static byte[] toBytes(ItemStack[] items) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bos)) {
                out.writeInt(items.length);
                for (ItemStack item : items) {
                    if (isEmpty(item)) {
                        out.writeInt(0);
                        continue;
                    }
                    byte[] data = item.serializeAsBytes();
                    out.writeInt(data.length);
                    out.write(data);
                }
            }
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not serialize items", ex);
        }
    }

    /** Returns null when the data is missing or corrupt. */
    public static ItemStack[] fromBytes(byte[] data) {
        if (data == null) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int n = in.readInt();
            ItemStack[] items = new ItemStack[n];
            for (int i = 0; i < n; i++) {
                int len = in.readInt();
                if (len > 0) {
                    byte[] b = new byte[len];
                    in.readFully(b);
                    items[i] = ItemStack.deserializeBytes(b);
                }
            }
            return items;
        } catch (Exception ex) {
            return null;
        }
    }

    public static byte[] single(ItemStack item) {
        return toBytes(new ItemStack[] {item});
    }

    public static ItemStack singleFromBytes(byte[] data) {
        ItemStack[] arr = fromBytes(data);
        return (arr == null || arr.length == 0) ? null : arr[0];
    }

    /** Copies name and lore of {@code label} onto a clone of {@code target}. */
    public static ItemStack relabel(ItemStack target, ItemStack label) {
        ItemStack out = target.clone();
        ItemMeta meta = out.getItemMeta();
        ItemMeta from = label.getItemMeta();
        if (meta == null || from == null) return out;
        meta.displayName(from.displayName());
        meta.lore(from.lore());
        out.setItemMeta(meta);
        return out;
    }

    public static ItemStack glow(ItemStack item, boolean glow) {
        if (!glow) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }
}
