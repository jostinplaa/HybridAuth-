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

    public boolean initialize() {
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

        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            createTables();
            this.userDAO = new UserDAO(this); // Initialize DAO
            plugin.getLogger().info("Database connected successfully!");
            return true;
        } catch (Exception e) { // Catch Hikari/SQL exceptions
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error in DatabaseManager", );
            return false;
        }
    }

    private void createTables() throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            boolean isMySQL = dataSource.getJdbcUrl().contains("mysql");

            if (isMySQL) {
                createTablesMySQL(stmt);
            } else {
                createTablesSQLite(stmt);
            }

            plugin.getLogger().info("Database tables verified for: " + (isMySQL ? "MySQL" : "SQLite"));
        }
    }

    private void createTablesMySQL(Statement stmt) throws SQLException {
        // Users table - MySQL
        stmt.execute("""
                    CREATE TABLE IF NOT EXISTS hybrid_users (
                        uuid CHAR(36) PRIMARY KEY,
                        username VARCHAR(16) NOT NULL UNIQUE,
                        password_hash VARCHAR(128),
                        auth_type ENUM('PREMIUM', 'CRACKED', 'BEDROCK') NOT NULL,
                        premium_uuid CHAR(36),
                        email VARCHAR(255),
                        registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        last_login_date TIMESTAMP NULL,
                        last_ip VARCHAR(45),
                        total_logins INT DEFAULT 0,
                        status VARCHAR(20) DEFAULT 'ACTIVE',
                        INDEX idx_username (username),
                        INDEX idx_auth_type (auth_type),
                        INDEX idx_status (status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        // Sessions table - MySQL
        stmt.execute("""
                    CREATE TABLE IF NOT EXISTS hybrid_sessions (
                        id INT PRIMARY KEY AUTO_INCREMENT,
                        user_uuid CHAR(36) NOT NULL,
                        session_token VARCHAR(64) UNIQUE NOT NULL,
                        fingerprint VARCHAR(128),
                        player_ip VARCHAR(45),
                        login_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        last_activity TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        expires_at TIMESTAMP,
                        active BOOLEAN DEFAULT TRUE,
                        INDEX idx_user_uuid (user_uuid),
                        INDEX idx_session_token (session_token),
                        INDEX idx_active (active),
                        FOREIGN KEY (user_uuid) REFERENCES hybrid_users(uuid) ON DELETE CASCADE
                    ) ENGINE=InnoDB
                """);

        // Security logs table - MySQL
        stmt.execute(
                """
                            CREATE TABLE IF NOT EXISTS hybrid_security_logs (
                                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                event_type ENUM('LOGIN_SUCCESS', 'LOGIN_FAIL', 'REGISTER', 'PREMIUM_DETECT', 'RATE_LIMIT', 'SUSPICIOUS') NOT NULL,
                                username VARCHAR(16),
                                uuid CHAR(36),
                                ip_address VARCHAR(45),
                                details TEXT,
                                timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                INDEX idx_event_type (event_type),
                                INDEX idx_timestamp (timestamp),
                                INDEX idx_username (username),
                                INDEX idx_ip (ip_address)
                            ) ENGINE=InnoDB
                        """);
    }

    private void createTablesSQLite(Statement stmt) throws SQLException {
        // Users table - SQLite
        stmt.execute("""
                    CREATE TABLE IF NOT EXISTS hybrid_users (
                        uuid TEXT PRIMARY KEY,
                        username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        password_hash TEXT,
                        auth_type TEXT NOT NULL CHECK(auth_type IN ('PREMIUM', 'CRACKED', 'BEDROCK')),
                        premium_uuid TEXT,
                        email TEXT,
                        registered_at TEXT DEFAULT (datetime('now')),
                        last_login_date TEXT,
                        last_ip TEXT,
                        total_logins INTEGER DEFAULT 0,
                        status TEXT DEFAULT 'ACTIVE'
                    )
                """);

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_username ON hybrid_users(username COLLATE NOCASE)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_auth_type ON hybrid_users(auth_type)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_status ON hybrid_users(status)");

        // Sessions table - SQLite
        stmt.execute("""
                    CREATE TABLE IF NOT EXISTS hybrid_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_uuid TEXT NOT NULL,
                        session_token TEXT UNIQUE NOT NULL,
                        fingerprint TEXT,
                        player_ip TEXT,
                        login_time TEXT DEFAULT (datetime('now')),
                        last_activity TEXT DEFAULT (datetime('now')),
                        expires_at TEXT,
                        active INTEGER DEFAULT 1,
                        FOREIGN KEY (user_uuid) REFERENCES hybrid_users(uuid) ON DELETE CASCADE
                    )
                """);

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_user_uuid ON hybrid_sessions(user_uuid)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_session_token ON hybrid_sessions(session_token)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_active ON hybrid_sessions(active)");

        // Security logs table - SQLite
        stmt.execute(
                """
                            CREATE TABLE IF NOT EXISTS hybrid_security_logs (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                event_type TEXT NOT NULL CHECK(event_type IN ('LOGIN_SUCCESS', 'LOGIN_FAIL', 'REGISTER', 'PREMIUM_DETECT', 'RATE_LIMIT', 'SUSPICIOUS')),
                                username TEXT,
                                uuid TEXT,
                                ip_address TEXT,
                                details TEXT,
                                timestamp TEXT DEFAULT (datetime('now'))
                            )
                        """);

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_event_type ON hybrid_security_logs(event_type)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON hybrid_security_logs(timestamp)");

        // v1.5.0 - Email tables for account recovery
        stmt.execute(
                """
                            CREATE TABLE IF NOT EXISTS hybrid_emails (
                                username TEXT PRIMARY KEY,
                                email TEXT NOT NULL UNIQUE,
                                verified INTEGER DEFAULT 0,
                                verification_code TEXT,
                                code_expires_at INTEGER,
                                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                            )
                        """);

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_email ON hybrid_emails(email)");

        stmt.execute(
                """
                            CREATE TABLE IF NOT EXISTS hybrid_recovery_codes (
                                username TEXT PRIMARY KEY,
                                recovery_code TEXT NOT NULL,
                                expires_at INTEGER NOT NULL,
                                attempts INTEGER DEFAULT 0,
                                created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
                            )
                        """);

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_recovery_expires ON hybrid_recovery_codes(expires_at)");
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
