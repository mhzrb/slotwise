package com.slotwise.notification.service;

public record EmailMessage(String to, String subject, String body) {

    @Override
    public String toString() {
        return "EmailMessage[to=" + LoggingEmailSender.mask(to) + ", subject=" + subject + "]";
    }
}
