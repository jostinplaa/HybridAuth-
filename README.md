# HybridAuth Premium 🛡️

![Version](https://img.shields.io/badge/version-1.1.0-blue.svg) ![Java](https://img.shields.io/badge/Java-17-orange.svg) ![Spigot](https://img.shields.io/badge/Spigot-1.20+-yellow.svg)

**HybridAuth** es una solución de autenticación avanzada para servidores de Minecraft híbridos (Premium/Cracked). Diseñado con seguridad de nivel empresarial y una experiencia de usuario fluida.

---

## 🚀 Características Premium

### 🔐 Seguridad Avanzada
- **Autenticación Híbrida**: Detección automática de jugadores Premium (Mojang) para auto-login seguro sin contraseñas.
- **BCrypt Hashing**: Las contraseñas se almacenan utilizando encriptación de grado militar.
- **Protección Anti-Bot**: Rate limiting inteligente con bloqueo progresivo de IPs.
- **Validación de Contraseñas**: Reglas configurables para forzar contraseñas fuertes.
- **Sanitización de Inputs**: Protección contra inyecciones y exploits.

### 💎 Experiencia de Usuario (UX)
- **Sistema de Mensajes Centralizado**: Todos los mensajes son configurables en `messages.yml`.
- **Soporte RGB**: Colores HEX y gradientes en todos los mensajes.
- **Feedback Visual**: Títulos, subtítulos y action bars interactivos.
- **Sonidos**: Efectos de sonido para eventos de éxito o error.
- **Restricciones Visuales**: Efectos de ceguera/lentitud configurables para usuarios no autenticados.

### 🛠️ Herramientas Administrativas
- **Comandos Seguros**: Sistema de confirmación para acciones destructivas (`unregister`).
- **Tab Completion**: Autocompletado inteligente para todos los comandos.
- **Estadísticas en Tiempo Real**: Visualiza usuarios totales, premium, cracked y sesiones activas.
- **Reload en Caliente**: Actualiza configuración y mensajes sin reiniciar.

---

## 📦 Instalación

1. Descarga el archivo `.jar` de la [sección de Releases](#).
2. Colócalo en la carpeta `plugins/` de tu servidor.
3. **Reinicia** el servidor.
4. (Opcional) Configura `config.yml` y `messages.yml` a tu gusto.

### Dependencias Requeridas
- **Java 17** o superior.
- **ProtocolLib** (necesario para la detección de paquetes Premium).

---

## ⚙️ Configuración

### base de datos
Soporta **SQLite** (por defecto) y **MySQL** para redes BungeeCord/Velocity.

```yaml
database:
  type: sqlite # o mysql
```

### Seguridad
Configura los límites de intentos y reglas de contraseña en `config.yml`.

```yaml
security:
  password:
    min-length: 8
    require-special-char: true
  rate-limit:
    max-attempts-per-ip: 5
    block-duration-seconds: 300
```

---

## 📝 Comandos y Permisos

| Comando | Descripción | Permiso |
|---------|-------------|---------|
| `/login <pass>` | Iniciar sesión | N/A |
| `/register <pass> <pass>` | Registrarse | N/A |
| `/changepassword <old> <new>` | Cambiar contraseña | N/A |
| `/logout` | Cerrar sesión actual | N/A |
| `/hybridauth reload` | Recargar config | `hybridauth.admin` |
| `/hybridauth unregister <user>` | Borrar cuenta de usuario | `hybridauth.admin` |
| `/hybridauth stats` | Ver estadísticas | `hybridauth.admin` |

---

## 🏗️ Audit & Compliance
Este plugin ha pasado una **Auditoría de Seguridad** completa (Enero 2026), resolviendo vulnerabilidades críticas como:
- ✅ Mensajes hardcodeados (Ahora 100% configurables)
- ✅ Validaciones de seguridad faltantes
- ✅ Feedback de usuario inexistente
- ✅ Gestión de sesiones insegura

---

**Desarrollado con ❤️ para la comunidad de Minecraft.**
