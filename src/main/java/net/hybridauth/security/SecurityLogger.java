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

                // SQLite uses TEXT, MySQL uses TIMESTAMP. both compatible with
                // setString/setObject or default CURRENT_TIMESTAMP
                // But we set it manually to be sure
                long now = System.currentTimeMillis();
                if (plugin.getDatabaseManager().getConnection().getMetaData().getURL().contains("sqlite")) {
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
        PASSWORD_CHANGE
    }
}
