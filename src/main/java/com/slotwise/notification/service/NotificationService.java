package com.slotwise.notification.service;

import com.slotwise.notification.domain.Notification;
import com.slotwise.notification.domain.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    public Page<Notification> listForRecipient(UUID userId, Pageable pageable) {
        return repository.findByRecipientId(userId, pageable);
    }
}
