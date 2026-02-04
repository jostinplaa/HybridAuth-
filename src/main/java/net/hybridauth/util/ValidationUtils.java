package net.hybridauth.util;

import java.util.regex.Pattern;

/**
 * Utilidades para validacin y sanitizacin de inputs.
 * Implementa las recomendaciones de seguridad de la auditora.
 */
public class ValidationUtils {

    // Regex: 3-16 chars, letters/nums/under, BUT not *only* numbers or *only*
    // underscores
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^(?![0-9_]+$)[a-zA-Z0-9_]{3,16}$");
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[<>\"';\\\\]");

    /**
     * Valida un nombre de usuario de Minecraft.
     * <p>
     * Requisitos:
     * - Entre 3 y 16 caracteres
     * - Solo letras, nmeros y guiones bajos
     * - No puede ser solo nmeros o guiones bajos
     * 
     * @param username Nombre a validar
     * @return true si es vlido
     */
    public static boolean isValidUsername(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }

    /**
     * Valida si una contrasea es segura (longitud mnima).
     * 
     * @param password Contrasea a validar
     * @return true si cumple longitud mnima
     */
    public static boolean isValidPassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            return false;
        }
        return password.length() >= 4 && password.length() <= 32;
    }

    /**
     * Verifica si el input contiene caracteres peligrosos.
     * 
     * @param input Entrada a verificar
     * @return true si contiene caracteres inseguros
     */
    public static boolean containsUnsafeChars(String input) {
        return input != null && UNSAFE_CHARS.matcher(input).find();
    }

    /**
     * Sanitiza un input removiendo caracteres peligrosos.
     * til para prevenir XSS bsico en logs o mensajes de admin.
     * 
     * @param input Entrada del usuario
     * @return Entrada sanitizada y truncada a 100 caracteres
     */
    public static String sanitizeForLog(String input) {
        if (input == null)
            return "";
        String sanitized = UNSAFE_CHARS.matcher(input).replaceAll("");
        return sanitized.length() > 100 ? sanitized.substring(0, 100) + "..." : sanitized;
    }
}

