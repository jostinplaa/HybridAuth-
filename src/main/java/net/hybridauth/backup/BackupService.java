package net.hybridauth.backup;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sistema de backup automático para HybridAuth
 * Crea backups periódicos de la base de datos y permite restauración
 * 
 * @version 1.5.0
 */
public class BackupService {

    private final HybridAuthPlugin plugin;
    private final File backupFolder;
    private final boolean enabled;
    private final int intervalHours;
    private final int maxBackups;
    private final boolean notifyAdmins;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    public BackupService(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();

        this.enabled = config.getBoolean("backup.enabled", true);
        this.intervalHours = config.getInt("backup.interval-hours", 6);
        this.maxBackups = config.getInt("backup.max-backups", 7);
        this.notifyAdmins = config.getBoolean("backup.notify-admins", true);

        // Crear carpeta de backups
        this.backupFolder = new File(plugin.getDataFolder(), "backups");
        if (!backupFolder.exists()) {
            backupFolder.mkdirs();
        }

        plugin.getLogger().info("[BackupService] Initialized - Enabled: " + enabled +
                ", Interval: " + intervalHours + "h, Max backups: " + maxBackups);
    }

    /**
     * Programa el backup automático usando BukkitScheduler
     */
    public void scheduleAutoBackup() {
        if (!enabled) {
            plugin.getLogger().info("[BackupService] Auto-backup disabled in config");
            return;
        }

        // Convertir horas a ticks (1 hora = 72000 ticks)
        long intervalTicks = intervalHours * 72000L;

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            plugin.getLogger().info("[BackupService] Running scheduled backup...");
            BackupResult result = createBackup();

            if (result.success) {
                plugin.getLogger().info("[BackupService] ✓ Scheduled backup created: " + result.filename);

                if (notifyAdmins) {
                    notifyAdminsAboutBackup(result.filename);
                }

                // Limpiar backups antiguos
                cleanOldBackups();
            } else {
                plugin.getLogger().severe("[BackupService] ✗ Scheduled backup failed: " + result.error);
            }
        }, intervalTicks, intervalTicks); // Delay inicial = intervalo, luego cada intervalo

        plugin.getLogger().info("[BackupService] Scheduled auto-backup every " + intervalHours + " hours");
    }

    /**
     * Crea un backup manual o automático
     */
    public BackupResult createBackup() {
        try {
            String dbType = getDatabaseType();
            String timestamp = dateFormat.format(new Date());
            String filename;
            File backupFile;

            if ("sqlite".equalsIgnoreCase(dbType)) {
                // SQLite: Copiar archivo directamente
                filename = "backup_" + timestamp + ".db";
                backupFile = new File(backupFolder, filename);

                File sourceDb = getDatabaseFile();
                if (sourceDb == null || !sourceDb.exists()) {
                    return new BackupResult(false, null, "SQLite database file not found");
                }

                Files.copy(sourceDb.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            } else {
                // MySQL: Usar mysqldump (requiere instalación en server)
                filename = "backup_" + timestamp + ".sql";
                backupFile = new File(backupFolder, filename);

                // TODO: Implementar mysqldump si se usa MySQL
                // Por ahora retornar que no está soportado
                return new BackupResult(false, null, "MySQL backup not yet implemented (use mysqldump manually)");
            }

            long size = backupFile.length();
            plugin.getLogger().info("[BackupService] Backup created: " + filename + " (" + formatSize(size) + ")");

            return new BackupResult(true, filename, null);

        } catch (IOException | SQLException e) {
            plugin.getLogger().severe("[BackupService] Error creating backup: " + e.getMessage());
            e.printStackTrace();
            return new BackupResult(false, null, e.getMessage());
        }
    }

    /**
     * Limpia backups antiguos, manteniendo solo los últimos N
     */
    public int cleanOldBackups() {
        File[] backups = backupFolder.listFiles(
                (dir, name) -> name.startsWith("backup_") && (name.endsWith(".db") || name.endsWith(".sql")));

        if (backups == null || backups.length <= maxBackups) {
            return 0;
        }

        // Ordenar por fecha de modificación (más reciente primero)
        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());

        // Eliminar los backups viejos (después de maxBackups)
        int deleted = 0;
        for (int i = maxBackups; i < backups.length; i++) {
            if (backups[i].delete()) {
                deleted++;
                plugin.getLogger().info("[BackupService] Deleted old backup: " + backups[i].getName());
            }
        }

        return deleted;
    }

    /**
     * Obtiene la lista de backups disponibles
     */
    public List<BackupInfo> getBackupList() {
        File[] backups = backupFolder.listFiles(
                (dir, name) -> name.startsWith("backup_") && (name.endsWith(".db") || name.endsWith(".sql")));

        if (backups == null) {
            return new ArrayList<>();
        }

        return Arrays.stream(backups)
                .sorted(Comparator.comparingLong(File::lastModified).reversed())
                .map(file -> new BackupInfo(
                        file.getName(),
                        file.length(),
                        new Date(file.lastModified())))
                .collect(Collectors.toList());
    }

    /**
     * Restaura un backup (PELIGROSO - sobrescribe BD actual)
     */
    public BackupResult restoreBackup(String filename) {
        try {
            File backupFile = new File(backupFolder, filename);

            if (!backupFile.exists()) {
                return new BackupResult(false, null, "Backup file not found: " + filename);
            }

            String dbType = getDatabaseType();

            if ("sqlite".equalsIgnoreCase(dbType)) {
                File currentDb = getDatabaseFile();
                if (currentDb == null) {
                    return new BackupResult(false, null, "Current database file not found");
                }

                // Crear backup de la BD actual antes de sobrescribir
                String safetyCopy = "pre-restore_" + dateFormat.format(new Date()) + ".db";
                Files.copy(currentDb.toPath(),
                        new File(backupFolder, safetyCopy).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);

                plugin.getLogger().info("[BackupService] Safety backup created: " + safetyCopy);

                // Sobrescribir con el backup
                Files.copy(backupFile.toPath(), currentDb.toPath(), StandardCopyOption.REPLACE_EXISTING);

                plugin.getLogger().warning("[BackupService] ⚠ DATABASE RESTORED from " + filename);

                return new BackupResult(true, filename, null);

            } else {
                return new BackupResult(false, null, "MySQL restore not yet implemented");
            }

        } catch (IOException | SQLException e) {
            plugin.getLogger().severe("[BackupService] Error restoring backup: " + e.getMessage());
            e.printStackTrace();
            return new BackupResult(false, null, e.getMessage());
        }
    }

    private String getDatabaseType() throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            String url = conn.getMetaData().getURL();
            return url.contains("sqlite") ? "sqlite" : "mysql";
        }
    }

    private File getDatabaseFile() {
        // Buscar archivo hybridauth.db en carpeta del plugin
        File dbFile = new File(plugin.getDataFolder(), "hybridauth.db");
        if (dbFile.exists()) {
            return dbFile;
        }

        // Intentar con database.db
        dbFile = new File(plugin.getDataFolder(), "database.db");
        if (dbFile.exists()) {
            return dbFile;
        }

        return null;
    }

    private void notifyAdminsAboutBackup(String filename) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            String message = plugin.getMessageManager().getMessage("backup.created",
                    net.hybridauth.core.messages.MessageManager.placeholder()
                            .add("filename", filename)
                            .build());

            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.hasPermission("hybridauth.admin.backup"))
                    .forEach(p -> p.sendMessage(message));
        });
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // Inner classes
    public static class BackupResult {
        public final boolean success;
        public final String filename;
        public final String error;

        public BackupResult(boolean success, String filename, String error) {
            this.success = success;
            this.filename = filename;
            this.error = error;
        }
    }

    public static class BackupInfo {
        public final String filename;
        public final long size;
        public final Date date;

        public BackupInfo(String filename, long size, Date date) {
            this.filename = filename;
            this.size = size;
            this.date = date;
        }

        public String getFormattedSize() {
            if (size < 1024)
                return size + " B";
            if (size < 1024 * 1024)
                return String.format("%.1f KB", size / 1024.0);
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        }

        public String getFormattedDate() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.format(date);
        }
    }
}
