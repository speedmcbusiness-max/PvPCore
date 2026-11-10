package dev.samar.vperplayerkit.rtpq;

import dev.samar.vperplayerkit.VPerPlayerKit;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The danger zone of a duel or Gladiator fight: a square or circle (random) that waits, then repeatedly shrinks
 * and moves towards a new center that always lies inside the old zone. Red beam columns (block displays) mark the
 * edge, a square also uses a per-player world border, a circle is drawn with red particles.
 * The current shape lives on the arena object (inSafeZone), the damage check reads it from there.
 */
public final class ZoneController {
    private enum Phase { WAIT, SHRINK, DONE }

    private final VPerPlayerKit plugin;
    private final String path;
    private final DuelManager.Duel arena;
    private final Supplier<Collection<Player>> players;
    private final BlockDisplay[] beams;
    private final WorldBorder border = Bukkit.createWorldBorder();

    private final double minRadius;
    private final long delayTicks;
    private final long shrinkTicks;
    private final long holdTicks;
    private final double factor;

    private BukkitTask task;
    private boolean stopped;
    private Phase phase = Phase.WAIT;
    private long phaseTicks;
    private int shrinks;
    private double fromR, toR, fromX, fromZ, toX, toZ;
    private double sentX, sentZ, sentR = -1;      // what the world border of the players currently shows
    private long sentAt;
    private long tick;
    private final boolean[] spawning;

    public ZoneController(VPerPlayerKit plugin, DuelManager.Duel arena, Supplier<Collection<Player>> players, String path) {
        this.plugin = plugin;
        this.path = path;
        this.arena = arena;
        this.players = players;
        var cfg = plugin.getConfig();
        this.minRadius = Math.max(10, cfg.getDouble(path + ".min-radius", 40));
        this.delayTicks = Math.max(0, cfg.getInt(path + ".shrink-start-seconds", 60)) * 20L;
        this.shrinkTicks = Math.max(1, cfg.getInt(path + ".shrink-duration-seconds", 25)) * 20L;
        this.holdTicks = Math.max(0, cfg.getInt(path + ".hold-seconds", 20)) * 20L;
        this.factor = Math.max(0.3, Math.min(0.95, cfg.getDouble(path + ".shrink-factor", 0.7)));
        this.beams = new BlockDisplay[cfg.getBoolean(path + ".beacons", true)
                ? Math.max(4, cfg.getInt(path + ".beam-count", 16)) : 0];
        this.spawning = new boolean[this.beams.length];
    }

    public void start() {
        border.setDamageAmount(0);
        border.setDamageBuffer(0);
        border.setWarningDistance(10);
        spawnBeams();
        refresh();
        task = new BukkitRunnable() {
            @Override
            public void run() {
                step();
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    public void stop() {
        stopped = true;
        if (task != null) task.cancel();
        for (int i = 0; i < beams.length; i++) {
            if (beams[i] != null && beams[i].isValid()) beams[i].remove();
            beams[i] = null;
        }
        for (Player p : players.get()) {
            if (p.isOnline()) p.setWorldBorder(null);
        }
    }

    // ------------------------------------------------------------------ shrinking and moving

    private void step() {
        if (stopped) return;
        phaseTicks += 5;
        switch (phase) {
            case WAIT -> {
                if (arena.zoneRadius <= minRadius + 0.5) {
                    phase = Phase.DONE;
                    break;
                }
                long wait = shrinks == 0 ? delayTicks : holdTicks;
                if (phaseTicks >= wait) startShrink();
            }
            case SHRINK -> {
                double t = Math.min(1.0, phaseTicks / (double) shrinkTicks);
                arena.zoneRadius = fromR + (toR - fromR) * t;
                arena.zoneX = fromX + (toX - fromX) * t;
                arena.zoneZ = fromZ + (toZ - fromZ) * t;
                if (t >= 1.0) {
                    phase = Phase.WAIT;
                    phaseTicks = 0;
                    shrinks++;
                    if (arena.zoneRadius <= minRadius + 0.5) announce("zone-final");
                }
            }
            default -> { }
        }
        refresh();
        tick += 5;
        if (tick % 20 == 0) ensureBeams();
    }

    /** Beams are entities and vanish when their chunk unloads: bring them back when a player is close again. */
    private void ensureBeams() {
        double range = Math.max(64, plugin.getConfig().getDouble(path + ".beam-range", 160));
        for (int i = 0; i < beams.length; i++) {
            if ((beams[i] != null && beams[i].isValid()) || spawning[i]) continue;
            double[] p = edgePoint(i);
            for (Player pl : players.get()) {
                if (pl.isOnline() && pl.getWorld().equals(arena.world)
                        && Math.hypot(pl.getX() - p[0], pl.getZ() - p[1]) < range) {
                    queueBeam(i);
                    break;
                }
            }
        }
    }

    private void queueBeam(int index) {
        spawning[index] = true;
        double[] p = edgePoint(index);
        arena.world.getChunkAtAsync((int) Math.floor(p[0]) >> 4, (int) Math.floor(p[1]) >> 4)
                .thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
                    spawning[index] = false;
                    spawnBeam(index);
                }));
    }

    private void startShrink() {
        fromR = arena.zoneRadius;
        fromX = arena.zoneX;
        fromZ = arena.zoneZ;
        toR = Math.max(minRadius, fromR * factor);
        // the new zone must fit inside the old one
        double room = Math.max(0, fromR - toR);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (room < 0.5) {
            toX = fromX;
            toZ = fromZ;
        } else if (arena.circle) {
            double angle = rnd.nextDouble(0, Math.PI * 2);
            double dist = rnd.nextDouble(0, room);
            toX = fromX + Math.cos(angle) * dist;
            toZ = fromZ + Math.sin(angle) * dist;
        } else {
            toX = fromX + rnd.nextDouble(-room, room);
            toZ = fromZ + rnd.nextDouble(-room, room);
        }
        phase = Phase.SHRINK;
        phaseTicks = 0;
        announce("zone-shrinking");
    }

    private void announce(String key) {
        for (Player p : players.get()) {
            if (p.isOnline()) plugin.msg().send(p, key);
        }
    }

    // ------------------------------------------------------------------ visuals

    private void refresh() {
        Collection<Player> list = players.get();
        boolean particles = plugin.getConfig().getBoolean(path + ".particles", true);
        if (!arena.circle) {
            boolean changed = sentR < 0 || Math.abs(arena.zoneX - sentX) > 0.05 || Math.abs(arena.zoneZ - sentZ) > 0.05
                    || Math.abs(arena.zoneRadius - sentR) > 0.05;
            long now = System.currentTimeMillis();
            if (changed || now - sentAt > 3000) {        // resend when it moved, and now and then so nobody misses it
                border.setCenter(arena.zoneX, arena.zoneZ);
                border.setSize(arena.zoneRadius * 2);
                for (Player p : list) {
                    if (p.isOnline()) p.setWorldBorder(border);
                }
                sentX = arena.zoneX;
                sentZ = arena.zoneZ;
                sentR = arena.zoneRadius;
                sentAt = now;
            }
        }
        if (particles) {
            for (Player p : list) {
                if (!p.isOnline()) continue;
                if (arena.circle) drawRing(p);
                else drawEdge(p);
            }
        }
        moveBeams();
    }

    /** Square: red particles on the parts of the four edges near the player. */
    private void drawEdge(Player p) {
        Location pl = p.getLocation();
        double r = arena.zoneRadius;
        double minX = arena.zoneX - r, maxX = arena.zoneX + r, minZ = arena.zoneZ - r, maxZ = arena.zoneZ + r;
        Particle.DustOptions dust = new Particle.DustOptions(Color.RED, 2f);
        double range = 14;
        for (double z : new double[] {minZ, maxZ}) {
            if (Math.abs(pl.getZ() - z) > 24) continue;
            for (double x = Math.max(minX, pl.getX() - range); x <= Math.min(maxX, pl.getX() + range); x += 2) {
                edgeParticles(p, x, z, dust);
            }
        }
        for (double x : new double[] {minX, maxX}) {
            if (Math.abs(pl.getX() - x) > 24) continue;
            for (double z = Math.max(minZ, pl.getZ() - range); z <= Math.min(maxZ, pl.getZ() + range); z += 2) {
                edgeParticles(p, x, z, dust);
            }
        }
    }

    private void edgeParticles(Player p, double x, double z, Particle.DustOptions dust) {
        double y = p.getLocation().getY();
        p.spawnParticle(Particle.DUST, x, y - 1, z, 1, 0, 0, 0, 0, dust);
        p.spawnParticle(Particle.DUST, x, y + 0.5, z, 1, 0, 0, 0, 0, dust);
        p.spawnParticle(Particle.DUST, x, y + 2, z, 1, 0, 0, 0, 0, dust);
    }

    /** Circle: red particles on the part of the edge near the player. */
    private void drawRing(Player p) {
        Location pl = p.getLocation();
        double dx = pl.getX() - arena.zoneX;
        double dz = pl.getZ() - arena.zoneZ;
        double dist = Math.hypot(dx, dz);
        if (Math.abs(dist - arena.zoneRadius) > 24) return;
        double radius = arena.zoneRadius;
        double base = Math.atan2(dz, dx);
        double step = 2.0 / radius;                                    // one point every 2 blocks
        int n = (int) Math.min(Math.PI / step, 14.0 / step);
        Particle.DustOptions dust = new Particle.DustOptions(Color.RED, 2f);
        for (int k = -n; k <= n; k++) {
            double a = base + k * step;
            double x = arena.zoneX + Math.cos(a) * radius;
            double z = arena.zoneZ + Math.sin(a) * radius;
            p.spawnParticle(Particle.DUST, x, pl.getY() - 1, z, 1, 0, 0, 0, 0, dust);
            p.spawnParticle(Particle.DUST, x, pl.getY() + 0.5, z, 1, 0, 0, 0, 0, dust);
            p.spawnParticle(Particle.DUST, x, pl.getY() + 2, z, 1, 0, 0, 0, 0, dust);
        }
    }

    /** Point i of the edge, evenly spaced along the circle or around the square. */
    private double[] edgePoint(int i) {
        double frac = i / (double) beams.length;
        double r = arena.zoneRadius;
        if (arena.circle) {
            double a = frac * Math.PI * 2;
            return new double[] {arena.zoneX + Math.cos(a) * r, arena.zoneZ + Math.sin(a) * r};
        }
        double t = frac * 4;
        int side = (int) Math.floor(t);
        double f = t - side;
        return switch (side) {
            case 0 -> new double[] {arena.zoneX - r + 2 * r * f, arena.zoneZ - r};
            case 1 -> new double[] {arena.zoneX + r, arena.zoneZ - r + 2 * r * f};
            case 2 -> new double[] {arena.zoneX + r - 2 * r * f, arena.zoneZ + r};
            default -> new double[] {arena.zoneX - r, arena.zoneZ + r - 2 * r * f};
        };
    }

    private void spawnBeams() {
        for (int i = 0; i < beams.length; i++) queueBeam(i);
    }

    private void spawnBeam(int index) {
        if (stopped || (beams[index] != null && beams[index].isValid())) return;
        World world = arena.world;
        Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".beam-block", "RED_STAINED_GLASS"));
        if (material == null || !material.isBlock()) material = Material.RED_STAINED_GLASS;
        final Material block = material;
        double[] p = edgePoint(index);
        Location loc = new Location(world, p[0], world.getMinHeight(), p[1]);
        float height = world.getMaxHeight() - world.getMinHeight();
        beams[index] = world.spawn(loc, BlockDisplay.class, display -> {
            display.setBlock(Bukkit.createBlockData(block));
            display.setTransformation(new Transformation(new Vector3f(-0.15f, 0f, -0.15f), new AxisAngle4f(),
                    new Vector3f(0.3f, height, 0.3f), new AxisAngle4f()));
            display.setBrightness(new Display.Brightness(15, 15));      // glows like a beacon beam
            display.setViewRange(3.0f);
            display.setTeleportDuration(5);                             // glides while the zone moves
            display.setPersistent(false);
        });
    }

    private void moveBeams() {
        World world = arena.world;
        for (int i = 0; i < beams.length; i++) {
            BlockDisplay beam = beams[i];
            if (beam == null || !beam.isValid()) continue;
            double[] p = edgePoint(i);
            beam.teleport(new Location(world, p[0], world.getMinHeight(), p[1]));
        }
    }
}
