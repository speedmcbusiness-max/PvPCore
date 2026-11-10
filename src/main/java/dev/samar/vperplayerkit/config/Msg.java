package dev.samar.vperplayerkit.config;

import dev.samar.vperplayerkit.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

public final class Msg {
    private final FileConfiguration cfg;

    public Msg(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public Component get(String key, String... kv) {
        String raw = cfg.getString("messages." + key, "<red>Missing message: " + key);
        raw = raw.replace("{prefix}", cfg.getString("messages.prefix", ""));
        return Text.mm(Text.replace(raw, kv));
    }

    /** The message as MiniMessage text (placeholders replaced) so it can be embedded in another message. */
    public String raw(String key, String... kv) {
        String raw = cfg.getString("messages." + key, "");
        raw = raw.replace("{prefix}", cfg.getString("messages.prefix", ""));
        return Text.replace(raw, kv);
    }

    /** The unparsed template (prefix filled in), for building bigger messages. */
    public String raw(String key) {
        String raw = cfg.getString("messages." + key, "");
        return raw.replace("{prefix}", cfg.getString("messages.prefix", ""));
    }

    public void send(CommandSender to, String key, String... kv) {
        to.sendMessage(get(key, kv));
    }
}
