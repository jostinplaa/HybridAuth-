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
            plugin.getLogger().info("RSA KeyPair generated for Reconnect Protocol.");
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
                if (playerName == null || playerName.isEmpty()) return;

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
            plugin.getLogger().info("[Smart Reconnect] " + playerName + " verified by IP cache. Granting Premium Access.");
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
                    } catch (Exception e) {}
                }
            }, 300L).getTaskId();

            VerificationContext context = new VerificationContext(playerName, verifyToken, taskId);
            pendingContexts.put(address, context);

            PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Login.Server.ENCRYPTION_BEGIN);
            packet.getStrings().write(0, ""); 
            packet.getByteArrays().write(0, keyPair.getPublic().getEncoded());
            packet.getByteArrays().write(1, verifyToken);
            
            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(event.getPlayer(), packet);
            } catch (Exception e) {
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

            // Debug Shared Secret
            if (sharedSecret.length != 16) {
                 plugin.getLogger().warning("[Secure Auth] SharedSecret length is " + sharedSecret.length + " (Expected 16). Decryption Error?");
            }

            if (!Arrays.equals(context.verifyToken, verifyToken)) {
                event.getPlayer().kickPlayer("§cVerification Failed (Wrong Key).");
                return;
            }

            String serverHash = getHash("", keyPair.getPublic(), new SecretKeySpec(sharedSecret, "AES"));
            
            // Verify with Mojang
            Optional<com.google.gson.JsonObject> response = mojangAPI.checkSession(context.username, serverHash);
            
            if (response.isPresent()) {
                String ip = event.getPlayer().getAddress().getAddress().getHostAddress();
                plugin.getLogger().info("[Smart Reconnect] " + context.username + " VERIFIED! Whitelisting IP: " + ip);
                
                verifiedIPs.put(ip, context.username); 
                
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                   event.getPlayer().kickPlayer("§a§l¡IDENTIDAD VERIFICADA! :)\n\n§ePor favor, entra de nuevo\n§epara acceder automáticamente.");
                });
                
            } else {
                plugin.getLogger().warning("[Secure Auth] FAILED: " + context.username + " validation failed (Code 204). Hash: " + serverHash);
                event.getPlayer().kickPlayer("§cError verificando sesión con Mojang.\n§7Asegúrate de ser el dueño real de la cuenta.");
            }

        } catch (Exception e) {
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
            e.printStackTrace();
        }
    }

    private byte[] decrypt(PrivateKey key, byte[] data) throws Exception {
        // Enforce PKCS1Padding for Minecraft Compatibility
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    private String getHash(String serverId, PublicKey publicKey, SecretKey secretKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(serverId.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            digest.update(secretKey.getEncoded());
            digest.update(publicKey.getEncoded());
            return new java.math.BigInteger(digest.digest()).toString(16);
        } catch (Exception e) {
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
            if (cached != null) return cached;
            boolean result = mojangAPI.getPremiumUUID(playerName).isPresent();
            premiumCheckCache.put(playerName.toLowerCase(), result);
            return result;
        });
    }
}
