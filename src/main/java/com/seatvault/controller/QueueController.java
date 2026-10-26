package com.seatvault.controller;

import com.seatvault.domain.Event;
import com.seatvault.dto.queue.QueueStatusResponse;
import com.seatvault.exception.ResourceNotFoundException;
import com.seatvault.repository.EventRepository;
import com.seatvault.security.AppUserPrincipal;
import com.seatvault.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Virtual waiting room for events flagged {@code highDemand}. Non-high-demand
 * events report as immediately admitted, so a client can always call
 * {@code join}/{@code status} unconditionally without branching on the event flag.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/queue")
@Tag(name = "Waiting Room", description = "FIFO admission queue for high-demand events")
@SecurityRequirement(name = "bearerAuth")
public class QueueController {

    private final QueueService queueService;
    private final EventRepository eventRepository;

    public QueueController(QueueService queueService, EventRepository eventRepository) {
        this.queueService = queueService;
        this.eventRepository = eventRepository;
    }

    @Operation(summary = "Join the waiting room for an event (no-op/immediately-admitted if not high-demand)")
    @PostMapping("/join")
    public ResponseEntity<QueueStatusResponse> join(@AuthenticationPrincipal AppUserPrincipal principal,
                                                     @PathVariable Long eventId) {
        Event event = loadEvent(eventId);
        if (!event.isHighDemand()) {
            return ResponseEntity.ok(admittedImmediately(eventId));
        }
        return ResponseEntity.ok(queueService.join(eventId, principal.getId()));
    }

    @Operation(summary = "Poll queue position / admission status")
    @GetMapping("/status")
    public ResponseEntity<QueueStatusResponse> status(@AuthenticationPrincipal AppUserPrincipal principal,
                                                       @PathVariable Long eventId) {
        Event event = loadEvent(eventId);
        if (!event.isHighDemand()) {
            return ResponseEntity.ok(admittedImmediately(eventId));
        }
        return ResponseEntity.ok(queueService.status(eventId, principal.getId()));
    }

    private Event loadEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    }

    private QueueStatusResponse admittedImmediately(Long eventId) {
        return new QueueStatusResponse(eventId, true, null, null, null);
    }
}
