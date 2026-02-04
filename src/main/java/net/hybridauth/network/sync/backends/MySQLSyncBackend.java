package net.hybridauth.network.sync.backends;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.data.DatabaseManager;
import net.hybridauth.network.sync.SyncBackend;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MySQL-based synchronization backend (fallback option).
 * Uses the existing database connection for state synchronization.
 * 
 * <p>
 * Performance Characteristics:
 * <ul>
 * <li>50-100ms latency (higher than Redis)</li>
 * <li>No additional infrastructure required</li>
 * <li>Suitable for small-medium networks</li>
 * <li>Polling-based (500ms refresh rate)</li>
 * </ul>
 * 
 * @author HybridAuth
 * @version 1.6.0
 * @since 1.6.0
 */
public class MySQLSyncBackend implements SyncBackend {

    private final HybridAuthPlugin plugin;
    private final DatabaseManager databaseManager;
    private ExecutorService executor;
    private volatile boolean connected = false;

    public MySQLSyncBackend(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
    }

    @Override
    public boolean initialize() {
        try {
            plugin.getLogger().info("[MultiServerSync] Initializing MySQL sync backend...");

            // Create sync tables if they don't exist
            createTables();

            // Create async executor
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "HybridAuth-MySQL-Sync");
                thread.setDaemon(true);
                return thread;
            });

            connected = true;
            plugin.getLogger().info("[MultiServerSync]  MySQL sync backend initialized");
            plugin.getLogger().warning("[MultiServerSync] Note: MySQL sync has higher latency than Redis (~50-100ms)");

            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("[MultiServerSync]  Failed to initialize MySQL sync: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in MySQLSyncBackend", e);
            connected = false;
            return false;
        }
    }

    private void createTables() throws SQLException {
        String authStateTable = "CREATE TABLE IF NOT EXISTS hybridauth_sync_auth (" +
                "uuid VARCHAR(36) PRIMARY KEY, " +
                "authenticated BOOLEAN NOT NULL, " +
                "expiry_time BIGINT NOT NULL, " +
                "last_update BIGINT NOT NULL" +
                ")";

        String blacklistIPTable = "CREATE TABLE IF NOT EXISTS hybridauth_sync_blacklist_ip (" +
                "ip VARCHAR(45) PRIMARY KEY, " +
                "expiry_time BIGINT NOT NULL, " +
                "reason TEXT" +
                ")";

        String blacklistUserTable = "CREATE TABLE IF NOT EXISTS hybridauth_sync_blacklist_user (" +
                "username VARCHAR(16) PRIMARY KEY, " +
                "expiry_time BIGINT NOT NULL, " +
                "reason TEXT" +
                ")";

        String rateLimitTable = "CREATE TABLE IF NOT EXISTS hybridauth_sync_ratelimit (" +
                "`key` VARCHAR(100) PRIMARY KEY, " +
                "count INT NOT NULL, " +
                "expiry_time BIGINT NOT NULL" +
                ")";

        try (Connection conn = databaseManager.getConnection()) {
            conn.createStatement().execute(authStateTable);
            conn.createStatement().execute(blacklistIPTable);
            conn.createStatement().execute(blacklistUserTable);
            conn.createStatement().execute(rateLimitTable);
        }
    }

    @Override
    public void shutdown() {
        plugin.getLogger().info("[MultiServerSync] Shutting down MySQL sync backend...");
        connected = false;

        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }

        plugin.getLogger().info("[MultiServerSync] MySQL  sync backend shut down");
    }

    @Override
    public boolean isConnected() {
        return connected && databaseManager != null;
    }

    // ==================== Authentication State ====================

    @Override
    public CompletableFuture<Void> setAuthState(UUID uuid, boolean authenticated, long ttlSeconds) {
        return CompletableFuture.runAsync(() -> {
            long expiryTime = System.currentTimeMillis() + (ttlSeconds * 1000);
            long now = System.currentTimeMillis();

            String sql = "INSERT INTO hybridauth_sync_auth (uuid, authenticated, expiry_time, last_update) " +
                    "VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE authenticated = ?, expiry_time = ?, last_update = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, uuid.toString());
                stmt.setBoolean(2, authenticated);
                stmt.setLong(3, expiryTime);
                stmt.setLong(4, now);
                stmt.setBoolean(5, authenticated);
                stmt.setLong(6, expiryTime);
                stmt.setLong(7, now);

                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to set auth state (MySQL): " + e.getMessage());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> getAuthState(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT authenticated, expiry_time FROM hybridauth_sync_auth WHERE uuid = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, uuid.toString());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    long expiryTime = rs.getLong("expiry_time");

                    // Check if expired
                    if (System.currentTimeMillis() > expiryTime) {
                        removeAuthState(uuid); // Clean up
                        return null;
                    }

                    return rs.getBoolean("authenticated");
                }

                return null;

            } catch (SQLException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to get auth state (MySQL): " + e.getMessage());
                return null;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeAuthState(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM hybridauth_sync_auth WHERE uuid = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to remove auth state (MySQL): " + e.getMessage());
            }
        }, executor);
    }

    // ==================== Blacklist Sync ====================

    @Override
    public CompletableFuture<Void> addIPBlacklist(String ip, long expiryTimestamp, String reason) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO hybridauth_sync_blacklist_ip (ip, expiry_time, reason) " +
                    "VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE expiry_time = ?, reason = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                long expiryTime = expiryTimestamp > 0 ? expiryTimestamp * 1000 : Long.MAX_VALUE;

                stmt.setString(1, ip);
                stmt.setLong(2, expiryTime);
                stmt.setString(3, reason);
                stmt.setLong(4, expiryTime);
                stmt.setString(5, reason);

                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to add IP blacklist (MySQL): " + e.getMessage());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> isIPBlacklisted(String ip) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT expiry_time FROM hybridauth_sync_blacklist_ip WHERE ip = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, ip);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    long expiryTime = rs.getLong("expiry_time");

                    // Check if expired
                    if (expiryTime != Long.MAX_VALUE && System.currentTimeMillis() > expiryTime) {
                        removeIPBlacklist(ip);
                        return false;
                    }

                    return true;
                }

                return false;

            } catch (SQLException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to check IP blacklist (MySQL): " + e.getMessage());
                return false;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeIPBlacklist(String ip) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM hybridauth_sync_blacklist_ip WHERE ip = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, ip);
                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger()
                        .warning("[MultiServerSync] Failed to remove IP blacklist (MySQL): " + e.getMessage());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> addUsernameBlacklist(String username, long expiryTimestamp, String reason) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO hybridauth_sync_blacklist_user (username, expiry_time, reason) " +
                    "VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE expiry_time = ?, reason = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                long expiryTime = expiryTimestamp > 0 ? expiryTimestamp * 1000 : Long.MAX_VALUE;

                stmt.setString(1, username.toLowerCase());
                stmt.setLong(2, expiryTime);
                stmt.setString(3, reason);
                stmt.setLong(4, expiryTime);
                stmt.setString(5, reason);

                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger()
                        .warning("[MultiServerSync] Failed to add username blacklist (MySQL): " + e.getMessage());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> isUsernameBlacklisted(String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT expiry_time FROM hybridauth_sync_blacklist_user WHERE username = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, username.toLowerCase());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    long expiryTime = rs.getLong("expiry_time");

                    if (expiryTime != Long.MAX_VALUE && System.currentTimeMillis() > expiryTime) {
                        removeUsernameBlacklist(username);
                        return false;
                    }

                    return true;
                }

                return false;

            } catch (SQLException e) {
                plugin.getLogger()
                        .warning("[MultiServerSync] Failed to check username blacklist (MySQL): " + e.getMessage());
                return false;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeUsernameBlacklist(String username) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM hybridauth_sync_blacklist_user WHERE username = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, username.toLowerCase());
                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger()
                        .warning("[MultiServerSync] Failed to remove username blacklist (MySQL): " + e.getMessage());
            }
        }, executor);
    }

    // ==================== Rate Limiting ====================

    @Override
    public CompletableFuture<Integer> incrementRateLimit(String key, long ttlSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            long expiryTime = System.currentTimeMillis() + (ttlSeconds * 1000);

            // First, try to increment
            String selectSql = "SELECT count FROM hybridauth_sync_ratelimit WHERE `key` = ? AND expiry_time > ?";
            String insertSql = "INSERT INTO hybridauth_sync_ratelimit (`key`, count, expiry_time) VALUES (?, 1, ?) " +
                    "ON DUPLICATE KEY UPDATE count = count + 1";

            try (Connection conn = databaseManager.getConnection()) {
                // Check current count
                try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                    stmt.setString(1, key);
                    stmt.setLong(2, System.currentTimeMillis());
                    ResultSet rs = stmt.executeQuery();

                    if (rs.next()) {
                        int currentCount = rs.getInt("count");

                        // Increment
                        try (PreparedStatement updateStmt = conn.prepareStatement(
                                "UPDATE hybridauth_sync_ratelimit SET count = count + 1 WHERE `key` = ?")) {
                            updateStmt.setString(1, key);
                            updateStmt.executeUpdate();
                        }

                        return currentCount + 1;
                    }
                }

                // Insert new entry
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, key);
                    stmt.setLong(2, expiryTime);
                    stmt.executeUpdate();
                }

                return 1;

            } catch (SQLException e) {
                plugin.getLogger()
                        .warning("[MultiServerSync] Failed to increment rate limit (MySQL): " + e.getMessage());
                return 0;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> getRateLimit(String key) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT count, expiry_time FROM hybridauth_sync_ratelimit WHERE `key` = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, key);
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    long expiryTime = rs.getLong("expiry_time");

                    if (System.currentTimeMillis() > expiryTime) {
                        resetRateLimit(key);
                        return 0;
                    }

                    return rs.getInt("count");
                }

                return 0;

            } catch (SQLException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to get rate limit (MySQL): " + e.getMessage());
                return 0;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> resetRateLimit(String key) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM hybridauth_sync_ratelimit WHERE `key` = ?";

            try (Connection conn = databaseManager.getConnection();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, key);
                stmt.executeUpdate();

            } catch (SQLException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to reset rate limit (MySQL): " + e.getMessage());
            }
        }, executor);
    }
}



