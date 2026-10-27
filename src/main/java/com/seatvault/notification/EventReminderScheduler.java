package com.seatvault.notification;

import com.seatvault.domain.Booking;
import com.seatvault.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Queues "your event is soon" emails for confirmed bookings whose event starts
 * within {@code app.notifications.reminder-hours-before}. Runs every 15 minutes;
 * the outbox's dedupe key makes repeated runs harmless.
 *
 * <p>Bookings confirmed after the reminder window opened are skipped: their
 * confirmation email (with the tickets) went out moments ago.
 */
@Component
public class EventReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventReminderScheduler.class);

    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;
    private final Duration window;

    public EventReminderScheduler(BookingRepository bookingRepository,
                                  NotificationService notificationService,
                                  @Value("${app.notifications.reminder-hours-before:24}") int hoursBefore) {
        this.bookingRepository = bookingRepository;
        this.notificationService = notificationService;
        this.window = Duration.ofHours(hoursBefore);
    }

    @Scheduled(fixedDelayString = "${app.notifications.reminder-interval-ms:900000}", initialDelay = 30_000)
    public void tick() {
        try {
            queueReminders(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Reminder run failed; will retry next tick", e);
        }
    }

    /** Queues reminders that are due at {@code now}; returns how many bookings were considered. */
    @Transactional
    public int queueReminders(Instant now) {
        List<Booking> upcoming = bookingRepository.findConfirmedForEventsStartingBetween(now, now.plus(window));
        int considered = 0;
        for (Booking booking : upcoming) {
            Instant windowOpened = booking.getEvent().getEventDateTime().minus(window);
            if (booking.getConfirmedAt() != null && booking.getConfirmedAt().isBefore(windowOpened)) {
                notificationService.enqueue(NotificationType.EVENT_REMINDER, booking);
                considered++;
            }
        }
        return considered;
    }
}
