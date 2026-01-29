package net.hybridauth.data.dao;

import net.hybridauth.data.DatabaseManager;
import net.hybridauth.data.model.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public class UserDAO {

    private final DatabaseManager dbManager;

    public UserDAO(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void createUser(User user) throws SQLException {
        String sql = "INSERT INTO hybrid_users (uuid, username, password_hash, auth_type, premium_uuid, last_ip, registered_at, last_login_date, total_logins, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getUuid().toString());
            stmt.setString(2, user.getUsername());
            stmt.setString(3, user.getPasswordHash());
            stmt.setString(4, user.getAuthType().name());
            stmt.setString(5, user.getPremiumUuid() != null ? user.getPremiumUuid().toString() : null);
            stmt.setString(6, user.getLastIp());
            stmt.setTimestamp(7, user.getRegisteredAt());
            stmt.setTimestamp(8, user.getLastLoginDate());
            stmt.setInt(9, user.getTotalLogins());
            stmt.setString(10, user.getStatus());
            stmt.executeUpdate();
        }
    }

    public Optional<User> getUserByUUID(UUID uuid) {
        String sql = "SELECT * FROM hybrid_users WHERE uuid = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public Optional<User> getUserByUsername(String username) {
        String sql = "SELECT * FROM hybrid_users WHERE username = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToUser(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    public void updateUser(User user) throws SQLException {
        String sql = "UPDATE hybrid_users SET password_hash = ?, auth_type = ?, premium_uuid = ?, last_ip = ?, last_login_date = ?, total_logins = ?, status = ? WHERE uuid = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getPasswordHash());
            stmt.setString(2, user.getAuthType().name());
            stmt.setString(3, user.getPremiumUuid() != null ? user.getPremiumUuid().toString() : null);
            stmt.setString(4, user.getLastIp());
            stmt.setTimestamp(5, user.getLastLoginDate());
            stmt.setInt(6, user.getTotalLogins());
            stmt.setString(7, user.getStatus());
            stmt.setString(8, user.getUuid().toString());
            stmt.executeUpdate();
        }
    }

    public void deleteUser(UUID uuid) throws SQLException {
        String sql = "DELETE FROM hybrid_users WHERE uuid = ?";
        try (Connection conn = dbManager.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.executeUpdate();
        }
    }

    private User mapResultSetToUser(ResultSet rs) throws SQLException {
        String premiumUuidStr = rs.getString("premium_uuid");
        User user = new User();
        user.setUuid(UUID.fromString(rs.getString("uuid")));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setAuthType(User.AuthType.valueOf(rs.getString("auth_type")));
        user.setPremiumUuid(premiumUuidStr != null ? UUID.fromString(premiumUuidStr) : null);
        user.setRegisteredAt(rs.getTimestamp("registered_at"));
        user.setLastLoginDate(rs.getTimestamp("last_login_date"));
        user.setLastIp(rs.getString("last_ip"));
        user.setTotalLogins(rs.getInt("total_logins"));
        user.setStatus(rs.getString("status"));
        return user;
    }
}
