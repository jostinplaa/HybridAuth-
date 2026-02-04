package net.hybridauth;

import net.hybridauth.core.auth.AuthStateManager;
import net.hybridauth.core.auth.AutoLoginManager;
import net.hybridauth.core.messages.MessageManager;
import org.bukkit.configuration.file.FileConfiguration;
import net.hybridauth.data.DatabaseManager;
import net.hybridauth.data.dao.UserDAO;
import net.hybridauth.data.model.User;
import net.hybridauth.network.netty.PremiumDetector;
import net.hybridauth.security.SecurityLogger;
import net.hybridauth.util.AccountTypeUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class PremiumIntegrationTest {

    @Mock
    private HybridAuthPlugin plugin;
    @Mock
    private SecurityLogger securityLogger;
    @Mock
    private AuthStateManager authStateManager;
    @Mock
    private DatabaseManager databaseManager;
    @Mock
    private UserDAO userDAO;
    @Mock
    private MessageManager messageManager;
    @Mock
    private Player player;
    @Mock
    private PlayerJoinEvent joinEvent;

    private AutoLoginManager autoLoginManager;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        when(plugin.getAuthStateManager()).thenReturn(authStateManager);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(databaseManager.getUserDAO()).thenReturn(userDAO);
        when(plugin.getMessageManager()).thenReturn(messageManager);
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(plugin.getSecurityLogger()).thenReturn(securityLogger);
        when(plugin.getConfig()).thenReturn(mock(FileConfiguration.class));

        autoLoginManager = new AutoLoginManager(plugin);

        when(joinEvent.getPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("PremiumUser");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        // Mock async calls by default to return empty/null correctly to avoid NPEs if
        // not overridden
        when(userDAO.getUserByName(anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
    }

    @Test
    public void testPremiumAutoLoginSuccess() {
        // Arrange
        // Simulate that the user IS premium
        try (MockedStatic<PremiumDetector> premiumMock = Mockito.mockStatic(PremiumDetector.class);
                MockedStatic<AccountTypeUtil> accountTypeMock = Mockito.mockStatic(AccountTypeUtil.class)) {

            premiumMock.when(() -> PremiumDetector.isPremium("PremiumUser")).thenReturn(true);
            UUID premiumUUID = UUID.randomUUID();
            premiumMock.when(() -> PremiumDetector.getRealUUID("PremiumUser")).thenReturn(premiumUUID);

            accountTypeMock.when(() -> AccountTypeUtil.getAccountType(player))
                    .thenReturn(AccountTypeUtil.AccountType.PREMIUM);

            // Mock DB returning a premium user
            User premiumUser = new User(premiumUUID, "PremiumUser", User.AuthType.PREMIUM);
            premiumUser.setPremiumUuid(premiumUUID);

            // Mock ASYNC return
            when(userDAO.getUserByName("PremiumUser"))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(premiumUser));
            when(authStateManager.isAuthenticated(player)).thenReturn(false);

            // Mock updateLoginStats to avoid NPE if called
            when(userDAO.updateLoginStats(anyString(), any()))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

            when(player.getAddress()).thenReturn(new java.net.InetSocketAddress("127.0.0.1", 1234));

            // Act
            autoLoginManager.onJoin(joinEvent);

            // Assert
            // Should set auth state to AUTHENTICATED automatically
            verify(authStateManager).setAuthenticated(player, true);
            verify(messageManager).send(player, "auth.premium-login-success");
        }
    }

    @Test
    public void testCrackedCannotAutoLogin() {
        // Arrange
        try (MockedStatic<PremiumDetector> premiumMock = Mockito.mockStatic(PremiumDetector.class);
                MockedStatic<AccountTypeUtil> accountTypeMock = Mockito.mockStatic(AccountTypeUtil.class)) {

            premiumMock.when(() -> PremiumDetector.isPremium("CrackedUser")).thenReturn(false);
            accountTypeMock.when(() -> AccountTypeUtil.getAccountType(player))
                    .thenReturn(AccountTypeUtil.AccountType.CRACKED);

            when(player.getName()).thenReturn("CrackedUser");
            when(authStateManager.isAuthenticated(player)).thenReturn(false);

            // Act
            autoLoginManager.onJoin(joinEvent);

            // Assert
            // Should NOT set auth state to AUTHENTICATED
            verify(authStateManager, never()).setAuthState(any(), eq(AuthStateManager.AuthState.AUTHENTICATED));
        }
    }
}
