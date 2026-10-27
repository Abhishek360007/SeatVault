package com.seatvault.integration;

import com.seatvault.domain.EventCategory;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.dto.PagedResponse;
import com.seatvault.dto.event.EventRequest;
import com.seatvault.dto.event.EventResponse;
import com.seatvault.dto.event.EventSearchCriteria;
import com.seatvault.dto.event.EventSort;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.SeatRepository;
import com.seatvault.service.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Event discovery against real Postgres: every filter and sort is a JPA
 * Specification that only proves itself once Hibernate renders it to SQL, so
 * these run through {@link EventService#search} rather than mocks.
 *
 * <p>Fixture (all in the future, base price in paise):
 * <pre>
 *   Rock Night      CONCERT  Bengaluru  +3d  ₹500   2 of 4 seats taken
 *   Derby Final     SPORTS   Mumbai     +1d  ₹1500  sold out (4 of 4)
 *   Dev Summit      CONFERENCE Bengaluru +10d ₹0    0 of 4 taken
 * </pre>
 */
class EventSearchIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.now();

    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void seed() {
        deleteBookingDependents();
        bookingRepository.deleteAll();
        seatRepository.deleteAll();
        eventRepository.deleteAll();
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());

        Long rock = create("Rock Night", EventCategory.CONCERT, "Bengaluru", 3, 50_000L);
        Long derby = create("Derby Final", EventCategory.SPORTS, "Mumbai", 1, 150_000L);
        create("Dev Summit", EventCategory.CONFERENCE, "Bengaluru", 10, 0L);
        takeSeats(rock, 2);
        takeSeats(derby, 4);
    }

    @Test
    void filtersByCategory() {
        assertThat(titles(criteria().category(EventCategory.SPORTS))).containsExactly("Derby Final");
    }

    @Test
    void filtersByCityCaseInsensitively() {
        assertThat(titles(criteria().city("  bengaluru "))).containsExactly("Rock Night", "Dev Summit");
    }

    @Test
    void keywordMatchesTitleVenueOrCity() {
        assertThat(titles(criteria().keyword("mumbai"))).containsExactly("Derby Final");
        assertThat(titles(criteria().keyword("SUMMIT"))).containsExactly("Dev Summit");
    }

    @Test
    void filtersByDateRange() {
        assertThat(titles(criteria().from(NOW.plus(2, ChronoUnit.DAYS)).to(NOW.plus(5, ChronoUnit.DAYS))))
                .containsExactly("Rock Night");
    }

    @Test
    void filtersByPriceRange() {
        assertThat(titles(criteria().minPrice(1L).maxPrice(100_000L))).containsExactly("Rock Night");
    }

    @Test
    void availableOnlyHidesSoldOutEvents() {
        assertThat(titles(criteria().availableOnly())).containsExactly("Rock Night", "Dev Summit");
    }

    @Test
    void sortsByDateAndPriceInEitherDirection() {
        assertThat(titles(criteria())).containsExactly("Derby Final", "Rock Night", "Dev Summit");
        assertThat(titles(criteria().sort(EventSort.PRICE, true))).containsExactly("Derby Final", "Rock Night", "Dev Summit");
        assertThat(titles(criteria().sort(EventSort.PRICE, false))).containsExactly("Dev Summit", "Rock Night", "Derby Final");
    }

    @Test
    void sortsByPopularityAndReportsIt() {
        PagedResponse<EventResponse> page = eventService.search(
                criteria().sort(EventSort.POPULARITY, true).build(), PageRequest.of(0, 10));

        assertThat(page.content()).extracting(EventResponse::title)
                .containsExactly("Derby Final", "Rock Night", "Dev Summit");
        assertThat(page.content()).extracting(EventResponse::popularity).containsExactly(4L, 2L, 0L);
        assertThat(page.totalElements()).isEqualTo(3);
    }

    @Test
    void paginatesWithCorrectTotals() {
        PagedResponse<EventResponse> second = eventService.search(criteria().build(), PageRequest.of(1, 2));

        assertThat(second.content()).extracting(EventResponse::title).containsExactly("Dev Summit");
        assertThat(second.totalElements()).isEqualTo(3);
        assertThat(second.totalPages()).isEqualTo(2);
    }

    @Test
    void listsDistinctUpcomingCities() {
        assertThat(eventService.upcomingCities()).containsExactly("Bengaluru", "Mumbai");
    }

    // ------------------------------------------------------------------ helpers

    private Long create(String title, EventCategory category, String city, int daysAhead, long basePriceMinor) {
        EventRequest request = new EventRequest(title, null, title + " Arena",
                NOW.plus(daysAhead, ChronoUnit.DAYS), 4, null, null,
                "INR", basePriceMinor, 0L, null, null, category, city);
        return eventService.create(request).id();
    }

    private void takeSeats(Long eventId, int count) {
        List<Seat> seats = seatRepository.findByEventIdOrderBySeatLabel(eventId);
        seats.stream().limit(count).forEach(s -> s.setStatus(SeatStatus.BOOKED));
        seatRepository.saveAll(seats);
    }

    private List<String> titles(CriteriaBuilder c) {
        return eventService.search(c.build(), PageRequest.of(0, 10)).content().stream()
                .map(EventResponse::title).toList();
    }

    private static CriteriaBuilder criteria() {
        return new CriteriaBuilder();
    }

    /** Tiny fluent builder so each test only names the filters it cares about. */
    private static final class CriteriaBuilder {
        private String keyword;
        private EventCategory category;
        private String city;
        private Instant from;
        private Instant to;
        private Long minPrice;
        private Long maxPrice;
        private boolean availableOnly;
        private EventSort sort = EventSort.DATE;
        private boolean descending;

        CriteriaBuilder keyword(String v) { keyword = v; return this; }
        CriteriaBuilder category(EventCategory v) { category = v; return this; }
        CriteriaBuilder city(String v) { city = v; return this; }
        CriteriaBuilder from(Instant v) { from = v; return this; }
        CriteriaBuilder to(Instant v) { to = v; return this; }
        CriteriaBuilder minPrice(Long v) { minPrice = v; return this; }
        CriteriaBuilder maxPrice(Long v) { maxPrice = v; return this; }
        CriteriaBuilder availableOnly() { availableOnly = true; return this; }
        CriteriaBuilder sort(EventSort s, boolean desc) { sort = s; descending = desc; return this; }

        EventSearchCriteria build() {
            return new EventSearchCriteria(keyword, category, city, null, from, to,
                    minPrice, maxPrice, availableOnly, sort, descending);
        }
    }
}
