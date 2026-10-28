package com.seatvault.dto.event;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The UI renders imageUrl as an image, so only bundled paths and https URLs may get in. */
class EventRequestImageUrlTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsBundledPhotosHttpsUrlsAndNothing() {
        assertThat(violations("/img/events/concert-1.jpg")).isZero();
        assertThat(violations("https://images.example.com/posters/gig.jpg?w=1200")).isZero();
        assertThat(violations(null)).isZero();
    }

    @Test
    void rejectsAnythingThatCouldBreakOutOfTheImage() {
        assertThat(violations("javascript:alert(1)")).isPositive();
        assertThat(violations("data:image/png;base64,AAAA")).isPositive();
        assertThat(violations("http://insecure.example.com/a.jpg")).isPositive();
        assertThat(violations("https://x.example.com/a.jpg\") , url(\"https://evil")).isPositive();
        assertThat(violations("/img/events/../../app.js")).isPositive();
    }

    private long violations(String imageUrl) {
        EventRequest request = new EventRequest("Gig", null, "Hall", Instant.now().plus(1, ChronoUnit.DAYS),
                10, null, null, null, null, null, null, null, null, null, imageUrl);
        return validator.validate(request).stream()
                .filter(v -> v.getPropertyPath().toString().equals("imageUrl")).count();
    }
}
