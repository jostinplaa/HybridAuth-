package net.hybridauth.network.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.data.model.User;
import net.hybridauth.network.MojangAPI;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class EncryptionHandler {

    private final HybridAuthPlugin plugin;
    private final MojangAPI mojangAPI;
    private final Set<String> premiumPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Cache<String, Boolean> premiumCache;

    public EncryptionHandler(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.mojangAPI = new MojangAPI();

        // Cache de 1 hora para resultados de Mojang
        this.premiumCache = Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .maximumSize(5000)
                .build();

        registerPacketListeners();
    }

    private void registerPacketListeners() {
        // Intercept Login Start packet to detect Premium users
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.LOWEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                String playerName;
                try {
                    WrappedGameProfile profile = packet.getGameProfiles().read(0);
                    playerName = profile.getName();
                } catch (Exception e) {
                    playerName = packet.getStrings().read(0);
                }

                if (playerName == null)
                    return;

                // Verificar en cache primero
                Boolean cachedResult = premiumCache.getIfPresent(playerName.toLowerCase());
                if (cachedResult != null) {
                    if (cachedResult) {
                        premiumPlayers.add(playerName.toLowerCase());
                        plugin.getLogger().info("Premium (cached): " + playerName);
                    }
                    return;
                }

                // Verificar async para no bloquear Netty
                final String finalPlayerName = playerName;
                CompletableFuture.supplyAsync(() -> {
                    try {
                        // 1. Check DB
                        Optional<User> userOpt = HybridAuthPlugin.getInstance().getDatabaseManager().getUserDAO()
                                .getUserByUsername(finalPlayerName);
                        if (userOpt.isPresent()) {
                            return userOpt.get().isPremium();
                        }

                        // 2. Check Mojang API
                        // Esto ya no bloquea el hilo principal ni Netty
                        // boolean isPremium = mojangAPI.getPremiumUUID(finalPlayerName).isPresent();

                        // FIX: No podemos confiar solo en la API para auto-login en modo offline
                        // porque cualquiera puede usar el nombre de un usuario premium.
                        // Por seguridad, nuevos usuarios siempre deben registrarse/loguearse.
                        // Solo confiamos si ya está verificado en la base de datos.
                        boolean isPremium = false;

                        // Cachear resultado
                        premiumCache.put(finalPlayerName.toLowerCase(), isPremium);

                        return isPremium;

                    } catch (Exception e) {
                        plugin.getLogger()
                                .warning("Error checking premium: " + finalPlayerName + " - " + e.getMessage());
                        return false;
                    }
                }).thenAccept(isPremium -> {
                    if (isPremium) {
                        premiumPlayers.add(finalPlayerName.toLowerCase());
                        // Log only if actually premium (which is impossible now for new users, safe)
                    }
                });
            }
        });
    }

    public boolean isPremium(String playerName) {
        return premiumPlayers.contains(playerName.toLowerCase());
    }
}
