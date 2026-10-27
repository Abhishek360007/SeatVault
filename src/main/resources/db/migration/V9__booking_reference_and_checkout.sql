-- ============================================================================
-- V9 — Booking reference + provider (order-based) checkout
-- ============================================================================

-- ---------------------------------------------------------------------------
-- bookings.reference — the human-facing booking code (e.g. SV-7KX4-92QD) shown
-- on screen, on the e-ticket and in support conversations, instead of the
-- sequential database id.
-- ---------------------------------------------------------------------------
ALTER TABLE bookings ADD COLUMN reference VARCHAR(16);

-- Backfill existing rows. Hex is a subset of the alphabet new references use,
-- and the unique constraint below would reject the (astronomically unlikely) clash.
UPDATE bookings
SET reference = 'SV-' || UPPER(SUBSTR(MD5(id::text || clock_timestamp()::text), 1, 4))
             || '-' || UPPER(SUBSTR(MD5(clock_timestamp()::text || id::text), 1, 4));

ALTER TABLE bookings ALTER COLUMN reference SET NOT NULL;
ALTER TABLE bookings ADD CONSTRAINT uq_bookings_reference UNIQUE (reference);

-- ---------------------------------------------------------------------------
-- payments: one row per provider ORDER. The browser pays the order inside the
-- provider's own widget; the server confirms it from a signed callback or a
-- signed webhook, whichever arrives first (both are idempotent).
-- ---------------------------------------------------------------------------
ALTER TABLE payments ADD COLUMN provider          VARCHAR(20);
ALTER TABLE payments ADD COLUMN provider_order_id VARCHAR(64);

-- A provider order belongs to exactly one payment row, so a webhook or callback
-- can never be applied to two bookings.
ALTER TABLE payments ADD CONSTRAINT uq_payments_provider_order UNIQUE (provider_order_id);

-- REFUNDED: money arrived after the booking could no longer be confirmed (hold
-- expired or booking cancelled) and was returned automatically.
ALTER TABLE payments DROP CONSTRAINT chk_payments_status;
ALTER TABLE payments ADD CONSTRAINT chk_payments_status
    CHECK (status IN ('INITIATED', 'APPROVED', 'DECLINED', 'FAILED', 'REFUNDED'));

-- The reconciliation sweep looks for open orders by age.
CREATE INDEX idx_payments_open_orders ON payments (status, created_at) WHERE provider_order_id IS NOT NULL;
