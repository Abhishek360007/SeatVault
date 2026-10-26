package com.seatvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.ticket.*}: the HMAC key used to sign e-ticket QR tokens
 * (see {@code TicketService}).
 */
@ConfigurationProperties(prefix = "app.ticket")
public record TicketProperties(String hmacSecret) {
}
