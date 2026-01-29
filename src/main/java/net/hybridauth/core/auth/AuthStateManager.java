package net.hybridauth.core.auth;

import org.bukkit.entity.Player;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthStateManager {

    public enum AuthState {
        UNAUTHENTICATED, // Jugador entró pero no se ha logueado
        AUTHENTICATED, // Jugador logueado correctamente
        PREMIUM_PENDING, // Esperando validación premium
        REGISTER_REQUIRED // Necesita registrarse
    }

    private final Map<UUID, AuthState> authStates = new ConcurrentHashMap<>();

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
        return getAuthState(player) == AuthState.AUTHENTICATED;
    }

    public boolean isPending(Player player) {
        AuthState state = getAuthState(player);
        return state == AuthState.UNAUTHENTICATED || state == AuthState.PREMIUM_PENDING
                || state == AuthState.REGISTER_REQUIRED;
    }
}
