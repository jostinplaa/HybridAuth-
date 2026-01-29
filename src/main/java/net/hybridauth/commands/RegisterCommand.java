package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.auth.AuthStateManager;
import net.hybridauth.core.auth.AuthStateManager.AuthState;
import net.hybridauth.data.model.User;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.UUID;

public class RegisterCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;

    public RegisterCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cSolo jugadores pueden registrarse.");
            return true;
        }

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        // 1. Check if already logged in
        if (plugin.getAuthStateManager().isAuthenticated(player)) {
            player.sendMessage("§cYa estás autenticado.");
            return true;
        }

        // 2. Check if already registered (DB check)
        if (plugin.getDatabaseManager().getUserDAO().getUserByUUID(uuid).isPresent()) {
            player.sendMessage("§cYa estás registrado. Usa /login <password>");
            return true;
        }

        // 3. Check arguments
        if (args.length < 2) {
            player.sendMessage("§cUso: /register <password> <confirmPassword>");
            return true;
        }

        String password = args[0];
        String confirm = args[1];

        // 4. Validate passwords match
        if (!password.equals(confirm)) {
            player.sendMessage("§cLas contraseñas no coinciden.");
            return true;
        }

        // 5. Create User
        // TODO: Validate password strength (Phase 5 refinement)

        String hash = plugin.getPasswordService().hashPassword(password);

        User newUser = new User(uuid, player.getName(), User.AuthType.CRACKED);
        newUser.setPasswordHash(hash);
        newUser.setLastIp(player.getAddress().getAddress().getHostAddress());
        newUser.setStatus("ACTIVE");

        // 6. Save to DB asynchronously
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getDatabaseManager().getUserDAO().createUser(newUser);

                // 7. Update state on main thread
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getAuthStateManager().setAuthState(player, AuthState.AUTHENTICATED);
                    player.sendMessage("§a¡Registrado correctamente! Has iniciado sesión.");
                    player.sendMessage("§7Disfruta tu estancia en el servidor.");
                });

            } catch (SQLException e) {
                e.printStackTrace();
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> player.sendMessage("§cError en la base de datos al registrar. Contacta un admin."));
            }
        });

        return true;
    }
}
