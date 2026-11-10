package dev.samar.vperplayerkit;

import dev.samar.vperplayerkit.announce.Announcements;
import dev.samar.vperplayerkit.command.Commands;
import dev.samar.vperplayerkit.config.DataStore;
import dev.samar.vperplayerkit.config.MenuConfig;
import dev.samar.vperplayerkit.config.Msg;
import dev.samar.vperplayerkit.discord.DiscordService;
import dev.samar.vperplayerkit.discord.ResultService;
import dev.samar.vperplayerkit.dialog.DialogText;
import dev.samar.vperplayerkit.dialog.ReportDialogs;
import dev.samar.vperplayerkit.dialog.GladiatorDialogs;
import dev.samar.vperplayerkit.dialog.RtpDialogs;
import dev.samar.vperplayerkit.event.EventListener;
import dev.samar.vperplayerkit.event.EventManager;
import dev.samar.vperplayerkit.ffa.FfaListener;
import dev.samar.vperplayerkit.ffa.FfaManager;
import dev.samar.vperplayerkit.gladiator.GladiatorListener;
import dev.samar.vperplayerkit.gladiator.GladiatorMatch;
import dev.samar.vperplayerkit.listener.MatchCommandListener;
import dev.samar.vperplayerkit.moderation.AutoCheckManager;
import dev.samar.vperplayerkit.moderation.BanManager;
import dev.samar.vperplayerkit.moderation.FreezeManager;
import dev.samar.vperplayerkit.moderation.ModerationListener;
import dev.samar.vperplayerkit.moderation.ReportManager;
import dev.samar.vperplayerkit.moderation.SfModeManager;
import dev.samar.vperplayerkit.nametag.NametagManager;
import dev.samar.vperplayerkit.ranks.RankManager;
import dev.samar.vperplayerkit.placeholder.PapiHook;
import dev.samar.vperplayerkit.placeholder.PlayerCache;
import dev.samar.vperplayerkit.gladiator.GladiatorManager;
import dev.samar.vperplayerkit.kit.KitService;
import dev.samar.vperplayerkit.listener.GuiListener;
import dev.samar.vperplayerkit.rtp.BiomeLocator;
import dev.samar.vperplayerkit.rtp.RtpService;
import dev.samar.vperplayerkit.rtpq.DuelListener;
import dev.samar.vperplayerkit.rtpq.DuelManager;
import dev.samar.vperplayerkit.rtpq.QueueManager;
import dev.samar.vperplayerkit.rtpq.ScoreBar;
import dev.samar.vperplayerkit.spectator.SpectatorListener;
import dev.samar.vperplayerkit.spectator.SpectatorManager;
import dev.samar.vperplayerkit.storage.Storage;
import dev.samar.vperplayerkit.teleport.TeleportListener;
import dev.samar.vperplayerkit.teleport.TeleportManager;
import dev.samar.vperplayerkit.web.RankEditorServer;
import dev.samar.vperplayerkit.totemprac.TotemPrac;
import dev.samar.vperplayerkit.totemprac.TotemPracListener;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class VPerPlayerKit extends JavaPlugin {
    private static VPerPlayerKit instance;

    private Storage storage;
    private MenuConfig menus;
    private DialogText dialogText;
    private RtpService rtp;
    private BiomeLocator locator;
    private FfaManager ffa;
    private DiscordService discord;
    private ResultService results;
    private Announcements announcements;
    private SpectatorManager spectators;
    private EventManager events;
    private ReportManager reports;
    private ReportDialogs reportDialogs;
    private SfModeManager sfmode;
    private FreezeManager freeze;
    private AutoCheckManager autocheck;
    private TeleportManager teleports;
    private BanManager bans;
    private ScoreBar scorebar;
    private RankManager ranks;
    private NametagManager nametags;
    private RankEditorServer web;
    private PlayerCache cache;
    private Msg msg;
    private KitService kits;
    private DataStore data;
    private DuelManager duels;
    private QueueManager queue;
    private RtpDialogs dialogs;
    private GladiatorManager gladiator;
    private GladiatorDialogs gladiatorDialogs;
    private TotemPrac totemPrac;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadAll();

        try {
            storage = new Storage(this);
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Could not open the database, disabling plugin", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        data = new DataStore(this);
        kits = new KitService(this);
        duels = new DuelManager(this);
        queue = new QueueManager(this);
        dialogs = new RtpDialogs(this);
        gladiator = new GladiatorManager(this);
        locator = new BiomeLocator(this);
        rtp = new RtpService(this);
        ffa = new FfaManager(this);
        cache = new PlayerCache(this);
        discord = new DiscordService(this);
        results = new ResultService(this, discord);
        announcements = new Announcements(this);
        spectators = new SpectatorManager(this);
        events = new EventManager(this);
        reports = new ReportManager(this);
        reportDialogs = new ReportDialogs(this);
        sfmode = new SfModeManager(this);
        freeze = new FreezeManager(this);
        autocheck = new AutoCheckManager(this);
        teleports = new TeleportManager(this);
        bans = new BanManager(this);
        scorebar = new ScoreBar(this);
        ranks = new RankManager(this);
        nametags = new NametagManager(this);
        web = new RankEditorServer(this);
        web.start();
        discord.start();
        gladiatorDialogs = new GladiatorDialogs(this);
        totemPrac = new TotemPrac(this);
        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(new DuelListener(this), this);
        getServer().getPluginManager().registerEvents(new GladiatorListener(this), this);
        getServer().getPluginManager().registerEvents(new SpectatorListener(this), this);
        getServer().getPluginManager().registerEvents(new EventListener(this), this);
        getServer().getPluginManager().registerEvents(new ModerationListener(this), this);
        getServer().getPluginManager().registerEvents(new TeleportListener(this), this);
        getServer().getPluginManager().registerEvents(new FfaListener(this), this);
        getServer().getPluginManager().registerEvents(new MatchCommandListener(this), this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                PapiHook.register(this);
                getLogger().info("PlaceholderAPI found, %vperplayerkit_...% placeholders registered.");
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "Could not register the PlaceholderAPI expansion", ex);
            }
        }
        getServer().getPluginManager().registerEvents(new TotemPracListener(this, totemPrac), this);
        new Commands(this).registerAll();
        kits.refreshPublicNames();
        getLogger().info("VPerPlayerKit enabled (" + getConfig().getString("storage.type", "sqlite") + ").");
    }

    @Override
    public void onDisable() {
        if (scorebar != null) scorebar.hideAll();
        if (web != null) web.stop();
        if (nametags != null) nametags.shutdown();
        if (bans != null) bans.shutdown();
        if (spectators != null) spectators.shutdown();
        if (discord != null) discord.shutdown();
        if (ffa != null) ffa.shutdown();
        if (gladiator != null) gladiator.shutdown();
        if (duels != null) duels.shutdown();
        if (totemPrac != null) totemPrac.shutdown();
        if (storage != null) storage.close();
    }

    /** Clears the player, restores health/food and teleports to the lobby spawn (or the main world spawn). */
    /**
     * Remembers that a player is in a match (kit items on them). If the server crashes mid-match the marker is still there when they
     * join again, so the leftovers are cleared then. sendToLobby clears it again.
     */
    public void markInMatch(UUID id, boolean on) {
        String owner = id.toString();
        if (on) async(() -> storage.put(owner, "inmatch", "on", new byte[] {1}));
        else async(() -> storage.delete(owner, "inmatch", "on"));
    }

    public void sendToLobby(Player player) {
        markInMatch(player.getUniqueId(), false);
        player.setWorldBorder(null);
        for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        player.getInventory().clear();
        kits.heal(player);
        Location lobby = data.lobby();
        if (lobby == null) lobby = Bukkit.getWorlds().get(0).getSpawnLocation();
        player.teleport(lobby);
    }

    public void reloadAll() {
        reloadConfig();
        File file = new File(getDataFolder(), "menus.yml");
        if (!file.exists()) saveResource("menus.yml", false);
        YamlConfiguration menusYaml = YamlConfiguration.loadConfiguration(file);
        InputStream defaults = getResource("menus.yml");
        if (defaults != null) {
            menusYaml.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        this.menus = new MenuConfig(menusYaml);

        File dialogFile = new File(getDataFolder(), "dialogs.yml");
        if (!dialogFile.exists()) saveResource("dialogs.yml", false);
        YamlConfiguration dialogYaml = YamlConfiguration.loadConfiguration(dialogFile);
        InputStream dialogDefaults = getResource("dialogs.yml");
        if (dialogDefaults != null) {
            dialogYaml.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(dialogDefaults, StandardCharsets.UTF_8)));
        }
        this.dialogText = new DialogText(dialogYaml);
        this.msg = new Msg(getConfig());
    }

    /** Runs {@code work} off the main thread, then hands the result to {@code done} on the main thread. */
    public <T> void async(Supplier<T> work, Consumer<T> done) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            T result = work.get();
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> done.accept(result));
        });
    }

    /** Fire and forget database work. */
    public void async(Runnable work) {
        Bukkit.getScheduler().runTaskAsynchronously(this, work);
    }

    public static VPerPlayerKit getInstance() {
        return instance;
    }

    public Storage storage() {
        return storage;
    }

    public DialogText dialogText() {
        return dialogText;
    }

    public BiomeLocator locator() {
        return locator;
    }

    public RtpService rtp() {
        return rtp;
    }

    public MenuConfig menus() {
        return menus;
    }

    public Msg msg() {
        return msg;
    }

    public KitService kits() {
        return kits;
    }

    public DataStore data() {
        return data;
    }

    public DuelManager duels() {
        return duels;
    }

    public QueueManager queue() {
        return queue;
    }

    public RtpDialogs dialogs() {
        return dialogs;
    }

    /** True while the player is in a queue duel or a Gladiator fight. */
    public boolean inMatch(Player player) {
        if (duels != null && duels.duelOf(player.getUniqueId()) != null) return true;
        if (ffa != null && ffa.fighting(player.getUniqueId())) return true;
        GladiatorMatch m = gladiator == null ? null : gladiator.matchOf(player.getUniqueId());
        return m != null && m.fighting();
    }

    /** Waiting in an event lobby or spectating: not free for queues, matches or random teleports. */
    public boolean busy(UUID id) {
        return (events != null && events.isBusy(id)) || (spectators != null && spectators.isSpectating(id))
                || (freeze != null && freeze.isFrozen(id))
                || (bans != null && bans.isBanned(id));
    }

    /** Not in a match, queue, event, spectating or frozen: free to teleport, be teleported or start something. */
    public boolean isFree(Player player) {
        UUID id = player.getUniqueId();
        return !inMatch(player) && !busy(id) && !queue.isQueued(id) && !duels.isBusy(id)
                && !gladiator.isBusy(id) && !ffa.isBusy(id);
    }

    public RankManager ranks() {
        return ranks;
    }

    public NametagManager nametags() {
        return nametags;
    }

    public RankEditorServer web() {
        return web;
    }

    public ScoreBar scorebar() {
        return scorebar;
    }

    public BanManager bans() {
        return bans;
    }

    public ReportManager reports() {
        return reports;
    }

    public ReportDialogs reportDialogs() {
        return reportDialogs;
    }

    public SfModeManager sfmode() {
        return sfmode;
    }

    public FreezeManager freeze() {
        return freeze;
    }

    public AutoCheckManager autocheck() {
        return autocheck;
    }

    public TeleportManager teleports() {
        return teleports;
    }

    public Announcements announcements() {
        return announcements;
    }

    public SpectatorManager spectators() {
        return spectators;
    }

    public EventManager events() {
        return events;
    }

    public ResultService results() {
        return results;
    }

    public DiscordService discord() {
        return discord;
    }

    public FfaManager ffa() {
        return ffa;
    }

    public PlayerCache cache() {
        return cache;
    }

    public GladiatorManager gladiator() {
        return gladiator;
    }

    public GladiatorDialogs gladiatorDialogs() {
        return gladiatorDialogs;
    }

    public TotemPrac totemPrac() {
        return totemPrac;
    }

    public int maxKits() {
        return Math.max(1, Math.min(14, getConfig().getInt("max-kits", 14)));
    }
}
