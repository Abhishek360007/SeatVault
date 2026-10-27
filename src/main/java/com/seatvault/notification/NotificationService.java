package com.seatvault.notification;

import com.seatvault.domain.Booking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Queues emails in the outbox. Callers are the booking state changes themselves
 * (confirm, cancel, refund, the reminder sweep), and the row is written in their
 * transaction ({@link Propagation#MANDATORY}), so the email exists if and only if
 * the change committed. Sending happens later, in {@link EmailDispatcher}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final boolean enabled;

    public NotificationService(NotificationRepository repository,
                               @Value("${app.notifications.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    /** Queues {@code type} for this booking's owner; a second call for the same pair is a no-op. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(NotificationType type, Booking booking) {
        if (!enabled) {
            return;
        }
        int queued = repository.enqueue(type.name(), booking.getId(), booking.getUser().getEmail(),
                type.name() + ":" + booking.getId(), Instant.now());
        if (queued > 0) {
            log.debug("Queued {} email for booking={}", type, booking.getId());
        }
    }
}
