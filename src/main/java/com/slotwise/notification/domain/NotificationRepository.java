package com.slotwise.notification.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Claims a batch of due notifications. {@code SKIP LOCKED} lets several
     * dispatcher instances (or threads) work in parallel without ever picking
     * the same row twice and without blocking each other.
     */
    @Query(value = """
            SELECT * FROM notifications
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> lockDueBatch(@Param("now") Instant now, @Param("limit") int limit);

    Page<Notification> findByRecipientId(UUID recipientId, Pageable pageable);

    List<Notification> findByAppointmentIdOrderByCreatedAtAsc(UUID appointmentId);
}
