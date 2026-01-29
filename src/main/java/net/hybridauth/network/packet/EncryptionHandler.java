package net.hybridauth.network.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.data.model.User;
import net.hybridauth.network.MojangAPI;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class EncryptionHandler {

    private final HybridAuthPlugin plugin;
    private final MojangAPI mojangAPI;
    private final Set<String> premiumPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Cache<String, PremiumCheckResult> premiumCache;

    public EncryptionHandler(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.mojangAPI = new MojangAPI();

        // Cache de 30 minutos para resultados
        this.premiumCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(5000)
                .build();

        registerPacketListeners();
    }

    private void registerPacketListeners() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.LOWEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                
                // Obtener nombre del jugador
                String playerName = packet.getStrings().read(0);
                
                if (playerName == null || playerName.isEmpty()) {
                    return;
                }

                final String finalPlayerName = playerName;

                // Verificar async
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        checkPremiumStatus(finalPlayerName);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error checking premium for " + finalPlayerName + ": " + e.getMessage());
                    }
                });
            }
        });
    }

    /**
     * Verifica el estado premium de un jugador
     * 
     * POLÍTICA DE PRIMERA CONEXIÓN:
     * - Solo confía en usuarios YA REGISTRADOS en la base de datos
     * - Usuarios nuevos SIEMPRE deben registrarse primero
     * - NO verifica Mojang API para nuevos usuarios (evita conflictos)
     */
    private void checkPremiumStatus(String playerName) {
        String lowerName = playerName.toLowerCase();
        
        // 1. Verificar en cache primero
        PremiumCheckResult cached = premiumCache.getIfPresent(lowerName);
        if (cached != null) {
            if (cached.isPremium) {
                premiumPlayers.add(lowerName);
                plugin.getLogger().info("[Premium Cache] " + playerName + " - Premium: YES");
            } else {
                plugin.getLogger().info("[Premium Cache] " + playerName + " - Cracked (cached)");
            }
            return;
        }

        // 2. Verificar SOLO en base de datos (usuarios ya registrados)
        Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO()
                .getUserByUsername(playerName);
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            boolean isPremium = user.isPremium();
            
            // Cachear resultado de DB
            premiumCache.put(lowerName, new PremiumCheckResult(isPremium, user.getPremiumUuid()));
            
            if (isPremium) {
                premiumPlayers.add(lowerName);
                plugin.getLogger().info("[Premium DB] " + playerName + " - Premium: YES (UUID: " + user.getPremiumUuid() + ")");
            } else {
                plugin.getLogger().info("[Premium DB] " + playerName + " - Cracked (registered)");
            }
            return;
        }

        // 3. Usuario NUEVO - NO verificar Mojang API
        // 
        // RAZÓN: En online-mode=false, cualquiera puede usar cualquier nombre.
        // Si verificamos Mojang API, un cracked usando nombre "Notch" sería
        // detectado como premium, causando problemas.
        //
        // SOLUCIÓN: Usuarios nuevos SIEMPRE deben registrarse primero.
        // Una vez registrados, su tipo (PREMIUM/CRACKED) queda guardado en DB.
        
        plugin.getLogger().info("[Premium Check] " + playerName + " - New user (not in DB), will require /register or /login");
        
        // Cachear como "no verificado" para evitar spam de logs
        premiumCache.put(lowerName, new PremiumCheckResult(false, null));
    }

    /**
     * Verifica si un jugador está marcado como premium
     */
    public boolean isPremium(String playerName) {
        boolean result = premiumPlayers.contains(playerName.toLowerCase());
        plugin.getLogger().info("[isPremium Check] " + playerName + " - Result: " + result);
        return result;
    }

    /**
     * Limpia el estado premium de un jugador (llamar en disconnect)
     */
    public void clearPremiumStatus(String playerName) {
        premiumPlayers.remove(playerName.toLowerCase());
        plugin.getLogger().info("[Premium Clear] " + playerName + " - Status cleared");
    }

    /**
     * Verifica asíncronamente si un jugador tiene cuenta Premium en Mojang.
     * Útil para comandos de admin o verificación manual.
     * 
     * ADVERTENCIA: Esto solo verifica si el NOMBRE existe en Mojang,
     * NO si el jugador conectado es el dueño real de esa cuenta.
     */
    public CompletableFuture<Boolean> checkMojangStatus(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Verificar cache primero
                PremiumCheckResult cached = premiumCache.getIfPresent(playerName.toLowerCase());
                if (cached != null) {
                    return cached.isPremium;
                }

                // Consultar Mojang API
                Optional<UUID> premiumUUID = mojangAPI.getPremiumUUID(playerName);
                boolean exists = premiumUUID.isPresent();
                
                plugin.getLogger().info("[Mojang API Check] " + playerName + " - Exists: " + exists);
                
                return exists;
            } catch (Exception e) {
                plugin.getLogger().warning("Error checking Mojang API for " + playerName + ": " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * Marca manualmente a un jugador como premium (para comandos de admin)
     */
    public void forcePremiumStatus(String playerName, UUID premiumUuid) {
        String lowerName = playerName.toLowerCase();
        premiumPlayers.add(lowerName);
        premiumCache.put(lowerName, new PremiumCheckResult(true, premiumUuid));
        plugin.getLogger().info("[Force Premium] " + playerName + " - Marked as premium by admin");
    }

    /**
     * Remueve estado premium manualmente (para comandos de admin)
     */
    public void removePremiumStatus(String playerName) {
        String lowerName = playerName.toLowerCase();
        premiumPlayers.remove(lowerName);
        premiumCache.invalidate(lowerName);
        plugin.getLogger().info("[Remove Premium] " + playerName + " - Premium status removed by admin");
    }

    /**
     * Limpia todo el cache (útil para /reload)
     */
    public void clearCache() {
        premiumCache.invalidateAll();
        premiumPlayers.clear();
        plugin.getLogger().info("[Premium Cache] All caches cleared");
    }

    /**
     * Clase interna para almacenar resultados de verificación premium
     */
    private static class PremiumCheckResult {
        final boolean isPremium;
        final UUID premiumUuid;

        PremiumCheckResult(boolean isPremium, UUID premiumUuid) {
            this.isPremium = isPremium;
            this.premiumUuid = premiumUuid;
        }
    }
}
