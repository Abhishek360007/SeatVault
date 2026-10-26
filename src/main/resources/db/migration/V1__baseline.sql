-- ============================================================================
-- V1 — Flyway baseline
-- ----------------------------------------------------------------------------
-- This is an intentionally empty baseline. It establishes the Flyway schema
-- history table and a clean starting point for the project. Actual domain
-- tables (users, events, seats, bookings) are introduced in V2 during
-- Phase 2 (Domain & persistence), so each phase maps to a reviewable migration.
--
-- Keeping a no-op V1 makes the migration timeline explicit and lets us run
-- `flyway baseline` cleanly against pre-existing databases if ever needed.
-- ============================================================================

-- pgcrypto gives us gen_random_uuid() should we choose UUID keys later.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
