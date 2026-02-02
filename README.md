# HybridAuth - Sistema de Autenticación Híbrida

[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.20.4+-brightgreen)](https://www.spigotmc.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Size](https://img.shields.io/badge/Size-213KB-orange)](https://github.com/jostinplaa/HybridAuth/releases)

**Plugin de autenticación híbrida para servidores Minecraft que soporta tanto jugadores Premium como No-Premium.**

---

## 🌟 Características Principales

### ✅ Detección Premium Automática
- **Consulta Mojang API** para verificación real en servidores offline-mode
- Auto-login instantáneo para cuentas premium
- Sin necesidad de ProtocolLib

### 🔐 Seguridad Avanzada
- **Rate Limiting** con bloqueo de IP temporal
- **BCrypt** para hashing de contraseñas
- Detección de intentos de impostor
- Sistema de sesiones persistentes
- Registro de eventos de seguridad

### 💾 Almacenamiento Flexible
- **SQLite** (por defecto) - Sin configuración
- **MySQL/MariaDB** - Para servidores en red
- **HikariCP** para optimización de conexiones

### 🎨 Experiencia de Usuario
- Mensajes totalmente personalizables (`messages.yml`)
- Títulos y action bars animados
- Sistema de placeholders dinámicos
- Efectos de partículas y sonidos

### ⚡ Ultra-Ligero
- **Solo 213 KB** - Reducción del 99% vs versión anterior
- Paper Library Loader - Dependencias auto-descargadas
- Sin impacto en rendimiento

---

## 📦 Instalación

### Requisitos
- **Servidor:** Paper/Spigot 1.16.5+
- **Java:** 17+
- **Dependencias:** Ninguna (se descargan automáticamente)

### Pasos
1. Descarga `HybridAuth-1.1.0.jar` desde [Releases](https://github.com/jostinplaa/HybridAuth/releases)
2. Arrastra el archivo a la carpeta `plugins/` de tu servidor
3. Reinicia el servidor
4. ¡Listo! Las dependencias se descargan automáticamente

---

## ⚙️ Configuración

### config.yml
```yaml
database:
  type: SQLITE  # o MYSQL
  
mysql:
  host: localhost
  port: 3306
  database: hybridauth
  username: root
  password: ''

authentication:
  timeout-seconds: 60
  max-password-length: 30
  min-password-length: 6

security:
  rate-limit:
    enabled: true
    max-attempts: 5
    lockout-duration-seconds: 300
```

### Personalizar Mensajes
Edita `messages.yml` para cambiar todos los textos del plugin a tu idioma o estilo.

---

## 📖 Comandos

| Comando | Descripción | Permiso |
|---------|-------------|---------|
| `/register <pass> <pass>` | Registrarse | - |
| `/login <pass>` | Iniciar sesión | - |
| `/changepassword <old> <new>` | Cambiar contraseña | - |
| `/logout` | Cerrar sesión | - |
| `/hybridauth reload` | Recargar config | `hybridauth.admin` |
| `/hybridauth resetpassword <player>` | Resetear contraseña | `hybridauth.admin` |

---

## 🔧 Cómo Funciona

### Detección Premium en Offline Mode

```
┌─────────────────────────────────────┐
│  Player connects (offline-mode)     │
│  UUID = v3 (always, for everyone)   │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│  Query Mojang API (async)           │
│  GET /users/profiles/minecraft/NAME │
└──────────────┬──────────────────────┘
               │
       ┌───────┴────────┐
       │                │
    200 OK           404 Not Found
       │                │
       ▼                ▼
   PREMIUM          CRACKED
  Auto-login    Require /register
```

### Flujo de Autenticación

**Jugador Premium:**
1. Conexión → Consulta Mojang API
2. Cuenta existe → Auto-registro/login
3. Acceso inmediato ✅

**Jugador Cracked:**
1. Conexión → Consulta Mojang API
2. Cuenta NO existe → Mostrar mensaje
3. `/register <pass> <pass>` → Registrado
4. Próximas conexiones: `/login <pass>`

---

## 🏗️ Arquitectura

```
HybridAuth/
├── src/main/java/net/hybridauth/
│   ├── HybridAuthPlugin.java          # Plugin principal
│   ├── commands/                      # Comandos del jugador
│   │   ├── LoginCommand.java
│   │   ├── RegisterCommand.java
│   │   └── AdminCommand.java
│   ├── listeners/                     # Event listeners
│   │   ├── LoginListener.java         # Auto-login premium
│   │   └── SecurityListener.java
│   ├── network/netty/                 # Detección premium
│   │   └── PremiumDetector.java       # Mojang API query
│   ├── data/                          # Capa de datos
│   │   ├── DatabaseManager.java
│   │   ├── dao/
│   │   └── model/
│   ├── security/                      # Servicios de seguridad
│   │   ├── PasswordService.java       # BCrypt hashing
│   │   ├── RateLimitService.java      # Anti-spam
│   │   └── SecurityLogger.java
│   └── core/                          # Lógica central
│       ├── auth/
│       ├── session/
│       └── messages/
└── src/main/resources/
    ├── plugin.yml                     # Metadata del plugin
    ├── config.yml                     # Configuración
    └── messages.yml                   # Mensajes personalizables
```

---

## 🚀 Optimizaciones Realizadas

### Antes: 18.34 MB ❌
- Todos los drivers embebidos en el JAR
- Caffeine Cache incluido
- ProtocolLib como dependencia

### Ahora: 213 KB ✅
- Paper Library Loader
- Guava (viene con Spigot)
- Sin ProtocolLib
- **Reducción del 99%**

---

## 🤝 Contribuir

Las contribuciones son bienvenidas! Por favor:
1. Fork el repositorio
2. Crea una rama (`git checkout -b feature/nueva-funcionalidad`)
3. Commit tus cambios (`git commit -m 'Añadir nueva funcionalidad'`)
4. Push a la rama (`git push origin feature/nueva-funcionalidad`)
5. Abre un Pull Request

---

## 📄 Licencia

Este proyecto está bajo la Licencia MIT - ver [LICENSE](LICENSE) para detalles.

---

## 💬 Soporte

¿Necesitas ayuda?
- 🐛 [Reportar un bug](https://github.com/jostinplaa/HybridAuth/issues)
- 💡 [Sugerir una funcionalidad](https://github.com/jostinplaa/HybridAuth/issues)

---

## 🙏 Créditos

Desarrollado con ❤️ para la comunidad de Minecraft

**Inspirado por:**
- FastLogin
- nLogin
- OpeNLogin

---

**⭐ Si te gusta este proyecto, dale una estrella en GitHub!**
