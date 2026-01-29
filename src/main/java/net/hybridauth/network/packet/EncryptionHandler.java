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
    
    // RSA Keys
    private KeyPair keyPair;
    private final Random random = new Random();

    // Context Storage (IP -> Verification Context)
    private final ConcurrentHashMap<SocketAddress, VerificationContext> pendingContexts = new ConcurrentHashMap<>();
    
    // Cache for checkMojangStatus (Compatibility)
    private final Cache<String, Boolean> premiumCheckCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    private static class VerificationContext {
        final String username;
        final byte[] verifyToken;
        final long timestamp;
        
        // Timeout Task ID
        int timeoutTaskId = -1;

        VerificationContext(String username, byte[] verifyToken) {
            this.username = username;
            this.verifyToken = verifyToken;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public EncryptionHandler(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.mojangAPI = new MojangAPI();
        
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            this.keyPair = generator.generateKeyPair();
            plugin.getLogger().info("RSA KeyPair generated for secure handshake.");
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().severe("Could not generate RSA keys!");
            e.printStackTrace();
        }

        registerPacketListeners();
    }

    private void registerPacketListeners() {
        // 1. Intercept Login Start
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

        // 2. Intercept Encryption Response
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
        // Check if user is theoretically Premium
        Optional<UUID> mojangUUID = mojangAPI.getPremiumUUID(playerName);
        
        SocketAddress address = event.getPlayer().getAddress();
        
        // Clean previous context
        VerificationContext oldContext = pendingContexts.remove(address);
        if (oldContext != null && oldContext.timeoutTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(oldContext.timeoutTaskId);
        }

        if (mojangUUID.isPresent()) {
            plugin.getLogger().info("[Secure Auth] Challenge: " + playerName + " (Mojang Account Found)");
            
            byte[] verifyToken = new byte[4];
            random.nextBytes(verifyToken);
            
            VerificationContext context = new VerificationContext(playerName, verifyToken);
            
            // Schedule Timeout (10 seconds)
            context.timeoutTaskId = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                VerificationContext currentCtx = pendingContexts.get(address);
                if (currentCtx != null && currentCtx == context) {
                    plugin.getLogger().warning("[Secure Auth] Timeout for " + playerName + ". Fallback to cracked.");
                    pendingContexts.remove(address);
                    try {
                        fallbackToCracked(event.getPlayer(), playerName);
                    } catch (Exception e) {}
                }
            }, 200L).getTaskId();

            pendingContexts.put(address, context);

            // Send Encryption Request
            PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Login.Server.ENCRYPTION_BEGIN);
            packet.getStrings().write(0, ""); // Server ID
            
            // FIX: Use ByteArrays for Key and Token (Modern MC)
            // Index 0: Public Key (Encoded)
            // Index 1: Verify Token
            packet.getByteArrays().write(0, keyPair.getPublic().getEncoded());
            packet.getByteArrays().write(1, verifyToken);
            
            try {
                ProtocolLibrary.getProtocolManager().sendServerPacket(event.getPlayer(), packet);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send Encryption Request: " + e.getMessage());
                fallbackToCracked(event.getPlayer(), playerName);
            }
        } else {
            plugin.getLogger().info("[Secure Auth] Clean: " + playerName + " (No Mojang Account)");
            fallbackToCracked(event.getPlayer(), playerName);
        }
    }

    private void handleEncryptionResponse(PacketEvent event) {
        SocketAddress address = event.getPlayer().getAddress();
        VerificationContext context = pendingContexts.get(address);

        if (context == null) {
            return; 
        }
        
        // Cancel timeout
        if (context.timeoutTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(context.timeoutTaskId);
        }

        try {
            byte[] sharedSecretEncrypted = event.getPacket().getByteArrays().read(0);
            byte[] verifyTokenEncrypted = event.getPacket().getByteArrays().read(1);

            PrivateKey privateKey = keyPair.getPrivate();
            byte[] sharedSecret = decrypt(privateKey, sharedSecretEncrypted);
            byte[] verifyToken = decrypt(privateKey, verifyTokenEncrypted);

            if (!Arrays.equals(context.verifyToken, verifyToken)) {
                plugin.getLogger().warning("[Secure Auth] Token mismatch for " + context.username);
                fallbackToCracked(event.getPlayer(), context.username); 
                return;
            }

            // Compute Server Hash
            String serverHash = new java.math.BigInteger(digest("", keyPair.getPublic(), new SecretKeySpec(sharedSecret, "AES"))).toString(16);
            
            // Verify with Mojang
            Optional<com.google.gson.JsonObject> response = mojangAPI.checkSession(context.username, serverHash);
            
            if (response.isPresent()) {
                plugin.getLogger().info("[Secure Auth] SUCCESS: " + context.username + " verified!");
                premiumPlayers.add(context.username.toLowerCase());
                resendLoginStart(event.getPlayer(), context.username);
            } else {
                plugin.getLogger().warning("[Secure Auth] FAILED: " + context.username + " validation failed.");
                fallbackToCracked(event.getPlayer(), context.username);
            }

        } catch (Exception e) {
            e.printStackTrace();
            fallbackToCracked(event.getPlayer(), context.username);
        } finally {
            pendingContexts.remove(address);
        }
    }

    private void fallbackToCracked(org.bukkit.entity.Player player, String username) {
        premiumPlayers.remove(username.toLowerCase());
        resendLoginStart(player, username);
    }
    
    private void resendLoginStart(org.bukkit.entity.Player player, String username) {
        PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Login.Client.START);
        packet.getStrings().write(0, username);
        packet.setMeta("auth_checked", true); // MARKER
        
        try {
            ProtocolLibrary.getProtocolManager().receiveClientPacket(player, packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private byte[] decrypt(PrivateKey key, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    private byte[] digest(String serverId, PublicKey publicKey, SecretKey secretKey) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(serverId.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        digest.update(secretKey.getEncoded());
        digest.update(publicKey.getEncoded());
        return digest.digest();
    }

    public boolean isPremium(String playerName) {
        return premiumPlayers.contains(playerName.toLowerCase());
    }
    
    public void clearPremiumStatus(String playerName) {
        premiumPlayers.remove(playerName.toLowerCase());
        pendingContexts.values().removeIf(ctx -> ctx.username.equalsIgnoreCase(playerName));
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
}
