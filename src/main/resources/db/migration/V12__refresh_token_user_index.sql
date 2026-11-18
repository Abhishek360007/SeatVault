-- Refresh-token reuse detection revokes all of a user's tokens with
-- UPDATE ... WHERE user_id = ?; index it so that is a lookup, not a table scan.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens (user_id);
