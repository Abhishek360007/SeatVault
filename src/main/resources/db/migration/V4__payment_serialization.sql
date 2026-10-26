-- ============================================================================
-- V4 — Payment serialization (one in-flight attempt per booking)
-- ----------------------------------------------------------------------------
-- Forward-only migration. Hardens the payment flow against concurrent charges
-- with DIFFERENT idempotency keys for the same booking.
--
-- The application serializes payment claims by locking the booking row
-- (SELECT ... FOR UPDATE) and rejecting a new attempt while one is INITIATED.
-- This partial unique index is the DATABASE-level backstop for that invariant:
-- at most one INITIATED payment row may exist per booking at any time. If two
-- claims ever raced past the application check, the second INSERT/UPDATE that
-- would create a second INITIATED row fails with a unique violation instead of
-- reaching the payment gateway.
--
-- (V3's uq_payments_booking_key already guarantees same-key idempotency; this
-- adds different-key serialization.)
-- ============================================================================

CREATE UNIQUE INDEX uq_payments_one_initiated_per_booking
    ON payments (booking_id)
    WHERE status = 'INITIATED';
