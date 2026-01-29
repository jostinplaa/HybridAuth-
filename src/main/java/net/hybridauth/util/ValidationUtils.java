package net.hybridauth.util;

import java.util.regex.Pattern;

/**
 * Utilidades para validación y sanitización de inputs.
 * Implementa las recomendaciones de seguridad de la auditoría.
 */
public class ValidationUtils {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[<>\"';\\\\]");

    /**
     * Valida un nombre de usuario de Minecraft.
     * 
     * @param username Nombre a validar
     * @return true si es válido
     */
    public static boolean isValidUsername(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }

    /**
     * Valida si una contraseña es segura (longitud mínima).
     * 
     * @param password Contraseña a validar
     * @return true si cumple longitud mínima
     */
    public static boolean isValidPasswordLength(String password) {
        return password != null && password.length() >= 4 && password.length() <= 32;
    }

    /**
     * Sanitiza un input removiendo caracteres peligrosos.
     * Útil para prevenir XSS básico en logs o mensajes de admin.
     * 
     * @param input Entrada del usuario
     * @return Entrada sanitizada
     */
    public static String sanitize(String input) {
        if (input == null)
            return "";
        return UNSAFE_CHARS.matcher(input).replaceAll("");
    }
}
