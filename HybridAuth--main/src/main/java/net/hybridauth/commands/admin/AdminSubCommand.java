package net.hybridauth.commands.admin;

import org.bukkit.command.CommandSender;

public interface AdminSubCommand {
    /**
     * Executes the subcommand.
     * 
     * @param sender The sender of the command
     * @param args   The arguments passed to the subcommand (args[0] is the
     *               subcommand name)
     */
    void execute(CommandSender sender, String[] args);

    /**
     * Returns the permission required to run this subcommand.
     * 
     * @return Permission string or null if no extra permission is needed beyond
     *         admin
     */
    default String getPermission() {
        return "hybridauth.admin";
    }
}
