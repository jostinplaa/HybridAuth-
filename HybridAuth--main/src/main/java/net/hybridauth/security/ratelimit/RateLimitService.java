package net.hybridauth.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.hybridauth.HybridAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RateLimitService {

    private final HybridAuthPlugin plugin;
    private final Cache<String, Integer> attemptsCache;
    private final Cache<String, Long> blockCache; // IP -> Blocked Until (Timestamp)
    private final int maxAttempts;
    private final long baseBlockTime; // Seconds
    private final Set<String> whitelist;

    public RateLimitService(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();

        int expireSeconds = config.getInt("security.ratelimit.expire_seconds", 300);
        this.maxAttempts = config.getInt("security.ratelimit.max_attempts", 5);
        this.baseBlockTime = config.getLong("security.ratelimit.block_seconds", 300); // 5 min default

        this.attemptsCache = Caffeine.newBuilder()
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
                .build();

        // Bloqueos persisten más tiempo
        this.blockCache = Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .build();

        this.whitelist = new HashSet<>(config.getStringList("security.ratelimit.whitelist"));
    }

    public boolean isAllowed(String ip) {
        if (whitelist.contains(ip))
            return true;

        // Check if blocked
        Long blockedUntil = blockCache.getIfPresent(ip);
        if (blockedUntil != null) {
            if (System.currentTimeMillis() < blockedUntil) {
                return false;
            } else {
                blockCache.invalidate(ip); // Expired
            }
        }
        return true;
    }

    public long getSecondsRemaining(String ip) {
        Long blockedUntil = blockCache.getIfPresent(ip);
        if (blockedUntil == null)
            return 0;
        return Math.max(0, (blockedUntil - System.currentTimeMillis()) / 1000);
    }

    public void recordFailure(String ip) {
        if (whitelist.contains(ip))
            return;

        Integer attempts = attemptsCache.getIfPresent(ip);
        if (attempts == null)
            attempts = 0;
        attempts++;
        attemptsCache.put(ip, attempts);

        // 1. Check Local Limit
        if (attempts >= maxAttempts) {
            triggerBlock(ip, attempts);
            return;
        }

        // 2. Check Global Limit (Async)
        if (plugin.getSyncManager() != null && plugin.getSyncManager().isEnabled()) {
            final int localAttempts = attempts;
            plugin.getSyncManager().incrementRateLimit(ip, 600) // Keep global count for 10 min
                    .thenAccept(globalCount -> {
                        if (globalCount >= maxAttempts) {
                            // Run on main thread to be safe with caches/logging
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                // Only trigger if not already blocked locally (avoid double log)
                                if (blockCache.getIfPresent(ip) == null) {
                                    plugin.getLogger().warning("[MultiServerSync] Global rate limit exceeded for " + ip
                                            + " (" + globalCount + " attempts)");
                                    triggerBlock(ip, globalCount);
                                }
                            });
                        }
                    });
        }
    }

    private void triggerBlock(String ip, int attempts) {
        // Calculate backoff: base * 2^(attempts - max)
        int overflow = attempts - maxAttempts;
        long multiplier = (long) Math.pow(2, Math.min(overflow, 5)); // Cap multiplier
        long blockDurationSeconds = baseBlockTime * multiplier;
        long blockDurationMillis = blockDurationSeconds * 1000;

        blockCache.put(ip, System.currentTimeMillis() + blockDurationMillis);
        plugin.getLogger().warning("Rate limiting IP: " + ip + " for " + blockDurationSeconds + "s");

        // Broadcast block to network if sync enabled
        if (plugin.getSyncManager() != null && plugin.getSyncManager().isEnabled()) {
            plugin.getSyncManager().addIPBlacklist(ip, (int) blockDurationSeconds, "Rate Limit Exceeded (Automated)");
        }
    }

    public void reset(String ip) {
        attemptsCache.invalidate(ip);
        blockCache.invalidate(ip);
    }

    // Compatibility methods
    public boolean checkLimit(String ip) {
        return isAllowed(ip);
    }

    public void incrementAttempt(String ip) {
        recordFailure(ip);
    }

    public void resetLimit(String ip) {
        reset(ip);
    }

    public int getAttempts(String ip) {
        Integer attempts = attemptsCache.getIfPresent(ip);
        return (attempts == null) ? 0 : attempts;
    }
}
