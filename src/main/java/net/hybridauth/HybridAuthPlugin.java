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

    private static HybridAuthPlugin instance;
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

        // 4. Inicializar base de datos
        this.databaseManager = new DatabaseManager(this);
        if (!this.databaseManager.initialize()) {
            getLogger().severe("Database initialization failed! Disabling plugin...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 5. Inicializar servicios de seguridad
        this.rateLimitService = new RateLimitService(this);
        this.fingerprintService = new ClientFingerprintService();

        // 6. Inicializar servicios core
        this.authStateManager = new AuthStateManager();
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

        // 7. Registrar listeners
        getServer().getPluginManager().registerEvents(new LoginListener(this, authStateManager), this);
        getServer().getPluginManager().registerEvents(new net.hybridauth.listeners.SecurityListener(this), this);
        getServer().getPluginManager().registerEvents(new BlacklistListener(this), this);

        // 7.5. Registrar AUTO-LOGIN para premium (¡LA NUEVA FEATURE!)
        getServer().getPluginManager().registerEvents(new net.hybridauth.core.auth.AutoLoginManager(this), this);
        getLogger().info("✓ Premium auto-login enabled");

        // 8. Registrar Comandos
        getCommand("login").setExecutor(new LoginCommand(this));
        getCommand("register").setExecutor(new RegisterCommand(this));
        getCommand("changepassword").setExecutor(new net.hybridauth.commands.ChangePasswordCommand(this));
        getCommand("hybridauth").setExecutor(new AdminCommand(this));
        getCommand("security").setExecutor(new SecurityCommand(this));

        // 9. Registrar Tab Completer
        net.hybridauth.commands.HybridAuthTabCompleter tabCompleter = new net.hybridauth.commands.HybridAuthTabCompleter();
        getCommand("login").setTabCompleter(tabCompleter);
        getCommand("register").setTabCompleter(tabCompleter);
        getCommand("changepassword").setTabCompleter(tabCompleter);
        getCommand("hybridauth").setTabCompleter(tabCompleter);

        // 10. Finalizar carga
        long loadTime = System.currentTimeMillis() - startTime;
        getLogger().info(String.format("HybridAuth v%s enabled in %dms",
                getDescription().getVersion(), loadTime));
    }

    @Override
    public void onDisable() {
        // Cerrar BlacklistManager (v1.3.0)
        if (blacklistManager != null) {
            blacklistManager.shutdown();
        }

        // Cerrar base de datos
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

        // 4. EncryptionHandler no se usa actualmente (comentado en onEnable)
        // Si se reactiva, descomentar: this.encryptionHandler.clearCache();
        getLogger().info("✓ Services reinitialized (EncryptionHandler inactive)");

        getLogger().info("All services reinitialized successfully!");
    }

    // Getters
    public static HybridAuthPlugin getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
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
}
