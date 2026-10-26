package com.seatvault.domain;

import com.seatvault.repository.AuditLogRepository;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * JPA EntityListener that automatically captures Event changes and records them in the audit log.
 */
@Component
public class EventAuditListener {

    private static AuditLogRepository auditLogRepository;

    @Autowired
    public void setAuditLogRepository(AuditLogRepository repository) {
        EventAuditListener.auditLogRepository = repository;
    }

    @PostPersist
    public void onPostPersist(Event event) {
        logAction("CREATE", event);
    }

    @PostUpdate
    public void onPostUpdate(Event event) {
        if (event.isDeleted()) {
            logAction("DELETE", event);
        } else {
            logAction("UPDATE", event);
        }
    }

    @PostRemove
    public void onPostRemove(Event event) {
        logAction("HARD_DELETE", event);
    }

    private void logAction(String action, Event event) {
        if (auditLogRepository == null) {
            return;
        }

        String actorEmail = "system";
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            actorEmail = auth.getName();
        }

        String details = String.format("Title: %s, Venue: %s, Date: %s, Deleted: %b",
                event.getTitle(), event.getVenue(), event.getEventDateTime(), event.isDeleted());

        AuditLog auditLog = new AuditLog(actorEmail, action, "Event", event.getId(), details);
        auditLogRepository.save(auditLog);
    }
}
