package net.hybridauth.commands;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.auth.AuthStateManager.AuthState;
import net.hybridauth.core.messages.MessageManager;
import net.hybridauth.data.model.User;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Comando de registro para nuevos jugadores.
 * Permite crear una cuenta en el sistema de autenticación.
 * 
 * @author TuNombre
 * @version 1.1.0
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
     * @return true si el comando se ejecutó correctamente
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

        // 2. Verificar si ya está autenticado
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

        // 4. Verificar base de datos (Usuario existe?) -> Usamos comprobación rápida si
        // es posible
        // Nota: Para optimización, esto podría revisarse antes, pero asumimos que el
        // usuario sabe si está registrado
        // Para evitar llamadas a BD innecesarias, podríamos confiar en el
        // AuthStateManager si cubriese todos los casos,
        // pero mejor verificar DB para consistencia.
        if (plugin.getDatabaseManager().getUserDAO().getUserByUUID(uuid).isPresent()) {
            messages.send(player, "error.already_registered");
            return true;
        }

        // 5. Validar que las contraseñas coincidan
        if (!password.equals(confirm)) {
            messages.send(player, "password.must_match");
            return true;
        }

        // 6. Validar fortaleza de la contraseña
        var validation = plugin.getPasswordService().validatePassword(password, player.getName());
        if (!validation.valid) {
            messages.send(player, "password.too_weak");

            // Mostrar errores específicos si existen
            if (validation.errorMessage != null && !validation.errorMessage.isEmpty()) {
                // Aquí podríamos mapear los errores a mensajes configurables si quisiéramos ser
                // muy estrictos,
                // pero por ahora usamos el mensaje raw del validador o un mensaje genérico
                player.sendMessage("§c" + validation.errorMessage);
            }

            // Mostrar requisitos
            messages.send(player, "password.requirements.uppercase");
            messages.send(player, "password.requirements.lowercase");
            messages.send(player, "password.requirements.number");
            return true;
        }

        // Mostrar fortaleza de contraseña
        messages.send(player, "password.strength." + validation.getStrengthKey(),
                MessageManager.placeholder()
                        .add("player", player.getName())
                        .build());

        // 7. Hash de la contraseña
        String hash = plugin.getPasswordService().hashPassword(password);

        // 8. Crear objeto Usuario
        User newUser = new User(uuid, player.getName(), User.AuthType.CRACKED);
        newUser.setPasswordHash(hash);
        newUser.setLastIp(player.getAddress().getAddress().getHostAddress());
        newUser.setStatus("ACTIVE");

        // 9. Feedback visual de procesamiento
        messages.sendActionBar(player, "success.processing");

        // 10. Guardar en BD asíncronamente
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getDatabaseManager().getUserDAO().createUser(newUser);

                // Log Security Event
                plugin.getSecurityLogger().log(
                        net.hybridauth.security.SecurityLogger.EventType.REGISTER,
                        newUser.getUsername(),
                        newUser.getUuid(),
                        newUser.getLastIp(),
                        "Registered via Command");

                // Resetear Rate Limit por si acaso
                plugin.getRateLimitService().resetLimit(newUser.getLastIp());

                // Crear sesión persistente inmediata
                plugin.getSessionManager().createSession(newUser.getUuid(), newUser.getLastIp());

                // Volver al thread principal para acciones de Bukkit API
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    // Autenticar
                    plugin.getAuthStateManager().setAuthState(player, AuthState.AUTHENTICATED);

                    // Remover restricciones
                    player.removePotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS);
                    player.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOW);

                    // Enviar mensajes de éxito
                    messages.send(player, "success.registered",
                            MessageManager.placeholder()
                                    .add("player", player.getName())
                                    .build());

                    messages.send(player, "success.enjoy");

                    // Títulos y Sonidos
                    messages.sendTitle(player, "titles.register_success.title", "titles.register_success.subtitle");
                    player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f);
                });

            } catch (SQLException e) {
                e.printStackTrace();
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> messages.send(player, "error.database_error"));
            }
        });

        return true;
    }
}
