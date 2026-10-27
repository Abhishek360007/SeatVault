package com.seatvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.payment.*}.
 *
 * @param provider             {@code simulated} (default, no account needed) or {@code razorpay}.
 * @param merchantName         shown in the provider's checkout widget.
 * @param simulatedSecret      HMAC key the simulated provider signs checkout results and
 *                             webhooks with, exactly as Razorpay does with its secrets.
 * @param razorpay             Razorpay test/live credentials; only read when provider is razorpay.
 * @param reconcileAfterSeconds how old an open order must be before the reconciliation
 *                             sweep asks the provider about it.
 */
@ConfigurationProperties(prefix = "app.payment")
public record PaymentProperties(
        String provider,
        String merchantName,
        String simulatedSecret,
        Razorpay razorpay,
        int reconcileAfterSeconds
) {
    public PaymentProperties {
        if (provider == null || provider.isBlank()) provider = "simulated";
        if (merchantName == null || merchantName.isBlank()) merchantName = "SeatVault";
        if (razorpay == null) razorpay = new Razorpay(null, null, null, null);
        if (reconcileAfterSeconds <= 0) reconcileAfterSeconds = 120;
    }

    public boolean isRazorpay() {
        return "razorpay".equalsIgnoreCase(provider);
    }

    /**
     * @param keyId         public key id ({@code rzp_test_...} in test mode); safe to send to the browser.
     * @param keySecret     API secret; signs checkout results. Server-side only.
     * @param webhookSecret secret set on the webhook in the Razorpay dashboard.
     * @param baseUrl       API base; overridable for tests.
     */
    public record Razorpay(String keyId, String keySecret, String webhookSecret, String baseUrl) {
        public Razorpay {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.razorpay.com";
        }
    }
}
