# Portable Startup And Storage

SDRTrunk-VCE keeps each extracted distribution independent from stock SDRTrunk and from other VCE copies.

## Data Location

- Windows and Linux distributions: `<install>/data`
- macOS application: `<app-name>-data` beside the `.app` bundle
- Development launches: `<working-directory>/data`, unless `sdrtrunk.vce.data.root` is set

The data directory owns the SQLite database, vault, preferences, logs, recordings, event logs, screenshots, streaming
files, temporary bug-report bundles, JMBE libraries, and optional modules. Java Preferences are stored in the SQLite
`application_settings` table; the operating-system Java preference store is not used by the normal application.

## First Launch And Application Migration

When `data/database/sdrtrunk.sqlite` is absent, a graphical launch first looks beside the current app or install
folder for portable data from an earlier sdrtrunk-vce build. The setup window offers these paths:

- Migrate using a discovered previous data folder.
- Choose a previous `.app`, install folder, data folder, or `database/sdrtrunk.sqlite` file.
- Import an older XML playlist.
- Set up as new.

The bundled Application Migrator is the only supported database-migration entry point. It copies the previous SQLite
database into a staging folder, updates only that staged copy, runs schema, integrity, and foreign-key checks, and
installs it only after every check succeeds. The previous database is never replaced. The saved vault, JMBE libraries,
and optional module files are also copied when present. Logs, recordings, event logs, screenshots, and streaming output
remain in the previous data folder instead of being duplicated.

Saved output and library paths that point inside the previous data folder are changed to the matching location inside
the new data folder. Deliberately shared paths outside the previous data folder are left alone.

In one staged migration, the Application Migrator handles every supported subsystem change that the selected database
needs:

- Alias schema v2 to v3, adding the optional Alias description while preserving all Alias rows.
- P25 activity schema v19 or v20 to v21. A v19 database receives the foreign-system band tables and the quality-retention
  index; a v20 database receives only that index.
- An absent trunked-site subsystem to its first public schema, v2.

Already-current subsystems are validated without change. The migration runs in one bundled child process against the
staged copy, so ordinary application services remain validation-only. Unsupported schema versions are refused with an
explanation. A database older than the supported range must first be opened with an appropriate intermediate release;
current packages do not contain standalone migration utilities.

If the current data folder contains any supported older subsystem schema, startup offers the Application Migrator. It
first creates a timestamped backup under `data/database/backups`, migrates another staged copy, validates the complete
database, and then replaces the current database atomically. If migration fails, the application does not start and
the completed backup is retained.

If no portable database is found, startup still searches `${user.home}/SDRTrunk/playlist` for `default.xml` and then
`playlist_v2.xml`. The legacy XML is read only.

Headless launches require one explicit option when the database is absent:

```text
--fresh
--import-xml <path>
--upgrade-data <previous-app-or-data-folder>
```

Fresh creation and XML import build the complete current schema in a temporary database, validate it, and then install
it atomically. `--upgrade-data` is the non-graphical equivalent of choosing previous portable data. When an existing
data folder contains a supported older schema, `--upgrade-current` explicitly authorizes the Application Migrator.
Schema changes are performed only by that bundled migrator against a backed-up staged database.

Once a portable database exists, the app holds an operating-system lock for that data folder until shutdown. A second
sdrtrunk-vce process receives a clear “already in use” error before it can validate, upgrade, or write the same data.
