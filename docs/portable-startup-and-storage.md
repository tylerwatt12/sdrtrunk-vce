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

Current main and Alpha 11 builds accept their own exact current schema or one exact public predecessor layout: Alias
v4/P25 v24 as shipped unchanged by Alpha 8, Alpha 9, and Alpha 10. Those releases have the same schema fingerprint and
store no release provenance, so an exact profile from any of them is structurally indistinguishable and satisfies the
same single source gate. The transition advances Alias storage to v6 and P25 activity storage directly to v27. It
converts only a single plain, unambiguous
full-domain talkgroup catch-all per Alias list into the list-owned unmatched-talkgroup policy and removes retired P25
fully-qualified Alias rows and their dependent routes. The v24 shared projection cannot establish qualifier-safe P25
history. The clean direct migration rebuilds that shared storage instead of retaining partial projection state, so
projected P25, DMR, and NXDN identity history resets. The migrator preserves system-wide current P25 affiliations by
re-keying them and reconstructing only the required ordinary P25 radio, talkgroup, and relationship rows within the
current per-scope limits. Authoritative site presence, clear watermarks, and zero-local review evidence start empty
because the source layout cannot prove them. The staged migration refuses invalid or over-capacity affiliation state
instead of truncating it. Mixed schemas, intermediate development schemas including v25, and `webfirst` databases
remain unsupported.

See the version-matched What's New document for the exact preserved and reset data. Layouts older than the shared
Alpha 8/Alpha 9/Alpha 10 boundary must be upgraded sequentially until they reach that exact source layout.

## Recording Storage

`main` is the supported development and release track.

Main releases keep classic call recording. Recorded audio remains administrator-owned in the configured recording
directory. Main releases do not create or require the web recorded-call catalog and do not apply automatic time/space
retention to recordings.

The retired `webfirst` development branch used an incompatible managed-recording catalog. Its database is not a
supported migration input for `main`, so an old `webfirst` data directory must remain separate. Features selected from
that branch must be adapted to the current `main` storage and migration rules instead of copying its database contract
into a release unintentionally.

During unreleased development, test databases are converted once with a backed-up utility kept outside the repository.
Each public release receives one reviewed transition from the preceding public release. Ordinary application services
remain validation-only, and skip-release migration chains are not accumulated in the current source tree.

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
--upgrade-data <previous-install-or-data-folder>
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
it atomically. `--upgrade-data` is the non-graphical equivalent of choosing accepted portable data. In a numbered
release that supports its immediate predecessor, `--upgrade-current` explicitly authorizes the release transition.
Unreleased development builds do not add intermediate schema transitions.

Once a portable database exists, the app holds an operating-system lock for that data folder until shutdown. A second
sdrtrunk-vce process receives a clear “already in use” error before it can validate, upgrade, or write the same data.
