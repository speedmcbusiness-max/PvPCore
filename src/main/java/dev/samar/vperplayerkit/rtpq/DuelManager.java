package dev.samar.vperplayerkit.rtpq;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.kit.KitService;
import dev.samar.vperplayerkit.util.ItemUtil;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Runs queue duels: finds an arena in an RTP world, builds the 250x250 danger zone (per-player border + red beacon
 * beams), plays first-to-2 (first-to-3 for tier tests), applies tier results and restores the arena afterwards.
 */
public final class DuelManager {

    public enum State { PREPARING, COUNTDOWN, ACTIVE, BETWEEN, ENDED }

    /** Everything about one running duel. */
    public static final class Duel {
        final UUID[] ids = new UUID[2];
        final String[] names = new String[2];
        final int[] kitIds = new int[2];
        final String[] biomeChoice = new String[2];
        final ItemStack[][] kits = new ItemStack[2][];
        final boolean[] testing = new boolean[2];
        final int[] score = new int[2];
        final int[] pops = new int[2];
        final int[] outside = new int[2];
        final boolean[] left = new boolean[2];
        final Location[] spawns = new Location[2];
        final Map<Long, BlockData> original = new HashMap<>();
        final Set<Long> protectedBlocks = new HashSet<>();
        int target;
        int half;
        int winner = -1;
        World world;
        Location center;
        State state = State.PREPARING;
        long startMillis;
        BukkitTask zoneTask;
        BukkitTask roundTask;
        double zoneX, zoneZ, zoneRadius;      // the shrinking safe zone (arena footprint stays at center/half)
        boolean circle;
        ZoneController zone;

        public int indexOf(UUID uuid) {
            return ids[0].equals(uuid) ? 0 : (ids[1].equals(uuid) ? 1 : -1);
        }

        public State state() {
            return state;
        }

        public String opponentName(UUID id) {
            int i = indexOf(id);
            return i < 0 ? "" : names[1 - i];
        }

        public int score(UUID id) {
            int i = indexOf(id);
            return i < 0 ? 0 : score[i];
        }

        public int opponentScore(UUID id) {
            int i = indexOf(id);
            return i < 0 ? 0 : score[1 - i];
        }

        public int ownPops(UUID id) {
            int i = indexOf(id);
            return i < 0 ? 0 : pops[i];
        }

        public int target() {
            return target;
        }

        public boolean isTest() {
            return testing[0] || testing[1];
        }

        public long elapsedSeconds() {
            return Math.max(0, (System.currentTimeMillis() - startMillis) / 1000);
        }

        public int[] pops() {
            return pops;
        }

        public Location spawn(int index) {
            return spawns[index];
        }

        public boolean isCircle() {
            return circle;
        }

        public double zoneRadius() {
            return zoneRadius;
        }

        /** Inside the current safe zone (square or circle) of this arena. */
        public boolean inSafeZone(Location loc) {
            if (loc.getWorld() == null || !loc.getWorld().equals(world)) return false;
            double dx = loc.getX() - zoneX;
            double dz = loc.getZ() - zoneZ;
            if (circle) return dx * dx + dz * dz <= zoneRadius * zoneRadius;
            return Math.abs(dx) <= zoneRadius && Math.abs(dz) <= zoneRadius;
        }

        public boolean contains(Location loc) {
            if (loc.getWorld() == null || !loc.getWorld().equals(world)) return false;
            return Math.abs(loc.getX() - center.getX()) <= half && Math.abs(loc.getZ() - center.getZ()) <= half;
        }

        /** Remembers what a block looked like before the first change in this duel. */
        public void record(Block block) {
            original.putIfAbsent(pack(block.getX(), block.getY(), block.getZ()), block.getBlockData());
        }

        public void recordState(Location loc, BlockData data) {
            original.putIfAbsent(pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()), data);
        }

        public boolean isProtected(Block block) {
            return protectedBlocks.contains(pack(block.getX(), block.getY(), block.getZ()));
        }
    }

    public record Spot(World world, Location center, Location a, Location b) {
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) ((y + 2048) & 0xFFF);
    }

    private static int unpackX(long k) {
        return (int) (k >> 38);
    }

    private static int unpackZ(long k) {
        return (int) ((k << 26) >> 38);
    }

    private static int unpackY(long k) {
        return (int) (k & 0xFFF) - 2048;
    }

    private final VPerPlayerKit plugin;
    private final Map<UUID, Duel> byPlayer = new HashMap<>();
    private final List<Duel> active = new ArrayList<>();
    private final Set<UUID> preparing = new HashSet<>();

    public DuelManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lookups

    public boolean isBusy(UUID uuid) {
        return preparing.contains(uuid) || byPlayer.containsKey(uuid);
    }

    public Duel duelOf(UUID uuid) {
        return byPlayer.get(uuid);
    }

    public Duel duelAt(Location loc) {
        for (Duel d : active) {
            if (d.contains(loc)) return d;
        }
        return null;
    }

    public List<Duel> activeDuels() {
        return active;
    }

    private int arenaSize() {
        return Math.max(50, plugin.getConfig().getInt("rtpq.arena-size", 250));
    }

    /** True when the spot is too close to a running duel, Gladiator or FFA arena. */
    public boolean tooClose(World world, int x, int z) {
        return tooClose(world, x, z, arenaSize() / 2);
    }

    /** {@code half} is the half size of the arena that would be created here. */
    public boolean tooClose(World world, int x, int z, int half) {
        int min = plugin.getConfig().getInt("rtpq.min-arena-distance", 400);
        for (Duel d : active) {
            if (d.world == null || !d.world.equals(world)) continue;
            int needed = Math.max(min, d.half + half + 50);
            if (Math.max(Math.abs(d.center.getBlockX() - x), Math.abs(d.center.getBlockZ() - z)) < needed) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ start

    public void startDuel(QueueManager.Entry a, QueueManager.Entry b) {
        Player pa = Bukkit.getPlayer(a.uuid);
        Player pb = Bukkit.getPlayer(b.uuid);
        if (pa == null || pb == null) return;

        preparing.add(a.uuid);
        preparing.add(b.uuid);
        String biome = !a.biome.equals("auto") ? a.biome : b.biome;
        boolean test = a.test || b.test;
        plugin.msg().send(pa, "match-found", "{opponent}", b.name);
        plugin.msg().send(pb, "match-found", "{opponent}", a.name);

        World world = pickWorld(biome);
        if (world == null) {
            abort(a, b, "rtpq-no-world");
            return;
        }

        plugin.async(() -> new byte[][] {
                plugin.storage().get(a.uuid.toString(), "kit", String.valueOf(a.kitId)),
                plugin.storage().get(b.uuid.toString(), "kit", String.valueOf(b.kitId))
        }, data -> {
            ItemStack[] kitA = ItemUtil.fromBytes(data[0]);
            ItemStack[] kitB = ItemUtil.fromBytes(data[1]);
            if (kitA == null || kitB == null) {
                abort(a, b, "queue-no-kit");
                return;
            }
            findArena(world, biome, 40, arenaSize() / 2, spot -> begin(a, b, kitA, kitB, test, spot),
                    () -> abort(a, b, "rtpq-no-arena"));
        });
    }

    private void abort(QueueManager.Entry a, QueueManager.Entry b, String messageKey) {
        preparing.remove(a.uuid);
        preparing.remove(b.uuid);
        for (QueueManager.Entry e : List.of(a, b)) {
            Player p = Bukkit.getPlayer(e.uuid);
            if (p != null) plugin.msg().send(p, messageKey);
        }
    }

    public World pickWorld(String biome) {
        return plugin.locator().worldFor(biome);
    }

    private void begin(QueueManager.Entry a, QueueManager.Entry b, ItemStack[] kitA, ItemStack[] kitB,
                       boolean test, Spot spot) {
        preparing.remove(a.uuid);
        preparing.remove(b.uuid);
        Player pa = Bukkit.getPlayer(a.uuid);
        Player pb = Bukkit.getPlayer(b.uuid);
        if (pa == null || pb == null) {
            if (pa != null) plugin.msg().send(pa, "opponent-left");
            if (pb != null) plugin.msg().send(pb, "opponent-left");
            return;
        }

        Duel d = new Duel();
        d.ids[0] = a.uuid;
        d.ids[1] = b.uuid;
        d.names[0] = a.name;
        d.names[1] = b.name;
        d.kitIds[0] = a.kitId;
        d.kitIds[1] = b.kitId;
        d.biomeChoice[0] = a.biome;
        d.biomeChoice[1] = b.biome;
        d.winner = -1;
        d.kits[0] = kitA;
        d.kits[1] = kitB;
        d.testing[0] = a.test;
        d.testing[1] = b.test;
        d.target = test ? plugin.getConfig().getInt("rtpq.test-target", 3) : plugin.getConfig().getInt("rtpq.match-target", 2);
        d.half = arenaSize() / 2;
        d.world = spot.world();
        d.center = spot.center();
        d.spawns[0] = spot.a();
        d.spawns[1] = spot.b();
        d.startMillis = System.currentTimeMillis();
        active.add(d);
        byPlayer.put(a.uuid, d);
        byPlayer.put(b.uuid, d);
        plugin.markInMatch(a.uuid, true);
        plugin.markInMatch(b.uuid, true);

        initZone(d);

        String tierTag = test ? "tier-test-start" : "match-start";
        plugin.msg().send(pa, tierTag, "{opponent}", b.name, "{target}", String.valueOf(d.target));
        plugin.msg().send(pb, tierTag, "{opponent}", a.name, "{target}", String.valueOf(d.target));

        startRound(d);
        startZoneTask(d);
        startZone(d, () -> duelPlayers(d));
        plugin.scorebar().show(d);

        // high tier players joining a duel are announced with a [Spect] button
        boolean aHigher = a.stats.tier >= b.stats.tier;
        plugin.announcements().highTier("duel", aHigher ? a.name : b.name, aHigher ? a.stats.tier : b.stats.tier,
                List.of(aHigher ? b.name : a.name), 1);
    }

    // ------------------------------------------------------------------ arena search

    /** Loads the chunk and reports a safe standing spot (or null when water, lava, cactus...). */
    public void surfaceAt(World world, int x, int z, Consumer<Location> done) {
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            boolean unsafe = block.isLiquid() || type == Material.MAGMA_BLOCK || type == Material.CACTUS
                    || type == Material.FIRE || type == Material.POWDER_SNOW || type == Material.AIR
                    || type == Material.CAMPFIRE || type == Material.SWEET_BERRY_BUSH;
            done.accept(unsafe ? null : new Location(world, x + 0.5, y + 1, z + 0.5));
        }));
    }

    public void findArena(World world, int attempts, Consumer<Spot> done, Runnable fail) {
        findArena(world, attempts, arenaSize() / 2, done, fail);
    }

    /** {@code half} = half size of the arena (FFA uses its own, bigger one). */
    public void findArena(World world, int attempts, int half, Consumer<Spot> done, Runnable fail) {
        findArena(world, null, attempts, half, done, fail);
    }

    /** {@code biome} = plains/desert/... (see rtp.biomes), null or "auto" = anywhere in the world. */
    public void findArena(World world, String biome, int attempts, int half, Consumer<Spot> done, Runnable fail) {
        if (attempts <= 0) {
            fail.run();
            return;
        }
        int radius = Math.max(500, plugin.getConfig().getInt("rtpq.rtp-radius", 5000));
        int[] picked = plugin.locator().pick(world, biome, radius);
        if (picked == null) {                                       // biome not found around that random point
            findArena(world, biome, attempts - 1, half, done, fail);
            return;
        }
        int cx = picked[0];
        int cz = picked[1];
        if (tooClose(world, cx, cz, half)) {
            findArena(world, biome, attempts - 1, half, done, fail);
            return;
        }
        int d = Math.max(10, plugin.getConfig().getInt("rtpq.spawn-distance", 25));
        Runnable retry = () -> findArena(world, biome, attempts - 1, half, done, fail);

        surfaceAt(world, cx - d, cz, a -> {
            if (a == null) {
                retry.run();
                return;
            }
            surfaceAt(world, cx + d, cz, b -> {
                if (b == null) {
                    retry.run();
                    return;
                }
                surfaceAt(world, cx, cz, c -> {
                    if (c == null || !plugin.locator().matches(c, biome)) {
                        retry.run();
                        return;
                    }
                    a.setYaw(-90f);   // faces east
                    b.setYaw(90f);    // faces west
                    done.accept(new Spot(world, c, a, b));
                });
            });
        });
    }

    // ------------------------------------------------------------------ rounds

    private Player player(Duel d, int index) {
        if (d.ids[index] == null) return null;    // arena holders (Gladiator) have no duel players
        Player p = Bukkit.getPlayer(d.ids[index]);
        return (p != null && p.isOnline()) ? p : null;
    }

    private void startRound(Duel d) {
        if (d.state == State.ENDED) return;
        d.state = State.COUNTDOWN;
        for (int i = 0; i < 2; i++) {
            Player p = player(d, i);
            if (p == null) continue;
            for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
            KitService.applyKit(p, d.kits[i], true);
            plugin.kits().heal(p);
            p.teleport(d.spawns[i]);
        }
        final int[] counter = {plugin.getConfig().getInt("rtpq.countdown-seconds", 3)};
        d.roundTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (d.state == State.ENDED) {
                    cancel();
                    return;
                }
                if (counter[0] > 0) {
                    showTitleBoth(d, "<yellow>" + counter[0], "<gray>First to " + d.target);
                    counter[0]--;
                } else {
                    d.state = State.ACTIVE;
                    showTitleBoth(d, "<red><bold>FIGHT", "");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 10L, 20L);
    }

    private void showTitleBoth(Duel d, String title, String subtitle) {
        for (int i = 0; i < 2; i++) {
            Player p = player(d, i);
            if (p == null) continue;
            p.showTitle(Title.title(dev.samar.vperplayerkit.util.Text.mm(title),
                    dev.samar.vperplayerkit.util.Text.mm(subtitle)));
        }
    }

    /** Called by the listener when a player takes lethal damage (the death itself is cancelled). */
    public void onKill(Duel d, Player victim) {
        if (d.state != State.ACTIVE) return;
        int v = d.indexOf(victim.getUniqueId());
        if (v < 0) return;
        int k = 1 - v;
        d.state = State.BETWEEN;
        d.score[k]++;
        plugin.scorebar().update(d);

        for (int i = 0; i < 2; i++) {
            Player p = player(d, i);
            if (p != null) {
                plugin.msg().send(p, "round-scored", "{player}", d.names[k],
                        "{a}", String.valueOf(d.score[0]), "{b}", String.valueOf(d.score[1]));
            }
        }
        if (d.score[k] >= d.target) {
            finish(d, k, false);
        } else {
            d.roundTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (d.state != State.BETWEEN) return;
                if (plugin.getConfig().getBoolean("rtpq.rebuild.between-rounds", true)) {
                    // rebuild: everything that was placed, broken or blown up is put back before the next round
                    for (int i = 0; i < 2; i++) {
                        Player p = player(d, i);
                        if (p != null) plugin.msg().send(p, "round-rebuilding");
                    }
                    rebuild(d, () -> {
                        if (d.state == State.BETWEEN) startRound(d);
                    });
                } else {
                    startRound(d);
                }
            }, 60L);
        }
    }

    public void forfeit(Duel d, UUID quitter) {
        if (d.state == State.ENDED) return;
        int q = d.indexOf(quitter);
        if (q < 0) return;
        finish(d, 1 - q, true);
    }

    // ------------------------------------------------------------------ danger zone

    private void startZoneTask(Duel d) {
        int grace = plugin.getConfig().getInt("rtpq.danger-zone.grace-seconds", 3);
        double damage = plugin.getConfig().getDouble("rtpq.danger-zone.damage", 10.0);   // 10 = 5 hearts per second
        d.zoneTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (d.state == State.ENDED) {
                    cancel();
                    return;
                }
                if (d.state != State.ACTIVE) return;
                for (int i = 0; i < 2; i++) {
                    Player p = player(d, i);
                    if (p == null) continue;
                    if (!p.getWorld().equals(d.world) || d.inSafeZone(p.getLocation())) {
                        d.outside[i] = 0;
                        continue;
                    }
                    d.outside[i]++;
                    if (d.outside[i] <= grace) {
                        plugin.msg().send(p, "danger-warning");
                        p.showTitle(Title.title(dev.samar.vperplayerkit.util.Text.mm("<red><bold>DANGER ZONE"),
                                dev.samar.vperplayerkit.util.Text.mm("<gray>Return to the arena!")));
                    } else {
                        p.damage(damage);
                        p.sendActionBar(plugin.msg().get("danger-damage"));
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /** Red beacon beams at the corners and edge midpoints so everyone can see the border. */
    private void placeMarkers(Duel d) {
        int h = d.half;
        int[][] points = {{-h, -h}, {-h, 0}, {-h, h}, {0, -h}, {0, h}, {h, -h}, {h, 0}, {h, h}};
        for (int[] point : points) {
            int x = d.center.getBlockX() + point[0];
            int z = d.center.getBlockZ() + point[1];
            surfaceAt(d.world, x, z, loc -> {
                if (loc == null || d.state == State.ENDED) return;
                Block base = loc.getBlock();
                Block glass = base.getRelative(0, 1, 0);
                d.record(base);
                d.record(glass);
                d.protectedBlocks.add(pack(base.getX(), base.getY(), base.getZ()));
                d.protectedBlocks.add(pack(glass.getX(), glass.getY(), glass.getZ()));
                base.setType(Material.BEACON, false);
                glass.setType(Material.RED_STAINED_GLASS, false);
            });
        }
    }

    // ------------------------------------------------------------------ finish

    private void finish(Duel d, int winner, boolean forfeit) {
        if (d.state == State.ENDED) return;
        d.state = State.ENDED;
        d.winner = winner;
        if (d.zoneTask != null) d.zoneTask.cancel();
        if (d.roundTask != null) d.roundTask.cancel();
        long seconds = Math.max(1, (System.currentTimeMillis() - d.startMillis) / 1000);
        int loser = 1 - winner;

        var cfg = plugin.getConfig();
        TierLogic.Params params = new TierLogic.Params(
                cfg.getDouble("tier.progress.base", 15),
                cfg.getDouble("tier.progress.per-tier-difference", 3),
                cfg.getDouble("tier.progress.per-opponent-totem-pop", 1),
                cfg.getDouble("tier.progress.opponent-totem-pop-max", 5),
                cfg.getDouble("tier.progress.per-own-totem-pop-penalty", 0.5),
                cfg.getDouble("tier.progress.per-kill", 1.5),
                cfg.getDouble("tier.progress.min-gain", 3),
                cfg.getDouble("tier.progress.max-gain", 40));

        final int[] scores = d.score.clone();
        final int[] pops = d.pops.clone();
        final boolean[] testing = d.testing.clone();
        final long now = System.currentTimeMillis();

        plugin.async(() -> {
            PlayerStats[] st = {
                    plugin.storage().loadStats(d.ids[0], d.names[0]),
                    plugin.storage().loadStats(d.ids[1], d.names[1])
            };
            int[] before = {st[0].tier, st[1].tier};
            double gain = TierLogic.gain(params, Tier.fromIndex(st[winner].tier), Tier.fromIndex(st[loser].tier),
                    pops[loser], pops[winner], scores[winner], seconds);
            TierLogic.Outcome[] out = new TierLogic.Outcome[2];
            for (int i = 0; i < 2; i++) {
                out[i] = TierLogic.apply(st[i], i == winner, testing[i], scores[i], i == winner ? gain : 0);
                plugin.storage().saveStats(st[i]);
                plugin.storage().addHistory(d.ids[i], new HistoryRow(now, scores[i], scores[1 - i], before[i],
                        before[1 - i], d.names[i], d.names[1 - i], testing[i]));
            }
            return out;
        }, out -> {
            announce(d, winner, forfeit, out);
            plugin.cache().refresh(d.ids[0], d.names[0]);
            plugin.cache().refresh(d.ids[1], d.names[1]);
        });

        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(d), 100L);
    }

    private void announce(Duel d, int winner, boolean forfeit, TierLogic.Outcome[] out) {
        // "<player> Won" above the hotbar (players and spectators) and a chat broadcast
        List<UUID> viewers = new ArrayList<>(List.of(d.ids[0], d.ids[1]));
        viewers.addAll(plugin.spectators().spectatorsOf(d));
        plugin.announcements().winBar(viewers, d.names[winner]);
        if (plugin.announcements().broadcastEnabled("duel")) {
            Bukkit.broadcast(plugin.msg().get("duel-win-broadcast", "{winner}", d.names[winner],
                    "{loser}", d.names[1 - winner], "{a}", String.valueOf(d.score[winner]),
                    "{b}", String.valueOf(d.score[1 - winner])));
        }
        for (int i = 0; i < 2; i++) {
            Player p = player(d, i);
            TierLogic.Outcome o = out[i];
            if (p == null) continue;
            boolean won = i == winner;
            p.showTitle(Title.title(
                    dev.samar.vperplayerkit.util.Text.mm(won ? "<green><bold>VICTORY" : "<red><bold>DEFEAT"),
                    dev.samar.vperplayerkit.util.Text.mm("<white>" + d.score[i] + " : " + d.score[1 - i])));
            plugin.msg().send(p, forfeit ? "match-over-forfeit" : "match-over", "{a}", String.valueOf(d.score[i]),
                    "{b}", String.valueOf(d.score[1 - i]), "{opponent}", d.names[1 - i]);

            if (o.testing && won && o.promoted != null) {
                plugin.msg().send(p, "tier-promoted", "{tier}", o.promoted.colored());
                Bukkit.broadcast(plugin.msg().get("tier-promoted-broadcast", "{player}", d.names[i],
                        "{tier}", o.promoted.colored()));
                plugin.discord().congratulate(d.ids[i], d.names[i], o.promoted);
                publishAutoResult(d, i, true, Tier.fromIndex(o.promoted.ordinal() - 1), o.promoted, forfeit);
            } else if (o.testing && !won) {
                if (o.chancesReset) {
                    plugin.msg().send(p, "tier-test-failed-reset", "{progress}", fmt(o.progress));
                    publishAutoResult(d, i, false, o.current, o.current, forfeit);
                } else {
                    plugin.msg().send(p, "tier-test-failed", "{chances}", String.valueOf(o.chancesLeft));
                }
            } else if (won && o.gain > 0) {
                plugin.msg().send(p, "tier-progress", "{gain}", fmt(o.gain), "{progress}", fmt(o.progress));
                if (o.readyForTest) plugin.msg().send(p, "tier-test-ready");
            }
        }
    }

    /** Discord result for a tier test that ended a promotion or used up the last chance. */
    private void publishAutoResult(Duel d, int i, boolean won, Tier previous, Tier earned, boolean forfeit) {
        if (!plugin.getConfig().getBoolean(won ? "results.post-auto-promotions" : "results.post-auto-failures", true)) return;
        String score = d.score[i] + "-" + d.score[1 - i];
        String fight = (won ? "Won " : "Lost ") + score + " vs. " + d.names[1 - i]
                + (forfeit && !won ? " (DQ at " + score + ")" : "");
        plugin.results().publish(d.ids[i], d.names[i], previous, earned,
                plugin.getConfig().getString("results.auto-tester", "Auto Tester"), null, List.of(fight));
    }

    public static String fmt(double value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(Math.round(value * 10.0) / 10.0);
    }

    // ------------------------------------------------------------------ zone (random square/circle, shrinks and moves)

    private boolean randomCircle(String path) {
        List<String> shapes = plugin.getConfig().getStringList(path + ".shapes");
        if (shapes.isEmpty()) shapes = List.of("square", "circle");
        return shapes.get(ThreadLocalRandom.current().nextInt(shapes.size())).equalsIgnoreCase("circle");
    }

    private void initZone(Duel d) {
        initZone(d, "rtpq.zone");
    }

    private void initZone(Duel d, String path) {
        d.zoneX = d.center.getX();
        d.zoneZ = d.center.getZ();
        d.zoneRadius = d.half;
        d.circle = randomCircle(path);
    }

    /** Starts the shrinking zone (borders, beams, particles) for a duel or Gladiator arena. */
    public void startZone(Duel d, java.util.function.Supplier<java.util.Collection<Player>> players) {
        startZone(d, players, "rtpq.zone");
    }

    /** {@code path} = config section with the zone settings (rtpq.zone or ffa.zone). */
    public void startZone(Duel d, java.util.function.Supplier<java.util.Collection<Player>> players, String path) {
        d.zone = new ZoneController(plugin, d, players, path);
        d.zone.start();
    }

    private java.util.Collection<Player> duelPlayers(Duel d) {
        List<Player> out = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Player p = player(d, i);
            if (p != null) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ shared arena API (used by Gladiator)

    /** Registers an arena (reserves the area, restores blocks afterwards) without a 1v1 attached. */
    public Duel createArena(Spot spot) {
        return createArena(spot, arenaSize() / 2, "rtpq.zone");
    }

    public Duel createArena(Spot spot, int half, String zonePath) {
        Duel d = new Duel();
        d.world = spot.world();
        d.center = spot.center();
        d.half = half;
        d.state = State.ACTIVE;
        d.startMillis = System.currentTimeMillis();
        active.add(d);
        initZone(d, zonePath);
        return d;
    }

    /** Removes drops/crystals, restores every recorded block and frees the area for new arenas. */
    public void releaseArena(Duel d) {
        d.state = State.ENDED;
        if (d.zone != null) d.zone.stop();
        removeEntities(d);
        restoreBlocks(d, () -> active.remove(d));
    }

    // ------------------------------------------------------------------ cleanup / restore

    private void cleanup(Duel d) {
        plugin.scorebar().hide(d);
        plugin.spectators().matchEnded(d);
        if (d.zone != null) d.zone.stop();
        removeEntities(d);
        for (int i = 0; i < 2; i++) {
            Player p = player(d, i);
            if (p != null && !d.left[i]) plugin.sendToLobby(p);
            byPlayer.remove(d.ids[i]);
        }
        // The old arena regenerates for other players; the winner can go straight into the next match with a
        // restored kit, full health and a brand new location.
        restoreBlocks(d, () -> active.remove(d));
        if (d.winner >= 0 && plugin.getConfig().getBoolean("rtpq.winner-auto-requeue", true)) {
            Player w = player(d, d.winner);
            if (w != null && !d.left[d.winner]) {
                plugin.msg().send(w, "winner-requeued");
                plugin.queue().join(w, d.biomeChoice[d.winner], "kit:" + d.kitIds[d.winner]);
            }
        }
        // keep the arena reserved until blocks are restored, see restoreBlocks
    }

    private void removeEntities(Duel d) {
        BoundingBox box = new BoundingBox(d.center.getX() - d.half, d.world.getMinHeight(), d.center.getZ() - d.half,
                d.center.getX() + d.half, d.world.getMaxHeight(), d.center.getZ() + d.half);
        for (Entity e : d.world.getNearbyEntities(box)) {
            if (e instanceof Item || e instanceof ExperienceOrb || e instanceof EnderCrystal
                    || e instanceof Projectile || e instanceof AreaEffectCloud || e instanceof TNTPrimed
                    || e instanceof FallingBlock) {
                e.remove();
            }
        }
    }

    /** Puts every recorded block back, removes crystals/drops and forgets the records (they start fresh for the next round). */
    private void rebuild(Duel d, Runnable after) {
        removeEntities(d);
        restoreBlocks(d, () -> {
            d.original.clear();
            after.run();
        });
    }

    private void restoreBlocks(Duel d, Runnable done) {
        Map<Long, List<Long>> byChunk = new HashMap<>();
        for (Long key : d.original.keySet()) {
            long chunkKey = ((long) (unpackX(key) >> 4) << 32) | ((unpackZ(key) >> 4) & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(key);
        }
        if (byChunk.isEmpty()) {
            done.run();
            return;
        }
        final int[] remaining = {byChunk.size()};
        for (Map.Entry<Long, List<Long>> entry : byChunk.entrySet()) {
            int chunkX = (int) (entry.getKey() >> 32);
            int chunkZ = (int) (long) entry.getKey();
            d.world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
                for (Long key : entry.getValue()) {
                    d.world.getBlockAt(unpackX(key), unpackY(key), unpackZ(key)).setBlockData(d.original.get(key), false);
                }
                if (--remaining[0] == 0) done.run();
            }));
        }
    }

    /** /leave and disconnecting: forfeits a running duel and sends the player to the lobby right away. */
    public boolean leave(Player player) {
        UUID id = player.getUniqueId();
        Duel d = byPlayer.get(id);
        if (d == null) return false;
        int index = d.indexOf(id);
        if (index >= 0) d.left[index] = true;
        plugin.scorebar().hideFor(player);
        if (d.state != State.ENDED) forfeit(d, id);
        byPlayer.remove(id);
        plugin.sendToLobby(player);
        return true;
    }

    public void handleQuit(Player player) {
        preparing.remove(player.getUniqueId());
        leave(player);
    }

    /** /regear inside a duel gives back the kit of this duel. */
    public boolean regear(Player player) {
        Duel d = byPlayer.get(player.getUniqueId());
        if (d == null) return false;
        int index = d.indexOf(player.getUniqueId());
        if (index < 0 || d.state == State.ENDED) return false;
        KitService.applyKit(player, d.kits[index], false);
        plugin.msg().send(player, "regeared-match");
        return true;
    }

    /** Safety net on shutdown: put everyone back and restore arenas. */
    public void shutdown() {
        for (Duel d : new ArrayList<>(active)) {
            d.state = State.ENDED;
            if (d.zone != null) d.zone.stop();
            removeEntities(d);
            for (Map.Entry<Long, BlockData> e : d.original.entrySet()) {
                long key = e.getKey();
                d.world.getBlockAt(unpackX(key), unpackY(key), unpackZ(key)).setBlockData(e.getValue(), false);
            }
            for (int i = 0; i < 2; i++) {
                Player p = player(d, i);
                if (p != null) plugin.sendToLobby(p);
            }
        }
        active.clear();
        byPlayer.clear();
    }

    public boolean isRtpWorld(World world) {
        return plugin.locator().isRtpWorld(world);
    }
}
