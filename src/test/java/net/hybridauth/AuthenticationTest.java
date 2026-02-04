package net.hybridauth;

import net.hybridauth.commands.LoginCommand;
import net.hybridauth.commands.RegisterCommand;
import net.hybridauth.core.PlayerFeedbackService;
import net.hybridauth.core.auth.AuthStateManager;
import net.hybridauth.core.auth.PasswordService;
import net.hybridauth.core.messages.MessageManager;
import net.hybridauth.core.session.SessionManager;
import net.hybridauth.data.DatabaseManager;
import net.hybridauth.data.dao.UserDAO;
import net.hybridauth.data.model.User;
import net.hybridauth.security.SecurityLogger;
import net.hybridauth.security.captcha.CaptchaService;
import net.hybridauth.security.ratelimit.RateLimitService;
import net.hybridauth.util.AccountTypeUtil;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class AuthenticationTest {

    @Mock
    private HybridAuthPlugin plugin;
    @Mock
    private MessageManager messageManager;
    @Mock
    private DatabaseManager databaseManager;
    @Mock
    private UserDAO userDAO;
    @Mock
    private PasswordService passwordService;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private AuthStateManager authStateManager;
    @Mock
    private CaptchaService captchaService;
    @Mock
    private SecurityLogger securityLogger;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private Server server;
    @Mock
    private BukkitScheduler scheduler;
    @Mock
    private Player player;
    @Mock
    private Command command;
    @Mock
    private InetSocketAddress socketAddress;
    @Mock
    private InetAddress inetAddress;
    @Mock
    private PlayerFeedbackService feedbackService;
    @Mock
    private FileConfiguration config;

    private RegisterCommand registerCommand;
    private LoginCommand loginCommand;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);

        // Mock Plugin components
        when(plugin.getMessageManager()).thenReturn(messageManager);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(databaseManager.getUserDAO()).thenReturn(userDAO);
        when(plugin.getPasswordService()).thenReturn(passwordService);
        when(plugin.getRateLimitService()).thenReturn(rateLimitService);
        when(plugin.getAuthStateManager()).thenReturn(authStateManager);
        when(plugin.getCaptchaService()).thenReturn(captchaService);
        when(plugin.getSecurityLogger()).thenReturn(securityLogger);
        when(plugin.getSessionManager()).thenReturn(sessionManager);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(plugin.getFeedbackService()).thenReturn(feedbackService);
        when(plugin.getConfig()).thenReturn(config);
        when(server.getScheduler()).thenReturn(scheduler);

        // Mock Scheduler to run synchronously
        when(scheduler.runTaskAsynchronously(any(HybridAuthPlugin.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return null;
                });
        when(scheduler.runTask(any(HybridAuthPlugin.class), any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        });

        // Mock Player
        UUID playerUUID = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerUUID);
        when(player.getName()).thenReturn("TestPlayer");
        when(player.getAddress()).thenReturn(socketAddress);
        when(socketAddress.getAddress()).thenReturn(inetAddress);
        when(inetAddress.getHostAddress()).thenReturn("127.0.0.1");

        // Mock Services defaults
        when(rateLimitService.checkLimit(anyString())).thenReturn(true);
        when(passwordService.validatePassword(anyString(), anyString()))
                .thenReturn(new PasswordService.PasswordValidationResult(true, null, 80));
        when(passwordService.hashPassword(anyString())).thenReturn("hashed_password");

        // Initialize Commands
        registerCommand = new RegisterCommand(plugin);
        loginCommand = new LoginCommand(plugin);
    }

    @Test
    public void testRegisterSuccess() {
        // Arrange
        String password = "StrongPassword123!";
        String[] args = { password, password };

        // Mock static AccountTypeUtil since it's static
        try (MockedStatic<AccountTypeUtil> accountTypeMock = Mockito.mockStatic(AccountTypeUtil.class)) {
            accountTypeMock.when(() -> AccountTypeUtil.getAccountType(player))
                    .thenReturn(AccountTypeUtil.AccountType.CRACKED);

            when(authStateManager.isAuthenticated(player)).thenReturn(false);
            when(userDAO.getUserByUUID(player.getUniqueId())).thenReturn(Optional.empty());

            // Act
            registerCommand.onCommand(player, command, "register", args);

            // Assert
            // Verify DB creation
            try {
                verify(userDAO).createUser(any(User.class));
            } catch (Exception e) {
                e.printStackTrace();
            }

            // Verify Authentication
            verify(authStateManager).setAuthState(player, AuthStateManager.AuthState.AUTHENTICATED);
            verify(messageManager).send(eq(player), eq("success.registered"), any());
            verify(feedbackService).removeRestrictions(player);
            verify(feedbackService).playSoundSuccess(player);
        }
    }

    @Test
    public void testRegisterMismatchPasswords() {
        // Arrange
        String[] args = { "Pass1", "Pass2" };
        try (MockedStatic<AccountTypeUtil> accountTypeMock = Mockito.mockStatic(AccountTypeUtil.class)) {
            accountTypeMock.when(() -> AccountTypeUtil.getAccountType(player))
                    .thenReturn(AccountTypeUtil.AccountType.CRACKED);

            // Act
            registerCommand.onCommand(player, command, "register", args);

            // Assert
            verify(messageManager).send(player, "password.must_match");
            // NOTE: The command DOES check interact with UserDAO early to check if user
            // exists.
            // verifyNoInteractions(userDAO); // Removed this check as it was incorrect
        }
    }

    @Test
    public void testLoginSuccess() {
        // Arrange
        String password = "MySecretPassword";
        String hash = "hashed_secret";
        User mockUser = new User(player.getUniqueId(), player.getName(), User.AuthType.CRACKED);
        mockUser.setPasswordHash(hash);

        when(userDAO.getUserByUUID(player.getUniqueId())).thenReturn(Optional.of(mockUser));
        when(passwordService.verifyPassword(password, hash)).thenReturn(true);

        try (MockedStatic<AccountTypeUtil> accountTypeMock = Mockito.mockStatic(AccountTypeUtil.class)) {
            accountTypeMock.when(() -> AccountTypeUtil.getAccountType(player))
                    .thenReturn(AccountTypeUtil.AccountType.CRACKED);

            // Act
            loginCommand.onCommand(player, command, "login", new String[] { password });

            // Assert
            verify(authStateManager).setAuthState(player, AuthStateManager.AuthState.AUTHENTICATED);
            verify(sessionManager).createSession(any(UUID.class), anyString());
            verify(messageManager).send(player, "success.logged_in");
            verify(feedbackService).removeRestrictions(player);
            verify(feedbackService).playSoundSuccess(player);
        }
    }

    @Test
    public void testLoginFailure() {
        // Arrange
        String password = "WrongPassword";
        String hash = "real_hash";
        User mockUser = new User(player.getUniqueId(), player.getName(), User.AuthType.CRACKED);
        mockUser.setPasswordHash(hash);

        when(userDAO.getUserByUUID(player.getUniqueId())).thenReturn(Optional.of(mockUser));
        when(passwordService.verifyPassword(password, hash)).thenReturn(false);
        // Simulate rate limit remaining attempts
        when(rateLimitService.getAttempts(anyString())).thenReturn(1);
        when(config.getInt(anyString(), anyInt())).thenReturn(5); // Max attempts

        try (MockedStatic<AccountTypeUtil> accountTypeMock = Mockito.mockStatic(AccountTypeUtil.class)) {
            accountTypeMock.when(() -> AccountTypeUtil.getAccountType(player))
                    .thenReturn(AccountTypeUtil.AccountType.CRACKED);

            // Act
            loginCommand.onCommand(player, command, "login", new String[] { password });

            // Assert
            verify(rateLimitService).incrementAttempt(anyString());
            verify(messageManager).send(eq(player), eq("password.incorrect"), any());
            verify(authStateManager, never()).setAuthState(any(), any());
            verify(feedbackService).playSoundError(player);
        }
    }
}
