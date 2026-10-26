package com.seatvault.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Shared identity + audit columns for all entities.
 *
 * <p>We use an IDENTITY-generated {@code Long} surrogate key (Postgres
 * {@code BIGINT GENERATED ... AS IDENTITY}). Surrogate keys keep relationships
 * cheap to index and never collide with business meaning.
 *
 * <p>Timestamps are managed by Hibernate ({@code @CreationTimestamp} /
 * {@code @UpdateTimestamp}) and stored as UTC {@code Instant}s.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
