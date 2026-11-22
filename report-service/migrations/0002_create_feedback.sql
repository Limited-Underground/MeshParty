CREATE TABLE feedback (
  feedback_id TEXT NOT NULL PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('like', 'dislike', 'change', 'idea', 'bug')),
  message TEXT NOT NULL,
  details_json TEXT CHECK (details_json IS NULL OR json_valid(details_json)),
  received_at INTEGER NOT NULL DEFAULT (unixepoch()),
  expires_at INTEGER NOT NULL,
  CHECK (expires_at > received_at)
) STRICT, WITHOUT ROWID;

CREATE INDEX feedback_expiry ON feedback (expires_at);
