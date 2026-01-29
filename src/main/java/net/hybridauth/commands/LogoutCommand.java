package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.auth.AuthStateManager.AuthState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LogoutCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;

    public LogoutCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cSolo jugadores.");
            return true;
        }

        Player player = (Player) sender;

        // De-authenticate
        plugin.getSessionManager().invalidateSession(player.getUniqueId());
        plugin.getAuthStateManager().setAuthState(player, AuthState.UNAUTHENTICATED);
        player.sendMessage("§aHas cerrado sesión correctamente.");
        player.sendMessage("§7Usa /login para entrar de nuevo.");

        return true;
    }
}
