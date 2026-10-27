package com.seatvault.dto.event;

import com.seatvault.domain.EventCategory;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Admin create/update payload for an event.
 *
 * <p>Seat generation is driven by EITHER:
 * <ul>
 *   <li>{@code rows} + {@code seatsPerRow} → labels like {@code A1..A10, B1..}, or</li>
 *   <li>{@code totalCapacity} alone → flat labels {@code S1..Sn}.</li>
 * </ul>
 * The service validates that exactly one valid option is supplied (cross-field
 * checks live in the service so the rule is explicit and testable). On UPDATE the
 * seating fields are ignored — seats are immutable once created to avoid
 * invalidating existing bookings.
 *
 * <h2>Pricing</h2>
 * Money is in <b>minor units</b> (paise/cents). {@code basePriceMinor} is the default
 * seat price; {@code convenienceFeeMinor} is a flat per-seat fee. Optional {@code tiers}
 * (grid mode only) assign a tier name + price to the front rows, front-to-back; any
 * remaining rows fall back to the base price. All pricing fields default sensibly so
 * the existing create flow keeps working when they're omitted.
 *
 * @param eventDateTime must be in the future at creation time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventRequest(

        @NotBlank(message = "title is required")
        @Size(max = 255)
        String title,

        @Size(max = 5000)
        String description,

        @NotBlank(message = "venue is required")
        @Size(max = 255)
        String venue,

        @NotNull(message = "eventDateTime is required")
        @Future(message = "eventDateTime must be in the future")
        Instant eventDateTime,

        // ---- Seating (one of the two strategies; only used on create) ----

        @Positive(message = "totalCapacity must be positive")
        Integer totalCapacity,

        @Positive(message = "rows must be positive")
        Integer rows,

        @Positive(message = "seatsPerRow must be positive")
        Integer seatsPerRow,

        // ---- Pricing (all optional; sensible defaults applied in the service) ----

        @Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter ISO code")
        String currency,

        @PositiveOrZero(message = "basePriceMinor must be >= 0")
        Long basePriceMinor,

        @PositiveOrZero(message = "convenienceFeeMinor must be >= 0")
        Long convenienceFeeMinor,

        @Valid
        List<PriceTier> tiers,

        /** Route holds through the virtual waiting room (see {@code QueueService}). Defaults to false. */
        Boolean highDemand,

        // ---- Discovery (optional) ----

        /** Browse category. Defaults to OTHER. */
        EventCategory category,

        @Size(max = 100, message = "city must be at most 100 characters")
        String city
) {
    /**
     * An optional price band applied to the front rows of a grid event.
     *
     * @param name  tier label shown to users (e.g. "Premium").
     * @param priceMinor seat price for this tier, in minor units.
     * @param rows  how many rows (from the front) this tier covers.
     */
    public record PriceTier(
            @NotBlank(message = "tier name is required") @Size(max = 40) String name,
            @NotNull(message = "tier priceMinor is required") @PositiveOrZero(message = "tier priceMinor must be >= 0") Long priceMinor,
            @NotNull(message = "tier rows is required") @Positive(message = "tier rows must be positive") Integer rows
    ) {
    }
}
