package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.auth.AuthStateManager.AuthState;
import net.hybridauth.core.messages.MessageManager;
import net.hybridauth.data.model.User;
import net.hybridauth.util.AccountTypeUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Optional;

public class LoginCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final MessageManager messages;

    public LoginCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 1. Verificar que sea un jugador
        if (!(sender instanceof Player)) {
            messages.send(sender, "error.only_players");
            return true;
        }

        Player player = (Player) sender;

        // 2. Verificar tipo de cuenta (Premium usa auto-login)
        AccountTypeUtil.AccountType accountType = AccountTypeUtil.getAccountType(player);
        if (accountType == AccountTypeUtil.AccountType.PREMIUM) {
            messages.send(player, "error.premium_cannot_login");
            messages.send(player, "error.premium_cannot_login_info");
            return true;
        }

        // 3. Verificar si ya est autenticado
        if (plugin.getAuthStateManager().isAuthenticated(player)) {
            messages.send(player, "error.already_authenticated");
            return true;
        }

        // 3.5. 2FA Code Check
        if (plugin.getAuthStateManager().getAuthState(player) == AuthState.AWAITING_2FA) {
            // If args are provided, try to treat as code?
            // Actually, user is instructed to use /2fa code.
            // But if they type /login <code> we could handle it too.
            // Let's stick to checking if they typed /login again, warn them.
            if (args.length > 0) {
                // Try to see if arg is a code
                try {
                    int code = Integer.parseInt(args[0]);
                    // Check code
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        Optional<User> uOpt = plugin.getDatabaseManager().getUserDAO()
                                .getUserByUUID(player.getUniqueId());
                        if (uOpt.isPresent() && uOpt.get().isTotpEnabled()) {
                            if (plugin.getTwoFactorService().authorize(uOpt.get().getTotpSecret(), code)) {
                                handleLoginSuccess(player, uOpt.get(),
                                        player.getAddress().getAddress().getHostAddress());
                            } else {
                                plugin.getServer().getScheduler().runTask(plugin,
                                        () -> player.sendMessage("cInvalid 2FA code."));
                            }
                        }
                    });
                    return true;
                } catch (NumberFormatException e) {
                    player.sendMessage("eEnter your 2FA code: f/2fa code <123456> e(or f/login <code>e)");
                    return true;
                }
            }
            return true;
        }

        // 3.5. NUEVO: Verificar si tiene captcha pendiente
        if (plugin.getCaptchaService().hasPendingCaptcha(player)) {
            messages.send(player, "captcha.pending");
            return true;
        }

        // 4. Verificar argumentos
        if (args.length < 1) {
            messages.send(player, "usage.login");
            return true;
        }

        // 4. Obtener datos con NULL SAFETY
        String password = args[0];

        java.net.InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            messages.send(player, "error.connection_lost");
            return true;
        }
        String ip = address.getAddress().getHostAddress();

        // 5. Verificar rate limit
        if (!plugin.getRateLimitService().checkLimit(ip)) {
            long remainingSeconds = plugin.getRateLimitService().getSecondsRemaining(ip);

            // KICKEAR AL JUGADOR con mensaje personalizado
            String kickMessage = buildRateLimitKickMessage(remainingSeconds);
            plugin.getFeedbackService().kickPlayer(player, kickMessage);

            // Log del evento
            plugin.getLogger().warning("[Rate Limit] " + player.getName() + " kicked - IP blocked for " +
                    formatTime(remainingSeconds));

            return true;
        }

        // 6. Mostrar feedback de procesamiento
        messages.sendActionBar(player, "actionbar.authenticating");

        // 7. Verificar credenciales asncronamente
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            handleLoginAsync(player, password, ip);
        });

        return true;
    }

    /**
     * Construye el mensaje de kick por rate limiting
     */
    private String buildRateLimitKickMessage(long seconds) {
        String timeFormatted = formatTime(seconds);

        return """
                8m

                clHybridAuth Security
                8m

                7Tu direccin IP est ctemporalmente bloqueada7.

                eRazn: fDemasiados intentos fallidos de autenticacin
                eExpira en: f%s

                7Si crees que esto es un error, contacta
                7a un administrador del servidor.

                8m
                """.formatted(timeFormatted);
    }

    /**
     * Formatea segundos a un string legible (Xm Ys o Xs)
     */
    private String formatTime(long seconds) {
        if (seconds >= 60) {
            long minutes = seconds / 60;
            long remainingSeconds = seconds % 60;

            if (remainingSeconds > 0) {
                return String.format("%d minuto%s %d segundo%s",
                        minutes, minutes != 1 ? "s" : "",
                        remainingSeconds, remainingSeconds != 1 ? "s" : "");
            } else {
                return String.format("%d minuto%s", minutes, minutes != 1 ? "s" : "");
            }
        } else {
            return String.format("%d segundo%s", seconds, seconds != 1 ? "s" : "");
        }
    }

    private void handleLoginAsync(Player player, String password, String ip) {
        // 1. Buscar usuario en la base de datos
        Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO().getUserByUUID(player.getUniqueId());

        if (userOpt.isEmpty()) {
            // Usuario no registrado
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                messages.send(player, "error.not_registered");
                messages.sendActionBar(player, "actionbar.error");
            });
            return;
        }

        User user = userOpt.get();

        // 2. Verificar contrasea
        boolean isValidPassword = plugin.getPasswordService().verifyPassword(password, user.getPasswordHash());

        if (isValidPassword) {

            // 2FA Check
            if (user.isTotpEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getAuthStateManager().setAuthState(player, AuthState.AWAITING_2FA);
                    messages.send(player, "auth.2fa_required"); // Need to add this message
                    player.sendMessage("ePlease enter your 2FA code: f/2fa code <123456>");
                });
                return;
            }

            handleLoginSuccess(player, user, ip);
        } else {
            handleLoginFailure(player, user, ip);
        }
    }

    private void handleLoginSuccess(Player player, User user, String ip) {
        // 1. Resetear rate limit
        plugin.getRateLimitService().resetLimit(ip);

        // 2. Crear sesin persistente
        plugin.getSessionManager().createSession(user.getUuid(), ip);

        // 3. Loggear evento de seguridad
        plugin.getSecurityLogger().log(
                net.hybridauth.security.SecurityLogger.EventType.LOGIN_SUCCESS,
                user.getUsername(),
                user.getUuid(),
                ip,
                "AuthType: " + user.getAuthType());

        // 3.5. Log Admin Login (Alerts)
        if (player.hasPermission("hybridauth.admin") || player.isOp()) {
            plugin.getSecurityLogger().log(
                    net.hybridauth.security.SecurityLogger.EventType.ADMIN_LOGIN,
                    user.getUsername(),
                    user.getUuid(),
                    ip,
                    "Admin Access Granted");
        }

        // 4. Actualizar informacin del usuario
        user.setLastIp(ip);
        user.setLastLoginDate(new java.sql.Timestamp(System.currentTimeMillis()));

        try {
            plugin.getDatabaseManager().getUserDAO().updateUser(user);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update user last login data for " + user.getUsername());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in LoginCommand", e);
        }

        // 5. Actualizar estado y UI en el thread principal
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // Cambiar estado de autenticacin
            plugin.getAuthStateManager().setAuthState(player, AuthState.AUTHENTICATED);

            // Remover efectos de restriccin
            plugin.getFeedbackService().removeRestrictions(player);

            // Enviar mensajes de xito
            messages.send(player, "success.logged_in");
            messages.send(player, "success.welcome_back",
                    MessageManager.placeholder()
                            .add("player", player.getName())
                            .build());

            // Enviar ttulo de bienvenida
            plugin.getFeedbackService().sendTitle(player, "titles.login_success.title", "titles.login_success.subtitle");

            // Action bar de xito
            messages.sendActionBar(player, "actionbar.success");

            // Reproducir sonido de xito
            plugin.getFeedbackService().playSoundSuccess(player);

            // Log en consola
            plugin.getLogger().info(player.getName() + " logged in successfully from " + ip);
        });
    }

    private void handleLoginFailure(Player player, User user, String ip) {
        // 1. Incrementar contador de rate limit
        plugin.getRateLimitService().incrementAttempt(ip);

        // 2. Obtener intentos restantes
        int maxAttempts = plugin.getConfig().getInt("security.rate-limit.max-attempts-per-ip", 5);
        int currentAttempts = plugin.getRateLimitService().getAttempts(ip);
        int remainingAttempts = Math.max(0, maxAttempts - currentAttempts);

        // 3. Loggear evento de seguridad
        plugin.getSecurityLogger().log(
                net.hybridauth.security.SecurityLogger.EventType.LOGIN_FAIL,
                user.getUsername(),
                user.getUuid(),
                ip,
                "Wrong Password - Attempt " + currentAttempts + "/" + maxAttempts);

        // 3.5. NUEVO: Si tiene 3+ intentos, requerir CAPTCHA
        if (currentAttempts >= 3) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getCaptchaService().requireCaptcha(
                        player,
                        net.hybridauth.security.captcha.CaptchaService.CaptchaReason.MULTIPLE_FAILED_LOGINS);
            });
            return;
        }

        // 4. Si lleg al lmite, KICKEAR
        if (remainingAttempts == 0) {
            long lockoutSeconds = plugin.getRateLimitService().getSecondsRemaining(ip);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String kickMessage = buildRateLimitKickMessage(lockoutSeconds);
                plugin.getFeedbackService().kickPlayer(player, kickMessage);

                plugin.getLogger().warning("[Rate Limit] " + player.getName() +
                        " kicked after " + maxAttempts + " failed attempts");
            });
            return;
        }

        // 5. Notificar al jugador en el thread principal
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            messages.send(player, "password.incorrect",
                    MessageManager.placeholder()
                            .add("attempts", remainingAttempts)
                            .build());

            // Action bar de error
            messages.sendActionBar(player, "actionbar.error");

            // Reproducir sonido de error
            plugin.getFeedbackService().playSoundError(player);
        });

        // 6. Log en consola
        plugin.getLogger().warning(player.getName() + " failed login attempt from " + ip +
                " (" + remainingAttempts + " attempts remaining)");
    }
}



