package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.commands.admin.AdminSubCommand;
import net.hybridauth.commands.admin.MigrateSubCommand;
import net.hybridauth.commands.admin.ReloadSubCommand;
import net.hybridauth.commands.admin.StatsSubCommand;
import net.hybridauth.commands.admin.UnregisterSubCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.Map;

/**
 * Comando administrativo refactorizado.
 * Utiliza Command Pattern para delegar a subcomandos.
 * 
 * @version 1.2.0
 */
public class AdminCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final Map<String, AdminSubCommand> subCommands = new HashMap<>();

    // Maintain direct reference to Unregister command for confirmation handling if
    // needed,
    // although execution flow handles it via "confirm" string or separate command.
    // In our case, /hybridauth confirm actually routes to UnregisterSubCommand's
    // logic?
    // Wait, original AdminCommand treated "confirm" as a top-level arg.
    // We need to route "confirm" to UnregisterSubCommand to check pending actions.
    private final UnregisterSubCommand unregisterCmd;

    public AdminCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;

        // Inicializar subcomandos
        this.unregisterCmd = new UnregisterSubCommand(plugin);

        registerSubCommand("reload", new ReloadSubCommand(plugin));
        registerSubCommand("stats", new StatsSubCommand(plugin));
        registerSubCommand("migrate", new MigrateSubCommand(plugin));
        registerSubCommand("unregister", unregisterCmd);
        // "confirm" is special, it needs to be handled by whatever command has pending
        // actions.
        // Currently only Unregister has pending actions.
    }

    private void registerSubCommand(String name, AdminSubCommand cmd) {
        subCommands.put(name, cmd);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hybridauth.admin")) {
            plugin.getMessageManager().send(sender, "error.no_permission");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommandName = args[0].toLowerCase();

        // Special handling for "confirm" to route to Unregister (since it holds the
        // map)
        if (subCommandName.equals("confirm")) {
            // Treat as unregister confirm
            unregisterCmd.execute(sender, new String[] { "confirm" });
            return true;
        }

        // Backup command (legacy check, though we could make it a subcommand too)
        if (subCommandName.equals("backup")) {
            new net.hybridauth.commands.BackupCommand(plugin).execute(sender, args);
            return true;
        }

        AdminSubCommand cmd = subCommands.get(subCommandName);
        if (cmd != null) {
            cmd.execute(sender, args);
        } else {
            sendHelp(sender);
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        plugin.getMessageManager().send(sender, "admin.help.header");
        plugin.getMessageManager().send(sender, "admin.help.reload");
        plugin.getMessageManager().send(sender, "admin.help.unregister");
        plugin.getMessageManager().send(sender, "admin.help.resetpassword");
        plugin.getMessageManager().send(sender, "admin.help.stats");
        plugin.getMessageManager().send(sender, "admin.help.footer");
    }
}

