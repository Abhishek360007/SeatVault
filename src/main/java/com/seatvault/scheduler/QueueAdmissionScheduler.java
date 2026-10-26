package com.seatvault.scheduler;

import com.seatvault.service.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically admits the next batch of waiting users for every high-demand event
 * that currently has someone in its wait line.
 *
 * <p>Each tick is independent per event (a slow/failing admission for one event
 * doesn't block the others), and the heavy lifting (Redis ZSET pop + token mint)
 * lives in {@link QueueService}; this class is just the trigger.
 */
@Component
public class QueueAdmissionScheduler {

    private static final Logger log = LoggerFactory.getLogger(QueueAdmissionScheduler.class);

    private final QueueService queueService;

    public QueueAdmissionScheduler(QueueService queueService) {
        this.queueService = queueService;
    }

    @Scheduled(
            fixedDelayString = "${app.waiting-room.admission-interval-ms:10000}",
            initialDelayString = "${app.waiting-room.admission-initial-delay-ms:15000}")
    public void admitNextBatch() {
        List<Long> activeEventIds = queueService.activeHighDemandEventIds();
        int totalAdmitted = 0;
        for (Long eventId : activeEventIds) {
            try {
                totalAdmitted += queueService.admitNextBatch(eventId);
            } catch (RuntimeException ex) {
                log.warn("Waiting-room admission tick failed for event={}: {}", eventId, ex.toString());
            }
        }
        if (totalAdmitted > 0) {
            log.info("Waiting-room admission tick admitted {} user(s) across {} event(s)",
                    totalAdmitted, activeEventIds.size());
        } else {
            log.debug("Waiting-room admission tick: nothing to admit");
        }
    }
}
