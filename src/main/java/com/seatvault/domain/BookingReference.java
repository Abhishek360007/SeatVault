package com.seatvault.domain;

import java.security.SecureRandom;

/**
 * Generates human-facing booking codes like {@code SV-7KX4-92QD}.
 *
 * <p>The alphabet drops 0/O, 1/I/L so a code read out over the phone or copied off
 * a printed ticket can't be misread. 8 random symbols from 31 is about 8.5 × 10^11
 * combinations, so a clash is vanishingly rare, and the {@code uq_bookings_reference}
 * constraint would reject one anyway. Random rather than derived from the id, so a
 * reference reveals nothing about booking volume and can't be guessed from another.
 */
public final class BookingReference {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private BookingReference() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder("SV-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
