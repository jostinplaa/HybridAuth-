package net.hybridauth.listeners;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.security.geoip.GeoLocationService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.net.InetAddress;

public class GeoListener implements Listener {

    private final HybridAuthPlugin plugin;
    private final GeoLocationService geoService;

    public GeoListener(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.geoService = plugin.getGeoLocationService();
    }

    // Priority LOW to run after BlacklistListener (which is HIGHEST/HIGH)
    // We want cheap checks (blacklist) first, then GeoIP (database lookup)
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!geoService.isEnabled()) {
            return;
        }

        InetAddress address = event.getAddress();
        String countryCode = geoService.getCountryCode(address);

        if (countryCode != null && geoService.isCountryBlocked(countryCode)) {
            String countryName = geoService.getCountryName(address);

            plugin.getLogger()
                    .warning("[GeoIP] Blocked connection from " + event.getName() + " (Country: " + countryName + ")");

            // Log security event
            plugin.getSecurityLogger().logWarning("GEO_BLOCK: player=" + event.getName() + ", country=" + countryName
                    + ", ip=" + address.getHostAddress());

            if ("KICK".equalsIgnoreCase(geoService.getAction())) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        "cYour country (" + countryName + ") is blocked from this server.");
            } else {
                // ALERT only
                plugin.getLogger()
                        .warning("[GeoIP] ALERT: User from blocked country allowed (Action=ALERT): " + event.getName());
            }
        }
    }
}

