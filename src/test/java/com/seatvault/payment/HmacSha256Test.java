package com.seatvault.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSha256Test {

    // Reference value from: printf '%s' 'order_IEIaMR65cu6nz3|pay_IH4NVgf4Dreq1l' | openssl dgst -sha256 -hmac test_secret_key
    private static final String SECRET = "test_secret_key";
    private static final String MESSAGE = "order_IEIaMR65cu6nz3|pay_IH4NVgf4Dreq1l";
    private static final String EXPECTED = "dce65dd7f382cc5ab097f75e210fc49ae476d28d482aed4406f1988e5471708e";

    @Test
    void producesLowercaseHexHmacLikeRazorpay() {
        assertThat(HmacSha256.hex(SECRET, MESSAGE)).isEqualTo(EXPECTED);
    }

    @Test
    void matchesOnlyTheRightSignature() {
        assertThat(HmacSha256.matches(SECRET, MESSAGE, EXPECTED)).isTrue();
        assertThat(HmacSha256.matches(SECRET, MESSAGE, EXPECTED.toUpperCase())).isTrue();
        assertThat(HmacSha256.matches(SECRET, MESSAGE + "x", EXPECTED)).isFalse();
        assertThat(HmacSha256.matches("other", MESSAGE, EXPECTED)).isFalse();
    }

    @Test
    void rejectsMissingInputsInsteadOfThrowing() {
        assertThat(HmacSha256.matches(SECRET, MESSAGE, null)).isFalse();
        assertThat(HmacSha256.matches(null, MESSAGE, EXPECTED)).isFalse();
        assertThat(HmacSha256.matches(" ", MESSAGE, EXPECTED)).isFalse();
    }
}
