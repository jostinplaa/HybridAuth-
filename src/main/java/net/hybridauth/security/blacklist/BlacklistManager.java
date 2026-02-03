package net.hybridauth.security.blacklist;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona la blacklist de IPs con persistencia y auto-expiración
 * 
 * Features:
 * - Blacklist temporal y permanente
 * - Persistencia entre reinicios (blacklist.yml)
 * - Auto-expiración de bloqueos temporales
 * - Comandos admin para gestionar blacklist
 * - Logs detallados de todas las acciones
 * 
 * @version 1.2.0
 */
public class BlacklistManager {

    private final HybridAuthPlugin plugin;
    private final File blacklistFile;
    private FileConfiguration blacklistConfig;

    // IP -> BlacklistEntry
    private final Map<String, BlacklistEntry> blacklistedIPs;

    // Cleanup task ID
    private int cleanupTaskId = -1;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public BlacklistManager(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.blacklistedIPs = new ConcurrentHashMap<>();
        this.blacklistFile = new File(plugin.getDataFolder(), "blacklist.yml");

        loadBlacklist();
        startCleanupTask();

        plugin.getLogger().info("✓ BlacklistManager initialized with " +
                blacklistedIPs.size() + " entries");
    }

    /**
     * Carga la blacklist desde archivo
     */
    private void loadBlacklist() {
        if (!blacklistFile.exists()) {
            try {
                blacklistFile.createNewFile();
                blacklistConfig = YamlConfiguration.loadConfiguration(blacklistFile);
                blacklistConfig.set("version", "1.0");
                blacklistConfig.set("entries", new ArrayList<>());
                blacklistConfig.save(blacklistFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create blacklist.yml: " + e.getMessage());
                return;
            }
        }

        blacklistConfig = YamlConfiguration.loadConfiguration(blacklistFile);

        // Cargar entradas
        if (blacklistConfig.contains("entries")) {
            List<Map<?, ?>> entries = blacklistConfig.getMapList("entries");

            for (Map<?, ?> entry : entries) {
                String ip = (String) entry.get("ip");
                String reason = (String) entry.get("reason");
                boolean permanent = entry.containsKey("permanent") ? (Boolean) entry.get("permanent") : false;
                long expiresAt = entry.containsKey("expires_at") ? ((Number) entry.get("expires_at")).longValue() : 0;
                String blockedBy = entry.containsKey("blocked_by") ? (String) entry.get("blocked_by") : "SYSTEM";
                long blockedAt = entry.containsKey("blocked_at") ? ((Number) entry.get("blocked_at")).longValue()
                        : System.currentTimeMillis();

                BlacklistEntry blacklistEntry = new BlacklistEntry(
                        ip, reason, permanent, expiresAt, blockedBy, blockedAt);

                // Solo cargar si no ha expirado
                if (permanent || expiresAt > System.currentTimeMillis()) {
                    blacklistedIPs.put(ip, blacklistEntry);
                }
            }
        }

        plugin.getLogger().info("Loaded " + blacklistedIPs.size() +
                " blacklist entries from file");
    }

    /**
     * Guarda la blacklist a archivo
     */
    private void saveBlacklist() {
        List<Map<String, Object>> entries = new ArrayList<>();

        for (BlacklistEntry entry : blacklistedIPs.values()) {
            Map<String, Object> entryMap = new HashMap<>();
            entryMap.put("ip", entry.ip);
            entryMap.put("reason", entry.reason);
            entryMap.put("permanent", entry.permanent);
            entryMap.put("expires_at", entry.expiresAt);
            entryMap.put("blocked_by", entry.blockedBy);
            entryMap.put("blocked_at", entry.blockedAt);
            entries.add(entryMap);
        }

        blacklistConfig.set("entries", entries);
        blacklistConfig.set("last_updated", System.currentTimeMillis());

        try {
            blacklistConfig.save(blacklistFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save blacklist.yml: " + e.getMessage());
        }
    }

    /**
     * Inicia tarea de limpieza automática (cada 5 minutos)
     */
    private void startCleanupTask() {
        cleanupTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin,
                this::cleanupExpired,
                20L * 60 * 5, // delay 5 min
                20L * 60 * 5 // repeat cada 5 min
        );
    }

    /**
     * Limpia entradas expiradas
     */
    private void cleanupExpired() {
        int removed = 0;
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<String, BlacklistEntry>> iterator = blacklistedIPs.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, BlacklistEntry> entry = iterator.next();
            BlacklistEntry blacklistEntry = entry.getValue();

            if (!blacklistEntry.permanent && blacklistEntry.expiresAt <= now) {
                iterator.remove();
                removed++;

                plugin.getLogger().info("Blacklist expired: " + entry.getKey());
            }
        }

        if (removed > 0) {
            saveBlacklist();
            plugin.getLogger().info("Cleaned up " + removed + " expired blacklist entries");
        }
    }

    /**
     * Bloquea una IP temporalmente
     */
    public void blockIP(String ip, long durationSeconds, String reason, String blockedBy) {
        long expiresAt = System.currentTimeMillis() + (durationSeconds * 1000);

        BlacklistEntry entry = new BlacklistEntry(
                ip,
                reason,
                false,
                expiresAt,
                blockedBy,
                System.currentTimeMillis());

        blacklistedIPs.put(ip, entry);
        saveBlacklist();

        plugin.getLogger().warning(
                String.format("IP BLOCKED (TEMP): %s for %ds by %s - Reason: %s",
                        ip, durationSeconds, blockedBy, reason));

        // Log de seguridad
        plugin.getSecurityLogger().logWarning(
                String.format("IP_BLOCKED_TEMP: ip=%s, duration=%ds, reason=%s, by=%s",
                        ip, durationSeconds, reason, blockedBy));

        // Alerta Discord
        plugin.getDiscordWebhook().notifyIPBlacklisted(ip, reason, blockedBy, false);
    }

    /**
     * Bloquea una IP permanentemente
     */
    public void blockIPPermanent(String ip, String reason, String blockedBy) {
        BlacklistEntry entry = new BlacklistEntry(
                ip,
                reason,
                true,
                0,
                blockedBy,
                System.currentTimeMillis());

        blacklistedIPs.put(ip, entry);
        saveBlacklist();

        plugin.getLogger().warning(
                String.format("IP BLOCKED (PERMANENT): %s by %s - Reason: %s",
                        ip, blockedBy, reason));

        // Log crítico
        plugin.getSecurityLogger().logCritical(
                String.format("IP_BLOCKED_PERMANENT: ip=%s, reason=%s, by=%s",
                        ip, reason, blockedBy));

        // Alerta Discord
        plugin.getDiscordWebhook().notifyIPBlacklisted(ip, reason, blockedBy, true);
    }

    /**
     * Desbloquea una IP
     */
    public boolean unblockIP(String ip, String unblockedBy) {
        BlacklistEntry removed = blacklistedIPs.remove(ip);

        if (removed != null) {
            saveBlacklist();

            plugin.getLogger().info(
                    String.format("IP UNBLOCKED: %s by %s (was blocked: %s)",
                            ip, unblockedBy, removed.reason));

            plugin.getSecurityLogger().logInfo(
                    String.format("IP_UNBLOCKED: ip=%s, by=%s, original_reason=%s",
                            ip, unblockedBy, removed.reason));

            // Alerta Discord
            plugin.getDiscordWebhook().notifyIPUnblocked(ip, unblockedBy);

            return true;
        }

        return false;
    }

    /**
     * Verifica si una IP está bloqueada
     */
    public boolean isBlocked(String ip) {
        BlacklistEntry entry = blacklistedIPs.get(ip);

        if (entry == null) {
            return false;
        }

        // Si es permanente, está bloqueada
        if (entry.permanent) {
            return true;
        }

        // Si es temporal, verificar expiración
        if (entry.expiresAt > System.currentTimeMillis()) {
            return true;
        }

        // Expiró, remover
        blacklistedIPs.remove(ip);
        saveBlacklist();
        return false;
    }

    /**
     * Obtiene info de bloqueo de una IP
     */
    public BlacklistEntry getEntry(String ip) {
        return blacklistedIPs.get(ip);
    }

    /**
     * Obtiene tiempo restante de bloqueo en segundos
     */
    public long getSecondsRemaining(String ip) {
        BlacklistEntry entry = blacklistedIPs.get(ip);

        if (entry == null) {
            return 0;
        }

        if (entry.permanent) {
            return -1; // -1 = permanente
        }

        long remaining = (entry.expiresAt - System.currentTimeMillis()) / 1000;
        return Math.max(0, remaining);
    }

    /**
     * Lista todas las IPs bloqueadas
     */
    public List<BlacklistEntry> getAllEntries() {
        return new ArrayList<>(blacklistedIPs.values());
    }

    /**
     * Obtiene estadísticas
     */
    public BlacklistStats getStats() {
        int total = blacklistedIPs.size();
        int permanent = 0;
        int temporary = 0;

        for (BlacklistEntry entry : blacklistedIPs.values()) {
            if (entry.permanent) {
                permanent++;
            } else {
                temporary++;
            }
        }

        return new BlacklistStats(total, permanent, temporary);
    }

    /**
     * Limpia todas las entradas temporales expiradas
     */
    public int cleanupAll() {
        cleanupExpired();
        return blacklistedIPs.size();
    }

    /**
     * Cierra el manager
     */
    public void shutdown() {
        if (cleanupTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(cleanupTaskId);
        }
        saveBlacklist();
    }

    /**
     * Entry de blacklist
     */
    public static class BlacklistEntry {
        public final String ip;
        public final String reason;
        public final boolean permanent;
        public final long expiresAt;
        public final String blockedBy;
        public final long blockedAt;

        public BlacklistEntry(String ip, String reason, boolean permanent,
                long expiresAt, String blockedBy, long blockedAt) {
            this.ip = ip;
            this.reason = reason;
            this.permanent = permanent;
            this.expiresAt = expiresAt;
            this.blockedBy = blockedBy;
            this.blockedAt = blockedAt;
        }

        public String getFormattedExpiry() {
            if (permanent) {
                return "PERMANENT";
            }

            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(expiresAt),
                    ZoneId.systemDefault());

            return dateTime.format(DATE_FORMAT);
        }

        public String getFormattedBlockedAt() {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(blockedAt),
                    ZoneId.systemDefault());

            return dateTime.format(DATE_FORMAT);
        }

        public String getTimeRemaining() {
            if (permanent) {
                return "NEVER";
            }

            long seconds = (expiresAt - System.currentTimeMillis()) / 1000;

            if (seconds <= 0) {
                return "EXPIRED";
            }

            long days = seconds / 86400;
            long hours = (seconds % 86400) / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;

            if (days > 0) {
                return String.format("%dd %dh %dm", days, hours, minutes);
            } else if (hours > 0) {
                return String.format("%dh %dm", hours, minutes);
            } else if (minutes > 0) {
                return String.format("%dm %ds", minutes, secs);
            } else {
                return String.format("%ds", secs);
            }
        }
    }

    /**
     * Estadísticas de blacklist
     */
    public static class BlacklistStats {
        public final int total;
        public final int permanent;
        public final int temporary;

        public BlacklistStats(int total, int permanent, int temporary) {
            this.total = total;
            this.permanent = permanent;
            this.temporary = temporary;
        }
    }
}
