package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
import net.hybridauth.data.model.User;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Comando para cambiar la contraseña del usuario.
 * 
 * @version 1.1.0
 */
public class ChangePasswordCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final MessageManager messages;

    public ChangePasswordCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            messages.send(sender, "error.only_players");
            return true;
        }

        Player player = (Player) sender;

        // Check if logged in
        if (!plugin.getAuthStateManager().isAuthenticated(player)) {
            messages.send(player, "error.not_authenticated");
            return true;
        }

        // Check args
        if (args.length < 3) {
            messages.send(player, "usage.changepassword");
            return true;
        }

        String oldPass = args[0];
        String newPass = args[1];
        String confirmPass = args[2];

        // Validate confirmation first
        if (!newPass.equals(confirmPass)) {
            messages.send(player, "password.must_match");
            return true;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO().getUserByUUID(player.getUniqueId());

            if (userOpt.isPresent()) {
                User user = userOpt.get();

                // Verify old password
                if (plugin.getPasswordService().verifyPassword(oldPass, user.getPasswordHash())) {

                    // Validate new password strength
                    var validation = plugin.getPasswordService().validatePassword(newPass, player.getName());
                    if (!validation.valid) {
                        messages.send(player, "password.too_weak");
                        if (validation.errorMessage != null && !validation.errorMessage.isEmpty()) {
                            // Opcional: mostrar error específico del validador
                            player.sendMessage("§c" + validation.errorMessage);
                        }
                        return;
                    }

                    // Update password
                    String newHash = plugin.getPasswordService().hashPassword(newPass);
                    user.setPasswordHash(newHash);

                    try {
                        plugin.getDatabaseManager().getUserDAO().updateUser(user);

                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            messages.send(player, "password.changed_successfully");
                            // Play sound
                            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
                        });

                    } catch (SQLException e) {
                        e.printStackTrace();
                        plugin.getServer().getScheduler().runTask(plugin,
                                () -> messages.send(player, "error.database_error"));
                    }
                } else {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        messages.send(player, "password.incorrect",
                                MessageManager.placeholder().add("attempts", "X").build() // No trackeamos intentos aquí
                                                                                          // por ahora
                        );
                    });
                }
            } else {
                plugin.getServer().getScheduler().runTask(plugin, () -> messages.send(player, "error.not_registered"));
            }
        });

        return true;
    }
}
