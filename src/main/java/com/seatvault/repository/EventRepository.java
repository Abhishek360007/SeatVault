package com.seatvault.repository;

import com.seatvault.domain.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Public browse/search, paginated.
     *
     * <p>The service passes pre-built lowercase LIKE patterns ({@code %term%}, or a
     * bare {@code %} to match everything) and a concrete {@code fromDate} (epoch when
     * the caller omits it). This deliberately avoids the {@code :param IS NULL OR ...}
     * idiom: with PostgreSQL, a null String bind parameter is sent untyped and
     * {@code lower(NULL)} is inferred as {@code lower(bytea)}, which fails. Passing
     * non-null patterns keeps every parameter strongly typed.
     *
     * <p>{@code description} is COALESCE'd because it is nullable.
     */
    @Query("""
            SELECT e FROM Event e
            WHERE (LOWER(e.title) LIKE :keyword OR LOWER(COALESCE(e.description, '')) LIKE :keyword)
              AND LOWER(e.venue) LIKE :venue
              AND e.eventDateTime >= :fromDate
            """)
    Page<Event> search(@Param("keyword") String keyword,
                       @Param("venue") String venue,
                       @Param("fromDate") Instant fromDate,
                       Pageable pageable);
}
