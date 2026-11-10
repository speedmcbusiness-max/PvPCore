package dev.samar.vperplayerkit.gladiator;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.gui.KitMenu;
import dev.samar.vperplayerkit.kit.KitService;
import dev.samar.vperplayerkit.rtpq.DuelManager;
import dev.samar.vperplayerkit.rtpq.PlayerStats;
import dev.samar.vperplayerkit.rtpq.Tier;
import dev.samar.vperplayerkit.util.ItemUtil;
import dev.samar.vperplayerkit.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gladiator: a host team (1-5 players) fights an opponent team (2-15 players) in an RTP arena with the same
 * 250x250 danger zone as queue duels. Last team standing wins. Players fight with their selected kit.
 */
public final class GladiatorManager {
    private final VPerPlayerKit plugin;
    private final Map<UUID, GladiatorMatch> byHost = new LinkedHashMap<>();
    private final Map<UUID, GladiatorMatch> byPlayer = new HashMap<>();

    public GladiatorManager(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lookups

    public boolean isBusy(UUID id) {
        return byPlayer.containsKey(id);
    }

    /** In a queue duel or an FFA. */
    private boolean otherBusy(UUID id) {
        return plugin.duels().isBusy(id) || plugin.ffa().isBusy(id) || plugin.busy(id);
    }

    public GladiatorMatch matchOf(UUID id) {
        return byPlayer.get(id);
    }

    public GladiatorMatch matchOfHost(String hostName) {
        for (GladiatorMatch m : byHost.values()) {
            if (m.hostName.equalsIgnoreCase(hostName)) return m;
        }
        return null;
    }

    public List<GladiatorMatch> openMatches() {
        List<GladiatorMatch> out = new ArrayList<>();
        for (GladiatorMatch m : byHost.values()) {
            if (m.state == GladiatorMatch.State.OPEN) out.add(m);
        }
        return out;
    }

    /** Matches a player can join: open ones, plus any they were invited to. */
    public List<GladiatorMatch> matchesFor(Player player) {
        List<GladiatorMatch> out = new ArrayList<>();
        for (GladiatorMatch m : byHost.values()) {
            boolean invited = m.invited.contains(player.getUniqueId())
                    && (m.state == GladiatorMatch.State.SETUP || m.state == GladiatorMatch.State.OPEN);
            if (invited || m.state == GladiatorMatch.State.OPEN) out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ access (LT2+, owner, youtuber)

    public Tier minTier() {
        Tier tier = Tier.fromName(plugin.getConfig().getString("gladiator.min-tier", "LT2"));
        return tier == null ? Tier.LT2 : tier;
    }

    /** Runs {@code ok} when the player may use gladiator: bypass permission or a high enough tier. */
    public void checkAccess(Player player, Runnable ok) {
        if (player.hasPermission("perplayerkit.gladiator.bypass")) {
            ok.run();
            return;
        }
        plugin.async(() -> plugin.storage().loadStats(player.getUniqueId(), player.getName()), (PlayerStats stats) -> {
            if (!player.isOnline()) return;
            if (stats.tier >= minTier().ordinal()) ok.run();
            else plugin.msg().send(player, "gladiator-no-access", "{tier}", minTier().display());
        });
    }

    // ------------------------------------------------------------------ create / publish / disband

    public void create(Player host) {
        UUID id = host.getUniqueId();
        GladiatorMatch existing = byPlayer.get(id);
        if (existing != null) {
            if (existing.host.equals(id)) plugin.gladiatorDialogs().openMain(host);
            else plugin.msg().send(host, "gladiator-busy");
            return;
        }
        if (otherBusy(id)) {
            plugin.msg().send(host, "gladiator-busy");
            return;
        }
        checkAccess(host, () -> {
            if (byPlayer.containsKey(id) || otherBusy(id)) return;
            plugin.queue().leave(host, false);
            GladiatorMatch m = new GladiatorMatch(id, host.getName());
            byHost.put(id, m);
            byPlayer.put(id, m);
            plugin.gladiatorDialogs().openMain(host);
        });
    }

    /** The [Create] button: opens the match so opponents can join (needs a valid kit choice). */
    public void publish(Player host) {
        GladiatorMatch m = byHost.get(host.getUniqueId());
        if (m == null || m.state != GladiatorMatch.State.SETUP) return;
        plugin.async(() -> resolveKitId(host.getUniqueId(), m.kitSpec), (Integer kit) -> {
            if (!host.isOnline() || m.state != GladiatorMatch.State.SETUP) return;
            if (kit == null) {
                plugin.msg().send(host, "gladiator-no-kit");
                return;
            }
            m.state = GladiatorMatch.State.OPEN;
            plugin.msg().send(host, "gladiator-created");
            Bukkit.broadcast(plugin.msg().get("gladiator-open", "{host}", m.hostName,
                    "{a}", String.valueOf(m.sizeA), "{b}", String.valueOf(m.sizeB)));
            maybeStart(m);
        });
    }

    private boolean useHostKit() {
        return plugin.getConfig().getBoolean("gladiator.use-host-kit", false);
    }

    /** Blocking: kit number for "selected" or "kit:N" when that kit exists, otherwise null. */
    private Integer resolveKitId(UUID owner, String spec) {
        Integer kit = null;
        if (spec != null && spec.startsWith("kit:")) {
            try {
                kit = Integer.parseInt(spec.substring(4));
            } catch (NumberFormatException ignored) {
                return null;
            }
        } else {
            kit = parse(plugin.storage().get(owner.toString(), "selected", "main"));
        }
        if (kit == null) return null;
        return plugin.storage().exists(owner.toString(), "kit", String.valueOf(kit)) ? kit : null;
    }

    public void disband(Player player) {
        GladiatorMatch m = byPlayer.get(player.getUniqueId());
        if (m == null) {
            plugin.msg().send(player, "gladiator-none");
            return;
        }
        if (!m.host.equals(player.getUniqueId())) {
            plugin.msg().send(player, "gladiator-not-host");
            return;
        }
        disbandMatch(m, "gladiator-disbanded");
    }

    private void disbandMatch(GladiatorMatch m, String messageKey) {
        announce(m, messageKey, "{host}", m.hostName);
        if (m.arena != null) cleanupArena(m);
        unregister(m);
    }

    private void unregister(GladiatorMatch m) {
        byHost.remove(m.host);
        for (UUID id : m.participants()) byPlayer.remove(id);
        byPlayer.remove(m.host);
    }

    // ------------------------------------------------------------------ team management

    /** Invites an online player to the host's team (add friend). */
    public void invite(Player host, String targetName) {
        GladiatorMatch m = byHost.get(host.getUniqueId());
        if (m == null) return;
        Player target = targetName == null ? null : Bukkit.getPlayerExact(targetName.trim());
        if (target == null || target.equals(host) || isBusy(target.getUniqueId())
                || otherBusy(target.getUniqueId())) {
            plugin.msg().send(host, "gladiator-invite-fail", "{player}", targetName == null ? "?" : targetName);
            return;
        }
        if (m.teamA.size() >= m.sizeA) {
            plugin.msg().send(host, "gladiator-team-full");
            return;
        }
        m.invited.add(target.getUniqueId());
        plugin.msg().send(host, "gladiator-invite-sent", "{player}", target.getName());
        plugin.msg().send(target, "gladiator-invite", "{host}", host.getName());
    }

    /** /gladiator join <host>: accepts an invite (host team) or joins the opponent team of an open match. */
    public void join(Player player, String hostName) {
        UUID id = player.getUniqueId();
        if (isBusy(id) || otherBusy(id)) {
            plugin.msg().send(player, "gladiator-busy");
            return;
        }
        GladiatorMatch m = hostName == null ? null : matchOfHost(hostName);
        if (m == null) {
            plugin.msg().send(player, "gladiator-not-found");
            return;
        }
        if (m.state == GladiatorMatch.State.STARTING || m.state == GladiatorMatch.State.ACTIVE
                || m.state == GladiatorMatch.State.ENDED) {
            plugin.msg().send(player, "gladiator-already-started");
            return;
        }

        if (m.invited.contains(id)) {
            if (m.teamA.size() >= m.sizeA) {
                plugin.msg().send(player, "gladiator-team-full");
                return;
            }
            addMember(m, player, 0);
            return;
        }
        if (m.state != GladiatorMatch.State.OPEN) {
            plugin.msg().send(player, "gladiator-not-found");
            return;
        }
        if (m.teamB.size() >= m.sizeB) {
            plugin.msg().send(player, "gladiator-team-full");
            return;
        }

        boolean needTier = plugin.getConfig().getBoolean("gladiator.join-requires-tier", false);
        Runnable proceed = () -> {
            if (!player.isOnline() || isBusy(id) || m.state != GladiatorMatch.State.OPEN
                    || m.teamB.size() >= m.sizeB) return;
            addMember(m, player, 1);
        };
        if (needTier) checkAccess(player, proceed);
        else proceed.run();
    }

    private boolean hasSelectedKit(UUID id) {
        byte[] sel = plugin.storage().get(id.toString(), "selected", "main");
        if (sel == null) return false;
        try {
            int kit = Integer.parseInt(new String(sel, StandardCharsets.UTF_8).trim());
            return plugin.storage().exists(id.toString(), "kit", String.valueOf(kit));
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private void addMember(GladiatorMatch m, Player player, int team) {
        UUID id = player.getUniqueId();
        plugin.queue().leave(player, false);
        m.invited.remove(id);
        (team == 0 ? m.teamA : m.teamB).add(id);
        m.names.put(id, player.getName());
        byPlayer.put(id, m);
        plugin.msg().send(player, "gladiator-joined", "{host}", m.hostName);
        promptKit(player);
        announce(m, "gladiator-player-joined", "{player}", player.getName(),
                "{team}", team == 0 ? "host team" : "opponent team");
        maybeStart(m);
    }

    /**
     * Opponents and teammates fight with the kit they selected in the Kit Menu. Without one, the selector opens.
     * (Skipped when gladiator.use-host-kit is on, then everyone gets the host's kit.)
     */
    private void promptKit(Player player) {
        if (useHostKit()) return;
        UUID id = player.getUniqueId();
        plugin.async(() -> hasSelectedKit(id), (Boolean has) -> {
            if (!player.isOnline() || !isBusy(id)) return;
            if (has) {
                plugin.msg().send(player, "gladiator-kit-ready");
            } else {
                plugin.msg().send(player, "gladiator-select-kit");
                KitMenu.openSelect(plugin, player);
            }
        });
    }

    /** /regear during a fight: gives back the kit this player started the match with. */
    public boolean regear(Player player) {
        GladiatorMatch m = byPlayer.get(player.getUniqueId());
        if (m == null || !m.fighting()) return false;
        ItemStack[] kit = m.kitsUsed.get(player.getUniqueId());
        if (kit == null || m.state == GladiatorMatch.State.ENDED || !m.isAlive(player.getUniqueId())) return false;
        KitService.applyKit(player, kit, false);
        plugin.msg().send(player, "regeared-match");
        return true;
    }

    /** /gladiator kit: open the kit selector (left click a kit to select it). */
    public void openKitSelector(Player player) {
        if (!isBusy(player.getUniqueId())) {
            plugin.msg().send(player, "gladiator-none");
            return;
        }
        KitMenu.openSelect(plugin, player);
    }

    private void maybeStart(GladiatorMatch m) {
        if (m.state == GladiatorMatch.State.OPEN && m.teamA.size() >= m.sizeA && m.teamB.size() >= m.sizeB) {
            start(m);
        }
    }

    /** Host command /gladiator start: begin without waiting for every slot. */
    public void startEarly(Player host) {
        GladiatorMatch m = byHost.get(host.getUniqueId());
        if (m == null || m.state != GladiatorMatch.State.OPEN) {
            plugin.msg().send(host, "gladiator-none");
            return;
        }
        if (m.teamB.isEmpty()) {
            plugin.msg().send(host, "gladiator-need-opponents");
            return;
        }
        start(m);
    }

    /** /gladiator leave: leave the match (the host uses /gladiator disband). */
    public void leave(Player player) {
        GladiatorMatch m = byPlayer.get(player.getUniqueId());
        if (m == null) {
            plugin.msg().send(player, "gladiator-none");
            return;
        }
        if (m.host.equals(player.getUniqueId()) && !m.fighting()) {
            disbandMatch(m, "gladiator-disbanded");
            return;
        }
        removePlayer(m, player, true);
        plugin.msg().send(player, "gladiator-left");
    }

    public void handleQuit(Player player) {
        GladiatorMatch m = byPlayer.get(player.getUniqueId());
        if (m == null) return;
        if (m.host.equals(player.getUniqueId()) && !m.fighting()) {
            disbandMatch(m, "gladiator-disbanded");
            return;
        }
        removePlayer(m, player, true);
    }

    private void removePlayer(GladiatorMatch m, Player player, boolean toLobby) {
        UUID id = player.getUniqueId();
        boolean wasFighting = m.fighting() && m.alive.contains(id);
        m.teamA.remove(id);
        m.teamB.remove(id);
        m.invited.remove(id);
        byPlayer.remove(id);
        if (wasFighting) {
            m.alive.remove(id);
            announce(m, "gladiator-eliminated", "{player}", player.getName());
        }
        if (toLobby && m.arena != null) {
            player.setGameMode(GameMode.SURVIVAL);
            plugin.sendToLobby(player);
        }
        if (wasFighting) checkEnd(m);
    }

    // ------------------------------------------------------------------ start

    private static Integer parse(byte[] data) {
        if (data == null) return null;
        try {
            return Integer.parseInt(new String(data, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void start(GladiatorMatch m) {
        if (m.state != GladiatorMatch.State.OPEN) return;
        m.state = GladiatorMatch.State.STARTING;
        announce(m, "gladiator-starting");
        List<UUID> all = m.participants();

        plugin.async(() -> {
            Map<UUID, byte[]> data = new HashMap<>();
            boolean shared = useHostKit();
            for (UUID id : all) {
                // shared: everybody gets the kit the host picked; otherwise each player brings their selected kit
                UUID owner = shared ? m.host : id;
                String spec = (shared || id.equals(m.host)) ? m.kitSpec : "selected";
                Integer kit = resolveKitId(owner, spec);
                byte[] kitData = kit == null ? null : plugin.storage().get(owner.toString(), "kit", String.valueOf(kit));
                if (kitData != null) data.put(id, kitData);
                m.tiers.put(id, plugin.storage().loadStats(id, null).tier);     // for the high tier announcement
            }
            return data;
        }, (Map<UUID, byte[]> data) -> {
            Map<UUID, ItemStack[]> kits = new HashMap<>();
            for (UUID id : all) {
                ItemStack[] items = ItemUtil.fromBytes(data.get(id));
                Player p = Bukkit.getPlayer(id);
                if (items == null || p == null) {
                    m.teamA.remove(id);
                    m.teamB.remove(id);
                    byPlayer.remove(id);
                    if (p != null) plugin.msg().send(p, "gladiator-no-kit");
                } else {
                    kits.put(id, items);
                }
            }
            if (!m.teamA.contains(m.host)) {
                disbandMatch(m, "gladiator-disbanded");
                return;
            }
            if (m.teamB.isEmpty()) {
                m.state = GladiatorMatch.State.OPEN;
                announce(m, "gladiator-cancelled");
                return;
            }
            World world = plugin.duels().pickWorld(m.biome);
            if (world == null) {
                m.state = GladiatorMatch.State.OPEN;
                announce(m, "rtpq-no-world");
                return;
            }
            plugin.duels().findArena(world, m.biome, 40, plugin.getConfig().getInt("rtpq.arena-size", 250) / 2,
                    spot -> begin(m, spot, kits), () -> {
                m.state = GladiatorMatch.State.OPEN;
                announce(m, "rtpq-no-arena");
            });
        });
    }

    private ItemStack[] adjustKit(ItemStack[] source, GladiatorMatch m, int team) {
        ItemStack[] kit = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) kit[i] = source[i] == null ? null : source[i].clone();

        if (!m.elytra(team)) {
            for (int i = 0; i < kit.length; i++) {
                if (kit[i] != null && kit[i].getType() == Material.ELYTRA) kit[i] = null;
            }
        }
        // armor slots: 36 boots, 37 leggings, 38 chestplate, 39 helmet
        // Single = Blast Protection IV on the chestplate, Double = chestplate and leggings. The rest gets Protection IV.
        boolean doubleBlast = m.blast(team) == GladiatorMatch.Blast.DOUBLE;
        for (int slot = 36; slot <= 39 && slot < kit.length; slot++) {
            ItemStack piece = kit[slot];
            if (piece == null || piece.getType() == Material.ELYTRA) continue;
            boolean blast = slot == 38 || (slot == 37 && doubleBlast);
            piece.removeEnchantment(Enchantment.PROTECTION);
            piece.removeEnchantment(Enchantment.BLAST_PROTECTION);
            piece.addUnsafeEnchantment(blast ? Enchantment.BLAST_PROTECTION : Enchantment.PROTECTION, 4);
        }
        return kit;
    }

    private void begin(GladiatorMatch m, DuelManager.Spot spot, Map<UUID, ItemStack[]> kits) {
        if (m.state != GladiatorMatch.State.STARTING) return;
        m.arena = plugin.duels().createArena(spot);

        placeTeam(m, 0, spot.a(), kits);
        placeTeam(m, 1, spot.b(), kits);
        plugin.duels().startZone(m.arena, () -> {
            List<Player> online = new ArrayList<>();
            for (UUID id : m.participants()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) online.add(p);
            }
            return online;
        });

        // announce high tier players joining the fight
        UUID top = null;
        int best = -1;
        for (UUID id : m.participants()) {
            int t = m.tiers.getOrDefault(id, 0);
            if (t > best) {
                best = t;
                top = id;
            }
        }
        if (top != null) {
            List<UUID> others = m.teamOf(top) == 0 ? m.teamB : m.teamA;
            List<String> names = new ArrayList<>();
            for (UUID id : others) names.add(m.name(id));
            plugin.announcements().highTier("gladiator", m.name(top), best, names, others.size());
        }

        final int[] left = {plugin.getConfig().getInt("gladiator.countdown-seconds", 5)};
        new BukkitRunnable() {
            @Override
            public void run() {
                if (m.state == GladiatorMatch.State.ENDED) {
                    cancel();
                    return;
                }
                if (left[0] > 0) {
                    titleAll(m, "<yellow>" + left[0], "<gray>Last team standing wins");
                    left[0]--;
                } else {
                    m.state = GladiatorMatch.State.ACTIVE;
                    titleAll(m, "<red><bold>FIGHT", "");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 10L, 20L);
        startZoneTask(m);
    }

    private void placeTeam(GladiatorMatch m, int team, Location base, Map<UUID, ItemStack[]> kits) {
        List<UUID> members = team == 0 ? m.teamA : m.teamB;
        World world = base.getWorld();
        int n = members.size();
        for (int i = 0; i < n; i++) {
            UUID id = members.get(i);
            Player p = Bukkit.getPlayer(id);
            ItemStack[] kit = kits.get(id);
            if (p == null || kit == null) continue;
            int x = base.getBlockX();
            int z = base.getBlockZ() + (int) Math.round((i - (n - 1) / 2.0) * 3.0);
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            Location loc = new Location(world, x + 0.5, y, z + 0.5, base.getYaw(), 0f);

            for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
            ItemStack[] adjusted = adjustKit(kit, m, team);
            m.kitsUsed.put(id, adjusted);
            KitService.applyKit(p, adjusted, true);
            plugin.kits().heal(p);
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(loc);
            m.alive.add(id);
            plugin.markInMatch(id, true);
        }
    }

    // ------------------------------------------------------------------ danger zone

    private void startZoneTask(GladiatorMatch m) {
        int grace = plugin.getConfig().getInt("rtpq.danger-zone.grace-seconds", 3);
        double damage = plugin.getConfig().getDouble("rtpq.danger-zone.damage", 10.0);
        m.zoneTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (m.state == GladiatorMatch.State.ENDED || m.arena == null) {
                    cancel();
                    return;
                }
                if (m.state != GladiatorMatch.State.ACTIVE) return;
                for (UUID id : new ArrayList<>(m.alive)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null) continue;
                    if (m.arena.inSafeZone(p.getLocation())) {
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

    /** A lethal hit (cancelled by the listener) knocks the player out. */
    public void eliminate(GladiatorMatch m, Player player) {
        if (!m.alive.remove(player.getUniqueId())) return;
        announce(m, "gladiator-eliminated", "{player}", player.getName());
        player.setGameMode(GameMode.SPECTATOR);
        plugin.kits().heal(player);
        checkEnd(m);
    }

    private int aliveIn(GladiatorMatch m, List<UUID> team) {
        int count = 0;
        for (UUID id : team) {
            if (m.alive.contains(id)) count++;
        }
        return count;
    }

    private void checkEnd(GladiatorMatch m) {
        if (!m.fighting() || m.state == GladiatorMatch.State.ENDED) return;
        int a = aliveIn(m, m.teamA);
        int b = aliveIn(m, m.teamB);
        if (a > 0 && b > 0) return;
        endMatch(m, a == 0 && b == 0 ? -1 : (a == 0 ? 1 : 0));
    }

    private void endMatch(GladiatorMatch m, int winnerTeam) {
        if (m.state == GladiatorMatch.State.ENDED) return;
        m.state = GladiatorMatch.State.ENDED;
        if (m.zoneTask != null) m.zoneTask.cancel();

        for (UUID id : m.participants()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            int team = m.teamOf(id);
            String title = winnerTeam < 0 ? "<yellow><bold>DRAW"
                    : (team == winnerTeam ? "<green><bold>VICTORY" : "<red><bold>DEFEAT");
            p.showTitle(Title.title(Text.mm(title), Text.mm("")));
        }
        if (winnerTeam >= 0) {
            String teamName = winnerTeam == 0 ? m.hostName + "'s team" : "The opponents";
            announce(m, "gladiator-winner", "{team}", teamName);
            List<UUID> viewers = new ArrayList<>(m.participants());
            viewers.addAll(plugin.spectators().spectatorsOf(m));
            plugin.announcements().winBar(viewers, teamName);
            if (plugin.announcements().broadcastEnabled("gladiator")) {
                Bukkit.broadcast(plugin.msg().get("gladiator-win-broadcast", "{team}", teamName, "{host}", m.hostName));
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            cleanupArena(m);
            unregister(m);
        }, 100L);
    }

    private void cleanupArena(GladiatorMatch m) {
        plugin.spectators().matchEnded(m);
        if (m.zoneTask != null) m.zoneTask.cancel();
        m.state = GladiatorMatch.State.ENDED;
        for (UUID id : m.participants()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.setGameMode(GameMode.SURVIVAL);
            plugin.sendToLobby(p);
        }
        if (m.arena != null) {
            plugin.duels().releaseArena(m.arena);
            m.arena = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    public void announce(GladiatorMatch m, String key, String... kv) {
        for (UUID id : m.participants()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.msg().send(p, key, kv);
        }
    }

    private void titleAll(GladiatorMatch m, String title, String subtitle) {
        for (UUID id : m.participants()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.showTitle(Title.title(Text.mm(title), Text.mm(subtitle)));
        }
    }

    public void shutdown() {
        for (GladiatorMatch m : new ArrayList<>(byHost.values())) {
            if (m.arena != null) cleanupArena(m);
        }
        byHost.clear();
        byPlayer.clear();
    }
}
