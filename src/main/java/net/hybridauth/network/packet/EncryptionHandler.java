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

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class EncryptionHandler {

    private final HybridAuthPlugin plugin;
    private final MojangAPI mojangAPI;
    private final Set<String> premiumPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public EncryptionHandler(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.mojangAPI = new MojangAPI();
        registerPacketListeners();
    }

    private void registerPacketListeners() {
        // Intercept Login Start packet to detect Premium users
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.LOWEST, PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                // 1.20.2+ Login Start packet changes: GameProfile might not be populated yet or
                // index changed.
                // Using getStrings().read(0) is safer for getting the Name.
                String playerName;
                try {
                    WrappedGameProfile profile = packet.getGameProfiles().read(0);
                    playerName = profile.getName();
                } catch (Exception e) {
                    // Fallback for newer versions where GameProfile isn't at index 0 or not fully
                    // constructed
                    playerName = packet.getStrings().read(0);
                }

                if (playerName == null)
                    return;

                // IMPORTANT: This happens on Netty thread, must be fast or async.
                // However, Login Start blocking is tricky.
                // Ideal flow:
                // 1. Check DB synchronously (fast if cached/Hikari)
                // 2. If known status -> act
                // 3. If unknown -> pause login, check API async, resume (Complex)

                // For this implementation v1.0, we will assume:
                // If user is in DB as Premium -> Force Encryption (mark as premium)
                // If user is NOT in DB -> Check API (blocking for 1st join is acceptable-ish or
                // should be async pre-login)

                // NOTE: Real production plugins use AsyncLogin event, but ProtocolLib gives us
                // packet access.

                try {
                    boolean isPremium = false;

                    // 1. Check DB
                    Optional<User> userOpt = HybridAuthPlugin.getInstance().getDatabaseManager().getUserDAO()
                            .getUserByUsername(playerName);
                    if (userOpt.isPresent()) {
                        isPremium = userOpt.get().isPremium();
                    } else {
                        // 2. Check Mojang API (First Join)
                        // WARNING: Blocking thread here for API call.
                        // In high-performance scenarios, this should be async with packet holding.
                        isPremium = mojangAPI.getPremiumUUID(playerName).isPresent();
                    }

                    if (isPremium) {
                        premiumPlayers.add(playerName.toLowerCase());
                        plugin.getLogger().info("Detected Premium User: " + playerName);
                        // Here we would initiate EncryptionRequest if we were controlling full
                        // handshake
                        // For now, we flag them so LoginListener knows they are Premium
                    }

                } catch (Exception e) {
                    plugin.getLogger()
                            .warning("Error checking premium status for " + playerName + ": " + e.getMessage());
                }
            }
        });
    }

    public boolean isPremium(String playerName) {
        return premiumPlayers.contains(playerName.toLowerCase());
    }
}
