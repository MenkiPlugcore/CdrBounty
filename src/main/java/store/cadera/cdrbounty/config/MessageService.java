package store.cadera.cdrbounty.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

@SuppressWarnings("deprecation")
public final class MessageService {
    private final JavaPlugin plugin;
    private volatile FileConfiguration messages;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
    }

    public String text(String key) {
        return text(key, Map.of());
    }

    public String text(String key, Map<String, String> replacements) {
        String raw = messages.getString(key, key);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        String prefix = messages.getString("prefix", "");
        if (!key.equals("prefix")) raw = prefix + raw;
        return ChatColor.translateAlternateColorCodes('&', raw);
    }
}
