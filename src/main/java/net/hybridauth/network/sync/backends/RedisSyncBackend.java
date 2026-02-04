package net.hybridauth.network.sync.backends;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.network.sync.SyncBackend;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redis-based synchronization backend using Jedis client.
 * Provides ultra-fast distributed state management for multi-server networks.
 * 
 * <p>
 * Key Features:
 * <ul>
 * <li>Sub-10ms latency for most operations</li>
 * <li>Connection pooling for high throughput</li>
 * <li>Automatic TTL for sessions and rate limits</li>
 * <li>Graceful degradation on connection loss</li>
 * </ul>
 * 
 * @author HybridAuth
 * @version 1.6.0
 * @since 1.6.0
 */
public class RedisSyncBackend implements SyncBackend {

    private final HybridAuthPlugin plugin;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int timeout;
    private final int poolSize;

    private JedisPool jedisPool;
    private ExecutorService executor;
    private volatile boolean connected = false;

    // Key prefixes for organization
    private static final String PREFIX_AUTH = "hybridauth:auth:";
    private static final String PREFIX_BLACKLIST_IP = "hybridauth:blacklist:ip:";
    private static final String PREFIX_BLACKLIST_USER = "hybridauth:blacklist:user:";
    private static final String PREFIX_RATELIMIT = "hybridauth:ratelimit:";

    /**
     * Constructs a new Redis sync backend.
     * 
     * @param plugin   The HybridAuth plugin instance
     * @param host     Redis server hostname
     * @param port     Redis server port
     * @param password Redis password (null or empty for no auth)
     * @param database Redis database number (0-15)
     * @param timeout  Connection timeout in milliseconds
     * @param poolSize Connection pool size
     */
    public RedisSyncBackend(HybridAuthPlugin plugin, String host, int port, String password,
            int database, int timeout, int poolSize) {
        this.plugin = plugin;
        this.host = host;
        this.port = port;
        this.password = (password == null || password.isEmpty()) ? null : password;
        this.database = database;
        this.timeout = timeout;
        this.poolSize = poolSize;
    }

    @Override
    public boolean initialize() {
        try {
            plugin.getLogger().info("[MultiServerSync] Connecting to Redis at " + host + ":" + port + "...");

            // Configure connection pool for optimal performance
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(poolSize);
            poolConfig.setMaxIdle(poolSize / 2);
            poolConfig.setMinIdle(2);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestOnReturn(true);
            poolConfig.setTestWhileIdle(true);
            poolConfig.setBlockWhenExhausted(true);
            poolConfig.setMaxWaitMillis(timeout);

            // Create Jedis pool
            if (password != null) {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, password, database);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, null, database);
            }

            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
                if (!"PONG".equals(pong)) {
                    throw new JedisException("Redis PING test failed");
                }
            }

            // Create async executor for non-blocking operations
            executor = Executors.newFixedThreadPool(4, r -> {
                Thread thread = new Thread(r, "HybridAuth-Redis-Worker");
                thread.setDaemon(true);
                return thread;
            });

            connected = true;
            plugin.getLogger().info("[MultiServerSync] ✓ Connected to Redis successfully!");
            plugin.getLogger().info("[MultiServerSync] Ready for cross-server sync");

            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("[MultiServerSync] ✗ Failed to connect to Redis: " + e.getMessage());
            plugin.getLogger().severe("[MultiServerSync] Multi-server sync will be DISABLED");
            e.printStackTrace();
            connected = false;
            return false;
        }
    }

    @Override
    public void shutdown() {
        plugin.getLogger().info("[MultiServerSync] Shutting down Redis connection...");

        connected = false;

        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }

        plugin.getLogger().info("[MultiServerSync] Redis connection closed");
    }

    @Override
    public boolean isConnected() {
        if (!connected || jedisPool == null || jedisPool.isClosed()) {
            return false;
        }

        // Quick health check
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[MultiServerSync] Redis health check failed: " + e.getMessage());
            connected = false;
            return false;
        }
    }

    // ==================== Authentication State ====================

    @Override
    public CompletableFuture<Void> setAuthState(UUID uuid, boolean authenticated, long ttlSeconds) {
        return CompletableFuture.runAsync(() -> {
            String key = PREFIX_AUTH + uuid.toString();
            String value = Boolean.toString(authenticated);

            try (Jedis jedis = jedisPool.getResource()) {
                if (ttlSeconds > 0) {
                    jedis.setex(key, ttlSeconds, value);
                } else {
                    jedis.set(key, value);
                }
            } catch (JedisException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to set auth state: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> getAuthState(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String key = PREFIX_AUTH + uuid.toString();

            try (Jedis jedis = jedisPool.getResource()) {
                String value = jedis.get(key);
                return value != null ? Boolean.parseBoolean(value) : null;
            } catch (JedisException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to get auth state: " + e.getMessage());
                return null;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeAuthState(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            String key = PREFIX_AUTH + uuid.toString();

            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(key);
            } catch (JedisException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to remove auth state: " + e.getMessage());
            }
        }, executor);
    }

    // ==================== Blacklist Sync ====================

    @Override
    public CompletableFuture<Void> addIPBlacklist(String ip, long expiryTimestamp, String reason) {
        return CompletableFuture.runAsync(() -> {
            String key = PREFIX_BLACKLIST_IP + ip;
            String value = expiryTimestamp + ":" + reason;

            try (Jedis jedis = jedisPool.getResource()) {
                if (expiryTimestamp > 0) {
                    long ttl = Math.max(1, expiryTimestamp - System.currentTimeMillis() / 1000);
                    jedis.setex(key, ttl, value);
                } else {
                    jedis.set(key, value); // Permanent ban
                }
            } catch (JedisException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to add IP blacklist: " + e.getMessage());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> isIPBlacklisted(String ip) {
        return CompletableFuture.supplyAsync(() -> {
            String key = PREFIX_BLACKLIST_IP + ip;

            try (Jedis jedis = jedisPool.getResource()) {
                String value = jedis.get(key);
                if (value == null) {
                    return false;
                }

                // Check if temporary ban expired
                String[] parts = value.split(":", 2);
                if (parts.length == 2) {
                    long expiryTimestamp = Long.parseLong(parts[0]);
                    if (expiryTimestamp > 0 && System.currentTimeMillis() / 1000 > expiryTimestamp) {
                        // Expired, remove it
                        jedis.del(key);
                        return false;
                    }
                }

                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to check IP blacklist: " + e.getMessage());
                return false;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeIPBlacklist(String ip) {
        return CompletableFuture.runAsync(() -> {
            String key = PREFIX_BLACKLIST_IP + ip;

            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(key);
            } catch (JedisException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to remove IP blacklist: " + e.getMessage());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> addUsernameBlacklist(String username, long expiryTimestamp, String reason) {
        return CompletableFuture.runAsync(() -> {
            String key = PREFIX_BLACKLIST_USER + username.toLowerCase();
            String value = expiryTimestamp + ":" + reason;

            try (Jedis jedis = jedisPool.getResource()) {
                if (expiryTimestamp > 0) {
                    long ttl = Math.max(1, expiryTimestamp - System.currentTimeMillis() / 1000);
                    jedis.setex(key, ttl, value);
                } else {
                    jedis.set(key, value);
                }
            } catch (JedisException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to add username blacklist: " + e.getMessage());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> isUsernameBlacklisted(String username) {
        return CompletableFuture.supplyAsync(() -> {
            String key = PREFIX_BLACKLIST_USER + username.toLowerCase();

            try (Jedis jedis = jedisPool.getResource()) {
                String value = jedis.get(key);
                if (value == null) {
                    return false;
                }

                // Check if temporary ban expired
                String[] parts = value.split(":", 2);
                if (parts.length == 2) {
                    long expiryTimestamp = Long.parseLong(parts[0]);
                    if (expiryTimestamp > 0 && System.currentTimeMillis() / 1000 > expiryTimestamp) {
                        jedis.del(key);
                        return false;
                    }
                }

                return true;
            } catch (Exception e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to check username blacklist: " + e.getMessage());
                return false;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeUsernameBlacklist(String username) {
        return CompletableFuture.runAsync(() -> {
            String key = PREFIX_BLACKLIST_USER + username.toLowerCase();

            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(key);
            } catch (JedisException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to remove username blacklist: " + e.getMessage());
            }
        }, executor);
    }

    // ==================== Rate Limiting ====================

    @Override
    public CompletableFuture<Integer> incrementRateLimit(String key, long ttlSeconds) {
        return CompletableFuture.supplyAsync(() -> {
            String redisKey = PREFIX_RATELIMIT + key;

            try (Jedis jedis = jedisPool.getResource()) {
                Long newCount = jedis.incr(redisKey);

                // Set TTL on first increment
                if (newCount == 1) {
                    jedis.expire(redisKey, ttlSeconds);
                }

                return newCount.intValue();
            } catch (JedisException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to increment rate limit: " + e.getMessage());
                return 0;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Integer> getRateLimit(String key) {
        return CompletableFuture.supplyAsync(() -> {
            String redisKey = PREFIX_RATELIMIT + key;

            try (Jedis jedis = jedisPool.getResource()) {
                String value = jedis.get(redisKey);
                return value != null ? Integer.parseInt(value) : 0;
            } catch (Exception e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to get rate limit: " + e.getMessage());
                return 0;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> resetRateLimit(String key) {
        return CompletableFuture.runAsync(() -> {
            String redisKey = PREFIX_RATELIMIT + key;

            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(redisKey);
            } catch (JedisException e) {
                plugin.getLogger().warning("[MultiServerSync] Failed to reset rate limit: " + e.getMessage());
            }
        }, executor);
    }
}
