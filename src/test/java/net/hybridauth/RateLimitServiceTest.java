package net.hybridauth;

import net.hybridauth.security.ratelimit.RateLimitService;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RateLimitServiceTest {

    @Mock
    private HybridAuthPlugin plugin;
    @Mock
    private FileConfiguration config;

    private RateLimitService rateLimitService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());

        // Default config values
        when(config.getInt("security.ratelimit.expire_seconds", 300)).thenReturn(300);
        when(config.getInt("security.ratelimit.max_attempts", 5)).thenReturn(3); // Low limit for testing
        when(config.getLong("security.ratelimit.block_seconds", 300)).thenReturn(60L);
    }

    @Test
    public void testLimitExceededConfig() {
        rateLimitService = new RateLimitService(plugin);
        String ip = "192.168.1.100";

        // 1. First attempt - allowed
        assertTrue(rateLimitService.isAllowed(ip));
        rateLimitService.recordFailure(ip);

        // 2. Second attempt - allowed
        assertTrue(rateLimitService.isAllowed(ip));
        rateLimitService.recordFailure(ip);

        // 3. Third attempt - allowed (but triggers block on failure)
        assertTrue(rateLimitService.isAllowed(ip));
        rateLimitService.recordFailure(ip); // Attempts = 3 (Max) -> Block triggered

        // 4. Fourth attempt - BLOCKED
        // Note: isAllowed checks cache which might be async in caffeine, but caffeine
        // put is atomic.
        // If blocking is immediate, this should be false.
        assertFalse(rateLimitService.isAllowed(ip), "IP should be blocked after 3 fails");
    }

    @Test
    public void testResetLimit() {
        rateLimitService = new RateLimitService(plugin);
        String ip = "10.0.0.1";

        // Trigger block
        rateLimitService.recordFailure(ip);
        rateLimitService.recordFailure(ip);
        rateLimitService.recordFailure(ip);
        assertFalse(rateLimitService.isAllowed(ip));

        // Act
        rateLimitService.reset(ip);

        // Assert
        assertTrue(rateLimitService.isAllowed(ip), "IP should be allowed after reset");
    }
}
