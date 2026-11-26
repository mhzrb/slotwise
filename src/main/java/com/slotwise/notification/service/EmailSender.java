package com.slotwise.notification.service;

/**
 * Delivery abstraction. Implementations throw on failure; the dispatcher
 * records the error and retries with backoff. Swap in an SMTP / SES /
 * SendGrid implementation without touching business code.
 */
public interface EmailSender {

    void send(EmailMessage message);
}
