package net.hybridauth;

import net.hybridauth.commands.AdminCommand;
import net.hybridauth.commands.LoginCommand;
import net.hybridauth.commands.RegisterCommand;
import net.hybridauth.commands.SecurityCommand;
import net.hybridauth.core.auth.AuthStateManager;
import net.hybridauth.core.auth.PasswordService;
import net.hybridauth.data.DatabaseManager;
import net.hybridauth.integrations.discord.DiscordWebhook;
import net.hybridauth.listeners.BlacklistListener;
import net.hybridauth.listeners.LoginListener;
import net.hybridauth.network.packet.EncryptionHandler;
import net.hybridauth.security.blacklist.BlacklistManager;
import net.hybridauth.security.captcha.CaptchaService;
import net.hybridauth.security.fingerprint.ClientFingerprintService;
import net.hybridauth.security.ratelimit.RateLimitService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class HybridAuthPlugin extends JavaPlugin {

    private static volatile HybridAuthPlugin instance; // THREAD-SAFE
    private DatabaseManager databaseManager;
    private AuthStateManager authStateManager;
    private PasswordService passwordService;
    private EncryptionHandler encryptionHandler;

    // Security Services
    private RateLimitService rateLimitService;
    private ClientFingerprintService fingerprintService;
    private net.hybridauth.security.SecurityLogger securityLogger;
    private net.hybridauth.core.session.SessionManager sessionManager;
    private net.hybridauth.core.messages.MessageManager messageManager;

    // v1.3.0 Security Features
    private BlacklistManager blacklistManager;
    private DiscordWebhook discordWebhook;
    private CaptchaService captchaService;

    // v1.5.0 Features
    private net.hybridauth.backup.BackupService backupService;
    private net.hybridauth.email.EmailService emailService;
    private net.hybridauth.logging.LogManager logManager;

    // v1.6.0 Multi-Server Sync
    private net.hybridauth.network.sync.MultiServerSyncManager syncManager;

    // v1.6.0 2FA Service
    private net.hybridauth.security.totp.TwoFactorService twoFactorService;

    // v1.7.0 Alerts
    private net.hybridauth.alerts.AlertManager alertManager;

    // v1.7.0 GeoIP
    private net.hybridauth.security.geoip.GeoLocationService geoLocationService;

    @Override
    public void onLoad() {
        instance = this;
        getLogger().info("HybridAuth is loading...");
    }

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();

        // 1. Verificar dependencias
        if (!checkDependencies()) {
            getLogger().severe("Missing required dependencies! Disabling...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Cargar configuración
        loadConfiguration();

        // 3. Inicializar sistema de mensajes
        this.messageManager = new net.hybridauth.core.messages.MessageManager(this);
        getLogger().info("✓ Message system loaded");

        // 0. Inicializar Configuración
        saveDefaultConfig();
        this.messageManager = new net.hybridauth.core.messages.MessageManager(this);

        // v1.7.0 Initialize Alert Manager
        this.alertManager = new net.hybridauth.alerts.AlertManager(this);

        // 4. Inicializar base de datos
        this.databaseManager = new DatabaseManager(this);
        if (!this.databaseManager.initialize()) {
            getLogger().severe("Database initialization failed! Disabling plugin...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 4.5. Inicializar Multi-Server Sync (v1.6.0)
        this.syncManager = new net.hybridauth.network.sync.MultiServerSyncManager(this);
        this.syncManager.initialize();

        // 5. Inicializar servicios de seguridad
        this.rateLimitService = new RateLimitService(this);
        this.fingerprintService = new ClientFingerprintService();

        // 6. Inicializar servicios core
        this.authStateManager = new AuthStateManager(this);
        getServer().getPluginManager().registerEvents(authStateManager, this); // FIX BUG #3: Register listener
        this.passwordService = new PasswordService(this);
        // this.encryptionHandler = new EncryptionHandler(this); // Ya no se usa
        // (requiere ProtocolLib)
        this.securityLogger = new net.hybridauth.security.SecurityLogger(this);
        this.sessionManager = new net.hybridauth.core.session.SessionManager(this);

        // 6.5. Inicializar v1.3.0 Security Features
        this.blacklistManager = new BlacklistManager(this);
        this.discordWebhook = new DiscordWebhook(this);
        this.captchaService = new CaptchaService(this);
        getLogger().info("✓ v1.3.0 Security features loaded (Blacklist, Discord, Captcha)");

        // 6.6. Inicializar v1.5.0 Features - Backup System
        this.backupService = new net.hybridauth.backup.BackupService(this);
        this.backupService.scheduleAutoBackup();
        getLogger().info("✓ v1.5.0 Backup system initialized");

        // 6.7. Inicializar v1.5.0 Features - Email Recovery
        this.emailService = new net.hybridauth.email.EmailService(this);
        if (emailService.isEnabled()) {
            getLogger().info("✓ v1.5.0 Email recovery system enabled");
        } else {
            getLogger().info("⊗ v1.5.0 Email recovery system disabled (configure in config.yml)");
        }

        // 6.8. Inicializar v1.5.0 Features - Enhanced Logging
        this.logManager = new net.hybridauth.logging.LogManager(this);
        if (logManager.isEnabled()) {
            logManager.startDailyRotation();
            getLogger().info("✓ v1.5.0 Enhanced logging system enabled");
        } else {
            getLogger().info("⊗ v1.5.0 Enhanced logging disabled");
        }

        // 6.9. Inicializar v1.7.0 Features - Geolocalización
        this.geoLocationService = new net.hybridauth.security.geoip.GeoLocationService(this);
        if (geoLocationService.isEnabled()) {
            getLogger().info("✓ v1.7.0 GeoIP system enabled");
        } else {
            getLogger().info("⊗ v1.7.0 GeoIP system disabled (configure in config.yml)");
        }

        // 7. Registrar listeners
        getServer().getPluginManager().registerEvents(new LoginListener(this, authStateManager), this);
        getServer().getPluginManager().registerEvents(new net.hybridauth.listeners.SecurityListener(this), this);
        getServer().getPluginManager().registerEvents(new BlacklistListener(this), this);
        getServer().getPluginManager().registerEvents(new net.hybridauth.listeners.GeoListener(this), this);

        // 7.5. Registrar AUTO-LOGIN para premium (¡LA NUEVA FEATURE!)
        getServer().getPluginManager().registerEvents(new net.hybridauth.core.auth.AutoLoginManager(this), this);
        getLogger().info("✓ Premium auto-login enabled");

        // 8. Registrar Comandos
        getCommand("login").setExecutor(new net.hybridauth.commands.LoginCommand(this));
        getCommand("register").setExecutor(new net.hybridauth.commands.RegisterCommand(this));
        getCommand("changepassword").setExecutor(new net.hybridauth.commands.ChangePasswordCommand(this));
        getCommand("admin").setExecutor(new net.hybridauth.commands.AdminCommand(this));
        getCommand("security").setExecutor(new net.hybridauth.commands.SecurityCommand(this));

        // 2FA Command
        net.hybridauth.commands.TwoFactorCommand twoFactorCmd = new net.hybridauth.commands.TwoFactorCommand(this);
        getCommand("2fa").setExecutor(twoFactorCmd);
        // Expose service for LoginCommand
        this.twoFactorService = twoFactorCmd.getService();

        // v1.5.0 - Email Recovery Commands
        getCommand("email").setExecutor(new net.hybridauth.commands.EmailCommand(this));
        getCommand("recover").setExecutor(new net.hybridauth.commands.RecoverCommand(this));

        // 9. Registrar Tab Completer
        net.hybridauth.commands.HybridAuthTabCompleter tabCompleter = new net.hybridauth.commands.HybridAuthTabCompleter();
        getCommand("login").setTabCompleter(tabCompleter);
        getCommand("register").setTabCompleter(tabCompleter);
        getCommand("changepassword").setTabCompleter(tabCompleter);
        getCommand("hybridauth").setTabCompleter(tabCompleter);
        getCommand("security").setTabCompleter(tabCompleter); // FIX

        // 10. Finalizar carga
        long loadTime = System.currentTimeMillis() - startTime;
        getLogger().info(String.format("HybridAuth v%s enabled in %dms",
                getDescription().getVersion(), loadTime));
    }

    @Override
    public void onDisable() {
        getLogger().info("HybridAuth is shutting down...");

        // Disable sync manager (v1.6.0)
        if (syncManager != null) {
            syncManager.shutdown();
        }

        // Cerrar BlacklistManager (v1.3.0)
        if (blacklistManager != null) {
            blacklistManager.shutdown();
        }

        // Cerrar pool de conexiones
        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("HybridAuth disabled successfully");
    }

    private boolean checkDependencies() {
        // Verificar Floodgate (opcional)
        if (Bukkit.getPluginManager().getPlugin("Floodgate") != null) {
            getLogger().info("✓ Floodgate detected - Bedrock support enabled");
        }

        return true;
    }

    private void loadConfiguration() {
        saveDefaultConfig();
        saveResource("messages.yml", false); // Save messages file
        getLogger().info("✓ Configuration loaded");
    }

    /**
     * Reinicializa servicios que dependen de la configuración
     * Llamar después de reloadConfig()
     */
    public void reinitializeServices() {
        getLogger().info("Reinitializing services...");

        // 1. Reiniciar SessionManager
        this.sessionManager = new net.hybridauth.core.session.SessionManager(this);
        getLogger().info("✓ SessionManager reinitialized");

        // 2. Reiniciar RateLimitService
        this.rateLimitService = new RateLimitService(this);
        getLogger().info("✓ RateLimitService reinitialized");

        // 3. Reiniciar PasswordService
        this.passwordService = new PasswordService(this);
        getLogger().info("✓ PasswordService reinitialized");

        // 4. Reiniciar SyncManager
        if (this.syncManager != null) {
            this.syncManager.shutdown();
        }
        this.syncManager = new net.hybridauth.network.sync.MultiServerSyncManager(this);
        this.syncManager.initialize();
        getLogger().info("✓ SyncManager reinitialized");

        getLogger().info("All services reinitialized successfully!");
    }

    // Getters
    public static HybridAuthPlugin getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Plugin not initialized!");
        }
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public net.hybridauth.network.sync.MultiServerSyncManager getSyncManager() {
        return syncManager;
    }

    public net.hybridauth.security.totp.TwoFactorService getTwoFactorService() {
        return twoFactorService;
    }

    public net.hybridauth.alerts.AlertManager getAlertManager() {
        return alertManager;
    }

    public AuthStateManager getAuthStateManager() {
        return authStateManager;
    }

    public PasswordService getPasswordService() {
        return passwordService;
    }

    public RateLimitService getRateLimitService() {
        return rateLimitService;
    }

    public ClientFingerprintService getFingerprintService() {
        return fingerprintService;
    }

    public EncryptionHandler getEncryptionHandler() {
        return encryptionHandler;
    }

    public net.hybridauth.security.SecurityLogger getSecurityLogger() {
        return securityLogger;
    }

    public net.hybridauth.core.session.SessionManager getSessionManager() {
        return sessionManager;
    }

    public net.hybridauth.core.messages.MessageManager getMessageManager() {
        return messageManager;
    }

    // v1.3.0 Security Features Getters
    public BlacklistManager getBlacklistManager() {
        return blacklistManager;
    }

    public DiscordWebhook getDiscordWebhook() {
        return discordWebhook;
    }

    public CaptchaService getCaptchaService() {
        return captchaService;
    }

    // v1.5.0 Features Getters
    public net.hybridauth.backup.BackupService getBackupService() {
        return backupService;
    }

    public net.hybridauth.email.EmailService getEmailService() {
        return emailService;
    }

    public net.hybridauth.logging.LogManager getLogManager() {
        return logManager;
    }

    // v1.7.0 Features Getters - GeoIP
    public net.hybridauth.security.geoip.GeoLocationService getGeoLocationService() {
        return geoLocationService;
    }
}
