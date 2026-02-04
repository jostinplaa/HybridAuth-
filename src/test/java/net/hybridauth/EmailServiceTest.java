package net.hybridauth;

import net.hybridauth.email.EmailService;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class EmailServiceTest {

    @Mock
    private HybridAuthPlugin plugin;
    @Mock
    private FileConfiguration config;

    private EmailService emailService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(config.getString("email.provider", "sendgrid")).thenReturn("sendgrid");
    }

    @Test
    public void testGenerateCodeFormat() {
        // Arrange
        when(config.getBoolean("email.enabled", false)).thenReturn(true);
        emailService = new EmailService(plugin);

        // Act
        String code = emailService.generateCode();

        // Assert
        assertNotNull(code);
        assertEquals(6, code.length());
        assertTrue(code.matches("\\d+"), "Code should be numeric");
    }

    @Test
    public void testSendReturnsFalseWhenDisabled() {
        // Arrange
        when(config.getBoolean("email.enabled", false)).thenReturn(false);
        emailService = new EmailService(plugin);

        // Act
        boolean result = emailService.sendVerificationCode("test@example.com", "User", "123456");

        // Assert
        assertFalse(result, "Should return false when disabled");
    }
}
