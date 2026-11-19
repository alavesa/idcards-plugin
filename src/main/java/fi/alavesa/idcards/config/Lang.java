package fi.alavesa.idcards.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Player-facing text: plugins/IdCards/lang/&lt;language&gt;.yml, MiniMessage. Keys missing from the chosen file fall
 * back to the English file bundled in the jar, so an older translation keeps working after an update.
 */
public final class Lang {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final Map<String, String> text = new HashMap<>();

    public Lang(Plugin plugin, String language) {
        try (InputStream in = plugin.getResource("lang/en.yml")) {
            if (in != null) read(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not read the bundled lang/en.yml: " + e);
        }
        // the English file on disk follows the plugin: an outdated one is set aside, not mixed with new texts
        File english = new File(plugin.getDataFolder(), "lang/en.yml");
        int bundled = Integer.parseInt(text.getOrDefault("version", "0"));
        if (english.exists() && YamlConfiguration.loadConfiguration(english).getInt("version", 1) < bundled) {
            File backup = new File(plugin.getDataFolder(), "lang/en.yml.old");
            backup.delete();
            if (english.renameTo(backup)) plugin.getLogger().info("lang/en.yml was from an older version: kept as en.yml.old, new texts written");
        }
        if (!english.exists()) plugin.saveResource("lang/en.yml", false);
        File file = new File(plugin.getDataFolder(), "lang/" + language + ".yml");
        if (file.exists()) read(YamlConfiguration.loadConfiguration(file));
        else plugin.getLogger().warning("lang/" + language + ".yml not found; using English");
    }

    private void read(YamlConfiguration yaml) {
        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key) || yaml.isInt(key)) text.put(key, String.valueOf(yaml.get(key)));
        }
    }

    /** The raw string, or the key itself when it is missing everywhere. */
    public String raw(String key) {
        return text.getOrDefault(key, key);
    }

    public Component get(String key, TagResolver... resolvers) {
        return MM.deserialize(raw(key), resolvers);
    }

    /** Sends a message with the configured prefix; an empty string in the lang file sends nothing. */
    public void send(Audience to, String key, TagResolver... resolvers) {
        if (raw(key).isEmpty()) return;
        to.sendMessage(get("prefix").append(get(key, resolvers)));
    }

    /** Placeholder whose value is inserted as plain text (never parsed: safe for names typed by players). */
    public static TagResolver ph(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver ph(String name, Component value) {
        return Placeholder.component(name, value);
    }
}
