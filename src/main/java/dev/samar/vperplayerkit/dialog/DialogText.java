package dev.samar.vperplayerkit.dialog;

import dev.samar.vperplayerkit.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** Reads every dialog string from dialogs.yml. */
public final class DialogText {
    private final FileConfiguration cfg;

    public DialogText(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    /** MiniMessage text with {placeholders} replaced. Missing keys show up as a red marker. */
    public String get(String path, String... kv) {
        String raw = cfg.getString(path);
        if (raw == null) raw = "<red>[missing " + path + "]";
        return Text.replace(raw, kv);
    }

    /** Like get but null when the key is missing or empty (used for optional tooltips). */
    public String opt(String path, String... kv) {
        String raw = cfg.getString(path);
        if (raw == null || raw.isEmpty()) return null;
        return Text.replace(raw, kv);
    }

    /** Dialog title wrapped in common.title-format. */
    public String title(String path, String... kv) {
        String format = cfg.getString("common.title-format", "{title}");
        return format.replace("{title}", get(path, kv));
    }

    /** A list of lines; a single string counts as one line. */
    public List<String> lines(String path, String... kv) {
        List<String> out = new ArrayList<>();
        if (cfg.isList(path)) {
            for (String line : cfg.getStringList(path)) out.add(Text.replace(line, kv));
        } else if (cfg.isString(path)) {
            out.add(get(path, kv));
        }
        return out;
    }

    public int integer(String path, int def) {
        return cfg.getInt(path, def);
    }

    public List<String> keys(String path) {
        ConfigurationSection section = cfg.getConfigurationSection(path);
        if (section == null || section.getKeys(false).isEmpty()) {       // an old dialogs.yml without this section
            var defaults = cfg.getDefaults();
            section = defaults == null ? null : defaults.getConfigurationSection(path);
        }
        return section == null ? new ArrayList<>() : new ArrayList<>(section.getKeys(false));
    }
}
