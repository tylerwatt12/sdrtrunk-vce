# SQLite Schema Migrations

This folder contains explicit, external SQLite migrations for sdrtrunk-vce.

Runtime SDRTrunk code should only create and validate the current schema during startup. It should not repair, alter,
drop, or migrate existing tables while the application is running. If a deployed database needs to change shape, stop
SDRTrunk, back up the database, run the matching migration here, then relaunch SDRTrunk.

## Current Database

Packaged builds keep the global SDRTrunk database in their portable data directory:

- macOS app: sibling `<app-name>-data/database/sdrtrunk.sqlite`
- Windows/Linux: `<install>/data/database/sdrtrunk.sqlite`

## Available Migrations

### P25 history v18 to v19

Adds compact aggregate `recorded_count` and `streamed_count` columns to `p25_talkgroup_summary`. Existing retained
hourly output buckets are summed into the new talkgroup totals, including output-only talkgroups. The migration also
preserves the original v18 output-metric start timestamp in dedicated metadata so later schema versions do not change
the chart boundary. No table or index is added.

Stop SDRTrunk before running it. The migration checks integrity, checkpoints the WAL, creates a timestamped backup,
applies and validates the change in one transaction, and runs a post-migration quick check.

```bash
tools/sqlite-migrations/p25-history/migrate-v18-to-v19-talkgroup-output-summary.sh \
  /path/to/sdrtrunk.sqlite /path/to/sdrtrunk-app
```

```powershell
tools\sqlite-migrations\p25-history\migrate-v18-to-v19-talkgroup-output-summary.ps1 `
  -DatabasePath C:\path\to\sdrtrunk.sqlite `
  -AppHome C:\path\to\sdrtrunk-app
```

### P25 history v17 to v18

Adds hourly `recorded_count` and `streamed_count` columns to the existing site and site/talkgroup activity buckets.
Stop SDRTrunk before running it. The migration checks integrity, checkpoints the WAL, creates a timestamped backup,
applies the change in one transaction, and validates the complete v18 schema. Existing history remains intact; the new
counters begin at zero.

```bash
tools/sqlite-migrations/p25-history/migrate-v17-to-v18-call-output-metrics.sh \
  /path/to/sdrtrunk.sqlite /path/to/sdrtrunk-app
```

```powershell
tools\sqlite-migrations\p25-history\migrate-v17-to-v18-call-output-metrics.ps1 `
  -DatabasePath C:\path\to\sdrtrunk.sqlite `
  -AppHome C:\path\to\sdrtrunk-app
```

### P25 history v16 to v17

Adds one latest-value site status record (LRA, MFID, broadcast clock, data/voice/registration service, data access,
Working Unit ID lease, TDMA, and u-Slots) plus the latest BSI callsign on current site channels. Stop SDRTrunk before
running it. The migration checks integrity, checkpoints WAL data, creates a timestamped backup, applies the schema
change in one transaction, and validates the complete v17 schema.

macOS/Linux:

```bash
tools/sqlite-migrations/p25-history/migrate-v16-to-v17-site-status.sh \
  /path/to/sdrtrunk.sqlite /path/to/sdrtrunk-vce
```

Windows PowerShell:

```powershell
tools\sqlite-migrations\p25-history\migrate-v16-to-v17-site-status.ps1 `
  -DatabasePath C:\path\to\sdrtrunk.sqlite `
  -AppHome C:\path\to\sdrtrunk-vce
```

### P25 history v14 or v15 to v16

Adds retained control-channel signal/decode-health buckets when starting at v14, replaces mutually exclusive channel
roles with non-exclusive tags, and recovers exact retained data-grant counts from detailed history. Stop SDRTrunk
before running it. The migration performs an integrity check, checkpoints WAL data, creates a timestamped database
backup, applies the rewrite in one transaction, and validates the complete v16 schema.

macOS/Linux:

```bash
tools/sqlite-migrations/p25-history/migrate-v14-or-v15-to-v16-channel-tags.sh \
  /path/to/sdrtrunk.sqlite /path/to/sdrtrunk-vce
```

Windows PowerShell:

```powershell
tools\sqlite-migrations\p25-history\migrate-v14-or-v15-to-v16-channel-tags.ps1 `
  -DatabasePath C:\path\to\sdrtrunk.sqlite `
  -AppHome C:\path\to\sdrtrunk-vce
```

### Legacy properties and tuner settings

After stopping SDRTrunk, this one-off tool moves the old `application_settings/default` UI payload,
`SDRTrunk.properties`, and `tuner_configuration.json` into the new keyed SQLite settings. It backs up the database,
verifies the result, and leaves both source files unchanged. Runtime SDRTrunk does not read or migrate these files.

```bash
java --enable-native-access=ALL-UNNAMED -cp "/path/to/sdrtrunk-vce/lib/*" \
  tools/settings-migrations/LegacySettingsToSqlite.java \
  /path/to/sdrtrunk.sqlite /path/to/SDRTrunk.properties /path/to/tuner_configuration.json
```

### P25 history v11 to v12

Rebuilds P25 ownership so radios and talkgroups belong to WACN plus System ID while GUID remains the site identity:

- preserves raw history, site RF facts, frequency summaries, and hourly site buckets
- combines lifetime radio and talkgroup totals from every GUID observing the same system
- seeds radio/talkgroup relationships from retained detailed history
- starts authoritative current affiliation state empty; new accepted affiliation messages populate it

macOS/Linux:

```bash
tools/sqlite-migrations/p25-history/migrate-v11-to-v12-system-identity.sh
```

Windows PowerShell:

```powershell
tools\sqlite-migrations\p25-history\migrate-v11-to-v12-system-identity.ps1 `
  -AppHome C:\path\to\sdrtrunk-vce
```

### Reset Stats Server schema

Rebuilds only the sdrtrunk-vce Stats Server tables, indexes, and views using the current schema:

- keeps SDRTrunk configuration, channels, aliases, streams, preferences, and vault data
- backs up the database beside the original file first
- drops old P25 history/stats objects
- creates the current `receiver_context`, `p25_*`, `conventional_*`, and `logger_status` objects
- validates the new schema

Use this when moving an existing node from an older stats/history schema to the current build. Historical stats rows are
not migrated.

macOS/Linux:

```bash
tools/sqlite-migrations/p25-history/reset-stats-schema.sh /path/to/sdr-trunk /path/to/sdrtrunk.sqlite
```

Windows PowerShell:

```powershell
tools\sqlite-migrations\p25-history\reset-stats-schema.ps1 `
  -InstallDir C:\path\to\sdrtrunk-vce `
  -Database C:\path\to\sdrtrunk.sqlite
```

### P25 history v9 to v10

Compacts `activity_event` storage for high-volume P25 history:

- moves repeated GUID identity into numeric `radio_context.id`
- stores action, event type, target kind, source/target IDs, and LCN as compact numeric values
- replaces broad text indexes with smaller context/time and partial indexes
- recreates `activity_event_resolved` as a readable view for SQLite browsing

macOS/Linux:

```bash
tools/sqlite-migrations/p25-history/migrate-v9-to-v10-compact-activity.sh
```

Windows PowerShell:

```powershell
tools\sqlite-migrations\p25-history\migrate-v9-to-v10-compact-activity.ps1
```

Pass a database path when migrating a non-default database:

```bash
tools/sqlite-migrations/p25-history/migrate-v9-to-v10-compact-activity.sh /path/to/sdrtrunk.sqlite
```

```powershell
tools\sqlite-migrations\p25-history\migrate-v9-to-v10-compact-activity.ps1 `
  -DatabasePath C:\path\to\sdrtrunk.sqlite
```

### P25 history v8 to v9

Removes the incorrect `nac` column from the `site_neighbor` table and updates
`database_metadata.p25_activity_schema_version` from `8` to `9`.

macOS/Linux:

```bash
tools/sqlite-migrations/p25-history/migrate-v8-to-v9-drop-neighbor-nac.sh
```

Windows PowerShell:

```powershell
tools\sqlite-migrations\p25-history\migrate-v8-to-v9-drop-neighbor-nac.ps1
```

Pass a database path when migrating a non-default database:

```bash
tools/sqlite-migrations/p25-history/migrate-v8-to-v9-drop-neighbor-nac.sh /path/to/sdrtrunk.sqlite
```

```powershell
tools\sqlite-migrations\p25-history\migrate-v8-to-v9-drop-neighbor-nac.ps1 `
  -DatabasePath C:\path\to\sdrtrunk.sqlite
```

Each wrapper uses Java source-file mode with the SDRTrunk distribution `lib` directory on the classpath. The wrappers run
an integrity check, create a timestamped backup beside the database, apply the migration, and verify the final schema.
The Java migrator is the single source for this schema change; do not keep a parallel SQL copy in this folder.

If the wrapper cannot find the installed SDRTrunk application, pass the app home explicitly:

```bash
tools/sqlite-migrations/p25-history/migrate-v8-to-v9-drop-neighbor-nac.sh \
  /path/to/sdrtrunk.sqlite /path/to/sdr-trunk
```

```powershell
tools\sqlite-migrations\p25-history\migrate-v8-to-v9-drop-neighbor-nac.ps1 `
  -DatabasePath C:\path\to\sdrtrunk.sqlite `
  -AppHome C:\path\to\sdrtrunk-vce `
  -JavaHome C:\path\to\jdk-25
```
