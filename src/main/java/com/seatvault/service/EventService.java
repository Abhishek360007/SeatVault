package com.seatvault.service;

import com.seatvault.domain.EventCategory;
import com.seatvault.dto.event.EventSearchCriteria;
import com.seatvault.repository.EventSpecifications;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.seatvault.config.CacheConfig;
import com.seatvault.domain.Event;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.dto.PagedResponse;
import com.seatvault.dto.event.EventRequest;
import com.seatvault.dto.event.EventResponse;
import com.seatvault.dto.event.SeatResponse;
import com.seatvault.exception.BadRequestException;
import com.seatvault.exception.ConflictException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.projection.EventAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Event lifecycle (admin CRUD) and public browse/search.
 *
 * <p>Authorization is enforced at the controller via {@code @PreAuthorize}; this
 * service assumes the caller is already permitted and focuses on business rules:
 * seat generation, immutability of seating after creation, and safe deletion.
 *
 * <h2>Caching (Phase 7)</h2>
 * Read methods are {@code @Cacheable} in Redis; every write {@code @CacheEvict}s the
 * affected caches so stale availability is never served after a hold/booking change.
 * Note that <b>booking writes also touch seat availability</b>, so the booking flow
 * evicts these caches too (see {@code BookingService}); short TTLs (CacheConfig) are
 * the safety net if any eviction is ever missed.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    /** Guard rail so a typo can't try to create millions of seat rows. */
    private static final int MAX_SEATS_PER_EVENT = 100_000;

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;

    public EventService(EventRepository eventRepository,
                        SeatRepository seatRepository,
                        BookingRepository bookingRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
    }

    // ============================ Admin writes ============================

    private static final String DEFAULT_CURRENCY = "INR";

    /** Creating an event changes the listing, so evict the search cache. */
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.EVENT_SEARCH, allEntries = true)
    public EventResponse create(EventRequest request) {
        Event event = new Event(
                request.title(),
                request.description(),
                request.venue(),
                request.eventDateTime(),
                0 // capacity set below once seats are generated
        );
        // Pricing — defaults keep the existing create flow working when omitted.
        event.setCurrency(request.currency() != null ? request.currency().toUpperCase() : DEFAULT_CURRENCY);
        event.setBasePriceMinor(request.basePriceMinor() != null ? request.basePriceMinor() : 0L);
        event.setConvenienceFeeMinor(request.convenienceFeeMinor() != null ? request.convenienceFeeMinor() : 0L);
        event.setHighDemand(Boolean.TRUE.equals(request.highDemand()));
        event.setCategory(request.category() != null ? request.category() : EventCategory.OTHER);
        event.setCity(normalizeCity(request.city()));
        event.setImageUrl(blankToNull(request.imageUrl()));

        List<Seat> seats = generateSeats(event, request);
        event.setTotalCapacity(seats.size());
        seats.forEach(event::addSeat);

        event = eventRepository.save(event); // cascades seats
        log.info("Created event id={} '{}' with {} seats (currency={}, base={} fee={})",
                event.getId(), event.getTitle(), seats.size(),
                event.getCurrency(), event.getBasePriceMinor(), event.getConvenienceFeeMinor());
        return toResponse(event, seats.size()); // all seats AVAILABLE on creation
    }

    /**
     * Updates event metadata only. Seating is intentionally immutable after
     * creation: changing capacity/labels could orphan or invalidate seats that
     * are already held or booked. Callers who need different seating create a new
     * event.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.EVENTS, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.EVENT_SEARCH, allEntries = true)
    })
    public EventResponse update(Long id, EventRequest request) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        event.setTitle(request.title());
        event.setDescription(request.description());
        event.setVenue(request.venue());
        event.setEventDateTime(request.eventDateTime());
        if (request.highDemand() != null) {
            event.setHighDemand(request.highDemand());
        }
        if (request.category() != null) {
            event.setCategory(request.category());
        }
        if (request.city() != null) {
            event.setCity(normalizeCity(request.city()));
        }
        if (request.imageUrl() != null) {
            event.setImageUrl(blankToNull(request.imageUrl()));
        }
        // capacity/seats deliberately untouched
        long available = seatRepository.countByEventIdAndStatus(id, SeatStatus.AVAILABLE);
        log.info("Updated event id={}", id);
        return toResponse(event, available);
    }

    /**
     * Deletes an event. Seats cascade (FK ON DELETE CASCADE), but if any bookings
     * reference the event the DB FK blocks it — we translate that into a clean 409
     * rather than letting a constraint violation bubble up as a 500.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.EVENTS, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.EVENT_SEATS, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.EVENT_SEARCH, allEntries = true)
    })
    public void delete(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        if (bookingRepository.existsByEventId(id)) {
            throw new ConflictException("Cannot delete an event that has bookings");
        }
        eventRepository.delete(event);
        log.info("Deleted event id={}", id);
    }

    // ============================ Public reads ============================

    /**
     * Filtered, sorted, paginated browse. Cached per (criteria, page, size) with a
     * short TTL because availability drifts as people book; explicit eviction on
     * event writes keeps the listing fresh.
     *
     * <p>Only the page number and size are taken from {@code pageable}; ordering
     * comes from {@code criteria.sort()} so clients can't sort on arbitrary columns.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.EVENT_SEARCH,
            key = "T(java.util.Objects).hash(#criteria, #pageable.pageNumber, #pageable.pageSize)")
    public PagedResponse<EventResponse> search(EventSearchCriteria criteria, Pageable pageable) {
        Page<Event> page = eventRepository.findAll(
                EventSpecifications.from(criteria),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortFor(criteria)));

        // Single grouped query for available counts across this page (no N+1).
        List<Long> ids = page.getContent().stream().map(Event::getId).toList();
        Map<Long, Long> availability = ids.isEmpty()
                ? Map.of()
                : seatRepository.availableCounts(ids).stream()
                    .collect(Collectors.toMap(EventAvailability::getEventId, EventAvailability::getAvailableCount));

        Page<EventResponse> mapped = page.map(e ->
                toResponse(e, availability.getOrDefault(e.getId(), 0L)));
        return PagedResponse.from(mapped);
    }

    /** Cities with upcoming events, for the browse filter. */
    @Transactional(readOnly = true)
    public List<String> upcomingCities() {
        return eventRepository.findDistinctCities(Instant.now());
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.EVENTS, key = "#id")
    public EventResponse getById(Long id) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        long available = seatRepository.countByEventIdAndStatus(id, SeatStatus.AVAILABLE);
        return toResponse(event, available);
    }

    /**
     * Seats for an event, optionally filtered by status (e.g. only AVAILABLE for a
     * seat-picker UI). Ordered by label for stable display. Only the unfiltered
     * full-seat-map view is cached (the common seat-picker read); status-filtered
     * queries hit the DB so they always reflect the latest availability.
     *
     * <p>A hold that has lapsed but not yet been swept is reported as AVAILABLE: the
     * hold path reclaims such seats on contact (see {@code SeatReservationService}),
     * so the picker should offer them rather than wait for the release job.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.EVENT_SEATS, key = "#eventId", condition = "#statusFilter == null")
    public List<SeatResponse> getSeats(Long eventId, SeatStatus statusFilter) {
        if (!eventRepository.existsById(eventId)) {
            throw notFound(eventId);
        }
        List<Seat> seats = (statusFilter == null)
                ? seatRepository.findByEventIdOrderBySeatLabel(eventId)
                : seatRepository.findByEventIdAndStatus(eventId, statusFilter);
        Instant now = Instant.now();
        return seats.stream()
                .sorted((a, b) -> a.getSeatLabel().compareTo(b.getSeatLabel()))
                .map(s -> new SeatResponse(s.getId(), s.getSeatLabel(),
                        s.isHoldExpired(now) ? SeatStatus.AVAILABLE : s.getStatus(),
                        s.getTierName(), s.effectivePriceMinor()))
                .toList();
    }

    // ============================ Helpers ============================

    /**
     * Popularity is ordered inside the specification (it needs a subquery), so it
     * gets an unsorted page here; the other keys get id as a stable tie-breaker.
     */
    private Sort sortFor(EventSearchCriteria criteria) {
        Sort.Direction dir = criteria.descending() ? Sort.Direction.DESC : Sort.Direction.ASC;
        return switch (criteria.sort()) {
            case POPULARITY -> Sort.unsorted();
            case PRICE -> Sort.by(dir, "basePriceMinor").and(Sort.by("eventDateTime", "id"));
            case DATE -> Sort.by(dir, "eventDateTime").and(Sort.by("id"));
        };
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** Trims, and stores blank as null so blank cities never show up in the filter list. */
    private String normalizeCity(String city) {
        return StringUtils.hasText(city) ? city.trim() : null;
    }

    /**
     * Generates the seat set from the request, applying tier pricing. Exactly one
     * seating strategy must be usable; ambiguous or missing input is a 400 (the
     * cross-field rule lives here so it is explicit and unit-testable).
     *
     * <p>Grid mode supports optional {@code tiers}: each tier covers a number of rows
     * from the front, in order, getting its name + price; rows beyond the declared
     * tiers fall back to the event base price (no tier name). Flat mode is always
     * base-priced.
     */
    private List<Seat> generateSeats(Event event, EventRequest request) {
        boolean hasGrid = request.rows() != null && request.seatsPerRow() != null;
        boolean hasFlat = request.totalCapacity() != null;

        if (hasGrid) {
            long total = (long) request.rows() * request.seatsPerRow();
            guardSeatCount(total);
            validateTiers(request.tiers(), request.rows());
            List<Seat> seats = new ArrayList<>((int) total);
            for (int r = 0; r < request.rows(); r++) {
                EventRequest.PriceTier tier = tierForRowIndex(request.tiers(), r);
                String rowLabel = rowLabel(r);
                for (int s = 1; s <= request.seatsPerRow(); s++) {
                    Seat seat = new Seat(event, rowLabel + s);
                    if (tier != null) {
                        seat.setTierName(tier.name());
                        seat.setPriceMinor(tier.priceMinor());
                    }
                    seats.add(seat);
                }
            }
            return seats;
        }
        if (hasFlat) {
            guardSeatCount(request.totalCapacity());
            List<Seat> seats = new ArrayList<>(request.totalCapacity());
            for (int i = 1; i <= request.totalCapacity(); i++) {
                seats.add(new Seat(event, "S" + i)); // base-priced, untiered
            }
            return seats;
        }
        throw new BadRequestException(
                "Provide either totalCapacity, or both rows and seatsPerRow, to generate seats");
    }

    /** Tiers may not claim more rows than the event has. */
    private void validateTiers(List<EventRequest.PriceTier> tiers, int rows) {
        if (tiers == null || tiers.isEmpty()) {
            return;
        }
        int claimed = tiers.stream().mapToInt(EventRequest.PriceTier::rows).sum();
        if (claimed > rows) {
            throw new BadRequestException(
                    "Tier rows (" + claimed + ") exceed the event's row count (" + rows + ")");
        }
    }

    /** Which tier (if any) covers the given 0-based row index, front-to-back. */
    private EventRequest.PriceTier tierForRowIndex(List<EventRequest.PriceTier> tiers, int rowIndex) {
        if (tiers == null) {
            return null;
        }
        int cursor = 0;
        for (EventRequest.PriceTier tier : tiers) {
            cursor += tier.rows();
            if (rowIndex < cursor) {
                return tier;
            }
        }
        return null; // beyond the declared tiers → base price
    }

    private void guardSeatCount(long count) {
        if (count <= 0) {
            throw new BadRequestException("Seat count must be positive");
        }
        if (count > MAX_SEATS_PER_EVENT) {
            throw new BadRequestException("Seat count exceeds maximum of " + MAX_SEATS_PER_EVENT);
        }
    }

    /** Spreadsheet-style row labels: 0→A, 25→Z, 26→AA, … */
    private String rowLabel(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        }
        return sb.toString();
    }

    private EventResponse toResponse(Event e, long availableSeats) {
        return new EventResponse(
                e.getId(),
                e.getTitle(),
                e.getDescription(),
                e.getVenue(),
                e.getEventDateTime(),
                e.getTotalCapacity(),
                availableSeats,
                e.getCurrency(),
                e.getBasePriceMinor(),
                e.getConvenienceFeeMinor(),
                e.isHighDemand(),
                e.getCreatedAt(),
                e.getCategory(),
                e.getCity(),
                Math.max(0, e.getTotalCapacity() - availableSeats),
                e.getImageUrl()
        );
    }

    private ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Event not found: " + id);
    }
}
