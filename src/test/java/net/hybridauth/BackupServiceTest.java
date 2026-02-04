package net.hybridauth;

import net.hybridauth.backup.BackupService;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

public class BackupServiceTest {

    @Mock
    private HybridAuthPlugin plugin;
    @Mock
    private FileConfiguration config;

    private BackupService backupService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());

        // Config defaults
        when(config.getBoolean("backup.enabled", true)).thenReturn(true);
        when(config.getInt("backup.max-backups", 7)).thenReturn(2); // Keep only 2 for test
    }

    @Test
    public void testCleanOldBackups(@TempDir Path tempDir) throws IOException {
        // Arrange
        // Mock data folder to temp dir
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        backupService = new BackupService(plugin);

        // Create fake backups in 'backups' folder
        File backupFolder = new File(tempDir.toFile(), "backups");
        backupFolder.mkdirs();

        File oldBackup = new File(backupFolder, "backup_2023-01-01.db");
        oldBackup.createNewFile();
        oldBackup.setLastModified(1000L); // Very old

        File newBackup1 = new File(backupFolder, "backup_2024-01-01.db");
        newBackup1.createNewFile();
        newBackup1.setLastModified(System.currentTimeMillis() - 10000);

        File newBackup2 = new File(backupFolder, "backup_2024-01-02.db");
        newBackup2.createNewFile();
        newBackup2.setLastModified(System.currentTimeMillis());

        // Act
        // Max backups is 2. We have 3 files. The oldest (oldBackup) should be deleted.
        int deleted = backupService.cleanOldBackups();

        // Assert
        assertEquals(1, deleted, "Should delete 1 old backup");
        assertEquals(2, backupFolder.listFiles().length, "Should remain 2 backups");
    }
}
