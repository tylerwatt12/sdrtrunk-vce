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

The bundled Application Migrator is the only supported release database-migration entry point. It copies an accepted
SQLite database into a staging folder, updates only that staged copy, runs schema, integrity, and foreign-key checks,
and installs it only after every check succeeds. The previous database is never replaced. The saved vault, JMBE
libraries, and optional module files are also copied when present. Logs, recordings, event logs, screenshots, and
streaming output remain in the previous data folder instead of being duplicated.

Saved output and library paths that point inside the previous data folder are changed to the matching location inside
the new data folder. Deliberately shared paths outside the previous data folder are left alone.

Older macOS `.app` releases remain supported migration sources. The setup workflow can find or open the old bundle
and use its sibling `<app-name>-data` folder without changing that old installation. Current macOS console packages
store their active data only under `<install>/data`, unless an explicit `sdrtrunk.vce.data.root` override is supplied.

Numbered Alpha and Nightly builds can have different application features, but they share one forward-only database-
format history. A channel name never selects a different schema or migration route. Each target build accepts only
the exact source formats recognized by its bundled Application Migrator and refuses unknown, newer, mixed, or partial
layouts without changing the source.

See the target build's version-matched release notes for its accepted sources and the exact data it preserves, resets,
or retires. An Alpha with an older database format cannot open data already used by a newer Nightly. Keep separate
installation and data folders when comparing channels, and never copy a newer database into an older build.

## Recording Storage

Both supported release channels keep classic call recording. Recorded audio remains administrator-owned in the
configured recording directory. Alpha and Nightly do not create or require a web recorded-call catalog and do not
apply automatic time/space retention to recordings.

The retired `webfirst` development branch used an incompatible managed-recording catalog. Its database is not a
supported migration input for either active channel, so an old `webfirst` data directory must remain separate.

Existing schemas remain validation-only during normal startup. When a supported transition is needed, the Application
Migrator first creates a recoverable backup, migrates a staged copy, validates the complete database, and promotes it
only after every step succeeds. If migration fails, the active database is not replaced.

If no portable database is found, startup still searches `${user.home}/SDRTrunk/playlist` for `default.xml` and then
`playlist_v2.xml`. The legacy XML is read only.

After setup, **File > Import Legacy Playlist XML** can merge another supported playlist into the active profile.
Existing configuration is retained, imported name conflicts are renamed, and a validated timestamped database backup
is created before the configuration snapshot is committed. The source XML remains read only.

Headless launches require one explicit option when the database is absent:

```text
--fresh
--import-xml <path>
--upgrade-data <previous-install-or-data-folder>
```

Current `main` and Nightly builds require a password for the fixed `admin` web account before a new installation can
start. Numbered Alpha builds may omit this newer startup feature; use that Alpha's version-matched documentation. In a
build that includes it, the graphical setup wizard collects and confirms the password. For an unattended headless
setup, put only the password in a protected UTF-8 file and add:

```text
--admin-password-file <path>
```

The password must contain 7-256 characters. Remove or secure the input file after setup. The application stores only
the salted PBKDF2 verifier in the portable database. Existing installations are not retroactively forced through this
step; copied profiles retain an already configured administrator.

Fresh creation and XML import build the complete current schema in a temporary database, validate it, and then install
it atomically. `--upgrade-data` is the non-graphical equivalent of choosing accepted portable data. When a build
supports an in-place transition, `--upgrade-current` explicitly authorizes that migration.

Once a portable database exists, the app holds an operating-system lock for that data folder until shutdown. A second
sdrtrunk-vce process receives a clear “already in use” error before it can validate, upgrade, or write the same data.
