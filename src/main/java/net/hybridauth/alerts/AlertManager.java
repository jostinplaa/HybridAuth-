package net.hybridauth.alerts;

import net.hybridauth.HybridAuthPlugin;
import java.util.ArrayList;
import java.util.List;

public class AlertManager {

    private final HybridAuthPlugin plugin;
    private final List<AlertService> services = new ArrayList<>();

    public AlertManager(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        services.clear();

        // Register Discord
        if (plugin.getConfig().getBoolean("alerts.discord.enabled")) {
            services.add(new DiscordAlertService(plugin));
            plugin.getLogger().info("Alerts: Discord service registered.");
        }

        // Register Email (placeholder for now, or reuse existing)
        if (plugin.getConfig().getBoolean("alerts.email.enabled")) {
            // services.add(new EmailAlertService(plugin));
            // For now we just log it as not implemented but ready
            plugin.getLogger().info("Alerts: Email service enabled (logic integrated in next step).");
        }
    }

    public void sendAlert(AlertService.AlertType type, String message, String details) {
        for (AlertService service : services) {
            try {
                service.sendAlert(type, message, details);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send alert via " + service.getClass().getSimpleName());
                e.printStackTrace();
            }
        }
    }
}
