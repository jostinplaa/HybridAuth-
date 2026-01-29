package net.hybridauth.core.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;
import net.hybridauth.HybridAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;

public class PasswordService {

    private final HybridAuthPlugin plugin;
    private final int cost;

    public PasswordService(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        this.cost = config.getInt("security.bcrypt_cost", 10);
    }

    public String hashPassword(String password) {
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    public boolean verifyPassword(String password, String paramHash) {
        if (paramHash == null || password == null)
            return false;
        BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), paramHash);
        return result.verified;
    }
}
