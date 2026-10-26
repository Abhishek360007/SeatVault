package com.seatvault.repository;

import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.repository.projection.EventAvailability;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Seat persistence, including the two locking finders that the booking service
 * uses (Phase 5).
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** Availability read for an event (cacheable, no lock). */
    List<Seat> findByEventIdAndStatus(Long eventId, SeatStatus status);

    List<Seat> findByEventIdOrderBySeatLabel(Long eventId);

    boolean existsByEventIdAndSeatLabel(Long eventId, String seatLabel);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    /** Seats belonging to a booking (for response mapping and confirm/cancel). */
    List<Seat> findByBookingId(Long bookingId);

    /**
     * One grouped query that returns the AVAILABLE-seat count for many events at
     * once — used to populate list responses without an N+1 per-event count.
     */
    @Query("""
            SELECT s.event.id AS eventId, COUNT(s) AS availableCount
            FROM Seat s
            WHERE s.event.id IN :eventIds AND s.status = com.seatvault.domain.SeatStatus.AVAILABLE
            GROUP BY s.event.id
            """)
    List<EventAvailability> availableCounts(@Param("eventIds") Collection<Long> eventIds);

    /**
     * PESSIMISTIC path: {@code SELECT ... FOR UPDATE}. Locks the matched seat rows
     * for the duration of the transaction so no other transaction can read-for-update
     * or write them until we commit. Writers serialize on the rows up front — no
     * retries needed — at the cost of holding DB row locks (and a connection) while
     * the booking transaction runs.
     *
     * <p>The {@code javax.persistence.lock.timeout} hint (ms) makes a blocked waiter
     * fail fast instead of hanging; 3s here. With Postgres this maps to
     * {@code SELECT ... FOR UPDATE} + a statement lock timeout.
     *
     * <p>Seats are locked in a deterministic order (ORDER BY id) to avoid deadlocks
     * when two multi-seat bookings overlap.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
            SELECT s FROM Seat s
            WHERE s.event.id = :eventId AND s.id IN :seatIds
            ORDER BY s.id
            """)
    List<Seat> findForUpdate(@Param("eventId") Long eventId, @Param("seatIds") List<Long> seatIds);

    /**
     * OPTIMISTIC path: a plain read. The {@code @Version} column does the work — when
     * the service mutates these and flushes, Hibernate's version-checked UPDATE fails
     * for any seat another transaction changed concurrently, raising
     * {@code OptimisticLockException}. No DB lock is held between read and write.
     */
    @Query("""
            SELECT s FROM Seat s
            WHERE s.event.id = :eventId AND s.id IN :seatIds
            ORDER BY s.id
            """)
    List<Seat> findAllForBooking(@Param("eventId") Long eventId, @Param("seatIds") List<Long> seatIds);

    /**
     * The scheduled job (Phase 6) uses this to find holds that have lapsed.
     * Idempotent by construction: it only ever matches still-HELD, past-expiry rows.
     */
    List<Seat> findByStatusAndHoldExpiresAtBefore(SeatStatus status, Instant cutoff);

    /**
     * Like {@link #findByStatusAndHoldExpiresAtBefore} but takes row locks and skips
     * rows another transaction already holds ({@code FOR UPDATE SKIP LOCKED}). This
     * makes the release job safe to run on multiple instances at once: each picks a
     * disjoint batch instead of blocking on each other, and a seat a user is
     * mid-paying (already locked by the confirm txn) is simply skipped this round.
     *
     * <p>{@code Pageable} caps the batch size so one tick can't lock the whole table.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // -2 = SKIP LOCKED (Hibernate)
    @Query("""
            SELECT s FROM Seat s
            WHERE s.status = com.seatvault.domain.SeatStatus.HELD
              AND s.holdExpiresAt < :cutoff
            ORDER BY s.id
            """)
    List<Seat> findExpiredHeldForUpdate(@Param("cutoff") Instant cutoff,
                                        org.springframework.data.domain.Pageable pageable);
}
