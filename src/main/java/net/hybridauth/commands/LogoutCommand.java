package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.auth.AuthStateManager.AuthState;
import net.hybridauth.core.messages.MessageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Comando para cerrar sesión manualmente.
 * 
 * @version 1.1.0
 */
public class LogoutCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final MessageManager messages;

    public LogoutCommand(HybridAuthPlugin plugin) {
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

        if (!plugin.getAuthStateManager().isAuthenticated(player)) {
            messages.send(player, "error.not_authenticated");
            return true;
        }

        // Change State
        plugin.getAuthStateManager().setAuthState(player, AuthState.UNAUTHENTICATED);

        // Invalidate Session
        plugin.getSessionManager().invalidateSession(player.getUniqueId());

        // Send confirmation
        messages.send(player, "success.logged_out");

        // Opcional: Kickear para forzar re-login real
        // player.kickPlayer("§aLogged out successfully.");

        return true;
    }
}
