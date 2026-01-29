package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.data.model.User;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Optional;

public class ChangePasswordCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;

    public ChangePasswordCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cSolo jugadores.");
            return true;
        }

        Player player = (Player) sender;

        // Check if logged in
        if (!plugin.getAuthStateManager().isAuthenticated(player)) {
            player.sendMessage("§cDebes iniciar sesión primero.");
            return true;
        }

        if (args.length < 2) {
            player.sendMessage("§cUso: /changepassword <antigua> <nueva>");
            return true;
        }

        String oldPass = args[0];
        String newPass = args[1];

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO().getUserByUUID(player.getUniqueId());

            if (userOpt.isPresent()) {
                User user = userOpt.get();

                // Verify old password
                if (plugin.getPasswordService().verifyPassword(oldPass, user.getPasswordHash())) {
                    // Update password
                    String newHash = plugin.getPasswordService().hashPassword(newPass);
                    user.setPasswordHash(newHash);

                    try {
                        plugin.getDatabaseManager().getUserDAO().updateUser(user);
                        sender.sendMessage("§aContraseña actualizada correctamente.");
                    } catch (SQLException e) {
                        e.printStackTrace();
                        sender.sendMessage("§cError al guardar la nueva contraseña.");
                    }
                } else {
                    sender.sendMessage("§cLa contraseña antigua es incorrecta.");
                }
            }
        });

        return true;
    }
}
