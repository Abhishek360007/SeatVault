package com.seatvault.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Audit log entry recording administrative actions.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog extends BaseEntity {

    @Column(name = "actor_email", nullable = false)
    private String actorEmail;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "entity_name", nullable = false, length = 100)
    private String entityName;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(columnDefinition = "text")
    private String details;

    public AuditLog(String actorEmail, String action, String entityName, Long entityId, String details) {
        this.actorEmail = actorEmail;
        this.action = action;
        this.entityName = entityName;
        this.entityId = entityId;
        this.details = details;
    }
}
