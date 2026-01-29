package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.auth.AuthStateManager.AuthState;
import net.hybridauth.data.model.User;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Optional;

public class LoginCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;

    public LoginCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cSolo jugadores pueden loguearse.");
            return true;
        }

        Player player = (Player) sender;

        // 1. Check if already logged in
        if (plugin.getAuthStateManager().isAuthenticated(player)) {
            player.sendMessage("§cYa estás autenticado.");
            return true;
        }

        // 2. Check arguments
        if (args.length < 1) {
            player.sendMessage("§cUso: /login <password>");
            return true;
        }

        String password = args[0];
        String ip = player.getAddress().getAddress().getHostAddress();

        // 3. Check rate limit
        if (!plugin.getRateLimitService().checkLimit(ip)) {
            player.sendMessage("§cHas excedido el límite de intentos. Espera un momento.");
            return true;
        }

        // 4. Verify credentials asynchronously
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO().getUserByUUID(player.getUniqueId());

            if (userOpt.isEmpty()) {
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.sendMessage("§cNo estás registrado. Usa /register <pass> <pass>"));
                return;
            }

            User user = userOpt.get();
            boolean valid = plugin.getPasswordService().verifyPassword(password, user.getPasswordHash());

            if (valid) {
                // Success
                plugin.getRateLimitService().resetLimit(ip);

                // Update Last login/IP info
                user.setLastIp(ip);
                user.setLastLoginDate(new java.sql.Timestamp(System.currentTimeMillis()));
                try {
                    plugin.getDatabaseManager().getUserDAO().updateUser(user);
                } catch (SQLException e) {
                    e.printStackTrace(); // Non-fatal, just logging
                }

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getAuthStateManager().setAuthState(player, AuthState.AUTHENTICATED);
                    player.sendMessage("§a¡Inicio de sesión exitoso!");
                });
            } else {
                // Failure
                plugin.getRateLimitService().incrementAttempt(ip);
                plugin.getServer().getScheduler().runTask(plugin, () -> player.sendMessage("§cContraseña incorrecta."));
            }
        });

        return true;
    }
}
