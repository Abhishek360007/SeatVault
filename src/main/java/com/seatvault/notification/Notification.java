package com.seatvault.notification;

import com.seatvault.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * One outbound email in the outbox (V10).
 *
 * <pre>
 *   PENDING ──claim──▶ SENDING ──sent──▶ SENT
 *      ▲                  │
 *      └──retry (backoff)─┤
 *                         └──out of attempts / permanent──▶ FAILED
 * </pre>
 * Only the type and booking are stored; the email is rendered from the current
 * database state at send time, so a receipt always shows the final payment.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor
public class Notification extends BaseEntity {

    public enum Status { PENDING, SENDING, SENT, FAILED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(nullable = false)
    private String recipient;

    @Column(name = "dedupe_key", nullable = false, length = 100, unique = true)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** Exponential backoff for attempt n (1-based): 30s, 1m, 2m, 4m ... capped at 1h. */
    public static Duration backoff(int attempt) {
        long seconds = 30L << Math.min(Math.max(attempt - 1, 0), 7);
        return Duration.ofSeconds(Math.min(seconds, 3600));
    }

    public void markSent(Instant now) {
        this.status = Status.SENT;
        this.sentAt = now;
        this.leaseUntil = null;
        this.lastError = null;
    }

    /** A failed attempt: schedule a retry, or give up once {@code maxAttempts} are used. */
    public void markAttemptFailed(String error, Instant now, int maxAttempts, boolean permanent) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        this.leaseUntil = null;
        if (permanent || attempts >= maxAttempts) {
            this.status = Status.FAILED;
        } else {
            this.status = Status.PENDING;
            this.nextAttemptAt = now.plus(backoff(attempts));
        }
    }
}
