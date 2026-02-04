package net.hybridauth;

import net.hybridauth.alerts.AlertManager;
import net.hybridauth.alerts.AlertService;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

public class AlertManagerTest {

    @Mock
    private HybridAuthPlugin plugin;
    @Mock
    private FileConfiguration config;
    @Mock
    private Logger logger;

    private AlertManager alertManager;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);

        // Default: Disable discord to test empty state first
        when(config.getBoolean("alerts.discord.enabled")).thenReturn(false);
        when(config.getBoolean("alerts.email.enabled")).thenReturn(false);
    }

    @Test
    public void testReloadAndSend() {
        // Arrange
        alertManager = new AlertManager(plugin);

        // Act & Assert
        // Should not throw even if no services registered
        assertDoesNotThrow(() -> alertManager.sendAlert(AlertService.AlertType.BRUTE_FORCE, "Test", "Details"));
    }

    @Test
    public void testDiscordRegister() {
        // Arrange
        when(config.getBoolean("alerts.discord.enabled")).thenReturn(true);
        when(config.getString("integrations.discord.webhook-url")).thenReturn("http://fake.url");

        // Act
        alertManager = new AlertManager(plugin);

        // Assert
        // Verification that logging happened implies service registration attempted
        verify(plugin.getLogger(), atLeastOnce()).info(contains("Discord service registered"));
    }
}
