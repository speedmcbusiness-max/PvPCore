package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.ItemUtil;
import dev.samar.vperplayerkit.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Armor trimmer: pick a pattern and a material, then apply to the armor row of the editor. */
public final class TrimMenu extends Menu {
    private static final List<String> PATTERNS = List.of(
            "sentry", "dune", "coast", "wild", "ward", "eye", "vex", "tide", "snout",
            "rib", "spire", "wayfinder", "shaper", "silence", "raiser", "host", "flow", "bolt");

    private static final Map<String, Material> MATERIALS = new LinkedHashMap<>();

    static {
        MATERIALS.put("quartz", Material.QUARTZ);
        MATERIALS.put("iron", Material.IRON_INGOT);
        MATERIALS.put("netherite", Material.NETHERITE_INGOT);
        MATERIALS.put("redstone", Material.REDSTONE);
        MATERIALS.put("copper", Material.COPPER_INGOT);
        MATERIALS.put("gold", Material.GOLD_INGOT);
        MATERIALS.put("emerald", Material.EMERALD);
        MATERIALS.put("diamond", Material.DIAMOND);
        MATERIALS.put("lapis", Material.LAPIS_LAZULI);
        MATERIALS.put("amethyst", Material.AMETHYST_SHARD);
    }

    private static final int BACK = 45;
    private static final int REMOVE = 48;
    private static final int APPLY = 49;

    private final EditorMenu parent;
    private final Map<Integer, String> patternSlots = new HashMap<>();
    private final Map<Integer, String> materialSlots = new HashMap<>();
    private String pattern;
    private String material;

    public TrimMenu(VPerPlayerKit plugin, EditorMenu parent) {
        super(plugin);
        this.parent = parent;
        create(54, plugin.menus().title("trim.title"));
        render();
    }

    private void render() {
        fillGlass();
        patternSlots.clear();
        materialSlots.clear();

        int slot = 0;
        for (String key : PATTERNS) {
            Material template = Material.getMaterial(key.toUpperCase() + "_ARMOR_TRIM_SMITHING_TEMPLATE");
            if (template == null || trimPattern(key) == null || slot >= 18) continue;
            patternSlots.put(slot, key);
            inv.setItem(slot, icon(template, key, key.equals(pattern)));
            slot++;
        }

        slot = 27;
        for (Map.Entry<String, Material> entry : MATERIALS.entrySet()) {
            if (trimMaterial(entry.getKey()) == null) continue;
            materialSlots.put(slot, entry.getKey());
            inv.setItem(slot, icon(entry.getValue(), entry.getKey(), entry.getKey().equals(material)));
            slot++;
        }

        inv.setItem(BACK, plugin.menus().item("trim.back"));
        inv.setItem(REMOVE, plugin.menus().item("trim.remove"));
        inv.setItem(APPLY, plugin.menus().item("trim.apply"));
    }

    private ItemStack icon(Material type, String key, boolean selected) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item("<#3799DD>" + Text.pretty(key)));
        if (selected) {
            meta.lore(List.<Component>of(Text.item(plugin.menus().string("trim.selected-lore", "<green>Selected"))));
        }
        item.setItemMeta(meta);
        return ItemUtil.glow(item, selected);
    }

    private static TrimPattern trimPattern(String key) {
        return Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(key));
    }

    private static TrimMaterial trimMaterial(String key) {
        return Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(key));
    }

    @Override
    public void click(InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inv.getSize()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (patternSlots.containsKey(raw)) {
            pattern = patternSlots.get(raw);
            render();
        } else if (materialSlots.containsKey(raw)) {
            material = materialSlots.get(raw);
            render();
        } else if (raw == BACK) {
            parent.open(player);
        } else if (raw == APPLY) {
            apply(player);
        } else if (raw == REMOVE) {
            int count = 0;
            for (int slot = 36; slot <= 39; slot++) {
                ItemStack item = parent.getInventory().getItem(slot);
                if (item != null && item.getItemMeta() instanceof ArmorMeta meta && meta.hasTrim()) {
                    meta.setTrim(null);
                    item.setItemMeta(meta);
                    count++;
                }
            }
            plugin.msg().send(player, "trim-removed", "{count}", String.valueOf(count));
        }
    }

    private void apply(Player player) {
        if (pattern == null || material == null) {
            plugin.msg().send(player, "trim-choose");
            return;
        }
        TrimPattern trimPattern = trimPattern(pattern);
        TrimMaterial trimMaterial = trimMaterial(material);
        if (trimPattern == null || trimMaterial == null) return;

        int count = 0;
        for (int slot = 36; slot <= 39; slot++) {
            ItemStack item = parent.getInventory().getItem(slot);
            if (item != null && item.getItemMeta() instanceof ArmorMeta meta) {
                meta.setTrim(new ArmorTrim(trimMaterial, trimPattern));
                item.setItemMeta(meta);
                count++;
            }
        }
        plugin.msg().send(player, "trim-applied", "{count}", String.valueOf(count));
    }
}
