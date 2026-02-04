package net.hybridauth.logging;

import net.hybridauth.HybridAuthPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * Sistema mejorado de logging con rotacin automtica
 * v1.5.0 - Feature #3
 */
public class LogManager {

    private final HybridAuthPlugin plugin;
    private final Path logsDir;
    private final Path archiveDir;
    private final DateTimeFormatter timestampFormat;
    private final boolean enabled;

    // Tipos de logs
    private PrintWriter securityLog;
    private PrintWriter errorLog;
    private PrintWriter debugLog;
    private PrintWriter perfLog;

    // Config
    private final boolean securityEnabled;
    private final boolean errorsEnabled;
    private final boolean debugEnabled;
    private final boolean perfEnabled;
    private final int keepDays;
    private final boolean compressOld;

    public LogManager(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("logging.file-logging.enabled", true);

        if (!enabled) {
            plugin.getLogger().info("[LogManager] File logging disabled");
            this.logsDir = null;
            this.archiveDir = null;
            this.timestampFormat = null;
            this.securityEnabled = false;
            this.errorsEnabled = false;
            this.debugEnabled = false;
            this.perfEnabled = false;
            this.keepDays = 30;
            this.compressOld = false;
            return;
        }

        // Crear directorios
        String logsDirPath = plugin.getConfig().getString("logging.file-logging.directory", "logs/");
        this.logsDir = plugin.getDataFolder().toPath().resolve(logsDirPath);
        this.archiveDir = logsDir.resolve("archive");

        try {
            Files.createDirectories(logsDir);
            Files.createDirectories(archiveDir);
        } catch (IOException e) {
            plugin.getLogger().severe("[LogManager] Failed to create log directories: " + e.getMessage());
        }

        // Config
        String format = plugin.getConfig().getString("logging.format.timestamp", "yyyy-MM-dd HH:mm:ss");
        this.timestampFormat = DateTimeFormatter.ofPattern(format);
        this.securityEnabled = plugin.getConfig().getBoolean("logging.levels.security", true);
        this.errorsEnabled = plugin.getConfig().getBoolean("logging.levels.errors", true);
        this.debugEnabled = plugin.getConfig().getBoolean("logging.levels.debug", false);
        this.perfEnabled = plugin.getConfig().getBoolean("logging.levels.performance", false);
        this.keepDays = plugin.getConfig().getInt("logging.rotation.keep-days", 30);
        this.compressOld = plugin.getConfig().getBoolean("logging.rotation.compress-old", true);

        // Inicializar archivos
        initializeLogFiles();

        plugin.getLogger().info("[LogManager] File logging enabled - Dir: " + logsDir.toAbsolutePath());
    }

    private void initializeLogFiles() {
        try {
            if (securityEnabled) {
                securityLog = new PrintWriter(new FileWriter(logsDir.resolve("security.log").toFile(), true), true);
            }
            if (errorsEnabled) {
                errorLog = new PrintWriter(new FileWriter(logsDir.resolve("errors.log").toFile(), true), true);
            }
            if (debugEnabled) {
                debugLog = new PrintWriter(new FileWriter(logsDir.resolve("debug.log").toFile(), true), true);
            }
            if (perfEnabled) {
                perfLog = new PrintWriter(new FileWriter(logsDir.resolve("performance.log").toFile(), true), true);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("[LogManager] Failed to initialize log files: " + e.getMessage());
        }
    }

    /**
     * Inicia rotacin diaria automtica
     */
    public void startDailyRotation() {
        if (!enabled)
            return;

        boolean daily = plugin.getConfig().getBoolean("logging.rotation.daily", true);
        if (!daily)
            return;

        // Tarea que corre cada hora y verifica si es medianoche
        new BukkitRunnable() {
            private LocalDate lastRotation = LocalDate.now();

            @Override
            public void run() {
                LocalDate now = LocalDate.now();
                if (!now.equals(lastRotation)) {
                    // Nuevo da - rotar logs
                    plugin.getLogger().info("[LogManager] Daily rotation triggered");
                    rotateLogs();
                    lastRotation = now;
                }

                // Comprimir logs antiguos si est habilitado
                if (compressOld) {
                    compressOldLogs();
                }

                // Limpiar logs muy antiguos en sistema de archivos
                cleanOldLogs();

                // Limpiar logs antiguos en BASE DE DATOS (Feature 8 Completa)
                plugin.getSecurityLogger().cleanupOldLogs(keepDays);
            }
        }.runTaskTimerAsynchronously(plugin, 20 * 60, 20 * 60 * 60); // Cada hora

        plugin.getLogger().info("[LogManager] Daily rotation scheduled");
    }

    /**
     * Log de evento de seguridad
     */
    public void logSecurity(String event, String player, String ip, String details) {
        if (!enabled || !securityEnabled || securityLog == null)
            return;

        String timestamp = LocalDateTime.now().format(timestampFormat);
        String logLine = String.format("[%s] [SECURITY] [%s] Player: %s | IP: %s | %s",
                timestamp, event, player, ip, details);

        securityLog.println(logLine);
    }

    /**
     * Log de error/excepcin
     */
    public void logError(Exception exception, String context) {
        if (!enabled || !errorsEnabled || errorLog == null)
            return;

        String timestamp = LocalDateTime.now().format(timestampFormat);
        errorLog.println(String.format("[%s] [ERROR] Context: %s", timestamp, context));
        errorLog.println("Exception: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());

        // Stack trace resumido (primeras 5 lneas)
        StackTraceElement[] stack = exception.getStackTrace();
        for (int i = 0; i < Math.min(stack.length, 5); i++) {
            errorLog.println("  at " + stack[i]);
        }
        errorLog.println();
    }

    /**
     * Log de debug (solo si est habilitado)
     */
    public void logDebug(String message) {
        if (!enabled || !debugEnabled || debugLog == null)
            return;

        String timestamp = LocalDateTime.now().format(timestampFormat);
        debugLog.println(String.format("[%s] [DEBUG] %s", timestamp, message));
    }

    /**
     * Log de performance
     */
    public void logPerformance(String operation, long durationMs) {
        if (!enabled || !perfEnabled || perfLog == null)
            return;

        String timestamp = LocalDateTime.now().format(timestampFormat);
        perfLog.println(String.format("[%s] [PERF] %s completed in %dms", timestamp, operation, durationMs));
    }

    /**
     * Rota los logs actuales al archive
     */
    public void rotateLogs() {
        if (!enabled)
            return;

        String yesterday = LocalDate.now().minusDays(1).toString();

        try {
            if (securityLog != null) {
                rotateFile("security", yesterday);
            }
            if (errorLog != null) {
                rotateFile("errors", yesterday);
            }
            if (debugLog != null) {
                rotateFile("debug", yesterday);
            }
            if (perfLog != null) {
                rotateFile("performance", yesterday);
            }

            plugin.getLogger().info("[LogManager] Logs rotated successfully");
        } catch (IOException e) {
            plugin.getLogger().severe("[LogManager] Rotation failed: " + e.getMessage());
        }
    }

    private void rotateFile(String type, String date) throws IOException {
        Path current = logsDir.resolve(type + ".log");
        Path archived = archiveDir.resolve(type + "-" + date + ".log");

        if (Files.exists(current) && Files.size(current) > 0) {
            Files.move(current, archived, StandardCopyOption.REPLACE_EXISTING);
            // Recrear archivo actual
            Files.createFile(current);
        }
    }

    /**
     * Comprime logs de ms de 7 das
     */
    private void compressOldLogs() {
        if (!compressOld)
            return;

        LocalDate cutoff = LocalDate.now().minusDays(7);

        try {
            Files.list(archiveDir)
                    .filter(p -> p.toString().endsWith(".log"))
                    .filter(p -> !p.toString().endsWith(".gz"))
                    .forEach(logFile -> {
                        try {
                            // Verificar si es viejo (por nombre de archivo)
                            String fileName = logFile.getFileName().toString();
                            if (fileName.length() > 15) {
                                String dateStr = fileName.substring(fileName.length() - 14, fileName.length() - 4);
                                LocalDate fileDate = LocalDate.parse(dateStr);

                                if (fileDate.isBefore(cutoff)) {
                                    compressFile(logFile);
                                }
                            }
                        } catch (Exception e) {
                            // Skip archivos con fecha invlida
                        }
                    });
        } catch (IOException e) {
            plugin.getLogger().warning("[LogManager] Compression scan failed: " + e.getMessage());
        }
    }

    private void compressFile(Path file) {
        try {
            Path gzFile = Paths.get(file.toString() + ".gz");

            try (FileInputStream fis = new FileInputStream(file.toFile());
                    FileOutputStream fos = new FileOutputStream(gzFile.toFile());
                    GZIPOutputStream gzos = new GZIPOutputStream(fos)) {

                byte[] buffer = new byte[1024];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    gzos.write(buffer, 0, len);
                }
            }

            // Eliminar archivo original
            Files.delete(file);
            plugin.getLogger().info("[LogManager] Compressed: " + file.getFileName());

        } catch (IOException e) {
            plugin.getLogger().warning("[LogManager] Failed to compress " + file.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * Elimina logs de ms de keepDays das
     */
    private void cleanOldLogs() {
        LocalDate cutoff = LocalDate.now().minusDays(keepDays);

        try {
            Files.list(archiveDir)
                    .filter(p -> p.toString().endsWith(".log") || p.toString().endsWith(".log.gz"))
                    .forEach(logFile -> {
                        try {
                            String fileName = logFile.getFileName().toString();
                            // Extraer fecha del nombre (formato: type-YYYY-MM-DD.log[.gz])
                            int dateStart = fileName.lastIndexOf('-') + 1;
                            int dateEnd = fileName.indexOf(".log");

                            if (dateStart > 0 && dateEnd > dateStart) {
                                String dateStr = fileName.substring(dateStart, dateEnd);
                                LocalDate fileDate = LocalDate.parse(dateStr);

                                if (fileDate.isBefore(cutoff)) {
                                    Files.delete(logFile);
                                    plugin.getLogger().info("[LogManager] Deleted old log: " + fileName);
                                }
                            }
                        } catch (Exception e) {
                            // Skip archivos con formato invlido
                        }
                    });
        } catch (IOException e) {
            plugin.getLogger().warning("[LogManager] Cleanup failed: " + e.getMessage());
        }
    }

    /**
     * Cierra todos los archivos de log
     */
    public void close() {
        if (securityLog != null)
            securityLog.close();
        if (errorLog != null)
            errorLog.close();
        if (debugLog != null)
            debugLog.close();
        if (perfLog != null)
            perfLog.close();

        plugin.getLogger().info("[LogManager] Closed all log files");
    }

    public boolean isEnabled() {
        return enabled;
    }
}

