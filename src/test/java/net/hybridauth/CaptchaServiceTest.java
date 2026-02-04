package net.hybridauth;

import net.hybridauth.core.messages.MessageManager;
import net.hybridauth.security.captcha.CaptchaService;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

public class CaptchaServiceTest {

    @Mock
    private HybridAuthPlugin plugin;
    @Mock
    private FileConfiguration config;
    @Mock
    private Server server;
    @Mock
    private PluginManager pluginManager;
    @Mock
    private MessageManager messageManager;

    private CaptchaService captchaService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(plugin.getMessageManager()).thenReturn(messageManager);

        // Mock scheduler to avoid NPE during requireCaptcha (though task won't run)
        when(server.getScheduler()).thenReturn(mock(org.bukkit.scheduler.BukkitScheduler.class));

        when(config.getBoolean("security.captcha.enabled", true)).thenReturn(true);
        when(messageManager.getMessage(anyString(), any())).thenReturn("Captcha Prompt");
        when(messageManager.getMessage(anyString())).thenReturn("Captcha Message");
    }

    @Test
    public void testRequireCaptcha() {
        captchaService = new CaptchaService(plugin);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        // Act
        captchaService.requireCaptcha(player, CaptchaService.CaptchaReason.SUSPICIOUS_IP);

        // Assert
        assertTrue(captchaService.hasPendingCaptcha(player), "Player should have pending captcha");
        verify(player).sendMessage(any(String.class)); // Message was sent
    }

    @Test
    public void testClearCaptcha() {
        captchaService = new CaptchaService(plugin);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        // Arrange
        captchaService.requireCaptcha(player, CaptchaService.CaptchaReason.BOT_BEHAVIOR);
        assertTrue(captchaService.hasPendingCaptcha(player));

        // Act
        captchaService.clearCaptcha(player.getUniqueId());

        // Assert
        assertFalse(captchaService.hasPendingCaptcha(player), "Captcha should be cleared");
    }
}
