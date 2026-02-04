package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.backup.BackupService;
import net.hybridauth.core.messages.MessageManager;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Subcomando para gestionar backups
 * Usage: /hy backup [now|list|restore <file>]
 */
public class BackupCommand {

    private final HybridAuthPlugin plugin;
    private final MessageManager messages;
    private final Map<String, String> restoreConfirmations = new HashMap<>();

    public BackupCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessageManager();
    }

    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hybridauth.admin.backup")) {
            messages.send(sender, "error.no_permission");
            return;
        }

        if (args.length < 2) {
            showHelp(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "now":
                handleBackupNow(sender);
                break;
            case "list":
                handleBackupList(sender);
                break;
            case "restore":
                if (args.length < 3) {
                    sender.sendMessage("cUso: /hy backup restore <archivo>");
                    return;
                }
                handleBackupRestore(sender, args);
                break;
            default:
                showHelp(sender);
                break;
        }
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("6=== HybridAuth Backup ===");
        sender.sendMessage("f/hy backup now 7- Crear backup manual");
        sender.sendMessage("f/hy backup list 7- Listar backups");
        sender.sendMessage("f/hy backup restore <archivo> 7- Restaurar (PELIGROSO)");
    }

    private void handleBackupNow(CommandSender sender) {
        sender.sendMessage("eCreando backup...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BackupService.BackupResult result = plugin.getBackupService().createBackup();

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (result.success) {
                    sender.sendMessage("a Backup creado: f" + result.filename);
                    int cleaned = plugin.getBackupService().cleanOldBackups();
                    if (cleaned > 0) {
                        sender.sendMessage("7Limpiados " + cleaned + " backups antiguos");
                    }
                } else {
                    sender.sendMessage("c Error: " + result.error);
                }
            });
        });
    }

    private void handleBackupList(CommandSender sender) {
        List<BackupService.BackupInfo> backups = plugin.getBackupService().getBackupList();

        if (backups.isEmpty()) {
            sender.sendMessage("c No hay backups disponibles");
            return;
        }

        sender.sendMessage("6=== Backups Disponibles ===");
        int index = 1;
        for (BackupService.BackupInfo backup : backups) {
            sender.sendMessage(String.format("f%d. 7%s 8(%s, %s)",
                    index++, backup.filename, backup.getFormattedSize(), backup.getFormattedDate()));
        }
    }

    private void handleBackupRestore(CommandSender sender, String[] args) {
        String filename = args[2];

        // Confirmacin doble
        if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
            if (!restoreConfirmations.containsKey(sender.getName())) {
                sender.sendMessage("c lnADVERTENCIA: OPERACIN PELIGROSA");
                sender.sendMessage("");
                sender.sendMessage("eRestaurar f" + filename + "e sobrescribir la BD actual:");
                sender.sendMessage("");
                sender.sendMessage("c   Se PERDERN todos los datos desde el backup");
                sender.sendMessage("c   Los jugadores sern DESCONECTADOS");
                sender.sendMessage("");
                sender.sendMessage("6Si ests SEGURO, escribe:");
                sender.sendMessage("f/hy backup restore " + filename + " confirm");

                restoreConfirmations.put(sender.getName(), filename);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    restoreConfirmations.remove(sender.getName());
                }, 600L); // 30 segundos

                return;
            }
        }

        String confirmed = restoreConfirmations.get(sender.getName());
        if (confirmed == null || !confirmed.equals(filename)) {
            sender.sendMessage("c Confirmacin expirada o archivo no coincide");
            restoreConfirmations.remove(sender.getName());
            return;
        }

        restoreConfirmations.remove(sender.getName());
        sender.sendMessage("e Restaurando backup...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BackupService.BackupResult result = plugin.getBackupService().restoreBackup(filename);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (result.success) {
                    sender.sendMessage("a Backup restaurado: f" + filename);
                    sender.sendMessage("6l REINICIA EL SERVIDOR AHORA");

                    // Kickear todos
                    plugin.getServer().getOnlinePlayers().forEach(p -> {
                        p.kickPlayer("clBase de datos restaurada\n\nfReconecta en unos segundos.");
                    });
                } else {
                    sender.sendMessage("c Error: " + result.error);
                }
            });
        });
    }
}
