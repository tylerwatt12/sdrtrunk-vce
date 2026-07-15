#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 /path/to/sdrtrunk.sqlite" >&2
    exit 2
fi

database=$1

if [ ! -f "$database" ]; then
    echo "Database not found: $database" >&2
    exit 2
fi

if ! command -v sqlite3 >/dev/null 2>&1; then
    echo "sqlite3 is required" >&2
    exit 2
fi

if command -v lsof >/dev/null 2>&1 && lsof "$database" >/dev/null 2>&1; then
    echo "Database is open. Stop SDRTrunk before running this migration." >&2
    exit 2
fi

integrity=$(sqlite3 -readonly "$database" "PRAGMA quick_check;")

if [ "$integrity" != "ok" ]; then
    echo "Pre-migration integrity check failed: $integrity" >&2
    exit 1
fi

sqlite3 "$database" "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null
timestamp=$(date +%Y%m%dT%H%M%S)
backup="${database%.sqlite}.before-invalid-encryption-cleanup-${timestamp}.sqlite"
cp -p "$database" "$backup"

invalid_before=$(sqlite3 -readonly "$database" "
    SELECT count(*) FROM p25_activity_event
    WHERE encrypted = 1 AND (encryption_algorithm_id IS NULL OR encryption_algorithm_id NOT IN
        (0,1,2,3,4,5,65,129,130,131,132,133,136,137,159,160,161,162,163,164,165,166,167,168,169,170,
         171,172,173,174,175,176));")

sqlite3 "$database" <<'SQL'
.bail on
PRAGMA foreign_keys = ON;
BEGIN IMMEDIATE;

CREATE TEMP TABLE cleanup_invalid_encryption AS
SELECT id
FROM p25_activity_event
WHERE encrypted = 1 AND (encryption_algorithm_id IS NULL OR encryption_algorithm_id NOT IN
    (0,1,2,3,4,5,65,129,130,131,132,133,136,137,159,160,161,162,163,164,165,166,167,168,169,170,
     171,172,173,174,175,176));

CREATE UNIQUE INDEX cleanup_invalid_encryption_id ON cleanup_invalid_encryption(id);

UPDATE p25_activity_event
SET encrypted = 0, encryption_algorithm_id = NULL, encryption_key_id = NULL
WHERE id IN (SELECT id FROM cleanup_invalid_encryption);

CREATE TEMP TABLE cleanup_valid_encryption AS
SELECT a.id, a.context_id, rc.system_key, a.observed_at_ms, a.source_radio_id, a.target_id,
       a.target_kind_code, a.frequency_hz, coalesce(a.timeslot, -1) AS timeslot,
       a.encryption_algorithm_id, a.encryption_key_id,
       (a.observed_at_ms / 3600000) * 3600000 AS bucket_start_ms
FROM p25_activity_event a
JOIN receiver_context rc ON rc.id = a.context_id
WHERE rc.kind_code = 1 AND a.encrypted = 1;

CREATE INDEX cleanup_valid_system_target ON cleanup_valid_encryption(system_key, target_id, target_kind_code);
CREATE INDEX cleanup_valid_system_radio ON cleanup_valid_encryption(system_key, source_radio_id);
CREATE INDEX cleanup_valid_context_bucket ON cleanup_valid_encryption(context_id, bucket_start_ms);
CREATE INDEX cleanup_valid_context_frequency ON cleanup_valid_encryption(context_id, frequency_hz, timeslot);

CREATE TEMP TABLE cleanup_talkgroup_count AS
SELECT system_key, target_id AS talkgroup_id, count(*) AS encrypted_count
FROM cleanup_valid_encryption
WHERE system_key IS NOT NULL AND target_id IS NOT NULL AND target_kind_code IN (1,3)
GROUP BY system_key, target_id;
CREATE UNIQUE INDEX cleanup_talkgroup_count_key ON cleanup_talkgroup_count(system_key, talkgroup_id);

CREATE TEMP TABLE cleanup_talkgroup_last AS
SELECT system_key, talkgroup_id, encryption_algorithm_id, encryption_key_id
FROM (
    SELECT system_key, target_id AS talkgroup_id, encryption_algorithm_id, encryption_key_id,
           row_number() OVER (PARTITION BY system_key, target_id ORDER BY observed_at_ms DESC, id DESC) AS rank
    FROM cleanup_valid_encryption
    WHERE system_key IS NOT NULL AND target_id IS NOT NULL AND target_kind_code IN (1,3)
)
WHERE rank = 1;
CREATE UNIQUE INDEX cleanup_talkgroup_last_key ON cleanup_talkgroup_last(system_key, talkgroup_id);

UPDATE p25_talkgroup_summary AS summary
SET encrypted_count = coalesce((
        SELECT value.encrypted_count FROM cleanup_talkgroup_count value
        WHERE value.system_key = summary.system_key AND value.talkgroup_id = summary.talkgroup_id), 0),
    last_encryption_algorithm_id = (
        SELECT value.encryption_algorithm_id FROM cleanup_talkgroup_last value
        WHERE value.system_key = summary.system_key AND value.talkgroup_id = summary.talkgroup_id),
    last_encryption_key_id = (
        SELECT value.encryption_key_id FROM cleanup_talkgroup_last value
        WHERE value.system_key = summary.system_key AND value.talkgroup_id = summary.talkgroup_id);

CREATE TEMP TABLE cleanup_radio_count AS
SELECT system_key, source_radio_id AS radio_id, count(*) AS encrypted_count
FROM cleanup_valid_encryption
WHERE system_key IS NOT NULL AND source_radio_id IS NOT NULL
GROUP BY system_key, source_radio_id;
CREATE UNIQUE INDEX cleanup_radio_count_key ON cleanup_radio_count(system_key, radio_id);

CREATE TEMP TABLE cleanup_radio_last AS
SELECT system_key, radio_id, encryption_algorithm_id, encryption_key_id
FROM (
    SELECT system_key, source_radio_id AS radio_id, encryption_algorithm_id, encryption_key_id,
           row_number() OVER (PARTITION BY system_key, source_radio_id ORDER BY observed_at_ms DESC, id DESC) AS rank
    FROM cleanup_valid_encryption
    WHERE system_key IS NOT NULL AND source_radio_id IS NOT NULL
)
WHERE rank = 1;
CREATE UNIQUE INDEX cleanup_radio_last_key ON cleanup_radio_last(system_key, radio_id);

UPDATE p25_radio_summary AS summary
SET encrypted_count = coalesce((
        SELECT value.encrypted_count FROM cleanup_radio_count value
        WHERE value.system_key = summary.system_key AND value.radio_id = summary.radio_id), 0),
    last_encryption_algorithm_id = (
        SELECT value.encryption_algorithm_id FROM cleanup_radio_last value
        WHERE value.system_key = summary.system_key AND value.radio_id = summary.radio_id),
    last_encryption_key_id = (
        SELECT value.encryption_key_id FROM cleanup_radio_last value
        WHERE value.system_key = summary.system_key AND value.radio_id = summary.radio_id);

CREATE TEMP TABLE cleanup_radio_talkgroup_count AS
SELECT system_key, source_radio_id AS radio_id, target_id AS talkgroup_id, count(*) AS encrypted_count
FROM cleanup_valid_encryption
WHERE system_key IS NOT NULL AND source_radio_id IS NOT NULL AND target_id IS NOT NULL
  AND target_kind_code IN (1,3)
GROUP BY system_key, source_radio_id, target_id;
CREATE UNIQUE INDEX cleanup_radio_talkgroup_count_key
ON cleanup_radio_talkgroup_count(system_key, radio_id, talkgroup_id);

UPDATE p25_radio_talkgroup_summary AS summary
SET encrypted_count = coalesce((
    SELECT value.encrypted_count FROM cleanup_radio_talkgroup_count value
    WHERE value.system_key = summary.system_key AND value.radio_id = summary.radio_id
      AND value.talkgroup_id = summary.talkgroup_id), 0);

CREATE TEMP TABLE cleanup_site_count AS
SELECT context_id, bucket_start_ms, count(*) AS encrypted_count
FROM cleanup_valid_encryption
GROUP BY context_id, bucket_start_ms;
CREATE UNIQUE INDEX cleanup_site_count_key ON cleanup_site_count(context_id, bucket_start_ms);

UPDATE p25_site_activity_bucket AS summary
SET encrypted_count = coalesce((
    SELECT value.encrypted_count FROM cleanup_site_count value
    WHERE value.context_id = summary.context_id AND value.bucket_start_ms = summary.bucket_start_ms), 0);

CREATE TEMP TABLE cleanup_site_talkgroup_count AS
SELECT context_id, target_id AS talkgroup_id, bucket_start_ms, count(*) AS encrypted_count
FROM cleanup_valid_encryption
WHERE target_id IS NOT NULL AND target_kind_code IN (1,3)
GROUP BY context_id, target_id, bucket_start_ms;
CREATE UNIQUE INDEX cleanup_site_talkgroup_count_key
ON cleanup_site_talkgroup_count(context_id, talkgroup_id, bucket_start_ms);

UPDATE p25_site_talkgroup_bucket AS summary
SET encrypted_count = coalesce((
    SELECT value.encrypted_count FROM cleanup_site_talkgroup_count value
    WHERE value.context_id = summary.context_id AND value.talkgroup_id = summary.talkgroup_id
      AND value.bucket_start_ms = summary.bucket_start_ms), 0);

CREATE TEMP TABLE cleanup_frequency_count AS
SELECT context_id, frequency_hz, timeslot, count(*) AS encrypted_count
FROM cleanup_valid_encryption
WHERE frequency_hz IS NOT NULL AND frequency_hz > 0
GROUP BY context_id, frequency_hz, timeslot;
CREATE UNIQUE INDEX cleanup_frequency_count_key
ON cleanup_frequency_count(context_id, frequency_hz, timeslot);

CREATE TEMP TABLE cleanup_frequency_last AS
SELECT context_id, frequency_hz, timeslot, encryption_algorithm_id, encryption_key_id
FROM (
    SELECT context_id, frequency_hz, timeslot, encryption_algorithm_id, encryption_key_id,
           row_number() OVER (
               PARTITION BY context_id, frequency_hz, timeslot ORDER BY observed_at_ms DESC, id DESC) AS rank
    FROM cleanup_valid_encryption
    WHERE frequency_hz IS NOT NULL AND frequency_hz > 0
)
WHERE rank = 1;
CREATE UNIQUE INDEX cleanup_frequency_last_key
ON cleanup_frequency_last(context_id, frequency_hz, timeslot);

UPDATE p25_site_frequency_summary AS summary
SET encrypted_count = coalesce((
        SELECT value.encrypted_count FROM cleanup_frequency_count value
        WHERE value.context_id = summary.context_id AND value.frequency_hz = summary.frequency_hz
          AND value.timeslot = summary.timeslot), 0),
    last_encryption_algorithm_id = (
        SELECT value.encryption_algorithm_id FROM cleanup_frequency_last value
        WHERE value.context_id = summary.context_id AND value.frequency_hz = summary.frequency_hz
          AND value.timeslot = summary.timeslot),
    last_encryption_key_id = (
        SELECT value.encryption_key_id FROM cleanup_frequency_last value
        WHERE value.context_id = summary.context_id AND value.frequency_hz = summary.frequency_hz
          AND value.timeslot = summary.timeslot);

INSERT INTO logger_status(key, value, updated_at_ms)
SELECT 'last_invalid_encryption_cleanup',
       'cleared_rows=' || count(*),
       cast(strftime('%s', 'now') AS INTEGER) * 1000
FROM cleanup_invalid_encryption
WHERE true
ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at_ms = excluded.updated_at_ms;

COMMIT;
PRAGMA optimize;
SQL

invalid_after=$(sqlite3 -readonly "$database" "
    SELECT count(*) FROM p25_activity_event
    WHERE encrypted = 1 AND (encryption_algorithm_id IS NULL OR encryption_algorithm_id NOT IN
        (0,1,2,3,4,5,65,129,130,131,132,133,136,137,159,160,161,162,163,164,165,166,167,168,169,170,
         171,172,173,174,175,176));")

mismatches=$(sqlite3 -readonly "$database" <<'SQL'
SELECT
    (SELECT abs(coalesce(sum(encrypted_count),0) -
        (SELECT count(*) FROM p25_activity_event a JOIN receiver_context rc ON rc.id=a.context_id
         WHERE rc.kind_code=1 AND a.encrypted=1)) FROM p25_site_activity_bucket)
  + (SELECT abs(coalesce(sum(encrypted_count),0) -
        (SELECT count(*) FROM p25_activity_event a JOIN receiver_context rc ON rc.id=a.context_id
         WHERE rc.kind_code=1 AND rc.system_key IS NOT NULL AND a.encrypted=1
           AND a.target_id IS NOT NULL AND a.target_kind_code IN (1,3))) FROM p25_talkgroup_summary)
  + (SELECT abs(coalesce(sum(encrypted_count),0) -
        (SELECT count(*) FROM p25_activity_event a JOIN receiver_context rc ON rc.id=a.context_id
         WHERE rc.kind_code=1 AND rc.system_key IS NOT NULL AND a.encrypted=1
           AND a.source_radio_id IS NOT NULL)) FROM p25_radio_summary)
  + (SELECT abs(coalesce(sum(encrypted_count),0) -
        (SELECT count(*) FROM p25_activity_event a JOIN receiver_context rc ON rc.id=a.context_id
         WHERE rc.kind_code=1 AND rc.system_key IS NOT NULL AND a.encrypted=1
           AND a.source_radio_id IS NOT NULL AND a.target_id IS NOT NULL AND a.target_kind_code IN (1,3)))
     FROM p25_radio_talkgroup_summary)
  + (SELECT abs(coalesce(sum(encrypted_count),0) -
        (SELECT count(*) FROM p25_activity_event a JOIN receiver_context rc ON rc.id=a.context_id
         WHERE rc.kind_code=1 AND a.encrypted=1 AND a.target_id IS NOT NULL AND a.target_kind_code IN (1,3)))
     FROM p25_site_talkgroup_bucket)
  + (SELECT abs(coalesce(sum(encrypted_count),0) -
        (SELECT count(*) FROM p25_activity_event a JOIN receiver_context rc ON rc.id=a.context_id
         WHERE rc.kind_code=1 AND a.encrypted=1 AND a.frequency_hz IS NOT NULL AND a.frequency_hz > 0))
     FROM p25_site_frequency_summary);
SQL
)

integrity=$(sqlite3 -readonly "$database" "PRAGMA quick_check;")

if [ "$invalid_after" != "0" ] || [ "$mismatches" != "0" ] || [ "$integrity" != "ok" ]; then
    echo "Validation failed; restoring backup $backup" >&2
    cp -p "$backup" "$database"
    rm -f "${database}-wal" "${database}-shm"
    exit 1
fi

echo "Invalid encrypted activity rows cleared: $invalid_before"
echo "Encryption summary mismatch count: $mismatches"
echo "Integrity check: $integrity"
echo "Backup: $backup"
