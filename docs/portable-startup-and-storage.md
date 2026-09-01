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

The bundled Application Migrator is the only supported release database-migration entry point. It copies an accepted
SQLite database into a staging folder, updates only that staged copy, runs schema, integrity, and foreign-key checks,
and installs it only after every check succeeds. The previous database is never replaced. The saved vault, JMBE
libraries, and optional module files are also copied when present. Logs, recordings, event logs, screenshots, and
streaming output remain in the previous data folder instead of being duplicated.

Saved output and library paths that point inside the previous data folder are changed to the matching location inside
the new data folder. Deliberately shared paths outside the previous data folder are left alone.

### Alpha 8+ Database Compatibility

This build uses one global database-format number and one linear chain across Alpha and nightly releases. The bundled
catalog accepts the exact shared Alpha 8, Alpha 9, and Alpha 10 layout as format 1 and migrates it to the current format
2. An exact markerless format-2 database is validated and safely adopts the authoritative marker through the same
migrator. Pre-Alpha 8, retired `webfirst`, known-unpublished developer, unknown, mixed, partially migrated, corrupt,
and newer-than-this-build databases are refused without mutation.

The format 1-to-2 step preserves supported administrator configuration while converting numeric playback priority to
Default scan-list Listen membership and eligible catch-all talkgroup Aliases to list-level unmatched behavior. It
removes retired fully-qualified P25 Alias matcher rows and their dependent routes and resets rebuildable P25
affiliation and identity evidence. Preflight and completion show the exact counts before any staged result can be
promoted. See [Database Migration Contract](database-migration.md) for the complete boundary.

## Alpha And Nightly Channels

SDRTrunk-VCE has two release channels with intentionally different feature sets:

- Numbered **Alpha** releases are the more stable maintenance line. They receive selected fixes after review and
  testing and may omit newer Nightly features.
- **Nightly** is the rolling development build from `main`. New features and fixes appear there first, with a higher
  chance of change or regression.

The channels are product choices, not separate database-format families. Database formats move forward in one
history, but a Nightly can advance before the next Alpha includes support for that newer format. Keep separate portable
data folders when testing both channels, never run both builds against the same database, and never try to open a
database from a newer build in an older build. Use the bundled Application Migrator only when the target release says
it supports the source database. Rollback means reopening the preserved older data with the older build, never a
down-migration.

Both channels keep classic call recordings as administrator-owned files in the configured recording directory. They
do not require a managed recording catalog or automatically delete recordings by age or disk usage.

When startup finds a supported older format, it offers the Application Migrator. The migrator first creates a
timestamped backup under `data/database/backups`, migrates another staged copy through every required adjacent step,
validates the exact current format and complete database, and then replaces the active database atomically. If
migration fails, the application does not start and the completed backup is retained.

If no portable database is found, startup still searches `${user.home}/SDRTrunk/playlist` for `default.xml` and then
`playlist_v2.xml`. The legacy XML is read only.

After setup, **File > Import SQLite Database** replaces the complete active database through the same backed-up,
staged, validated workflow. It is not a row merge. A file-only import leaves the selected source and neighboring files
unchanged, does not copy external profile artifacts or remap stored portable paths, and restarts only after confirmed
success.

**File > Import Legacy Playlist XML** can merge another supported playlist into the active profile.
Existing configuration is retained, imported name conflicts are renamed, and a validated timestamped database backup
is created before the configuration snapshot is committed. The source XML remains read only.

Headless launches require one explicit option when the database is absent:

```text
--fresh
--import-xml <path>
--upgrade-data <previous-app-or-data-folder>
```

Fresh creation and XML import build the complete current schema in a temporary database, validate it, and then install
it atomically. `--upgrade-data` is the non-graphical equivalent of choosing accepted portable data.
`--upgrade-current` explicitly authorizes migration of a supported older active database through the bundled chain.

Once a portable database exists, the app holds an operating-system lock for that data folder until shutdown. A second
sdrtrunk-vce process receives a clear “already in use” error before it can validate, upgrade, or write the same data.
