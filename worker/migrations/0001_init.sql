-- Hazards from every source, merged. Rows are upserted by id and swept on expiry.
CREATE TABLE IF NOT EXISTS alerts (
  id          TEXT PRIMARY KEY,
  source      TEXT NOT NULL,
  kind        TEXT NOT NULL,
  lat         REAL NOT NULL,
  lon         REAL NOT NULL,
  headline    TEXT NOT NULL,
  detail      TEXT,
  road        TEXT,
  bearing     REAL,
  severity    INTEGER NOT NULL DEFAULT 1,
  started_at  INTEGER,
  updated_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  confidence  REAL NOT NULL DEFAULT 1.0,
  polyline    TEXT
);

-- Bounding-box reads are the hot path: every device polls its own window.
CREATE INDEX IF NOT EXISTS idx_alerts_bbox    ON alerts (lat, lon);
CREATE INDEX IF NOT EXISTS idx_alerts_expiry  ON alerts (expires_at);
CREATE INDEX IF NOT EXISTS idx_alerts_updated ON alerts (updated_at);

-- Speed/red-light cameras. Government datasets only; licensed sets stay on-device.
CREATE TABLE IF NOT EXISTS cameras (
  id          TEXT PRIMARY KEY,
  source      TEXT NOT NULL,
  kind        TEXT NOT NULL,
  lat         REAL NOT NULL,
  lon         REAL NOT NULL,
  road        TEXT,
  suburb      TEXT,
  state       TEXT NOT NULL,
  speed_limit INTEGER,
  bearing     REAL,
  verified_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cameras_bbox  ON cameras (lat, lon);
CREATE INDEX IF NOT EXISTS idx_cameras_state ON cameras (state);

-- Anonymous community reports. device_id is a random UUID minted on the phone;
-- it is never linked to an account, an email, or a location history.
CREATE TABLE IF NOT EXISTS reports (
  id           TEXT PRIMARY KEY,
  device_id    TEXT NOT NULL,
  kind         TEXT NOT NULL,
  lat          REAL NOT NULL,
  lon          REAL NOT NULL,
  bearing      REAL,
  note         TEXT,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  confirms     INTEGER NOT NULL DEFAULT 0,
  denies       INTEGER NOT NULL DEFAULT 0,
  retracted    INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_reports_bbox   ON reports (lat, lon);
CREATE INDEX IF NOT EXISTS idx_reports_expiry ON reports (expires_at);
CREATE INDEX IF NOT EXISTS idx_reports_device ON reports (device_id, created_at);

-- One row per device per report, so a device cannot stuff the ballot.
CREATE TABLE IF NOT EXISTS report_votes (
  report_id  TEXT NOT NULL,
  device_id  TEXT NOT NULL,
  vote       INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (report_id, device_id)
);

-- Bounding boxes devices have actually asked about recently. The Waze sweep and
-- the congestion poll only cover these, so we never crawl the whole continent.
CREATE TABLE IF NOT EXISTS active_regions (
  cell       TEXT PRIMARY KEY,
  min_lon    REAL NOT NULL,
  min_lat    REAL NOT NULL,
  max_lon    REAL NOT NULL,
  max_lat    REAL NOT NULL,
  last_seen  INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_active_seen ON active_regions (last_seen);

-- Per-source bookkeeping so a broken feed is visible instead of silently empty.
CREATE TABLE IF NOT EXISTS source_status (
  source      TEXT PRIMARY KEY,
  last_ok_at  INTEGER,
  last_try_at INTEGER,
  last_error  TEXT,
  last_count  INTEGER
);
