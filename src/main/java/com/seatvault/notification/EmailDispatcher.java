package com.seatvault.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends due outbox emails, off the request path.
 *
 * <ol>
 *   <li><b>Claim</b> (short transaction): lock due rows with {@code SKIP LOCKED} and
 *       lease them as SENDING, so parallel instances never pick the same row.</li>
 *   <li><b>Send</b> (no transaction): render and hand to SMTP. Never holds row locks
 *       while talking to the mail server.</li>
 *   <li><b>Record</b> (short transaction): SENT, or a retry with exponential backoff,
 *       or FAILED once attempts run out or the failure is permanent (bad address,
 *       booking gone). A crash between 1 and 3 leaves the lease to expire, and the
 *       row is retried: delivery is at-least-once, the usual trade-off for email.</li>
 * </ol>
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final NotificationRepository repository;
    private final EmailComposer composer;
    private final JavaMailSender mailSender;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;

    public EmailDispatcher(NotificationRepository repository,
                           EmailComposer composer,
                           JavaMailSender mailSender,
                           PlatformTransactionManager transactionManager,
                           @Value("${app.notifications.enabled:true}") boolean enabled,
                           @Value("${app.notifications.batch-size:20}") int batchSize,
                           @Value("${app.notifications.max-attempts:8}") int maxAttempts) {
        this.repository = repository;
        this.composer = composer;
        this.mailSender = mailSender;
        this.tx = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${app.notifications.dispatch-interval-ms:5000}",
            initialDelayString = "${app.notifications.dispatch-interval-ms:5000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            int sent;
            do {
                sent = dispatchBatch(Instant.now());
            } while (sent == batchSize); // keep going while there's a backlog
        } catch (RuntimeException e) {
            log.warn("Email dispatch run failed; will retry next tick", e);
        }
    }

    /** Sends one batch of due emails. Returns how many rows it claimed. */
    public int dispatchBatch(Instant now) {
        List<Notification> claimed = tx.execute(s -> {
            List<Notification> due = repository.lockDue(now, batchSize);
            if (!due.isEmpty()) {
                repository.lease(due.stream().map(Notification::getId).toList(), now.plus(LEASE));
            }
            return due;
        });
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        for (Notification n : claimed) {
            send(n);
        }
        return claimed.size();
    }

    private void send(Notification n) {
        try {
            MimeMessage message = composer.compose(n.getType(), n.getBookingId(), n.getRecipient());
            mailSender.send(message);
            record(n.getId(), row -> row.markSent(Instant.now()));
            log.info("Sent {} email for booking={} to {}", n.getType(), n.getBookingId(), mask(n.getRecipient()));
        } catch (EmailComposer.MissingBookingException | MailParseException | MailPreparationException e) {
            fail(n, e, true);
        } catch (MessagingException e) {
            fail(n, e, true); // the message itself is malformed (e.g. invalid address)
        } catch (MailAuthenticationException e) {
            fail(n, e, false); // our SMTP credentials: fixable by ops, so keep retrying
        } catch (RuntimeException e) {
            fail(n, e, false); // server down, timeout, transient rejection
        }
    }

    private void fail(Notification n, Exception e, boolean permanent) {
        String error = e.getClass().getSimpleName() + ": " + e.getMessage();
        record(n.getId(), row -> row.markAttemptFailed(error, Instant.now(), maxAttempts, permanent));
        log.warn("{} email for booking={} failed (attempt {}, {}): {}", n.getType(), n.getBookingId(),
                n.getAttempts() + 1, permanent ? "permanent" : "will retry", error);
    }

    private void record(Long id, java.util.function.Consumer<Notification> change) {
        tx.executeWithoutResult(s -> repository.findById(id).ifPresent(change));
    }

    /** {@code jane.doe@example.com → j***@example.com}: logs shouldn't collect addresses. */
    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at <= 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
