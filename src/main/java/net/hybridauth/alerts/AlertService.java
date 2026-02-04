package net.hybridauth.alerts;

/**
 * Interface for alert services (Discord, Email, etc.)
 */
public interface AlertService {

    /**
     * Sends an alert.
     * 
     * @param type    The type of event (e.g. BRUTE_FORCE, IMPOSTOR)
     * @param message The alert message
     * @param details Additional details (IP, User, etc.)
     */
    void sendAlert(AlertType type, String message, String details);

    enum AlertType {
        BRUTE_FORCE,
        IMPOSTOR,
        BLACKLIST,
        ADMIN_LOGIN
    }
}
