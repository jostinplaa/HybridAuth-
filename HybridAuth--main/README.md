# 🔐 HybridAuth

**Advanced Hybrid Authentication System for Minecraft 1.21.1**

A professional-grade authentication plugin that seamlessly supports Premium (Mojang), Cracked, and Bedrock (Geyser/Floodgate) players on the same server.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## ✨ Features

### 🎮 Hybrid Player Support
- **Premium Players**: Auto-login via Mojang authentication
- **Cracked Players**: Traditional username/password system
- **Bedrock Players**: Full Geyser/Floodgate compatibility

### 🛡️ Advanced Security
- **IP Blacklisting**: Temporary and permanent IP bans
- **Rate Limiting**: Prevents brute-force attacks
- **CAPTCHA System**: Math-based bot prevention
- **Security Logging**: Comprehensive audit trail
- **Discord Webhooks**: Real-time security alerts

### 🔥 Smart Command System (v1.4.0)
- **Account Type Detection**: Automatically identifies Premium/Cracked/Bedrock
- **Intelligent Validation**: Prevents illogical operations
  - Premium players can't register (already auto-registered)
  - Premium players can't login manually (use auto-login)
  - Premium players can't change passwords (Mojang account)
  - Admins can't unregister Premium accounts (protected)

### 💾 Flexible Storage
- **SQLite**: Zero-config local storage
- **MySQL/MariaDB**: Production-ready remote database
- **Session Manager**: Persistent login sessions

### 🎨 User Experience
- **Action Bar Feedback**: Real-time status updates
- **Title Messages**: Beautiful login/register screens
- **Sound Effects**: Audio feedback for actions
- **Multi-language**: Fully customizable messages

---

## 📦 Installation

1. Download the latest release: [HybridAuth-v1.4.0.jar](https://github.com/jostinplaa/HybridAuth/releases)
2. Place in your server's `plugins/` folder
3. Start the server (config files auto-generate)
4. Configure `config.yml` and `messages.yml`
5. Restart the server

---

## 🔧 Configuration

### Database Setup

**SQLite (Default - Recommended for small servers)**:
```yaml
database:
  type: SQLITE
```

**MySQL (Recommended for production)**:
```yaml
database:
  type: MYSQL
  mysql:
    host: localhost
    port: 3306
    database: hybridauth
    username: your_user
    password: your_password
```

### Security Settings

```yaml
security:
  # Rate limiting
  rate-limit:
    enabled: true
    max-attempts-per-ip: 5
    lockout-duration-seconds: 300

  # Session management
  session-timeout-minutes: 60
  auto-login-premium: true

  # Captcha
  captcha:
    enabled: true
    max-failed-attempts: 3
```

### Discord Webhooks

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/YOUR_WEBHOOK"
  notifications:
    impostor-detected: true
    blacklist-events: true
    brute-force-attempts: true
```

---

## 📋 Commands

### Player Commands
| Command | Permission | Description |
|---------|-----------|-------------|
| `/register <pass> <pass>` | `hybridauth.register` | Register a new account |
| `/login <password>` | `hybridauth.login` | Login to your account |
| `/changepassword <old> <new>` | `hybridauth.changepass` | Change your password |

### Admin Commands
| Command | Permission | Description |
|---------|-----------|-------------|
| `/hybridauth reload` | `hybridauth.admin` | Reload configuration |
| `/hybridauth unregister <player>` | `hybridauth.admin` | Unregister a player* |
| `/hybridauth stats` | `hybridauth.admin` | View system statistics |

*Cannot unregister Premium accounts (protected)

### Security Commands
| Command | Permission | Description |
|---------|-----------|-------------|
| `/security blacklist add <ip> <duration> [reason]` | `hybridauth.security` | Blacklist an IP |
| `/security blacklist remove <ip>` | `hybridauth.security` | Remove IP from blacklist |
| `/security blacklist list` | `hybridauth.security` | List all blacklisted IPs |
| `/security blacklist info <ip>` | `hybridauth.security` | View blacklist details |
| `/security stats` | `hybridauth.security` | View security statistics |

---

## 🎯 How It Works

### Premium Players
```
1. Player joins with Mojang account (UUID v4)
2. HybridAuth detects premium UUID
3. Auto-login activates (no password needed)
4. Player immediately joins the game
```

### Cracked Players
```
1. Player joins with cracked launcher
2. HybridAuth detects offline UUID (v3)
3. Player must /register or /login
4. After authentication, joins the game
```

### Bedrock Players
```
1. Player joins via Geyser/Floodgate
2. HybridAuth detects Bedrock prefix/UUID
3. Treated as cracked (must register/login)
4. Full compatibility maintained
```

---

## 🔒 Security Features

### IP Blacklisting
- **Temporary Bans**: Auto-expire after specified duration
- **Permanent Bans**: Manual removal only
- **Automatic Cleanup**: Expired entries removed hourly

### Rate Limiting
- **Per-IP Tracking**: Prevents distributed attacks
- **Automatic Lockout**: Temporary IP ban after max attempts
- **Kick on Limit**: Players are kicked with formatted message showing remaining time

### CAPTCHA System
- **Math Challenges**: Simple arithmetic (e.g., "7 + 3 = ?")
- **Triggered After**: Configurable failed login attempts
- **Timeout**: 60 seconds to solve
- **Blacklist on Fail**: IPs that fail CAPTCHA are blacklisted

---

## 📊 Statistics

View real-time statistics with `/hybridauth stats`:
- Total registered users
- Premium vs Cracked breakdown
- Active sessions
- Recent security events

---

## 🚀 Version History

### v1.4.0 (Current) - Command System Refactoring
- ✨ Account type detection (Premium/Cracked/Bedrock)
- 🛡️ Smart command validation
- ❌ Removed useless `/logout` command
- 📝 8 new contextual error messages
- 🔒 Premium account protection in admin commands

### v1.3.0 - Security Features
- 🛡️ IP blacklist system
- 🔔 Discord webhook integration
- 🤖 CAPTCHA service
- 📊 Security statistics

### v1.1.0 - Initial Release
- ⚡ Premium auto-login
- 🔐 Cracked player authentication
- 💾 SQLite/MySQL support
- 🎮 Session management

---

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 👨‍💻 Author

**JostinPlaa**
- GitHub: [@jostinplaa](https://github.com/jostinplaa)

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

## ⭐ Support

If you find this plugin useful, please give it a star ⭐ on GitHub!

---

**Made with ❤️ for the Minecraft community**
