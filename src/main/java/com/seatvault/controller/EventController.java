package com.seatvault.controller;

import com.seatvault.domain.EventCategory;
import com.seatvault.dto.event.EventSearchCriteria;
import com.seatvault.dto.event.EventSort;
import com.seatvault.exception.BadRequestException;
import org.springframework.data.domain.Sort;
import com.seatvault.domain.SeatStatus;
import com.seatvault.dto.PagedResponse;
import com.seatvault.dto.event.EventRequest;
import com.seatvault.dto.event.EventResponse;
import com.seatvault.dto.event.SeatResponse;
import com.seatvault.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Event endpoints.
 *
 * <ul>
 *   <li>Writes (create/update/delete) are ADMIN-only via {@code @PreAuthorize}.</li>
 *   <li>Reads (list/search, detail, seats) are public (configured in SecurityConfig).</li>
 * </ul>
 *
 * Controllers stay thin: validate, delegate, shape the HTTP response.
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events", description = "Event browsing (public) and management (admin)")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    // ---------------------------- Admin writes ----------------------------

    @Operation(summary = "Create an event and generate its seats (ADMIN)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.create(request));
    }

    @Operation(summary = "Update event metadata (ADMIN). Seating is immutable.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public ResponseEntity<EventResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody EventRequest request) {
        return ResponseEntity.ok(eventService.update(id, request));
    }

    @Operation(summary = "Delete an event (ADMIN). Fails if it has bookings.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        eventService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------- Public reads ----------------------------

    @Operation(summary = "Browse/search events with filters and sorting (public, paginated)")
    @GetMapping
    public ResponseEntity<PagedResponse<EventResponse>> search(
            @Parameter(description = "Free-text match on title, description, venue or city")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "Filter by category")
            @RequestParam(required = false) EventCategory category,
            @Parameter(description = "Filter by city (case-insensitive exact match)")
            @RequestParam(required = false) String city,
            @Parameter(description = "Filter by venue (partial match)")
            @RequestParam(required = false) String venue,
            @Parameter(description = "Only events at/after this instant (ISO-8601)")
            @RequestParam(required = false) Instant fromDate,
            @Parameter(description = "Only events at/before this instant (ISO-8601)")
            @RequestParam(required = false) Instant toDate,
            @Parameter(description = "Minimum base seat price, in minor units")
            @RequestParam(required = false) Long minPrice,
            @Parameter(description = "Maximum base seat price, in minor units")
            @RequestParam(required = false) Long maxPrice,
            @Parameter(description = "Only events that still have available seats")
            @RequestParam(defaultValue = "false") boolean availableOnly,
            @Parameter(description = "Sort key: DATE, PRICE or POPULARITY")
            @RequestParam(defaultValue = "DATE") EventSort sortBy,
            @Parameter(description = "Sort direction: ASC or DESC")
            @RequestParam(defaultValue = "ASC") Sort.Direction direction,
            @PageableDefault(size = 20) Pageable pageable) {
        if ((minPrice != null && minPrice < 0) || (maxPrice != null && maxPrice < 0)) {
            throw new BadRequestException("minPrice and maxPrice must be >= 0");
        }
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw new BadRequestException("toDate must not be before fromDate");
        }
        if (minPrice != null && maxPrice != null && maxPrice < minPrice) {
            throw new BadRequestException("maxPrice must not be less than minPrice");
        }
        EventSearchCriteria criteria = new EventSearchCriteria(keyword, category, city, venue,
                fromDate, toDate, minPrice, maxPrice, availableOnly, sortBy, direction.isDescending());
        return ResponseEntity.ok(eventService.search(criteria, pageable));
    }

    @Operation(summary = "Cities that have upcoming events, for the browse filter (public)")
    @GetMapping("/cities")
    public ResponseEntity<java.util.List<String>> cities() {
        return ResponseEntity.ok(eventService.upcomingCities());
    }

    @Operation(summary = "Get a single event with live availability (public)")
    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(eventService.getById(id));
    }

    @Operation(summary = "List an event's seats, optionally filtered by status (public)")
    @GetMapping("/{id}/seats")
    public ResponseEntity<java.util.List<SeatResponse>> getSeats(
            @PathVariable Long id,
            @Parameter(description = "Filter by seat status: AVAILABLE, HELD, BOOKED")
            @RequestParam(required = false) SeatStatus status) {
        return ResponseEntity.ok(eventService.getSeats(id, status));
    }
}
