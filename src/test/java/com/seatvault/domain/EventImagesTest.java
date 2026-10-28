package com.seatvault.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventImagesTest {

    @Test
    void usesTheEventsOwnImageWhenSet() {
        Event e = event(7L, EventCategory.CONCERT);
        e.setImageUrl("https://images.example.com/poster.jpg");

        assertThat(EventImages.posterFor(e)).isEqualTo("https://images.example.com/poster.jpg");
    }

    @Test
    void otherwiseAlternatesBetweenTheCategorysBundledPhotos() {
        assertThat(EventImages.posterFor(event(4L, EventCategory.SPORTS))).isEqualTo("/img/events/sports-1.jpg");
        assertThat(EventImages.posterFor(event(5L, EventCategory.SPORTS))).isEqualTo("/img/events/sports-2.jpg");
        assertThat(EventImages.posterFor(event(5L, null))).isEqualTo("/img/events/other-2.jpg");
    }

    private static Event event(Long id, EventCategory category) {
        Event e = new Event("t", "d", "v", Instant.now(), 1);
        e.setId(id);
        e.setCategory(category);
        return e;
    }
}
