-- ============================================================================
-- V8 — Event discovery: category + city, and indexes for browse filters/sorts
-- ============================================================================

ALTER TABLE events ADD COLUMN category VARCHAR(30)  NOT NULL DEFAULT 'OTHER';
ALTER TABLE events ADD COLUMN city     VARCHAR(100);

ALTER TABLE events ADD CONSTRAINT chk_events_category
    CHECK (category IN ('CONCERT', 'SPORTS', 'CONFERENCE', 'MOVIE', 'THEATRE', 'COMEDY', 'OTHER'));

-- Backfill existing rows with the same keyword heuristic the UI used before
-- categories were stored, so old events land in a sensible bucket.
UPDATE events SET category = CASE
    WHEN LOWER(title || ' ' || COALESCE(description, '')) ~ '(concert|live|music|tour|fest|band)'         THEN 'CONCERT'
    WHEN LOWER(title || ' ' || COALESCE(description, '')) ~ '(match|cup|league|sport|cricket|football)'  THEN 'SPORTS'
    WHEN LOWER(title || ' ' || COALESCE(description, '')) ~ '(movie|film|cinema|screening|premiere)'     THEN 'MOVIE'
    WHEN LOWER(title || ' ' || COALESCE(description, '')) ~ '(play|theatre|theater|drama|musical|opera)' THEN 'THEATRE'
    WHEN LOWER(title || ' ' || COALESCE(description, '')) ~ '(comedy|stand-up|standup|comic)'            THEN 'COMEDY'
    WHEN LOWER(title || ' ' || COALESCE(description, '')) ~ '(conf|summit|expo|keynote|tech|meetup)'     THEN 'CONFERENCE'
    ELSE 'OTHER'
END;

-- Filters: category (paired with date, since the default listing is "upcoming, by date"),
-- city (case-insensitive equality) and price range.
CREATE INDEX idx_events_category_datetime ON events (category, event_datetime);
CREATE INDEX idx_events_city_lower        ON events (LOWER(city));
CREATE INDEX idx_events_base_price        ON events (base_price_minor);
