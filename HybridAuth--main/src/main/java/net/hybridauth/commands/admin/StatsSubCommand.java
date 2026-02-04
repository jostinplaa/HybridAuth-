package net.hybridauth.commands.admin;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
import org.bukkit.command.CommandSender;

import java.sql.SQLException;
import java.util.Map;

public class StatsSubCommand implements AdminSubCommand {

    private final HybridAuthPlugin plugin;

    public StatsSubCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, Long> stats = plugin.getDatabaseManager().getUserDAO().getStatistics();

                long total = stats.getOrDefault("total_users", 0L);
                long premium = stats.getOrDefault("premium_users", 0L);
                long cracked = stats.getOrDefault("cracked_users", 0L);
                long sessions = stats.getOrDefault("active_sessions", 0L);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    MessageManager messages = plugin.getMessageManager();
                    messages.send(sender, "admin.stats.header");

                    messages.send(sender, "admin.stats.version",
                            MessageManager.placeholder()
                                    .add("version", plugin.getDescription().getVersion())
                                    .build());

                    messages.send(sender, "admin.stats.total_users",
                            MessageManager.placeholder().add("total", total).build());

                    messages.send(sender, "admin.stats.premium_users",
                            MessageManager.placeholder().add("premium", premium).build());

                    messages.send(sender, "admin.stats.cracked_users",
                            MessageManager.placeholder().add("cracked", cracked).build());

                    messages.send(sender, "admin.stats.active_sessions",
                            MessageManager.placeholder().add("sessions", sessions).build());

                    messages.send(sender, "admin.stats.footer");
                });

            } catch (SQLException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in StatsSubCommand", );
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().send(sender, "error.database_error"));
            }
        });
    }
}
