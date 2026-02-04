package net.hybridauth.listeners;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
import net.hybridauth.security.blacklist.BlacklistManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Listener que verifica la blacklist ANTES de que el jugador entre al servidor
 * 
 * @version 1.2.0
 */
public class BlacklistListener implements Listener {

    private final HybridAuthPlugin plugin;
    private final BlacklistManager blacklistManager;

    public BlacklistListener(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.blacklistManager = plugin.getBlacklistManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress().getHostAddress();

        // Verificar si est en blacklist
        if (blacklistManager.isBlocked(ip)) {
            BlacklistManager.BlacklistEntry entry = blacklistManager.getEntry(ip);

            if (entry == null) {
                return; // Expir entre medio
            }

            // Construir mensaje de kick
            String kickMessage = buildKickMessage(entry);

            // Denegar conexin
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    kickMessage);

            plugin.getLogger().warning(
                    String.format("BLOCKED CONNECTION: %s from blacklisted IP %s (Reason: %s)",
                            event.getName(), ip, entry.reason));

            // Log de seguridad
            plugin.getSecurityLogger().logWarning(
                    String.format("BLACKLIST_BLOCK: player=%s, ip=%s, reason=%s",
                            event.getName(), ip, entry.reason));
        }
    }

    /**
     * Construye mensaje de kick formateado usando MessageManager
     */
    private String buildKickMessage(BlacklistManager.BlacklistEntry entry) {
        String durationInfo;
        if (entry.permanent) {
            durationInfo = plugin.getMessageManager()
                    .getMessage("security.blacklist_duration_permanent");
        } else {
            durationInfo = plugin.getMessageManager().getMessage(
                    "security.blacklist_duration_temporary",
                    MessageManager.placeholder()
                            .add("expires_at", entry.getFormattedExpiry())
                            .add("time_remaining", entry.getTimeRemaining())
                            .build());
        }

        return plugin.getMessageManager().getMessage("security.blacklist_kick",
                MessageManager.placeholder()
                        .add("ip", entry.ip)
                        .add("reason", entry.reason)
                        .add("blocked_by", entry.blockedBy)
                        .add("blocked_at", entry.getFormattedBlockedAt())
                        .add("duration_info", durationInfo)
                        .build());
    }
}

