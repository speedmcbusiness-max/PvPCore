package dev.samar.vperplayerkit.config;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/** Locations and settings that admins set in-game (lobby spawn, totem practice). Stored in data.yml. */
public final class DataStore {
    private final VPerPlayerKit plugin;
    private final File file;
    private final YamlConfiguration yml;

    public DataStore(VPerPlayerKit plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.yml = YamlConfiguration.loadConfiguration(file);
    }

    public YamlConfiguration yml() {
        return yml;
    }

    public synchronized void save() {
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save data.yml", ex);
        }
    }

    public void setLocation(String path, Location loc) {
        yml.set(path + ".world", loc.getWorld().getName());
        yml.set(path + ".x", loc.getX());
        yml.set(path + ".y", loc.getY());
        yml.set(path + ".z", loc.getZ());
        yml.set(path + ".yaw", (double) loc.getYaw());
        yml.set(path + ".pitch", (double) loc.getPitch());
        save();
    }

    /** Null when unset or the world is not loaded. */
    public Location getLocation(String path) {
        if (!yml.contains(path + ".world")) return null;
        World world = Bukkit.getWorld(yml.getString(path + ".world", ""));
        if (world == null) return null;
        return new Location(world, yml.getDouble(path + ".x"), yml.getDouble(path + ".y"), yml.getDouble(path + ".z"),
                (float) yml.getDouble(path + ".yaw"), (float) yml.getDouble(path + ".pitch"));
    }

    public void clear(String path) {
        yml.set(path, null);
        save();
    }

    public Location lobby() {
        return getLocation("lobby");
    }
}
