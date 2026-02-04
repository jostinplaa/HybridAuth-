package net.hybridauth.security.geoip;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import net.hybridauth.HybridAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

/**
 * Service to handle IP Geolocation using MaxMind GeoIP2.
 */
public class GeoLocationService {

    private final HybridAuthPlugin plugin;
    private DatabaseReader dbReader;
    private boolean enabled;
    private File databaseFile;
    private Set<String> blockedCountries;
    private boolean alertOnChange;
    private String action;

    public GeoLocationService(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.reloadConfig();
    }

    public void reloadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.enabled = config.getBoolean("security.geoip.enabled", false);
        this.alertOnChange = config.getBoolean("security.geoip.alert-on-change", true);
        this.action = config.getString("security.geoip.action", "KICK");
        this.blockedCountries = new HashSet<>(config.getStringList("security.geoip.blocked-countries"));

        String dbFileName = config.getString("security.geoip.database-file", "GeoLite2-Country.mmdb");
        this.databaseFile = new File(plugin.getDataFolder(), dbFileName);

        if (enabled) {
            initializeDatabase();
        } else {
            closeDatabase();
        }
    }

    private void initializeDatabase() {
        try {
            if (!databaseFile.exists()) {
                plugin.getLogger().warning("[GeoIP] Database file not found: " + databaseFile.getName());
                plugin.getLogger()
                        .warning("[GeoIP] Please download GeoLite2-Country.mmdb and place it in plugin folder.");
                plugin.getLogger().warning("[GeoIP] GeoIP features will be DISABLED until file is present.");
                this.enabled = false;
                return;
            }

            dbReader = new DatabaseReader.Builder(databaseFile).build();
            plugin.getLogger().info("[GeoIP] Database loaded successfully!");
            plugin.getLogger().info("[GeoIP] Blocking " + blockedCountries.size() + " countries.");

        } catch (IOException e) {
            plugin.getLogger().severe("[GeoIP] Failed to load database: " + e.getMessage());
            this.enabled = false;
        }
    }

    private void closeDatabase() {
        if (dbReader != null) {
            try {
                dbReader.close();
            } catch (IOException e) {
                // ignore
            }
            dbReader = null;
        }
    }

    public String getCountryCode(InetAddress ip) {
        if (!enabled || dbReader == null)
            return null;

        try {
            CountryResponse response = dbReader.country(ip);
            return response.getCountry().getIsoCode(); // e.g. "US", "ES", "CN"
        } catch (IOException | GeoIp2Exception e) {
            // IP not found or private IP
            return null;
        }
    }

    public String getCountryName(InetAddress ip) {
        if (!enabled || dbReader == null)
            return null;

        try {
            CountryResponse response = dbReader.country(ip);
            return response.getCountry().getName();
        } catch (IOException | GeoIp2Exception e) {
            return "Unknown";
        }
    }

    public boolean isCountryBlocked(String countryCode) {
        if (!enabled || countryCode == null)
            return false;
        return blockedCountries.contains(countryCode.toUpperCase());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getAction() {
        return action;
    }

    public boolean isAlertOnChange() {
        return alertOnChange;
    }

    public void shutdown() {
        closeDatabase();
    }
}

