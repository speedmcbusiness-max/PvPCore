package dev.samar.vperplayerkit.rtp;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Biome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Finds random spots in a biome (plains, desert, badlands, snow, mushroom) of the RTP world.
 * The world is rtpq.world, a biome can have its own dedicated world in rtpq.worlds.<biome> (then any spot in it is used).
 * The biome groups are in config.yml under rtp.biomes. Used by /rtp, /qrtp, the RTP queue, FFA and Gladiator.
 */
public final class BiomeLocator {
    private final VPerPlayerKit plugin;

    public BiomeLocator(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    /** The biome choices (keys of rtp.biomes). */
    public List<String> biomes() {
        List<String> out = new ArrayList<>();
        var section = plugin.getConfig().getConfigurationSection("rtp.biomes");
        if (section == null || section.getKeys(false).isEmpty()) {        // an old config.yml does not have this section yet
            var defaults = plugin.getConfig().getDefaults();
            section = defaults == null ? null : defaults.getConfigurationSection("rtp.biomes");
        }
        if (section != null) {
            for (String key : section.getKeys(false)) out.add(key.toLowerCase());
        }
        return out;
    }

    public boolean isBiome(String key) {
        return key != null && biomes().contains(key.toLowerCase());
    }

    public Set<Biome> biomesOf(String key) {
        Set<Biome> out = new HashSet<>();
        for (String name : plugin.getConfig().getStringList("rtp.biomes." + key)) {
            Biome biome = Registry.BIOME.get(NamespacedKey.minecraft(name.toLowerCase()));
            if (biome != null) out.add(biome);
        }
        return out;
    }

    public World defaultWorld() {
        World world = Bukkit.getWorld(plugin.getConfig().getString("rtpq.world", "world"));
        if (world != null) return world;
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    private World dedicated(String biome) {
        if (biome == null) return null;
        String name = plugin.getConfig().getString("rtpq.worlds." + biome, "");
        return (name == null || name.isBlank()) ? null : Bukkit.getWorld(name);
    }

    /** The world to use for a biome ("auto" or null = the RTP world). */
    public World worldFor(String biome) {
        World dedicated = dedicated(biome);
        return dedicated != null ? dedicated : defaultWorld();
    }

    /** Worlds where /qrtp works and where fights happen. */
    public boolean isRtpWorld(World world) {
        if (world == null) return false;
        World def = defaultWorld();
        if (def != null && def.equals(world)) return true;
        var section = plugin.getConfig().getConfigurationSection("rtpq.worlds");
        if (section == null) return false;
        for (String key : section.getKeys(false)) {
            if (world.getName().equals(section.getString(key))) return true;
        }
        return false;
    }

    private boolean needsSearch(World world, String biome) {
        return biome != null && !biome.equals("auto") && dedicated(biome) == null && !biomesOf(biome).isEmpty();
    }

    /** A random x/z in the world (inside the border), in the biome when one is wanted. Null when the biome was not found. */
    public int[] pick(World world, String biome, int radius) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        WorldBorder border = world.getWorldBorder();
        double half = Math.max(16, Math.min(radius, border.getSize() / 2.0 - 16));
        Location center = border.getCenter();
        int ox = (int) Math.round(center.getX() + rnd.nextDouble(-half, half));
        int oz = (int) Math.round(center.getZ() + rnd.nextDouble(-half, half));
        if (!needsSearch(world, biome)) return new int[] {ox, oz};

        int searchRadius = Math.max(256, plugin.getConfig().getInt("rtp.biome-search-radius", 3000));
        Set<Biome> set = biomesOf(biome);
        var result = world.locateNearestBiome(new Location(world, ox, 64, oz), searchRadius, 32, 64,
                set.toArray(new Biome[0]));
        if (result == null) return null;
        Location found = result.getLocation();
        int x = found.getBlockX() + rnd.nextInt(-24, 25);        // do not land on the exact same block every time
        int z = found.getBlockZ() + rnd.nextInt(-24, 25);
        double limit = border.getSize() / 2.0 - 8;
        x = (int) Math.max(center.getX() - limit, Math.min(center.getX() + limit, x));
        z = (int) Math.max(center.getZ() - limit, Math.min(center.getZ() + limit, z));
        return new int[] {x, z};
    }

    /** True when the location is in the wanted biome (always true when no biome is wanted). */
    public boolean matches(Location loc, String biome) {
        World world = loc.getWorld();
        if (world == null || !needsSearch(world, biome)) return true;
        return biomesOf(biome).contains(world.getBiome(loc));
    }
}
