package com.seatvault.repository;

import com.seatvault.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    /**
     * Distinct cities that have events at/after {@code from}, for the browse filter
     * dropdown. Public search itself goes through {@link EventSpecifications}.
     */
    @Query("""
            SELECT DISTINCT e.city FROM Event e
            WHERE e.city IS NOT NULL AND e.city <> '' AND e.eventDateTime >= :from
            ORDER BY e.city
            """)
    List<String> findDistinctCities(@Param("from") Instant from);
}
