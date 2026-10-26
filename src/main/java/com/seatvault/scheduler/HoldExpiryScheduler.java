package com.seatvault.scheduler;

import com.seatvault.service.HoldReleaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Periodically drains expired seat holds.
 *
 * <p>Runs on a fixed delay (gap measured from the end of the previous run, so ticks
 * never overlap on a single instance). Each tick loops {@link HoldReleaseService}
 * in bounded batches until a batch comes back empty, so a backlog is cleared
 * promptly without any single transaction locking too many rows.
 *
 * <p>The heavy lifting (locking + state checks) lives in the service; this class is
 * just the trigger, kept thin and free of business logic.
 */
@Component
public class HoldExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);

    /** Safety cap on batches per tick, so one tick can't run unbounded. */
    private static final int MAX_BATCHES_PER_TICK = 50;

    private final HoldReleaseService holdReleaseService;
    private final int batchSize;

    public HoldExpiryScheduler(HoldReleaseService holdReleaseService,
                               @Value("${app.booking.release-batch-size:200}") int batchSize) {
        this.holdReleaseService = holdReleaseService;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.booking.release-interval-ms:30000}",
            initialDelayString = "${app.booking.release-initial-delay-ms:15000}")
    public void releaseExpiredHolds() {
        Instant now = Instant.now();
        int totalReleased = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            int released = holdReleaseService.releaseBatch(batchSize, now);
            totalReleased += released;
            if (released < batchSize) {
                break; // drained
            }
        }
        if (totalReleased > 0) {
            log.info("Hold-expiry tick released {} seat(s) total", totalReleased);
        } else {
            log.debug("Hold-expiry tick: nothing to release");
        }
    }
}
