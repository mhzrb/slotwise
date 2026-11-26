package com.slotwise.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development implementation: "sends" by logging. The recipient address is
 * masked; the body (which contains names and times) is only logged at DEBUG.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.email.sender", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.info("[email] to={} subject=\"{}\"", mask(message.to()), message.subject());
        log.debug("[email] body:\n{}", message.body());
    }

    /** {@code jane.doe@example.com -> j***@example.com} */
    static String mask(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
