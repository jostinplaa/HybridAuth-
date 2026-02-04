package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Comando /hybrid email para gestión de emails
 * Subcomandos: set, verify, remove
 * 
 * @version 1.5.0
 */
public class EmailCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final MessageManager messages;
    private final Map<String, Long> cooldowns = new HashMap<>();

    public EmailCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cSolo jugadores pueden usar este comando");
            return true;
        }

        Player player = (Player) sender;

        if (!plugin.getAuthStateManager().isAuthenticated(player)) {
            messages.send(player, "error.not_logged_in");
            return true;
        }

        if (args.length == 0) {
            showHelp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "set":
                if (args.length < 2) {
                    player.sendMessage("§cUso: /hybrid email set <email>");
                    return true;
                }
                handleSetEmail(player, args[1]);
                break;

            case "verify":
                if (args.length < 2) {
                    player.sendMessage("§cUso: /hybrid email verify <codigo>");
                    return true;
                }
                handleVerifyEmail(player, args[1]);
                break;

            case "remove":
                handleRemoveEmail(player);
                break;

            default:
                showHelp(player);
                break;
        }

        return true;
    }

    private void showHelp(Player player) {
        player.sendMessage("§6=== HybridAuth Email ===");
        player.sendMessage("§f/hybrid email set <email> §7- Vincular email");
        player.sendMessage("§f/hybrid email verify <codigo> §7- Verificar email");
        player.sendMessage("§f/hybrid email remove §7- Desvincular email");
    }

    private void handleSetEmail(Player player, String email) {
        // Validar formato de email
        if (!email.matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
            player.sendMessage("§c✗ Email inválido");
            return;
        }

        // Cooldown de 1 minuto
        String key = player.getName();
        if (cooldowns.containsKey(key)) {
            long timeLeft = (cooldowns.get(key) - System.currentTimeMillis()) / 1000;
            if (timeLeft > 0) {
                player.sendMessage("§c✗ Espera " + timeLeft + " segundos");
                return;
            }
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                // Verificar si ya tiene email
                String checkSql = "SELECT email FROM hybrid_emails WHERE username = ?";
                try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
                    stmt.setString(1, player.getName());
                    ResultSet rs = stmt.executeQuery();

                    if (rs.next()) {
                        String existingEmail = rs.getString("email");
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§c✗ Ya tienes un email vinculado: " + existingEmail);
                            player.sendMessage("§7Usa §f/hybrid email remove §7primero");
                        });
                        return;
                    }
                }

                // Generar código de verificación
                String code = plugin.getEmailService().generateCode();
                long expiresAt = System.currentTimeMillis() + (15 * 60 * 1000); // 15 minutos

                // Guardar en BD
                String insertSql = "INSERT OR REPLACE INTO hybrid_emails (username, email, verified, verification_code, code_expires_at, created_at) VALUES (?, ?, 0, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, player.getName());
                    stmt.setString(2, email);
                    stmt.setString(3, code);
                    stmt.setLong(4, expiresAt);
                    stmt.setLong(5, System.currentTimeMillis());
                    stmt.executeUpdate();
                }

                // Enviar email
                boolean sent = plugin.getEmailService().sendVerificationCode(email, player.getName(), code);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (sent) {
                        player.sendMessage("§a✓ Código de verificación enviado a " + email);
                        player.sendMessage("§7Revisa tu email y usa: §f/hybrid email verify <codigo>");
                        cooldowns.put(key, System.currentTimeMillis() + 60000);
                    } else {
                        player.sendMessage("§c✗ Error al enviar el email. Contacta un administrador.");
                    }
                });

            } catch (SQLException e) {
                plugin.getLogger().severe("Error setting email: " + e.getMessage());
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in EmailCommand", );
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§c✗ Error de base de datos");
                });
            }
        });
    }

    private void handleVerifyEmail(Player player, String code) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                String sql = "SELECT email, verification_code, code_expires_at FROM hybrid_emails WHERE username = ? AND verified = 0";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, player.getName());
                    ResultSet rs = stmt.executeQuery();

                    if (!rs.next()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§c✗ No tienes email pendiente de verificación");
                        });
                        return;
                    }

                    String storedCode = rs.getString("verification_code");
                    long expiresAt = rs.getLong("code_expires_at");

                    // Verificar expiración
                    if (System.currentTimeMillis() > expiresAt) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§c✗ Código expirado. Solicita uno nuevo con /hybrid email set");
                        });
                        return;
                    }

                    // Verificar código
                    if (!code.equals(storedCode)) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            player.sendMessage("§c✗ Código incorrecto");
                        });
                        return;
                    }

                    // Marcar como verificado
                    String updateSql = "UPDATE hybrid_emails SET verified = 1, verification_code = NULL, code_expires_at = NULL WHERE username = ?";
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setString(1, player.getName());
                        updateStmt.executeUpdate();
                    }

                    String email = rs.getString("email");
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        player.sendMessage("§a✓ Email verificado correctamente: " + email);
                        player.sendMessage("§7Ahora puedes recuperar tu cuenta con §f/hybrid recover");
                    });
                }

            } catch (SQLException e) {
                plugin.getLogger().severe("Error verifying email: " + e.getMessage());
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in EmailCommand", );
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§c✗ Error de base de datos");
                });
            }
        });
    }

    private void handleRemoveEmail(Player player) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                String sql = "DELETE FROM hybrid_emails WHERE username = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, player.getName());
                    int deleted = stmt.executeUpdate();

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (deleted > 0) {
                            player.sendMessage("§a✓ Email desvinculado correctamente");
                        } else {
                            player.sendMessage("§c✗ No tienes email vinculado");
                        }
                    });
                }

            } catch (SQLException e) {
                plugin.getLogger().severe("Error removing email: " + e.getMessage());
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in EmailCommand", );
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§c✗ Error de base de datos");
                });
            }
        });
    }
}
