package com.seatvault.service;

import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.domain.Event;
import com.seatvault.domain.Role;
import com.seatvault.domain.Seat;
import com.seatvault.domain.SeatStatus;
import com.seatvault.domain.User;
import com.seatvault.dto.booking.SeatStatusUpdate;
import com.seatvault.repository.BookingRepository;
import com.seatvault.repository.SeatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HoldReleaseServiceTest {

    @Mock SeatRepository seatRepository;
    @Mock BookingRepository bookingRepository;
    @Mock CacheInvalidator cacheInvalidator;

    @InjectMocks HoldReleaseService holdReleaseService;

    @Test
    void releaseBatchReleasesExpiredSeatsExpiresBookingsAndEvictsCache() {
        Instant now = Instant.now();
        Event event = event(20L);
        Seat seat = new Seat(event, "A1");
        seat.setId(55L);
        seat.hold(7L, 99L, now.minus(1, ChronoUnit.MINUTES)); // HELD, already expired
        Booking booking = new Booking(user(), event, 1, now.minus(1, ChronoUnit.MINUTES));
        booking.setId(99L);

        when(seatRepository.findExpiredHeldForUpdate(eq(now), any(Pageable.class)))
                .thenReturn(List.of(seat));
        when(bookingRepository.findByIdInAndStatus(any(), eq(BookingStatus.PENDING)))
                .thenReturn(List.of(booking));

        int released = holdReleaseService.releaseBatch(200, now);

        assertThat(released).isEqualTo(1);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getBookingId()).isNull();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        verify(seatRepository).saveAll(List.of(seat));
        verify(cacheInvalidator).evictEventAvailability(
                20L, List.of(new SeatStatusUpdate(55L, "AVAILABLE", null)));
    }

    @Test
    void releaseBatchReturnsZeroWhenNothingExpired() {
        Instant now = Instant.now();
        when(seatRepository.findExpiredHeldForUpdate(eq(now), any(Pageable.class)))
                .thenReturn(List.of());

        int released = holdReleaseService.releaseBatch(200, now);

        assertThat(released).isZero();
        verify(bookingRepository, never()).saveAll(any());
        verifyNoInteractions(cacheInvalidator);
    }

    private Event event(Long id) {
        Event e = new Event("E", "d", "V", Instant.now().plus(2, ChronoUnit.DAYS), 1);
        e.setId(id);
        return e;
    }

    private User user() {
        User u = new User("u@example.com", "h", "U", Role.USER);
        u.setId(7L);
        return u;
    }
}
