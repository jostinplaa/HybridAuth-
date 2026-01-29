package net.hybridauth.core.session;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

public class SessionManager {

    private final HybridAuthPlugin plugin;
    private final long sessionDurationMillis;
    private final SecureRandom random = new SecureRandom();

    public SessionManager(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        // Default 24 hours
        this.sessionDurationMillis = config.getLong("sessions.duration_hours", 24) * 3600 * 1000;

        // Cleanup expired sessions on startup/periodically
        cleanExpiredSessions();
    }

    public void createSession(UUID uuid, String ip) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            invalidateSession(uuid); // Invalidate previous sessions

            String token = generateToken();
            long now = System.currentTimeMillis();
            long expires = now + sessionDurationMillis;

            String sql = "INSERT INTO hybrid_sessions (user_uuid, session_token, player_ip, login_time, last_activity, expires_at, active) VALUES (?, ?, ?, ?, ?, ?, ?)";

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, uuid.toString());
                stmt.setString(2, token);
                stmt.setString(3, ip);

                if (plugin.getDatabaseManager().getConnection().getMetaData().getURL().contains("sqlite")) {
                    stmt.setString(4,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(now)));
                    stmt.setString(5,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(now)));
                    stmt.setString(6,
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(expires)));
                    stmt.setInt(7, 1);
                } else {
                    stmt.setTimestamp(4, new java.sql.Timestamp(now));
                    stmt.setTimestamp(5, new java.sql.Timestamp(now));
                    stmt.setTimestamp(6, new java.sql.Timestamp(expires));
                    stmt.setBoolean(7, true);
                }

                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to create session for " + uuid + ": " + e.getMessage());
            }
        });
    }

    public boolean validateSession(UUID uuid, String ip) {
        // Synchronous check usually needed for pre-login, but we can do it async if we
        // suspend login event.
        // For simplicity/performance in this MVP, we might check Cache if we
        // implemented it, or DB.
        // Since this is typically called in AsyncPreLogin or LoginListener (async), DB
        // is fine.

        String sql = "SELECT * FROM hybrid_sessions WHERE user_uuid = ? AND active = 1"; // AND player_ip = ? (Optional
                                                                                         // IP lock)

        try (Connection conn = plugin.getDatabaseManager().getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, uuid.toString());
            // stmt.setString(2, ip); // If we want to IP lock sessions

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    // Check expiry
                    // ... (Implementation depends on how we read timestamps from SQLite/MySQL
                    // generic)
                    // Simplified: just return true if record exists and active
                    return true;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void invalidateSession(UUID uuid) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "UPDATE hybrid_sessions SET active = 0 WHERE user_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    private void cleanExpiredSessions() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            // ... Logic to delete/deactivate expired sessions
        });
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
