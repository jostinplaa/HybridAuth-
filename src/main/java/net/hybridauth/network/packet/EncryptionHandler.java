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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

public class EncryptionHandler {

    private final HybridAuthPlugin plugin;
    private final MojangAPI mojangAPI;
    private final Set<String> premiumPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Cache<String, PremiumCheckResult> premiumCache;
    
    // Cache for checkMojangStatus (Compatibility)
    private final Cache<String, Boolean> premiumCheckCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

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
     * POLÍTICA DE PRIMERA CONEXIÓN (STABLE):
     * - Usuarios REGISTRADOS: Confía en la DB.
     * - Usuarios NUEVOS: NO verifica Mojang (trata como cracked).
     *   Esto obliga a registrarse, previniendo impostores.
     */
    private void checkPremiumStatus(String playerName) {
        String lowerName = playerName.toLowerCase();
        
        // 1. Verificar en cache
        PremiumCheckResult cached = premiumCache.getIfPresent(lowerName);
        if (cached != null) {
            if (cached.isPremium) {
                premiumPlayers.add(lowerName);
            }
            return;
        }

        // 2. Verificar SOLO en base de datos
        Optional<User> userOpt = plugin.getDatabaseManager().getUserDAO()
                .getUserByUsername(playerName);
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            boolean isPremium = user.isPremium();
            
            premiumCache.put(lowerName, new PremiumCheckResult(isPremium, user.getPremiumUuid()));
            
            if (isPremium) {
                premiumPlayers.add(lowerName);
                plugin.getLogger().info("[Encryption] Trusted Premium User: " + playerName);
            } else {
                plugin.getLogger().info("[Encryption] Trusted Cracked User: " + playerName);
            }
            return;
        }

        // 3. Usuario NUEVO -> Cracked (Fallback de Seguridad)
        // Obligatorio /register para reclamar el nombre
        plugin.getLogger().info("[Encryption] New User " + playerName + " -> Requires Registration (Security Policy)");
        premiumCache.put(lowerName, new PremiumCheckResult(false, null));
    }

    public boolean isPremium(String playerName) {
        return premiumPlayers.contains(playerName.toLowerCase());
    }

    public void clearPremiumStatus(String playerName) {
        premiumPlayers.remove(playerName.toLowerCase());
    }
    
    // Compatibility Method for RegisterCommand
    public CompletableFuture<Boolean> checkMojangStatus(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            Boolean cached = premiumCheckCache.getIfPresent(playerName.toLowerCase());
            if (cached != null) return cached;
            
            boolean result = mojangAPI.getPremiumUUID(playerName).isPresent();
            premiumCheckCache.put(playerName.toLowerCase(), result);
            return result;
        });
    }

    public void removePremiumStatus(String playerName) {
        premiumPlayers.remove(playerName.toLowerCase());
        premiumCache.invalidate(playerName.toLowerCase());
    }

    public void forcePremiumStatus(String playerName, UUID uuid) {
         premiumPlayers.add(playerName.toLowerCase());
         premiumCache.put(playerName.toLowerCase(), new PremiumCheckResult(true, uuid));
    }

    private static class PremiumCheckResult {
        final boolean isPremium;
        final UUID premiumUuid;

        PremiumCheckResult(boolean isPremium, UUID premiumUuid) {
            this.isPremium = isPremium;
            this.premiumUuid = premiumUuid;
        }
    }
}
