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

Alpha 10 retains Alpha 9's migration behavior and accepts either an exact Alpha 7 database (Alias v3, P25 activity
v21, trunked-site v2, and no DMR activity schema) or the exact current database tuple shared by Alpha 8, Alpha 9, and
Alpha 10 (Alias v4, P25 activity v24, trunked-site v2, and DMR activity v1). It does not accept Alpha 1 through Alpha 6
databases, mixed schema combinations, or intermediate development schemas; those older public releases must be
upgraded sequentially through Alpha 7.

Alpha 8, Alpha 9, and Alpha 10 use the same schema, so an Alpha 8 or Alpha 9 profile has no database conversion or
history reset.
Application and tuner settings, channels, channel maps, broadcast streams, icons, aliases, calls, counts,
affiliations, site observations, identity evidence, and quality records remain in the copied database. An exact Alpha
7 profile still uses the existing conversion: supported configuration is preserved or converted, while activity and
statistics history starts fresh. Both paths use a validated staged copy and rebase recognized portable paths when the
data folder moves.

## Recording Storage By Release Track

`main` and `webfirst` are separate product and database release tracks.

`main` keeps classic call recording. Recorded audio remains administrator-owned in the configured recording
directory. Main releases do not create or require the web recorded-call catalog and do not apply automatic time/space
retention to recordings.

`webfirst` keeps managed recordings under `<recording-directory>/calls/v1`, the recorded-call catalog, browser
search/playback, and bounded age/space retention. Retention owns only files that validate against that managed layout.
Existing classic recordings and unknown files remain untouched and are not automatically imported into the catalog or
displayed by the web Recordings page.

When the first public `webfirst` release is prepared from Alpha 7, its reviewed staged-copy transition must add DMR
activity schema v1, store explicit Conventional/Trunked DMR modes, and create recorded-call catalog schema v3. The
upgrade does not move, rename, index, or delete recordings from the earlier classic layout. Only new recordings written
to the managed `calls/v1` layout enter the catalog unless a separate importer is designed later.

Later migrations follow the immediately preceding public release on their own track. A database migrated to
`webfirst` is not supported as a downgrade input to `main`. Keep separate portable data folders for the two release
tracks, and never run both builds against the same database.

During unreleased development, test databases are converted once with a backed-up utility kept outside the repository.
The next public release will receive one reviewed transition from the preceding public release. Ordinary application
services remain validation-only, and skip-release migration chains are not accumulated in the current source tree.

When a numbered release contains a transition for its immediately preceding release, startup offers the Application
Migrator. It first creates a timestamped backup under `data/database/backups`, migrates another staged copy, validates
the complete database, and then replaces the current database atomically. If migration fails, the application does
not start and the completed backup is retained.

If no portable database is found, startup still searches `${user.home}/SDRTrunk/playlist` for `default.xml` and then
`playlist_v2.xml`. The legacy XML is read only.

After setup, **File > Import Legacy Playlist XML** can merge another supported playlist into the active profile.
Existing configuration is retained, imported name conflicts are renamed, and a validated timestamped database backup
is created before the configuration snapshot is committed. The source XML remains read only.

Headless launches require one explicit option when the database is absent:

```text
--fresh
--import-xml <path>
--upgrade-data <previous-app-or-data-folder>
```

Fresh creation and XML import build the complete current schema in a temporary database, validate it, and then install
it atomically. `--upgrade-data` is the non-graphical equivalent of choosing accepted portable data. In a numbered
release that supports its immediate predecessor, `--upgrade-current` explicitly authorizes the release transition.
Unreleased development builds do not add intermediate schema transitions.

Once a portable database exists, the app holds an operating-system lock for that data folder until shutdown. A second
sdrtrunk-vce process receives a clear “already in use” error before it can validate, upgrade, or write the same data.
