# Portable Startup And Storage

SDRTrunk-VCE keeps each extracted distribution independent from stock SDRTrunk and from other VCE copies.

## Data Location

- Windows and Linux distributions: `<install>/data`
- macOS application: `<app-name>-data` beside the `.app` bundle
- Development launches: `<working-directory>/data`, unless `sdrtrunk.vce.data.root` is set

The data directory owns the SQLite database, vault, preferences, logs, recordings, event logs, screenshots, streaming
files, temporary bug-report bundles, JMBE libraries, and optional modules. Java Preferences are stored in the SQLite
`application_settings` table; the operating-system Java preference store is not used by the normal application.

## First Launch And Upgrades

When `data/database/sdrtrunk.sqlite` is absent, a graphical launch first looks beside the current app or install
folder for portable data from an earlier sdrtrunk-vce build. The setup window offers these paths:

- Upgrade using a discovered previous data folder.
- Choose a previous `.app`, install folder, data folder, or `database/sdrtrunk.sqlite` file.
- Import an older XML playlist.
- Set up as new.

The Upgrade Assistant copies the previous SQLite database into a staging folder, updates only that staged copy, runs
schema, integrity, and foreign-key checks, and installs it only after every check succeeds. The previous database is
never replaced. The saved vault, JMBE libraries, and optional module files are also copied when present. Logs,
recordings, event logs, screenshots, and streaming output remain in the previous data folder instead of being
duplicated.

Saved output and library paths that point inside the previous data folder are changed to the matching location inside
the new data folder. Deliberately shared paths outside the previous data folder are left alone.

The bundled P25 activity upgrade accepts public schema v19 or v20 and produces v21. A v19 database receives the v20
foreign-system band tables and the v21 quality-retention index; a v20 database receives only that index. The migration
runs in a bundled child process against a staged copy, so the normal application startup path remains validation-only.
A database already at v21 is copied and validated without a schema change. Other versions are refused with an
explanation.

If the current data folder itself contains a v19 or v20 database, startup offers **Upgrade and Start**. It first
creates a standalone backup under `data/database/backups`, migrates another staged copy, validates it, and then
replaces the current database atomically. If an upgrade fails, the application does not start and the completed backup
is retained.

The trunked-site subsystem was introduced publicly at schema v2. Older databases selected through the Upgrade
Assistant do not contain that subsystem; the bundled staged installer adds the complete current schema before the
copied profile is validated and promoted. Existing active databases remain validation-only at normal startup.

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
data folder contains a v19 or v20 database, `--upgrade-current` explicitly authorizes its one-time upgrade to v21.
Schema changes are performed only by the bundled one-off upgrade helper against a staged database.

Once a portable database exists, the app holds an operating-system lock for that data folder until shutdown. A second
sdrtrunk-vce process receives a clear “already in use” error before it can validate, upgrade, or write the same data.
