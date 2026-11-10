package dev.samar.vperplayerkit.totemprac;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Aim target for totem practice: a block display only the practicing player can see. It grows a little while
 * the player's crosshair is on it and goes back to normal size when they look away.
 * Settings: totemprac.target.* in config.yml.
 */
public final class AimTarget {

    private static final class Target {
        BlockDisplay display;
        Location center;
        boolean grown;
        BukkitTask task;
    }

    private final VPerPlayerKit plugin;
    private final Map<UUID, Target> targets = new HashMap<>();

    public AimTarget(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    public boolean has(UUID id) {
        return targets.containsKey(id);
    }

    private double size() {
        return Math.max(0.1, plugin.getConfig().getDouble("totemprac.target.size", 0.6));
    }

    private double grownSize() {
        return size() * Math.max(1.0, plugin.getConfig().getDouble("totemprac.target.grow-multiplier", 1.25));
    }

    private static Transformation transformation(double size) {
        float s = (float) size;
        // translate by -s/2 so the block scales around its center
        return new Transformation(new Vector3f(-s / 2f, -s / 2f, -s / 2f), new AxisAngle4f(),
                new Vector3f(s, s, s), new AxisAngle4f());
    }

    /** /totemprac spawn start: puts the target in front of the player. */
    public void start(Player player) {
        stop(player);
        Material material = Material.matchMaterial(plugin.getConfig().getString("totemprac.target.block", "DIAMOND_BLOCK"));
        if (material == null || !material.isBlock()) material = Material.DIAMOND_BLOCK;
        final Material block = material;

        double distance = Math.max(2.0, plugin.getConfig().getDouble("totemprac.target.distance", 6.0));
        Location eye = player.getEyeLocation();
        // stop in front of a wall so the block never ends up inside blocks where nobody can see it
        RayTraceResult wall = eye.getWorld().rayTraceBlocks(eye, eye.getDirection(), distance,
                FluidCollisionMode.NEVER, true);
        if (wall != null) distance = Math.max(1.5, eye.toVector().distance(wall.getHitPosition()) - 0.7);
        Location center = eye.clone().add(eye.getDirection().normalize().multiply(distance));
        int interpolation = Math.max(1, plugin.getConfig().getInt("totemprac.target.animation-ticks", 3));

        Target target = new Target();
        target.center = center;
        target.display = center.getWorld().spawn(center, BlockDisplay.class, display -> {
            display.setBlock(Bukkit.createBlockData(block));
            display.setTransformation(transformation(size()));
            display.setInterpolationDuration(interpolation);
            display.setPersistent(false);
            display.setVisibleByDefault(false);          // nobody sees it until we show it to the owner
            display.setGlowing(true);                    // easy to spot, also in dark places
        });
        player.showEntity(plugin, target.display);
        targets.put(player.getUniqueId(), target);

        double maxRange = Math.max(distance + 5, plugin.getConfig().getDouble("totemprac.target.max-distance", 40.0));
        target.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !player.getWorld().equals(target.center.getWorld()) || !target.display.isValid()) {
                    stop(player);
                    return;
                }
                tick(player, target, maxRange, interpolation);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void tick(Player player, Target target, double maxRange, int interpolation) {
        double current = target.grown ? grownSize() : size();      // test against the current size, no flicker at the edge
        BoundingBox box = BoundingBox.of(target.center, current / 2, current / 2, current / 2);
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        RayTraceResult hit = box.rayTrace(eye.toVector(), direction, maxRange);
        boolean aiming = hit != null;
        if (aiming == target.grown) return;

        target.grown = aiming;
        target.display.setInterpolationDelay(0);
        target.display.setInterpolationDuration(interpolation);
        target.display.setTransformation(transformation(aiming ? grownSize() : size()));
    }

    public void stop(Player player) {
        Target target = targets.remove(player.getUniqueId());
        if (target == null) return;
        if (target.task != null) target.task.cancel();
        if (target.display != null && target.display.isValid()) target.display.remove();
    }

    public void shutdown() {
        for (Target target : targets.values()) {
            if (target.task != null) target.task.cancel();
            if (target.display != null && target.display.isValid()) target.display.remove();
        }
        targets.clear();
    }
}
