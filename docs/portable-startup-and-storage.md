# Portable Startup And Storage

SDRTrunk-VCE keeps each extracted distribution independent from stock SDRTrunk and from other VCE copies.

## Data Location

- Windows, Linux, and macOS distributions: `<install>/data`
- Development launches: `<working-directory>/data`, unless `sdrtrunk.vce.data.root` is set

The data directory owns the SQLite database, vault, preferences, logs, recordings, event logs, screenshots, streaming
files, temporary bug-report bundles, JMBE libraries, and optional modules. Java Preferences are stored in the SQLite
`application_settings` table; the operating-system Java preference store is not used by the normal application.

## First Launch And Application Migration

When `data/database/sdrtrunk.sqlite` is absent, a graphical launch first looks beside the current install folder for
portable data from an earlier sdrtrunk-vce build. The setup window offers these paths:

- **Migrate Existing** using a discovered previous data folder.
- **Choose Install…** to select a previous install folder, data folder, `database/sdrtrunk.sqlite` file, or a legacy
  macOS `.app` bundle.
- **Use Found XML** or **Choose XML…** to import an older XML playlist.
- **Start Fresh** with an empty profile.

The bundled Application Migrator is the only supported release database-migration entry point. During first-launch
migration it copies an accepted SQLite database into a private staging folder, updates only that staged copy, runs
schema, integrity, and foreign-key checks, and installs it only after every check succeeds. The source database and
previous installation are never changed. In-place upgrades and post-setup database replacement instead retain a
timestamped safety backup before atomically promoting their validated staged copy. The saved vault, JMBE libraries,
and optional module files are also copied when present during a full portable-data migration. Logs, recordings, event
logs, screenshots, and streaming output remain in the previous data folder instead of being duplicated.

Saved output and library paths that point inside the previous data folder are changed to the matching location inside
the new data folder. Deliberately shared paths outside the previous data folder are left alone.

### Alpha 8+ Database Compatibility

This source tree contains the Alpha 8+ format catalog, linear migration chain, and deterministic format fixtures.
Older binaries retain the source formats and migration behavior documented by their version-matched release notes.

Global database format 4/P25 activity schema v28 stores one system-level logical call separately from each distinct
learned P25 site observation. Its adjacent migration step records an explicit collection boundary and does not
backfill either metric from older physical call activity; every other preservation or reset remains declared by its
registered migration step.

Supported Alpha 8-or-newer macOS `.app` releases remain migration sources. The setup workflow finds or opens
the old bundle and uses its sibling `<app-name>-data` folder without changing that old installation. Current macOS
console packages store their active data only under `<install>/data`, unless an explicit
`sdrtrunk.vce.data.root` override is supplied.

The bundled migrator accepts every verified successfully published database format from Alpha 8 through the format
used by the running build. Main, alpha, and nightly builds share one global integer format and one linear forward
migration chain; the build label does not choose the route. Alpha 8, Alpha 9, and Alpha 10 share one legacy signature
and enter at the same baseline. Known-unpublished developer layouts are refused rather than guessed.

Preflight validates the complete schema, expected metadata, and critical invariants; resolves the source format;
and lists every step plus any declared reset or dropped retired state. Pre-Alpha 8, unknown, mixed, partially migrated,
newer-than-the-app, and retired `webfirst` databases are refused without mutation. See
[Database Migration Contract](database-migration.md) and the
version-matched What's New document for the exact preservation and reset behavior supplied by a build.

### Format Change And Safe Execution Rule

Every persisted schema or semantic change lands with its global format bump, in-repository adjacent migration step,
deterministic prior-format fixture, and tests. The bundled chain retains those steps back to the Alpha 8 baseline, so
a verified older alpha, nightly, or main database does not require sequential installation of skipped builds.
Ordinary application services remain validation-only.

When startup finds a verified older format, it offers the Application Migrator. The migrator first creates a
timestamped backup under `data/database/backups`, migrates another staged copy through the required steps, validates
the exact target signature and complete database, and then replaces the current database atomically. If migration
fails, the application does not start and the completed backup is retained.

## Recording Storage

`main` is the supported development and release track.

Main releases keep classic call recording. Recorded audio remains administrator-owned in the configured recording
directory. Main releases do not create or require the web recorded-call catalog and do not apply automatic time/space
retention to recordings.

The retired `webfirst` development branch used an incompatible managed-recording catalog. Its database is not a
supported migration input for `main`, so an old `webfirst` data directory must remain separate. Features selected from
that branch must be adapted to the current `main` storage and migration rules instead of copying its database contract
into a release unintentionally.

If no portable database is found, startup still searches `${user.home}/SDRTrunk/playlist` for `default.xml` and then
`playlist_v2.xml`. The legacy XML is read only.

After setup, **File > Import Legacy Playlist XML** can merge another supported playlist into the active profile.
Existing configuration is retained, imported name conflicts are renamed, and a validated timestamped database backup
is created before the configuration snapshot is committed. The source XML remains read only.

**File > Import SQLite Database** can instead replace the complete active database from a supported Alpha 8-or-newer
SQLite file. This is a replacement, not a merge. A bold red warning and migration plan are shown before confirmation.
SDRTrunk then stops its services, preserves the current database as a timestamped safety backup, migrates and validates
a staged copy of the selected file, installs it atomically, and restarts. Only SQLite contents are imported; files
beside the source database are not copied, current non-database portable files remain in place, and the selected source
is never changed. Stored portable paths are not remapped. If the imported database has no administrator, setup asks
for a new administrator password after restart. The success report can be copied and continues automatically after
its visible countdown; an unconfirmed failed replacement does not restart automatically.

Headless launches require one explicit option when the database is absent:

```text
--fresh
--import-xml <path>
--upgrade-data <previous-install-data-folder-or-sqlite-file>
```

Every new installation must also configure the fixed `admin` web account before the application can start. The
graphical setup wizard collects and confirms this password. For an unattended headless setup, put only the password
in a protected UTF-8 file and add:

```text
--admin-password-file <path>
```

The password must contain 7-256 characters. Remove or secure the input file after setup. The application stores only
the salted PBKDF2 verifier in the portable database. Existing installations are not retroactively forced through this
step; copied profiles retain an already configured administrator.

Fresh creation and XML import build the complete current schema in a temporary database, validate it, and then install
it atomically. Older binaries apply the source compatibility stated in their release notes. `--upgrade-data` is the
non-graphical equivalent of choosing a verified Alpha 8-or-newer SQLite file or portable data source and authorizes
the same bundled migration chain. A SQLite-file source contains no vault, JMBE library,
optional modules, or other external profile files, so the completion report will identify those items as not copied.

For an existing older database already in the active data path, headless startup uses `--upgrade-current` as the
explicit authorization to run the migrator. That flag authorizes any verified older format in the bundled Alpha
8-to-current chain.

Once a portable database exists, the app holds an operating-system lock for that data folder until shutdown. A second
sdrtrunk-vce process receives a clear “already in use” error before it can validate, upgrade, or write the same data.
