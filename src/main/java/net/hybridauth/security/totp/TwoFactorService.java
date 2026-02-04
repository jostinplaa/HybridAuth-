package net.hybridauth.security.totp;

import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator;
import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.data.model.User;

import java.util.concurrent.CompletableFuture;

/**
 * Service to handle Two-Factor Authentication (TOTP).
 */
public class TwoFactorService {

    private final HybridAuthPlugin plugin;
    private final GoogleAuthenticator gAuth;

    public TwoFactorService(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.gAuth = new GoogleAuthenticator();
    }

    /**
     * Generates a new TOTP secret key for a user.
     * 
     * @return The secret key logic object
     */
    public GoogleAuthenticatorKey generateKey() {
        return gAuth.createCredentials();
    }

    /**
     * Validates a TOTP code against a secret.
     * 
     * @param secret The user's secret key
     * @param code   The code entered by the user
     * @return true if valid
     */
    public boolean authorize(String secret, int code) {
        return gAuth.authorize(secret, code);
    }

    /**
     * Get QR Code URL (using Google Charts API - simple way).
     * For better privacy/security, using a local QR generator library is preferred,
     * but GoogleAuth library helper uses Google Charts which is deprecated but
     * still works or alternatives.
     * Actually, GoogleAuthenticatorQRGenerator uses Google Charts API which is
     * deprecated.
     * We might want to construct the otpauth:// URI manually for clients to handle,
     * or use an external chart API.
     * 
     * Let's stick to returning the Secret + manual entry for now, or a simple
     * text-based QR if possible?
     * No, let's try to generate a valid otpauth URL.
     */
    public String getOtpAuthURL(String username, String secret) {
        return GoogleAuthenticatorQRGenerator.getOtpAuthTotpURL("HybridAuth", username,
                new GoogleAuthenticatorKey.Builder(secret).build());
    }
}
