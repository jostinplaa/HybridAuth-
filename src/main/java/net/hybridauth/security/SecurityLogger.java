package net.hybridauth.security;

import net.hybridauth.HybridAuthPlugin;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

public class SecurityLogger {

    private final HybridAuthPlugin plugin;

    public SecurityLogger(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void log(EventType type, String username, UUID uuid, String ip, String details) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO hybrid_security_logs (event_type, username, uuid, ip_address, details, timestamp) VALUES (?, ?, ?, ?, ?, ?)";

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, type.name());
                stmt.setString(2, username);
                stmt.setString(3, uuid != null ? uuid.toString() : null);
                stmt.setString(4, ip);
                stmt.setString(5, details);

                // Usar la MISMA conexión para detectar tipo de DB (no abrir otra)
                long now = System.currentTimeMillis();
                boolean isSQLite = conn.getMetaData().getURL().contains("sqlite");
                if (isSQLite) {
                    stmt.setString(6,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(now)));
                } else {
                    stmt.setTimestamp(6, new java.sql.Timestamp(now));
                }

                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to log security event: " + e.getMessage());
            }
        });
    }

    public enum EventType {
        LOGIN_SUCCESS,
        LOGIN_FAIL,
        REGISTER,
        PREMIUM_DETECT,
        RATE_LIMIT,
        SUSPICIOUS,
        LOGOUT,
        PASSWORD_CHANGE,
        IMPOSTOR_DETECTED,
        PREMIUM_AUTO_LOGIN,
        PREMIUM_AUTO_REGISTER,
        MIGRATION
    }

    // Helper methods para logging simplificado
    public void logInfo(String message) {
        plugin.getLogger().info("[Security] " + message);
    }

    public void logWarning(String message) {
        plugin.getLogger().warning("[Security] " + message);
    }

    public void logCritical(String message) {
        plugin.getLogger().severe("[Security] ⚠ CRITICAL: " + message);
    }
}
