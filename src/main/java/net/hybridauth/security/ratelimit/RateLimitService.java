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

        if (attempts >= maxAttempts) {
            // Calculate backoff: base * 2^(attempts - max)
            // e.g. 5 failures = 300s
            // 6 failures = 600s
            // 7 failures = 1200s
            int overflow = attempts - maxAttempts;
            long multiplier = (long) Math.pow(2, Math.min(overflow, 5)); // Cap multiplier
            long blockDurationMillis = baseBlockTime * multiplier * 1000;

            blockCache.put(ip, System.currentTimeMillis() + blockDurationMillis);
            plugin.getLogger().warning("Rate limiting IP: " + ip + " for " + (blockDurationMillis / 1000) + "s");
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
