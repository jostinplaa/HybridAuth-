package net.hybridauth.listeners;

import net.hybridauth.HybridAuthPlugin;
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
        
        // Verificar si está en blacklist
        if (blacklistManager.isBlocked(ip)) {
            BlacklistManager.BlacklistEntry entry = blacklistManager.getEntry(ip);
            
            if (entry == null) {
                return; // Expiró entre medio
            }
            
            // Construir mensaje de kick
            String kickMessage = buildKickMessage(entry);
            
            // Denegar conexión
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_BANNED, 
                kickMessage
            );
            
            plugin.getLogger().warning(
                String.format("BLOCKED CONNECTION: %s from blacklisted IP %s (Reason: %s)",
                    event.getName(), ip, entry.reason)
            );
            
            // Log de seguridad
            plugin.getSecurityLogger().logWarning(
                String.format("BLACKLIST_BLOCK: player=%s, ip=%s, reason=%s",
                    event.getName(), ip, entry.reason)
            );
        }
    }

    /**
     * Construye mensaje de kick formateado
     */
    private String buildKickMessage(BlacklistManager.BlacklistEntry entry) {
        StringBuilder msg = new StringBuilder();
        
        msg.append("§4§l╔════════════════════════════════════╗\n");
        msg.append("§4§l║    ⚠  IP BLACKLISTED  ⚠           ║\n");
        msg.append("§4§l╠════════════════════════════════════╣\n");
        msg.append("§c\n");
        msg.append("§c  Your IP address has been blocked\n");
        msg.append("§c  from accessing this server.\n");
        msg.append("§7\n");
        msg.append("§7  IP: §f").append(entry.ip).append("\n");
        msg.append("§7  Reason: §c").append(entry.reason).append("\n");
        msg.append("§7  Blocked by: §f").append(entry.blockedBy).append("\n");
        msg.append("§7  Blocked at: §f").append(entry.getFormattedBlockedAt()).append("\n");
        msg.append("§7\n");
        
        if (entry.permanent) {
            msg.append("§4§l  Duration: PERMANENT\n");
        } else {
            msg.append("§e  Expires: §f").append(entry.getFormattedExpiry()).append("\n");
            msg.append("§e  Time remaining: §f").append(entry.getTimeRemaining()).append("\n");
        }
        
        msg.append("§7\n");
        msg.append("§7  If you believe this is an error,\n");
        msg.append("§7  contact a server administrator.\n");
        msg.append("§7\n");
        msg.append("§4§l╚════════════════════════════════════╝");
        
        return msg.toString();
    }
}
