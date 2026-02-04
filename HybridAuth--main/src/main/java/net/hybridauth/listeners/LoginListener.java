package net.hybridauth.listeners;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.auth.AuthStateManager;
import net.hybridauth.core.auth.AuthStateManager.AuthState;
import net.hybridauth.data.model.User;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LoginListener implements Listener {

    private final HybridAuthPlugin plugin;
    private final AuthStateManager authStateManager;

    // Almacena UUIDs que ya vieron el mensaje de advertencia
    private final Set<UUID> hasSeenWarning = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public LoginListener(HybridAuthPlugin plugin, AuthStateManager authStateManager) {
        this.plugin = plugin;
        this.authStateManager = authStateManager;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String username = event.getName();
        UUID uuid = event.getUniqueId();
        String ip = event.getAddress().getHostAddress();

        plugin.getLogger().info("[PreLogin] " + username + " attempting to join...");

        // ====== PASO 1: Verificar si es PREMIUM ======
        // Premium = Auto-login, dejar pasar siempre
        boolean isPremium = net.hybridauth.network.netty.PremiumDetector.isPremium(username);

        if (isPremium) {
            // Actualizar el UUID en el cache (porque isPremium() lo guarda como null)
            net.hybridauth.network.netty.PremiumDetector.updateUUID(username, uuid);
            plugin.getLogger().info("[PreLogin] " + username + " - Premium detected, allowing");
            return; // Dejar pasar sin mostrar nada
        }

        // ====== PASO 2: Usuario CRACKED ======

        // Verificar si ya está registrado en DB
        Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO()
                .getUserByUsername(username);

        if (userOpt.isPresent()) {
            User user = userOpt.get();

            // Usuario cracked YA REGISTRADO
            // Verificar si tiene sesión válida
            if (plugin.getSessionManager().validateSession(uuid, ip)) {
                plugin.getLogger().info("[PreLogin] " + username + " - Valid session, allowing");
                return; // Dejar pasar (auto-login por sesión)
            }

            // Si ya vio el mensaje antes, dejar pasar (para que haga /login)
            if (hasSeenWarning.contains(uuid)) {
                plugin.getLogger().info("[PreLogin] " + username + " - Already saw warning, allowing");
                return;
            }

            // PRIMERA CONEXIÓN DEL DÍA (sin sesión válida)
            // Mostrar mensaje de LOGIN (desde messages.yml)
            String warningMessage = plugin.getMessageManager().getMessage("login.warning_kick",
                    net.hybridauth.core.messages.MessageManager.placeholder()
                            .add("player", username)
                            .build());

            // Marcar que ya vio el mensaje (sin delay — el mensaje se muestran en el kick)
            hasSeenWarning.add(uuid);

            // Programar limpieza (5 minutos)
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                hasSeenWarning.remove(uuid);
            }, 5 * 60 * 20L);

            // Kickear con el mensaje
            event.disallow(Result.KICK_OTHER, warningMessage);

            plugin.getLogger().info("[PreLogin] " + username + " - Showed login warning, disconnecting");
            return;
        }

        // ====== PASO 3: Usuario NUEVO (no registrado) ======

        // Si ya vio el mensaje, dejar pasar (para que haga /register)
        if (hasSeenWarning.contains(uuid)) {
            plugin.getLogger().info("[PreLogin] " + username + " - New user, already saw warning, allowing");
            return;
        }

        // PRIMERA VEZ conectando
        // Mostrar mensaje de REGISTRO (desde messages.yml)
        String warningMessage = plugin.getMessageManager().getMessage("register.warning_kick",
                net.hybridauth.core.messages.MessageManager.placeholder()
                        .add("player", username)
                        .build());

        // Marcar que ya vio el mensaje (sin delay — el mensaje se muestran en el kick)
        hasSeenWarning.add(uuid);

        // Programar limpieza (5 minutos)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            hasSeenWarning.remove(uuid);
        }, 5 * 60 * 20L);

        // Kickear con el mensaje
        event.disallow(Result.KICK_OTHER, warningMessage);

        plugin.getLogger().info("[PreLogin] " + username + " - New user, showed register warning, disconnecting");
    }



    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        plugin.getLogger().info("[Login] Player " + player.getName() + " joining...");

        // Pequeño delay para asegurar que el PremiumDetector terminó
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // 1. Check if Premium (Validated by PremiumDetector)
            boolean isPremium = net.hybridauth.network.netty.PremiumDetector.isPremium(player.getName());

            plugin.getLogger().info("[Login] " + player.getName() + " - Premium status: " + isPremium);

            if (isPremium) {
                handlePremiumLogin(player);
            } else {
                // 2. Cracked flow
                handleCrackedJoin(player);
            }
        }, 5L); // Delay de 5 ticks (250ms)
    }

    private void handlePremiumLogin(Player player) {
        plugin.getLogger().info("[Premium Login] Processing " + player.getName());

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Check if user exists, if not create
                Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO()
                        .getUserByUUID(player.getUniqueId());
                User user;

                if (userOpt.isPresent()) {
                    user = userOpt.get();
                    plugin.getLogger().info("[Premium Login] User exists in DB: " + player.getName());

                    // Update info
                    user.setUsername(player.getName());
                    user.setLastIp(player.getAddress().getAddress().getHostAddress());
                    user.setLastLoginDate(new java.sql.Timestamp(System.currentTimeMillis()));
                    user.setTotalLogins(user.getTotalLogins() + 1);

                    // Ensure auth type is updated
                    if (user.getAuthType() != User.AuthType.PREMIUM) {
                        plugin.getLogger().info("[Premium Login] Updating auth type to PREMIUM");
                        user.setAuthType(User.AuthType.PREMIUM);
                    }

                    plugin.getDatabaseManager().getUserDAO().updateUser(user);
                } else {
                    // Register new Premium User
                    plugin.getLogger().info("[Premium Login] Creating new premium user");

                    user = new User(player.getUniqueId(), player.getName(), User.AuthType.PREMIUM);
                    user.setLastIp(player.getAddress().getAddress().getHostAddress());
                    user.setLastLoginDate(new java.sql.Timestamp(System.currentTimeMillis()));
                    user.setTotalLogins(1);
                    plugin.getDatabaseManager().getUserDAO().createUser(user);
                    plugin.getLogger().info("✓ Auto-registered Premium user: " + player.getName());
                }

                // Sync back to main thread to authorize
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    authStateManager.setAuthState(player, AuthState.AUTHENTICATED);
                    removeAuthRestrictions(player);
                    plugin.getMessageManager().send(player, "authentication.premium_detected");

                    // Crear sesión
                    plugin.getSessionManager().createSession(player.getUniqueId(),
                            player.getAddress().getAddress().getHostAddress());

                    plugin.getLogger().info("✓ Premium login completed: " + player.getName());
                });

            } catch (Exception e) {
                plugin.getLogger().severe("ERROR in premium login for " + player.getName());
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in LoginListener", );

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getMessageManager().send(player, "auth.premium-verification-failed");
                });
            }
        });
    }

    private void handleCrackedJoin(Player player) {
        plugin.getLogger().info("[Cracked Login] Processing " + player.getName());

        authStateManager.setAuthState(player, AuthState.UNAUTHENTICATED);

        // Check if registered or has session
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {

            // Check for persistent session first
            String ip = player.getAddress().getAddress().getHostAddress();
            if (plugin.getSessionManager().validateSession(player.getUniqueId(), ip)) {
                plugin.getLogger().info("[Cracked Login] Valid session found");

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    authStateManager.setAuthState(player, AuthState.AUTHENTICATED);
                    removeAuthRestrictions(player);
                    plugin.getMessageManager().send(player, "authentication.session_resumed");
                });
                return;
            }

            boolean isRegistered = plugin.getDatabaseManager().getUserDAO()
                    .getUserByUUID(player.getUniqueId()).isPresent();

            plugin.getLogger().info("[Cracked Login] Registered: " + isRegistered);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (isRegistered) {
                    plugin.getMessageManager().send(player, "auth.login-required");
                } else {
                    plugin.getMessageManager().send(player, "auth.register-required");
                }

                // Apply restrictions
                applyAuthRestrictions(player);

                // Authentication Timeout
                int timeoutSeconds = plugin.getConfig().getInt("authentication.timeout-seconds", 60);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && authStateManager.isPending(player)) {
                        plugin.getLogger().warning("[Timeout] Kicking " + player.getName());
                        player.kickPlayer(plugin.getMessageManager().getMessage("authentication.timeout"));
                    }
                }, timeoutSeconds * 20L);
            });
        });
    }

    private void applyAuthRestrictions(Player player) {
        if (plugin.getConfig().getBoolean("restrictions.blindness-effect", true)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1, false, false));
        }
        if (plugin.getConfig().getBoolean("restrictions.slow-effect", true)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 5, false, false));
        }
    }

    private void removeAuthRestrictions(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOW);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Limpiar estado premium
        net.hybridauth.network.netty.PremiumDetector.clearCache(player.getName());

        // Limpiar estado de autenticación
        authStateManager.removePlayer(player);

        plugin.getLogger().info("[Logout] " + player.getName() + " - State cleared");
    }

    // --- Restricciones para usuarios no autenticados ---

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (authStateManager.isPending(event.getPlayer())) {
            if (event.getFrom().getX() != event.getTo().getX() ||
                    event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom());
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (authStateManager.isPending(event.getPlayer())) {
            event.setCancelled(true);
            plugin.getMessageManager().send(event.getPlayer(), "error.not_authenticated");
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (authStateManager.isPending(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player &&
                authStateManager.isPending(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (authStateManager.isPending(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
