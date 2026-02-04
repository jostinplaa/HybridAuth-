package net.hybridauth.commands.admin;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
import net.hybridauth.data.model.User;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class UnregisterSubCommand implements AdminSubCommand {

    private final HybridAuthPlugin plugin;
    // Store pending confirmations locally
    private final Map<UUID, Runnable> pendingConfirmations = new HashMap<>();

    public UnregisterSubCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        // Check if it's the confirmation command
        if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
            handleConfirm(sender);
            return;
        }

        if (args.length < 2) {
            plugin.getMessageManager().send(sender, "usage.unregister");
            return;
        }

        String targetName = args[1];

        // Si es consola, ejecutar directamente (asumimos que sabe lo que hace)
        if (!(sender instanceof Player)) {
            executeUnregister(sender, targetName);
            return;
        }

        Player admin = (Player) sender;
        Runnable action = () -> executeUnregister(sender, targetName);

        // Crear confirmacin
        pendingConfirmations.put(admin.getUniqueId(), action);

        // Enviar mensaje de confirmacin
        plugin.getMessageManager().send(sender, "admin.unregister.confirm",
                MessageManager.placeholder().add("player", targetName).build());
        plugin.getMessageManager().send(sender, "admin.unregister.confirm_instruction");

        // Programar limpieza (timeout 30s)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pendingConfirmations.containsKey(admin.getUniqueId()) &&
                    pendingConfirmations.get(admin.getUniqueId()) == action) {
                pendingConfirmations.remove(admin.getUniqueId());
                plugin.getMessageManager().send(sender, "admin.unregister.timeout");
            }
        }, 30 * 20L); // 30 seconds
    }

    private void handleConfirm(CommandSender sender) {
        if (!(sender instanceof Player)) {
            plugin.getMessageManager().send(sender, "admin.unregister.console_no_confirmation");
            return;
        }

        Player admin = (Player) sender;

        if (!pendingConfirmations.containsKey(admin.getUniqueId())) {
            plugin.getMessageManager().send(sender, "admin.unregister.no_pending_confirmation");
            return;
        }

        // Ejecutar accin
        Runnable action = pendingConfirmations.remove(admin.getUniqueId());
        action.run();
    }

    private void executeUnregister(CommandSender sender, String targetName) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // CORREGIDO: Usar getUserByUsername
                Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO().getUserByUsername(targetName);
                if (userOpt.isEmpty()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                            plugin.getMessageManager().getMessage("error.user_not_registered").replace("{player}",
                                    targetName)));
                    return;
                }

                User user = userOpt.get();

                // Verificar si es premium
                if (user.isPremium()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getMessageManager().send(sender, "error.premium_cannot_unregister");
                        plugin.getMessageManager().send(sender, "error.premium_cannot_unregister_info");
                    });
                    return;
                }

                plugin.getDatabaseManager().getUserDAO().deleteUser(user.getUuid());

                // Invalidate session if exists
                plugin.getSessionManager().invalidateSession(user.getUuid());

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getMessageManager().send(sender, "admin.unregister.success",
                            MessageManager.placeholder()
                                    .add("player", targetName)
                                    .build());
                });

            } catch (SQLException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in UnregisterSubCommand", e);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().send(sender, "error.database_error"));
            }
        });
    }
}



