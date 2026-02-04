package net.hybridauth.commands.admin;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.data.model.User;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public class MigrateSubCommand implements AdminSubCommand {

    private final HybridAuthPlugin plugin;

    public MigrateSubCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        // Solo jugadores pueden migrar su propia cuenta
        if (!(sender instanceof Player)) {
            plugin.getMessageManager().send(sender, "error.only_players");
            return;
        }

        if (args.length < 2) {
            plugin.getMessageManager().send(sender, "auth.migration.usage");
            return;
        }

        Player player = (Player) sender;
        String password = args[1];

        // Verificar que sea premium
        if (!net.hybridauth.network.netty.PremiumDetector.isPremium(player.getName())) {
            plugin.getMessageManager().send(sender, "auth.migration.not_premium");
            return;
        }

        UUID premiumUUID = net.hybridauth.network.netty.PremiumDetector.getRealUUID(player.getName());
        if (premiumUUID == null) {
            plugin.getMessageManager().send(sender, "auth.migration.premium_uuid_error");
            return;
        }

        // Procesar migración
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO().getUserByUsername(player.getName());

                if (userOpt.isEmpty()) {
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> plugin.getMessageManager().send(sender, "error.not_registered"));
                    return;
                }

                User user = userOpt.get();

                // Ya es premium
                if (user.isPremium()) {
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> plugin.getMessageManager().send(sender, "auth.migration.already_premium"));
                    return;
                }

                // Verificar contraseña
                if (!plugin.getPasswordService().verifyPassword(password, user.getPasswordHash())) {
                    plugin.getMessageManager().send(sender, "auth.migration.wrong_password");
                    plugin.getSecurityLogger().logWarning("Failed migration attempt for " + player.getName());
                    return;
                }

                // Migrar
                plugin.getDatabaseManager().getUserDAO().upgradeToPremium(player.getName(), premiumUUID).thenRun(() -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        plugin.getMessageManager().send(sender, "auth.migration.success");
                        plugin.getSecurityLogger().logInfo("Account migrated to premium: " + player.getName());
                        // Log event type MIGRATION if we update the enum
                        plugin.getSecurityLogger().log(
                                net.hybridauth.security.SecurityLogger.EventType.MIGRATION,
                                player.getName(),
                                premiumUUID,
                                player.getAddress().getAddress().getHostAddress(),
                                "Migrated from Cracked to Premium");
                    });
                });

            } catch (Exception e) {
                e.printStackTrace();
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().send(sender, "auth.migration.failed"));
            }
        });
    }
}
