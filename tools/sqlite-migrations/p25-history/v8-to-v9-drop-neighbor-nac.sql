PRAGMA foreign_keys = OFF;

BEGIN IMMEDIATE;

DROP TABLE IF EXISTS site_neighbor_v9;

CREATE TABLE site_neighbor_v9 (
    guid TEXT NOT NULL,
    neighbor_key TEXT NOT NULL,
    system_id INTEGER,
    rfss INTEGER,
    site INTEGER,
    lra INTEGER,
    channel_descriptor TEXT,
    downlink_hz INTEGER,
    uplink_hz INTEGER,
    status TEXT,
    first_seen_ms INTEGER NOT NULL,
    last_seen_ms INTEGER NOT NULL,
    seen_count INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY(guid, neighbor_key)
);

INSERT INTO site_neighbor_v9 (
    guid,
    neighbor_key,
    system_id,
    rfss,
    site,
    lra,
    channel_descriptor,
    downlink_hz,
    uplink_hz,
    status,
    first_seen_ms,
    last_seen_ms,
    seen_count
)
SELECT
    guid,
    neighbor_key,
    system_id,
    rfss,
    site,
    lra,
    channel_descriptor,
    downlink_hz,
    uplink_hz,
    status,
    first_seen_ms,
    last_seen_ms,
    seen_count
FROM site_neighbor;

DROP TABLE site_neighbor;
ALTER TABLE site_neighbor_v9 RENAME TO site_neighbor;

CREATE INDEX IF NOT EXISTS idx_site_neighbor_guid_site ON site_neighbor(guid, system_id, rfss, site);

INSERT INTO database_metadata (key, value, updated_at_ms)
VALUES ('p25_activity_schema_version', '9', CAST(strftime('%s', 'now') AS INTEGER) * 1000)
ON CONFLICT(key) DO UPDATE SET
    value = excluded.value,
    updated_at_ms = excluded.updated_at_ms;

COMMIT;

PRAGMA foreign_keys = ON;
