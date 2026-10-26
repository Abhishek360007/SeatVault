-- ============================================================================
-- V6 — Virtual waiting room: flag events as high-demand
-- ============================================================================
-- The wait line and admission tokens themselves live in Redis (QueueService);
-- this column is the only durable state the feature needs.

ALTER TABLE events ADD COLUMN high_demand BOOLEAN NOT NULL DEFAULT FALSE;
