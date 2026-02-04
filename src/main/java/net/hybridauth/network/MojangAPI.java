package net.hybridauth.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MojangAPI {

    private static final String API_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private final ConcurrentMap<String, UUID> cache = new ConcurrentHashMap<>();

    public Optional<UUID> getPremiumUUID(String username) {
        // 1. Check cache
        if (cache.containsKey(username.toLowerCase())) {
            return Optional.of(cache.get(username.toLowerCase()));
        }

        // 2. Request to Mojang
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_URL + username).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                // try-with-resources para garantizar cierre del stream
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                    String uuidStr = json.get("id").getAsString();

                    // Format UUID with hyphens
                    String formattedUUID = uuidStr.replaceFirst(
                            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");

                    UUID uuid = UUID.fromString(formattedUUID);
                    cache.put(username.toLowerCase(), uuid);
                    return Optional.of(uuid);
                }
            }
            // 204 o cualquier otro cdigo = no premium
            return Optional.empty();

        } catch (Exception e) {
            net.hybridauth.HybridAuthPlugin.getPlugin(net.hybridauth.HybridAuthPlugin.class).getLogger()
                    .log(java.util.logging.Level.SEVERE, "Error in MojangAPI", e);
            return Optional.empty();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public Optional<JsonObject> checkSession(String username, String serverHash) {
        try {
            String url = "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=" + username
                    + "&serverId=" + serverHash;
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                return Optional.of(JsonParser.parseString(response.toString()).getAsJsonObject());
            }
        } catch (Exception e) {
            net.hybridauth.HybridAuthPlugin.getPlugin(net.hybridauth.HybridAuthPlugin.class).getLogger()
                    .log(java.util.logging.Level.SEVERE, "Error in MojangAPI", e);
        }
        return Optional.empty();
    }
}
