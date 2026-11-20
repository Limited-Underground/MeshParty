CREATE TABLE abuse_reports (
  report_id TEXT NOT NULL PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('message', 'sender')),
  reason TEXT NOT NULL CHECK (reason IN ('harassment', 'threat', 'hate', 'sexual', 'spam', 'illegal', 'privacy', 'other')),
  network TEXT NOT NULL CHECK (network IN ('meshcore', 'meshtastic')),
  message TEXT,
  sender_id TEXT,
  sender_name TEXT,
  received_at INTEGER NOT NULL DEFAULT (unixepoch()),
  expires_at INTEGER NOT NULL,
  CHECK (sender_id IS NULL OR sender_name IS NULL),
  CHECK (sender_name IS NULL OR network = 'meshcore'),
  CHECK (kind <> 'message' OR message IS NOT NULL),
  CHECK (kind <> 'sender' OR sender_id IS NOT NULL OR sender_name IS NOT NULL),
  CHECK (expires_at > received_at)
) STRICT, WITHOUT ROWID;

CREATE INDEX abuse_reports_expiry ON abuse_reports (expires_at);
