# Portable Startup And Storage

SDRTrunk-VCE keeps each extracted distribution independent from stock SDRTrunk and from other VCE copies.

## Data Location

- Windows, Linux, and macOS distributions: `<install>/data`
- Development launches: `<working-directory>/data`, unless `sdrtrunk.vce.data.root` is set

The data directory owns the SQLite database, vault, preferences, logs, recordings, event logs, screenshots, streaming
files, JMBE libraries, and optional modules. Java Preferences are stored in the SQLite
`application_settings` table; the operating-system Java preference store is not used by the normal application.

## First Launch And Application Migration

When `data/database/sdrtrunk.sqlite` is absent, a graphical launch first looks beside the current install folder for
portable data from an earlier sdrtrunk-vce build. The unified Setup Wizard offers four radio-card choices:

- **Start fresh** is recommended for new users.
- **Copy a previous VCE installation / data folder** includes the database and supported portable assets. Choose a
  discovered nearby installation or browse explicitly; legacy macOS `.app` sources remain supported.
- **Import a SQLite database only** imports only that file's contents. It does not copy a vault, JMBE library,
  optional modules, or neighboring files, and does not remap stored output paths.
- **Import legacy XML** reads supported configuration from an older playlist without modifying the source XML.

Source-selection controls appear only for the selected import option. Start fresh does not require a file or folder;
nearby installations are offered with the folder option, and older XML playlists with the XML option.

The wizard's fixed sequence is Starting point, Administrator, Web access, Digital audio, RadioReference,
Statistics & history, Your radios, Optimize decoding, and Review & finish. Every step remains visible. Valid imported
settings are marked **Carried over** and skipped by Continue; click a completed step to review or edit it. Deferred
and failed steps are not shown as successful. Exit preserves accepted settings, but never saves password drafts.

The sun/moon button at the top of the wizard switches between Light and Dark without losing entered values.
It works before choosing a starting point without creating a database. An explicit choice is saved once the profile
is created or imported; otherwise the imported theme is preserved. More theme presets remain available under
View → User Preferences → Application → Appearance.

New profiles default to HTTPS on localhost port **8090**. The web interface remains available for alias editing;
there is no desktop-only/disabled choice. An imported disabled web server is enabled on localhost and the adjustment
is disclosed. Network access is never automatically enabled. Choosing **Other devices** binds reachable network
interfaces under the host firewall; it is not a guaranteed LAN-only boundary and does not open firewall/router ports.
Existing administrator credentials are preserved. Changing an imported administrator password requires the current
password or the established account recovery workflow.

Digital audio setup recommends setting up JMBE so supported digital radio calls can be heard. It validates an existing
JMBE library or downloads and builds one inline after explicit download/compile consent. A successful installation
is confirmed on the page before Continue; a failed attempt offers retry, an existing library, or setup later.
RadioReference distinguishes stored credentials from a verified connection; passwords stay masked. Both are optional.
Fresh profiles collect summary statistics; detailed activity history is opt-in. Imported Off choices remain Off.
Time-based activity retention defaults to 30 days (1–365 days); these controls do not change recordings or ordinary logs.

Tuner discovery lists physical devices as **Detected**, not Ready. It only enumerates descriptors/identities; it does
not open, configure, tune, or start receiving from a tuner. **Skip discovery** is available only during a scan and
waits for discovery to stop safely. Afterward, use **Rescan** or **Continue**; having no hardware or a missing driver
does not prevent setup. Discovery runs again when revisiting the page rather than reusing an imported inventory.

**Optimize decoding** helps sdrtrunk-vce choose the fastest supported way to process radio signals on this computer;
it is not a general-purpose computer performance score. Running it during setup is recommended. Close other
applications and pause CPU-heavy background work first. Existing valid per-test results are reused; missing, reset,
or updated tests run individually. A changed CPU/JVM environment can
invalidate all tests. Retry keeps completed valid results. **Skip this time** does not permanently suppress future
prompts. Digital audio setup and decoding optimization display progress within the wizard; once started, navigation
waits until completion, failure, or acknowledged cancellation. Cancellation takes effect between optimization tests.

Review lists effective output folders, web access, and existing auto-start selections. Applicable release information
and vault-unlock controls appear here. The listener is checked before setup completes, and receiver construction
follows calibration. Migration completion includes **Copy Message** and a visible ten-second continuation countdown;
copying does not reset it. Other preparation pages do not automatically start work.

Use **Help → Setup Wizard…** to reopen setup after an explicit restart confirmation, or launch graphically with
`--setup-wizard`. Completed profiles normally launch without optional setup pages unless required preparation changes.
When explicitly reopened with an existing profile, the same Starting point page offers **Keep my current settings**,
**Replace settings from a SQLite database**, and **Import a legacy XML playlist**. No extra step is inserted. Merely
selecting an option or returning with Back never imports or replaces data; each import has a separate preview and
confirmation. Fresh-start and folder-copy choices remain exclusive to new installations. Imports are no longer in
the File menu. After an import completes, Starting point becomes a results review for that session.

The bundled Application Migrator is the only supported release database-migration entry point. During first-launch
migration it copies an accepted SQLite database into a private staging folder, updates only that staged copy, runs
schema, integrity, and foreign-key checks, and installs it only after every check succeeds. The source database and
previous installation are never changed. In-place upgrades and post-setup database replacement instead retain a
timestamped safety backup before atomically promoting their validated staged copy. The saved vault, JMBE libraries,
and optional module files are also copied when present during a full portable-data migration. Logs, recordings, event
logs, screenshots, and streaming output remain in the previous data folder instead of being duplicated.

For full-folder migration, saved output and library paths inside the previous data folder are changed to the matching
location inside the new data folder. Deliberately shared paths outside it are left alone. SQLite-only imports preserve
stored paths without remapping: absolute paths may still refer to the old installation, while portable-relative paths
resolve under the destination data root. Check the effective folders on Review before starting channels.

### Alpha 8+ Database Compatibility

Numbered Alpha and Nightly builds can have different application features, but they share one forward-only database-
format history. A channel name never selects a different schema or migration route.

This source tree contains the Alpha 8+ format catalog, linear migration chain, and deterministic format fixtures.
Older binaries retain the source formats and migration behavior documented by their version-matched release notes.

Global database format 4/P25 activity schema v28 stores one system-level logical call separately from each distinct
learned P25 site observation. Its adjacent migration step records an explicit collection boundary and does not
backfill either metric from older physical call activity; every other preservation or reset remains declared by its
registered migration step.

Global database format 5 introduced normalized web users, exact password verifiers and roles,
per-user browser preferences, configurable access overrides, a site-settings revision, and canonical saved-channel
identities. Active conventional channels use their configuration UUID; active trunked channels use both their
configuration UUID and nonblank RadioResolve GUID. The format 4-to-5 step preserves supported accounts, access,
receiver preferences, and active channel configuration; rebuilds reproducible channel query projections from each
authoritative channel document even when the old derived columns are stale; moves personal browser choices into each
account; and drops and counts recognized retired MPT-1327 and sound-card rows, retired web-policy overrides, and
superseded personal setting storage. Malformed or ambiguous administrator-owned channel documents, identities,
credentials, access policy, and shared preferences are refused rather than repaired or guessed.

Global database format 6 rekeys existing configured conventional
receiver contexts from the former RadioResolve GUID key to the saved channel configuration UUID key. The same context
row ID is retained, so linked activity history remains attached. Databases with conflicting, duplicated, malformed,
or otherwise ambiguous context identities are refused instead of merged or repaired.

Global database format 7 adds Conversation Mode. Its format 6-to-7 step upgrades each exact per-user browser
preference document to add Conversation Mode and the bounded calls-before-switching value. It preserves every other
personal preference, increments that user's preference revision, and removes the five retired receiver-wide browser
audio capacity keys. Unknown or incomplete preference documents and exhausted revisions are refused rather than
defaulted. A version-1 user with more than 16 selected Scan Lists is refused rather than silently truncated; reduce
that user's selection in the previous build and run the migration again.

Global database format 8 added per-user health-alert visibility. Its format 7-to-8 step upgrades each exact per-user browser
preference document to add the bounded list of receiver-health alerts that account has turned off. The new list is
empty for every migrated account, preserving the existing behavior where all alerts are on. Every other personal
preference is preserved and the user's preference revision is incremented. Unknown or incomplete preference
documents and exhausted revisions are refused rather than repaired or defaulted.

Global database format 9 added three per-user Live presentation choices in its format 8-to-9 step.
Active-trunked-channel filtering defaults off. Retaining the last call on idle rows and clearing idle voice quality
inherit the previous receiver-wide values for every existing account, defaulting false when those shared values are
absent. The two obsolete shared keys are removed while traffic-grant age-out, the site-settings revision, and every
unrelated portable preference remain intact.

Global database format 13 adds one bounded setup-progress record in the existing `application_settings` table. The
12-to-13 migration marks existing installations as previously configured without changing their other settings;
the wizard revalidates actual readiness. New/copy-imported destinations get their own incomplete setup session.
The record contains only completion and finite step states, not passwords, hardware inventories, benchmark results,
or job logs. No new tables or separate database-version scheme are introduced.

Supported Alpha 8-or-newer macOS `.app` releases remain migration sources. The setup workflow finds or opens
the old bundle and uses its sibling `<app-name>-data` folder without changing that old installation. Current macOS
console packages store their active data only under `<install>/data`, unless an explicit
`sdrtrunk.vce.data.root` override is supplied.

The bundled migrator accepts every verified successfully published database format from Alpha 8 through the format
used by the running build. Alpha and Nightly builds share one global integer format and one linear forward migration
chain; the build label does not choose the route. Alpha 8, Alpha 9, and Alpha 10 share one legacy signature
and enter at the same baseline. Known-unpublished developer layouts are refused rather than guessed.

Preflight validates the complete schema, expected metadata, and critical invariants; resolves the source format;
and lists every step plus any declared reset or dropped retired state. Pre-Alpha 8, unknown, mixed, partially migrated,
newer-than-the-app, and retired `webfirst` databases are refused without mutation. See
[Database Migration Contract](database-migration.md) and the
version-matched What's New document for the exact preservation and reset behavior supplied by a build.

### Format Change And Safe Execution Rule

Every persisted schema or semantic change lands with its global format bump, in-repository adjacent migration step,
deterministic prior-format fixture, and tests. The bundled chain retains those steps back to the Alpha 8 baseline, so
a verified older Alpha or Nightly database does not require sequential installation of skipped builds.
Ordinary application services remain validation-only.

When startup finds a verified older format, it offers the Application Migrator. The migrator first creates a
timestamped backup under `data/database/backups`, migrates another staged copy through the required steps, validates
the exact target signature and complete database, and then replaces the current database atomically. If migration
fails, the application does not start and the completed backup is retained.

See the target build's version-matched release notes for its accepted sources and the exact data it preserves, resets,
or retires. An Alpha with an older database format cannot open data already used by a newer Nightly. Keep separate
installation and data folders when comparing channels, and never copy a newer database into an older build.

## Recording Storage

Both supported release channels keep classic call recording. Recorded audio remains administrator-owned in the
configured recording directory. Alpha and Nightly do not create or require a web recorded-call catalog and do not
apply automatic time/space retention to recordings.

The retired `webfirst` development branch used an incompatible managed-recording catalog. Its database is not a
supported migration input for either active channel, so an old `webfirst` data directory must remain separate.

If no portable database is found, startup still searches `${user.home}/SDRTrunk/playlist` for `default.xml` and then
`playlist_v2.xml`. The legacy XML is read only.

After setup, **Help > Setup Wizard… > Import a legacy XML playlist** can merge another supported playlist into the
active profile.
Existing configuration is retained, imported name conflicts are renamed, and a validated timestamped database backup
is created before the configuration snapshot is committed. The source XML remains read only.

**Help > Setup Wizard… > Replace settings from a SQLite database** can instead replace the complete active database
from a supported Alpha 8-or-newer SQLite file. This is a replacement, not a merge. Receiving is already stopped by
restarting into setup. A bold red warning and migration plan are shown before confirmation. SDRTrunk then closes its
setup preferences, preserves the current database as a timestamped safety backup, migrates and validates
a staged copy of the selected file, installs it atomically, and restarts. Only SQLite contents are imported; files
beside the source database are not copied, current non-database portable files remain in place, and the selected source
is never changed. Stored portable paths are not remapped. If the imported database has no administrator, setup asks
for a new administrator password after restart. Every replacement saves an unfinished destination review before
promotion, so even an interrupted restart returns to setup. Valid imported settings are checked off; missing setup
and Review & finish are shown before reception can start. The success report can be copied and continues automatically after
its visible countdown; an unconfirmed failed replacement does not restart automatically.

Headless launches require one explicit option when the database is absent:

```text
--fresh
--import-xml <path>
--upgrade-data <previous-install-data-folder-or-sqlite-file>
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
it atomically. Older binaries apply the source compatibility stated in their release notes. `--upgrade-data` is the
non-graphical equivalent of choosing a verified Alpha 8-or-newer SQLite file or portable data source and authorizes
the same bundled migration chain. A SQLite-file source contains no vault, JMBE library,
optional modules, or other external profile files, so the completion report will identify those items as not copied.

For an existing older database already in the active data path, headless startup uses `--upgrade-current` as the
explicit authorization to run the migrator. That flag authorizes any verified older format in the bundled Alpha
8-to-current chain.

Once a portable database exists, the app holds an operating-system lock for that data folder until shutdown. A second
sdrtrunk-vce process receives a clear “already in use” error before it can validate, upgrade, or write the same data.
