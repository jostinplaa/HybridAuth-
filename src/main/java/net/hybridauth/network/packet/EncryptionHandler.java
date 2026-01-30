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

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

/**
 * Handler simplificado de autenticación premium.
 * 
 * SISTEMA SIMPLE Y EFECTIVO:
 * 1. Verificar si el nombre es premium (API Mojang)
 * 2. Verificar si el UUID del jugador coincide con el UUID de Mojang
 * 3. Si coincide = Premium real → Auto-login
 * 4. Si NO coincide = Impostor → Obligar a registrarse
 * 
 * @version 2.1.0 (Fixed - obtiene UUID del paquete)
 */
public class EncryptionHandler {

    private final HybridAuthPlugin plugin;
    private final MojangAPI mojangAPI;

    // Lista de jugadores verificados como premium
    private final Set<String> verifiedPremiumPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Cache de verificaciones (para no spam a Mojang API)
    private final Cache<String, PremiumStatus> verificationCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(500)
            .build();

    /**
     * Resultado de verificación premium
     */
    private static class PremiumStatus {
        final boolean isPremium;
        final UUID mojangUUID;

        PremiumStatus(boolean isPremium, UUID mojangUUID) {
            this.isPremium = isPremium;
            this.mojangUUID = mojangUUID;
        }
    }

    public EncryptionHandler(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.mojangAPI = new MojangAPI();

        plugin.getLogger().info("✓ Premium Authentication System (UUID-based) loaded.");

        registerPacketListeners();
    }

    private void registerPacketListeners() {
        // Interceptar el paquete de login
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.LOWEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                // Evitar procesamiento duplicado
                if (event.getPacket().getMeta("auth_checked").isPresent()) {
                    return;
                }

                // ✅ CRÍTICO: Obtener GameProfile del paquete (tiene UUID y nombre)
                WrappedGameProfile profile = event.getPacket().getGameProfiles().read(0);

                if (profile == null) {
                    plugin.getLogger().warning("[Premium Check] No se pudo obtener GameProfile del paquete");
                    return;
                }

                String playerName = profile.getName();
                UUID clientUUID = profile.getUUID();

                if (playerName == null || playerName.isEmpty()) {
                    return;
                }

                // Cancelar el paquete original
                event.setCancelled(true);

                // Procesar async para no bloquear el thread principal
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    handleLoginStart(event, playerName, clientUUID);
                });
            }
        });
    }

    /**
     * Maneja el inicio de login de un jugador.
     * 
     * LÓGICA SIMPLE:
     * 1. Obtener UUID del cliente (del paquete)
     * 2. Verificar en Mojang API si ese nombre es premium
     * 3. Si es premium, obtener el UUID real de Mojang
     * 4. Comparar: UUID cliente == UUID Mojang?
     * - SÍ → Premium verificado
     * - NO → Impostor/Cracked
     */
    private void handleLoginStart(PacketEvent event, String playerName, UUID clientUUID) {
        try {
            plugin.getLogger().info("[Premium Check] Verificando: " + playerName + " (UUID: " + clientUUID + ")");

            // 1. Verificar en cache primero
            PremiumStatus cached = verificationCache.getIfPresent(playerName.toLowerCase());
            if (cached != null) {
                plugin.getLogger().info("[Premium Check] Usando resultado cacheado para " + playerName);
                processVerification(event, playerName, clientUUID, cached);
                return;
            }

            // 2. Verificar con Mojang API
            Optional<UUID> mojangUUID = mojangAPI.getPremiumUUID(playerName);

            if (mojangUUID.isEmpty()) {
                // NO es premium - nombre no existe en Mojang
                plugin.getLogger().info("[Premium Check] ✖ " + playerName + " NO es cuenta premium. Modo Cracked.");

                PremiumStatus status = new PremiumStatus(false, null);
                verificationCache.put(playerName.toLowerCase(), status);
                processVerification(event, playerName, clientUUID, status);
                return;
            }

            // 3. Es premium - verificar UUID
            UUID realMojangUUID = mojangUUID.get();

            plugin.getLogger().info("[Premium Check] " + playerName + " es cuenta premium en Mojang.");
            plugin.getLogger().info("[UUID Check] Cliente: " + clientUUID);
            plugin.getLogger().info("[UUID Check] Mojang:  " + realMojangUUID);

            boolean uuidMatch = clientUUID.equals(realMojangUUID);

            if (uuidMatch) {
                plugin.getLogger().info("[Premium Check] ✓ UUIDs coinciden - PREMIUM VERIFICADO");
            } else {
                plugin.getLogger().warning("[Premium Check] ✖ UUIDs NO coinciden - IMPOSTOR DETECTADO");
            }

            PremiumStatus status = new PremiumStatus(uuidMatch, realMojangUUID);
            verificationCache.put(playerName.toLowerCase(), status);
            processVerification(event, playerName, clientUUID, status);

        } catch (Exception e) {
            plugin.getLogger().severe("[Premium Check] Error verificando " + playerName + ":");
            e.printStackTrace();
            // En caso de error, permitir como cracked
            allowAsCracked(event.getPlayer(), playerName);
        }
    }

    /**
     * Procesa el resultado de la verificación
     */
    private void processVerification(PacketEvent event, String playerName, UUID clientUUID, PremiumStatus status) {
        if (status.isPremium) {
            // ✓ Es premium verificado - permitir auto-login
            plugin.getLogger().info("[Premium Check] ✓✓✓ " + playerName + " verificado como PREMIUM REAL");
            verifiedPremiumPlayers.add(playerName.toLowerCase());
            allowAsVerifiedPremium(event.getPlayer(), playerName);
        } else {
            // ✖ No es premium O es impostor
            if (status.mojangUUID != null && !clientUUID.equals(status.mojangUUID)) {
                // Es un impostor intentando usar nombre premium
                plugin.getLogger().warning("[Security Alert] ⚠ IMPOSTOR DETECTADO: " + playerName);
                plugin.getLogger().warning("[Security Alert] → Cliente UUID: " + clientUUID);
                plugin.getLogger().warning("[Security Alert] → Real UUID: " + status.mojangUUID);

                kickImpostor(event.getPlayer(), playerName);
            } else {
                // Simplemente no es premium
                plugin.getLogger().info("[Premium Check] " + playerName + " permitido en modo Cracked");
                verifiedPremiumPlayers.remove(playerName.toLowerCase());
                allowAsCracked(event.getPlayer(), playerName);
            }
        }
    }

    /**
     * Permite el login como premium verificado
     */
    private void allowAsVerifiedPremium(org.bukkit.entity.Player player, String username) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PacketContainer packet = ProtocolLibrary.getProtocolManager()
                    .createPacket(PacketType.Login.Client.START);
            packet.getStrings().write(0, username);
            packet.setMeta("auth_checked", true);

            try {
                ProtocolLibrary.getProtocolManager().receiveClientPacket(player, packet);
            } catch (Exception e) {
                plugin.getLogger().severe("[Error] Failed to allow premium login for " + username);
                e.printStackTrace();
            }
        });
    }

    /**
     * Permite el login como cracked (debe registrarse)
     */
    private void allowAsCracked(org.bukkit.entity.Player player, String username) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PacketContainer packet = ProtocolLibrary.getProtocolManager()
                    .createPacket(PacketType.Login.Client.START);
            packet.getStrings().write(0, username);
            packet.setMeta("auth_checked", true);

            try {
                ProtocolLibrary.getProtocolManager().receiveClientPacket(player, packet);
            } catch (Exception e) {
                plugin.getLogger().severe("[Error] Failed to allow cracked login for " + username);
                e.printStackTrace();
            }
        });
    }

    /**
     * Kickea a un impostor que intenta usar nombre premium
     */
    private void kickImpostor(org.bukkit.entity.Player player, String username) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.kickPlayer(
                    "§c§l⚠ IMPOSTOR DETECTADO ⚠\n\n" +
                            "§7El nombre §e" + username + " §7es una cuenta §aPREMIUM §7real.\n" +
                            "§7Tu UUID no coincide con el UUID de esa cuenta.\n\n" +
                            "§7Posibles razones:\n" +
                            "§7• Estás usando un launcher pirata\n" +
                            "§7• Intentas hacerte pasar por otra persona\n" +
                            "§7• Tu cuenta no es la verdadera\n\n" +
                            "§cSi eres el dueño real, usa el launcher oficial de Minecraft.\n" +
                            "§cSi no, elige otro nombre que no sea premium.");
        });
    }

    /**
     * Verifica si un jugador está marcado como premium
     */
    public boolean isPremium(String playerName) {
        return verifiedPremiumPlayers.contains(playerName.toLowerCase());
    }

    /**
     * Limpia el estado premium de un jugador
     */
    public void clearPremiumStatus(String playerName) {
        verifiedPremiumPlayers.remove(playerName.toLowerCase());
    }

    /**
     * Verifica el estado de una cuenta en Mojang (async)
     */
    public CompletableFuture<Boolean> checkMojangStatus(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            PremiumStatus cached = verificationCache.getIfPresent(playerName.toLowerCase());
            if (cached != null) {
                return cached.isPremium;
            }

            Optional<UUID> uuid = mojangAPI.getPremiumUUID(playerName);
            boolean isPremium = uuid.isPresent();

            verificationCache.put(playerName.toLowerCase(),
                    new PremiumStatus(isPremium, uuid.orElse(null)));

            return isPremium;
        });
    }

    /**
     * Obtiene estadísticas del sistema
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("verified_premium_players", verifiedPremiumPlayers.size());
        stats.put("cache_size", verificationCache.estimatedSize());
        return stats;
    }
}
