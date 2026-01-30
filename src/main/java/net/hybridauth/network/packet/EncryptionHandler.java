package net.hybridauth.network.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.network.MojangAPI;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

public class EncryptionHandler {

    private final HybridAuthPlugin plugin;
    private final MojangAPI mojangAPI;
    private final Set<String> premiumPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // IP-based Verified Cache (The "VIP List")
    private final Cache<String, String> verifiedIPs = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    // RSA Keys
    private KeyPair keyPair;
    private final Random random = new Random();

    // Context Storage
    private final ConcurrentHashMap<SocketAddress, VerificationContext> pendingContexts = new ConcurrentHashMap<>();

    // Compatibility Cache
    private final Cache<String, Boolean> premiumCheckCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private static class VerificationContext {
        final String username;
        final byte[] verifyToken;
        final int timeoutTaskId;

        VerificationContext(String username, byte[] verifyToken, int timeoutTaskId) {
            this.username = username;
            this.verifyToken = verifyToken;
            this.timeoutTaskId = timeoutTaskId;
        }
    }

    public EncryptionHandler(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.mojangAPI = new MojangAPI();

        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            this.keyPair = generator.generateKeyPair();
            plugin.getLogger().info("✓ RSA KeyPair generated for Premium Authentication.");
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().severe("Could not generate RSA keys!");
            e.printStackTrace();
        }

        registerPacketListeners();
    }

    private void registerPacketListeners() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.LOWEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (event.getPacket().getMeta("auth_checked").isPresent()) {
                    return;
                }

                String playerName = event.getPacket().getStrings().read(0);
                if (playerName == null || playerName.isEmpty())
                    return;

                event.setCancelled(true);

                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    handleLoginStart(event, playerName);
                });
            }
        });

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.LOWEST, PacketType.Login.Client.ENCRYPTION_BEGIN) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                event.setCancelled(true);

                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    handleEncryptionResponse(event);
                });
            }
        });
    }

    private void handleLoginStart(PacketEvent event, String playerName) {
        String ip = event.getPlayer().getAddress().getAddress().getHostAddress();

        // 1. SMART RECONNECT CHECK
        String verifiedUser = verifiedIPs.getIfPresent(ip);
        if (verifiedUser != null && verifiedUser.equalsIgnoreCase(playerName)) {
            plugin.getLogger()
                    .info("[Smart Reconnect] " + playerName + " verified by IP cache. Granting Premium Access.");
            premiumPlayers.add(playerName.toLowerCase());
            resendLoginStart(event.getPlayer(), playerName);
            return;
        }

        // 2. Check if user is Premium Name
        Optional<UUID> mojangUUID = mojangAPI.getPremiumUUID(playerName);

        SocketAddress address = event.getPlayer().getAddress();
        cleanupContext(address);

        if (mojangUUID.isPresent()) {
            plugin.getLogger().info("[Secure Auth] " + playerName + " is a Premium Name. Challenging...");

            byte[] verifyToken = new byte[4];
            random.nextBytes(verifyToken);

            // Timeout Task
            int taskId = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                VerificationContext currentCtx = pendingContexts.get(address);
                if (currentCtx != null && currentCtx.username.equals(playerName)) {
                    pendingContexts.remove(address);
                    try {
                        event.getPlayer().kickPlayer("§cAuthentication Timeout.\n§7Impostor detection.");
                    } catch (Exception e) {
                    }
                }
            }, 300L).getTaskId();

            VerificationContext context = new VerificationContext(playerName, verifyToken, taskId);
            pendingContexts.put(address, context);

            PacketContainer packet = ProtocolLibrary.getProtocolManager()
                    .createPacket(PacketType.Login.Server.ENCRYPTION_BEGIN);
            packet.getStrings().write(0, "");
            packet.getByteArrays().write(0, keyPair.getPublic().getEncoded());
            packet.getByteArrays().write(1, verifyToken);

            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(event.getPlayer(), packet);
            } catch (Exception e) {
                plugin.getLogger().warning("[Secure Auth] Failed to send encryption packet to " + playerName
                        + ". Falling back to cracked.");
                fallbackToCracked(event.getPlayer(), playerName);
            }
        } else {
            plugin.getLogger().info("[Auth] " + playerName + " is NOT a Premium Name. Allowing registration.");
            fallbackToCracked(event.getPlayer(), playerName);
        }
    }

    private void handleEncryptionResponse(PacketEvent event) {
        SocketAddress address = event.getPlayer().getAddress();
        VerificationContext context = pendingContexts.get(address);

        if (context == null) {
            event.getPlayer().kickPlayer("§cInvalid session.");
            return;
        }

        if (context.timeoutTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(context.timeoutTaskId);
        }

        try {
            byte[] sharedSecretEncrypted = event.getPacket().getByteArrays().read(0);
            byte[] verifyTokenEncrypted = event.getPacket().getByteArrays().read(1);

            PrivateKey privateKey = keyPair.getPrivate();
            byte[] sharedSecret = decrypt(privateKey, sharedSecretEncrypted);
            byte[] verifyToken = decrypt(privateKey, verifyTokenEncrypted);

            // Validate Shared Secret Length
            if (sharedSecret.length != 16) {
                plugin.getLogger().warning("[Secure Auth] SharedSecret length is " + sharedSecret.length
                        + " (Expected 16). Decryption Error?");
                event.getPlayer().kickPlayer("§cEncryption Error (Invalid SharedSecret).");
                return;
            }

            // Validate Verify Token
            if (!Arrays.equals(context.verifyToken, verifyToken)) {
                plugin.getLogger().warning("[Secure Auth] VerifyToken mismatch for " + context.username);
                event.getPlayer().kickPlayer("§cVerification Failed (Wrong Key).");
                return;
            }

            // ✅ FIX CRÍTICO: Usar el método correcto para generar el hash
            String serverHash = generateMinecraftHash("", keyPair.getPublic(), new SecretKeySpec(sharedSecret, "AES"));

            plugin.getLogger().info("[Debug] Generated serverHash for " + context.username + ": " + serverHash);

            // Verify with Mojang
            Optional<com.google.gson.JsonObject> response = mojangAPI.checkSession(context.username, serverHash);

            if (response.isPresent()) {
                String ip = event.getPlayer().getAddress().getAddress().getHostAddress();
                plugin.getLogger().info("[✓] " + context.username + " VERIFIED as Premium! Whitelisting IP: " + ip);

                verifiedIPs.put(ip, context.username);
                premiumPlayers.add(context.username.toLowerCase());

                // IMPORTANTE: En vez de kickear, dejar pasar al jugador
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    resendLoginStart(event.getPlayer(), context.username);
                });

            } else {
                plugin.getLogger()
                        .warning("[✖] " + context.username + " validation FAILED (Code 204). Hash: " + serverHash);
                plugin.getLogger().warning("[✖] This means the client didn't authenticate properly with Mojang.");
                event.getPlayer().kickPlayer(
                        "§c§lVerificación Fallida\n\n§7No pudimos verificar tu sesión con Mojang.\n§7Posibles causas:\n§7• Tu launcher no es oficial\n§7• La cuenta no es premium\n§7• Problemas de conexión");
            }

        } catch (Exception e) {
            plugin.getLogger().severe("[Secure Auth] Exception during encryption handling:");
            e.printStackTrace();
            event.getPlayer().kickPlayer("§cHandshake Error.");
        } finally {
            pendingContexts.remove(address);
        }
    }

    private void cleanupContext(SocketAddress address) {
        VerificationContext old = pendingContexts.remove(address);
        if (old != null && old.timeoutTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(old.timeoutTaskId);
        }
    }

    private void fallbackToCracked(org.bukkit.entity.Player player, String username) {
        premiumPlayers.remove(username.toLowerCase());
        resendLoginStart(player, username);
    }

    private void resendLoginStart(org.bukkit.entity.Player player, String username) {
        PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Login.Client.START);
        packet.getStrings().write(0, username);
        packet.setMeta("auth_checked", true);

        try {
            ProtocolLibrary.getProtocolManager().receiveClientPacket(player, packet);
        } catch (Exception e) {
            plugin.getLogger().severe("[Error] Failed to resend login packet for " + username);
            e.printStackTrace();
        }
    }

    private byte[] decrypt(PrivateKey key, byte[] data) throws Exception {
        // Enforce PKCS1Padding for Minecraft Compatibility
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    /**
     * ✅ MÉTODO CORREGIDO: Genera el hash de servidor compatible con Minecraft.
     * 
     * Este hash DEBE ser hexadecimal con SIGNO (puede ser negativo).
     * El método anterior usaba toString(16) que no maneja el signo correctamente.
     * 
     * @param serverId  El server ID (normalmente vacío "")
     * @param publicKey La clave pública RSA del servidor
     * @param secretKey La clave secreta compartida (AES)
     * @return Hash hexadecimal con signo compatible con Mojang
     */
    private String generateMinecraftHash(String serverId, PublicKey publicKey, SecretKey secretKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(serverId.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            digest.update(secretKey.getEncoded());
            digest.update(publicKey.getEncoded());

            byte[] hash = digest.digest();

            // ✅ CRÍTICO: Usar BigInteger CON SIGNO (1 indica signo)
            java.math.BigInteger bigInt = new java.math.BigInteger(1, hash);
            String result = bigInt.toString(16);

            // ✅ CRÍTICO: Si el hash es negativo, manejar el signo
            // Verificar si el primer bit del hash es 1 (negativo en complemento a 2)
            if ((hash[0] & 0x80) == 0x80) {
                // Hash negativo - aplicar complemento a 2 manualmente
                bigInt = new java.math.BigInteger(hash);
                result = bigInt.toString(16);
            }

            return result;
        } catch (Exception e) {
            plugin.getLogger().severe("[Error] Failed to generate Minecraft hash:");
            e.printStackTrace();
            return "";
        }
    }

    public boolean isPremium(String playerName) {
        return premiumPlayers.contains(playerName.toLowerCase());
    }

    public void clearPremiumStatus(String playerName) {
        premiumPlayers.remove(playerName.toLowerCase());
    }

    public CompletableFuture<Boolean> checkMojangStatus(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            Boolean cached = premiumCheckCache.getIfPresent(playerName.toLowerCase());
            if (cached != null)
                return cached;
            boolean result = mojangAPI.getPremiumUUID(playerName).isPresent();
            premiumCheckCache.put(playerName.toLowerCase(), result);
            return result;
        });
    }
}
