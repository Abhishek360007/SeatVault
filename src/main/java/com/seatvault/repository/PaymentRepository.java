package com.seatvault.repository;

import com.seatvault.domain.Payment;
import com.seatvault.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
