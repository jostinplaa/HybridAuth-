package net.hybridauth.security.captcha;

import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.core.messages.MessageManager;
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
 * Caractersticas:
 * - Captcha matemtico simple (suma, resta)
 * - Se activa en situaciones sospechosas
 * - Timeout de 30 segundos
 * - 3 intentos mximos
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
            plugin.getLogger().info(" Captcha system enabled");
        }
    }

    /**
     * Requiere que el jugador resuelva un captcha
     */
    public void requireCaptcha(Player player, CaptchaReason reason) {
        if (!enabled)
            return;

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

                String kickMsg = plugin.getMessageManager().getMessage("captcha.timeout_kick");
                player.kickPlayer(kickMsg);

                plugin.getLogger().warning(
                        "Captcha timeout: " + player.getName() + " (Reason: " + reason + ")");
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
     * FIX BUG #9: Regenera el captcha para un jugador
     * til cuando falla y quiere intentar con otro desafo
     */
    public void regenerateCaptcha(Player player, CaptchaReason reason) {
        activeChallenges.remove(player.getUniqueId());
        requireCaptcha(player, reason);
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
                // Nmeros ms pequeos para multiplicacin
                a = random.nextInt(10) + 1;
                b = random.nextInt(10) + 1;
                answer = a * b;
                question = a + "  " + b + " = ?";
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
        String prompt = plugin.getMessageManager().getMessage("captcha.prompt",
                MessageManager.placeholder()
                        .add("question", challenge.question)
                        .add("attempts", String.valueOf(challenge.attemptsRemaining))
                        .add("reason", challenge.reason.getDescription())
                        .build());
        player.sendMessage(prompt);

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
            plugin.getMessageManager().send(player, "captcha.invalid_number");
            return;
        }

        // Verificar respuesta
        if (answer == challenge.correctAnswer) {
            // CORRECTO!
            activeChallenges.remove(uuid);

            // FIX CRTICO: Resetear rate limit
            try {
                java.net.InetSocketAddress addr = player.getAddress();
                if (addr != null && addr.getAddress() != null) {
                    plugin.getRateLimitService().resetLimit(addr.getAddress().getHostAddress());
                }
            } catch (Exception ignored) {
            }

            plugin.getMessageManager().send(player, "captcha.success");

            player.playSound(player.getLocation(),
                    org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

            plugin.getLogger().info(
                    "Captcha solved: " + player.getName() + " (Reason: " + challenge.reason + ")");

        } else {
            // INCORRECTO
            challenge.attemptsRemaining--;

            if (challenge.attemptsRemaining <= 0) {
                // Sin intentos - kickear
                activeChallenges.remove(uuid);

                String kickMsg = plugin.getMessageManager().getMessage("captcha.failed_kick");
                player.kickPlayer(kickMsg);

                plugin.getLogger().warning(
                        "Captcha failed: " + player.getName() + " (Reason: " + challenge.reason + ")");

                // Bloquear IP con NULL SAFETY
                try {
                    java.net.InetSocketAddress addr = player.getAddress();
                    if (addr != null && addr.getAddress() != null) {
                        plugin.getBlacklistManager().blockIP(
                                addr.getAddress().getHostAddress(),
                                300, "Failed captcha", "SYSTEM");
                    }
                } catch (Exception ignored) {
                }

            } else {
                // An tiene intentos
                plugin.getMessageManager().send(player, "captcha.failed",
                        MessageManager.placeholder()
                                .add("attempts", String.valueOf(challenge.attemptsRemaining))
                                .add("question", challenge.question)
                                .build());

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
     * Razn por la que se requiere captcha
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

