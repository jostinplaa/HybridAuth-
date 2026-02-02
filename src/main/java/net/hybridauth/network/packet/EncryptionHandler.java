package net.hybridauth.network.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.network.MojangAPI;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

/**
 * Handler simplificado de autenticación premium.
 * Version 2.2.0 (Impostor Warning Mode)
 */
public class EncryptionHandler {

    private final HybridAuthPlugin plugin;
    private final MojangAPI mojangAPI;

    // Lista de jugadores verificados como premium
    private final Set<String> verifiedPremiumPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Cache de verificaciones
    private final Cache<String, PremiumStatus> verificationCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(500)
            .build();

    private static class PremiumStatus {
        final boolean isPremium;
        final UUID mojangUUID;
        final boolean warningShown;

        PremiumStatus(boolean isPremium, UUID mojangUUID) {
            this(isPremium, mojangUUID, false);
        }

        PremiumStatus(boolean isPremium, UUID mojangUUID, boolean warningShown) {
            this.isPremium = isPremium;
            this.mojangUUID = mojangUUID;
            this.warningShown = warningShown;
        }
    }

    public EncryptionHandler(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.mojangAPI = new MojangAPI();
        plugin.getLogger().info("✓ Premium Authentication System (UUID-based v2.2) loaded.");
        registerPacketListeners();
    }

    private void registerPacketListeners() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.LOWEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.getPacket().getMeta("auth_checked").isPresent())
                    return;

                String playerName = null;
                UUID clientUUID = null;

                try {
                    if (event.getPacket().getGameProfiles().size() > 0) {
                        WrappedGameProfile profile = event.getPacket().getGameProfiles().read(0);
                        if (profile != null) {
                            playerName = profile.getName();
                            clientUUID = profile.getUUID();
                        }
                    }
                    if (playerName == null && event.getPacket().getStrings().size() > 0) {
                        playerName = event.getPacket().getStrings().read(0);
                    }
                    if (clientUUID == null && event.getPacket().getUUIDs().size() > 0) {
                        clientUUID = event.getPacket().getUUIDs().read(0);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[Premium Check] Failed to parse Login Packet: " + e.getMessage());
                    return;
                }

                if (playerName == null || playerName.isEmpty())
                    return;

                final UUID finalUUID = clientUUID;
                final String finalName = playerName;

                event.setCancelled(true);

                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    handleLoginStart(event, finalName, finalUUID);
                });
            }
        });
    }

    private void handleLoginStart(PacketEvent event, String playerName, UUID clientUUID) {
        try {
            plugin.getLogger().info("[Premium Check] Verificando: " + playerName + " (UUID: " + clientUUID + ")");

            PremiumStatus cached = verificationCache.getIfPresent(playerName.toLowerCase());
            if (cached != null) {
                plugin.getLogger().info("[Premium Check] Usando resultado cacheado para " + playerName);
                processVerification(event, playerName, clientUUID, cached);
                return;
            }

            Optional<UUID> mojangUUID = mojangAPI.getPremiumUUID(playerName);

            if (mojangUUID.isEmpty()) {
                plugin.getLogger().info("[Premium Check] ✖ " + playerName + " NO es cuenta premium. Modo Cracked.");
                PremiumStatus status = new PremiumStatus(false, null);
                verificationCache.put(playerName.toLowerCase(), status);
                processVerification(event, playerName, clientUUID, status);
                return;
            }

            UUID realMojangUUID = mojangUUID.get();
            boolean uuidMatch = clientUUID.equals(realMojangUUID);

            if (uuidMatch) {
                plugin.getLogger().info("[Premium Check] ✓ UUIDs coinciden - PREMIUM VERIFICADO");
            } else {
                plugin.getLogger().warning("[Premium Check] ✖ UUIDs NO coinciden - IMPOSTOR DETECTADO");
            }

            PremiumStatus status = new PremiumStatus(uuidMatch, realMojangUUID, false);
            verificationCache.put(playerName.toLowerCase(), status);
            processVerification(event, playerName, clientUUID, status);

        } catch (Exception e) {
            plugin.getLogger().severe("[Premium Check] Error verificando " + playerName + ":");
            e.printStackTrace();
            allowAsCracked(event.getPlayer(), playerName);
        }
    }

    private void processVerification(PacketEvent event, String playerName, UUID clientUUID, PremiumStatus status) {
        if (status.isPremium) {
            plugin.getLogger().info("[Premium Check] ✓✓✓ " + playerName + " verificado como PREMIUM REAL");
            verifiedPremiumPlayers.add(playerName.toLowerCase());
            allowAsVerifiedPremium(event.getPlayer(), playerName);
        } else {
            if (status.mojangUUID != null && !clientUUID.equals(status.mojangUUID)) {
                if (!status.warningShown) {
                    plugin.getLogger().warning("[Impostor Warning] Kicking " + playerName + " to show warning.");
                    PremiumStatus warnedStatus = new PremiumStatus(false, status.mojangUUID, true);
                    verificationCache.put(playerName.toLowerCase(), warnedStatus);
                    kickImpostor(event.getPlayer(), playerName);
                } else {
                    plugin.getLogger().warning("[Impostor Allowed] " + playerName + " acknowledged warning.");
                    verifiedPremiumPlayers.remove(playerName.toLowerCase());
                    allowAsCracked(event.getPlayer(), playerName);
                }
            } else {
                plugin.getLogger().info("[Premium Check] " + playerName + " permitido en modo Cracked");
                verifiedPremiumPlayers.remove(playerName.toLowerCase());
                allowAsCracked(event.getPlayer(), playerName);
            }
        }
    }

    private void allowAsVerifiedPremium(org.bukkit.entity.Player player, String username) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Login.Client.START);
            packet.getStrings().write(0, username);
            packet.setMeta("auth_checked", true);
            try {
                ProtocolLibrary.getProtocolManager().receiveClientPacket(player, packet);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void allowAsCracked(org.bukkit.entity.Player player, String username) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Login.Client.START);
            packet.getStrings().write(0, username);
            packet.setMeta("auth_checked", true);
            try {
                ProtocolLibrary.getProtocolManager().receiveClientPacket(player, packet);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void kickImpostor(org.bukkit.entity.Player player, String username) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.kickPlayer(
                    "§c§l⚠ ALERTA DE SEGURIDAD ⚠\n\n" +
                            "§eEl nombre §f" + username + " §epertenece a un usuario Premium.\n" +
                            "§7--------------------------------------------------\n" +
                            "§f§l¿QUIERES ENTRAR IGUAL?\n" +
                            "§7Te dejaremos pasar, pero deberás §nregistrarte§7.\n\n" +
                            "§4§l¡RIESGO TOTAL DE PÉRDIDA!\n" +
                            "§cSi el dueño original entra al servidor,\n" +
                            "§cRECUPERARÁ LA CUENTA Y PERDERÁS TODO.\n" +
                            "§7(Inventario, Dinero, Rangos, Casas...)\n" +
                            "§7--------------------------------------------------\n\n" +
                            "§a§l¡VUELVE A CONECTARTE SI ACEPTAS EL RIESGO!");
        });
    }

    public boolean isPremium(String playerName) {
        return verifiedPremiumPlayers.contains(playerName.toLowerCase());
    }

    public void clearPremiumStatus(String playerName) {
        verifiedPremiumPlayers.remove(playerName.toLowerCase());
    }

    public CompletableFuture<Boolean> checkMojangStatus(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            PremiumStatus cached = verificationCache.getIfPresent(playerName.toLowerCase());
            if (cached != null)
                return cached.isPremium;
            Optional<UUID> uuid = mojangAPI.getPremiumUUID(playerName);
            boolean isPremium = uuid.isPresent();
            verificationCache.put(playerName.toLowerCase(), new PremiumStatus(isPremium, uuid.orElse(null)));
            return isPremium;
        });
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("verified_premium_players", verifiedPremiumPlayers.size());
        stats.put("cache_size", verificationCache.estimatedSize());
        return stats;
    }

    public void clearCache() {
        verificationCache.invalidateAll();
        verifiedPremiumPlayers.clear();
        plugin.getLogger().info("[EncryptionHandler] Cache cleared manually.");
    }

    public void removePremiumStatus(String playerName) {
        clearPremiumStatus(playerName); // Alias
        verificationCache.invalidate(playerName.toLowerCase());
    }

    public void forcePremiumStatus(String playerName, UUID uuid) {
        verifiedPremiumPlayers.add(playerName.toLowerCase());
        verificationCache.put(playerName.toLowerCase(), new PremiumStatus(true, uuid));
    }
}
