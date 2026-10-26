package com.seatvault.dto.booking;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seatvault.domain.Booking;
import com.seatvault.domain.BookingStatus;
import com.seatvault.domain.Payment;
import com.seatvault.domain.PaymentStatus;
import com.seatvault.domain.Seat;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * A booking as returned to clients, including its seat labels, the frozen payable
 * amount (so the UI shows prices from the API, not hardcoded values), a per-seat
 * price breakdown, the hold expiry (for a countdown), and — on the pay path — the
 * payment result.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookingResponse(
        Long id,
        Long eventId,
        String eventTitle,
        BookingStatus status,
        int seatCount,
        List<String> seatLabels,
        String currency,
        long amountMinor,
        long feeMinor,
        List<SeatPrice> seats,
        Instant expiresAt,
        Instant confirmedAt,
        Instant createdAt,
        PaymentInfo payment
) {
    /** Per-seat price breakdown line. */
    public record SeatPrice(String seatLabel, String tierName, long priceMinor) {
    }

    /** Payment result details surfaced on the pay path (null otherwise). */
    public record PaymentInfo(PaymentStatus status, String providerReference, String failureReason) {
        public static PaymentInfo of(Payment p) {
            return new PaymentInfo(p.getStatus(), p.getProviderReference(), p.getFailureReason());
        }
    }

    /** Build from a managed Booking + its seats (no payment block). Call inside the transaction. */
    public static BookingResponse of(Booking booking, List<Seat> seats) {
        return of(booking, seats, null);
    }

    /** Build from a managed Booking + its seats, including a payment result. */
    public static BookingResponse of(Booking booking, List<Seat> seats, Payment payment) {
        List<String> labels = seats.stream()
                .map(Seat::getSeatLabel)
                .sorted()
                .toList();
        List<SeatPrice> breakdown = seats.stream()
                .sorted(Comparator.comparing(Seat::getSeatLabel))
                .map(s -> new SeatPrice(s.getSeatLabel(), s.getTierName(), s.effectivePriceMinor()))
                .toList();
        return new BookingResponse(
                booking.getId(),
                booking.getEvent().getId(),
                booking.getEvent().getTitle(),
                booking.getStatus(),
                booking.getSeatCount(),
                labels,
                booking.getCurrency(),
                booking.getAmountMinor(),
                booking.getFeeMinor(),
                breakdown,
                booking.getExpiresAt(),
                booking.getConfirmedAt(),
                booking.getCreatedAt(),
                payment != null ? PaymentInfo.of(payment) : null
        );
    }
}
