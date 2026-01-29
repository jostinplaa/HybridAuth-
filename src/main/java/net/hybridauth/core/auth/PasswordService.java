package net.hybridauth.core.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import net.hybridauth.HybridAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PasswordService {

    private final int cost;
    private final int minLength;
    private final int maxLength;
    private final boolean requireUppercase;
    private final boolean requireLowercase;
    private final boolean requireNumbers;
    private final boolean requireSpecialChars;
    private final Set<String> commonPasswords;

    public PasswordService(HybridAuthPlugin plugin) {
        FileConfiguration config = plugin.getConfig();

        this.cost = config.getInt("security.password.bcrypt-cost", 12);
        this.minLength = config.getInt("security.password.min-length", 8);
        this.maxLength = config.getInt("security.password.max-length", 32);
        this.requireUppercase = config.getBoolean("security.password.require-uppercase", true);
        this.requireLowercase = config.getBoolean("security.password.require-lowercase", true);
        this.requireNumbers = config.getBoolean("security.password.require-numbers", true);
        this.requireSpecialChars = config.getBoolean("security.password.require-special-chars", false);

        // Cargar lista de contraseñas comunes
        this.commonPasswords = loadCommonPasswords();
    }

    private Set<String> loadCommonPasswords() {
        Set<String> passwords = new HashSet<>();
        // Top list
        String[] common = {
                "123456", "password", "12345678", "qwerty", "123456789",
                "12345", "1234", "111111", "1234567", "dragon",
                "123123", "baseball", "iloveyou", "trustno1", "1234567890",
                "superman", "qazwsx", "michael", "football", "shadow",
                "master", "666666", "qwertyuiop", "123321", "mustang",
                "letmein", "baseball", "654321", "monkey", "696969",
                "minecraft", "password1", "admin", "root", "toor",
                "pass", "test", "guest", "login", "changeme"
        };
        passwords.addAll(Arrays.asList(common));
        return passwords;
    }

    public PasswordValidationResult validatePassword(String password, String username) {
        List<String> errors = new ArrayList<>();

        // Longitud
        if (password.length() < minLength) {
            errors.add("La contraseña debe tener al menos " + minLength + " caracteres.");
        }
        if (password.length() > maxLength) {
            errors.add("La contraseña no puede tener más de " + maxLength + " caracteres.");
        }

        // Mayúsculas
        if (requireUppercase && !password.matches(".*[A-Z].*")) {
            errors.add("La contraseña debe contener al menos una letra mayúscula.");
        }

        // Minúsculas
        if (requireLowercase && !password.matches(".*[a-z].*")) {
            errors.add("La contraseña debe contener al menos una letra minúscula.");
        }

        // Números
        if (requireNumbers && !password.matches(".*\\d.*")) {
            errors.add("La contraseña debe contener al menos un número.");
        }

        // Caracteres especiales
        if (requireSpecialChars && !password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            errors.add("La contraseña debe contener al menos un carácter especial.");
        }

        // Contraseñas comunes
        String lowerPass = password.toLowerCase();
        if (commonPasswords.contains(lowerPass)) {
            errors.add("Esta contraseña es demasiado común. Elige una más segura.");
        }

        // Similitud con username
        if (username != null && password.toLowerCase().contains(username.toLowerCase())) {
            errors.add("La contraseña no puede contener tu nombre de usuario.");
        }

        // Calcular fuerza
        int strength = calculateStrength(password);

        if (errors.isEmpty()) {
            return new PasswordValidationResult(true, null, strength);
        } else {
            return new PasswordValidationResult(false, String.join("\n", errors), strength);
        }
    }

    private int calculateStrength(String password) {
        int strength = 0;

        // Longitud
        if (password.length() >= 8)
            strength += 20;
        if (password.length() >= 12)
            strength += 20;
        if (password.length() >= 16)
            strength += 10;

        // Complejidad
        if (password.matches(".*[A-Z].*"))
            strength += 15;
        if (password.matches(".*[a-z].*"))
            strength += 15;
        if (password.matches(".*\\d.*"))
            strength += 10;
        if (password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*"))
            strength += 10;

        return Math.min(100, strength);
    }

    public String hashPassword(String password) {
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    public boolean verifyPassword(String password, String paramHash) {
        if (paramHash == null || password == null)
            return false;
        BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), paramHash);
        return result.verified;
    }

    public static class PasswordValidationResult {
        public final boolean valid;
        public final String errorMessage;
        public final int strength; // 0-100

        public PasswordValidationResult(boolean valid, String errorMessage, int strength) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.strength = strength;
        }

        public String getStrengthLabel() {
            if (strength < 40)
                return "§c§lMUY DÉBIL";
            if (strength < 60)
                return "§6§lDÉBIL";
            if (strength < 80)
                return "§e§lMEDIA";
            if (strength < 95)
                return "§a§lFUERTE";
            return "§a§l§nMUY FUERTE";
        }
    }
}
