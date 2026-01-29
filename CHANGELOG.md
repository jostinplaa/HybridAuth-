# Changelog

Todas las mejoras notables de este proyecto serán documentadas en este archivo.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

### ✨ Added (Nuevas Características)
- **Centralized Message System**: Se implementó `MessageManager` para manejar todos los mensajes del plugin desde `messages.yml`.

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
