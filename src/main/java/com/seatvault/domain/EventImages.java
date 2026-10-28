package com.seatvault.domain;

/**
 * The poster image for an event: its own {@code imageUrl} if it has one, otherwise
 * one of the bundled photos for its category (static/img/events, two per category).
 * The web UI applies the same rule (posterStyle in app.js), so a card, its event
 * page and its emails always show the same photo.
 */
public final class EventImages {

    /** Bundled photos per category: {@code /img/events/<category>-1.jpg} and {@code -2.jpg}. */
    public static final int PER_CATEGORY = 2;

    private EventImages() {
    }

    /** Site-relative path or https URL of the event's poster. */
    public static String posterFor(Event event) {
        if (event.getImageUrl() != null && !event.getImageUrl().isBlank()) {
            return event.getImageUrl();
        }
        EventCategory category = event.getCategory() == null ? EventCategory.OTHER : event.getCategory();
        long id = event.getId() == null ? 0 : event.getId();
        return "/img/events/" + category.name().toLowerCase() + "-" + (Math.floorMod(id, PER_CATEGORY) + 1) + ".jpg";
    }
}
