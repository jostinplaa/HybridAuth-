package net.hybridauth.alerts;

import net.hybridauth.HybridAuthPlugin;
import java.util.List;

public class DiscordAlertService implements AlertService {

    private final HybridAuthPlugin plugin;
    private final DiscordWebhook webhook;
    private final List<String> enabledEvents;

    public DiscordAlertService(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        String url = plugin.getConfig().getString("alerts.discord.webhook-url");
        this.webhook = new DiscordWebhook(url);
        this.enabledEvents = plugin.getConfig().getStringList("alerts.discord.notify-events");
    }

    @Override
    public void sendAlert(AlertType type, String message, String details) {
        if (!plugin.getConfig().getBoolean("alerts.discord.enabled", false)) {
            return;
        }

        if (!enabledEvents.contains(type.name())) {
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String content = String.format("**[%s]** %s\n> %s", type.name(), message, details);
            webhook.send(content, "HybridAuth Security");
        });
    }
}
