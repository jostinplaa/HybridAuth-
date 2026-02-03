package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.security.blacklist.BlacklistManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Comando /security para gestionar la seguridad del servidor
 * 
 * Subcomandos:
 * - /security blacklist <add|remove|list|info> - Gestionar blacklist de IPs
 * - /security alerts - Ver alertas de seguridad recientes
 * - /security stats - Estadísticas de seguridad
 * 
 * @version 1.2.0
 */
public class SecurityCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final BlacklistManager blacklistManager;

    public SecurityCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.blacklistManager = plugin.getBlacklistManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hybridauth.security")) {
            sender.sendMessage("§c§l✖ §cNo tienes permiso para usar este comando.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "blacklist":
                handleBlacklist(sender, args);
                break;

            case "stats":
                handleStats(sender);
                break;

            case "help":
                sendHelp(sender);
                break;

            default:
                sender.sendMessage("§c§l✖ §cSubcomando desconocido: §f" + args[0]);
                sender.sendMessage("§7Usa §f/security help §7para ver los comandos disponibles");
                break;
        }

        return true;
    }

    /**
     * Maneja subcomandos de blacklist
     */
    private void handleBlacklist(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c§l✖ §cUso: §f/security blacklist <add|remove|list|info|cleanup>");
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "add":
                handleBlacklistAdd(sender, args);
                break;

            case "remove":
                handleBlacklistRemove(sender, args);
                break;

            case "list":
                handleBlacklistList(sender, args);
                break;

            case "info":
                handleBlacklistInfo(sender, args);
                break;

            case "cleanup":
                handleBlacklistCleanup(sender);
                break;

            default:
                sender.sendMessage("§c§l✖ §cAcción inválida: §f" + action);
                sender.sendMessage("§7Usa: §fadd§7, §fremove§7, §flist§7, §finfo§7, §fcleanup");
                break;
        }
    }

    /**
     * /security blacklist add <ip> [duration] [reason]
     */
    private void handleBlacklistAdd(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c§l✖ §cUso: §f/security blacklist add <ip> [duration] [reason]");
            sender.sendMessage("§7Duración: §fpermanent §7o tiempo en segundos (ej: §f3600§7)");
            sender.sendMessage("§7Ejemplo: §f/security blacklist add 123.45.67.89 3600 Spam");
            return;
        }

        String ip = args[2];
        String blockedBy = sender.getName();

        // Validar formato de IP básico
        if (!ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            sender.sendMessage("§c§l✖ §cFormato de IP inválido: §f" + ip);
            sender.sendMessage("§7Ejemplo: §f123.45.67.89");
            return;
        }

        // Verificar si ya está bloqueada
        if (blacklistManager.isBlocked(ip)) {
            sender.sendMessage("§e§l⚠ §eEsta IP ya está en la blacklist");
            sender.sendMessage("§7Usa §f/security blacklist info " + ip + " §7para ver detalles");
            return;
        }

        // Parsear duración
        boolean permanent = false;
        long duration = 3600; // 1 hora por defecto

        if (args.length >= 4) {
            String durationArg = args[3].toLowerCase();

            if (durationArg.equals("permanent") || durationArg.equals("perm")) {
                permanent = true;
            } else {
                try {
                    duration = Long.parseLong(durationArg);
                    if (duration <= 0) {
                        sender.sendMessage("§c§l✖ §cLa duración debe ser mayor a 0");
                        return;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c§l✖ §cDuración inválida: §f" + durationArg);
                    sender.sendMessage("§7Usa §fpermanent §7o un número en segundos");
                    return;
                }
            }
        }

        // Parsear razón
        String reason = "No reason specified";
        if (args.length >= 5) {
            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = 4; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            reason = reasonBuilder.toString().trim();
        }

        // Bloquear
        if (permanent) {
            blacklistManager.blockIPPermanent(ip, reason, blockedBy);
            
            sender.sendMessage("");
            sender.sendMessage("§4§l✔ IP BLOQUEADA PERMANENTEMENTE");
            sender.sendMessage("§7IP: §f" + ip);
            sender.sendMessage("§7Razón: §f" + reason);
            sender.sendMessage("§7Bloqueada por: §f" + blockedBy);
            sender.sendMessage("");
        } else {
            blacklistManager.blockIP(ip, duration, reason, blockedBy);
            
            String timeStr = formatDuration(duration);
            
            sender.sendMessage("");
            sender.sendMessage("§6§l✔ IP BLOQUEADA TEMPORALMENTE");
            sender.sendMessage("§7IP: §f" + ip);
            sender.sendMessage("§7Duración: §f" + timeStr);
            sender.sendMessage("§7Razón: §f" + reason);
            sender.sendMessage("§7Bloqueada por: §f" + blockedBy);
            sender.sendMessage("");
        }

        // Kickear jugadores con esa IP
        kickPlayersWithIP(sender, ip);
    }

    /**
     * /security blacklist remove <ip>
     */
    private void handleBlacklistRemove(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c§l✖ §cUso: §f/security blacklist remove <ip>");
            return;
        }

        String ip = args[2];
        String unblockedBy = sender.getName();

        boolean removed = blacklistManager.unblockIP(ip, unblockedBy);

        if (removed) {
            sender.sendMessage("");
            sender.sendMessage("§a§l✔ IP DESBLOQUEADA");
            sender.sendMessage("§7IP: §f" + ip);
            sender.sendMessage("§7Desbloqueada por: §f" + unblockedBy);
            sender.sendMessage("");
        } else {
            sender.sendMessage("§c§l✖ §cEsta IP no está en la blacklist");
        }
    }

    /**
     * /security blacklist list [page]
     */
    private void handleBlacklistList(CommandSender sender, String[] args) {
        List<BlacklistManager.BlacklistEntry> entries = blacklistManager.getAllEntries();

        if (entries.isEmpty()) {
            sender.sendMessage("§a§l✔ §aNo hay IPs en la blacklist");
            return;
        }

        // Paginación
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§c§l✖ §cNúmero de página inválido");
                return;
            }
        }

        int perPage = 10;
        int totalPages = (int) Math.ceil((double) entries.size() / perPage);

        if (page < 1 || page > totalPages) {
            sender.sendMessage("§c§l✖ §cPágina inválida. Total: §f" + totalPages);
            return;
        }

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, entries.size());

        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage("§4§l  IP BLACKLIST §7(Página " + page + "/" + totalPages + ")");
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        for (int i = start; i < end; i++) {
            BlacklistManager.BlacklistEntry entry = entries.get(i);
            
            String typeColor = entry.permanent ? "§4" : "§6";
            String type = entry.permanent ? "[PERM]" : "[TEMP]";
            
            sender.sendMessage("");
            sender.sendMessage("§7" + (i + 1) + ". " + typeColor + type + " §f" + entry.ip);
            sender.sendMessage("   §7Razón: §f" + entry.reason);
            sender.sendMessage("   §7Por: §f" + entry.blockedBy);
            
            if (!entry.permanent) {
                sender.sendMessage("   §7Expira en: §e" + entry.getTimeRemaining());
            }
        }

        sender.sendMessage("");
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage("§7Total de IPs bloqueadas: §f" + entries.size());
        
        if (page < totalPages) {
            sender.sendMessage("§7Siguiente página: §f/security blacklist list " + (page + 1));
        }
    }

    /**
     * /security blacklist info <ip>
     */
    private void handleBlacklistInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c§l✖ §cUso: §f/security blacklist info <ip>");
            return;
        }

        String ip = args[2];
        BlacklistManager.BlacklistEntry entry = blacklistManager.getEntry(ip);

        if (entry == null) {
            sender.sendMessage("§c§l✖ §cEsta IP no está en la blacklist");
            return;
        }

        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage("§4§l  INFORMACIÓN DE BLACKLIST");
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage("");
        sender.sendMessage("§7IP: §f" + entry.ip);
        sender.sendMessage("§7Tipo: " + (entry.permanent ? "§4§lPERMANENTE" : "§6§lTEMPORAL"));
        sender.sendMessage("§7Razón: §f" + entry.reason);
        sender.sendMessage("§7Bloqueada por: §f" + entry.blockedBy);
        sender.sendMessage("§7Bloqueada el: §f" + entry.getFormattedBlockedAt());
        
        if (!entry.permanent) {
            sender.sendMessage("§7Expira el: §f" + entry.getFormattedExpiry());
            sender.sendMessage("§7Tiempo restante: §e" + entry.getTimeRemaining());
        }
        
        sender.sendMessage("");
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    /**
     * /security blacklist cleanup
     */
    private void handleBlacklistCleanup(CommandSender sender) {
        sender.sendMessage("§e§l⏳ §eLimpiando entradas expiradas...");
        
        int before = blacklistManager.getAllEntries().size();
        int after = blacklistManager.cleanupAll();
        int removed = before - after;
        
        sender.sendMessage("§a§l✔ §aLimpieza completada");
        sender.sendMessage("§7Entradas removidas: §f" + removed);
        sender.sendMessage("§7Entradas restantes: §f" + after);
    }

    /**
     * /security stats
     */
    private void handleStats(CommandSender sender) {
        BlacklistManager.BlacklistStats stats = blacklistManager.getStats();

        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage("§4§l  ESTADÍSTICAS DE SEGURIDAD");
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage("");
        sender.sendMessage("§7Total IPs bloqueadas: §f" + stats.total);
        sender.sendMessage("§7  §4● §7Permanentes: §f" + stats.permanent);
        sender.sendMessage("§7  §6● §7Temporales: §f" + stats.temporary);
        sender.sendMessage("");
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    /**
     * Muestra ayuda
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage("§4§l  COMANDOS DE SEGURIDAD");
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage("");
        sender.sendMessage("§7/security blacklist add <ip> [duration] [reason]");
        sender.sendMessage("  §8→ §fBloquear una IP");
        sender.sendMessage("");
        sender.sendMessage("§7/security blacklist remove <ip>");
        sender.sendMessage("  §8→ §fDesbloquear una IP");
        sender.sendMessage("");
        sender.sendMessage("§7/security blacklist list [page]");
        sender.sendMessage("  §8→ §fListar IPs bloqueadas");
        sender.sendMessage("");
        sender.sendMessage("§7/security blacklist info <ip>");
        sender.sendMessage("  §8→ §fVer información de una IP");
        sender.sendMessage("");
        sender.sendMessage("§7/security blacklist cleanup");
        sender.sendMessage("  §8→ §fLimpiar entradas expiradas");
        sender.sendMessage("");
        sender.sendMessage("§7/security stats");
        sender.sendMessage("  §8→ §fEstadísticas de seguridad");
        sender.sendMessage("");
        sender.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    /**
     * Formatea duración en segundos a texto legible
     */
    private String formatDuration(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (secs > 0 || sb.length() == 0) sb.append(secs).append("s");

        return sb.toString().trim();
    }

    /**
     * Kickea jugadores conectados con la IP bloqueada
     */
    private void kickPlayersWithIP(CommandSender sender, String ip) {
        int kicked = 0;
        
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            String playerIP = player.getAddress().getAddress().getHostAddress();
            
            if (playerIP.equals(ip)) {
                player.kickPlayer(
                    "§4§l⚠ TU IP HA SIDO BLOQUEADA ⚠\n\n" +
                    "§cContacta a un administrador del servidor"
                );
                kicked++;
            }
        }
        
        if (kicked > 0) {
            sender.sendMessage("§7Jugadores kickeados: §f" + kicked);
        }
    }
}
