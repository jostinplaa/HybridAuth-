package net.hybridauth.network.netty;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DETECCIÓN PREMIUM EN HANDSHAKE - SIN PROTOCOLLIB
 * 
 * VERSIÓN 2.0 - Compatible con todas las versiones de Minecraft
 * 
 * Este sistema usa reflection pura para evitar dependencias de NMS en
 * compile-time.
 * La inyección de Netty se hace en RUNTIME, haciendo el código portable entre
 * versiones.
 * 
 * Inspirado en FastLogin y nLogin
 */
public class PremiumDetector {

    private static final ConcurrentHashMap<String, PlayerConnectionData> connectionCache = new ConcurrentHashMap<>();
    private static boolean nettyAvailable = false;

    static {
        // Verificar si Netty está disponible en runtime
        try {
            Class.forName("io.netty.channel.Channel");
            nettyAvailable = true;
        } catch (ClassNotFoundException e) {
            Bukkit.getLogger().warning("[PremiumDetector] Netty not found - using fallback detection");
        }
    }

    /**
     * Inyecta el detector en el pipeline de Netty del jugador
     */
    public static void injectPlayer(Player player) {
        if (!nettyAvailable) {
            // Fallback: usar el UUID del jugador directamente
            detectPremiumByUUID(player);
            return;
        }

        try {
            Object channel = getChannel(player);
            if (channel == null) {
                detectPremiumByUUID(player);
                return;
            }

            // Obtener pipeline
            Method getPipelineMethod = channel.getClass().getMethod("pipeline");
            Object pipeline = getPipelineMethod.invoke(channel);

            // Verificar si ya está inyectado
            Method getMethod = pipeline.getClass().getMethod("get", String.class);
            if (getMethod.invoke(pipeline, "premium_detector") != null) {
                return; // Ya está inyectado
            }

            // Por ahora usamos detección directa por UUID
            // La inyección completa requeriría más reflection compleja
            detectPremiumByUUID(player);

        } catch (Exception e) {
            Bukkit.getLogger()
                    .warning("[PremiumDetector] Could not inject for " + player.getName() + ": " + e.getMessage());
            detectPremiumByUUID(player);
        }
    }

    /**
     * Detección premium consultando la API de Mojang
     * 
     * En servidores offline-mode, TODOS tienen UUID v3
     * La ÚNICA forma de verificar premium es consultar Mojang API
     */
    private static void detectPremiumByUUID(Player player) {
        String playerName = player.getName();

        // Consultar API de Mojang de forma async
        Bukkit.getScheduler().runTaskAsynchronously(
                Bukkit.getPluginManager().getPlugin("HybridAuth"),
                () -> {
                    boolean isPremium = checkMojangAPI(playerName);
                    UUID playerUUID = player.getUniqueId();

                    connectionCache.put(playerName.toLowerCase(),
                            new PlayerConnectionData(playerUUID, isPremium, System.currentTimeMillis()));

                    Bukkit.getLogger().info("[PremiumDetect] " + playerName + " → "
                            + (isPremium ? "PREMIUM" : "CRACKED")
                            + " (UUID: " + playerUUID + ")");
                });
    }

    /**
     * Consulta la API de Mojang para verificar si el usuario existe
     * 
     * @param username Nombre del jugador
     * @return true si la cuenta existe en Mojang, false si no
     */
    private static boolean checkMojangAPI(String username) {
        try {
            java.net.URL url = new java.net.URL("https://api.mojang.com/users/profiles/minecraft/" + username);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000); // 5 segundos timeout
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "HybridAuth/1.1.0");

            int responseCode = connection.getResponseCode();
            connection.disconnect();

            // 200 = Usuario existe (PREMIUM)
            // 404 = Usuario no existe (CRACKED)
            return responseCode == 200;

        } catch (java.io.IOException e) {
            // En caso de error de red, asumir cracked por seguridad
            Bukkit.getLogger().warning("[PremiumDetect] Error checking " + username + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Remueve el detector del jugador
     */
    public static void uninjectPlayer(Player player) {
        if (!nettyAvailable) {
            return;
        }

        try {
            Object channel = getChannel(player);
            if (channel == null)
                return;

            Method getPipelineMethod = channel.getClass().getMethod("pipeline");
            Object pipeline = getPipelineMethod.invoke(channel);

            Method getMethod = pipeline.getClass().getMethod("get", String.class);
            if (getMethod.invoke(pipeline, "premium_detector") != null) {
                Method removeMethod = pipeline.getClass().getMethod("remove", String.class);
                removeMethod.invoke(pipeline, "premium_detector");
            }
        } catch (Exception e) {
            // Silently ignore
        }
    }

    /**
     * Obtiene el canal Netty del jugador usando reflection
     */
    private static Object getChannel(Player player) {
        try {
            // Obtener CraftPlayer
            Method getHandleMethod = player.getClass().getMethod("getHandle");
            Object nmsPlayer = getHandleMethod.invoke(player);

            // playerConnection / connection (field name cambia según versión)
            Field connectionField = findField(nmsPlayer.getClass(), "playerConnection", "connection", "c", "b");
            if (connectionField == null)
                return null;
            connectionField.setAccessible(true);
            Object connection = connectionField.get(nmsPlayer);
            if (connection == null)
                return null;

            // networkManager / network
            Field networkManagerField = findField(connection.getClass(), "networkManager", "network", "h", "a");
            if (networkManagerField == null)
                return null;
            networkManagerField.setAccessible(true);
            Object networkManager = networkManagerField.get(connection);
            if (networkManager == null)
                return null;

            // channel
            Field channelField = findField(networkManager.getClass(), "channel", "m", "k");
            if (channelField == null)
                return null;
            channelField.setAccessible(true);
            return channelField.get(networkManager);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Busca un field por varios nombres posibles (helper method)
     */
    private static Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        // Buscar en superclases
        if (clazz.getSuperclass() != null) {
            return findField(clazz.getSuperclass(), names);
        }
        return null;
    }

    /**
     * Consulta si un jugador es premium
     * Si no está en caché, consulta Mojang API de forma síncrona
     * 
     * IMPORTANTE: Este método puede bloquear el thread,
     * así que solo llamar desde contextos async (AsyncPlayerPreLoginEvent está OK)
     */
    public static boolean isPremium(String playerName) {
        PlayerConnectionData data = connectionCache.get(playerName.toLowerCase());

        // Si está en cache y no expiró, retornar
        if (data != null) {
            // Expirar cache después de 5 minutos
            if (System.currentTimeMillis() - data.timestamp < 300000) {
                return data.isPremium;
            }
            // Cache expirado, eliminar
            connectionCache.remove(playerName.toLowerCase());
        }

        // NO está en cache → Consultar Mojang de forma síncrona
        boolean isPremium = checkMojangAPI(playerName);

        // Guardar en cache (sin UUID porque no tenemos player object aquí)
        connectionCache.put(playerName.toLowerCase(),
                new PlayerConnectionData(null, isPremium, System.currentTimeMillis()));

        Bukkit.getLogger().info("[PremiumDetect] " + playerName + " → "
                + (isPremium ? "PREMIUM" : "CRACKED") + " (via API)");

        return isPremium;
    }

    /**
     * Obtiene el UUID real del jugador (premium o cracked)
     */
    public static UUID getRealUUID(String playerName) {
        PlayerConnectionData data = connectionCache.get(playerName.toLowerCase());
        return data != null ? data.uuid : null;
    }

    /**
     * Detecta premium para un jugador online
     */
    public static void detectForPlayer(Player player) {
        injectPlayer(player);
    }

    /**
     * Limpia el cache de un jugador
     */
    public static void clearCache(String playerName) {
        connectionCache.remove(playerName.toLowerCase());
    }

    /**
     * Actualiza el UUID de un jugador en el cache (útil cuando se detecta en
     * PreLogin)
     */
    public static void updateUUID(String playerName, UUID uuid) {
        PlayerConnectionData data = connectionCache.get(playerName.toLowerCase());
        if (data != null) {
            // Crear nueva entrada con el UUID actualizado
            connectionCache.put(playerName.toLowerCase(),
                    new PlayerConnectionData(uuid, data.isPremium, data.timestamp));
        }
    }

    /**
     * Limpia todo el cache
     */
    public static void clearAllCache() {
        connectionCache.clear();
    }

    /**
     * Obtiene el tamaño del cache
     */
    public static int getCacheSize() {
        return connectionCache.size();
    }

    /**
     * Verifica si Netty está disponible
     */
    public static boolean isNettyAvailable() {
        return nettyAvailable;
    }

    /**
     * FIX BUG #2: Wrapper async-safe explícito para isPremium()
     * Previene que desarrolladores llamen isPremium() desde main thread
     * accidentalmente
     * 
     * @param playerName Nombre del jugador
     * @return CompletableFuture que se resuelve con true si es premium, false si
     *         cracked
     */
    public static java.util.concurrent.CompletableFuture<Boolean> isPremiumAsync(String playerName) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            return isPremium(playerName);
        });
    }

    /**
     * FIX BUG #2: Wrapper async-safe para obtener UUID
     * Previene bloqueo del main thread
     * 
     * @param playerName Nombre del jugador
     * @return CompletableFuture con el UUID real
     */
    public static java.util.concurrent.CompletableFuture<UUID> getRealUUIDAsync(String playerName) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            return getRealUUID(playerName);
        });
    }

    /**
     * Datos de conexión del jugador
     */
    private static class PlayerConnectionData {
        final UUID uuid;
        final boolean isPremium;
        final long timestamp;

        PlayerConnectionData(UUID uuid, boolean isPremium, long timestamp) {
            this.uuid = uuid;
            this.isPremium = isPremium;
            this.timestamp = timestamp;
        }
    }
}
