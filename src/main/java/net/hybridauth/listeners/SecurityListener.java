package net.hybridauth.listeners;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

public class SecurityListener implements Listener {

    private final HybridAuthPlugin plugin;

    public SecurityListener(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();

        if (!plugin.getRateLimitService().isAllowed(ip)) {
            long remaining = plugin.getRateLimitService().getSecondsRemaining(ip);

            // Only log if not just spamming (maybe check if block happened recently?
            // For now simple log is fine, though might spam DB)
            // Let's log it anyway as "BLOCKED_CONNECTION"
            plugin.getSecurityLogger().log(
                    net.hybridauth.security.SecurityLogger.EventType.RATE_LIMIT,
                    event.getName(),
                    event.getUniqueId(),
                    ip,
                    "Blocked for " + remaining + "s");

            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "§c§lHybridAuth Security\n\n§7Tu dirección IP está temporalmente bloqueada.\n§7Razón: §fDemasiados intentos fallidos.\n§7Expira en: §e"
                            + remaining + "s");
            return;
        }
    }
}
