package dev.samar.vperplayerkit.command;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.gui.EditorMenu;
import dev.samar.vperplayerkit.gui.ImportMenu;
import dev.samar.vperplayerkit.gui.KitMenu;
import dev.samar.vperplayerkit.gui.SusMenu;
import dev.samar.vperplayerkit.gui.KitRoomCategoriesMenu;
import dev.samar.vperplayerkit.gui.KitRoomMenu;
import dev.samar.vperplayerkit.gui.PublicKitsMenu;
import dev.samar.vperplayerkit.dialog.RtpDialogs;
import dev.samar.vperplayerkit.kit.KitService;
import dev.samar.vperplayerkit.rtpq.DuelManager;
import dev.samar.vperplayerkit.rtpq.PlayerStats;
import dev.samar.vperplayerkit.rtpq.Tier;
import dev.samar.vperplayerkit.totemprac.TotemPrac;
import java.util.UUID;
import dev.samar.vperplayerkit.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Registers every command in code so that /kit1../kit14, /k1../k14 and the aliases
 * do not need to be listed in plugin.yml.
 *
 * /kit /k, /kit1-14 /k1-14, /loadselectedkit /lsk, /kitroom, /heal, /repair, /regear, /pk
 */
public final class Commands {
    private static final String USE = "perplayerkit.use";
    private static final String ADMIN = "perplayerkit.kitroom.admin";

    private interface Handler {
        void run(CommandSender sender, String[] args);
    }

    private interface Completer {
        List<String> complete(CommandSender sender, String[] args);
    }

    private static final class Cmd extends Command {
        private final Handler handler;
        private final Completer completer;

        Cmd(String name, List<String> aliases, Handler handler, Completer completer) {
            super(name);
            setAliases(aliases);
            this.handler = handler;
            this.completer = completer;
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            handler.run(sender, args);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return completer == null ? List.of() : completer.complete(sender, args);
        }
    }

    private final VPerPlayerKit plugin;

    public Commands(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    private void register(String name, List<String> aliases, Handler handler, Completer completer) {
        register("vperplayerkit", name, aliases, handler, completer);
    }

    /** Registers under another namespace, e.g. so /vcrystal:ssspawn works as well as /vperplayerkit:ssspawn. */
    private void register(String prefix, String name, List<String> aliases, Handler handler, Completer completer) {
        plugin.getServer().getCommandMap().register(prefix, new Cmd(name, aliases, handler, completer));
    }

    public void registerAll() {
        register("kit", List.of("k"), this::kit, (s, a) -> a.length == 1 ? filter(List.of("share-code"), a[0]) : List.of());

        for (int n = 1; n <= plugin.maxKits(); n++) {
            final int kitNumber = n;
            register("kit" + n, List.of("k" + n), (s, a) -> loadNumbered(s, kitNumber), null);
        }

        register("loadselectedkit", List.of("lsk"), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, USE)) plugin.kits().loadSelected(player);
        }, null);

        register("kitroom", List.of(), this::kitRoom, (s, a) -> {
            if (a.length == 1 && s.hasPermission(ADMIN)) return filter(List.of("admin"), a[0]);
            if (a.length == 2 && s.hasPermission(ADMIN)) return filter(KitRoomMenu.categories(plugin), a[1]);
            return List.of();
        });

        register("heal", List.of(), (s, a) -> {
            Player player = player(s);
            if (player == null || !allowed(player, "perplayerkit.heal")) return;
            plugin.kits().heal(player);
            plugin.msg().send(player, "healed");
        }, null);

        register("repair", List.of(), this::repair, (s, a) -> a.length == 1 ? filter(List.of("all"), a[0]) : List.of());

        register("regear", List.of(), (s, a) -> {
            Player player = player(s);
            if (player == null || !allowed(player, "perplayerkit.regear")) return;
            if (plugin.duels().regear(player) || plugin.gladiator().regear(player) || plugin.ffa().regear(player)) return;
            if (plugin.inMatch(player)) return;           // match is over, nothing to regear
            plugin.kits().regear(player);
        }, null);

        register("leave", List.of(), this::leaveMatch, null);
        register("state", List.of("stats"), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, USE)) plugin.dialogs().openStats(player);
        }, null);

        register("pk", List.of("publickit"), this::publicKit, this::publicKitComplete);

        register("rtp", List.of(), (s, a) -> {
            Player player = player(s);
            if (player == null || !allowed(player, "perplayerkit.rtp")) return;
            if (a.length == 0) plugin.dialogs().openRtp(player);
            else plugin.rtp().toBiome(player, a[0]);
        }, (s, a) -> a.length == 1 ? filter(plugin.rtp().biomes(), a[0]) : List.of());
        register("qrtp", List.of("quickrtp"), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, "perplayerkit.qrtp")) plugin.rtp().quick(player);
        }, null);
        register("gladiator", List.of("glad"), this::gladiator, (s, a) -> {
            if (a.length == 1) return filter(List.of("create", "join", "disband", "leave", "start", "kit"), a[0]);
            if (a.length == 2 && a[0].equalsIgnoreCase("join")) {
                List<String> hosts = new ArrayList<>();
                for (var m : plugin.gladiator().openMatches()) hosts.add(m.hostName);
                return filter(hosts, a[1]);
            }
            return List.of();
        });
        register("spawn", List.of(), this::spawn, null);
        register("setspawn", List.of(), this::setSpawn, null);

        register("rtpq", List.of("queue"), this::rtpq,
                (s, a) -> {
                    if (a.length == 1) {
                        return filter(s.hasPermission("perplayerkit.rtpq.admin")
                                ? List.of("leave", "biome", "setspawn") : List.of("leave", "biome"), a[0]);
                    }
                    if (a.length == 2 && a[0].equalsIgnoreCase("biome")) {
                        List<String> options = new ArrayList<>(plugin.locator().biomes());
                        options.add("auto");
                        return filter(options, a[1]);
                    }
                    return List.of();
                });
        register("leaderboard", List.of("lb"), this::leaderboard, (s, a) -> a.length == 1
                ? filter(List.of("overall", "kills", "victory", "defeat", "matchplayed"), a[0]) : List.of());
        register("tier", List.of(), this::tier, (s, a) -> {
            if (a.length == 1) {
                List<String> options = new ArrayList<>(List.of("playername", "dc"));
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
                return filter(options, a[0]);
            }
            if (a.length == 2 && a[0].equalsIgnoreCase("playername")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                return filter(names, a[1]);
            }
            return List.of();
        });
        register("progress", List.of(), this::progress, null);
        register("spec", List.of("spectator"), (s, a) -> {
            Player player = player(s);
            if (player == null || !allowed(player, "perplayerkit.spectate")) return;
            if (a.length == 0 || a[0].equalsIgnoreCase("leave") || a[0].equalsIgnoreCase("stop")) {
                if (!plugin.spectators().stop(player, true)) plugin.msg().send(player, "spec-usage");
                return;
            }
            plugin.spectators().start(player, a[0]);
        }, (s, a) -> {
            if (a.length != 1) return List.of();
            List<String> names = new ArrayList<>(List.of("leave"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (plugin.spectators().matchOf(p.getUniqueId()) != null) names.add(p.getName());
            }
            return filter(names, a[0]);
        });
        register("event", List.of(), (s, a) -> {
            Player player = player(s);
            if (player == null) return;
            String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "", "join" -> {
                    if (allowed(player, "perplayerkit.event")) plugin.events().join(player);
                }
                case "leave" -> plugin.events().leave(player);
                case "open" -> {
                    if (allowed(player, "perplayerkit.event.admin")) plugin.events().open(player);
                }
                case "start" -> {
                    if (allowed(player, "perplayerkit.event.admin")) plugin.events().start(player);
                }
                case "close" -> {
                    if (allowed(player, "perplayerkit.event.admin")) plugin.events().close(player);
                }
                case "setspawn" -> {
                    if (allowed(player, "perplayerkit.event.admin")) plugin.events().setSpawn(player);
                }
                default -> plugin.msg().send(player, "event-usage");
            }
        }, (s, a) -> {
            if (a.length != 1) return List.of();
            List<String> options = new ArrayList<>(List.of("leave"));
            if (s.hasPermission("perplayerkit.event.admin")) options.addAll(List.of("open", "start", "close", "setspawn"));
            return filter(options, a[0]);
        });
        register("region", List.of(), this::region,
                (s, a) -> a.length == 1 ? filter(plugin.results().regions(), a[0]) : List.of());
        registerModeration();
        registerTeleports();
        register("verify", List.of(), this::verify, null);
        register("unverify", List.of(), this::unverify, null);
        register("result", List.of(), this::result, (s, a) -> {
            if (a.length == 1) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                return filter(names, a[0]);
            }
            if (a.length == 2) {
                List<String> tiers = new ArrayList<>();
                for (Tier t : Tier.values()) tiers.add(t.name());
                return filter(tiers, a[1]);
            }
            return List.of();
        });
        register("totemprac", List.of(), (s, a) -> {
            Player player = player(s);
            if (player == null || !allowed(player, USE)) return;
            if (a.length >= 1 && a[0].equalsIgnoreCase("help")) {
                sendHelp(player, "player");
                return;
            }
            if (a.length >= 1 && a[0].equalsIgnoreCase("spawn")) {
                // works anywhere until an admin has set up a practice world (then only inside it)
                if (plugin.totemPrac().spawn() != null && !plugin.totemPrac().inPracWorld(player)) {
                    plugin.msg().send(player, "totemprac-target-world");
                    return;
                }
                String sub = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "start";
                if (sub.equals("start")) {
                    plugin.totemPrac().aim().start(player);
                    plugin.msg().send(player, "totemprac-target-started");
                } else if (sub.equals("stop")) {
                    plugin.totemPrac().aim().stop(player);
                    plugin.msg().send(player, "totemprac-target-stopped");
                } else {
                    plugin.msg().send(player, "totemprac-target-usage");
                }
                return;
            }
            plugin.totemPrac().enterOrLeave(player);
        }, (s, a) -> {
            if (a.length == 1) return filter(List.of("spawn", "help"), a[0]);
            if (a.length == 2 && a[0].equalsIgnoreCase("spawn")) return filter(List.of("start", "stop"), a[1]);
            return List.of();
        });
        register("ffartpq", List.of("rtpffa", "rtpqffa"), (s, a) -> {
            Player player = player(s);
            if (player == null || !allowed(player, "perplayerkit.ffa")) return;
            if (a.length >= 1 && a[0].equalsIgnoreCase("leave")) {
                if (!plugin.ffa().leave(player)) plugin.msg().send(player, "match-none");
            } else {
                plugin.ffa().join(player);
            }
        }, (s, a) -> a.length == 1 ? filter(List.of("leave"), a[0]) : List.of());
        register("ffartp", List.of(), this::ffartp, (s, a) -> {
            if (a.length == 1) return filter(List.of("wand", "create", "del", "list", "lobby"), a[0]);
            if (a.length == 2 && (a[0].equalsIgnoreCase("del") || a[0].equalsIgnoreCase("delete"))) {
                List<String> names = new ArrayList<>();
                for (var arena : plugin.ffa().arenas()) names.add(arena.name());
                return filter(names, a[1]);
            }
            return List.of();
        });
        register("admintotemprac", List.of(), this::adminTotemPrac, (s, a) -> {
            if (a.length == 1) return filter(List.of("create", "delete", "button", "setting", "help"), a[0]);
            if (a.length == 2 && a[0].equalsIgnoreCase("setting")) {
                return filter(List.of("damage", "hits", "interval", "gap", "prepare", "spawn", "lobby"), a[1]);
            }
            return List.of();
        });
    }

    // ------------------------------------------------------------------ /ffartp (admin)

    private void ffartp(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, "perplayerkit.ffa.admin")) return;
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "wand" -> {
                player.getInventory().addItem(plugin.ffa().wand());
                plugin.msg().send(player, "ffa-wand-given");
            }
            case "create" -> {
                if (args.length < 2) plugin.msg().send(player, "ffa-usage-admin");
                else plugin.ffa().createArena(player, args[1]);
            }
            case "del", "delete" -> {
                if (args.length < 2) plugin.msg().send(player, "ffa-usage-admin");
                else plugin.ffa().deleteArena(player, args[1]);
            }
            case "lobby" -> {
                plugin.data().setLocation("ffa.lobby", player.getLocation());
                plugin.msg().send(player, "ffa-lobby-set");
            }
            case "list" -> {
                List<String> names = new ArrayList<>();
                for (var arena : plugin.ffa().arenas()) names.add(arena.name() + " (" + arena.spawns().size() + ")");
                plugin.msg().send(player, "ffa-list", "{list}", names.isEmpty() ? "-" : String.join(", ", names));
            }
            default -> plugin.msg().send(player, "ffa-usage-admin");
        }
    }

    // ------------------------------------------------------------------ /leave

    /** Leaves whatever the player is in: a duel (forfeit), a Gladiator match or the queue. */
    private void leaveMatch(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) return;
        java.util.UUID id = player.getUniqueId();
        if (plugin.spectators().stop(player, true)) {
            return;
        }
        if (plugin.duels().leave(player)) {
            plugin.msg().send(player, "match-left");
        } else if (plugin.duels().isBusy(id)) {
            plugin.msg().send(player, "match-preparing");     // arena is still being prepared
        } else if (plugin.gladiator().isBusy(id)) {
            plugin.gladiator().leave(player);
        } else if (plugin.ffa().leave(player)) {
            // left the FFA queue or match (message sent by the manager)
        } else if (plugin.queue().isQueued(id)) {
            plugin.queue().leave(player, true);
        } else {
            plugin.msg().send(player, "match-none");
        }
    }

    // ------------------------------------------------------------------ /gladiator

    private void gladiator(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, USE)) return;
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> plugin.gladiator().create(player);
            case "join" -> {
                if (args.length >= 2) plugin.gladiator().join(player, args[1]);
                else plugin.gladiatorDialogs().openMatchList(player);
            }
            case "disband" -> plugin.gladiator().disband(player);
            case "leave" -> plugin.gladiator().leave(player);
            case "start" -> plugin.gladiator().startEarly(player);
            case "kit" -> plugin.gladiator().openKitSelector(player);
            default -> plugin.msg().send(player, "gladiator-usage");
        }
    }

    // ------------------------------------------------------------------ moderation: /report /offend /sus /sfmode /damage /autocheck /freeze /ss

    private List<String> onlineNames(String typed) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return filter(names, typed);
    }

    private Player online(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) plugin.msg().send(sender, "player-not-found", "{player}", name);
        return target;
    }

    private void rank(CommandSender sender, String[] args) {
        if (!allowed(sender, "perplayerkit.rank.admin")) return;
        if (args.length >= 1 && args[0].equalsIgnoreCase("editor")) {
            String link = plugin.web().createLink();
            if (link == null) {
                plugin.msg().send(sender, "rank-editor-disabled");
                return;
            }
            plugin.msg().send(sender, "rank-editor-link", "{url}", link, "{minutes}",
                    String.valueOf(plugin.getConfig().getInt("web.token-minutes", 15)));
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            plugin.ranks().sendList(sender);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.async(() -> plugin.ranks().reloadDefs());
            plugin.msg().send(sender, "rank-reloaded");
            return;
        }
        if (args.length < 3) {
            plugin.msg().send(sender, "rank-usage");
            return;
        }
        plugin.ranks().modify(sender, args[0], args[1], args[2], args.length > 3 ? args[3] : null);
    }

    private void registerModeration() {
        register("report", List.of(), (s, a) -> {
            Player player = player(s);
            if (player == null || !allowed(player, "perplayerkit.report")) return;
            if (a.length == 0) {
                plugin.msg().send(player, "report-usage");
                return;
            }
            UUID target = plugin.reports().resolve(a[0]);
            if (target == null) {
                plugin.msg().send(player, "report-unknown", "{player}", a[0]);
                return;
            }
            Player onlineTarget = Bukkit.getPlayer(target);
            String name = onlineTarget != null ? onlineTarget.getName() : a[0];
            if (a.length >= 2) {      // /report <player> <any custom reason>
                plugin.reports().submit(player, target, name, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
            } else {
                plugin.reportDialogs().open(player, target, name);
            }
        }, (s, a) -> a.length == 1 ? onlineNames(a[0])
                : (a.length == 2 ? filter(plugin.reports().reasons(), a[1]) : List.of()));

        register("offend", List.of(), (s, a) -> {
            if (!allowed(s, "perplayerkit.offend")) return;
            if (a.length < 2) {
                plugin.msg().send(s, "offend-usage", "{list}", String.join("|", plugin.reports().offendReasons()));
                return;
            }
            plugin.reports().offend(s, a[0], String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
        }, (s, a) -> a.length == 1 ? onlineNames(a[0])
                : (a.length == 2 ? filter(plugin.reports().offendReasons(), a[1]) : List.of()));

        register("unoffend", List.of(), (s, a) -> {
            if (!allowed(s, "perplayerkit.offend")) return;
            if (a.length < 1) {
                plugin.msg().send(s, "unoffend-usage");
                return;
            }
            UUID uuid = plugin.reports().resolve(a[0]);
            if (uuid != null && plugin.bans().unban(uuid)) plugin.msg().send(s, "unoffend-done", "{player}", a[0]);
            else plugin.msg().send(s, "unoffend-none", "{player}", a[0]);
        }, (s, a) -> a.length == 1 ? onlineNames(a[0]) : List.of());

        // /v:rank <player> <set|add|remove> <rank> [time]   /v:rank editor|list|reload
        register("v", "rank", List.of(), this::rank, (s, a) -> {
            if (a.length == 1) {
                List<String> options = new ArrayList<>(List.of("editor", "list", "reload"));
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
                return filter(options, a[0]);
            }
            if (a.length == 2) return filter(List.of("set", "add", "remove"), a[1]);
            if (a.length == 3) {
                List<String> keys = new ArrayList<>();
                for (var d : plugin.ranks().definitions()) keys.add(d.key());
                return filter(keys, a[2]);
            }
            return a.length == 4 ? filter(List.of("perm", "7d", "30d", "1h"), a[3]) : List.of();
        });

        register("sus", List.of(), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, "perplayerkit.sus")) SusMenu.open(plugin, player, 0);
        }, null);

        register("sfmode", List.of(), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, "perplayerkit.sfmode")) plugin.sfmode().toggle(player);
        }, null);

        register("damage", List.of(), (s, a) -> {
            if (!allowed(s, "perplayerkit.autocheck")) return;
            if (a.length < 2) {
                plugin.msg().send(s, "damage-usage");
                return;
            }
            Player target = online(s, a[0]);
            if (target == null) return;
            try {
                plugin.autocheck().damage(s, target, Double.parseDouble(a[1]));
            } catch (NumberFormatException ex) {
                plugin.msg().send(s, "damage-usage");
            }
        }, (s, a) -> a.length == 1 ? onlineNames(a[0]) : List.of());

        register("autocheck", List.of(), (s, a) -> {
            if (!allowed(s, "perplayerkit.autocheck")) return;
            if (a.length < 2) {
                plugin.msg().send(s, "autocheck-usage");
                return;
            }
            Player target = online(s, a[0]);
            if (target == null) return;
            try {
                plugin.autocheck().autocheck(s, target, Integer.parseInt(a[1]));
            } catch (NumberFormatException ex) {
                plugin.msg().send(s, "autocheck-usage");
            }
        }, (s, a) -> a.length == 1 ? onlineNames(a[0]) : List.of());

        register("freeze", List.of(), (s, a) -> {
            if (!allowed(s, "perplayerkit.freeze")) return;
            if (a.length < 1) {
                plugin.msg().send(s, "freeze-usage");
                return;
            }
            Player target = online(s, a[0]);
            if (target != null) plugin.freeze().freeze(s, target);
        }, (s, a) -> a.length == 1 ? onlineNames(a[0]) : List.of());

        register("unfreeze", List.of(), (s, a) -> {
            if (!allowed(s, "perplayerkit.freeze")) return;
            if (a.length < 1) {
                plugin.msg().send(s, "freeze-usage");
                return;
            }
            Player target = online(s, a[0]);
            if (target != null) plugin.freeze().unfreeze(s, target);
        }, (s, a) -> a.length == 1 ? onlineNames(a[0]) : List.of());

        register("ss", List.of(), (s, a) -> {
            Player staff = player(s);
            if (staff == null || !allowed(staff, "perplayerkit.ss")) return;
            if (a.length < 1) {
                plugin.msg().send(staff, "ss-usage");
                return;
            }
            Player target = online(staff, a[0]);
            if (target == null) return;
            if (target.equals(staff)) {
                plugin.msg().send(staff, "ss-self");
                return;
            }
            plugin.freeze().toggleScreenshare(staff, target);
        }, (s, a) -> a.length == 1 ? onlineNames(a[0]) : List.of());

        // /ssspawn set  (also reachable as /vcrystal:ssspawn set)
        Handler ssSpawn = (s, a) -> {
            Player staff = player(s);
            if (staff == null || !allowed(staff, "perplayerkit.ss")) return;
            if (a.length >= 1 && a[0].equalsIgnoreCase("set")) plugin.freeze().setSsSpawn(staff);
            else plugin.msg().send(staff, "ssspawn-usage");
        };
        Completer ssSpawnTab = (s, a) -> a.length == 1 ? filter(List.of("set"), a[0]) : List.of();
        register("ssspawn", List.of(), ssSpawn, ssSpawnTab);
        register("vcrystal", "ssspawn", List.of(), ssSpawn, ssSpawnTab);
    }

    // ------------------------------------------------------------------ /tpa family and /back

    private void registerTeleports() {
        register("tpa", List.of(), (s, a) -> {
            Player player = player(s);
            if (player == null || !allowed(player, "perplayerkit.tpa")) return;
            if (a.length < 1) plugin.msg().send(player, "tpa-usage");
            else plugin.teleports().request(player, a[0], false);
        }, (s, a) -> a.length == 1 ? onlineNames(a[0]) : List.of());

        register("tpahere", List.of(), (s, a) -> {
            Player player = player(s);
            if (player == null || !allowed(player, "perplayerkit.tpa")) return;
            if (a.length < 1) plugin.msg().send(player, "tpa-usage");
            else plugin.teleports().request(player, a[0], true);
        }, (s, a) -> a.length == 1 ? onlineNames(a[0]) : List.of());

        register("tpaauto", List.of(), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, "perplayerkit.tpa")) plugin.teleports().toggleAuto(player);
        }, null);

        register("tpaaccept", List.of("tpyes"), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, "perplayerkit.tpa")) plugin.teleports().accept(player, a.length > 0 ? a[0] : null);
        }, (s, a) -> a.length == 1 ? onlineNames(a[0]) : List.of());

        register("tpadeny", List.of("tpano"), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, "perplayerkit.tpa")) plugin.teleports().deny(player, a.length > 0 ? a[0] : null);
        }, (s, a) -> a.length == 1 ? onlineNames(a[0]) : List.of());

        register("tpacancel", List.of(), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, "perplayerkit.tpa")) plugin.teleports().cancel(player);
        }, null);

        register("tpaignore", List.of(), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, "perplayerkit.tpa")) plugin.teleports().ignore(player, a.length > 0 ? a[0] : null);
        }, (s, a) -> a.length == 1 ? filter(List.of("10m", "1h", "1d", "off"), a[0]) : List.of());

        register("back", List.of(), (s, a) -> {
            Player player = player(s);
            if (player != null && allowed(player, "perplayerkit.back")) plugin.teleports().back(player);
        }, null);
    }

    // ------------------------------------------------------------------ /spawn, /setspawn

    private void spawn(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, "perplayerkit.spawn")) return;
        if (plugin.inMatch(player) || plugin.duels().isBusy(player.getUniqueId())) {
            plugin.msg().send(player, "spawn-in-duel");
            return;
        }
        plugin.queue().leave(player, false);
        plugin.ffa().leave(player);
        if (plugin.events().isBusy(player.getUniqueId())) plugin.events().leave(player);
        plugin.spectators().stop(player, false);
        if (plugin.totemPrac().inPracWorld(player)) {
            plugin.totemPrac().leave(player);      // clears inventory, full health and saturation
        } else {
            org.bukkit.Location lobby = plugin.data().lobby();
            if (lobby == null) lobby = Bukkit.getWorlds().get(0).getSpawnLocation();
            player.teleport(lobby);
        }
        plugin.msg().send(player, "spawn-teleported");
    }

    private void setSpawn(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, "perplayerkit.rtpq.admin")) return;
        plugin.data().setLocation("lobby", player.getLocation());
        plugin.msg().send(player, "lobby-set");
    }

    // ------------------------------------------------------------------ /rtpq, /leaderboard

    private void rtpq(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, USE)) return;
        if (args.length >= 1 && args[0].equalsIgnoreCase("leave")) {
            plugin.queue().leave(player, true);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("biome")) {
            if (args.length >= 2) plugin.queue().changeBiome(player, args[1]);
            else plugin.dialogs().openBiome(player);
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("setspawn")) {
            if (!allowed(player, "perplayerkit.rtpq.admin")) return;
            plugin.data().setLocation("lobby", player.getLocation());
            plugin.msg().send(player, "lobby-set");
            return;
        }
        plugin.dialogs().openMain(player);
    }

    private void leaderboard(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, USE)) return;
        if (args.length == 0) {
            plugin.dialogs().openLeaderboardMenu(player);
            return;
        }
        String category = RtpDialogs.normalizeCategory(args[0]);
        if (category == null) {
            plugin.msg().send(player, "leaderboard-usage");
            return;
        }
        plugin.dialogs().openLeaderboard(player, category);
    }

    // ------------------------------------------------------------------ /tier, /result

    /** /tier [name] or /tier <playername|dc> <ign|discord user> */
    private void tier(CommandSender sender, String[] args) {
        if (!allowed(sender, USE)) return;
        String mode = "playername";
        String value;
        if (args.length >= 2 && (args[0].equalsIgnoreCase("playername") || args[0].equalsIgnoreCase("dc"))) {
            mode = args[0].toLowerCase(Locale.ROOT);
            value = args[1];
        } else if (args.length >= 1) {
            value = args[0];
        } else if (sender instanceof Player p) {
            value = p.getName();
        } else {
            plugin.msg().send(sender, "players-only");
            return;
        }
        final String lookup = mode;
        final String query = value;
        plugin.async(() -> {
            if (lookup.equals("dc")) {
                String digits = query.replaceAll("[^0-9]", "");
                UUID uuid = digits.isEmpty() ? null : plugin.discord().uuidOfDiscord(digits);
                return uuid == null ? null : plugin.storage().loadStats(uuid, null);
            }
            return plugin.storage().loadStatsByName(query);
        }, (PlayerStats stats) -> {
            Tier tier = stats == null ? Tier.UNRANKED : stats.tier();
            String shown = stats == null || stats.name == null ? query : stats.name;
            plugin.msg().send(sender, "tier-show", "{player}", shown, "{tier}", tier.colored(),
                    "{points}", tier.pointsText(),
                    "{progress}", stats == null ? "0" : DuelManager.fmt(stats.progress));
        });
    }

    // ------------------------------------------------------------------ /progress, /verify, /unverify

    private static String progressBar(double percent) {
        int filled = (int) Math.round(Math.max(0, Math.min(100, percent)) / 10.0);
        return "<green>" + "█".repeat(filled) + "<dark_gray>" + "█".repeat(10 - filled);
    }

    private void progress(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, USE)) return;
        plugin.async(() -> plugin.storage().loadStats(player.getUniqueId(), player.getName()), (PlayerStats stats) -> {
            if (!player.isOnline()) return;
            Tier tier = stats.tier();
            Tier next = tier.next();
            if (next == null) {
                plugin.msg().send(player, "progress-max", "{tier}", tier.colored());
                return;
            }
            plugin.msg().send(player, "progress-line", "{tier}", tier.colored(), "{next}", next.colored(),
                    "{bar}", progressBar(stats.progress), "{percent}", DuelManager.fmt(stats.progress));
            if (stats.testReady()) plugin.msg().send(player, "progress-test", "{chances}", String.valueOf(stats.chances));
        });
    }

    private void verify(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, "perplayerkit.verify")) return;
        if (!plugin.discord().enabled()) {
            plugin.msg().send(player, "verify-disabled");
            return;
        }
        plugin.async(() -> plugin.discord().discordTag(player.getUniqueId()), (String tag) -> {
            if (!player.isOnline()) return;
            if (tag != null) {
                plugin.msg().send(player, "verify-already", "{discord}", tag);
                return;
            }
            String code = plugin.discord().generateCode(player);
            plugin.msg().send(player, "verify-code", "{code}", code, "{minutes}", String.valueOf(plugin.discord().codeMinutes()));
        });
    }

    /** /unverify (yourself) or /unverify <account> (a Minecraft name or Discord ID, needs the admin permission). */
    private void unverify(CommandSender sender, String[] args) {
        Player self = sender instanceof Player p ? p : null;
        String account = args.length >= 1 ? args[0] : null;
        boolean own = account == null || (self != null && account.equalsIgnoreCase(self.getName()));
        if (own && self == null) {
            plugin.msg().send(sender, "unverify-usage");
            return;
        }
        if (!own && !allowed(sender, "perplayerkit.unverify.admin")) return;
        if (own && !allowed(sender, "perplayerkit.verify")) return;

        plugin.async(() -> {
            UUID uuid;
            if (own) {
                uuid = self.getUniqueId();
            } else {
                Player online = Bukkit.getPlayerExact(account);
                String digits = account.replaceAll("[^0-9]", "");
                if (online != null) {
                    uuid = online.getUniqueId();
                } else if (digits.length() >= 15) {
                    uuid = plugin.discord().uuidOfDiscord(digits);
                } else {
                    PlayerStats stats = plugin.storage().loadStatsByName(account);
                    uuid = stats == null ? null : stats.uuid;
                }
            }
            if (uuid == null) return 2;
            return plugin.discord().unlink(uuid) ? 0 : 1;
        }, (Integer result) -> {
            if (result == 0) plugin.msg().send(sender, "unverify-done");
            else if (result == 1) plugin.msg().send(sender, "unverify-none");
            else plugin.msg().send(sender, "unverify-unknown", "{account}", String.valueOf(account));
        });
    }

    /** /region [region] shows or sets your region, /region <player> <region> sets someone else's (admins). */
    private void region(CommandSender sender, String[] args) {
        Player self = sender instanceof Player p ? p : null;
        if (args.length == 0) {
            if (self == null) {
                plugin.msg().send(sender, "region-usage");
                return;
            }
            plugin.async(() -> plugin.results().region(self.getUniqueId()), (String region) ->
                    plugin.msg().send(self, "region-show", "{region}", region));
            return;
        }
        boolean other = args.length >= 2;
        Player target = other ? Bukkit.getPlayerExact(args[0]) : self;
        String value = other ? args[1] : args[0];
        if (other && !allowed(sender, "perplayerkit.region.admin")) return;
        if (!other && !allowed(sender, "perplayerkit.region")) return;
        if (target == null) {
            plugin.msg().send(sender, other ? "region-unknown-player" : "region-usage", "{player}", args[0]);
            return;
        }
        String canonical = plugin.results().canonical(value);
        if (canonical == null) {
            plugin.msg().send(sender, "region-invalid", "{list}", String.join(", ", plugin.results().regions()));
            return;
        }
        final Player t = target;
        plugin.async(() -> {
            plugin.results().setRegion(t.getUniqueId(), canonical);
            plugin.cache().refresh(t.getUniqueId(), t.getName());
        });
        plugin.msg().send(sender, other ? "region-set-other" : "region-set", "{player}", target.getName(), "{region}", canonical);
    }

    private void result(CommandSender sender, String[] args) {
        if (!allowed(sender, "perplayerkit.tier.admin")) return;
        if (args.length < 2) {
            plugin.msg().send(sender, "result-usage");
            return;
        }
        Tier tier = Tier.fromName(args[1]);
        if (tier == null) {
            plugin.msg().send(sender, "result-bad-tier");
            return;
        }
        String name = args[0];
        // optional fight lines for the Discord result, separated by |  e.g. /result Ivri_ LT3 Lost 1-4 vs. ilmcl | Lost 0-4 vs. joshy
        List<String> fights = new ArrayList<>();
        if (args.length > 2) {
            String joined = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            for (String part : joined.split("\\|")) {
                if (!part.trim().isEmpty()) fights.add(part.trim());
            }
        }
        Player online = Bukkit.getPlayerExact(name);
        final int[] oldTier = {-1};
        plugin.async(() -> {
            PlayerStats stats = online != null
                    ? plugin.storage().loadStats(online.getUniqueId(), online.getName())
                    : plugin.storage().loadStatsByName(name);
            if (stats == null) return null;
            oldTier[0] = stats.tier;
            stats.tier = tier.ordinal();
            stats.progress = 0;
            stats.chances = 2;
            plugin.storage().saveStats(stats);
            return stats;
        }, stats -> {
            if (stats == null) {
                plugin.msg().send(sender, "result-unknown-player", "{player}", name);
                return;
            }
            plugin.msg().send(sender, "result-set", "{player}", stats.name, "{tier}", tier.colored());
            plugin.cache().refresh(stats.uuid, stats.name);
            if (tier.ordinal() > oldTier[0]) plugin.discord().congratulate(stats.uuid, stats.name, tier);
            plugin.results().publish(stats.uuid, stats.name, Tier.fromIndex(Math.max(0, oldTier[0])), tier,
                    sender.getName(), sender instanceof Player tester ? tester.getUniqueId() : null, fights);
            Player target = Bukkit.getPlayer(stats.uuid);
            if (target != null) plugin.msg().send(target, "result-notify", "{tier}", tier.colored());
        });
    }

    // ------------------------------------------------------------------ /admintotemprac

    private void adminTotemPrac(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, "perplayerkit.totemprac.admin")) return;
        TotemPrac prac = plugin.totemPrac();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> {
                giveItem(player, prac.tool(TotemPrac.TOOL_REGION));
                plugin.msg().send(player, "totemprac-create-help");
            }
            case "delete" -> {
                prac.deleteArea();
                plugin.msg().send(player, "totemprac-area-deleted");
            }
            case "button" -> {
                giveItem(player, prac.tool(TotemPrac.TOOL_BUTTON));
                plugin.msg().send(player, "totemprac-button-help");
            }
            case "setting" -> totemSetting(player, prac, args);
            default -> sendHelp(player, "admin");
        }
    }

    /** Puts the item in the inventory, or drops it at the player's feet when it is full. */
    private void giveItem(Player player, ItemStack item) {
        var left = player.getInventory().addItem(item);
        for (ItemStack rest : left.values()) player.getWorld().dropItem(player.getLocation(), rest);
    }

    /** Sends a help list from config.yml (totemprac.help.<key>), every line is MiniMessage. */
    private void sendHelp(CommandSender to, String key) {
        for (String line : plugin.getConfig().getStringList("totemprac.help." + key)) {
            to.sendMessage(dev.samar.vperplayerkit.util.Text.mm(line));
        }
    }

    private void totemSetting(Player player, TotemPrac prac, String[] args) {
        if (args.length < 2) {
            plugin.msg().send(player, "totemprac-settings",
                    "{damage}", String.valueOf(prac.damage()), "{hits}", String.valueOf(prac.hits()),
                    "{interval}", String.valueOf(prac.intervalTicks()), "{gap}", String.valueOf(prac.gapTicks()),
                    "{prepare}", String.valueOf(prac.prepareSeconds()));
            return;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        if (key.equals("spawn")) {
            plugin.data().setLocation("totemprac.spawn", player.getLocation());
            plugin.msg().send(player, "totemprac-spawn-set");
            return;
        }
        if (key.equals("lobby")) {
            plugin.data().setLocation("lobby", player.getLocation());
            plugin.msg().send(player, "lobby-set");
            return;
        }
        if (args.length < 3) {
            plugin.msg().send(player, "totemprac-admin-usage");
            return;
        }
        try {
            switch (key) {
                case "damage" -> prac.setSetting("damage", Double.parseDouble(args[2]));
                case "hits" -> prac.setSetting("hits", Integer.parseInt(args[2]));
                case "interval" -> prac.setSetting("interval-ticks", Integer.parseInt(args[2]));
                case "gap" -> prac.setSetting("gap-ticks", Integer.parseInt(args[2]));
                case "prepare" -> prac.setSetting("prepare-seconds", Integer.parseInt(args[2]));
                default -> {
                    plugin.msg().send(player, "totemprac-admin-usage");
                    return;
                }
            }
            plugin.msg().send(player, "totemprac-setting-saved", "{key}", key, "{value}", args[2]);
        } catch (NumberFormatException ex) {
            plugin.msg().send(player, "totemprac-admin-usage");
        }
    }

    // ------------------------------------------------------------------ helpers

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        plugin.msg().send(sender, "players-only");
        return null;
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        plugin.msg().send(sender, "no-permission");
        return false;
    }

    private static List<String> filter(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(option);
        }
        return out;
    }

    // ------------------------------------------------------------------ /kit

    private void kit(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, USE)) return;
        if (args.length >= 1 && args[0].equalsIgnoreCase("share-code")) {
            if (args.length < 2) {
                plugin.msg().send(player, "share-usage");
                return;
            }
            ImportMenu.open(plugin, player, args[1]);
            return;
        }
        KitMenu.open(plugin, player, null);
    }

    private void loadNumbered(CommandSender sender, int number) {
        Player player = player(sender);
        if (player != null && allowed(player, USE)) plugin.kits().loadKit(player, number);
    }

    // ------------------------------------------------------------------ /kitroom

    private void kitRoom(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) return;

        if (args.length >= 1 && args[0].equalsIgnoreCase("admin")) {
            if (!allowed(player, ADMIN)) return;
            List<String> categories = KitRoomMenu.categories(plugin);
            if (args.length < 2) {
                plugin.msg().send(player, "kitroom-usage");
                return;
            }
            String category = args[1].toLowerCase(Locale.ROOT);
            if (!categories.contains(category)) {
                plugin.msg().send(player, "kitroom-unknown", "{list}", String.join(", ", categories));
                return;
            }
            KitRoomMenu.open(plugin, player, category, true);
            return;
        }
        if (!allowed(player, USE)) return;
        KitRoomCategoriesMenu.open(plugin, player);
    }

    // ------------------------------------------------------------------ /repair

    private void repair(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, "perplayerkit.repair")) return;

        int count = 0;
        if (args.length >= 1 && args[0].equalsIgnoreCase("all")) {
            for (int i = 0; i < 41; i++) {
                if (repairItem(player.getInventory().getItem(i))) count++;
            }
        } else if (repairItem(player.getInventory().getItemInMainHand())) {
            count = 1;
        }
        if (count == 0) plugin.msg().send(player, "nothing-to-repair");
        else plugin.msg().send(player, "repaired", "{count}", String.valueOf(count));
    }

    private static boolean repairItem(ItemStack item) {
        if (ItemUtil.isEmpty(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            damageable.setDamage(0);
            item.setItemMeta(damageable);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ /pk

    private void publicKit(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || !allowed(player, USE)) return;

        if (args.length == 0) {
            PublicKitsMenu.open(plugin, player);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean management = sub.equals("create") || sub.equals("edit") || sub.equals("delete");
        if (!management) {
            String name = KitService.sanitizeName(args[0]);
            if (name == null) {
                plugin.msg().send(player, "public-missing", "{name}", args[0]);
                return;
            }
            plugin.kits().loadPublic(player, name);
            return;
        }

        if (!allowed(player, ADMIN)) return;
        if (args.length < 2) {
            plugin.msg().send(player, "public-usage");
            return;
        }
        String name = KitService.sanitizeName(args[1]);
        if (name == null) {
            plugin.msg().send(player, "bad-name");
            return;
        }
        switch (sub) {
            case "create" -> plugin.kits().createPublic(player, name);
            case "edit" -> EditorMenu.openPublic(plugin, player, name);
            case "delete" -> plugin.kits().deletePublic(player, name);
            default -> plugin.msg().send(player, "public-usage");
        }
    }

    private List<String> publicKitComplete(CommandSender sender, String[] args) {
        List<String> names = new ArrayList<>(plugin.kits().publicNames());
        if (args.length == 1) {
            List<String> options = new ArrayList<>(names);
            if (sender.hasPermission(ADMIN)) options.addAll(List.of("create", "edit", "delete"));
            return filter(options, args[0]);
        }
        if (args.length == 2 && sender.hasPermission(ADMIN)) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("edit") || sub.equals("delete")) return filter(names, args[1]);
        }
        return List.of();
    }
}
