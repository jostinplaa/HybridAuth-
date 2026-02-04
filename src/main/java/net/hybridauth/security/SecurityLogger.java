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

                // Usar la MISMA conexin para detectar tipo de DB (no abrir otra)
                long now = System.currentTimeMillis();
                boolean isSQLite = conn.getMetaData().getURL().contains("sqlite");
                if (isSQLite) {
                    stmt.setString(6,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(now)));
                } else {
                    stmt.setTimestamp(6, new java.sql.Timestamp(now));
                }

                stmt.executeUpdate();

                // v1.7.0 Trigger Alerts
                triggerAlert(type, username, ip, details);

            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to log security event: " + e.getMessage());
            }
        });
    }

    private void triggerAlert(EventType type, String username, String ip, String details) {
        if (plugin.getAlertManager() == null)
            return;

        switch (type) {
            case RATE_LIMIT:
                plugin.getAlertManager().sendAlert(
                        net.hybridauth.alerts.AlertService.AlertType.BRUTE_FORCE,
                        "Brute Force Detected (Rate Limit Exceeded)",
                        "User: " + username + " | IP: " + ip + " | " + details);
                break;
            case IMPOSTOR_DETECTED:
                plugin.getAlertManager().sendAlert(
                        net.hybridauth.alerts.AlertService.AlertType.IMPOSTOR,
                        "Impostor Detected (Premium UUID Mismatch)",
                        "User: " + username + " | IP: " + ip + " | " + details);
                break;
            case ADMIN_LOGIN:
                plugin.getAlertManager().sendAlert(
                        net.hybridauth.alerts.AlertService.AlertType.ADMIN_LOGIN,
                        "Admin Login Detected",
                        "User: " + username + " | IP: " + ip + " | " + details);
                break;
            default:
                // No alert for other events
                break;
        }
    }

    /**
     * Cleans up security logs older than the specified number of days from the
     * database.
     * 
     * @param days Retention period in days
     */
    public void cleanupOldLogs(int days) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            // Calculate cutoff date
            long cutoffTime = System.currentTimeMillis() - ((long) days * 24 * 60 * 60 * 1000);

            // Query compatible with both MySQL and SQLite (using timestamp or date
            // comparison)
            // Assuming 'timestamp' column is DATETIME or similar standard format,
            // but for safety with our current PreparedStatement in log(), we use a
            // parameter.
            // Note: DB implementation might vary.
            // SQLite: datetime(timestamp) < datetime(now, '-days')
            // MySQL: timestamp < DATE_SUB(NOW(), INTERVAL days DAY)
            // EASIEST: DELETE WHERE timestamp < ? (as properly formatted string/object)

            String sql = "DELETE FROM hybrid_security_logs WHERE timestamp < ?";

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                boolean isSQLite = conn.getMetaData().getURL().contains("sqlite");
                if (isSQLite) {
                    // SQLite text format: yyyy-MM-dd HH:mm:ss
                    stmt.setString(1,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                    .format(new java.util.Date(cutoffTime)));
                } else {
                    stmt.setTimestamp(1, new java.sql.Timestamp(cutoffTime));
                }

                int deleted = stmt.executeUpdate();
                if (deleted > 0) {
                    plugin.getLogger().info("[Security] Cleaned up " + deleted + " old log entries from database.");
                }

            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to cleanup old logs: " + e.getMessage());
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
        MIGRATION,
        ADMIN_LOGIN
    }

    // Helper methods para logging simplificado
    public void logInfo(String message) {
        plugin.getLogger().info("[Security] " + message);
    }

    public void logWarning(String message) {
        plugin.getLogger().warning("[Security] " + message);
    }

    public void logCritical(String message) {
        plugin.getLogger().severe("[Security]  CRITICAL: " + message);
    }
}

