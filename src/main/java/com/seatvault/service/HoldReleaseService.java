package com.seatvault.service;

import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Releases expired seat holds back to AVAILABLE and marks their bookings EXPIRED.
 *
 * <p>Split from the scheduler ({@link com.seatvault.scheduler.HoldExpiryScheduler})
 * so the {@code @Transactional} boundary is honoured (the scheduler calls across the
 * proxy) and so this logic is unit-testable in isolation.
 *
 * <h3>Why it's safe</h3>
 * <ul>
 *   <li><b>Concurrency-safe:</b> it selects expired HELD seats with
 *       {@code FOR UPDATE SKIP LOCKED}. A seat a user is actively confirming is
 *       already locked by that transaction, so the job skips it this round instead
 *       of racing — no risk of releasing a seat that's being paid for.</li>
 *   <li><b>Idempotent:</b> the query only matches still-HELD, past-expiry rows, and
 *       inside the lock we re-check the status before releasing. Running it twice
 *       (or on two instances) releases each seat at most once; a second pass finds
 *       nothing.</li>
 *   <li><b>Bounded:</b> processes at most {@code batchSize} seats per call so a
 *       backlog can't lock the whole table in one transaction; the scheduler loops
 *       until a tick comes back empty.</li>
 * </ul>
 *
 * <p>Releasing changes availability, so affected events' cached reads are evicted
 * via {@link CacheInvalidator} (after-commit).
 */
@Service
public class HoldReleaseService {

    private static final Logger log = LoggerFactory.getLogger(HoldReleaseService.class);

    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final CacheInvalidator cacheInvalidator;

    public HoldReleaseService(SeatRepository seatRepository,
                              BookingRepository bookingRepository,
                              CacheInvalidator cacheInvalidator) {
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.cacheInvalidator = cacheInvalidator;
    }

    /**
     * Release one batch of expired holds. Returns the number of seats released so the
     * scheduler knows whether to keep going.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int releaseBatch(int batchSize, Instant now) {
        List<Seat> expired = seatRepository.findExpiredHeldForUpdate(now, PageRequest.of(0, batchSize));
        if (expired.isEmpty()) {
            return 0;
        }

        Set<Long> bookingIds = new HashSet<>();
        java.util.Map<Long, List<com.seatvault.dto.booking.SeatStatusUpdate>> updatesByEvent = new java.util.HashMap<>();
        int released = 0;
        for (Seat seat : expired) {
            // Re-check under the lock: defends against a confirm that committed between
            // our SELECT and now (belt-and-suspenders; SKIP LOCKED already excludes
            // rows locked by an in-flight confirm).
            if (seat.getStatus() != SeatStatus.HELD
                    || seat.getHoldExpiresAt() == null
                    || !seat.getHoldExpiresAt().isBefore(now)) {
                continue;
            }
            if (seat.getBookingId() != null) {
                bookingIds.add(seat.getBookingId());
            }
            seat.release();
            released++;
            updatesByEvent.computeIfAbsent(seat.getEvent().getId(), k -> new java.util.ArrayList<>())
                    .add(new com.seatvault.dto.booking.SeatStatusUpdate(seat.getId(), "AVAILABLE", null));
        }
        seatRepository.saveAll(expired);

        // Flip the parent bookings to EXPIRED (only those still PENDING).
        if (!bookingIds.isEmpty()) {
            List<Booking> bookings = bookingRepository.findByIdInAndStatus(bookingIds, BookingStatus.PENDING);
            for (Booking booking : bookings) {
                booking.expire();
            }
            bookingRepository.saveAll(bookings);
        }

        // Availability for these events changed — drop their cached reads and broadcast updates.
        updatesByEvent.forEach(cacheInvalidator::evictEventAvailability);

        if (released > 0) {
            log.info("Released {} expired seat hold(s); expired {} booking(s)", released, bookingIds.size());
        }
        return released;
    }
}
