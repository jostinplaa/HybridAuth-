package net.hybridauth.core.auth;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
import net.hybridauth.data.model.User;
import net.hybridauth.network.netty.PremiumDetector;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * AUTO-LOGIN para jugadores PREMIUM
 * ¡SIN necesidad de /login!
 * 
 * Este sistema:
 * 1. Detecta si el jugador es premium en el handshake
 * 2. Verifica que el UUID coincida con el registrado
 * 3. Auto-autentica jugadores premium
 * 4. Protege contra robo de cuentas
 */
public class AutoLoginManager implements Listener {

    private final HybridAuthPlugin plugin;

    public AutoLoginManager(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // Este evento se ejecuta durante el handshake
        // Aquí podríamos hacer validaciones adicionales si es necesario
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String username = player.getName();

        // GUARD: Si ya está autenticado, retornar (evita doble procesamiento)
        // FIX BUG #1: Race condition cuando evento se dispara múltiples veces
        if (plugin.getAuthStateManager().isAuthenticated(player)) {
            return;
        }

        // 1. Verificar si es premium (desde el handshake)
        boolean isPremiumDetected = PremiumDetector.isPremium(username);

        if (!isPremiumDetected) {
            // Es cracked - necesita /register o /login
            plugin.getAuthStateManager().setAuthenticated(player, false);

            // Verificar si está registrado
            plugin.getDatabaseManager().getUserDAO().getUserByName(username).thenAccept(user -> {
                if (user == null) {
                    // No registrado
                    player.sendMessage(plugin.getMessageManager().getMessage("auth.register-required"));
                } else {
                    // Registrado, necesita login
                    player.sendMessage(plugin.getMessageManager().getMessage("auth.login-required"));
                }
            });
            return;
        }

        // 2. Obtener UUID real del handshake
        UUID premiumUUID = PremiumDetector.getRealUUID(username);
        if (premiumUUID == null) {
            player.kickPlayer(plugin.getMessageManager().getMessage("auth.premium-verification-failed"));
            plugin.getSecurityLogger().logWarning("Premium verification failed for " + username + " - UUID is null");
            return;
        }

        // 3. Verificar con base de datos si ya está registrado
        plugin.getDatabaseManager().getUserDAO().getUserByName(username).thenAccept(user -> {

            if (user == null) {
                // Primera vez - auto-registrar como premium
                autoRegisterPremium(player, username, premiumUUID);

            } else if (user.isPremium()) {
                // Ya registrado como premium - verificar UUID
                autoLoginPremium(player, user, premiumUUID);

            } else {
                // Registrado como cracked, pero conecta como premium
                handleCrackedToPremiumAttempt(player, username);
            }
        }).exceptionally(throwable -> {
            player.kickPlayer("§c§lDatabase Error\n§7Please try again later.");
            plugin.getLogger().severe("Database error for " + username + ": " + throwable.getMessage());
            return null;
        });
    }

    /**
     * Auto-registra un jugador premium nuevo
     */
    private void autoRegisterPremium(Player player, String username, UUID premiumUUID) {
        plugin.getDatabaseManager().getUserDAO().createPremiumUser(username, premiumUUID).thenRun(() -> {
            plugin.getAuthStateManager().setAuthenticated(player, true);

            player.sendTitle(
                    plugin.getMessageManager().getMessage("auth.welcome-title"),
                    plugin.getMessageManager().getMessage("auth.premium-auto-registered"),
                    10, 70, 20);

            plugin.getMessageManager().send(player, "auth.premium-registered-success");

            plugin.getSecurityLogger().logInfo("Premium auto-register: " + username + " (UUID: " + premiumUUID + ")");
        });
    }

    /**
     * Auto-login para jugador premium existente
     */
    private void autoLoginPremium(Player player, User user, UUID detectedUUID) {
        UUID registeredUUID = user.getPremiumUuid();

        // CRÍTICO: Verificar que el UUID coincida (anti-impostor)
        if (!registeredUUID.equals(detectedUUID)) {
            // ¡IMPOSTOR DETECTADO!
            handleImpostor(player, user.getUsername(), registeredUUID, detectedUUID);
            return;
        }

        // UUID coincide - auto-login exitoso
        plugin.getAuthStateManager().setAuthenticated(player, true);

        // Actualizar stats
        plugin.getDatabaseManager().getUserDAO().updateLoginStats(user.getUsername(),
                player.getAddress().getAddress().getHostAddress());

        player.sendTitle(
                plugin.getMessageManager().getMessage("auth.welcome-back-title"),
                plugin.getMessageManager().getMessage("auth.premium-auto-login"),
                10, 50, 20);

        plugin.getMessageManager().send(player, "auth.premium-login-success");

        // Mostrar stats si está configurado
        if (plugin.getConfig().getBoolean("features.show-login-stats", true)) {
            plugin.getMessageManager().send(player, "auth.stats-last-login",
                    net.hybridauth.core.messages.MessageManager.placeholder()
                            .add("date", user.getLastLoginDate().toString())
                            .build());
            plugin.getMessageManager().send(player, "auth.stats-total-logins",
                    net.hybridauth.core.messages.MessageManager.placeholder()
                            .add("total", user.getTotalLogins())
                            .build());
        }

        plugin.getSecurityLogger().logInfo("Premium auto-login: " + user.getUsername());
    }

    /**
     * Maneja cuando alguien registrado como cracked intenta conectar como premium
     */
    private void handleCrackedToPremiumAttempt(Player player, String username) {
        plugin.getAuthStateManager().setAuthenticated(player, false);

        plugin.getMessageManager().send(player, "premium.cracked_to_premium_prompt");

        plugin.getSecurityLogger().logInfo("Cracked→Premium attempt: " + username);
    }

    /**
     * Maneja detección de impostores (UUID mismatch)
     */
    private void handleImpostor(Player player, String username, UUID expectedUUID, UUID actualUUID) {
        String kickMsg = plugin.getMessageManager().getMessage("security.impostor_kick",
                MessageManager.placeholder()
                        .add("username", username)
                        .add("expected_uuid", expectedUUID.toString())
                        .add("actual_uuid", actualUUID.toString())
                        .build());
        player.kickPlayer(kickMsg);

        // Notify admins
        String ip = player.getAddress().getAddress().getHostAddress();
        plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("hybridauth.admin"))
                .forEach(admin -> {
                    plugin.getMessageManager().send(admin, "premium.impostor_alert_admin",
                            MessageManager.placeholder()
                                    .add("username", username)
                                    .add("ip", ip)
                                    .add("expected_uuid", expectedUUID.toString())
                                    .add("actual_uuid", actualUUID.toString())
                                    .build());
                });

        // Log crítico DB + Alerta (Discord/Email) via AlertManager
        plugin.getSecurityLogger().log(
                net.hybridauth.security.SecurityLogger.EventType.IMPOSTOR_DETECTED,
                username,
                expectedUUID,
                ip,
                "Impostor Attempt (UUID mismatch). Expected: " + expectedUUID + " Actual: " + actualUUID);

        // Blacklist IP automáticamente por 1 hora
        plugin.getBlacklistManager().blockIP(
                ip,
                3600, // 1 hora
                "IMPOSTOR ATTEMPT: " + username,
                "SYSTEM");

        plugin.getLogger().severe("IP BLACKLISTED: " + ip + " (impostor attempt)");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Limpiar cache cuando el jugador se va
        PremiumDetector.clearCache(event.getPlayer().getName());
        plugin.getAuthStateManager().setAuthenticated(event.getPlayer(), false);
    }
}
