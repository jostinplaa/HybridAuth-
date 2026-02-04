package net.hybridauth.core;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

public class BukkitPlayerFeedbackService implements PlayerFeedbackService {

    private final HybridAuthPlugin plugin;

    public BukkitPlayerFeedbackService(HybridAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void playSoundSuccess(Player player) {
        // Try/catch for compatibility or safely ignoring if sound doesn't exist in version
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        } catch (NoSuchFieldError | Exception ignored) {
            // Fallback or ignore
        }
    }

    @Override
    public void playSoundError(Player player) {
        try {
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 1.0f);
        } catch (NoSuchFieldError | Exception ignored) {
            // Fallback or ignore
        }
    }

    @Override
    public void removeRestrictions(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOW);
    }

    @Override
    public void sendTitle(Player player, String titleKey, String subtitleKey) {
        plugin.getMessageManager().sendTitle(player, titleKey, subtitleKey);
    }

    @Override
    public void kickPlayer(Player player, String message) {
        player.kickPlayer(message);
    }
}
