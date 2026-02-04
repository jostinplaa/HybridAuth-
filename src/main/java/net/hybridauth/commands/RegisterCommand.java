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
import java.util.UUID;

/**
 * Comando de registro para nuevos jugadores.
 * Permite crear una cuenta en el sistema de autenticacin.
 * 
 * @author TuNombre
 * @version 1.4.0
 */
public class RegisterCommand implements CommandExecutor {

    private final HybridAuthPlugin plugin;
    private final MessageManager messages;

    /**
     * Constructor del comando de registro.
     * 
     * @param plugin Instancia del plugin principal
     */
    public RegisterCommand(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessageManager();
    }

    /**
     * Ejecuta el comando de registro.
     * 
     * @param sender  Quien ejecuta el comando
     * @param command El comando ejecutado
     * @param label   El alias usado
     * @param args    Argumentos del comando
     * @return true si el comando se ejecut correctamente
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 1. Verificar que sea un jugador
        if (!(sender instanceof Player)) {
            messages.send(sender, "error.only_players");
            return true;
        }

        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        // 2. Verificar tipo de cuenta (Premium no puede registrarse)
        AccountTypeUtil.AccountType accountType = AccountTypeUtil.getAccountType(player);
        if (accountType == AccountTypeUtil.AccountType.PREMIUM) {
            messages.send(player, "error.premium_cannot_register");
            messages.send(player, "error.premium_cannot_register_info");
            return true;
        }

        // 3. Verificar si ya est autenticado
        if (plugin.getAuthStateManager().isAuthenticated(player)) {
            messages.send(player, "error.already_authenticated");
            return true;
        }

        // 3. Verificar argumentos
        if (args.length < 2) {
            messages.send(player, "usage.register");
            return true;
        }

        String password = args[0];
        String confirm = args[1];

        // 4.5. Verificar rate limit (proteccin contra spam de registros)
        String ip = player.getAddress().getAddress().getHostAddress();
        if (!plugin.getRateLimitService().checkLimit(ip)) {
            long remainingSeconds = plugin.getRateLimitService().getSecondsRemaining(ip);

            // KICKEAR AL JUGADOR con mensaje personalizado
            String kickMessage = messages.getMessage("rate_limit.kick_message",
                    MessageManager.placeholder().add("remaining", remainingSeconds).build());
            plugin.getFeedbackService().kickPlayer(player, kickMessage);

            // Log del evento
            plugin.getLogger().warning("[Rate Limit] " + player.getName() + " kicked during register - IP blocked for " + remainingSeconds + "s");

            return true;
        }

        // 4. Verificar base de datos (Usuario existe?) -> Usamos comprobacin rpida si
        // es posible
        // Nota: Para optimizacin, esto podra revisarse antes, pero asumimos que el
        // usuario sabe si est registrado
        // Para evitar llamadas a BD innecesarias, podramos confiar en el
        // AuthStateManager si cubriese todos los casos,
        // pero mejor verificar DB para consistencia.
        if (plugin.getDatabaseManager().getUserDAO().getUserByUUID(uuid).isPresent()) {
            messages.send(player, "error.already_registered");
            return true;
        }

        // 5. Validar que las contraseas coincidan
        if (!password.equals(confirm)) {
            messages.send(player, "password.must_match");
            return true;
        }

        // 6. Validar fortaleza de la contrasea
        var validation = plugin.getPasswordService().validatePassword(password, player.getName());
        if (!validation.valid) {
            messages.send(player, "password.too_weak");

            // Mostrar errores especficos si existen -> DESHABILITADO para evitar
            // duplicados con messages.yml
            // if (validation.errorMessage != null && !validation.errorMessage.isEmpty()) {
            // player.sendMessage("c" + validation.errorMessage);
            // }

            // Mostrar requisitos
            messages.send(player, "password.requirements.length");
            messages.send(player, "password.requirements.uppercase");
            messages.send(player, "password.requirements.lowercase");
            messages.send(player, "password.requirements.number");
            return true;
        }

        // Mostrar fortaleza de contrasea
        messages.send(player, "password.strength." + validation.getStrengthKey(),
                MessageManager.placeholder()
                        .add("player", player.getName())
                        .build());

        // 7. Hash de la contrasea
        String hash = plugin.getPasswordService().hashPassword(password);

        // 8. Crear objeto Usuario (siempre CRACKED porque los premium hacen auto-login)
        User newUser = new User(uuid, player.getName(), User.AuthType.CRACKED);
        newUser.setPasswordHash(hash);
        newUser.setLastIp(player.getAddress().getAddress().getHostAddress());
        newUser.setStatus("ACTIVE");

        // 9. Feedback visual de procesamiento
        messages.sendActionBar(player, "success.processing");

        // 10. Guardar en BD asncronamente
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getDatabaseManager().getUserDAO().createUser(newUser);

                // Log Security Event
                plugin.getSecurityLogger().log(
                        net.hybridauth.security.SecurityLogger.EventType.REGISTER,
                        newUser.getUsername(),
                        newUser.getUuid(),
                        newUser.getLastIp(),
                        "Registered via Command (AuthType: CRACKED)");

                // Resetear Rate Limit por si acaso
                plugin.getRateLimitService().resetLimit(newUser.getLastIp());

                // Crear sesin persistente inmediata
                plugin.getSessionManager().createSession(newUser.getUuid(), newUser.getLastIp());

                //// Volver al thread principal para acciones de Bukkit API
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    // Autenticar
                    plugin.getAuthStateManager().setAuthState(player, AuthState.AUTHENTICATED);

                    // Remover restricciones
                    plugin.getFeedbackService().removeRestrictions(player);

                    // Enviar mensajes de xito
                    messages.send(player, "success.registered",
                            MessageManager.placeholder()
                                    .add("player", player.getName())
                                    .build());

                    messages.send(player, "success.enjoy");

                    // Ttulos y Sonidos
                    plugin.getFeedbackService().sendTitle(player, "titles.register_success.title", "titles.register_success.subtitle");
                    plugin.getFeedbackService().playSoundSuccess(player);
                });

            } catch (SQLException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in RegisterCommand", e);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> messages.send(player, "error.database_error"));
            }
        });

        return true;
    }


}



