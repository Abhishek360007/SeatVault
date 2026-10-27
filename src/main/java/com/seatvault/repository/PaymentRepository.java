package com.seatvault.repository;

import com.seatvault.domain.Payment;
import com.seatvault.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Payment persistence. The key query is the idempotency lookup
 * {@link #findByBookingIdAndIdempotencyKey}, which lets the payment flow detect a
 * replayed request and return the prior result instead of charging again.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Idempotency lookup: an existing attempt for this booking + client key. */
    Optional<Payment> findByBookingIdAndIdempotencyKey(Long bookingId, String idempotencyKey);

    /**
     * Serialization guard: is there already an in-flight ({@code INITIATED}) attempt
     * for this booking? Combined with the booking-row lock in
     * {@code PaymentService.claim}, this rejects a concurrent different-key payment
     * before it can reach the gateway.
     */
    boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    /** The most recent attempt for a booking (for read responses / "my tickets"). */
    Optional<Payment> findTopByBookingIdOrderByIdDesc(Long bookingId);

    /** The payment row for a provider order (unique, V9). */
    Optional<Payment> findByProviderOrderId(String providerOrderId);

    /** Latest payment of a booking in a given status, e.g. its open (INITIATED) order. */
    Optional<Payment> findTopByBookingIdAndStatusOrderByIdDesc(Long bookingId, PaymentStatus status);

    /** Open provider orders older than {@code cutoff}, for the reconciliation sweep. */
    @Query("""
            SELECT p FROM Payment p
            WHERE p.status = com.seatvault.domain.PaymentStatus.INITIATED
              AND p.providerOrderId IS NOT NULL
              AND p.provider = :provider
              AND p.createdAt < :cutoff
            ORDER BY p.createdAt
            """)
    List<Payment> findOpenOrdersCreatedBefore(@Param("cutoff") Instant cutoff, @Param("provider") String provider);
}
