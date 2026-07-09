#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
SQL_FILE="$SCRIPT_DIR/v8-to-v9-drop-neighbor-nac.sql"
DATABASE_PATH="${1:-$HOME/SDRTrunk/database/sdrtrunk.sqlite}"

if ! command -v sqlite3 >/dev/null 2>&1; then
    echo "sqlite3 is required for this external migration." >&2
    exit 1
fi

if [ ! -f "$DATABASE_PATH" ]; then
    echo "Database not found: $DATABASE_PATH" >&2
    exit 1
fi

sqlite_scalar()
{
    sqlite3 "$DATABASE_PATH" "$1"
}

schema_version="$(sqlite_scalar "SELECT value FROM database_metadata WHERE key = 'p25_activity_schema_version';")"
nac_columns="$(sqlite_scalar "SELECT COUNT(*) FROM pragma_table_info('site_neighbor') WHERE name = 'nac';")"

if [ "$schema_version" = "9" ] && [ "$nac_columns" = "0" ]; then
    echo "Database is already at P25 activity schema v9 with no site_neighbor.nac column."
    exit 0
fi

if [ "$schema_version" != "8" ]; then
    echo "Expected p25_activity_schema_version 8, found [$schema_version]. Refusing migration." >&2
    exit 1
fi

if [ "$nac_columns" != "1" ]; then
    echo "Expected exactly one site_neighbor.nac column, found [$nac_columns]. Refusing migration." >&2
    exit 1
fi

integrity="$(sqlite_scalar "PRAGMA integrity_check;")"
if [ "$integrity" != "ok" ]; then
    echo "Integrity check failed before migration: $integrity" >&2
    exit 1
fi

sqlite_scalar "PRAGMA wal_checkpoint(TRUNCATE);" >/dev/null

timestamp="$(date +%Y%m%d-%H%M%S)"
backup_path="$DATABASE_PATH.backup-v8-to-v9-$timestamp"
cp -p "$DATABASE_PATH" "$backup_path"
echo "Backup created: $backup_path"

sqlite3 "$DATABASE_PATH" < "$SQL_FILE"

schema_version="$(sqlite_scalar "SELECT value FROM database_metadata WHERE key = 'p25_activity_schema_version';")"
nac_columns="$(sqlite_scalar "SELECT COUNT(*) FROM pragma_table_info('site_neighbor') WHERE name = 'nac';")"
integrity="$(sqlite_scalar "PRAGMA integrity_check;")"

if [ "$schema_version" != "9" ] || [ "$nac_columns" != "0" ] || [ "$integrity" != "ok" ]; then
    echo "Migration verification failed." >&2
    echo "schema=$schema_version site_neighbor.nac_columns=$nac_columns integrity=$integrity" >&2
    echo "Backup remains at: $backup_path" >&2
    exit 1
fi

echo "Migration complete: P25 activity schema v9, site_neighbor.nac removed."
