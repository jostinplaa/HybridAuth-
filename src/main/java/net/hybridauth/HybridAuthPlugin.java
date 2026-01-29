package net.hybridauth;

import net.hybridauth.commands.AdminCommand;
import net.hybridauth.commands.LoginCommand;
import net.hybridauth.commands.RegisterCommand;
import net.hybridauth.core.auth.AuthStateManager;
import net.hybridauth.core.auth.PasswordService;
import net.hybridauth.data.DatabaseManager;
import net.hybridauth.listeners.LoginListener;
import net.hybridauth.network.packet.EncryptionHandler;
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

        // 3. Inicializar base de datos
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        // 4. Inicializar servicios de Seguridad
        this.rateLimitService = new RateLimitService(this);
        this.fingerprintService = new ClientFingerprintService();

        // 5. Inicializar servicios Core
        this.authStateManager = new AuthStateManager();
        this.passwordService = new PasswordService(this);
        this.encryptionHandler = new EncryptionHandler(this);

        // 6. Registrar listeners
        getServer().getPluginManager().registerEvents(new LoginListener(this, authStateManager), this);

        // 7. Registrar comandos
        getCommand("login").setExecutor(new LoginCommand(this));
        getCommand("register").setExecutor(new RegisterCommand(this));
        getCommand("hybridauth").setExecutor(new AdminCommand(this));
        getCommand("changepassword").setExecutor(new net.hybridauth.commands.ChangePasswordCommand(this));
        getCommand("logout").setExecutor(new net.hybridauth.commands.LogoutCommand(this));

        long loadTime = System.currentTimeMillis() - startTime;
        getLogger().info(String.format("HybridAuth v%s enabled in %dms",
                getDescription().getVersion(), loadTime));
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("HybridAuth disabled successfully");
    }

    private boolean checkDependencies() {
        // Verificar ProtocolLib
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib not found! Please install it.");
            return false;
        }

        getLogger().info("✓ ProtocolLib found");

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
}
