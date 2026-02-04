package net.hybridauth;

import net.hybridauth.integrations.discord.DiscordWebhook;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class DiscordWebhookTest {

    @Mock
    private HybridAuthPlugin plugin;
    @Mock
    private FileConfiguration config;
    @Mock
    private Logger logger;

    private DiscordWebhook webhook;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);

        // Default valid config
        when(config.getBoolean("integrations.discord.enabled", false)).thenReturn(true);
        when(config.getString("integrations.discord.webhook-url", ""))
                .thenReturn("http://discord.com/api/webhooks/fake");
    }

    @Test
    public void testIsEnabled() {
        webhook = new DiscordWebhook(plugin);
        assertTrue(webhook.isEnabled(), "Should be enabled with valid config");
    }

    @Test
    public void testDisabledIfUrlEmpty() {
        when(config.getString("integrations.discord.webhook-url", "")).thenReturn("");
        webhook = new DiscordWebhook(plugin);
        assertFalse(webhook.isEnabled(), "Should be disabled if URL is empty");
    }

    @Test
    public void testNotificationsDoNotCrash() {
        // We cannot test the actual HTTP call without complex mocking,
        // but we can ensure the public methods don't throw exceptions.
        webhook = new DiscordWebhook(plugin);

        // These run async, so we assume if no exception hits the main thread
        // immediately, likely ok
        // In a real integration test we would wait, but for unit test ensuring no NPE
        // is good start.
        webhook.notifyImpostor("Player", "1.1.1.1", "uuid1", "uuid2");
        webhook.notifyBruteForce("Player", "1.1.1.1", 5);

        // Verify no severe errors logged immediately (tho it's async)
        verify(plugin.getLogger(), never()).severe(anyString());
    }
}
