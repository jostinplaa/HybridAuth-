package net.hybridauth.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * TabCompleter para los comandos de HybridAuth.
 * Proporciona autocompletado inteligente para comandos y nombres de jugadores.
 * 
 * @version 1.0.0
 */
public class HybridAuthTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        // 1. Comando principal /hybridauth (admin)
        if (command.getName().equalsIgnoreCase("hybridauth")) {
            if (!sender.hasPermission("hybridauth.admin")) {
                return Collections.emptyList();
            }

            if (args.length == 1) {
                // Subcomandos principales (agregado "confirm")
                return StringUtil.copyPartialMatches(args[0],
                        Arrays.asList("reload", "unregister", "resetpassword", "stats", "confirm"),
                        new ArrayList<>());
            } else if (args.length == 2) {
                // Segundo argumento: nombres de jugadores para unregister/resetpassword
                if (args[0].equalsIgnoreCase("unregister") || args[0].equalsIgnoreCase("resetpassword")) {
                    return null; // Return null para que Bukkit complete con nombres de jugadores online
                }
            }
        }

        // 2. Comando /changepassword
        if (command.getName().equalsIgnoreCase("changepassword")) {
            if (args.length == 1) {
                return Collections.singletonList("<contrasea_actual>");
            } else if (args.length == 2) {
                return Collections.singletonList("<contrasea_nueva>");
            } else if (args.length == 3) {
                return Collections.singletonList("<confirmar_nueva>");
            }
        }

        // 3. Comando /login
        if (command.getName().equalsIgnoreCase("login")) {
            if (args.length == 1) {
                return Collections.singletonList("<password>");
            }
        }

        // 4. Comando /register
        if (command.getName().equalsIgnoreCase("register")) {
            if (args.length == 1) {
                return Collections.singletonList("<password>");
            } else if (args.length == 2) {
                return Collections.singletonList("<confirmPassword>");
            }
        }

        return Collections.emptyList();
    }
}
