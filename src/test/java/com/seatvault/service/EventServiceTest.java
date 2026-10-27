package com.seatvault.service;

import com.seatvault.domain.Event;
import com.seatvault.domain.EventCategory;
import com.seatvault.domain.SeatStatus;
import com.seatvault.dto.event.EventRequest;
import com.seatvault.dto.event.EventResponse;
import com.seatvault.exception.BadRequestException;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.repository.EventRepository;
import com.seatvault.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock EventRepository eventRepository;
    @Mock SeatRepository seatRepository;

    @InjectMocks EventService eventService;

    private static final Instant FUTURE = Instant.now().plus(2, ChronoUnit.DAYS);

    @Test
    void createWithFlatCapacityGeneratesSequentialSeats() {
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(100L);
            return e;
        });

        EventResponse response = eventService.create(
                new EventRequest("Concert", "Live", "Arena", FUTURE, 3, null, null, null, null, null, null, null, null, null));

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.totalCapacity()).isEqualTo(3);
        assertThat(response.availableSeats()).isEqualTo(3); // all AVAILABLE on creation
        assertThat(response.currency()).isEqualTo("INR"); // sensible default

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        org.mockito.Mockito.verify(eventRepository).save(saved.capture());
        assertThat(saved.getValue().getSeats())
                .extracting("seatLabel")
                .containsExactly("S1", "S2", "S3");
    }

    @Test
    void createWithGridGeneratesRowColumnLabels() {
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        eventService.create(new EventRequest("Play", "d", "Theatre", FUTURE, null, 2, 3, null, null, null, null, null, null, null));

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        org.mockito.Mockito.verify(eventRepository).save(saved.capture());
        assertThat(saved.getValue().getSeats())
                .extracting("seatLabel")
                .containsExactly("A1", "A2", "A3", "B1", "B2", "B3");
        assertThat(saved.getValue().getTotalCapacity()).isEqualTo(6);
    }

    @Test
    void createWithoutAnySeatingStrategyIsRejected() {
        assertThatThrownBy(() -> eventService.create(
                new EventRequest("Bad", "d", "Nowhere", FUTURE, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createStoresCategoryAndTrimmedCityDefaultingCategoryToOther() {
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        EventResponse movie = eventService.create(new EventRequest("Film", "d", "PVR", FUTURE, 2,
                null, null, null, null, null, null, null, EventCategory.MOVIE, "  Pune "));
        EventResponse untagged = eventService.create(new EventRequest("Misc", "d", "Hall", FUTURE, 2,
                null, null, null, null, null, null, null, null, "   "));

        assertThat(movie.category()).isEqualTo(EventCategory.MOVIE);
        assertThat(movie.city()).isEqualTo("Pune");
        assertThat(untagged.category()).isEqualTo(EventCategory.OTHER);
        assertThat(untagged.city()).isNull();
    }

    @Test
    void getByIdFailsWhenMissing() {
        when(eventRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getById(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByIdReturnsLiveAvailableCount() {
        Event event = new Event("Gig", "d", "Hall", FUTURE, 10);
        event.setId(5L);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(event));
        when(seatRepository.countByEventIdAndStatus(5L, SeatStatus.AVAILABLE)).thenReturn(8L);

        EventResponse response = eventService.getById(5L);

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.availableSeats()).isEqualTo(8L);
        assertThat(response.popularity()).isEqualTo(2L); // 10 capacity - 8 available
    }
}
