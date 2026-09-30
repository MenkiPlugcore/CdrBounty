package store.cadera.cdrbounty.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Lightweight config migration for production upgrades.
 * Missing keys are copied from the bundled defaults while existing values are preserved.
 */
public final class ConfigMigrationService {
    public static final int CURRENT_VERSION = 9;

    private final JavaPlugin plugin;

    public ConfigMigrationService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Result migrate() {
        plugin.reloadConfig();
        int from = Math.max(0, plugin.getConfig().getInt("config-version", 0));
        if (from >= CURRENT_VERSION) return new Result(false, from, CURRENT_VERSION, null);

        File configFile = new File(plugin.getDataFolder(), "config.yml");
        File backup = null;
        if (configFile.isFile()) {
            backup = new File(plugin.getDataFolder(), "config.yml.bak-v" + from + "-" + Instant.now().toEpochMilli());
            try {
                Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            } catch (IOException ex) {
                throw new IllegalStateException("Could not create config migration backup", ex);
            }
        }

        plugin.getConfig().options().copyDefaults(true);
        plugin.getConfig().set("config-version", CURRENT_VERSION);
        plugin.saveConfig();
        plugin.reloadConfig();
        return new Result(true, from, CURRENT_VERSION, backup);
    }

    public record Result(boolean migrated, int fromVersion, int toVersion, File backupFile) {}
}
