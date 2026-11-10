package dev.samar.vperplayerkit.discord;

import dev.samar.vperplayerkit.VPerPlayerKit;
import dev.samar.vperplayerkit.rtpq.Tier;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discord account linking (/verify) and tier announcements. This class has no Discord library types,
 * the bot itself (DiscordBot, JDA) is only loaded when discord.enabled is true.
 * Link data lives in the normal database, so it works across servers.
 */
public final class DiscordService {

    /** Implemented by the JDA bot. */
    public interface Sink {
        void announce(String message);

        void post(Result result);

        void shutdown();
    }

    public record CodeEntry(UUID uuid, String name, long expires) {
    }

    /**
     * A finished Discord message (built by ResultService, sent by the bot). embed = true sends an embed (normal results)
     * with {@code content} as the text above it, otherwise {@code text} is sent as a plain message (high results).
     */
    public record Result(String channelId, String content, boolean embed, String authorTitle, String authorIconUrl,
                         String thumbnailUrl, int color, List<String[]> fields, String text, List<String> reactions) {
    }

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final VPerPlayerKit plugin;
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();
    private final Map<UUID, String> tags = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile Sink sink;

    public DiscordService(VPerPlayerKit plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("discord.enabled", false);
    }

    public int codeMinutes() {
        return Math.max(1, plugin.getConfig().getInt("discord.code-expire-seconds", 300) / 60);
    }

    // ------------------------------------------------------------------ bot lifecycle

    public void start() {
        if (!enabled()) return;
        String token = plugin.getConfig().getString("discord.token", "");
        if (token == null || token.isBlank()) {
            plugin.getLogger().warning("discord.enabled is true but discord.token is empty.");
            return;
        }
        plugin.async(() -> {
            try {
                DiscordBot bot = new DiscordBot(plugin, this);
                bot.start();
                sink = bot;
                plugin.getLogger().info("Discord bot connected.");
            } catch (Throwable ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not start the Discord bot", ex);
            }
        });
    }

    public void shutdown() {
        Sink s = sink;
        if (s != null) s.shutdown();
    }

    // ------------------------------------------------------------------ verification codes

    public String generateCode(Player player) {
        long now = System.currentTimeMillis();
        codes.values().removeIf(e -> e.expires() < now || e.uuid().equals(player.getUniqueId()));
        int length = Math.max(4, Math.min(10, plugin.getConfig().getInt("discord.code-length", 6)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        String code = sb.toString();
        long ttl = Math.max(30, plugin.getConfig().getInt("discord.code-expire-seconds", 300)) * 1000L;
        codes.put(code, new CodeEntry(player.getUniqueId(), player.getName(), now + ttl));
        return code;
    }

    /** The entry for a valid, unexpired code or null. */
    public CodeEntry peek(String code) {
        CodeEntry entry = codes.get(code.toUpperCase());
        if (entry == null) return null;
        if (entry.expires() < System.currentTimeMillis()) {
            codes.remove(code.toUpperCase());
            return null;
        }
        return entry;
    }

    public void consume(String code) {
        codes.remove(code.toUpperCase());
    }

    // ------------------------------------------------------------------ links (blocking database calls)

    public String discordId(UUID uuid) {
        byte[] data = plugin.storage().get(uuid.toString(), "dclink", "id");
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    public String discordTag(UUID uuid) {
        byte[] data = plugin.storage().get(uuid.toString(), "dclink", "tag");
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    public UUID uuidOfDiscord(String discordId) {
        byte[] data = plugin.storage().get("global", "dcrev", discordId);
        if (data == null) return null;
        try {
            return UUID.fromString(new String(data, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void link(UUID uuid, String discordId, String tag) {
        UUID previous = uuidOfDiscord(discordId);
        if (previous != null && !previous.equals(uuid)) unlink(previous);      // one account per Discord user
        plugin.storage().put(uuid.toString(), "dclink", "id", discordId.getBytes(StandardCharsets.UTF_8));
        plugin.storage().put(uuid.toString(), "dclink", "tag", tag.getBytes(StandardCharsets.UTF_8));
        plugin.storage().put("global", "dcrev", discordId, uuid.toString().getBytes(StandardCharsets.UTF_8));
        tags.put(uuid, tag);
    }

    /** Returns false when the player had no link. */
    public boolean unlink(UUID uuid) {
        String id = discordId(uuid);
        if (id == null) return false;
        plugin.storage().delete(uuid.toString(), "dclink", "id");
        plugin.storage().delete(uuid.toString(), "dclink", "tag");
        plugin.storage().delete("global", "dcrev", id);
        tags.remove(uuid);
        return true;
    }

    /** Fills the placeholder cache (call off the main thread). */
    public void loadTag(UUID uuid) {
        String tag = discordTag(uuid);
        if (tag == null) tags.remove(uuid);
        else tags.put(uuid, tag);
    }

    public String cachedTag(UUID uuid) {
        return tags.get(uuid);
    }

    // ------------------------------------------------------------------ tier congratulations

    /** Sends a finished result to Discord (does nothing while the bot is not connected). */
    public void post(Result result) {
        Sink s = sink;
        if (s != null) s.post(result);
    }

    /** In-game "Congratulations" title for a player who reached a new tier (Discord gets the result post instead). */
    public void congratulate(UUID uuid, String name, Tier tier) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        player.showTitle(Title.title(plugin.msg().get("tier-congrats-title"),
                plugin.msg().get("tier-congrats-subtitle", "{tier}", tier.colored())));
    }
}
