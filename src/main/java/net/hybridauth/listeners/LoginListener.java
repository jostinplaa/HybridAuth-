package net.hybridauth.listeners;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.auth.AuthStateManager;
import net.hybridauth.core.auth.AuthStateManager.AuthState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Bukkit;

public class LoginListener implements Listener {

    private final HybridAuthPlugin plugin;
    private final AuthStateManager authStateManager;

    public LoginListener(HybridAuthPlugin plugin, AuthStateManager authStateManager) {
        this.plugin = plugin;
        this.authStateManager = authStateManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // En un futuro aqui verificaremos si el nombre es válido o si está baneado
        // También aquí se decide si se inicia el proceso Premium
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 1. Check if Premium (Validated by EncryptionHandler)
        if (plugin.getEncryptionHandler().isPremium(player.getName())) {
            handlePremiumLogin(player);
        } else {
            // 2. Cracked flow
            handleCrackedJoin(player);
        }
    }

    private void handlePremiumLogin(Player player) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Check if user exists, if not create
                java.util.Optional<net.hybridauth.data.model.User> userOpt = plugin.getDatabaseManager().getUserDAO()
                        .getUserByUUID(player.getUniqueId());
                net.hybridauth.data.model.User user;

                if (userOpt.isPresent()) {
                    user = userOpt.get();
                    // Update info
                    user.setUsername(player.getName());
                    user.setLastIp(player.getAddress().getAddress().getHostAddress());
                    user.setLastLoginDate(new java.sql.Timestamp(System.currentTimeMillis()));
                    user.setTotalLogins(user.getTotalLogins() + 1);
                    // Ensure auth type is updated if they switched/migrated (optional, but safe)
                    if (user.getAuthType() != net.hybridauth.data.model.User.AuthType.PREMIUM) {
                        user.setAuthType(net.hybridauth.data.model.User.AuthType.PREMIUM);
                    }
                    plugin.getDatabaseManager().getUserDAO().updateUser(user);
                } else {
                    // Register new Premium User
                    user = new net.hybridauth.data.model.User(player.getUniqueId(), player.getName(),
                            net.hybridauth.data.model.User.AuthType.PREMIUM);
                    user.setLastIp(player.getAddress().getAddress().getHostAddress());
                    user.setLastLoginDate(new java.sql.Timestamp(System.currentTimeMillis()));
                    user.setTotalLogins(1);
                    plugin.getDatabaseManager().getUserDAO().createUser(user);
                    plugin.getLogger().info("Auto-registered Premium user: " + player.getName());
                }

                // Sync back to main thread to authorize
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    authStateManager.setAuthState(player, AuthState.AUTHENTICATED);
                    removeAuthRestrictions(player);
                    player.sendMessage("§a§lHybridAuth §8» §aAutenticado automáticamente (Cuenta Premium).");
                });

            } catch (Exception e) {
                e.printStackTrace();
                player.sendMessage("§cError en auto-login premium. Contacta a un admin.");
            }
        });
    }

    private void handleCrackedJoin(Player player) {
        authStateManager.setAuthState(player, AuthState.UNAUTHENTICATED);
        // Check if registered or has session
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {

            // Check for persistent session first
            String ip = player.getAddress().getAddress().getHostAddress();
            if (plugin.getSessionManager().validateSession(player.getUniqueId(), ip)) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    authStateManager.setAuthState(player, AuthState.AUTHENTICATED);
                    removeAuthRestrictions(player);
                    player.sendMessage("§a§lHybridAuth §8» §aSesión restaurada correctamente.");
                });
                return;
            }

            boolean isRegistered = plugin.getDatabaseManager().getUserDAO().getUserByUUID(player.getUniqueId())
                    .isPresent();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (isRegistered) {
                    player.sendMessage("§c§lHybridAuth §8» §7Por favor, usa §f/login <pass> §7para entrar.");
                } else {
                    player.sendMessage(
                            "§c§lHybridAuth §8» §7Por favor, usa §f/register <pass> <pass> §7para registrarte.");
                }

                // UX Enhancement: Apply Blindness/Slow
                applyAuthRestrictions(player);

                // UX Enhancement: Authentication Timeout
                int timeoutSeconds = plugin.getConfig().getInt("authentication.timeout-seconds", 60);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && authStateManager.isPending(player)) {
                        player.kickPlayer("§c§lHybridAuth\n\n§7Tiempo de autenticación agotado.");
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
        authStateManager.removePlayer(event.getPlayer());
    }

    // --- Restricciones para usuarios no autenticados ---

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (authStateManager.isPending(event.getPlayer())) {
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom()); // Cancelar movimiento horizontal
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (authStateManager.isPending(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cDebes loguearte primero.");
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
        if (event.getWhoClicked() instanceof Player player && authStateManager.isPending(player)) {
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
