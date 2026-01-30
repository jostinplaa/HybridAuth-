# Changelog

Todas las mejoras notables de este proyecto serán documentadas en este archivo.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

## [1.2.2] - 2026-01-29 (Hash Fix)
- **Fix Critical**: Corregido el relleno RSA a `PKCS1Padding` para coincidir con Minecraft.
- **Fix**: Esto soluciona el "Error 204" (Hash Mismatch) donde Mojang rechazaba la sesión válida.
- **Core**: Añadida verificación de longitud del `sharedSecret` para detectar errores de desencriptación.

## [1.2.1] - 2026-01-29 (Verification Debug)
- **Fix**: Añadido `User-Agent` a las peticiones MojangAPI para evitar bloqueos (403 Forbidden).
- **Core**: Logs detallados en consola (`responseCode`) para diagnosticar fallos de verificación.
- **Protocol**: Corregido error de sintaxis en `MojangAPI.java`.

## [1.2.0] - 2026-01-29 (Smart Reconnect Protocol)
- **Feature**: Implementado **Smart Reconnect** para Auto-Login Seguro sin crashes.
- **Flow**: Join -> Handshake -> **Kick (Verified)** -> Rejoin -> **Auto-Login**.
- **Security**: Impostores (Cracked con nick Premium) NUNCA pasan la primera fase (Kick/Block).
- **Hybrid**: Usuarios No-Premium pueden entrar y registrarse normalmente.
- **Fix**: Solucionado `DecoderException` al evitar la encriptación persistente en servidor offline.

## [1.1.10] - 2026-01-29 (Stable First-Connection Policy)
- **Revert**: Se retiró el "Secure Handshake" debido a incompatibilidad con Spigot no-NMS (DecoderException).
- **Core**: Restaurada la "Política de Primera Conexión" (Solución Estable).
- **Security**: Usuarios NUEVOS siempre son tratados como Cracked (requieren /register) para prevenir impostores.
- **Trust**: El Auto-Login solo se da si el usuario YA ESTÁ verificado en la Base de Datos.

## [1.1.9] - 2026-01-29 (Strict Security Patch)
- **Fix**: Solucionado error `unknown packet id 52` (DecoderException) al fallar handshake.
- **Security**: Ahora los impostores (Fallan Handshake) son **kick** en vez de fallback (Evita bugs de protocolo).
- **Core**: Mejora en la generación de hash para validar sesión Mojang.

## [1.1.8] - 2026-01-29 (Protocol Fix)
- **Fix**: Solucionado crash `FieldAccessException` en 1.21+ al enviar Encryption Packet.
- **Fix**: Corregido formato de `PublicKey` en handshake (ahora usa `byte[]` en vez de Object).
- **Core**: Optimización de Timeouts para evitar tareas zombies.

## [1.1.7] - 2026-01-29 (Secure Handshake)
- **Feature**: Implementado **Secure Encryption Handshake** usando ProtocolLib.
- **Security**: Verificación criptográfica real con Mojang antes de dar Auto-Login.
- **Fix**: Impostores (Cracked usando nicks Premium) fallan el handshake y son forzados a registrarse (`/register`).
- **Fix**: Corrección de timeout para evitar conexiones colgadas.

## [1.1.6] - 2026-01-29 (Definitive Premium Logic)
- **Fix Critical**: Se deshabilitó la comprobación de Mojang API para usuarios nuevos.
- **Security**: Implementada "Política de Primera Conexión" (First Connection Policy).
- **Protection**: Evita que usuarios cracked roben cuentas premium o viceversa.

## [1.1.5] - 2026-01-29 (Manual Hotfix)
- **Fix Critical**: Corrección manual de `EncryptionHandler` y `LoginListener` según reporte de bugs.
- **Fix**: Solucionado Race Condition en detección Premium.
- **Fix**: Limpieza de estado correcta al desconectar.

## [1.1.4] - 2026-01-29 (UX & Logic Hotfix)
- **Fix**: Eliminados mensajes de error duplicados en Registro y Cambio de Contraseña.
- **Fix**: Corrección lógica en detección Premium (ahora detecta Mojang status al registrarse).
- **Mejora**: Agregado mensaje explicito de longitud mínima de contraseña (8 caracteres).

## [1.1.3] - 2026-01-29 (Security Patch)
- **Seguridad**: Deshabilitada la detección automática de Premium insegura para evitar falsos positivos por caché.

## [1.1.2] - 2026-01-29 (Database Hotfix)
- **Fix**: Solucionado error `SQLException` por formato de fecha incorrecto en bases de datos antiguas (Milisegundos vs Timestamp).

## [1.1.0] - 2026-01-29 (Premium Upgrade)
### ✨ Added (Nuevas Características)
- **Tab Completion**: Autocompletado para todos los comandos (`/login`, `/register`, `/hybridauth`).
- **Confirmation System**: Los comandos destructivos (`unregister`) ahora requieren confirmación explícita.
- **Visual & Audio Feedback**: Añadidos títulos, subtítulos, action bars y sonidos para eventos de login/registro.
- **Validation Utils**: Nuevas utilidades para validación estricta de usernames y passwords.
- **Audit Compliance**: Se resolvieron todas las vulnerabilidades críticas del reporte de auditoría.

### 🔧 Changed (Cambios)
- **Refactorización de Comandos**: Todos los comandos (`Login`, `Register`, `Admin`, etc.) ahora usan el sistema de mensajes centralizado.
- **Admin Tools**: El comando `/hybridauth stats` ahora muestra datos reales de la base de datos (Premium vs Cracked users).
- **Security Listener**: Mejorado el mensaje de "Rate Limit Kick" para ser informativo y configurable.
- **Configuración**: Se reestructuró `config.yml` y se creó un `messages.yml` completo con soporte para HEX colors.

### 🐛 Fixed (Arreglos)
- Corregido `NullPointerException` en inicialización de base de datos.
- Eliminados mensajes hardcodeados que impedían la traducción.
- Corregidos problemas de consistencia en feedback al usuario.

---

## [1.0.0] - 2026-01-15 (Initial Release)
- Lanzamiento inicial con soporte básico para SQLite/MySQL.
- Detección de usuarios Premium.
- Sistema de Rate Limiting.
- Autenticación básica (Login/Register).
