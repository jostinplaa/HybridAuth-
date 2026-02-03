package net.hybridauth.security.captcha;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sistema de Captcha para prevenir bots y ataques automatizados
 * 
 * Características:
 * - Captcha matemático simple (suma, resta)
 * - Se activa en situaciones sospechosas
 * - Timeout de 30 segundos
 * - 3 intentos máximos
 * 
 * @version 1.2.0
 */
public class CaptchaService implements Listener {

    private final HybridAuthPlugin plugin;
    private final SecureRandom random;
    
    // UUID -> CaptchaChallenge
    private final Map<UUID, CaptchaChallenge> activeChallenges;
    
    private final boolean enabled;

    public CaptchaService(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.random = new SecureRandom();
        this.activeChallenges = new ConcurrentHashMap<>();
        this.enabled = plugin.getConfig().getBoolean("security.captcha.enabled", true);
        
        if (enabled) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            plugin.getLogger().info("✓ Captcha system enabled");
        }
    }

    /**
     * Requiere que el jugador resuelva un captcha
     */
    public void requireCaptcha(Player player, CaptchaReason reason) {
        if (!enabled) return;

        // Si ya tiene un captcha activo, no crear otro
        if (activeChallenges.containsKey(player.getUniqueId())) {
            return;
        }

        // Generar challenge
        CaptchaChallenge challenge = generateChallenge(reason);
        activeChallenges.put(player.getUniqueId(), challenge);

        // Mostrar captcha
        showCaptcha(player, challenge);

        // Programar timeout (30 segundos)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            CaptchaChallenge current = activeChallenges.get(player.getUniqueId());
            
            if (current != null && current == challenge) {
                // Timeout - kickear
                activeChallenges.remove(player.getUniqueId());
                
                player.kickPlayer(
                    "§c§l⚠ VERIFICATION TIMEOUT ⚠\n\n" +
                    "§7You failed to complete the verification\n" +
                    "§7in time. Please try again."
                );
                
                plugin.getLogger().warning(
                    "Captcha timeout: " + player.getName() + " (Reason: " + reason + ")"
                );
            }
        }, 30 * 20L); // 30 segundos
    }

    /**
     * Verifica si un jugador necesita resolver captcha
     */
    public boolean hasPendingCaptcha(Player player) {
        return activeChallenges.containsKey(player.getUniqueId());
    }

    /**
     * Genera un challenge aleatorio
     */
    private CaptchaChallenge generateChallenge(CaptchaReason reason) {
        CaptchaType type = CaptchaType.values()[random.nextInt(CaptchaType.values().length)];
        
        int a = random.nextInt(20) + 1;
        int b = random.nextInt(20) + 1;
        int answer;
        String question;

        switch (type) {
            case ADDITION:
                answer = a + b;
                question = a + " + " + b + " = ?";
                break;
                
            case SUBTRACTION:
                // Asegurar que no sea negativo
                if (a < b) {
                    int temp = a;
                    a = b;
                    b = temp;
                }
                answer = a - b;
                question = a + " - " + b + " = ?";
                break;
                
            case MULTIPLICATION:
                // Números más pequeños para multiplicación
                a = random.nextInt(10) + 1;
                b = random.nextInt(10) + 1;
                answer = a * b;
                question = a + " × " + b + " = ?";
                break;
                
            default:
                answer = a + b;
                question = a + " + " + b + " = ?";
                break;
        }

        return new CaptchaChallenge(question, answer, reason);
    }

    /**
     * Muestra el captcha al jugador
     */
    private void showCaptcha(Player player, CaptchaChallenge challenge) {
        player.sendMessage("");
        player.sendMessage("§c§l╔════════════════════════════════════╗");
        player.sendMessage("§c§l║   ⚠ VERIFICATION REQUIRED ⚠      ║");
        player.sendMessage("§c§l╠════════════════════════════════════╣");
        player.sendMessage("§7");
        player.sendMessage("§7  Please solve this simple math problem");
        player.sendMessage("§7  to verify you're not a bot:");
        player.sendMessage("§7");
        player.sendMessage("§e§l     " + challenge.question);
        player.sendMessage("§7");
        player.sendMessage("§7  Type your answer in chat");
        player.sendMessage("§7  You have §c30 seconds");
        player.sendMessage("§7  Attempts remaining: §a" + challenge.attemptsRemaining);
        player.sendMessage("§7");
        player.sendMessage("§8  Reason: " + challenge.reason.getDescription());
        player.sendMessage("§7");
        player.sendMessage("§c§l╚════════════════════════════════════╝");
        player.sendMessage("");

        // Sonido
        player.playSound(player.getLocation(), 
            org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);
    }

    /**
     * Listener para capturar respuestas en chat
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!activeChallenges.containsKey(uuid)) {
            return; // No tiene captcha pendiente
        }

        event.setCancelled(true); // Cancelar el mensaje normal

        String message = event.getMessage().trim();
        CaptchaChallenge challenge = activeChallenges.get(uuid);

        // Intentar parsear respuesta
        int answer;
        try {
            answer = Integer.parseInt(message);
        } catch (NumberFormatException e) {
            player.sendMessage("§c§l✖ §cPlease enter a valid number");
            return;
        }

        // Verificar respuesta
        if (answer == challenge.correctAnswer) {
            // ¡CORRECTO!
            activeChallenges.remove(uuid);
            
            player.sendMessage("");
            player.sendMessage("§a§l✔ VERIFICATION SUCCESSFUL");
            player.sendMessage("§7Thank you for verifying!");
            player.sendMessage("");
            
            player.playSound(player.getLocation(), 
                org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            
            plugin.getLogger().info(
                "Captcha solved: " + player.getName() + " (Reason: " + challenge.reason + ")"
            );
            
        } else {
            // INCORRECTO
            challenge.attemptsRemaining--;
            
            if (challenge.attemptsRemaining <= 0) {
                // Sin intentos - kickear
                activeChallenges.remove(uuid);
                
                player.kickPlayer(
                    "§c§l⚠ VERIFICATION FAILED ⚠\n\n" +
                    "§7You failed to solve the verification\n" +
                    "§7challenge. Please try again later."
                );
                
                plugin.getLogger().warning(
                    "Captcha failed: " + player.getName() + " (Reason: " + challenge.reason + ")"
                );
                
                // Considerar bloquear IP temporalmente
                String ip = player.getAddress().getAddress().getHostAddress();
                plugin.getBlacklistManager().blockIP(
                    ip, 
                    300, // 5 minutos
                    "Failed captcha verification", 
                    "SYSTEM"
                );
                
            } else {
                // Aún tiene intentos
                player.sendMessage("");
                player.sendMessage("§c§l✖ INCORRECT ANSWER");
                player.sendMessage("§7Please try again");
                player.sendMessage("§7Attempts remaining: §e" + challenge.attemptsRemaining);
                player.sendMessage("§7Question: §e§l" + challenge.question);
                player.sendMessage("");
                
                player.playSound(player.getLocation(), 
                    org.bukkit.Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f);
            }
        }
    }

    /**
     * Limpia un captcha (por ejemplo, al desconectarse)
     */
    public void clearCaptcha(UUID uuid) {
        activeChallenges.remove(uuid);
    }

    /**
     * Challenge de captcha
     */
    private static class CaptchaChallenge {
        final String question;
        final int correctAnswer;
        final CaptchaReason reason;
        int attemptsRemaining;

        CaptchaChallenge(String question, int correctAnswer, CaptchaReason reason) {
            this.question = question;
            this.correctAnswer = correctAnswer;
            this.reason = reason;
            this.attemptsRemaining = 3;
        }
    }

    /**
     * Tipo de captcha
     */
    private enum CaptchaType {
        ADDITION,
        SUBTRACTION,
        MULTIPLICATION
    }

    /**
     * Razón por la que se requiere captcha
     */
    public enum CaptchaReason {
        MULTIPLE_FAILED_LOGINS("Multiple failed login attempts"),
        SUSPICIOUS_IP("Login from suspicious IP address"),
        RATE_LIMIT_TRIGGERED("Too many requests"),
        BOT_BEHAVIOR("Suspicious bot-like behavior"),
        VPN_DETECTED("VPN or proxy detected"),
        ADMIN_REQUEST("Requested by administrator");

        private final String description;

        CaptchaReason(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
