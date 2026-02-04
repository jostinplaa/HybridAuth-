package net.hybridauth.data.dao;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.hybridauth.data.DatabaseManager;
import net.hybridauth.data.model.User;

import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class UserDAO {

    private final DatabaseManager dbManager;
    private final Cache<UUID, User> userCache;
    private final Cache<String, User> usernameCache;

    public UserDAO(DatabaseManager dbManager) {
        this.dbManager = dbManager;
        // Cache de 1 hora, máximo 1000 usuarios
        this.userCache = Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .maximumSize(1000)
                .build();

        this.usernameCache = Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .maximumSize(1000)
                .build();
    }

    public void createUser(User user) throws SQLException {
        String sql = "INSERT INTO hybrid_users (uuid, username, password_hash, auth_type, premium_uuid, last_ip, registered_at, last_login_date, total_logins, status, totp_secret, totp_enabled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getUuid().toString());
            stmt.setString(2, user.getUsername());
            stmt.setString(3, user.getPasswordHash());
            stmt.setString(4, user.getAuthType().name());
            stmt.setString(5, user.getPremiumUuid() != null ? user.getPremiumUuid().toString() : null);
            stmt.setString(6, user.getLastIp());
            stmt.setTimestamp(7, user.getRegisteredAt());
            stmt.setTimestamp(8, user.getLastLoginDate());
            stmt.setInt(9, user.getTotalLogins());
            stmt.setString(10, user.getStatus());
            stmt.setString(11, user.getTotpSecret());
            stmt.setBoolean(12, user.isTotpEnabled());
            stmt.executeUpdate();

            // Cachear
            userCache.put(user.getUuid(), user);
            usernameCache.put(user.getUsername().toLowerCase(), user);
        }
    }

    public Optional<User> getUserByUUID(UUID uuid) {
        // Intentar obtener del cache primero
        User cached = userCache.getIfPresent(uuid);
        if (cached != null) {
            return Optional.of(cached);
        }

        String sql = "SELECT * FROM hybrid_users WHERE uuid = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    User user = mapResultSetToUser(rs);
                    // Cachear
                    userCache.put(uuid, user);
                    usernameCache.put(user.getUsername().toLowerCase(), user);
                    return Optional.of(user);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in UserDAO", );
        }
        return Optional.empty();
    }

    public Optional<User> getUserByUsername(String username) {
        // Intentar obtener del cache primero
        User cached = usernameCache.getIfPresent(username.toLowerCase());
        if (cached != null) {
            return Optional.of(cached);
        }

        String sql = "SELECT * FROM hybrid_users WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    User user = mapResultSetToUser(rs);
                    // Cachear
                    userCache.put(user.getUuid(), user);
                    usernameCache.put(username.toLowerCase(), user);
                    return Optional.of(user);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in UserDAO", );
        }
        return Optional.empty();
    }

    public void updateUser(User user) throws SQLException {
        String sql = "UPDATE hybrid_users SET password_hash = ?, auth_type = ?, premium_uuid = ?, last_ip = ?, last_login_date = ?, total_logins = ?, status = ?, totp_secret = ?, totp_enabled = ? WHERE uuid = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getPasswordHash());
            stmt.setString(2, user.getAuthType().name());
            stmt.setString(3, user.getPremiumUuid() != null ? user.getPremiumUuid().toString() : null);
            stmt.setString(4, user.getLastIp());
            stmt.setTimestamp(5, user.getLastLoginDate());
            stmt.setInt(6, user.getTotalLogins());
            stmt.setString(7, user.getStatus());
            stmt.setString(8, user.getTotpSecret());
            stmt.setBoolean(9, user.isTotpEnabled());
            stmt.setString(10, user.getUuid().toString());
            stmt.executeUpdate();

            // Actualizar cache o invalidar
            userCache.put(user.getUuid(), user);
            usernameCache.put(user.getUsername().toLowerCase(), user);
        }
    }

    public void deleteUser(UUID uuid) throws SQLException {
        // Obtener usuario antes de borrar para limpiar cache de username
        Optional<User> userOpt = getUserByUUID(uuid);

        String sql = "DELETE FROM hybrid_users WHERE uuid = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();

            // Invalidar cache
            userCache.invalidate(uuid);
            if (userOpt.isPresent()) {
                usernameCache.invalidate(userOpt.get().getUsername().toLowerCase());
            }
        }
    }

    public Map<String, Long> getStatistics() throws SQLException {
        Map<String, Long> stats = new HashMap<>();

        try (Connection conn = dbManager.getConnection();
                Statement stmt = conn.createStatement()) {

            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as total FROM hybrid_users");
            if (rs.next())
                stats.put("total_users", rs.getLong("total"));

            rs = stmt.executeQuery("SELECT COUNT(*) as premium FROM hybrid_users WHERE auth_type = 'PREMIUM'");
            if (rs.next())
                stats.put("premium_users", rs.getLong("premium"));

            rs = stmt.executeQuery("SELECT COUNT(*) as cracked FROM hybrid_users WHERE auth_type = 'CRACKED'");
            if (rs.next())
                stats.put("cracked_users", rs.getLong("cracked"));

            try {
                rs = stmt.executeQuery("SELECT COUNT(*) as active FROM hybrid_sessions WHERE active = TRUE");
                if (rs.next())
                    stats.put("active_sessions", rs.getLong("active"));
            } catch (SQLException e) {
                // Table might not exist yet if not migrated properly, ignore for now
                stats.put("active_sessions", 0L);
            }
        }

        return stats;
    }

    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        String premiumUuidStr = rs.getString("premium_uuid");
        User user = new User();
        user.setUuid(UUID.fromString(rs.getString("uuid")));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setAuthType(User.AuthType.valueOf(rs.getString("auth_type")));
        user.setPremiumUuid(premiumUuidStr != null ? UUID.fromString(premiumUuidStr) : null);
        user.setRegisteredAt(getSafeTimestamp(rs, "registered_at"));
        user.setLastLoginDate(getSafeTimestamp(rs, "last_login_date"));
        user.setLastIp(rs.getString("last_ip"));
        user.setTotalLogins(rs.getInt("total_logins"));
        user.setStatus(rs.getString("status"));
        user.setTotpSecret(rs.getString("totp_secret"));
        user.setTotpEnabled(rs.getBoolean("totp_enabled"));
        return user;
    }

    private Timestamp getSafeTimestamp(ResultSet rs, String column) throws SQLException {
        Object obj = rs.getObject(column);
        if (obj == null)
            return null;

        // Caso 1: Es un número (Long/Integer)
        if (obj instanceof Number) {
            return new Timestamp(((Number) obj).longValue());
        }

        // Caso 2: Es un String
        String str = obj.toString();

        // Intentar parsear como milisegundos (Long explícito)
        try {
            long millis = Long.parseLong(str);
            return new Timestamp(millis);
        } catch (NumberFormatException ignored) {
            // No es un número puro
        }

        // Caso 3: Formato estándar SQL (YYYY-MM-DD HH:MM:SS)
        // Dejar que el driver lo intente parsear
        try {
            return rs.getTimestamp(column);
        } catch (Exception e) {
            // En caso de emergencia, retornar timestamp actual para no crashear
            // y loguear el error para debug
            System.err.println("[HybridAuth] Error parsing date for column " + column + ": " + str);
            return new Timestamp(System.currentTimeMillis());
        }
    }

    // ========== ASYNC METHODS FOR AUTO-LOGIN ==========

    /**
     * Crea un usuario premium de forma asíncrona (para auto-register)
     */
    public java.util.concurrent.CompletableFuture<Void> createPremiumUser(String username, UUID premiumUUID) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                User user = new User(premiumUUID, username, User.AuthType.PREMIUM);
                user.setPremiumUuid(premiumUUID);
                user.setPasswordHash(null); // Premium no necesita password
                user.setLastLoginDate(new Timestamp(System.currentTimeMillis()));
                user.setTotalLogins(1);
                createUser(user);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create premium user", e);
            }
        });
    }

    /**
     * Obtiene usuario por nombre de forma asíncrona
     */
    public java.util.concurrent.CompletableFuture<User> getUserByName(String username) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            return getUserByUsername(username).orElse(null);
        });
    }

    /**
     * Actualiza estadísticas de login de forma asíncrona
     */
    public java.util.concurrent.CompletableFuture<Void> updateLoginStats(String username, String ip) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Optional<User> userOpt = getUserByUsername(username);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    user.setLastLoginDate(new Timestamp(System.currentTimeMillis()));
                    user.setLastIp(ip);
                    user.incrementLogins();
                    updateUser(user);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to update login stats", e);
            }
        });
    }

    /**
     * Migra una cuenta cracked a premium
     */
    public java.util.concurrent.CompletableFuture<Void> upgradeToPremium(String username, UUID premiumUUID) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Optional<User> userOpt = getUserByUsername(username);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    user.setAuthType(User.AuthType.PREMIUM);
                    user.setPremiumUuid(premiumUUID);
                    user.setPasswordHash(null); // Ya no necesita password
                    updateUser(user);

                    // Invalidar cache para forzar recarga
                    userCache.invalidate(user.getUuid());
                    usernameCache.invalidate(username.toLowerCase());
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to upgrade to premium", e);
            }
        });
    }

    /**
     * Invalida el cache de un usuario
     */
    public void invalidateCache(String username) {
        usernameCache.invalidate(username.toLowerCase());
    }

    /**
     * Invalida todo el cache
     */
    public void invalidateAllCache() {
        userCache.invalidateAll();
        usernameCache.invalidateAll();
    }
}
