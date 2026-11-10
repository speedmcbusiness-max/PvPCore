package dev.samar.vperplayerkit.gui;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.util.ItemUtil;
import dev.samar.vperplayerkit.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Banner editor: build a banner and store it as the icon of the kit shown in the Kit Menu. */
public final class BannerMenu extends Menu {
    private static final List<String> PATTERN_KEYS = List.of(
            "stripe_bottom", "stripe_top", "stripe_left", "stripe_right", "stripe_center", "stripe_middle",
            "stripe_downright", "stripe_downleft", "small_stripes", "cross", "straight_cross",
            "diagonal_left", "diagonal_right", "diagonal_up_left", "diagonal_up_right",
            "half_vertical", "half_vertical_right", "half_horizontal", "half_horizontal_bottom",
            "square_bottom_left", "square_bottom_right", "square_top_left", "square_top_right",
            "triangle_bottom", "triangle_top", "triangles_bottom", "triangles_top",
            "circle", "rhombus", "border", "curly_border", "gradient", "gradient_up",
            "bricks", "globe", "creeper", "skull", "flower", "mojang", "piglin");

    private static final int PREVIEW = 8;
    private static final int UNDO = 17;
    private static final int PATTERN_START = 18;
    private static final int PAGE_SIZE = 27;
    private static final int BACK = 45;
    private static final int PREV = 46;
    private static final int RESET = 47;
    private static final int SAVE = 49;
    private static final int NEXT = 52;
    private static final int MAX_LAYERS = 6;

    private final EditorMenu parent;
    private final List<PatternType> patterns = new ArrayList<>();
    private final List<Pattern> layers = new ArrayList<>();
    private final Map<Integer, DyeColor> colorSlots = new HashMap<>();
    private final Map<Integer, PatternType> patternSlots = new HashMap<>();
    private DyeColor base = DyeColor.WHITE;
    private DyeColor active = DyeColor.BLACK;
    private int page = 0;

    public BannerMenu(VPerPlayerKit plugin, EditorMenu parent) {
        super(plugin);
        this.parent = parent;
        for (String key : PATTERN_KEYS) {
            PatternType type = Registry.BANNER_PATTERN.get(NamespacedKey.minecraft(key));
            if (type != null) patterns.add(type);
        }
        create(54, plugin.menus().title("banner.title"));
        render();
    }

    private static ItemStack banner(DyeColor color, List<Pattern> layers) {
        ItemStack item = new ItemStack(Material.valueOf(color.name() + "_BANNER"));
        if (item.getItemMeta() instanceof BannerMeta meta) {
            for (Pattern layer : layers) meta.addPattern(layer);
            item.setItemMeta(meta);
        }
        return item;
    }

    private int pageCount() {
        return Math.max(1, (patterns.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void render() {
        fillGlass();
        colorSlots.clear();
        patternSlots.clear();

        DyeColor[] colors = DyeColor.values();
        for (int i = 0; i < colors.length && i < 16; i++) {
            DyeColor color = colors[i];
            int slot = i < 8 ? i : i + 1;
            colorSlots.put(slot, color);
            ItemStack dye = new ItemStack(Material.valueOf(color.name() + "_DYE"));
            ItemMeta meta = dye.getItemMeta();
            meta.displayName(Text.item("<#3799DD>" + Text.pretty(color.name())));
            meta.lore(colorLore(color));
            dye.setItemMeta(meta);
            inv.setItem(slot, ItemUtil.glow(dye, color == base || color == active));
        }

        ItemStack preview = banner(base, layers);
        inv.setItem(PREVIEW, ItemUtil.relabel(preview, plugin.menus().item("banner.preview")));
        inv.setItem(UNDO, plugin.menus().item("banner.undo"));

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < patterns.size(); i++) {
            PatternType type = patterns.get(start + i);
            int slot = PATTERN_START + i;
            patternSlots.put(slot, type);
            List<Pattern> sample = new ArrayList<>(layers);
            sample.add(new Pattern(active, type));
            ItemStack item = banner(base, sample);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Text.item("<#3799DD>" + Text.pretty(type.getKey().getKey())));
            item.setItemMeta(meta);
            inv.setItem(slot, item);
        }

        inv.setItem(BACK, plugin.menus().item("banner.back"));
        if (page > 0) inv.setItem(PREV, plugin.menus().item("banner.prev"));
        inv.setItem(RESET, plugin.menus().item("banner.reset"));
        inv.setItem(SAVE, plugin.menus().item("banner.save"));
        if (page < pageCount() - 1) inv.setItem(NEXT, plugin.menus().item("banner.next"));
    }

    private List<Component> colorLore(DyeColor color) {
        List<Component> lore = new ArrayList<>();
        lore.add(Text.item("<gray>Left click: <white>base color" + (color == base ? " <green>(current)" : "")));
        lore.add(Text.item("<gray>Right click: <white>pattern color" + (color == active ? " <green>(current)" : "")));
        return lore;
    }

    @Override
    public void click(InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inv.getSize()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (colorSlots.containsKey(raw)) {
            if (event.getClick().isRightClick()) active = colorSlots.get(raw);
            else base = colorSlots.get(raw);
        } else if (patternSlots.containsKey(raw)) {
            if (layers.size() >= MAX_LAYERS) {
                plugin.msg().send(player, "banner-max");
                return;
            }
            layers.add(new Pattern(active, patternSlots.get(raw)));
        } else if (raw == UNDO) {
            if (!layers.isEmpty()) layers.remove(layers.size() - 1);
        } else if (raw == RESET) {
            layers.clear();
            base = DyeColor.WHITE;
            active = DyeColor.BLACK;
        } else if (raw == PREV) {
            if (page > 0) page--;
        } else if (raw == NEXT) {
            if (page < pageCount() - 1) page++;
        } else if (raw == SAVE) {
            plugin.kits().saveIcon(player, parent.kitId(), banner(base, layers));
            parent.open(player);
            return;
        } else if (raw == BACK) {
            parent.open(player);
            return;
        } else {
            return;
        }
        render();
    }
}
