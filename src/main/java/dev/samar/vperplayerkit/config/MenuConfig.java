package dev.samar.vperplayerkit.config;

import dev.samar.vperplayerkit.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Reads buttons, titles and materials from menus.yml. */
public final class MenuConfig {
    private final FileConfiguration cfg;

    public MenuConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public ItemStack item(String path, String... kv) {
        Material material = Material.matchMaterial(cfg.getString(path + ".material", "STONE"));
        if (material == null || material.isAir()) material = Material.STONE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String name = cfg.getString(path + ".name", " ");
            if (name.isEmpty()) name = " ";
            meta.displayName(Text.item(Text.replace(name, kv)));
            List<String> lore = cfg.getStringList(path + ".lore");
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (String line : lore) lines.add(Text.item(Text.replace(line, kv)));
                meta.lore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public Component title(String path, String... kv) {
        return Text.mm(Text.replace(cfg.getString(path, "<white>Menu"), kv));
    }

    public String string(String path, String def) {
        return cfg.getString(path, def);
    }

    public List<String> keys(String path) {
        ConfigurationSection section = cfg.getConfigurationSection(path);
        if (section == null || section.getKeys(false).isEmpty()) {       // an old menus.yml without this section
            var defaults = cfg.getDefaults();
            section = defaults == null ? null : defaults.getConfigurationSection(path);
        }
        if (section == null) return new ArrayList<>();
        return new ArrayList<>(section.getKeys(false));
    }

    public int integer(String path, int def) {
        return cfg.getInt(path, def);
    }

    public List<String> list(String path) {
        return cfg.getStringList(path);
    }

    public List<Integer> integers(String path) {
        return cfg.getIntegerList(path);
    }

    public ItemStack glass() {
        return item("glass");
    }
}
