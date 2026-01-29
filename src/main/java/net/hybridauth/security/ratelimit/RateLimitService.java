package net.hybridauth.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.hybridauth.HybridAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.TimeUnit;

public class RateLimitService {

    private final HybridAuthPlugin plugin;
    private final Cache<String, Integer> attemptsCache;
    private final int maxAttempts;

    public RateLimitService(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();

        int expireSeconds = config.getInt("security.ratelimit.expire_seconds", 300);
        this.maxAttempts = config.getInt("security.ratelimit.max_attempts", 5);

        this.attemptsCache = Caffeine.newBuilder()
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
                .build();
    }

    public boolean checkLimit(String key) {
        Integer attempts = attemptsCache.getIfPresent(key);
        if (attempts == null)
            return true;
        return attempts < maxAttempts;
    }

    public void incrementAttempt(String key) {
        Integer attempts = attemptsCache.getIfPresent(key);
        if (attempts == null) {
            attempts = 0;
        }
        attemptsCache.put(key, attempts + 1);
    }

    public void resetLimit(String key) {
        attemptsCache.invalidate(key);
    }

    public int getRemainingAttempts(String key) {
        Integer attempts = attemptsCache.getIfPresent(key);
        if (attempts == null)
            return maxAttempts;
        return Math.max(0, maxAttempts - attempts);
    }
}
