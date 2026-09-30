package store.cadera.cdrbounty.npc;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public final class BountyNpcBindingService {
    private final JavaPlugin plugin;
    private final File file;
    private Integer npcId;
    private UUID npcUuid;
    private String npcName;

    public BountyNpcBindingService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npc.yml");
        load();
    }

    public void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        npcId = yaml.contains("bounty-master.id") ? yaml.getInt("bounty-master.id") : null;
        String rawUuid = yaml.getString("bounty-master.uuid");
        try { npcUuid = rawUuid == null || rawUuid.isBlank() ? null : UUID.fromString(rawUuid); }
        catch (IllegalArgumentException ex) { npcUuid = null; }
        npcName = yaml.getString("bounty-master.name");
    }

    public BindResult bindLookedAt(Player player) {
        Entity entity = player.getTargetEntity(8);
        if (entity == null) return BindResult.fail("Arahkan crosshair ke Citizens NPC maksimal 8 blok.");
        NPC npc = findNpc(entity);
        if (npc == null) return BindResult.fail("Entity yang dilihat bukan Citizens NPC.");

        npcId = npc.getId();
        npcUuid = npc.getUniqueId();
        npcName = npc.getName();
        save();
        return BindResult.ok(npcId, npcUuid, npcName);
    }

    public boolean matches(NPC npc) {
        if (npc == null || npcId == null) return false;
        if (npcUuid != null && npc.getUniqueId() != null) return npcUuid.equals(npc.getUniqueId());
        return npc.getId() == npcId;
    }

    public String info() {
        if (npcId == null) return "Belum ada Bounty Master NPC yang di-bind.";
        NPC resolved = resolve();
        String status = resolved == null ? "UNRESOLVED" : "OK";
        return "NPC=" + (npcName == null ? "unknown" : npcName)
                + " id=" + npcId + " uuid=" + (npcUuid == null ? "unknown" : npcUuid)
                + " status=" + status;
    }

    public void unbind() {
        npcId = null;
        npcUuid = null;
        npcName = null;
        save();
    }

    private NPC resolve() {
        for (NPCRegistry registry : CitizensAPI.getNPCRegistries()) {
            if (npcUuid != null) {
                NPC byUuid = registry.getByUniqueId(npcUuid);
                if (byUuid != null) return byUuid;
            }
            if (npcId != null) {
                NPC byId = registry.getById(npcId);
                if (byId != null) return byId;
            }
        }
        return null;
    }

    private NPC findNpc(Entity entity) {
        for (NPCRegistry registry : CitizensAPI.getNPCRegistries()) {
            NPC npc = registry.getNPC(entity);
            if (npc != null) return npc;
        }
        return null;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (npcId != null) yaml.set("bounty-master.id", npcId);
        if (npcUuid != null) yaml.set("bounty-master.uuid", npcUuid.toString());
        if (npcName != null) yaml.set("bounty-master.name", npcName);
        try {
            yaml.save(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save npc.yml", ex);
        }
    }

    public record BindResult(boolean success, String reason, Integer npcId, UUID npcUuid, String npcName) {
        public static BindResult ok(int id, UUID uuid, String name) {
            return new BindResult(true, "OK", id, uuid, name);
        }
        public static BindResult fail(String reason) {
            return new BindResult(false, reason, null, null, null);
        }
    }
}
