package com.seatvault.dto.booking;

/**
 * Message payload carrying seat status updates via WebSocket channels.
 */
public record SeatStatusUpdate(
        Long seatId,
        String status,
        Long heldByUserId
) {
}
