package com.seatvault.repository;

import com.seatvault.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository interface for managing AuditLog entities.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
