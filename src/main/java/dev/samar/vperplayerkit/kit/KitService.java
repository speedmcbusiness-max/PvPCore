package dev.samar.vperplayerkit.kit;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.storage.Storage;
import dev.samar.vperplayerkit.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** All kit logic: loading, saving, sharing, public kits. Database work is always async. */
public final class KitService {
    public static final String GLOBAL = "global";
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final VPerPlayerKit plugin;
    private final Set<String> publicNames = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    public KitService(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private Storage db() {
        return plugin.storage();
    }

    // ---------------------------------------------------------------- inventory helpers

    public static ItemStack[] capture(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] out = new ItemStack[KitLayout.KIT_SIZE];
        for (int i = 0; i < KitLayout.KIT_SIZE; i++) {
            ItemStack item = inv.getItem(i);
            out[i] = ItemUtil.isEmpty(item) ? null : item.clone();
        }
        return out;
    }

    public static void applyKit(Player player, ItemStack[] items, boolean clear) {
        PlayerInventory inv = player.getInventory();
        if (clear) inv.clear();
        for (int i = 0; i < Math.min(KitLayout.KIT_SIZE, items.length); i++) {
            if (items[i] != null) inv.setItem(i, items[i].clone());
        }
        player.updateInventory();
    }

    @SuppressWarnings("deprecation")
    public void heal(Player player) {
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0f);
    }

    private void feedback(Player player) {
        String sound = plugin.getConfig().getString("load-kit.sound", "");
        if (sound == null || sound.isEmpty()) return;
        // String keys work on every Paper version (Sound is no longer an enum on newer ones)
        player.playSound(player.getLocation(), sound.toLowerCase(), 0.6f, 1.2f);
    }

    private boolean canBroadcast(Player player) {
        return !player.hasPermission("perplayerkit.notifications.bypass");
    }

    // ---------------------------------------------------------------- personal kits

    public void loadKit(Player player, int id) {
        if (plugin.inMatch(player)) {
            plugin.msg().send(player, "match-command-blocked");
            return;
        }
        String owner = player.getUniqueId().toString();
        plugin.async(() -> new byte[][] {
                db().get(owner, "kit", String.valueOf(id)),
                db().get(owner, "echest", String.valueOf(id))
        }, data -> {
            if (!player.isOnline()) return;
            ItemStack[] items = ItemUtil.fromBytes(data[0]);
            if (items == null) {
                plugin.msg().send(player, "kit-empty", "{n}", String.valueOf(id));
                return;
            }
            boolean clear = plugin.getConfig().getBoolean("load-kit.clear-inventory", true);
            applyKit(player, items, clear);
            if (plugin.getConfig().getBoolean("load-kit.sync-enderchest", true) && data[1] != null) {
                ItemStack[] ender = ItemUtil.fromBytes(data[1]);
                if (ender != null) {
                    ItemStack[] fixed = new ItemStack[27];
                    System.arraycopy(ender, 0, fixed, 0, Math.min(27, ender.length));
                    player.getEnderChest().setContents(fixed);
                }
            }
            if (plugin.getConfig().getBoolean("load-kit.heal", true)) heal(player);
            setSelected(player, id);
            feedback(player);
            plugin.msg().send(player, "kit-loaded", "{n}", String.valueOf(id));
            if (plugin.getConfig().getBoolean("notifications.broadcast-kit-load", false) && canBroadcast(player)) {
                Bukkit.broadcast(plugin.msg().get("broadcast-kit", "{player}", player.getName()));
            }
        });
    }

    public void loadSelected(Player player) {
        String owner = player.getUniqueId().toString();
        plugin.async(() -> db().get(owner, "selected", "main"), data -> {
            if (!player.isOnline()) return;
            Integer id = parseSelected(data);
            if (id == null) {
                plugin.msg().send(player, "no-selected");
                return;
            }
            loadKit(player, id);
        });
    }

    /** Restores missing/used items from the last loaded kit without clearing anything else. */
    public void regear(Player player) {
        String owner = player.getUniqueId().toString();
        plugin.async(() -> {
            Integer id = parseSelected(db().get(owner, "selected", "main"));
            if (id == null) return null;
            return new Object[] {id, db().get(owner, "kit", String.valueOf(id))};
        }, result -> {
            if (!player.isOnline()) return;
            if (result == null) {
                plugin.msg().send(player, "no-selected");
                return;
            }
            int id = (Integer) result[0];
            ItemStack[] items = ItemUtil.fromBytes((byte[]) result[1]);
            if (items == null) {
                plugin.msg().send(player, "kit-empty", "{n}", String.valueOf(id));
                return;
            }
            applyKit(player, items, false);
            feedback(player);
            plugin.msg().send(player, "regeared", "{n}", String.valueOf(id));
        });
    }

    private Integer parseSelected(byte[] data) {
        if (data == null) return null;
        try {
            return Integer.parseInt(new String(data, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void setSelected(Player player, int id) {
        String owner = player.getUniqueId().toString();
        byte[] data = String.valueOf(id).getBytes(StandardCharsets.UTF_8);
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        plugin.async(() -> {
            db().put(owner, "selected", "main", data);
            plugin.cache().refresh(uuid, name);
        });
    }

    /** Marks a kit as the player's selected kit (shown as paper in the Kit Menu, used by the RTP queue). */
    public void selectKit(Player player, int id) {
        setSelected(player, id);
        plugin.msg().send(player, "kit-selected", "{n}", String.valueOf(id));
    }

    public void saveKit(Player player, int id, ItemStack[] items) {
        UUID uuid = player.getUniqueId();
        byte[] data = ItemUtil.toBytes(items);
        plugin.async(() -> db().put(uuid.toString(), "kit", String.valueOf(id), data), ok -> {
            if (!player.isOnline()) return;
            if (ok) {
                plugin.msg().send(player, "kit-saved", "{n}", String.valueOf(id));
                plugin.cache().refresh(uuid, player.getName());
            }
            else plugin.msg().send(player, "database-error");
        });
    }

    public void saveEnderChest(Player player, int id, ItemStack[] items) {
        UUID uuid = player.getUniqueId();
        byte[] data = ItemUtil.toBytes(items);
        plugin.async(() -> db().put(uuid.toString(), "echest", String.valueOf(id), data), ok -> {
            if (!player.isOnline()) return;
            if (ok) plugin.msg().send(player, "enderchest-saved", "{n}", String.valueOf(id));
            else plugin.msg().send(player, "database-error");
        });
    }

    public void saveIcon(Player player, int id, ItemStack banner) {
        UUID uuid = player.getUniqueId();
        byte[] data = ItemUtil.single(banner);
        plugin.async(() -> db().put(uuid.toString(), "icon", String.valueOf(id), data), ok -> {
            if (!player.isOnline()) return;
            if (ok) plugin.msg().send(player, "banner-saved", "{n}", String.valueOf(id));
            else plugin.msg().send(player, "database-error");
        });
    }

    // ---------------------------------------------------------------- sharing

    public void share(Player player, ItemStack[] items) {
        byte[] data = ItemUtil.toBytes(items);
        int length = Math.max(4, Math.min(12, plugin.getConfig().getInt("share.code-length", 6)));
        plugin.async(() -> {
            for (int attempt = 0; attempt < 10; attempt++) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < length; i++) sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
                String code = sb.toString();
                if (!db().exists(GLOBAL, "share", code)) {
                    return db().put(GLOBAL, "share", code, data) ? code : null;
                }
            }
            return null;
        }, code -> {
            if (!player.isOnline()) return;
            if (code == null) plugin.msg().send(player, "database-error");
            else plugin.msg().send(player, "share-created", "{code}", code);
        });
    }

    // ---------------------------------------------------------------- public kits

    public Set<String> publicNames() {
        return Collections.unmodifiableSet(publicNames);
    }

    public void refreshPublicNames() {
        plugin.async(() -> db().ids(GLOBAL, "public"), list -> {
            publicNames.clear();
            publicNames.addAll(list);
        });
    }

    public static String sanitizeName(String raw) {
        if (raw == null) return null;
        String name = raw.toLowerCase();
        return name.matches("[a-z0-9_-]{1,24}") ? name : null;
    }

    public void loadPublic(Player player, String name) {
        if (plugin.inMatch(player)) {
            plugin.msg().send(player, "match-command-blocked");
            return;
        }
        plugin.async(() -> db().get(GLOBAL, "public", name), data -> {
            if (!player.isOnline()) return;
            ItemStack[] items = ItemUtil.fromBytes(data);
            if (items == null) {
                plugin.msg().send(player, "public-missing", "{name}", name);
                return;
            }
            applyKit(player, items, plugin.getConfig().getBoolean("load-kit.clear-inventory", true));
            if (plugin.getConfig().getBoolean("load-kit.heal", true)) heal(player);
            feedback(player);
            plugin.msg().send(player, "public-loaded", "{name}", name);
            if (plugin.getConfig().getBoolean("notifications.broadcast-public-kit", false) && canBroadcast(player)) {
                Bukkit.broadcast(plugin.msg().get("broadcast-public", "{player}", player.getName(), "{name}", name));
            }
        });
    }

    public void createPublic(Player player, String name) {
        byte[] data = ItemUtil.toBytes(capture(player));
        plugin.async(() -> {
            if (db().exists(GLOBAL, "public", name)) return 1;
            return db().put(GLOBAL, "public", name, data) ? 0 : 2;
        }, code -> {
            if (!player.isOnline()) return;
            if (code == 0) {
                publicNames.add(name);
                plugin.msg().send(player, "public-created", "{name}", name);
            } else if (code == 1) {
                plugin.msg().send(player, "public-exists", "{name}", name);
            } else {
                plugin.msg().send(player, "database-error");
            }
        });
    }

    public void savePublic(Player player, String name, ItemStack[] items) {
        byte[] data = ItemUtil.toBytes(items);
        plugin.async(() -> db().put(GLOBAL, "public", name, data), ok -> {
            if (!player.isOnline()) return;
            if (ok) {
                publicNames.add(name);
                plugin.msg().send(player, "public-saved", "{name}", name);
            } else {
                plugin.msg().send(player, "database-error");
            }
        });
    }

    public void deletePublic(Player player, String name) {
        plugin.async(() -> {
            if (!db().exists(GLOBAL, "public", name)) return false;
            return db().delete(GLOBAL, "public", name);
        }, ok -> {
            if (!player.isOnline()) return;
            if (ok) {
                publicNames.remove(name);
                plugin.msg().send(player, "public-deleted", "{name}", name);
            } else {
                plugin.msg().send(player, "public-missing", "{name}", name);
            }
        });
    }

    // ---------------------------------------------------------------- kit room

    public void saveKitRoom(Player player, String category, ItemStack[] items) {
        byte[] data = ItemUtil.toBytes(items);
        plugin.async(() -> db().put(GLOBAL, "kitroom", category, data), ok -> {
            if (!player.isOnline()) return;
            if (ok) plugin.msg().send(player, "kitroom-saved", "{category}", category);
            else plugin.msg().send(player, "database-error");
        });
    }
}
