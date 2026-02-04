package net.hybridauth.data.model;

import java.sql.Timestamp;
import java.util.UUID;

public class User {
    private UUID uuid;
    private String username;
    private String passwordHash;
    private AuthType authType;
    private UUID premiumUuid;
    private String email;
    private Timestamp registeredAt;
    private Timestamp lastLoginDate;
    private String lastIp;
    private int totalLogins;
    private String status;
    private String totpSecret;
    private boolean isTotpEnabled;

    public enum AuthType {
        PREMIUM, CRACKED, BEDROCK
    }

    // Constructor required by pasos.txt and logic
    public User(UUID uuid, String username, AuthType authType) {
        this.uuid = uuid;
        this.username = username;
        this.authType = authType;
        this.registeredAt = new Timestamp(System.currentTimeMillis());
        this.totalLogins = 0;
        this.status = "ACTIVE";
    }

    // No-args constructor for flexibility if needed
    public User() {
    }

    // Getters and Setters
    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String hash) {
        this.passwordHash = hash;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public void setAuthType(AuthType type) {
        this.authType = type;
    }

    public UUID getPremiumUuid() {
        return premiumUuid;
    }

    public void setPremiumUuid(UUID uuid) {
        this.premiumUuid = uuid;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Timestamp getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Timestamp registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Timestamp getLastLoginDate() {
        return lastLoginDate;
    }

    public void setLastLoginDate(Timestamp time) {
        this.lastLoginDate = time;
    }

    public String getLastIp() {
        return lastIp;
    }

    public void setLastIp(String ip) {
        this.lastIp = ip;
    }

    public int getTotalLogins() {
        return totalLogins;
    }

    public void setTotalLogins(int totalLogins) {
        this.totalLogins = totalLogins;
    }

    public void incrementLogins() {
        this.totalLogins++;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isPremium() {
        return authType == AuthType.PREMIUM;
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    public boolean isTotpEnabled() {
        return isTotpEnabled;
    }

    public void setTotpEnabled(boolean totpEnabled) {
        isTotpEnabled = totpEnabled;
    }
}

