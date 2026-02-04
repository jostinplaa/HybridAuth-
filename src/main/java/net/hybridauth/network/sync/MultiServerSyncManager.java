package net.hybridauth.network.sync;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.network.sync.backends.MySQLSyncBackend;
import net.hybridauth.network.sync.backends.RedisSyncBackend;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Central manager for multi-server synchronization.
 * Coordinates backend selection (Redis or MySQL) and provides a unified API.
 * 
 * <p>
 * Features:
 * <ul>
 * <li>Automatic fallback from Redis to MySQL if Redis unavailable</li>
 * <li>Graceful degradation to local-only mode if sync fails</li>
 * <li>Thread-safe operations</li>
 * <li>Performance monitoring</li>
 * </ul>
 * 
 * @author HybridAuth
 * @version 1.6.0
 * @since 1.6.0
 */
public class MultiServerSyncManager {

    private final HybridAuthPlugin plugin;
    private SyncBackend backend;
    private boolean enabled;
    private String serverId;

    // Feature flags
    private boolean syncAuth;
    private boolean syncBlacklist;
    private boolean syncRateLimit;

    public MultiServerSyncManager(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.enabled = false;
    }

    /**
     * Initialize the sync manager based on configuration.
     * 
     * @return true if initialization successful
     */
    public boolean initialize() {
        // Check if multi-server sync is enabled
        if (!plugin.getConfig().getBoolean("multi-server.enabled", false)) {
            plugin.getLogger().info("[MultiServerSync] Multi-server sync is DISABLED in config.yml");
            enabled = false;
            return true; // Not an error, just disabled
        }

        // Load configuration
        serverId = plugin.getConfig().getString("multi-server.server-id", "unknown");
        syncAuth = plugin.getConfig().getBoolean("multi-server.sync-auth", true);
        syncBlacklist = plugin.getConfig().getBoolean("multi-server.sync-blacklist", true);
        syncRateLimit = plugin.getConfig().getBoolean("multi-server.sync-ratelimit", true);

        String backendType = plugin.getConfig().getString("multi-server.backend", "redis").toLowerCase();

        plugin.getLogger().info("========================================");
        plugin.getLogger().info(" HybridAuth Multi-Server Sync");
        plugin.getLogger().info("========================================");
        plugin.getLogger().info("Server ID: " + serverId);
        plugin.getLogger().info("Backend: " + backendType.toUpperCase());
        plugin.getLogger().info("Sync Auth: " + (syncAuth ? "YES" : "NO"));
        plugin.getLogger().info("Sync Blacklist: " + (syncBlacklist ? "YES" : "NO"));
        plugin.getLogger().info("Sync Rate Limit: " + (syncRateLimit ? "YES" : "NO"));
        plugin.getLogger().info("========================================");

        // Initialize backend
        boolean success = false;

        if ("redis".equals(backendType)) {
            success = initializeRedis();

            // Fallback to MySQL if Redis fails
            if (!success) {
                plugin.getLogger().warning("[MultiServerSync] Redis initialization failed, falling back to MySQL...");
                success = initializeMySQL();
            }
        } else if ("mysql".equals(backendType)) {
            success = initializeMySQL();
        } else {
            plugin.getLogger().severe("[MultiServerSync] Invalid backend type: " + backendType);
            plugin.getLogger().severe("[MultiServerSync] Valid options: 'redis' or 'mysql'");
            enabled = false;
            return false;
        }

        if (success) {
            enabled = true;
            plugin.getLogger().info("[MultiServerSync] ✓ Multi-server sync is ACTIVE");
            plugin.getLogger().info("[MultiServerSync] This server can now share auth state with network!");
        } else {
            enabled = false;
            plugin.getLogger().severe("[MultiServerSync] ✗ Failed to initialize any backend");
            plugin.getLogger().severe("[MultiServerSync] Multi-server sync will be DISABLED");
            plugin.getLogger().warning("[MultiServerSync] Plugin will function in LOCAL-ONLY mode");
        }

        return success;
    }

    private boolean initializeRedis() {
        try {
            String host = plugin.getConfig().getString("multi-server.redis.host", "localhost");
            int port = plugin.getConfig().getInt("multi-server.redis.port", 6379);
            String password = plugin.getConfig().getString("multi-server.redis.password", "");
            int database = plugin.getConfig().getInt("multi-server.redis.database", 0);
            int timeout = plugin.getConfig().getInt("multi-server.redis.timeout", 2000);
            int poolSize = plugin.getConfig().getInt("multi-server.redis.pool-size", 8);

            backend = new RedisSyncBackend(plugin, host, port, password, database, timeout, poolSize);
            return backend.initialize();

        } catch (Exception e) {
            plugin.getLogger().severe("[MultiServerSync] Redis initialization exception: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private boolean initializeMySQL() {
        try {
            backend = new MySQLSyncBackend(plugin);
            return backend.initialize();

        } catch (Exception e) {
            plugin.getLogger().severe("[MultiServerSync] MySQL initialization exception: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Shutdown the sync manager and release resources.
     */
    public void shutdown() {
        if (backend != null) {
            backend.shutdown();
        }
        enabled = false;
    }

    /**
     * Check if multi-server sync is enabled and operational.
     * 
     * @return true if enabled and connected
     */
    public boolean isEnabled() {
        return enabled && backend != null && backend.isConnected();
    }

    /**
     * Get the unique server ID.
     * 
     * @return server identifier
     */
    public String getServerId() {
        return serverId;
    }

    // ==================== Authentication State ====================

    /**
     * Sync authentication state to the network.
     * 
     * @param uuid          Player UUID
     * @param authenticated Whether player is authenticated
     */
    public void setAuthState(UUID uuid, boolean authenticated) {
        if (!isEnabled() || !syncAuth) {
            return;
        }

        long ttlSeconds = plugin.getConfig().getLong("session.duration-days", 1) * 86400;

        backend.setAuthState(uuid, authenticated, ttlSeconds).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to sync auth state: " + ex.getMessage());
            return null;
        });
    }

    /**
     * Get authentication state from the network.
     * 
     * @param uuid Player UUID
     * @return CompletableFuture with auth state, null if not found or disabled
     */
    public CompletableFuture<Boolean> getAuthState(UUID uuid) {
        if (!isEnabled() || !syncAuth) {
            return CompletableFuture.completedFuture(null);
        }

        return backend.getAuthState(uuid).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to get auth state: " + ex.getMessage());
            return null;
        });
    }

    /**
     * Remove authentication state from the network.
     * 
     * @param uuid Player UUID
     */
    public void removeAuthState(UUID uuid) {
        if (!isEnabled() || !syncAuth) {
            return;
        }

        backend.removeAuthState(uuid).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to remove auth state: " + ex.getMessage());
            return null;
        });
    }

    // ==================== Blacklist Sync ====================

    /**
     * Sync IP blacklist to the network.
     * 
     * @param ip              IP address
     * @param durationSeconds Ban duration (0 for permanent)
     * @param reason          Ban reason
     */
    public void addIPBlacklist(String ip, int durationSeconds, String reason) {
        if (!isEnabled() || !syncBlacklist) {
            return;
        }

        long expiryTimestamp = durationSeconds > 0 ? (System.currentTimeMillis() / 1000) + durationSeconds : 0;

        backend.addIPBlacklist(ip, expiryTimestamp, reason).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to sync IP blacklist: " + ex.getMessage());
            return null;
        });
    }

    /**
     * Check if IP is blacklisted network-wide.
     * 
     * @param ip IP address
     * @return CompletableFuture with true if blacklisted
     */
    public CompletableFuture<Boolean> isIPBlacklisted(String ip) {
        if (!isEnabled() || !syncBlacklist) {
            return CompletableFuture.completedFuture(false);
        }

        return backend.isIPBlacklisted(ip).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to check IP blacklist: " + ex.getMessage());
            return false;
        });
    }

    /**
     * Remove IP from network blacklist.
     * 
     * @param ip IP address
     */
    public void removeIPBlacklist(String ip) {
        if (!isEnabled() || !syncBlacklist) {
            return;
        }

        backend.removeIPBlacklist(ip).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to remove IP blacklist: " + ex.getMessage());
            return null;
        });
    }

    /**
     * Sync username blacklist to the network.
     * 
     * @param username        Username
     * @param durationSeconds Ban duration (0 for permanent)
     * @param reason          Ban reason
     */
    public void addUsernameBlacklist(String username, int durationSeconds, String reason) {
        if (!isEnabled() || !syncBlacklist) {
            return;
        }

        long expiryTimestamp = durationSeconds > 0 ? (System.currentTimeMillis() / 1000) + durationSeconds : 0;

        backend.addUsernameBlacklist(username, expiryTimestamp, reason).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to sync username blacklist: " + ex.getMessage());
            return null;
        });
    }

    /**
     * Check if username is blacklisted network-wide.
     * 
     * @param username Username
     * @return CompletableFuture with true if blacklisted
     */
    public CompletableFuture<Boolean> isUsernameBlacklisted(String username) {
        if (!isEnabled() || !syncBlacklist) {
            return CompletableFuture.completedFuture(false);
        }

        return backend.isUsernameBlacklisted(username).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to check username blacklist: " + ex.getMessage());
            return false;
        });
    }

    /**
     * Remove username from network blacklist.
     * 
     * @param username Username
     */
    public void removeUsernameBlacklist(String username) {
        if (!isEnabled() || !syncBlacklist) {
            return;
        }

        backend.removeUsernameBlacklist(username).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to remove username blacklist: " + ex.getMessage());
            return null;
        });
    }

    // ==================== Rate Limiting ====================

    /**
     * Increment network-wide rate limit counter.
     * 
     * @param username Username
     * @param ip       IP address
     * @return CompletableFuture with new count
     */
    public CompletableFuture<Integer> incrementRateLimit(String username, String ip) {
        if (!isEnabled() || !syncRateLimit) {
            return CompletableFuture.completedFuture(0);
        }

        String key = username + ":" + ip;
        long ttlSeconds = 60; // 1 minute

        return backend.incrementRateLimit(key, ttlSeconds).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to increment rate limit: " + ex.getMessage());
            return 0;
        });
    }

    /**
     * Increment network-wide rate limit counter with custom key and TTL.
     * 
     * @param key        Rate limit key (e.g. IP address)
     * @param ttlSeconds Time to live for the counter
     * @return CompletableFuture with new count
     */
    public CompletableFuture<Integer> incrementRateLimit(String key, long ttlSeconds) {
        if (!isEnabled() || !syncRateLimit) {
            return CompletableFuture.completedFuture(0);
        }

        return backend.incrementRateLimit(key, ttlSeconds).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to increment rate limit: " + ex.getMessage());
            return 0;
        });
    }

    /**
     * Get network-wide rate limit count.
     * 
     * @param username Username
     * @param ip       IP address
     * @return CompletableFuture with count
     */
    public CompletableFuture<Integer> getRateLimit(String username, String ip) {
        if (!isEnabled() || !syncRateLimit) {
            return CompletableFuture.completedFuture(0);
        }

        String key = username + ":" + ip;

        return backend.getRateLimit(key).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to get rate limit: " + ex.getMessage());
            return 0;
        });
    }

    /**
     * Reset network-wide rate limit.
     * 
     * @param username Username
     * @param ip       IP address
     */
    public void resetRateLimit(String username, String ip) {
        if (!isEnabled() || !syncRateLimit) {
            return;
        }

        String key = username + ":" + ip;

        backend.resetRateLimit(key).exceptionally(ex -> {
            plugin.getLogger().warning("[MultiServerSync] Failed to reset rate limit: " + ex.getMessage());
            return null;
        });
    }
}
