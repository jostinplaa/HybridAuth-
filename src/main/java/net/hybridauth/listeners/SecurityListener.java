package net.hybridauth.listeners;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public class SecurityListener implements Listener {

    private final HybridAuthPlugin plugin;
    private final MessageManager messages;

    public SecurityListener(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessageManager();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();

        if (!plugin.getRateLimitService().isAllowed(ip)) {
            long remaining = plugin.getRateLimitService().getSecondsRemaining(ip);

            // Log security event
            plugin.getSecurityLogger().log(
                    net.hybridauth.security.SecurityLogger.EventType.RATE_LIMIT,
                    event.getName(),
                    event.getUniqueId(),
                    ip,
                    "Blocked for " + remaining + "s");

            // Get formatted kick message
            String kickMessage = messages.getMessage("rate_limit.kick_message",
                    MessageManager.placeholder()
                            .add("remaining", remaining)
                            .build());

            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
        }
    }
}
