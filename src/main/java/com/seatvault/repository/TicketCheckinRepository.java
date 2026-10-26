package com.seatvault.repository;

import com.seatvault.domain.TicketCheckin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TicketCheckinRepository extends JpaRepository<TicketCheckin, Long> {
    Optional<TicketCheckin> findBySeatId(Long seatId);
}
