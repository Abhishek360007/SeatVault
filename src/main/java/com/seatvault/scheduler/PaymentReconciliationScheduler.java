package com.seatvault.scheduler;

import com.seatvault.service.CheckoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Safety net for payments whose browser callback AND webhook both went missing
 * (closed tab, network drop, webhook outage): periodically asks the provider about
 * orders that have been open a while, and finishes them through the same idempotent
 * path as the callback and webhook (see {@link CheckoutService#reconcile}).
 */
@Component
public class PaymentReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationScheduler.class);

    private final CheckoutService checkoutService;

    public PaymentReconciliationScheduler(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Scheduled(fixedDelayString = "${app.payment.reconciliation-interval-ms:60000}",
            initialDelayString = "${app.payment.reconciliation-interval-ms:60000}")
    public void reconcilePayments() {
        try {
            int resolved = checkoutService.reconcile(Instant.now());
            if (resolved > 0) {
                log.info("Payment reconciliation resolved {} open order(s)", resolved);
            }
        } catch (RuntimeException e) {
            log.warn("Payment reconciliation run failed; will retry next tick", e);
        }
    }
}
