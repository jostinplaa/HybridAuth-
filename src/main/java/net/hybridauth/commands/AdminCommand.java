package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.data.model.User;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.sql.SQLException;
import java.util.Optional;

public class AdminCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;

    public AdminCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hybridauth.admin")) {
            sender.sendMessage("§cNo tienes permiso para usar este comando.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                plugin.reloadConfig();
                // TODO: Reload messages.yml as well
                sender.sendMessage("§a[HybridAuth] Configuración recargada.");
                break;

            case "unregister":
                if (args.length < 2) {
                    sender.sendMessage("§cUso: /hybridauth unregister <jugador>");
                    return true;
                }
                handleUnregister(sender, args[1]);
                break;

            case "resetpassword":
                if (args.length < 3) {
                    sender.sendMessage("§cUso: /hybridauth resetpassword <jugador> <nueva_pass>");
                    return true;
                }
                handleResetPassword(sender, args[1], args[2]);
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

    private void handleUnregister(CommandSender sender, String targetName) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO().getUserByUsername(targetName);

            if (userOpt.isEmpty()) {
                sender.sendMessage("§cEl usuario " + targetName + " no está registrado.");
                return;
            }

            try {
                plugin.getDatabaseManager().getUserDAO().deleteUser(userOpt.get().getUuid());
                plugin.getRateLimitService().resetLimit(userOpt.get().getLastIp()); // Bonus: reset their limit
                sender.sendMessage("§aEl usuario " + targetName + " ha sido desregistrado correctamente.");
            } catch (SQLException e) {
                e.printStackTrace();
                sender.sendMessage("§cError al eliminar usuario de la base de datos.");
            }
        });
    }

    private void handleResetPassword(CommandSender sender, String targetName, String newPass) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO().getUserByUsername(targetName);
            if (userOpt.isEmpty()) {
                sender.sendMessage("§cUsuario no encontrado.");
                return;
            }
            User user = userOpt.get();
            String hash = plugin.getPasswordService().hashPassword(newPass);
            user.setPasswordHash(hash);
            try {
                plugin.getDatabaseManager().getUserDAO().updateUser(user);
                sender.sendMessage("§aContraseña restablecida para " + targetName);
            } catch (SQLException e) {
                sender.sendMessage("§cError en base de datos.");
            }
        });
    }

    private void handleStats(CommandSender sender) {
        // Simple stats implementation (placeholder for full DB stats from spec)
        sender.sendMessage("§8§m---------------------§r §bHybridAuth Stats §8§m---------------------");
        sender.sendMessage("§eVersión: §7" + plugin.getDescription().getVersion());
        try {
            sender.sendMessage("§eBase de Datos: §7"
                    + (plugin.getDatabaseManager().getConnection() != null ? "Conectada" : "Desconectada"));
        } catch (SQLException e) {
            sender.sendMessage("§eBase de Datos: §cError");
        }
        // In a real scenario we would count users in DB
        sender.sendMessage("§eAutenticación: §7Híbrida (Mojang API + BCrypt)");
        sender.sendMessage("§8§m--------------------------------------------------------");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§8§m----------------§r §bHybridAuth Admin §8§m----------------");
        sender.sendMessage("§7/hybridauth reload §8- §fRecargar configuración");
        sender.sendMessage("§7/hybridauth unregister <player> §8- §fDesregistrar usuario");
        sender.sendMessage("§8§m--------------------------------------------------");
    }
}
