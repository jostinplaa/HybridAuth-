package net.hybridauth.core.auth;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthStateManager implements Listener {

    private final HybridAuthPlugin plugin;

    public enum AuthState {
        UNAUTHENTICATED, // Jugador entr pero no se ha logueado
        AUTHENTICATED, // Jugador logueado correctamente
        PREMIUM_PENDING, // Esperando validacin premium
        REGISTER_REQUIRED, // Necesita registrarse
        AWAITING_2FA // Esperando cdigo 2FA
    }

    private final Map<UUID, AuthState> authStates = new ConcurrentHashMap<>();

    public AuthStateManager(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * FIX BUG #3: Limpiar estado cuando jugador se desconecta
     * Previene memory leak con miles de conexiones/desconexiones
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        authStates.remove(event.getPlayer().getUniqueId());
    }

    public void setAuthState(Player player, AuthState state) {
        authStates.put(player.getUniqueId(), state);
    }

    public void removePlayer(Player player) {
        authStates.remove(player.getUniqueId());
    }

    public AuthState getAuthState(Player player) {
        return authStates.getOrDefault(player.getUniqueId(), AuthState.UNAUTHENTICATED);
    }

    public boolean isAuthenticated(Player player) {
        // Check local state first
        if (getAuthState(player) == AuthState.AUTHENTICATED) {
            return true;
        }

        // If not authenticated locally, check sync manager
        if (plugin.getSyncManager() != null && plugin.getSyncManager().isEnabled()) {
            try {
                // Non-blocking quick check if possible (CompletableFuture.getNow)
                Boolean syncState = plugin.getSyncManager().getAuthState(player.getUniqueId()).getNow(null);
                if (syncState != null && syncState) {
                    // Update local state to avoid future calls
                    setAuthState(player, AuthState.AUTHENTICATED);
                    return true;
                }
            } catch (Exception e) {
                // Ignore sync errors, fall back to local
            }
        }

        return false;
    }

    public boolean isPending(Player player) {
        AuthState state = getAuthState(player);
        return state == AuthState.UNAUTHENTICATED || state == AuthState.PREMIUM_PENDING
                || state == AuthState.REGISTER_REQUIRED || state == AuthState.AWAITING_2FA;
    }

    /**
     * Helper method to set authenticated/NOT authenticated
     * Also updates network state via SyncManager
     */
    public void setAuthenticated(Player player, boolean authenticated) {
        setAuthState(player, authenticated ? AuthState.AUTHENTICATED : AuthState.UNAUTHENTICATED);

        // Broadcast to network
        if (plugin.getSyncManager() != null && plugin.getSyncManager().isEnabled()) {
            plugin.getSyncManager().setAuthState(player.getUniqueId(), authenticated);
        }
    }
}

