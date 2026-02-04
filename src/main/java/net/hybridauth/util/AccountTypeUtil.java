package net.hybridauth.util;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Utilidad para detectar el tipo de cuenta de un jugador
 * 
 * @version 1.4.0
 */
public class AccountTypeUtil {

    /**
     * Tipos de cuenta soportados
     */
    public enum AccountType {
        PREMIUM, // Cuenta original de Mojang
        CRACKED, // Cuenta pirata
        BEDROCK // Cuenta de Bedrock (Geyser/Floodgate)
    }

    /**
     * Detecta el tipo de cuenta de un jugador
     */
    public static AccountType getAccountType(Player player) {
        String name = player.getName();
        UUID uuid = player.getUniqueId();

        // 1. Detectar Bedrock (Floodgate/Geyser)
        if (isBedrock(name, uuid)) {
            return AccountType.BEDROCK;
        }

        // 2. Detectar Premium (UUID v4 o verificacin de BD)
        if (isPremium(player)) {
            return AccountType.PREMIUM;
        }

        // 3. Por defecto es Cracked
        return AccountType.CRACKED;
    }

    /**
     * Verifica si un jugador es de Bedrock
     */
    private static boolean isBedrock(String name, UUID uuid) {
        // Mtodo 1: Nombre empieza con . (Geyser/Flood gate)
        if (name.startsWith(".")) {
            return true;
        }

        // Mtodo 2: UUID de Floodgate (empieza con 00000000-0000-0000)
        String uuidString = uuid.toString();
        return uuidString.startsWith("00000000-0000-0000");
    }

    /**
     * Verifica si un jugador tiene cuenta premium
     */
    private static boolean isPremium(Player player) {
        UUID uuid = player.getUniqueId();

        // UUID v4 (premium) = version 4
        // UUID v3 (offline/cracked) = version 3
        int version = uuid.version();

        if (version == 4) {
            return true; // Premium
        }

        // Verificar en BD si est marcado como premium
        try {
            HybridAuthPlugin plugin = HybridAuthPlugin.getInstance();
            if (plugin != null) {
                var userOpt = plugin.getDatabaseManager().getUserDAO()
                        .getUserByUUID(uuid);

                if (userOpt.isPresent()) {
                    return userOpt.get().isPremium();
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    /**
     * Obtiene el nombre legible del tipo de cuenta
     */
    public static String getAccountTypeName(AccountType type) {
        switch (type) {
            case PREMIUM:
                return "aPremium 7(Mojang Original)";
            case BEDROCK:
                return "bBedrock 7(Geyser/Mobile)";
            case CRACKED:
                return "eCracked 7(No Premium)";
            default:
                return "7Desconocido";
        }
    }
}

