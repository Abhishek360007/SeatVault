package com.seatvault.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Adds an email to the outbox unless one with the same dedupe key exists.
     * {@code ON CONFLICT DO NOTHING} rather than catching a unique violation:
     * on PostgreSQL a violation aborts the whole transaction, and this runs inside
     * the booking's own transaction (confirm, cancel...), which must not fail
     * because an email was already queued. Returns 1 if queued, 0 if a duplicate.
     */
    @Modifying
    @Query(value = """
            INSERT INTO notifications (type, booking_id, recipient, dedupe_key, status, attempts,
                                       next_attempt_at, created_at, updated_at)
            VALUES (:type, :bookingId, :recipient, :dedupeKey, 'PENDING', 0, :now, :now, :now)
            ON CONFLICT (dedupe_key) DO NOTHING
            """, nativeQuery = true)
    int enqueue(@Param("type") String type, @Param("bookingId") Long bookingId,
                @Param("recipient") String recipient, @Param("dedupeKey") String dedupeKey,
                @Param("now") Instant now);

    /**
     * Locks up to {@code limit} due rows for this dispatcher: PENDING ones whose time
     * has come, and SENDING ones whose sender vanished (lease expired). SKIP LOCKED
     * lets several app instances dispatch side by side without sending twice.
     */
    @Query(value = """
            SELECT * FROM notifications
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'SENDING' AND lease_until < :now)
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query("UPDATE Notification n SET n.status = com.seatvault.notification.Notification.Status.SENDING, "
            + "n.leaseUntil = :leaseUntil WHERE n.id IN :ids")
    int lease(@Param("ids") List<Long> ids, @Param("leaseUntil") Instant leaseUntil);

    List<Notification> findByBookingIdOrderById(Long bookingId);
}
