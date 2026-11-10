package dev.samar.vperplayerkit.ffa;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.kit.KitService;
import dev.samar.vperplayerkit.rtpq.DuelManager;
import dev.samar.vperplayerkit.util.ItemUtil;
import dev.samar.vperplayerkit.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FFA RTP queue: players queue with /ffartpq, get spawned together on the spawn blocks of an admin-made arena
 * and fight, last player standing wins. Arenas are created with /ffartp wand.
 */
public final class FfaManager {

    /** An arena: the blocks players stand on when the FFA starts. */
    public record Arena(String name, String world, List<int[]> spawns) {
    }

    public static final class Match {
        public enum State { COUNTDOWN, ACTIVE, ENDED }

        final Arena arena;
        final World world;
        final BoundingBox region;
        final Set<UUID> players = new LinkedHashSet<>();
        final Set<UUID> alive = new HashSet<>();
        final Map<UUID, String> names = new HashMap<>();
        final Map<UUID, ItemStack[]> kits = new HashMap<>();
        final Map<UUID, Integer> outside = new HashMap<>();
        DuelManager.Duel holder;          // rtp mode: the arena (zone, block restore)
        boolean rtp;
        boolean event;                    // started by /event start
        State state = State.COUNTDOWN;
        long startMillis;
        BukkitTask task;
        BukkitTask zoneTask;

        Match(Arena arena, World world, BoundingBox region) {
            this.arena = arena;
            this.world = world;
            this.region = region;
        }

        public State state() {
            return state;
        }

        public String arenaName() {
            return arena.name();
        }

        public int aliveCount() {
            return alive.size();
        }

        public int playerCount() {
            return players.size();
        }

        public boolean isAlive(UUID id) {
            return alive.contains(id);
        }

        public boolean contains(Location loc) {
            if (holder != null) return holder.contains(loc);
            return region != null && loc.getWorld() != null && loc.getWorld().equals(world)
                    && region.contains(loc.getX(), loc.getY(), loc.getZ());
        }
    }

    private final VPerPlayerKit plugin;
    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Map<UUID, Match> byPlayer = new HashMap<>();
    private final List<Match> matches = new ArrayList<>();
    private final Set<String> arenasInUse = new HashSet<>();
    private boolean starting = false;
    private int countdown = -1;
    private long nextStart = 0;           // scheduled FFA: when the next round begins

    private final NamespacedKey wandKey;
    private final Map<UUID, List<Location>> selections = new HashMap<>();
    private final Set<UUID> awaitingName = new HashSet<>();

    public FfaManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "ffa_wand");
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L);
        new BukkitRunnable() {
            @Override
            public void run() {
                showSelections();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    // ------------------------------------------------------------------ lookups

    public boolean isBusy(UUID id) {
        return queue.contains(id) || byPlayer.containsKey(id);
    }

    public boolean isQueued(UUID id) {
        return queue.contains(id);
    }

    public int queueSize() {
        return queue.size();
    }

    public Match matchOf(UUID id) {
        return byPlayer.get(id);
    }

    public boolean fighting(UUID id) {
        return byPlayer.containsKey(id);
    }

    public List<Match> activeMatches() {
        return matches;
    }

    public Match matchAt(Location loc) {
        for (Match m : matches) {
            if (m.contains(loc)) return m;
        }
        return null;
    }

    // ------------------------------------------------------------------ arenas (stored in data.yml)

    public List<Arena> arenas() {
        List<Arena> out = new ArrayList<>();
        ConfigurationSection section = plugin.data().yml().getConfigurationSection("ffa.arenas");
        if (section == null) return out;
        for (String name : section.getKeys(false)) {
            Arena arena = arena(name);
            if (arena != null) out.add(arena);
        }
        return out;
    }

    public Arena arena(String name) {
        String path = "ffa.arenas." + name;
        if (!plugin.data().yml().contains(path + ".world")) return null;
        List<int[]> spawns = new ArrayList<>();
        for (String raw : plugin.data().yml().getStringList(path + ".spawns")) {
            String[] parts = raw.split(",");
            if (parts.length != 3) continue;
            try {
                spawns.add(new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim())});
            } catch (NumberFormatException ignored) {
                // bad entry
            }
        }
        return new Arena(name, plugin.data().yml().getString(path + ".world"), spawns);
    }

    // ------------------------------------------------------------------ admin wand

    public ItemStack wand() {
        ItemStack item = new ItemStack(Material.GOLDEN_SHOVEL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.item("<gold>FFA Spawn Wand"));
        meta.lore(List.of(
                Text.item("<gray>Right click a block: add/remove a spawn block"),
                Text.item("<gray>Left click a block: remove it"),
                Text.item("<gray>Shift + right click: finish and name the arena")));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, "ffa");
        item.setItemMeta(meta);
        return item;
    }

    public boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.STRING);
    }

    public int selectionSize(UUID admin) {
        return selections.getOrDefault(admin, List.of()).size();
    }

    /** Right click: select a block, or deselect it when it is already selected. */
    public void toggle(Player admin, Block block) {
        List<Location> list = selections.computeIfAbsent(admin.getUniqueId(), k -> new ArrayList<>());
        if (!list.isEmpty() && !list.get(0).getWorld().equals(block.getWorld())) {
            plugin.msg().send(admin, "ffa-bad-world");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Location l = list.get(i);
            if (l.getBlockX() == block.getX() && l.getBlockY() == block.getY() && l.getBlockZ() == block.getZ()) {
                list.remove(i);
                plugin.msg().send(admin, "ffa-point-removed", "{n}", String.valueOf(list.size()));
                return;
            }
        }
        list.add(block.getLocation());
        plugin.msg().send(admin, "ffa-point-added", "{n}", String.valueOf(list.size()),
                "{x}", String.valueOf(block.getX()), "{y}", String.valueOf(block.getY()), "{z}", String.valueOf(block.getZ()));
    }

    /** Left click: only removes. */
    public void remove(Player admin, Block block) {
        List<Location> list = selections.get(admin.getUniqueId());
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            Location l = list.get(i);
            if (l.getBlockX() == block.getX() && l.getBlockY() == block.getY() && l.getBlockZ() == block.getZ()) {
                list.remove(i);
                plugin.msg().send(admin, "ffa-point-removed", "{n}", String.valueOf(list.size()));
                return;
            }
        }
    }

    /** Shift + right click: ask for the arena name in chat. */
    public void promptName(Player admin) {
        if (selectionSize(admin.getUniqueId()) == 0) {
            plugin.msg().send(admin, "ffa-need-points");
            return;
        }
        awaitingName.add(admin.getUniqueId());
        plugin.msg().send(admin, "ffa-ask-name", "{n}", String.valueOf(selectionSize(admin.getUniqueId())));
    }

    public boolean isAwaitingName(UUID id) {
        return awaitingName.contains(id);
    }

    public void handleName(Player admin, String text) {
        awaitingName.remove(admin.getUniqueId());
        if (text.trim().equalsIgnoreCase("cancel")) {
            plugin.msg().send(admin, "ffa-name-cancelled");
            return;
        }
        createArena(admin, text.trim());
    }

    public void createArena(Player admin, String rawName) {
        String name = KitService.sanitizeName(rawName);
        if (name == null) {
            plugin.msg().send(admin, "bad-name");
            return;
        }
        List<Location> list = selections.get(admin.getUniqueId());
        if (list == null || list.isEmpty()) {
            plugin.msg().send(admin, "ffa-need-points");
            return;
        }
        if (arena(name) != null) {
            plugin.msg().send(admin, "ffa-exists", "{name}", name);
            return;
        }
        List<String> spawns = new ArrayList<>();
        for (Location l : list) spawns.add(l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
        plugin.data().yml().set("ffa.arenas." + name + ".world", list.get(0).getWorld().getName());
        plugin.data().yml().set("ffa.arenas." + name + ".spawns", spawns);
        plugin.data().save();
        selections.remove(admin.getUniqueId());
        plugin.msg().send(admin, "ffa-created", "{name}", name, "{count}", String.valueOf(spawns.size()));
    }

    public void deleteArena(Player admin, String rawName) {
        String name = rawName == null ? null : rawName.toLowerCase();
        if (name == null || arena(name) == null) {
            plugin.msg().send(admin, "ffa-not-found", "{name}", String.valueOf(rawName));
            return;
        }
        plugin.data().clear("ffa.arenas." + name);
        plugin.msg().send(admin, "ffa-deleted", "{name}", name);
    }

    /** Particles on the selected blocks, only visible to the admin holding the wand. */
    private void showSelections() {
        for (Map.Entry<UUID, List<Location>> entry : selections.entrySet()) {
            Player admin = Bukkit.getPlayer(entry.getKey());
            if (admin == null || !isWand(admin.getInventory().getItemInMainHand())) continue;
            for (Location l : entry.getValue()) {
                if (!l.getWorld().equals(admin.getWorld())) continue;
                admin.spawnParticle(Particle.END_ROD, l.clone().add(0.5, 1.2, 0.5), 2, 0.2, 0.2, 0.2, 0.0);
            }
        }
    }

    // ------------------------------------------------------------------ queue

    private static Integer parse(byte[] data) {
        if (data == null) return null;
        try {
            return Integer.parseInt(new String(data, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public void join(Player player) {
        UUID id = player.getUniqueId();
        if (isBusy(id) || plugin.duels().isBusy(id) || plugin.gladiator().isBusy(id) || plugin.queue().isQueued(id)
                || plugin.inMatch(player) || plugin.busy(id)) {
            plugin.msg().send(player, "ffa-busy");
            return;
        }
        String owner = id.toString();
        plugin.async(() -> {
            Integer kit = parse(plugin.storage().get(owner, "selected", "main"));
            return kit != null && plugin.storage().exists(owner, "kit", String.valueOf(kit));
        }, (Boolean has) -> {
            if (!player.isOnline() || isBusy(id)) return;
            if (!has) {
                plugin.msg().send(player, "ffa-no-kit");
                return;
            }
            queue.add(id);
            plugin.msg().send(player, "ffa-joined", "{count}", String.valueOf(queue.size()));
        });
    }

    /** Leaves the queue or the running FFA. Returns false when the player was in neither. */
    public boolean leave(Player player) {
        UUID id = player.getUniqueId();
        if (queue.remove(id)) {
            plugin.msg().send(player, "ffa-left");
            return true;
        }
        Match m = byPlayer.get(id);
        if (m == null) return false;
        boolean wasAlive = m.alive.remove(id);
        byPlayer.remove(id);
        m.players.remove(id);
        if (wasAlive && m.state != Match.State.ENDED) {
            announce(m, "ffa-eliminated", "{player}", player.getName(), "{left}", String.valueOf(m.alive.size()));
        }
        player.setGameMode(GameMode.SURVIVAL);
        plugin.sendToLobby(player);
        plugin.msg().send(player, "ffa-left");
        checkEnd(m);
        return true;
    }

    public void handleQuit(Player player) {
        UUID id = player.getUniqueId();
        queue.remove(id);
        if (byPlayer.containsKey(id)) leave(player);
    }

    private void tick() {
        queue.removeIf(id -> {
            Player p = Bukkit.getPlayer(id);
            return p == null || !p.isOnline();
        });
        if (starting) return;
        if (plugin.getConfig().getBoolean("ffa.schedule.enabled", true)) {
            scheduledTick();
            return;
        }

        int min = Math.max(2, plugin.getConfig().getInt("ffa.min-players", 2));
        int max = Math.max(min, plugin.getConfig().getInt("ffa.max-players", 20));
        if (queue.size() < min) {
            countdown = -1;
            return;
        }
        int delay = Math.max(1, plugin.getConfig().getInt("ffa.start-delay-seconds", 20));
        if (countdown < 0) countdown = delay;
        else countdown--;
        if (queue.size() >= max) countdown = 0;

        if (countdown > 0 && (countdown == delay || countdown % 10 == 0 || countdown <= 5)) {
            for (UUID id : queue) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) plugin.msg().send(p, "ffa-countdown", "{n}", String.valueOf(countdown),
                        "{count}", String.valueOf(queue.size()));
            }
        }
        if (countdown <= 0) startMatch(max);
    }

    // ------------------------------------------------------------------ scheduled rounds (every ffa.schedule.interval-seconds)

    /** Seconds until the next scheduled FFA, or -1 when the schedule is off. */
    public long secondsUntilNext() {
        if (!plugin.getConfig().getBoolean("ffa.schedule.enabled", true) || nextStart == 0) return -1;
        return Math.max(0, (nextStart - System.currentTimeMillis() + 999) / 1000);
    }

    /** Players standing in the FFA lobby area (set with /ffartp lobby, radius ffa.lobby-radius). */
    private List<Player> lobbyPlayers() {
        List<Player> out = new ArrayList<>();
        Location lobby = plugin.data().getLocation("ffa.lobby");
        if (lobby == null) return out;
        double radius = plugin.getConfig().getDouble("ffa.lobby-radius", 10.0);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(lobby.getWorld()) && p.getLocation().distance(lobby) <= radius) out.add(p);
        }
        return out;
    }

    private void scheduledTick() {
        int interval = Math.max(30, plugin.getConfig().getInt("ffa.schedule.interval-seconds", 120));
        long now = System.currentTimeMillis();
        if (nextStart == 0) nextStart = now + interval * 1000L;
        long left = secondsUntilNext();

        if (left > 0) {
            if (left == 60 || left == 30 || left == 10 || left <= 5) {
                List<Player> audience = new ArrayList<>(lobbyPlayers());
                for (UUID id : queue) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && !audience.contains(p)) audience.add(p);
                }
                for (Player p : audience) {
                    plugin.msg().send(p, "ffa-countdown", "{n}", String.valueOf(left), "{count}", String.valueOf(audience.size()));
                }
            }
            return;
        }

        // time is up: everybody standing in the lobby area is added, then the round starts
        nextStart = now + interval * 1000L;
        for (Player p : lobbyPlayers()) {
            UUID id = p.getUniqueId();
            if (queue.contains(id) || isBusy(id) || plugin.duels().isBusy(id) || plugin.gladiator().isBusy(id)
                    || plugin.queue().isQueued(id) || plugin.inMatch(p)) continue;
            queue.add(id);
        }
        int min = Math.max(2, plugin.getConfig().getInt("ffa.min-players", 2));
        int max = Math.max(min, plugin.getConfig().getInt("ffa.max-players", 20));
        if (queue.size() < min) {
            for (UUID id : queue) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) plugin.msg().send(p, "ffa-not-enough", "{min}", String.valueOf(min),
                        "{minutes}", String.valueOf(Math.max(1, interval / 60)));
            }
            return;
        }
        startMatch(max);
    }

    private Arena pickArena(int wanted) {
        List<Arena> fitting = new ArrayList<>();
        Arena largest = null;
        for (Arena arena : arenas()) {
            if (arenasInUse.contains(arena.name()) || arena.spawns().size() < 2
                    || Bukkit.getWorld(arena.world()) == null) continue;
            if (largest == null || arena.spawns().size() > largest.spawns().size()) largest = arena;
            if (arena.spawns().size() >= wanted) fitting.add(arena);
        }
        if (!fitting.isEmpty()) return fitting.get(ThreadLocalRandom.current().nextInt(fitting.size()));
        return largest;
    }

    private boolean rtpMode() {
        return !"arenas".equalsIgnoreCase(plugin.getConfig().getString("ffa.mode", "rtp"));
    }

    private void startMatch(int max) {
        List<UUID> picked = new ArrayList<>();
        for (UUID id : queue) {
            if (picked.size() >= max) break;
            picked.add(id);
        }
        launch(picked, false);
    }

    private record Loaded(Map<UUID, byte[]> kits, Map<UUID, Integer> tiers) {
    }

    /** Starts a FFA with exactly these players (queue timer, or an admin event). False when it could not start. */
    public boolean launch(List<UUID> players, boolean event) {
        if (starting || players.size() < 2) return false;
        Arena arena = null;
        List<UUID> picked = new ArrayList<>(players);
        if (!rtpMode()) {
            arena = pickArena(picked.size());
            if (arena == null) {
                for (UUID id : picked) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) plugin.msg().send(p, "ffa-no-arena");
                }
                countdown = Math.max(1, plugin.getConfig().getInt("ffa.start-delay-seconds", 20));
                return false;
            }
            if (picked.size() > arena.spawns().size()) picked = new ArrayList<>(picked.subList(0, arena.spawns().size()));
            arenasInUse.add(arena.name());
        }
        queue.removeAll(picked);
        starting = true;
        countdown = -1;
        final Arena fixed = arena;
        final List<UUID> group = picked;

        plugin.async(() -> {
            Map<UUID, byte[]> kits = new HashMap<>();
            Map<UUID, Integer> tiers = new HashMap<>();
            for (UUID id : group) {
                String owner = id.toString();
                Integer kit = parse(plugin.storage().get(owner, "selected", "main"));
                byte[] kitData = kit == null ? null : plugin.storage().get(owner, "kit", String.valueOf(kit));
                if (kitData != null) kits.put(id, kitData);
                tiers.put(id, plugin.storage().loadStats(id, null).tier);
            }
            return new Loaded(kits, tiers);
        }, (Loaded loaded) -> prepare(group, fixed, event, loaded));
        return true;
    }

    private void abort(String messageKey, List<UUID> ready, Arena fixed, boolean event) {
        if (event) plugin.events().startFailed();
        for (UUID id : ready) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (!event) queue.add(id);                    // event players simply stay in the event lobby
            plugin.msg().send(p, messageKey);
        }
        if (fixed != null) arenasInUse.remove(fixed.name());
        starting = false;
    }

    private void prepare(List<UUID> group, Arena fixed, boolean event, Loaded loaded) {
        Map<UUID, ItemStack[]> kits = new HashMap<>();
        List<UUID> ready = new ArrayList<>();
        for (UUID id : group) {
            Player p = Bukkit.getPlayer(id);
            ItemStack[] kit = ItemUtil.fromBytes(loaded.kits().get(id));
            if (p == null || kit == null) {
                if (p != null) plugin.msg().send(p, "ffa-no-kit");
                continue;
            }
            kits.put(id, kit);
            ready.add(id);
        }
        if (ready.size() < 2) {
            abort("ffa-cancelled", ready, fixed, event);
            return;
        }
        if (fixed != null) {
            beginArena(fixed, ready, kits, event, loaded.tiers());
            return;
        }
        String biome = plugin.getConfig().getString("ffa.biome", "auto");
        World world = plugin.duels().pickWorld(biome);
        if (world == null) {
            abort("rtpq-no-world", ready, null, event);
            return;
        }
        int half = Math.max(100, plugin.getConfig().getInt("ffa.zone.radius", 500));
        plugin.duels().findArena(world, biome, 40, half,
                spot -> beginRtp(spot, half, ready, kits, event, loaded.tiers()),
                () -> abort("rtpq-no-arena", ready, null, event));
    }

    /** Admin arena: players stand on the spawn blocks chosen with /ffartp wand. */
    private void beginArena(Arena arena, List<UUID> ready, Map<UUID, ItemStack[]> kits, boolean event,
                            Map<UUID, Integer> tiers) {
        World world = Bukkit.getWorld(arena.world());
        if (world == null) {
            abort("ffa-cancelled", ready, arena, event);
            return;
        }
        int pad = Math.max(0, plugin.getConfig().getInt("ffa.protect-radius", 40));
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        double sumX = 0, sumZ = 0;
        for (int[] s : arena.spawns()) {
            minX = Math.min(minX, s[0]);
            maxX = Math.max(maxX, s[0]);
            minZ = Math.min(minZ, s[2]);
            maxZ = Math.max(maxZ, s[2]);
            sumX += s[0];
            sumZ += s[2];
        }
        double centerX = sumX / arena.spawns().size() + 0.5;
        double centerZ = sumZ / arena.spawns().size() + 0.5;
        BoundingBox region = new BoundingBox(minX - pad, world.getMinHeight(), minZ - pad,
                maxX + pad + 1, world.getMaxHeight(), maxZ + pad + 1);

        Match m = new Match(arena, world, region);
        m.event = event;
        List<int[]> spawns = new ArrayList<>(arena.spawns());
        Collections.shuffle(spawns);
        List<Location> locs = new ArrayList<>();
        for (int i = 0; i < ready.size(); i++) {
            int[] s = spawns.get(i);
            Location loc = new Location(world, s[0] + 0.5, s[1] + 1, s[2] + 0.5);
            loc.setYaw((float) Math.toDegrees(Math.atan2(-(centerX - loc.getX()), centerZ - loc.getZ())));
            locs.add(loc);
        }
        startPlayers(m, ready, kits, locs, tiers);
    }

    /** RTP arena like duels: random spot, big shrinking zone (ffa.zone), blocks restored afterwards. */
    private void beginRtp(DuelManager.Spot spot, int half, List<UUID> ready, Map<UUID, ItemStack[]> kits,
                          boolean event, Map<UUID, Integer> tiers) {
        World world = spot.world();
        DuelManager.Duel holder = plugin.duels().createArena(spot, half, "ffa.zone");
        Match m = new Match(new Arena("rtp", world.getName(), List.of()), world, null);
        m.holder = holder;
        m.rtp = true;
        m.event = event;

        int n = ready.size();
        double ring = Math.min(plugin.getConfig().getDouble("ffa.spawn-radius", 60), half * 0.6);
        double offset = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        Location center = spot.center();
        Location[] locs = new Location[n];
        int[] done = {0};
        for (int i = 0; i < n; i++) {
            final int index = i;
            double angle = offset + i * (Math.PI * 2 / n);
            int x = (int) Math.round(center.getX() + Math.cos(angle) * ring);
            int z = (int) Math.round(center.getZ() + Math.sin(angle) * ring);
            plugin.duels().surfaceAt(world, x, z, loc -> {
                Location spawn = loc != null ? loc : center.clone();
                spawn.setYaw((float) Math.toDegrees(Math.atan2(-(center.getX() - spawn.getX()), center.getZ() - spawn.getZ())));
                locs[index] = spawn;
                if (++done[0] == n) startPlayers(m, ready, kits, new ArrayList<>(Arrays.asList(locs)), tiers);
            });
        }
    }

    private void startPlayers(Match m, List<UUID> ready, Map<UUID, ItemStack[]> kits, List<Location> locs,
                              Map<UUID, Integer> tiers) {
        matches.add(m);
        for (int i = 0; i < ready.size(); i++) {
            UUID id = ready.get(i);
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            m.players.add(id);
            m.alive.add(id);
            plugin.markInMatch(id, true);
            m.names.put(id, p.getName());
            m.kits.put(id, kits.get(id));
            byPlayer.put(id, m);

            for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
            KitService.applyKit(p, kits.get(id), true);
            plugin.kits().heal(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(locs.get(i));
            plugin.msg().send(p, "ffa-start", "{arena}", m.arena.name());
        }
        starting = false;

        // high tier players joining are announced
        UUID top = null;
        int best = -1;
        for (UUID id : m.players) {
            int t = tiers.getOrDefault(id, 0);
            if (t > best) {
                best = t;
                top = id;
            }
        }
        if (top != null) {
            List<String> others = new ArrayList<>();
            for (UUID id : m.players) {
                if (!id.equals(top)) others.add(m.names.getOrDefault(id, "?"));
            }
            plugin.announcements().highTier("ffa", m.names.getOrDefault(top, "?"), best, others, others.size());
        }

        if (m.holder != null) startZone(m);

        final int[] left = {plugin.getConfig().getInt("ffa.countdown-seconds", 5)};
        final int maxSeconds = plugin.getConfig().getInt("ffa.max-duration-seconds", 600);
        m.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (m.state == Match.State.ENDED) {
                    cancel();
                    return;
                }
                if (m.state == Match.State.COUNTDOWN) {
                    if (left[0] > 0) {
                        titleAll(m, "<yellow>" + left[0], "<gray>Last player standing wins");
                        left[0]--;
                    } else {
                        m.state = Match.State.ACTIVE;
                        m.startMillis = System.currentTimeMillis();
                        titleAll(m, "<red><bold>FIGHT", "");
                    }
                } else if (maxSeconds > 0 && System.currentTimeMillis() - m.startMillis > maxSeconds * 1000L) {
                    end(m, null);
                }
            }
        }.runTaskTimer(plugin, 10L, 20L);
    }

    /** Shrinking/moving zone (rtp mode) and the damage outside of it. */
    private void startZone(Match m) {
        plugin.duels().startZone(m.holder, () -> {
            List<Player> online = new ArrayList<>();
            for (UUID id : m.players) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) online.add(p);
            }
            return online;
        }, "ffa.zone");

        int grace = plugin.getConfig().getInt("rtpq.danger-zone.grace-seconds", 3);
        double damage = plugin.getConfig().getDouble("rtpq.danger-zone.damage", 10.0);
        m.zoneTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (m.state == Match.State.ENDED) {
                    cancel();
                    return;
                }
                if (m.state != Match.State.ACTIVE) return;
                for (UUID id : new ArrayList<>(m.alive)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null) continue;
                    if (m.holder.inSafeZone(p.getLocation())) {
                        m.outside.put(id, 0);
                        continue;
                    }
                    int seconds = m.outside.merge(id, 1, Integer::sum);
                    if (seconds <= grace) {
                        plugin.msg().send(p, "danger-warning");
                        p.showTitle(Title.title(Text.mm("<red><bold>DANGER ZONE"), Text.mm("<gray>Return to the arena!")));
                    } else {
                        p.damage(damage);
                        p.sendActionBar(plugin.msg().get("danger-damage"));
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // ------------------------------------------------------------------ fighting

    /** Lethal hit (cancelled by the listener): the player is out and watches as spectator. */
    public void eliminate(Match m, Player player) {
        if (!m.alive.remove(player.getUniqueId())) return;
        announce(m, "ffa-eliminated", "{player}", player.getName(), "{left}", String.valueOf(m.alive.size()));
        player.setGameMode(GameMode.SPECTATOR);
        plugin.kits().heal(player);
        checkEnd(m);
    }

    private void checkEnd(Match m) {
        if (m.state == Match.State.ENDED || m.state == Match.State.COUNTDOWN && m.alive.size() > 1) return;
        if (m.alive.size() > 1) return;
        UUID winner = m.alive.isEmpty() ? null : m.alive.iterator().next();
        end(m, winner);
    }

    private void end(Match m, UUID winner) {
        if (m.state == Match.State.ENDED) return;
        m.state = Match.State.ENDED;
        if (m.task != null) m.task.cancel();
        if (m.zoneTask != null) m.zoneTask.cancel();

        for (UUID id : m.players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            String title = winner == null ? "<yellow><bold>DRAW"
                    : (id.equals(winner) ? "<green><bold>VICTORY" : "<red><bold>DEFEAT");
            p.showTitle(Title.title(Text.mm(title), Text.mm("")));
        }
        if (winner != null) announce(m, "ffa-winner", "{player}", m.names.getOrDefault(winner, "?"));
        else announce(m, "ffa-draw");
        if (winner != null) {
            List<UUID> viewers = new ArrayList<>(m.players);
            viewers.addAll(plugin.spectators().spectatorsOf(m));
            plugin.announcements().winBar(viewers, m.names.getOrDefault(winner, "?"));
        }
        if (m.event) plugin.events().finished(winner == null ? null : m.names.get(winner));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.spectators().matchEnded(m);
            for (UUID id : new ArrayList<>(m.players)) {
                Player p = Bukkit.getPlayer(id);
                byPlayer.remove(id);
                if (p == null) continue;
                p.setGameMode(GameMode.SURVIVAL);
                plugin.sendToLobby(p);
            }
            matches.remove(m);
            arenasInUse.remove(m.arena.name());
            if (m.holder != null) plugin.duels().releaseArena(m.holder);     // restores the arena
            if (m.event) plugin.events().clear();
        }, 100L);
    }

    /** /regear inside the FFA gives back the kit the player started with. */
    public boolean regear(Player player) {
        Match m = byPlayer.get(player.getUniqueId());
        if (m == null || m.state == Match.State.ENDED || !m.alive.contains(player.getUniqueId())) return false;
        ItemStack[] kit = m.kits.get(player.getUniqueId());
        if (kit == null) return false;
        KitService.applyKit(player, kit, false);
        plugin.msg().send(player, "regeared-match");
        return true;
    }

    private void announce(Match m, String key, String... kv) {
        for (UUID id : m.players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.msg().send(p, key, kv);
        }
    }

    private void titleAll(Match m, String title, String subtitle) {
        for (UUID id : m.players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.showTitle(Title.title(Text.mm(title), Text.mm(subtitle)));
        }
    }

    public void shutdown() {
        for (Match m : new ArrayList<>(matches)) {
            m.state = Match.State.ENDED;
            if (m.holder != null) plugin.duels().releaseArena(m.holder);
            for (UUID id : m.players) {
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                p.setGameMode(GameMode.SURVIVAL);
                plugin.sendToLobby(p);
            }
        }
        matches.clear();
        byPlayer.clear();
        queue.clear();
    }
}
