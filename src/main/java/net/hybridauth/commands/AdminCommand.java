package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
import net.hybridauth.data.model.User;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Comando administrativo para gestionar HybridAuth.
 * 
 * @version 1.1.0
 */
public class AdminCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final MessageManager messages;

    // Almacena confirmaciones pendientes: UUID del admin -> Contexto
    private final Map<UUID, ConfirmationContext> pendingConfirmations = new HashMap<>();

    public AdminCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hybridauth.admin")) {
            messages.send(sender, "error.no_permission");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                plugin.reloadConfig();
                plugin.getMessageManager().reload();
                messages.send(sender, "admin.reload.success");
                break;

            case "unregister":
                handleUnregister(sender, args);
                break;

            case "confirm":
                handleConfirm(sender);
                break;

            case "stats":
                handleStats(sender);
                break;

            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        messages.send(sender, "admin.help.header");
        messages.send(sender, "admin.help.reload");
        messages.send(sender, "admin.help.unregister");
        messages.send(sender, "admin.help.resetpassword");
        messages.send(sender, "admin.help.stats");
        messages.send(sender, "admin.help.footer");
    }

    private void handleUnregister(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "usage.unregister");
            return;
        }

        String targetName = args[1];

        // Si es consola, ejecutar directamente (asumimos que sabe lo que hace)
        if (!(sender instanceof Player)) {
            executeUnregister(sender, targetName);
            return;
        }

        Player admin = (Player) sender;

        // Crear contexto de confirmación
        ConfirmationContext context = new ConfirmationContext(targetName, () -> executeUnregister(sender, targetName));
        pendingConfirmations.put(admin.getUniqueId(), context);

        // Enviar mensaje de confirmación
        messages.send(sender, "admin.unregister.confirm",
                MessageManager.placeholder().add("player", targetName).build());
        messages.send(sender, "admin.unregister.confirm_instruction");

        // Programar limpieza (timeout 30s)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pendingConfirmations.containsKey(admin.getUniqueId()) &&
                    pendingConfirmations.get(admin.getUniqueId()) == context) {
                pendingConfirmations.remove(admin.getUniqueId());
                messages.send(sender, "admin.unregister.timeout");
            }
        }, 30 * 20L);
    }

    private void handleConfirm(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Consola no necesita confirmación.");
            return;
        }

        Player admin = (Player) sender;

        if (!pendingConfirmations.containsKey(admin.getUniqueId())) {
            sender.sendMessage("§cNo tienes ninguna confirmación pendiente.");
            return;
        }

        // Ejecutar acción
        ConfirmationContext context = pendingConfirmations.remove(admin.getUniqueId());
        context.action.run();
    }

    private void executeUnregister(CommandSender sender, String targetName) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // CORREGIDO: Usar getUserByUsername en lugar de getUserByName
                Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO().getUserByUsername(targetName);
                if (userOpt.isEmpty()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                            messages.getMessage("error.user_not_registered").replace("{player}", targetName)));
                    return;
                }

                User user = userOpt.get();
                plugin.getDatabaseManager().getUserDAO().deleteUser(user.getUuid());

                // Invalidate session if exists
                plugin.getSessionManager().invalidateSession(user.getUuid());

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    messages.send(sender, "admin.unregister.success",
                            MessageManager.placeholder()
                                    .add("player", targetName)
                                    .build());
                });

            } catch (SQLException e) {
                e.printStackTrace();
                plugin.getServer().getScheduler().runTask(plugin, () -> messages.send(sender, "error.database_error"));
            }
        });
    }

    private void handleStats(CommandSender sender) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, Long> stats = plugin.getDatabaseManager().getUserDAO().getStatistics();

                long total = stats.getOrDefault("total_users", 0L);
                long premium = stats.getOrDefault("premium_users", 0L);
                long cracked = stats.getOrDefault("cracked_users", 0L);
                long sessions = stats.getOrDefault("active_sessions", 0L);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    messages.send(sender, "admin.stats.header");

                    messages.send(sender, "admin.stats.version",
                            MessageManager.placeholder()
                                    .add("version", plugin.getDescription().getVersion())
                                    .build());

                    messages.send(sender, "admin.stats.total_users",
                            MessageManager.placeholder().add("total", total).build());

                    messages.send(sender, "admin.stats.premium_users",
                            MessageManager.placeholder().add("premium", premium).build());

                    messages.send(sender, "admin.stats.cracked_users",
                            MessageManager.placeholder().add("cracked", cracked).build());

                    messages.send(sender, "admin.stats.active_sessions",
                            MessageManager.placeholder().add("sessions", sessions).build());

                    messages.send(sender, "admin.stats.footer");
                });

            } catch (SQLException e) {
                e.printStackTrace();
                plugin.getServer().getScheduler().runTask(plugin, () -> messages.send(sender, "error.database_error"));
            }
        });
    }

    /**
     * Clase auxiliar para almacenar el contexto de una confirmación.
     */
    private static class ConfirmationContext {
        final String targetName; // Solo para referencia si se necesitara
        final Runnable action;

        ConfirmationContext(String targetName, Runnable action) {
            this.targetName = targetName;
            this.action = action;
        }
    }
}
