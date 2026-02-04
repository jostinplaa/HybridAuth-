package net.hybridauth.integrations.discord;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Sistema de notificaciones Discord via Webhook
 * 
 * Enva alertas en tiempo real sobre:
 * - Intentos de impostor
 * - IPs bloqueadas
 * - Intentos de brute force
 * - Logins sospechosos
 * - Registros premium
 * 
 * @version 1.2.0
 */
public class DiscordWebhook {

    private final HybridAuthPlugin plugin;
    private final String webhookUrl;
    private final boolean enabled;
    private final boolean notifyImpostor;
    private final boolean notifyBlacklist;
    private final boolean notifyBruteForce;
    private final boolean notifySuspicious;
    private final boolean notifyPremium;

    public DiscordWebhook(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();

        this.enabled = config.getBoolean("integrations.discord.enabled", false);
        this.webhookUrl = config.getString("integrations.discord.webhook-url", "");

        this.notifyImpostor = config.getBoolean("integrations.discord.notify.impostor-detected", true);
        this.notifyBlacklist = config.getBoolean("integrations.discord.notify.ip-blacklisted", true);
        this.notifyBruteForce = config.getBoolean("integrations.discord.notify.brute-force", true);
        this.notifySuspicious = config.getBoolean("integrations.discord.notify.suspicious-login", true);
        this.notifyPremium = config.getBoolean("integrations.discord.notify.premium-register", false);

        if (enabled && !webhookUrl.isEmpty()) {
            plugin.getLogger().info(" Discord webhook enabled");
        }
    }

    /**
     * Verifica si el webhook est configurado
     */
    public boolean isEnabled() {
        return enabled && !webhookUrl.isEmpty();
    }

    /**
     * Alerta de impostor detectado
     */
    public void notifyImpostor(String playerName, String ip, String expectedUUID, String actualUUID) {
        if (!isEnabled() || !notifyImpostor)
            return;

        String description = String.format(
                "** IMPOSTOR DETECTED **\n\n" +
                        "**Player:** `%s`\n" +
                        "**IP:** `%s`\n" +
                        "**Expected UUID:** `%s`\n" +
                        "**Actual UUID:** `%s`\n\n" +
                        "**Action:** Player kicked and IP auto-blacklisted for 1 hour",
                playerName, ip, expectedUUID, actualUUID);

        sendEmbed(" IMPOSTOR ALERT", description, 0xFF0000); // Rojo
    }

    /**
     * Alerta de IP blacklisteada
     */
    public void notifyIPBlacklisted(String ip, String reason, String blockedBy, boolean permanent) {
        if (!isEnabled() || !notifyBlacklist)
            return;

        String type = permanent ? "PERMANENT" : "TEMPORARY";
        String emoji = permanent ? "" : "";

        String description = String.format(
                "%s **IP BLACKLISTED (%s)**\n\n" +
                        "**IP:** `%s`\n" +
                        "**Reason:** %s\n" +
                        "**Blocked by:** %s",
                emoji, type, ip, reason, blockedBy);

        int color = permanent ? 0x8B0000 : 0xFF8C00; // Rojo oscuro o naranja
        sendEmbed(" Security Alert", description, color);
    }

    /**
     * Alerta de intento de brute force
     */
    public void notifyBruteForce(String playerName, String ip, int attempts) {
        if (!isEnabled() || !notifyBruteForce)
            return;

        String description = String.format(
                "** BRUTE FORCE ATTEMPT**\n\n" +
                        "**Player:** `%s`\n" +
                        "**IP:** `%s`\n" +
                        "**Failed attempts:** %d\n\n" +
                        "**Action:** IP temporarily blocked",
                playerName, ip, attempts);

        sendEmbed(" Brute Force Alert", description, 0xFFA500); // Naranja
    }

    /**
     * Alerta de login sospechoso
     */
    public void notifySuspiciousLogin(String playerName, String ip, String reason) {
        if (!isEnabled() || !notifySuspicious)
            return;

        String description = String.format(
                "** SUSPICIOUS LOGIN DETECTED**\n\n" +
                        "**Player:** `%s`\n" +
                        "**IP:** `%s`\n" +
                        "**Reason:** %s",
                playerName, ip, reason);

        sendEmbed(" Suspicious Activity", description, 0xFFFF00); // Amarillo
    }

    /**
     * Notificacin de registro premium (opcional)
     */
    public void notifyPremiumRegister(String playerName, String uuid) {
        if (!isEnabled() || !notifyPremium)
            return;

        String description = String.format(
                "** NEW PREMIUM PLAYER**\n\n" +
                        "**Player:** `%s`\n" +
                        "**UUID:** `%s`\n" +
                        "**Status:** Auto-registered as premium",
                playerName, uuid);

        sendEmbed(" Premium Register", description, 0x00FF00); // Verde
    }

    /**
     * Notificacin de IP desbloqueada
     */
    public void notifyIPUnblocked(String ip, String unblockedBy) {
        if (!isEnabled() || !notifyBlacklist)
            return;

        String description = String.format(
                "** IP UNBLOCKED**\n\n" +
                        "**IP:** `%s`\n" +
                        "**Unblocked by:** %s",
                ip, unblockedBy);

        sendEmbed(" IP Unblocked", description, 0x00FF00); // Verde
    }

    /**
     * Enva un embed al webhook
     */
    @SuppressWarnings("unchecked")
    private void sendEmbed(String title, String description, int color) {
        CompletableFuture.runAsync(() -> {
            try {
                JSONObject embed = new JSONObject();
                embed.put("title", title);
                embed.put("description", description);
                embed.put("color", color);
                embed.put("timestamp", Instant.now().toString());

                JSONObject footer = new JSONObject();
                footer.put("text", "HybridAuth Security System");
                embed.put("footer", footer);

                JSONArray embeds = new JSONArray();
                embeds.add(embed);

                JSONObject payload = new JSONObject();
                payload.put("embeds", embeds);
                payload.put("username", "HybridAuth Security");
                payload.put("avatar_url", "https://i.imgur.com/AfFp7pu.png"); // Logo de seguridad

                sendWebhook(payload.toJSONString());

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
            }
        });
    }

    /**
     * Enva el webhook HTTP POST
     */
    private void sendWebhook(String jsonPayload) {
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "HybridAuth/1.2.0");
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();

            if (responseCode != 204 && responseCode != 200) {
                plugin.getLogger().warning(
                        "Discord webhook failed with code: " + responseCode);
            }

            connection.disconnect();

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
        }
    }

    /**
     * Enva mensaje simple (sin embed)
     */
    @SuppressWarnings("unchecked")
    public void sendSimpleMessage(String message) {
        if (!isEnabled())
            return;

        CompletableFuture.runAsync(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("content", message);
                payload.put("username", "HybridAuth Security");

                sendWebhook(payload.toJSONString());

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send Discord message: " + e.getMessage());
            }
        });
    }
}
