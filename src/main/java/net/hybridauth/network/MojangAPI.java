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
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(API_URL + username).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            if (connection.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                String uuidStr = json.get("id").getAsString();

                // Format UUID with hyphens
                String formattedUUID = uuidStr.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");

                UUID uuid = UUID.fromString(formattedUUID);
                cache.put(username.toLowerCase(), uuid);
                return Optional.of(uuid);
            } else if (connection.getResponseCode() == 204) {
                // No content = Not premium (or name unused)
                return Optional.empty();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

}
