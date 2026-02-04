package net.hybridauth.commands;

import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.data.model.User;
import net.hybridauth.security.totp.TwoFactorService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TwoFactorCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final TwoFactorService twoFactorService;

    // Almacena secretos temporales: UUID -> Secret
    private final Map<UUID, String> pendingSetup = new HashMap<>();

    public TwoFactorCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.twoFactorService = new TwoFactorService(plugin);
    }

    public TwoFactorService getService() {
        return twoFactorService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        // Check if user is logged in
        if (!plugin.getAuthStateManager().isAuthenticated(player)) {
            player.sendMessage(ChatColor.RED + "You must be logged in to manage 2FA.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setup":
                handleSetup(player);
                break;
            case "confirm":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /2fa confirm <code>");
                    return true;
                }
                handleConfirm(player, args[1]);
                break;
            case "disable":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Usage: /2fa disable <password>");
                    return true;
                }
                handleDisable(player, args[1]);
                break;
            case "code":
                // This is used during login, not management
                player.sendMessage(ChatColor.RED + "Use this command only during login when prompted.");
                break;
            default:
                sendHelp(player);
                break;
        }

        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== User 2FA Management ===");
        player.sendMessage(ChatColor.YELLOW + "/2fa setup" + ChatColor.WHITE + " - Setup 2FA for your account");
        player.sendMessage(ChatColor.YELLOW + "/2fa confirm <code>" + ChatColor.WHITE + " - Confirm setup with code");
        player.sendMessage(ChatColor.YELLOW + "/2fa disable <password>" + ChatColor.WHITE + " - Disable 2FA");
    }

    private void handleSetup(Player player) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = plugin.getDatabaseManager().getUserDAO().getUserByUUID(player.getUniqueId()).orElse(null);

            if (user == null) {
                player.sendMessage(ChatColor.RED + "Error: User not found.");
                return;
            }

            if (user.isTotpEnabled()) {
                player.sendMessage(ChatColor.RED + "2FA is already enabled! Use /2fa disable to remove it.");
                return;
            }

            // Generate secret
            GoogleAuthenticatorKey key = twoFactorService.generateKey();
            String secret = key.getKey();
            pendingSetup.put(player.getUniqueId(), secret);

            // Send instructions
            player.sendMessage(ChatColor.GREEN + "=== 2FA Setup ===");
            player.sendMessage(ChatColor.YELLOW + "1. Download Google Authenticator or Authy.");
            player.sendMessage(ChatColor.YELLOW + "2. Add account manually using this secret key:");
            player.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + secret);
            player.sendMessage(ChatColor.YELLOW + "3. Verify with: /2fa confirm <123456>");
            player.sendMessage(ChatColor.GRAY + "(Or use the QR code link in console if enabled)");
        });
    }

    private void handleConfirm(Player player, String codeStr) {
        String secret = pendingSetup.get(player.getUniqueId());

        if (secret == null) {
            player.sendMessage(ChatColor.RED + "You don't have a pending 2FA setup. Run /2fa setup first.");
            return;
        }

        int code;
        try {
            code = Integer.parseInt(codeStr);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid code. Must be numbers.");
            return;
        }

        if (twoFactorService.authorize(secret, code)) {
            // Success! Save to DB
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    User user = plugin.getDatabaseManager().getUserDAO().getUserByUUID(player.getUniqueId())
                            .orElse(null);
                    if (user != null) {
                        user.setTotpSecret(secret);
                        user.setTotpEnabled(true);
                        plugin.getDatabaseManager().getUserDAO().updateUser(user);

                        pendingSetup.remove(player.getUniqueId());
                        plugin.getSecurityLogger().logInfo("2FA ENABLED for user " + player.getName());

                        player.sendMessage(ChatColor.GREEN + "SUCCESS! 2FA is now enabled.");
                        player.sendMessage(ChatColor.GREEN + "You will need the code next time you login.");
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in TwoFactorCommand", e);
                    player.sendMessage(ChatColor.RED + "Database error enabling 2FA.");
                }
            });
        } else {
            player.sendMessage(ChatColor.RED + "Invalid code. Please try again.");
        }
    }

    private void handleDisable(Player player, String password) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            User user = plugin.getDatabaseManager().getUserDAO().getUserByUUID(player.getUniqueId()).orElse(null);

            if (user == null || !user.isTotpEnabled()) {
                player.sendMessage(ChatColor.RED + "2FA is not enabled.");
                return;
            }

            // Verify password first
            if (!plugin.getPasswordService().verifyPassword(password, user.getPasswordHash())) {
                player.sendMessage(ChatColor.RED + "Incorrect password.");
                return;
            }

            try {
                user.setTotpEnabled(false);
                user.setTotpSecret(null);
                plugin.getDatabaseManager().getUserDAO().updateUser(user);

                plugin.getSecurityLogger().logWarning("2FA DISABLED for user " + player.getName());
                player.sendMessage(ChatColor.GREEN + "2FA has been disabled.");
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in TwoFactorCommand", e);
                player.sendMessage(ChatColor.RED + "Database error disabling 2FA.");
            }
        });
    }
}



