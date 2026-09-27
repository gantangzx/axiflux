package com.gantang.tianshu.spring.providers;

import com.gantang.tianshu.api.email.EmailProvider;
import com.gantang.tianshu.spring.config.props.EmailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Properties;

/**
 * SMTP email provider using JavaMail.
 *
 * <p>Supports STARTTLS and SSL connections. Configured via
 * {@code tianshu.email.*} properties.
 *
 * <p>Design pattern: <b>Strategy - concrete {@link EmailProvider}.
 */
public class SmtpEmailProvider implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailProvider.class);

    private final EmailProperties config;
    private final Session session;

    public SmtpEmailProvider(EmailProperties config) {
        this.config = config;
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getHost());
        props.put("mail.smtp.port", String.valueOf(config.getPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(config.isStarttls()));
        props.put("mail.smtp.starttls.required", String.valueOf(config.isStarttls()));

        this.session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getUsername(), config.getPassword());
            }
        });
    }

    @Override
    public String send(List<String> to, String subject, String body, boolean html) {
        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(
                config.getFrom() != null ? config.getFrom() : config.getUsername()));

            Address[] recipients = new Address[to.size()];
            for (int i = 0; i < to.size(); i++) {
                recipients[i] = new InternetAddress(to.get(i));
            }
            msg.setRecipients(Message.RecipientType.TO, recipients);
            msg.setSubject(subject, "UTF-8");

            if (html) {
                msg.setContent(body, "text/html; charset=UTF-8");
            } else {
                msg.setText(body, "UTF-8");
            }

            Transport.send(msg);
            String messageId = msg.getMessageID();
            log.info("Email sent to {}: '{}' (id={})", to, subject, messageId);
            return messageId != null ? messageId : "sent-" + System.currentTimeMillis();
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean available() {
        return config.getHost() != null && !config.getHost().isBlank()
            && config.getUsername() != null && !config.getUsername().isBlank();
    }

    @Override
    public String fromAddress() {
        return config.getFrom() != null ? config.getFrom() : config.getUsername();
    }
}
