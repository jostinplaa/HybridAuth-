package net.hybridauth.email;

import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import net.hybridauth.HybridAuthPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * Servicio de envío de emails para HybridAuth
 * Soporta SendGrid API (recomendado) y SMTP tradicional
 * 
 * @version 1.5.0
 */
public class EmailService {

    private final HybridAuthPlugin plugin;
    private final boolean enabled;
    private final String provider; // "sendgrid" o "smtp"

    // SendGrid
    private final String sendGridAPIKey;
    private final String fromEmail;
    private final String fromName;

    // SMTP
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUsername;
    private final String smtpPassword;
    private final boolean smtpTLS;

    private final SecureRandom random = new SecureRandom();

    public EmailService(HybridAuthPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();

        this.enabled = config.getBoolean("email.enabled", false);
        this.provider = config.getString("email.provider", "sendgrid").toLowerCase();

        // SendGrid config
        this.sendGridAPIKey = config.getString("email.sendgrid.api-key", "");
        this.fromEmail = config.getString("email.from.email", "noreply@yourserver.com");
        this.fromName = config.getString("email.from.name", "HybridAuth");

        // SMTP config
        this.smtpHost = config.getString("email.smtp.host", "smtp.gmail.com");
        this.smtpPort = config.getInt("email.smtp.port", 587);
        this.smtpUsername = config.getString("email.smtp.username", "");
        this.smtpPassword = config.getString("email.smtp.password", "");
        this.smtpTLS = config.getBoolean("email.smtp.use-tls", true);

        if (enabled) {
            plugin.getLogger().info("[EmailService] Enabled with provider: " + provider);
        } else {
            plugin.getLogger().info("[EmailService] Disabled in config");
        }
    }

    /**
     * Envía un código de verificación de email
     */
    public boolean sendVerificationCode(String toEmail, String username, String code) {
        if (!enabled) {
            plugin.getLogger().warning("[EmailService] Attempted to send email but service is disabled");
            return false;
        }

        String subject = "Verifica tu email - " + getServerName();
        String body = buildVerificationEmail(username, code);

        return sendEmail(toEmail, subject, body);
    }

    /**
     * Envía un código de recuperación de cuenta
     */
    public boolean sendRecoveryCode(String toEmail, String username, String code) {
        if (!enabled) {
            plugin.getLogger().warning("[EmailService] Attempted to send email but service is disabled");
            return false;
        }

        String subject = "Recupera tu cuenta - " + getServerName();
        String body = buildRecoveryEmail(username, code);

        return sendEmail(toEmail, subject, body);
    }

    /**
     * Envía un email genérico
     */
    private boolean sendEmail(String toEmail, String subject, String htmlBody) {
        try {
            if ("sendgrid".equals(provider)) {
                return sendViaSendGrid(toEmail, subject, htmlBody);
            } else {
                return sendViaSMTP(toEmail, subject, htmlBody);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[EmailService] Error sending email: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Envía via SendGrid API (recomendado)
     */
    private boolean sendViaSendGrid(String toEmail, String subject, String htmlBody) throws IOException {
        if (sendGridAPIKey.isEmpty() || sendGridAPIKey.equals("YOUR_SENDGRID_API_KEY")) {
            plugin.getLogger().severe("[EmailService] SendGrid API key not configured!");
            return false;
        }

        Email from = new Email(fromEmail, fromName);
        Email to = new Email(toEmail);
        Content content = new Content("text/html", htmlBody);
        Mail mail = new Mail(from, subject, to, content);

        SendGrid sg = new SendGrid(sendGridAPIKey);
        Request request = new Request();

        try {
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sg.api(request);

            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                plugin.getLogger().info("[EmailService] Email sent successfully via SendGrid to " + toEmail);
                return true;
            } else {
                plugin.getLogger().warning(
                        "[EmailService] SendGrid error: " + response.getStatusCode() + " - " + response.getBody());
                return false;
            }
        } catch (IOException e) {
            plugin.getLogger().severe("[EmailService] SendGrid IO error: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Envía via SMTP tradicional (fallback)
     */
    private boolean sendViaSMTP(String toEmail, String subject, String htmlBody) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(smtpTLS));
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpUsername, smtpPassword);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromEmail, fromName));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject(subject);
            message.setContent(htmlBody, "text/html; charset=utf-8");

            Transport.send(message);
            plugin.getLogger().info("[EmailService] Email sent successfully via SMTP to " + toEmail);
            return true;

        } catch (MessagingException e) {
            plugin.getLogger().severe("[EmailService] SMTP error: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            plugin.getLogger().severe("[EmailService] Unexpected error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Genera un código aleatorio de 6 dígitos
     */
    public String generateCode() {
        return String.format("%06d", random.nextInt(1000000));
    }

    /**
     * Template HTML para email de verificación
     */
    private String buildVerificationEmail(String username, String code) {
        return "<!DOCTYPE html>" +
                "<html>" +
                "<head><meta charset='UTF-8'></head>" +
                "<body style='font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;'>" +
                "<div style='background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 30px; text-align: center; border-radius: 10px 10px 0 0;'>"
                +
                "<h1 style='color: white; margin: 0;'>🔐 HybridAuth</h1>" +
                "</div>" +
                "<div style='background: #f7f7f7; padding: 30px; border-radius: 0 0 10px 10px;'>" +
                "<h2 style='color: #333;'>Hola, " + username + "!</h2>" +
                "<p style='color: #666; font-size: 16px;'>Has solicitado verificar tu dirección de email.</p>" +
                "<div style='background: white; padding: 20px; border-radius: 5px; margin: 20px 0; text-align: center;'>"
                +
                "<p style='color: #999; font-size: 14px; margin: 0 0 10px 0;'>Tu código de verificación es:</p>" +
                "<h1 style='color: #667eea; font-size: 36px; letter-spacing: 5px; margin: 0;'>" + code + "</h1>" +
                "</div>" +
                "<p style='color: #666; font-size: 14px;'>Este código expirará en 15 minutos.</p>" +
                "<p style='color: #666; font-size: 14px;'>Si no solicitaste esto, ignora este email.</p>" +
                "<hr style='border: none; border-top: 1px solid #ddd; margin: 20px 0;'>" +
                "<p style='color: #999; font-size: 12px; text-align: center;'>Servidor: "
                + getServerName() + "</p>" +
                "</div>" +
                "</body>" +
                "</html>";
    }

    /**
     * Template HTML para email de recuperación
     */
    private String buildRecoveryEmail(String username, String code) {
        return "<!DOCTYPE html>" +
                "<html>" +
                "<head><meta charset='UTF-8'></head>" +
                "<body style='font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;'>" +
                "<div style='background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%); padding: 30px; text-align: center; border-radius: 10px 10px 0 0;'>"
                +
                "<h1 style='color: white; margin: 0;'>🔑 Recuperación de Cuenta</h1>" +
                "</div>" +
                "<div style='background: #f7f7f7; padding: 30px; border-radius: 0 0 10px 10px;'>" +
                "<h2 style='color: #333;'>Hola, " + username + "!</h2>" +
                "<p style='color: #666; font-size: 16px;'>Has solicitado recuperar tu cuenta.</p>" +
                "<div style='background: white; padding: 20px; border-radius: 5px; margin: 20px 0; text-align: center;'>"
                +
                "<p style='color: #999; font-size: 14px; margin: 0 0 10px 0;'>Tu código de recuperación es:</p>" +
                "<h1 style='color: #f5576c; font-size: 36px; letter-spacing: 5px; margin: 0;'>" + code + "</h1>" +
                "</div>" +
                "<p style='color: #666; font-size: 14px;'>Usa este código con:</p>" +
                "<p style='background: #fff; padding: 15px; border-left: 3px solid #f5576c; margin: 10px 0; font-family: monospace;'>"
                +
                "/hybrid recover " + username + " " + code + " &lt;nueva_contraseña&gt;</p>" +
                "<p style='color: #666; font-size: 14px;'>Este código expirará en 10 minutos.</p>" +
                "<p style='color: #f5576c; font-size: 14px; font-weight: bold;'>⚠️ Si no solicitaste esto, alguien está intentando acceder a tu cuenta!</p>"
                +
                "<hr style='border: none; border-top: 1px solid #ddd; margin: 20px 0;'>" +
                "<p style='color: #999; font-size: 12px; text-align: center;'>Servidor: "
                + getServerName() + "</p>" +
                "</div>" +
                "</body>" +
                "</html>";
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Helper: Bukkit no tiene getServerName(), usar motd o nombre configurado
     */
    private String getServerName() {
        String motd = plugin.getServer().getMotd();
        if (motd != null && !motd.isEmpty()) {
            // Limpiar códigos de color
            return motd.replaceAll("§[0-9a-fk-or]", "").trim();
        }
        return "Minecraft Server";
    }
}
