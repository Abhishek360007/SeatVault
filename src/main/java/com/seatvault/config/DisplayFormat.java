package com.seatvault.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;

/**
 * How dates and money look on tickets and in emails. Events are stored in UTC;
 * people read them in {@code app.display-zone} (default Asia/Kolkata), with the
 * zone shown so nobody misreads the time.
 */
@Component
public class DisplayFormat {

    private static final Locale LOCALE = Locale.forLanguageTag("en-IN");

    private final ZoneId zone;
    private final DateTimeFormatter date;
    private final DateTimeFormatter time;

    public DisplayFormat(@Value("${app.display-zone:Asia/Kolkata}") String zoneId) {
        this.zone = ZoneId.of(zoneId);
        this.date = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", LOCALE).withZone(zone);
        this.time = DateTimeFormatter.ofPattern("h:mm a z", LOCALE).withZone(zone);
    }

    /** e.g. {@code Mon, 2 Nov 2026}. */
    public String date(Instant instant) {
        return instant == null ? "—" : date.format(instant);
    }

    /** e.g. {@code 7:30 pm IST}. */
    public String time(Instant instant) {
        return instant == null ? "—" : time.format(instant);
    }

    /** e.g. {@code Mon, 2 Nov 2026 · 7:30 pm IST}. */
    public String dateTime(Instant instant) {
        return instant == null ? "—" : date(instant) + " · " + time(instant);
    }

    /** Minor units to a display amount, e.g. {@code 510000, INR → ₹5,100.00}. */
    public String money(long minor, String currencyCode) {
        Currency currency = Currency.getInstance(currencyCode == null ? "INR" : currencyCode);
        NumberFormat format = NumberFormat.getCurrencyInstance(LOCALE);
        format.setCurrency(currency);
        return format.format(BigDecimal.valueOf(minor).movePointLeft(currency.getDefaultFractionDigits()));
    }

    public ZoneId zone() {
        return zone;
    }
}
