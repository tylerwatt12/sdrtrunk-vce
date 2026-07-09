# SQLite Schema Migrations

This folder contains explicit, external SQLite migrations for SDRTrunk RadioResolve Optimized.

Runtime SDRTrunk code should only create and validate the current schema during startup. It should not repair, alter,
drop, or migrate existing tables while the application is running. If a deployed database needs to change shape, stop
SDRTrunk, back up the database, run the matching migration here, then relaunch SDRTrunk.

## Current Database

The global SDRTrunk database normally lives at:

- macOS/Linux: `$HOME/SDRTrunk/database/sdrtrunk.sqlite`
- Windows receiver nodes: `%USERPROFILE%\SDRTrunk\database\sdrtrunk.sqlite`

## Available Migrations

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
  -DatabasePath C:\Users\Example\SDRTrunk\database\sdrtrunk.sqlite
```

Each wrapper uses Java source-file mode with the SDRTrunk distribution `lib` directory on the classpath. The wrappers run
an integrity check, create a timestamped backup beside the database, apply the migration, and verify the final schema.

If the wrapper cannot find the installed SDRTrunk application, pass the app home explicitly:

```bash
tools/sqlite-migrations/p25-history/migrate-v8-to-v9-drop-neighbor-nac.sh \
  /path/to/sdrtrunk.sqlite /path/to/sdr-trunk
```

```powershell
tools\sqlite-migrations\p25-history\migrate-v8-to-v9-drop-neighbor-nac.ps1 `
  -DatabasePath C:\Users\Example\SDRTrunk\database\sdrtrunk.sqlite `
  -AppHome C:\Users\Example\Desktop\sdr-trunk-windows-x86_64-vradioresolve-6 `
  -JavaHome C:\Users\Example\Java\jdk-25.0.1-full
```
