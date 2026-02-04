package net.hybridauth.security.fingerprint;

import org.bukkit.entity.Player;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class ClientFingerprintService {

    public String generateFingerprint(Player player) {
        try {
            // Combining IP subnet (usually /24 equivalent for privacy/stability) and UUID
            // Ideally we would use more client-side data provided by plugins via channels
            // if available
            String ip = player.getAddress().getAddress().getHostAddress();
            String uuid = player.getUniqueId().toString();

            // Simple fingerprint base: IP + UUID
           
            String rawFingerprint = ip + ":" + uuid;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawFingerprint.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            net.hybridauth.HybridAuthPlugin.getPlugin(net.hybridauth.HybridAuthPlugin.class).getLogger()
                    .log(java.util.logging.Level.SEVERE, "Error in ClientFingerprintService", e);
            return "UNKNOWN";
        }
    }

    public boolean verifyFingerprint(Player player, String storedFingerprint) {
        if (storedFingerprint == null)
            return false;
        String current = generateFingerprint(player);
        return current.equals(storedFingerprint);
    }

    /**
     * FIX BUG #10: Detecta cambios en el fingerprint del jugador
     * Seal de cuenta comprometida o uso de proxy/VPN
     */
    public boolean hasFingerprintChanged(Player player, String storedFingerprint) {
        if (storedFingerprint == null) {
            return false; // Primera vez, no hay cambio
        }

        String currentFingerprint = generateFingerprint(player);
        return !currentFingerprint.equals(storedFingerprint);
    }
}
