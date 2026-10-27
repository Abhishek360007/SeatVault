package com.seatvault.repository;

import com.seatvault.domain.TicketCheckin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TicketCheckinRepository extends JpaRepository<TicketCheckin, Long> {
    Optional<TicketCheckin> findBySeatId(Long seatId);

    /** Admissions so far for an event (check-ins whose seat belongs to it). */
    @Query("SELECT COUNT(c) FROM TicketCheckin c, Seat s WHERE s.id = c.seatId AND s.event.id = :eventId")
    long countByEventId(@Param("eventId") Long eventId);

    /** Check-ins of these seats, for marking passes already used. */
    List<TicketCheckin> findBySeatIdIn(Collection<Long> seatIds);
}
