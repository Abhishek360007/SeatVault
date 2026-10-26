package com.seatvault.scheduler;

import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodically reconciles local booking states with payment providers.
 */
@Component
public class PaymentReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationScheduler.class);
    private final BookingRepository bookingRepository;

    public PaymentReconciliationScheduler(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    /**
     * Reconciliation sweep runs every 10 minutes to resolve any missed webhook notifications
     * for bookings that are about to expire.
     */
    @Scheduled(fixedDelayString = "${app.payment.reconciliation-interval-ms:600000}")
    public void reconcilePayments() {
        log.info("Starting payment reconciliation scan...");
        // Fetch pending bookings whose holds expire within the next 2 minutes
        Instant threshold = Instant.now().plus(2, ChronoUnit.MINUTES);
        List<Booking> pendingBookings = bookingRepository.findByStatusAndExpiresAtBefore(
                BookingStatus.PENDING, threshold);

        for (Booking booking : pendingBookings) {
            // TODO(decision): Query the selected payment provider API using booking ID/payment intent ID
            // to fetch transaction status. If transaction is marked succeeded in the provider dashboard
            // but is still PENDING locally, resolve state and call bookingService.confirm(...)
            log.warn("Booking id={} is PENDING near hold expiry. Checking status with payment provider...", booking.getId());
        }
    }
}
