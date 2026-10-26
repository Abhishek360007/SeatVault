package com.seatvault.repository;

import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** "My bookings", paginated. */
    Page<Booking> findByUserId(Long userId, Pageable pageable);

    /** Check if any bookings exist for a given event ID. */
    boolean existsByEventId(Long eventId);

    /**
     * Locks the booking row ({@code SELECT ... FOR UPDATE}). Used by the payment
     * claim to serialize all payment attempts for one booking, so two concurrent
     * {@code /pay} calls (even with different idempotency keys) cannot both create an
     * in-flight payment and reach the gateway.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    /**
     * The expiry sweep (Phase 6): PENDING bookings whose hold has lapsed. Marking
     * these EXPIRED alongside releasing their seats keeps booking state consistent.
     */
    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant cutoff);

    /**
     * PENDING bookings, among the given ids, whose hold has lapsed — used by the
     * release job to flip the parent bookings to EXPIRED after their seats are freed.
     */
    List<Booking> findByIdInAndStatus(java.util.Collection<Long> ids, BookingStatus status);
}
