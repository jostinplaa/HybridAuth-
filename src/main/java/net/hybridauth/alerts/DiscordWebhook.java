package net.hybridauth.alerts;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Utility class to send messages to a Discord Webhook.
 * Handles simple JSON payload construction manually to avoid extra
 * dependencies.
 */
public class DiscordWebhook {

    private final String webhookUrl;

    public DiscordWebhook(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void send(String content, String username) {
        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.equals("YOUR_WEBHOOK_URL_HERE")) {
            return;
        }

        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "HybridAuth-Alerts");
            connection.setDoOutput(true);

            // Simple JSON construction
            // { "content": "message", "username": "name" }
            String json = String.format("{\"content\": \"%s\", \"username\": \"%s\"}",
                    escapeJson(content),
                    escapeJson(username));

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = json.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Read response code to ensure it was sent
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) {
                System.err.println("[HybridAuth] Failed to send Discord alert. Code: " + code);
            }

            connection.disconnect();

        } catch (Exception e) {
            net.hybridauth.HybridAuthPlugin.getPlugin(net.hybridauth.HybridAuthPlugin.class).getLogger()
                    .log(java.util.logging.Level.SEVERE, "Error in DiscordWebhook", e);
        }
    }

    /**
     * Escapes special characters for JSON strings.
     */
    private String escapeJson(String s) {
        if (s == null)
            return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (Character.isISOControl(c)) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int k = 0; k < 4 - hex.length(); k++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
