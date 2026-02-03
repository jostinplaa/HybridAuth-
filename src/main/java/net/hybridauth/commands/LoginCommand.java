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

        // 3. Verificar si ya está autenticado
        if (plugin.getAuthStateManager().isAuthenticated(player)) {
            messages.send(player, "error.already_authenticated");
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

        // 4. Obtener datos
        String password = args[0];
        String ip = player.getAddress().getAddress().getHostAddress();

        // 5. Verificar rate limit
        if (!plugin.getRateLimitService().checkLimit(ip)) {
            long remainingSeconds = plugin.getRateLimitService().getSecondsRemaining(ip);

            // KICKEAR AL JUGADOR con mensaje personalizado
            String kickMessage = messages.getMessage("rate_limit.kick_message",
                    MessageManager.placeholder().add("remaining", remainingSeconds).build());
            player.kickPlayer(kickMessage);

            // Log del evento
            plugin.getLogger().warning("[Rate Limit] " + player.getName() + " kicked - IP blocked for " + remainingSeconds + "s");

            return true;
        }

        // 6. Mostrar feedback de procesamiento
        messages.sendActionBar(player, "actionbar.authenticating");

        // 7. Verificar credenciales asíncronamente
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            handleLoginAsync(player, password, ip);
        });

        return true;
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

        // 2. Verificar contraseña
        boolean isValidPassword = plugin.getPasswordService().verifyPassword(password, user.getPasswordHash());

        if (isValidPassword) {
            handleLoginSuccess(player, user, ip);
        } else {
            handleLoginFailure(player, user, ip);
        }
    }

    private void handleLoginSuccess(Player player, User user, String ip) {
        // 1. Resetear rate limit
        plugin.getRateLimitService().resetLimit(ip);

        // 2. Crear sesión persistente
        plugin.getSessionManager().createSession(user.getUuid(), ip);

        // 3. Loggear evento de seguridad
        plugin.getSecurityLogger().log(
                net.hybridauth.security.SecurityLogger.EventType.LOGIN_SUCCESS,
                user.getUsername(),
                user.getUuid(),
                ip,
                "AuthType: " + user.getAuthType());

        // 4. Actualizar información del usuario
        user.setLastIp(ip);
        user.setLastLoginDate(new java.sql.Timestamp(System.currentTimeMillis()));

        try {
            plugin.getDatabaseManager().getUserDAO().updateUser(user);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update user last login data for " + user.getUsername());
            e.printStackTrace();
        }

        // 5. Actualizar estado y UI en el thread principal
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // Cambiar estado de autenticación
            plugin.getAuthStateManager().setAuthState(player, AuthState.AUTHENTICATED);

            // Remover efectos de restricción
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS);
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOW);

            // Enviar mensajes de éxito
            messages.send(player, "success.logged_in");
            messages.send(player, "success.welcome_back",
                    MessageManager.placeholder()
                            .add("player", player.getName())
                            .build());

            // Enviar título de bienvenida
            messages.sendTitle(player, "titles.login_success.title", "titles.login_success.subtitle");

            // Action bar de éxito
            messages.sendActionBar(player, "actionbar.success");

            // Reproducir sonido de éxito
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

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

        // 4. Si llegó al límite, KICKEAR
        if (remainingAttempts == 0) {
            long lockoutSeconds = plugin.getRateLimitService().getSecondsRemaining(ip);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                String kickMessage = messages.getMessage("rate_limit.kick_message",
                        MessageManager.placeholder().add("remaining", lockoutSeconds).build());
                player.kickPlayer(kickMessage);

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
            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f);
        });

        // 6. Log en consola
        plugin.getLogger().warning(player.getName() + " failed login attempt from " + ip +
                " (" + remainingAttempts + " attempts remaining)");
    }
}
