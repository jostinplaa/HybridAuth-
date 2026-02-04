package net.hybridauth.core;

import org.bukkit.entity.Player;

/**
 * Service to handle player feedback (messages, sounds, effects)
 * abstraction to allow easier unit testing without Bukkit static dependencies.
 */
public interface PlayerFeedbackService {
    
    void playSoundSuccess(Player player);
    
    void playSoundError(Player player);
    
    void removeRestrictions(Player player);
    
    void sendTitle(Player player, String titleKey, String subtitleKey);
    
    void kickPlayer(Player player, String message);
}
