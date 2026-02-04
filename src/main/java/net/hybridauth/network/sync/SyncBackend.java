package net.hybridauth.network.sync;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for multi-server synchronization backends.
 * Implementations can use Redis, MySQL, or other distributed storage systems.
 * 
 * @author HybridAuth
 * @version 1.6.0
 * @since 1.6.0
 */
public interface SyncBackend {

    /**
     * Initialize the sync backend and establish connections.
     * Should be called during plugin startup.
     * 
     * @return true if initialization successful, false otherwise
     */
    boolean initialize();

    /**
     * Shutdown the sync backend and release all resources.
     * Should be called during plugin shutdown.
     */
    void shutdown();

    /**
     * Check if the backend is currently connected and operational.
     * 
     * @return true if connected, false otherwise
     */
    boolean isConnected();

    // ==================== Authentication State ====================

    /**
     * Set the authentication state for a player across the network.
     * 
     * @param uuid          Player's unique identifier
     * @param authenticated Whether the player is authenticated
     * @param ttlSeconds    Time-to-live in seconds (session duration)
     * @return CompletableFuture that completes when state is synced
     */
    CompletableFuture<Void> setAuthState(UUID uuid, boolean authenticated, long ttlSeconds);

    /**
     * Get the authentication state for a player from the network.
     * 
     * @param uuid Player's unique identifier
     * @return CompletableFuture with authentication state, null if not found
     */
    CompletableFuture<Boolean> getAuthState(UUID uuid);

    /**
     * Remove authentication state for a player (on logout).
     * 
     * @param uuid Player's unique identifier
     * @return CompletableFuture that completes when state is removed
     */
    CompletableFuture<Void> removeAuthState(UUID uuid);

    // ==================== Blacklist Sync ====================

    /**
     * Add an IP to the network-wide blacklist.
     * 
     * @param ip              IP address to blacklist
     * @param expiryTimestamp Unix timestamp when the ban expires (0 for permanent)
     * @param reason          Reason for the ban
     * @return CompletableFuture that completes when IP is blacklisted
     */
    CompletableFuture<Void> addIPBlacklist(String ip, long expiryTimestamp, String reason);

    /**
     * Check if an IP is blacklisted on the network.
     * 
     * @param ip IP address to check
     * @return CompletableFuture with true if blacklisted, false otherwise
     */
    CompletableFuture<Boolean> isIPBlacklisted(String ip);

    /**
     * Remove an IP from the network-wide blacklist.
     * 
     * @param ip IP address to unban
     * @return CompletableFuture that completes when IP is removed
     */
    CompletableFuture<Void> removeIPBlacklist(String ip);

    /**
     * Add a username to the network-wide blacklist.
     * 
     * @param username        Username to blacklist
     * @param expiryTimestamp Unix timestamp when the ban expires (0 for permanent)
     * @param reason          Reason for the ban
     * @return CompletableFuture that completes when username is blacklisted
     */
    CompletableFuture<Void> addUsernameBlacklist(String username, long expiryTimestamp, String reason);

    /**
     * Check if a username is blacklisted on the network.
     * 
     * @param username Username to check
     * @return CompletableFuture with true if blacklisted, false otherwise
     */
    CompletableFuture<Boolean> isUsernameBlacklisted(String username);

    /**
     * Remove a username from the network-wide blacklist.
     * 
     * @param username Username to unban
     * @return CompletableFuture that completes when username is removed
     */
    CompletableFuture<Void> removeUsernameBlacklist(String username);

    // ==================== Rate Limiting ====================

    /**
     * Increment the rate limit counter for a key (username+ip) across the network.
     * 
     * @param key        Rate limit key (e.g., "ratelimit:player:127.0.0.1")
     * @param ttlSeconds Time-to-live for the counter
     * @return CompletableFuture with the new count
     */
    CompletableFuture<Integer> incrementRateLimit(String key, long ttlSeconds);

    /**
     * Get the current rate limit count for a key.
     * 
     * @param key Rate limit key
     * @return CompletableFuture with current count, 0 if not found
     */
    CompletableFuture<Integer> getRateLimit(String key);

    /**
     * Reset the rate limit counter for a key.
     * 
     * @param key Rate limit key
     * @return CompletableFuture that completes when counter is reset
     */
    CompletableFuture<Void> resetRateLimit(String key);
}
