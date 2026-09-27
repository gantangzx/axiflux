package com.gantang.tianshu.api.email;

import java.util.List;

/**
 * Strategy interface for sending emails.
 *
 * <p>Implementations may use SMTP (JavaMail), a transactional email API
 * (SendGrid, Mailgun, AWS SES), etc.
 *
 * <p>Design pattern: <b>Strategy</b> — the EmailSendTool delegates to whichever
 * provider is configured.
 */
public interface EmailProvider {

    /**
     * Send an email.
     *
     * @param to       recipient addresses
     * @param subject  email subject
     * @param body     email body (plain text or HTML)
     * @param html     whether body is HTML
     * @return         message ID or success reference
     */
    String send(List<String> to, String subject, String body, boolean html);

    /** Whether this provider is configured and available. */
    default boolean available() { return true; }

    /** Default sender address ("From" header). */
    default String fromAddress() { return "tianshu@localhost"; }
}
