package com.seatvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <p>{@code @EnableScheduling} is switched on here because the expired-hold
 * release job (Phase 6) relies on {@code @Scheduled}. {@code @EnableCaching}
 * wires up the Redis-backed cache abstraction used for event/seat reads
 * (Phase 7). Enabling them at the root keeps component scanning simple.
 */
@SpringBootApplication
@EnableScheduling
@EnableCaching
@ConfigurationPropertiesScan  // registers JwtProperties, BookingProperties, etc.
public class SeatVaultApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeatVaultApplication.class, args);
    }
}
