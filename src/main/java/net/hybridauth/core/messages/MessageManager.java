package net.hybridauth.core.messages;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gestor centralizado de mensajes del plugin.
 * Carga mensajes desde messages.yml y proporciona mtodos para
 * formatear, colorizar y enviar mensajes con placeholders.
 * 
 * @author TuNombre
 * @version 1.0.0
 */
public class MessageManager {

    private final Plugin plugin;
    private FileConfiguration messages;
    private String prefix;

    // Pattern para colores HEX (#RRGGBB)
    private static final Pattern HEX_PATTERN = Pattern.compile("#[a-fA-F0-9]{6}");

    // Pattern para gradientes &#RRGGBB
    private static final Pattern GRADIENT_PATTERN = Pattern.compile("&#[a-fA-F0-9]{6}");

    /**
     * Constructor del MessageManager.
     * 
     * @param plugin Instancia del plugin principal
     */
    public MessageManager(Plugin plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    /**
     * Carga o recarga los mensajes desde messages.yml.
     * Si el archivo no existe, lo crea desde los resources.
     */
    public void loadMessages() {
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");

        // Crear archivo si no existe
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        // Cargar configuracin existente
        messages = YamlConfiguration.loadConfiguration(messagesFile);

        // Auto-update: Cargar recurso interno para comparar claves faltantes
        java.io.InputStream defConfigStream = plugin.getResource("messages.yml");
        if (defConfigStream != null) {
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(defConfigStream, java.nio.charset.StandardCharsets.UTF_8));

            boolean changed = false;
            for (String key : defConfig.getKeys(true)) {
                if (!messages.contains(key)) {
                    messages.set(key, defConfig.get(key));
                    changed = true;
                }
            }

            // Guardar si hubo cambios y recargar
            if (changed) {
                try {
                    messages.save(messagesFile);
                    plugin.getLogger().info("Updated messages.yml with new keys.");
                } catch (java.io.IOException e) {
                    plugin.getLogger().severe("Could not save updated messages.yml: " + e.getMessage());
                }
            }
        }

        // Cargar prefijo
        prefix = colorize(messages.getString("prefix", "&8[&bHybridAuth&8] &r"));

        plugin.getLogger().info("Messages loaded successfully! Total messages: " + countMessages());
    }

    /**
     * Recarga los mensajes del archivo.
     * til para el comando /hybridauth reload.
     */
    public void reload() {
        loadMessages();
        plugin.getLogger().info("Messages reloaded!");
    }

    /**
     * Obtiene un mensaje del archivo messages.yml sin placeholders.
     * 
     * @param path Ruta del mensaje usando notacin de puntos (ej:
     *             "error.wrong-password")
     * @return Mensaje formateado con colores y prefijo
     */
    public String getMessage(String path) {
        return getMessage(path, null);
    }

    /**
     * Obtiene un mensaje con placeholders reemplazados.
     * 
     * @param path         Ruta del mensaje usando notacin de puntos
     * @param placeholders Mapa de placeholders a reemplazar (key sin llaves)
     * @return Mensaje formateado con colores, prefijo y placeholders aplicados
     */
    public String getMessage(String path, Map<String, String> placeholders) {
        String message = messages.getString(path);

        // Si no existe el mensaje, retornar error descriptivo
        if (message == null) {
            plugin.getLogger().warning("Missing message key: " + path);
            return colorize("&c[Missing message: " + path + "]");
        }

        // Aplicar placeholders si existen
        if (placeholders != null && !placeholders.isEmpty()) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        // Aplicar prefijo si el mensaje no contiene el marcador {no-prefix}
        if (!message.contains("{no-prefix}")) {
            message = prefix + message;
        } else {
            // Remover el marcador
            message = message.replace("{no-prefix}", "");
        }

        // Aplicar colorizacin
        return colorize(message);
    }

    /**
     * Obtiene una lista de mensajes (til para help menus).
     * 
     * @param path Ruta de la lista
     * @return Lista de mensajes formateados
     */
    public List<String> getMessageList(String path) {
        List<String> list = messages.getStringList(path);
        list.replaceAll(this::colorize);
        return list;
    }

    /**
     * Enva un mensaje a un CommandSender.
     * 
     * @param sender El receptor del mensaje (jugador o consola)
     * @param path   Ruta del mensaje
     */
    public void send(CommandSender sender, String path) {
        send(sender, path, null);
    }

    /**
     * Enva un mensaje con placeholders a un CommandSender.
     * 
     * @param sender       El receptor del mensaje
     * @param path         Ruta del mensaje
     * @param placeholders Placeholders a reemplazar
     */
    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        String message = getMessage(path, placeholders);
        sender.sendMessage(message);
    }

    /**
     * Enva mltiples mensajes (til para listas).
     * 
     * @param sender   El receptor
     * @param messages Lista de mensajes a enviar
     */
    public void sendMultiple(CommandSender sender, List<String> messages) {
        messages.forEach(sender::sendMessage);
    }

    /**
     * Enva un ttulo y subttulo a un jugador.
     * 
     * @param player       El jugador
     * @param titlePath    Ruta del ttulo
     * @param subtitlePath Ruta del subttulo
     */
    public void sendTitle(Player player, String titlePath, String subtitlePath) {
        sendTitle(player, titlePath, subtitlePath, 10, 70, 20);
    }

    /**
     * Enva un ttulo y subttulo con timing personalizado.
     * 
     * @param player       El jugador
     * @param titlePath    Ruta del ttulo
     * @param subtitlePath Ruta del subttulo
     * @param fadeIn       Tiempo de aparicin (ticks)
     * @param stay         Tiempo de permanencia (ticks)
     * @param fadeOut      Tiempo de desaparicin (ticks)
     */
    public void sendTitle(Player player, String titlePath, String subtitlePath, int fadeIn, int stay, int fadeOut) {
        String title = getMessage(titlePath);
        String subtitle = getMessage(subtitlePath);
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    /**
     * Enva un mensaje en la action bar del jugador.
     * 
     * @param player El jugador
     * @param path   Ruta del mensaje
     */
    public void sendActionBar(Player player, String path) {
        sendActionBar(player, path, null);
    }

    /**
     * Enva un mensaje en la action bar con placeholders.
     * 
     * @param player       El jugador
     * @param path         Ruta del mensaje
     * @param placeholders Placeholders a reemplazar
     */
    public void sendActionBar(Player player, String path, Map<String, String> placeholders) {
        String message = getMessage(path, placeholders);
        player.spigot().sendMessage(
                net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                new net.md_5.bungee.api.chat.TextComponent(message));
    }

    /**
     * Convierte cdigos de color (&) y HEX (#RRGGBB o &#RRGGBB) a formato
     * Minecraft.
     * 
     * @param message Mensaje a colorizar
     * @return Mensaje con colores aplicados
     */
    private String colorize(String message) {
        if (message == null)
            return "";

        // Procesar colores HEX con formato &#RRGGBB (gradientes)
        Matcher gradientMatcher = GRADIENT_PATTERN.matcher(message);
        while (gradientMatcher.find()) {
            String hexCode = message.substring(gradientMatcher.start() + 1, gradientMatcher.end());
            try {
                String replacement = net.md_5.bungee.api.ChatColor.of(hexCode).toString();
                message = message.replace("&" + hexCode, replacement);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid hex color: " + hexCode);
            }
            gradientMatcher = GRADIENT_PATTERN.matcher(message);
        }

        // Procesar colores HEX con formato #RRGGBB (normal)
        Matcher hexMatcher = HEX_PATTERN.matcher(message);
        while (hexMatcher.find()) {
            String hexCode = message.substring(hexMatcher.start(), hexMatcher.end());
            try {
                String replacement = net.md_5.bungee.api.ChatColor.of(hexCode).toString();
                message = message.replace(hexCode, replacement);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid hex color: " + hexCode);
            }
            hexMatcher = HEX_PATTERN.matcher(message);
        }

        // Procesar cdigos de color tradicionales (&a, &c, etc.)
        message = ChatColor.translateAlternateColorCodes('&', message);

        return message;
    }

    /**
     * Cuenta el nmero total de mensajes cargados (para debug).
     * 
     * @return Nmero de claves en messages.yml
     */
    private int countMessages() {
        return countKeys(messages.getValues(true));
    }

    /**
     * Cuenta recursivamente las claves en un mapa.
     * 
     * @param map Mapa de configuracin
     * @return Nmero de claves finales (no secciones)
     */
    private int countKeys(Map<String, Object> map) {
        int count = 0;
        for (Object value : map.values()) {
            if (!(value instanceof Map)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Obtiene el prefijo configurado.
     * 
     * @return Prefijo formateado con colores
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Crea un builder de placeholders para facilitar su construccin.
     * 
     * Uso:
     * 
     * <pre>
     * Map&lt;String, String&gt; placeholders = MessageManager.placeholder()
     *         .add("player", player.getName())
     *         .add("time", "30")
     *         .build();
     * </pre>
     * 
     * @return Nueva instancia de PlaceholderBuilder
     */
    public static PlaceholderBuilder placeholder() {
        return new PlaceholderBuilder();
    }

    /**
     * Builder pattern para crear mapas de placeholders de forma fluida.
     */
    public static class PlaceholderBuilder {
        private final Map<String, String> placeholders = new HashMap<>();

        /**
         * Aade un placeholder string.
         * 
         * @param key   Nombre del placeholder (sin llaves)
         * @param value Valor del placeholder
         * @return Esta instancia para encadenar llamadas
         */
        public PlaceholderBuilder add(String key, String value) {
            placeholders.put(key, value);
            return this;
        }

        /**
         * Aade un placeholder de cualquier tipo.
         * Se convertir automticamente a String.
         * 
         * @param key   Nombre del placeholder (sin llaves)
         * @param value Valor del placeholder
         * @return Esta instancia para encadenar llamadas
         */
        public PlaceholderBuilder add(String key, Object value) {
            placeholders.put(key, String.valueOf(value));
            return this;
        }

        /**
         * Construye y retorna el mapa de placeholders.
         * 
         * @return Mapa de placeholders listo para usar
         */
        public Map<String, String> build() {
            return placeholders;
        }
    }
}

