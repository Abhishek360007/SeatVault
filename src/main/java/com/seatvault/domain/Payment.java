package com.seatvault.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single payment attempt against a booking.
 *
 * <h3>Idempotency</h3>
 * The unique {@code (booking_id, idempotency_key)} constraint is the backbone of
 * exactly-once charging: a client retry (or a double-click, or two app instances)
 * carrying the same key can only ever create one row. The first writer proceeds to
 * charge; any concurrent writer loses the INSERT race and instead replays the
 * winner's recorded result. See {@code PaymentService}.
 *
 * <p>Amounts are stored in <b>minor units</b> (e.g. paise/cents) as a {@code long}
 * to avoid floating-point money bugs. The amount is copied from the booking's frozen
 * payable amount, so the gateway always charges what the user agreed to at hold time.
 */
@Entity
@Table(
        name = "payments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_payments_booking_key",
                columnNames = {"booking_id", "idempotency_key"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class Payment extends BaseEntity {

    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status = PaymentStatus.INITIATED;

    @Column(name = "provider_reference", length = 120)
    private String providerReference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /** Provider that owns {@link #providerOrderId} ({@code razorpay}, {@code simulated}); null for direct charges. */
    @Column(length = 20)
    private String provider;

    /** The provider-side order the customer pays in the provider's widget; unique (V9). */
    @Column(name = "provider_order_id", length = 64, unique = true)
    private String providerOrderId;

    public Payment(Long bookingId, Long userId, String idempotencyKey, long amountMinor, String currency) {
        this.bookingId = bookingId;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = PaymentStatus.INITIATED;
    }

    // ---- State-transition helpers (keep invariants in one place) ----

    public void approve(String providerReference) {
        this.status = PaymentStatus.APPROVED;
        this.providerReference = providerReference;
        this.failureReason = null;
    }

    public void decline(String reason) {
        this.status = PaymentStatus.DECLINED;
        this.failureReason = reason;
    }

    /**
     * A single attempt on an open order failed (card declined, UPI timed out...).
     * The order stays INITIATED because the customer can retry on the same order;
     * only the latest reason is kept, for display.
     */
    public void recordAttemptFailure(String reason) {
        this.failureReason = reason;
    }

    /** Money was captured but the booking couldn't be confirmed, so it was returned. */
    public void refund(String providerReference, String reason) {
        this.status = PaymentStatus.REFUNDED;
        this.providerReference = providerReference;
        this.failureReason = reason;
    }

    public void fail(String reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
    }

    public boolean isApproved() {
        return status == PaymentStatus.APPROVED;
    }

    public boolean isInitiated() {
        return status == PaymentStatus.INITIATED;
    }
}
