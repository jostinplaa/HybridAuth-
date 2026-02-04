package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Comando /recover para recuperar cuentas via email
 * Uso: /recover <username> [codigo] [nueva_password]
 * 
 * @version 1.5.0
 */
public class RecoverCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final MessageManager messages;
    private final Map<String, Long> cooldowns = new HashMap<>();

    public RecoverCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cUso: /recover <username> [codigo] [nueva_password]");
            return true;
        }

        String username = args[0];

        // Step 1: Solo username - enviar código
        if (args.length == 1) {
            handleRequestCode(sender, username);
            return true;
        }

        // Step 2: username + codigo + password - cambiar password
        if (args.length >= 3) {
            String code = args[1];
            String newPassword = args[2];
            handleResetPassword(sender, username, code, newPassword);
            return true;
        }

        sender.sendMessage("§cUso: /recover <username> [codigo] [nueva_password]");
        return true;
    }

    private void handleRequestCode(CommandSender sender, String username) {
        // Cooldown de 5 minutos
        String key = username.toLowerCase();
        if (cooldowns.containsKey(key)) {
            long timeLeft = (cooldowns.get(key) - System.currentTimeMillis()) / 1000 / 60;
            if (timeLeft > 0) {
                sender.sendMessage("§c✗ Espera " + timeLeft + " minutos antes de solicitar otro código");
                return;
            }
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                // Verificar que la cuenta exista y tenga email VERIFICADO
                String emailSql = "SELECT email FROM hybrid_emails WHERE username = ? AND verified = 1";
                String email = null;

                try (PreparedStatement stmt = conn.prepareStatement(emailSql)) {
                    stmt.setString(1, username);
                    ResultSet rs = stmt.executeQuery();

                    if (!rs.next()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            sender.sendMessage("§c✗ Esta cuenta no tiene email vinculado o verificado");
                        });
                        return;
                    }

                    email = rs.getString("email");
                }

                // Generar código de recuperación
                String code = plugin.getEmailService().generateCode();
                long expiresAt = System.currentTimeMillis() + (10 * 60 * 1000); // 10 minutos

                // Guardar código en BD (reemplaza si existe)
                String insertSql = "INSERT OR REPLACE INTO hybrid_recovery_codes (username, recovery_code, expires_at, attempts, created_at) VALUES (?, ?, ?, 0, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, username);
                    stmt.setString(2, code);
                    stmt.setLong(3, expiresAt);
                    stmt.setLong(4, System.currentTimeMillis());
                    stmt.executeUpdate();
                }

                // Enviar email
                String finalEmail = email;
                boolean sent = plugin.getEmailService().sendRecoveryCode(finalEmail, username, code);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (sent) {
                        sender.sendMessage("§a✓ Código de recuperación enviado a " + maskEmail(finalEmail));
                        sender.sendMessage("§7Revisa tu email y usa:");
                        sender.sendMessage("§f/recover " + username + " <codigo> <nueva_password>");
                        cooldowns.put(key, System.currentTimeMillis() + (5 * 60 * 1000));
                    } else {
                        sender.sendMessage("§c✗ Error al enviar el email. Contacta un administrador.");
                    }
                });

            } catch (SQLException e) {
                plugin.getLogger().severe("Error requesting recovery code: " + e.getMessage());
                e.printStackTrace();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§c✗ Error de base de datos");
                });
            }
        });
    }

    private void handleResetPassword(CommandSender sender, String username, String code, String newPassword) {
        // Validar password
        int minLength = plugin.getConfig().getInt("security.password.min-length", 6);
        if (newPassword.length() < minLength) {
            sender.sendMessage("§c✗ La contraseña debe tener al menos " + minLength + " caracteres");
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                // Verificar código
                String checkSql = "SELECT recovery_code, expires_at, attempts FROM hybrid_recovery_codes WHERE username = ?";

                try (PreparedStatement stmt = conn.prepareStatement(checkSql)) {
                    stmt.setString(1, username);
                    ResultSet rs = stmt.executeQuery();

                    if (!rs.next()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            sender.sendMessage("§c✗ No hay código de recuperación activo para esta cuenta");
                            sender.sendMessage("§7Solicita uno con: §f/recover " + username);
                        });
                        return;
                    }

                    String storedCode = rs.getString("recovery_code");
                    long expiresAt = rs.getLong("expires_at");
                    int attempts = rs.getInt("attempts");

                    // Verificar expiración
                    if (System.currentTimeMillis() > expiresAt) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            sender.sendMessage("§c✗ Código expirado. Solicita uno nuevo");
                        });
                        // Limpiar código expirado
                        String deleteSql = "DELETE FROM hybrid_recovery_codes WHERE username = ?";
                        try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                            deleteStmt.setString(1, username);
                            deleteStmt.executeUpdate();
                        }
                        return;
                    }

                    // Verificar máximo de intentos
                    if (attempts >= 3) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            sender.sendMessage("§c✗ Demasiados intentos. Solicita un nuevo código");
                        });
                        return;
                    }

                    // Verificar código
                    if (!code.equals(storedCode)) {
                        // Incrementar intentos
                        String updateSql = "UPDATE hybrid_recovery_codes SET attempts = attempts + 1 WHERE username = ?";
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                            updateStmt.setString(1, username);
                            updateStmt.executeUpdate();
                        }

                        int attemptsLeft = 3 - (attempts + 1);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            sender.sendMessage("§c✗ Código incorrecto. Intentos restantes: " + attemptsLeft);
                        });
                        return;
                    }

                    // Código correcto - cambiar contraseña
                    String hashedPassword = plugin.getPasswordService().hashPassword(newPassword);
                    String updatePasswordSql = "UPDATE hybrid_users SET password = ? WHERE username = ?";

                    try (PreparedStatement updateStmt = conn.prepareStatement(updatePasswordSql)) {
                        updateStmt.setString(1, hashedPassword);
                        updateStmt.setString(2, username);
                        int updated = updateStmt.executeUpdate();

                        if (updated == 0) {
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                sender.sendMessage("§c✗ Cuenta no encontrada");
                            });
                            return;
                        }
                    }

                    // Eliminar código usado
                    String deleteSql = "DELETE FROM hybrid_recovery_codes WHERE username = ?";
                    try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                        deleteStmt.setString(1, username);
                        deleteStmt.executeUpdate();
                    }

                    // Log de seguridad
                    plugin.getSecurityLogger().log(
                            net.hybridauth.security.SecurityLogger.EventType.PASSWORD_CHANGE,
                            username,
                            null,
                            sender instanceof org.bukkit.entity.Player
                                    ? ((org.bukkit.entity.Player) sender).getAddress().getAddress().getHostAddress()
                                    : "CONSOLE",
                            "Password changed via email recovery");

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        sender.sendMessage("§a✓ Contraseña cambiada exitosamente");
                        sender.sendMessage("§7Ahora puedes entrar con: §f/login " + username + " <nueva_password>");
                        cooldowns.remove(username.toLowerCase());
                    });
                }

            } catch (SQLException e) {
                plugin.getLogger().severe("Error resetting password: " + e.getMessage());
                e.printStackTrace();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§c✗ Error de base de datos");
                });
            }
        });
    }

    /**
     * Enmascara el email para privacidad: test@gmail.com -> t***@gmail.com
     */
    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1)
            return email;

        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}
