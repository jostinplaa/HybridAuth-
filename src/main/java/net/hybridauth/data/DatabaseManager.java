package net.hybridauth.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.hybridauth.HybridAuthPlugin;
import net.hybridauth.data.dao.UserDAO;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final HybridAuthPlugin plugin;
    private HikariDataSource dataSource;
    private UserDAO userDAO;

    public DatabaseManager(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        FileConfiguration config = plugin.getConfig();
        HikariConfig hikariConfig = new HikariConfig();

        String type = config.getString("database.type", "SQLITE");

        if (type.equalsIgnoreCase("MYSQL")) {
            hikariConfig.setJdbcUrl("jdbc:mysql://" +
                    config.getString("database.host", "localhost") + ":" +
                    config.getInt("database.port", 3306) + "/" +
                    config.getString("database.database", "hybridauth"));
            hikariConfig.setUsername(config.getString("database.username", "root"));
            hikariConfig.setPassword(config.getString("database.password", ""));
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        } else {
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + plugin.getDataFolder() + "/database.db");
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
        }

        hikariConfig.setPoolName("HybridAuthPool");
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setConnectionTimeout(30000);

        this.dataSource = new HikariDataSource(hikariConfig);

        try {
            createTables();
            this.userDAO = new UserDAO(this); // Initialize DAO
            plugin.getLogger().info("Database connected successfully!");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTables() throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Users table
            // Users table
            stmt.execute("CREATE TABLE IF NOT EXISTS hybrid_users (" +
                    "uuid CHAR(36) PRIMARY KEY, " +
                    "username VARCHAR(16) NOT NULL, " +
                    "password_hash VARCHAR(128), " +
                    "auth_type VARCHAR(20) NOT NULL, " +
                    "premium_uuid CHAR(36), " +
                    "email VARCHAR(255), " +
                    "registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_login_date TIMESTAMP NULL, " +
                    "last_ip VARCHAR(45), " +
                    "total_logins INT DEFAULT 0, " +
                    "status VARCHAR(20) " +
                    ");");

            // Index for username lookups
            try {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_username ON hybrid_users (username)");
            } catch (SQLException ignored) {
            }

            // Sessions table
            stmt.execute("CREATE TABLE IF NOT EXISTS hybrid_sessions (" +
                    "id INTEGER PRIMARY KEY "
                    + (dataSource.getJdbcUrl().contains("mysql") ? "AUTO_INCREMENT" : "AUTOINCREMENT") + ", " +
                    "user_uuid VARCHAR(36) NOT NULL, " +
                    "player_ip VARCHAR(45), " +
                    "login_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_activity TIMESTAMP, " +
                    "active BOOLEAN DEFAULT TRUE" +
                    ");");
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource is not initialized");
        }
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public UserDAO getUserDAO() {
        return userDAO;
    }
}
