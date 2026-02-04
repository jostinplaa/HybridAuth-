package net.hybridauth.commands.admin;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.command.CommandSender;

public class ReloadSubCommand implements AdminSubCommand {

    private final HybridAuthPlugin plugin;

    public ReloadSubCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        // 1. Recargar config.yml del disco
        plugin.reloadConfig();

        // 2. Recargar messages.yml
        plugin.getMessageManager().reload();

        // v1.7.0 Reload AlertManager
        plugin.getAlertManager().reload();

        // 3. CRTICO: Reinicializar servicios
        plugin.reinitializeServices();

        plugin.getMessageManager().send(sender, "admin.reload.success");
    }
}

