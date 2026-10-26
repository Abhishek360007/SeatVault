package com.seatvault.service;

import com.seatvault.config.CacheConfig;
import com.seatvault.dto.booking.SeatStatusUpdate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

/**
 * Programmatic cache eviction for code paths where the affected {@code eventId}
 * only becomes known after loading entities (booking hold/confirm/cancel and the
 * expiry job). Declarative {@code @CacheEvict} with SpEL is used where the id is a
 * direct method argument (see {@code EventService}); this covers the rest.
 *
 * <p>Any seat state change alters an event's availability, so we evict that event's
 * detail and seat-map entries and clear the (small, short-TTL) search cache.
 *
 * <h3>Why evict AFTER commit</h3>
 * When called inside a transaction, eviction is deferred to {@code afterCommit}. If
 * we evicted mid-transaction, a concurrent reader could re-populate the cache with
 * the still-uncommitted old state before our commit lands, leaving stale data with
 * a fresh TTL. Deferring to after-commit closes that window. (If there's no active
 * transaction, we evict immediately.) Short TTLs in {@link CacheConfig} remain the
 * backstop.
 */
@Component
public class CacheInvalidator {

    private final CacheManager cacheManager;
    private final SimpMessagingTemplate messagingTemplate;

    public CacheInvalidator(CacheManager cacheManager, SimpMessagingTemplate messagingTemplate) {
        this.cacheManager = cacheManager;
        this.messagingTemplate = messagingTemplate;
    }

    /** Evict all cached reads whose contents depend on this event's availability. */
    public void evictEventAvailability(Long eventId) {
        evictEventAvailability(eventId, null);
    }

    /** Evict cache and broadcast real-time WebSocket seat updates. */
    public void evictEventAvailability(Long eventId, List<SeatStatusUpdate> updates) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doEvict(eventId);
                    broadcastWebSocket(eventId, updates);
                }
            });
        } else {
            doEvict(eventId);
            broadcastWebSocket(eventId, updates);
        }
    }

    private void doEvict(Long eventId) {
        evict(CacheConfig.EVENTS, eventId);
        evict(CacheConfig.EVENT_SEATS, eventId);
        clear(CacheConfig.EVENT_SEARCH);
    }

    private void broadcastWebSocket(Long eventId, List<SeatStatusUpdate> updates) {
        if (updates != null && !updates.isEmpty()) {
            try {
                messagingTemplate.convertAndSend("/topic/events/" + eventId + "/seats", updates);
            } catch (Exception ex) {
                // Fail-silent for WebSocket messaging to ensure transaction outcome is unaffected
            }
        }
    }

    private void evict(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.evictIfPresent(key);
        }
    }

    private void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }
}
