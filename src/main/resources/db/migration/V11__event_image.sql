-- ============================================================================
-- V11 — Optional event image. NULL means "use a bundled photo for the category".
-- Accepts a bundled path (/img/events/...) or an https:// URL (validated in the API).
-- ============================================================================

ALTER TABLE events ADD COLUMN image_url VARCHAR(500);
