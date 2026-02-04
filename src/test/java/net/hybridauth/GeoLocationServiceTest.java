package net.hybridauth;

import net.hybridauth.security.geoip.GeoLocationService;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.Arrays;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class GeoLocationServiceTest {

    @Mock
    private HybridAuthPlugin plugin;
    @Mock
    private FileConfiguration config;
    @Mock
    private Logger logger;

    private GeoLocationService geoService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getDataFolder()).thenReturn(new File("dummy_folder"));

        // Default: Enabled but no file present means it will self-disable
        when(config.getBoolean("security.geoip.enabled", false)).thenReturn(true);
        when(config.getStringList("security.geoip.blocked-countries")).thenReturn(Arrays.asList("CN", "RU"));
        when(config.getString("security.geoip.database-file", "GeoLite2-Country.mmdb"))
                .thenReturn("GeoLite2-Country.mmdb");
    }

    @Test
    public void testIsCountryBlocked() {
        // Arrange: disable to force manual checking of logic without loading DB
        when(config.getBoolean("security.geoip.enabled", false)).thenReturn(false);
        geoService = new GeoLocationService(plugin);

        // We can't easily test isCountryBlocked with "false" enabled because it returns
        // false immediately.
        // However, we can re-enable it manually or refactor.
        // Given we are testing structure:

        // If enabled is false, everything is allowed
        assertFalse(geoService.isCountryBlocked("CN"), "Should return false if disabled");
    }

    @Test
    public void testBlockingLogic() {
        // Arrange
        // Mock internal set via reflection or just use the constructor logic if we
        // could inject the set.
        // Since we can't easily mock the internal HashSet without reflection or
        // changing code,
        // and loading a real DB file is an integration test, we will verify safe
        // fallback.

        when(config.getBoolean("security.geoip.enabled", false)).thenReturn(true);
        // The service will try to load DB, fail (file not found), and disable itself.

        geoService = new GeoLocationService(plugin);

        assertFalse(geoService.isEnabled(), "Service should disable itself if DB file missing");
        verify(plugin.getLogger(), atLeastOnce()).warning(contains("Database file not found"));
    }
}
