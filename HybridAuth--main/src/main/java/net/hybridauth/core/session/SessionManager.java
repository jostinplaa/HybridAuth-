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

        // ANTES: config.getLong("sessions.duration_hours", 24)
        // AHORA: Leer de la ruta correcta en config.yml

        long durationSeconds = config.getLong("security.sessions.duration", 86400); // 24 horas en segundos
        this.sessionDurationMillis = durationSeconds * 1000;

        plugin.getLogger().info(
                "SessionManager initialized - Duration: " + durationSeconds + "s (" + (durationSeconds / 3600) + "h)");

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

                // FIX BUG #4: Use existing connection instead of creating a new one
                if (conn.getMetaData().getURL().contains("sqlite")) {
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
        String sql;
        boolean isSQLite = false;

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            isSQLite = conn.getMetaData().getURL().contains("sqlite");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to detect database type: " + e.getMessage());
            return false;
        }

        if (isSQLite) {
            // SQLite: Comparar timestamps como strings
            sql = "SELECT * FROM hybrid_sessions WHERE user_uuid = ? AND active = 1 " +
                    "AND datetime(expires_at) > datetime('now')";
        } else {
            // MySQL: Comparar timestamps normales
            sql = "SELECT * FROM hybrid_sessions WHERE user_uuid = ? AND active = 1 " +
                    "AND expires_at > NOW()";
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, uuid.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    // FIX BUG #7: Validar que la IP no haya cambiado (detecta session hijacking)
                    String sessionIP = rs.getString("player_ip");
                    if (sessionIP != null && !sessionIP.equals(ip)) {
                        plugin.getLogger().warning("[Session] IP MISMATCH for " + uuid +
                                " - Expected: " + sessionIP + ", Got: " + ip);
                        plugin.getSecurityLogger().logWarning(
                                "Session IP changed for " + uuid + " from " + sessionIP + " to " + ip);

                        // Invalidar sesión comprometida
                        invalidateSession(uuid);
                        return false;
                    }

                    // Sesión válida y NO expirada
                    plugin.getLogger().info("[Session] Valid session found for " + uuid);

                    // Actualizar last_activity (opcional)
                    updateLastActivity(uuid);

                    return true;
                } else {
                    plugin.getLogger().info("[Session] No valid session for " + uuid + " (expired or not found)");
                    return false;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error validating session: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in SessionManager", );
        }
        return false;
    }

    /**
     * Actualiza el timestamp de última actividad de una sesión
     */
    private void updateLastActivity(UUID uuid) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean isSQLite = false;

            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                isSQLite = conn.getMetaData().getURL().contains("sqlite");
            } catch (SQLException e) {
                return;
            }

            String sql;
            if (isSQLite) {
                sql = "UPDATE hybrid_sessions SET last_activity = datetime('now') WHERE user_uuid = ? AND active = 1";
            } else {
                sql = "UPDATE hybrid_sessions SET last_activity = NOW() WHERE user_uuid = ? AND active = 1";
            }

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to update session activity: " + e.getMessage());
            }
        });
    }

    public void invalidateSession(UUID uuid) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "UPDATE hybrid_sessions SET active = 0 WHERE user_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in SessionManager", );
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
