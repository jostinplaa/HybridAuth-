package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
import net.hybridauth.security.blacklist.BlacklistManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Comando /security - VERSIN CORREGIDA SIN MENSAJES HARDCODEADOS
 */
public class SecurityCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final BlacklistManager blacklistManager;
    private final MessageManager messages;

    public SecurityCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.blacklistManager = plugin.getBlacklistManager();
        this.messages = plugin.getMessageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hybridauth.security")) {
            messages.send(sender, "security.no_permission");
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
                messages.send(sender, "error.unknown_command");
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void handleBlacklist(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "security.blacklist.add.usage");
            return;
        }

        switch (args[1].toLowerCase()) {
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
                messages.send(sender, "error.unknown_command");
                break;
        }
    }

    private void handleBlacklistAdd(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "security.blacklist.add.usage");
            return;
        }

        String ip = args[2];
        String blockedBy = sender.getName();

        if (!ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            messages.send(sender, "security.blacklist.add.invalid_ip",
                MessageManager.placeholder().add("ip", ip).build());
            return;
        }

        if (blacklistManager.isBlocked(ip)) {
            messages.send(sender, "security.blacklist.add.already_blocked",
                MessageManager.placeholder().add("ip", ip).build());
            return;
        }

        boolean permanent = false;
        long duration = 3600;

        if (args.length >= 4) {
            String durationArg = args[3].toLowerCase();
            if (durationArg.equals("permanent") || durationArg.equals("perm")) {
                permanent = true;
            } else {
                try {
                    duration = Long.parseLong(durationArg);
                    if (duration <= 0) {
                        messages.send(sender, "security.blacklist.add.duration_zero");
                        return;
                    }
                } catch (NumberFormatException e) {
                    messages.send(sender, "security.blacklist.add.invalid_duration",
                        MessageManager.placeholder().add("duration", durationArg).build());
                    return;
                }
            }
        }

        String reason = "No reason specified";
        if (args.length >= 5) {
            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = 4; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            reason = reasonBuilder.toString().trim();
        }

        if (permanent) {
            blacklistManager.blockIPPermanent(ip, reason, blockedBy);
            messages.send(sender, "security.blacklist.add.success_permanent",
                MessageManager.placeholder()
                    .add("ip", ip)
                    .add("reason", reason)
                    .add("blocked_by", blockedBy)
                    .build());
        } else {
            blacklistManager.blockIP(ip, duration, reason, blockedBy);
            messages.send(sender, "security.blacklist.add.success_temporary",
                MessageManager.placeholder()
                    .add("ip", ip)
                    .add("duration", formatDuration(duration))
                    .add("reason", reason)
                    .add("blocked_by", blockedBy)
                    .build());
        }

        int kicked = kickPlayersWithIP(ip);
        if (kicked > 0) {
            messages.send(sender, "security.blacklist.add.players_kicked",
                MessageManager.placeholder().add("count", kicked).build());
        }
    }

    private void handleBlacklistRemove(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "security.blacklist.remove.usage");
            return;
        }

        String ip = args[2];
        boolean removed = blacklistManager.unblockIP(ip, sender.getName());

        if (removed) {
            messages.send(sender, "security.blacklist.remove.success",
                MessageManager.placeholder()
                    .add("ip", ip)
                    .add("unblocked_by", sender.getName())
                    .build());
        } else {
            messages.send(sender, "security.blacklist.remove.not_found");
        }
    }

    private void handleBlacklistList(CommandSender sender, String[] args) {
        List<BlacklistManager.BlacklistEntry> entries = blacklistManager.getAllEntries();

        if (entries.isEmpty()) {
            messages.send(sender, "security.blacklist.list.empty");
            return;
        }

        int page = 1;
        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                page = 1;
            }
        }

        int perPage = 10;
        int totalPages = (int) Math.ceil((double) entries.size() / perPage);

        if (page < 1 || page > totalPages) {
            messages.send(sender, "security.blacklist.list.invalid_page",
                MessageManager.placeholder().add("total_pages", totalPages).build());
            return;
        }

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, entries.size());

        messages.send(sender, "security.blacklist.list.header",
            MessageManager.placeholder()
                .add("page", page)
                .add("total_pages", totalPages)
                .build());

        for (int i = start; i < end; i++) {
            BlacklistManager.BlacklistEntry entry = entries.get(i);
            String key = entry.permanent ? 
                "security.blacklist.list.entry_permanent" : 
                "security.blacklist.list.entry_temporary";
            
            MessageManager.PlaceholderBuilder pb = MessageManager.placeholder()
                .add("index", i + 1)
                .add("ip", entry.ip)
                .add("reason", entry.reason)
                .add("blocked_by", entry.blockedBy);
            
            if (!entry.permanent) {
                pb.add("time_remaining", entry.getTimeRemaining());
            }
            
            messages.send(sender, key, pb.build());
        }

        messages.send(sender, "security.blacklist.list.footer",
            MessageManager.placeholder().add("total", entries.size()).build());
        
        if (page < totalPages) {
            messages.send(sender, "security.blacklist.list.next_page",
                MessageManager.placeholder().add("next_page", page + 1).build());
        }
    }

    private void handleBlacklistInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "security.blacklist.info.usage");
            return;
        }

        String ip = args[2];
        BlacklistManager.BlacklistEntry entry = blacklistManager.getEntry(ip);

        if (entry == null) {
            messages.send(sender, "security.blacklist.info.not_found");
            return;
        }

        String typeKey = entry.permanent ? 
            "security.blacklist.info.type_permanent" : 
            "security.blacklist.info.type_temporary";
        String type = messages.getMessage(typeKey);
        
        String expiryInfo = "";
        if (!entry.permanent) {
            expiryInfo = messages.getMessage("security.blacklist.info.expiry_lines",
                MessageManager.placeholder()
                    .add("expires_at", entry.getFormattedExpiry())
                    .add("time_remaining", entry.getTimeRemaining())
                    .build());
        }
        
        messages.send(sender, "security.blacklist.info.display",
            MessageManager.placeholder()
                .add("ip", entry.ip)
                .add("type", type)
                .add("reason", entry.reason)
                .add("blocked_by", entry.blockedBy)
                .add("blocked_at", entry.getFormattedBlockedAt())
                .add("expiry_info", expiryInfo)
                .build());
    }

    private void handleBlacklistCleanup(CommandSender sender) {
        messages.send(sender, "security.blacklist.cleanup.processing");
        int before = blacklistManager.getAllEntries().size();
        int after = blacklistManager.cleanupAll();
        messages.send(sender, "security.blacklist.cleanup.success",
            MessageManager.placeholder()
                .add("removed", before - after)
                .add("remaining", after)
                .build());
    }

    private void handleStats(CommandSender sender) {
        BlacklistManager.BlacklistStats stats = blacklistManager.getStats();
        messages.send(sender, "security.stats.header");
        messages.send(sender, "security.stats.total_blocked",
            MessageManager.placeholder().add("total", stats.total).build());
        messages.send(sender, "security.stats.permanent_count",
            MessageManager.placeholder().add("permanent", stats.permanent).build());
        messages.send(sender, "security.stats.temporary_count",
            MessageManager.placeholder().add("temporary", stats.temporary).build());
        messages.send(sender, "security.stats.footer");
    }

    private void sendHelp(CommandSender sender) {
        messages.send(sender, "security.help.header");
        messages.send(sender, "security.help.add");
        messages.send(sender, "security.help.remove");
        messages.send(sender, "security.help.list");
        messages.send(sender, "security.help.info");
        messages.send(sender, "security.help.cleanup");
        messages.send(sender, "security.help.stats");
        messages.send(sender, "security.help.footer");
    }

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

    private int kickPlayersWithIP(String ip) {
        int kicked = 0;
        String kickMsg = messages.getMessage("security.blacklist_kick",
            MessageManager.placeholder()
                .add("ip", ip)
                .add("reason", "IP Blacklisted")
                .add("blocked_by", "SYSTEM")
                .add("blocked_at", "Just now")
                .add("duration_info", "")
                .build());
        
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getAddress().getAddress().getHostAddress().equals(ip)) {
                player.kickPlayer(kickMsg);
                kicked++;
            }
        }
        return kicked;
    }
}

