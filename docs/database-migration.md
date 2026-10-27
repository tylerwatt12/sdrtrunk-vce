# Moving and updating VCE data

VCE can update supported older settings during startup or transfer them into a new installation. The Setup Wizard runs
the bundled **Application Migrator** for you. You do not need a separate download or each intervening VCE release.

If you are coming from mainline sdrtrunk, choose **Import XML playlist** and select your playlist. For first-run choices and
the rest of setup, see [Setup, updates, and your saved data](portable-startup-and-storage.md). Related guides are in
the [documentation index](README.md).

## Choose the right operation

| What you want to do | Where to start | What changes |
| --- | --- | --- |
| Use a newer build with the same active VCE data folder | Review the **Update** offered during startup | Confirm your manual backup and choose history scope before large operations. The active profile is updated in place. |
| Upgrade a previous VCE installation into a new download | **Upgrade existing VCE**, then choose its folder | Confirm your manual backup and choose history scope first. Its portable data folder is moved into the empty new installation, preserving recordings, the Managed Recordings catalog, vault, JMBE, and optional modules. |
| Start a new installation from a supported XML playlist | **Import XML playlist**, then choose the playlist | Supported channels, aliases, and streaming settings initialize the new profile; the XML source stays unchanged. |

VCE folder upgrades and XML imports are offered only before the destination has a database. Choose an installation folder, portable
data folder, supported macOS `.app`, or XML playlist. Standalone SQLite selection, replacement of an existing profile,
and XML merge into an existing profile are not public setup operations. Use the existing desktop and web settings
for ordinary changes; see [Change settings after setup](portable-startup-and-storage.md#change-settings-after-setup).

VCE profile upgrades support recognized database formats used by **Alpha 12 and later**, up to the format understood by the
running build. Alpha 11 uses the same format as Alpha 12 and remains compatible. For an older database format, first
open it in Alpha 12 and complete its update. Alpha and Nightly share one forward-only format history. An older build
cannot open a database already updated to a newer format. The retired `webfirst` formats and unknown or unsafe layouts
are unsupported. The selected database's structure determines compatibility; its filename or release label does not.

## Before you begin

Stop VCE and copy the complete existing installation, including its `data` folder, to a separate backup location.
For a legacy macOS `.app`, also copy its sibling `<app-name>-data` folder; copying the application bundle alone does
not include that profile. Also back up recording or output folders placed outside the installation. Keep the matching
application files if you may need to return to the older version. The graphical wizard asks you to confirm this manual backup before
large operations; it does not make or verify a backup for you, and it does not add an automatic database backup.

A VCE folder upgrade transfers ownership of the portable data: the old `data` folder moves into the new
installation. The old application folder left behind is not a complete backup and must not be started as though
its data were still there. Keep your independent manual backup until the new installation receives normally and
its recording and streaming settings are checked.

The fast folder transfer requires source and destination to be on the same filesystem. That lets VCE move the folder
without copying every audio file. It refuses a cross-filesystem transfer before moving anything, instead of silently
falling back to a long copy. The new destination must be empty; existing profiles are not merged. Headless commands
retain their separately documented backup behavior.

## Review, update, and check the result

1. For a new profile, choose the previous installation folder or supported XML playlist. A macOS application bundle
   can also identify its portable profile.
2. Before large operations for a VCE upgrade, close the previous application and confirm **I have backed up my
   existing VCE installation, including its data folder.** Choose **Keep activity and statistics** now. It starts
   unchecked for settings-only migration; select it to carry forward usable history. XML import and Start fresh
   do not need these VCE upgrade choices.
3. For a folder transfer, choose **Review upgrade**. VCE checks the supported format and bounded configuration
   under the source profile lock without copying the database or scanning saved history. Review the source,
   destination, chosen scope, and declared resets or retired settings; adjust your choices if needed. Choose
   **Begin upgrade**, or **Update settings** for an active-profile update. Receiving has not started, and the
   profile stays locked while the upgrade runs.
4. Setup continues to the next needed step. The optional report gives actual preserved, repaired, defaulted,
   reset, and skipped-item counts.
5. Finish setup review. Check channels and aliases, account access, recording and streaming routes, output folders,
   digital voice libraries, and channel auto-start selections before relying on the new installation.

An active-profile update commits its database changes before activation. For a full-history update, the configuration/history split first
commits its extracted configuration, then commits the existing database as retained activity and activates the checked
pair. Only the Application Migrator completes an interrupted activation. A failure before the source transaction
commits rolls back those database changes. A folder upgrade moves the portable profile and changes its saved paths
to the new location. Its database work still uses the selected scope and required final checks. This is an explicit
move, not the internal source-preserving copy-import operation. Settings are reviewed before reception can start.

### Choose how much to migrate

**Keep activity and statistics** starts unchecked in the graphical VCE upgrade. This keeps
channels, aliases, lists, accounts, credentials, and settings while starting receiver
Activity, statistics, learned observations, and alert history fresh. Old pending alert deliveries are discarded;
notification topics, rules, and subscriptions remain. Account preferences, including dismissed guides, are kept.
Recording audio and the separate Managed Recordings catalog are not deleted by either choice. Headless and internal
copy-imports retain their separately described file scope. Selecting the checkbox opts into carrying forward usable history;
required historical resets still apply and appear in the review. Headless migration retains its full-content default.

Settings-only preparation extracts the configuration into a small private database and starts receiver history
fresh. It does not first copy or fingerprint the whole unwanted history, count its rows, delete them one by one,
or run physical-integrity scans over discarded history. It still identifies the supported source format and checks
the configuration it will retain. The converted result must pass the current configuration and format checks.

Full migration can take much longer because retained history may need conversion. The review gives a broad
planning range from file and journal sizes without scanning history just to estimate the duration. The source
format, retained contents, and storage speed determine the work; the estimate is not a countdown or deadline.

Both choices use the supported format catalog and adjacent configuration conversions. Keeping history also retains
the applicable historical conversion steps. Neither choice treats recording audio or the Managed Recordings catalog
as disposable Activity. No upgrade silently compacts a large database or offers an automatic downgrade.

### Find the migration report after setup

Completed updates and imports automatically save a text report in the new installation's
`data/logs/migration-reports` folder. Optional Managed Recordings catalog updates save their own report there.
The latest ten reports are kept, and the setup report shows the saved filename. These reports remain available
after setup closes; they are separate from the ordinary application log.

Each report includes the application build, source database and journal sizes, selected history and backup scope,
completion results, and elapsed milliseconds for copying, hashing, integrity checks, individual database-format
steps, history discard, validation, commit, and finalization when those phases run. Source-review timings are
included for a reviewed external import. Nested phases overlap, so their times must not be added together.
Reports hide credentials, endpoints and personal paths. A report-write failure shows a warning without undoing a
completed update; copy the displayed report before closing setup in that case.

The selected history scope is recorded before large operations. Settings-only reports describe omitted history
without running a whole-history count just to report its size. Full-history reports identify the conversions and
checks actually performed. Neither mode makes a throwaway converted database just to preview the result.

## What is kept, copied, or reset

Usable channels, aliases, accounts, credentials, and settings are preserved or converted to the current format.
Some older activity, signal history, or cached information may be reset when it cannot be carried forward reliably;
new reception rebuilds those observations. An unusable setting or configuration component may be repaired, defaulted,
or skipped independently. The plan describes these possibilities and the final report names the actual effects.
If an older import has damaged Activity or statistics, the import discards that saved receiver-history component
instead of reconstructing it. Activity events, observed radios and systems, affiliations, learned site information,
quality history and counters start over. Usable channels, aliases, lists, accounts and settings are retained.
Recording files and the separate Managed Recordings catalog are not deleted or rebuilt by this reset.
Dismissed page tours are saved with your web account and carried forward with the database, including across
password resets. A browser with earlier local tour history can add that history when you sign in.

Remote Links pairing and connection settings and paging-tone detection are stored in the application database.
Upgrading an older profile or importing its complete data folder imports their former JSON values when the database
has no saved value. The original files remain unchanged. The update removes retired application-managed
voice-enhancement and continuous-gain choices; the selected JMBE library owns its audio processing and output format.
Unused legacy model-selection files are not read or imported.
Choose the complete folder so eligible settings that an older build kept outside SQLite can be inspected with it.

An earlier format-53 update could create defaults without importing the old JSON files. A later update preserves
those database choices because a default may also be an intentional selection. Explicit Application Migrator
recovery can restore selected valid legacy values over reviewed defaults; normal setup does not offer this repair
as a starting-point choice. Recovery stops if the reviewed database value changes. Newer or non-default choices are retained.

A VCE folder upgrade moves the complete portable `data` folder, including its encryption vault, JMBE files,
optional modules, logs, recordings, screenshots, event logs, streaming output, and separate Managed Recordings
catalog. A settings-only choice changes application history scope, not the ownership of those other files.

Supported saved paths inside the previous data folder change to the corresponding new locations. This includes
Classic and Managed recording output and saved Recording Tuner inputs within the transferred folder. Paths outside
it keep their locations; their files are not moved. Check the effective folders on **Review & finish**, and keep any
old folder still referenced by an external output or input. Internal database-only imports do not transfer these
files or remap paths.

Managed Recordings has a separate audio library and catalog. Its supported older catalog can be updated independently
before receiving starts, preserving the indexed calls. If that optional update fails, retry or continue receiving with
Managed Recordings unavailable. See the [storage guide](portable-startup-and-storage.md#where-files-are-stored-and-how-to-back-them-up)
for backing up the audio and catalog together.

## If an update or import fails

Keep the source and use **Copy error** or the expandable details to retain the reason. Close another instance if the
data folder is already in use, free disk space if requested, or choose the correct installation folder or playlist and
review again.
Unknown, newer, mixed, or structurally incomplete formats and unreadable retained configuration are refused rather
than guessed. A settings-only upgrade can recover readable configuration even when the discarded history is damaged;
it does not inspect that history to certify that the whole old database is healthy. This does not relax the integrity
and validation requirements of strict copy-imports or the applicable retained-history conversion. Do not delete the
database to bypass an error.

An active-profile database failure before commit can be retried after correcting the cause. A folder-transfer
failure must be resolved using its reported data location; do not start another instance against data that may
already have moved. Keep the manual backup available, including the old application files and matching databases.
The internal copy-import engine still refuses to install a partial working copy and leaves its selected source unchanged.

If a graphical upgrade was interrupted, return to the Setup Wizard and use **Finish update** before launching
headless. Headless startup refuses a pending graphical transfer or settings replacement; `--upgrade-current` does
not finish that separate operation.

After a successful update there is no automatic downgrade. If the report says the update completed with a cleanup
warning, do not repeat it as if conversion failed. Returning to an older build requires restoring its compatible
data from your manual backup, not reopening the old installation folder left without its transferred data.

## Technical migration reference

<details>
<summary>Format catalog, historical conversion policies, execution guarantees, and required tests</summary>

The following is the implementation contract for the bundled Application Migrator. It records the global format
catalog, complete format-2-to-current chain, deterministic format fixtures, and safety integration. These are
implementation and maintenance requirements, not extra setup steps for listeners. Numbered Alpha distributions
keep version-matched release notes; rolling Nightlies use their bundled current documentation and the migrator's
preflight and completion reports.

The database-file copy-import and replacement contracts below describe retained internal engine and safety-test
boundaries. They are distinct from the explicit graphical VCE folder transfer and are not public SQLite-file import
support. Headless `--upgrade-data` retains its source-preserving folder/application import behavior. No
running-application menu reopens setup or replaces its database.

## Support Boundary

The migrator supports every distinct sdrtrunk-vce database format from format 2, used by the latest published
Alpha 12, through the format used by the running build. `main`, alpha, and nightly are build channels over one
database-format sequence; they are not separate migration tracks. Each retained distributed format must have a catalog
entry and fixture;
omitting it from the catalog does not make it an allowed support exception.

- A valid exact current-format database is accepted without mutation. A structurally exact current database with
  recoverable row-level damage can be repaired only by the Application Migrator, with every change itemized.
  The owned current profile uses its in-place transaction; an explicitly selected external source uses a staged copy.
- A structurally recognized older format is migrated forward through the linear chain. Recoverable row-level damage
  does not disqualify the whole database: each configuration component is preserved, repaired, defaulted, or skipped
  independently and the completion report gives non-secret counts.
- Alpha 11 and Alpha 12 share the exact format-2 signature and therefore enter at the same supported baseline.
- Format 1, used by Alpha 8, Alpha 9 and Alpha 10, is retired. Its conversion is available in Alpha 12; this build
  refuses it without changing the source and directs the operator to update with Alpha 12 first.
- Older, retired `webfirst`, unknown, mixed, and structurally partial layouts are refused without guessing. Strict
  copy-imports retain physical-integrity checks and source immutability. Settings-only extraction may preserve
  readable configuration from an admitted layout despite damage in history that the user explicitly discards;
  unreadable retained configuration is refused. Retained-history conversion keeps its applicable validation contract.
- Migration is forward-only. A build must refuse a database whose format is newer than it understands.

Support is based on database structure, not a release label, filename, timestamp, or build number. This matters for
nightly builds, which may not contain durable release provenance.

## Legacy Inventory Gate

The historical inventory came from the audit of Alpha release tags, recoverable nightly and
schema-changing commits, archived artifacts, and available deployed database samples. The runtime format catalog is
also the checked-in legacy manifest; do not create a second hand-copied inventory. Each legacy entry records:

- its global format ID and complete format signature;
- expected subsystem metadata and critical invariants;
- known source tags, commits, build ranges, or archived samples;
- its populated fixture; and
- its preservation, reset, drop, and refusal policy.

Several builds may reference one entry when they are structurally and semantically indistinguishable. Tests enumerate
the catalog entries and their fixtures directly. If a known distributed build cannot be reconstructed or matched to a
safe signature, record it as unresolved and obtain an exact database sample; do not claim complete retained-format
support or guess a route until the entry is resolved.

The original replacement audit recovered six formats from successful alpha, nightly, and main builds: the
shared Alpha 8/9/10 and early-nightly baseline, the scan-list/P25-v26 format, the P25-v27 site-projection format, the
P25-v28 logical-call/site-observation format, the normalized web-user/saved-channel-identity format, and the current
canonical conventional-context identity format. The runtime catalog is the sole exact inventory of their
fingerprints, subsystem metadata, source references, fixtures, and data policies; this document intentionally does
not duplicate those values. Format 1 is now retired; the retained catalog starts at format 2.

Seven intermediate schema fingerprints are source-recoverable, but the nightly workflow failed before packaging them.
They are cataloged as known unsupported developer states so the inspector can give a precise refusal instead of
guessing. If one was actually deployed, retain that database and its matching `build_info.txt`; support requires an
exact deployed sample and an explicit adjacent step.

## One Version And One Chain

Each new database stores one authoritative, monotonically increasing integer `database_format_version` in the existing
`database_metadata` table. That value describes the complete persisted SQLite contract, including schema semantics
that an exact DDL fingerprint alone cannot identify. Historical formats retain their subsystem-version rows as part
of their frozen signatures. Format 15 removes those redundant rows from the current database; only the global format
version chooses or validates the current contract. Do not add another version or migration-history table.

Marker-bearing historical formats resolve by the authoritative global version and its exact schema fingerprint. The
format marker never overrides a structurally mixed layout, but redundant subsystem metadata and recoverable row-content
invariants are migration inputs rather than whole-file admission failures; adjacent steps normalize or remove them
before final strict validation. This permits recovery from incomplete preference writes, orphaned relationships, and
damaged derived metadata without guessing a schema. Markerless legacy formats still resolve from all matching
fingerprint candidates and are accepted only when their structure, subsystem metadata, and critical disambiguating
invariants identify one safe source format; an ambiguous markerless state is refused.

Supported legacy databases that predate the global marker are admitted through the format registry. A signature
combines the complete schema fingerprint with exact subsystem metadata and critical structural/data invariants. A
unique signature may still contain independently repairable row damage. When several semantic formats share one
schema, their metadata and disambiguating invariants must identify exactly one source; the migrator refuses an
ambiguous markerless state instead of guessing which build produced it.

Migration steps form one ordered chain:

```text
format 2 (Alpha 12 family) -> format 3 -> format 4 -> format 5 -> format 6 -> format 7 -> format 8 -> format 9 -> format 10 -> format 11 -> format 12 -> format 13 -> format 14 -> format 15 -> format 16 -> format 17 -> format 18 -> format 19 -> format 20 -> format 21 -> format 22 -> format 23 -> format 24 -> format 25 -> format 26 -> format 27 -> format 28 -> format 29 -> format 30 -> format 31 -> format 32 -> format 33 -> format 34 -> format 35 -> format 36 -> format 37 -> format 38 -> format 39 -> format 40 -> format 41 -> format 42 -> format 43 -> format 44 -> format 45 -> format 46 -> format 47 -> format 48 -> format 49 -> format 50 -> format 51 -> format 52 -> format 53 -> format 54 -> format 55 -> format 56 -> format 57 -> format 58 -> format 59 -> format 60 -> format 61 -> format 62 -> format 63 -> format 64 (current)
```

Each step owns exactly one `N -> N+1` transformation. The runner repeatedly applies the next registered step until it
reaches the target. There is no release graph, alpha/nightly branch, path-cost planner, or second migration-history
table. A target build retains every step back to the format-2 baseline; the retired format-1-to-2 step is not bundled.

The format 2-to-3 step resets all receiver-derived activity, counters, identity caches, site observations, and quality
history instead of projecting old observations into the updated schema. Live traffic rebuilds those reproducible rows
from zero. It also creates missing canonical factory Alias Lists and their Default scan-list routing. An existing
same-family match keeps its stored spelling and administrator-owned routing; blank compatible channels use that
stored spelling. If a custom list from the source uses a factory name for a different family, the migrator moves that
custom list to a unique name such as `Default P25 (DMR)`, preserves its ID, aliases, policy, routing, and historical
references, and then creates the correct factory list. Preflight declares this possible repair; exact rename and
reference counts appear in the completion report.

The format 3-to-4 step resets all remaining receiver-derived rows and establishes separate logical-call and P25
site-observation summaries at a new collection boundary. It does not copy signaling buckets, conventional counters,
receiver contexts, identity evidence, topology, quality, or physical receiver-leg activity into the new model.

The format 4-to-5 step normalizes web accounts, password verifiers, per-user browser preferences, configurable access
overrides, and the receiver-settings revision. It gives every active saved channel one exact configuration UUID and kind
and rebuilds its deterministic database query projections from the authoritative channel document. Those old query
columns are reproducible derived state, so a stale value there is replaced rather than mistaken for administrator
configuration. The step drops and counts recognized MPT-1327 and sound-card channel rows, retired web-policy
overrides, superseded personal-setting storage, and channel documents that cannot be decoded as a supported channel.
Missing or duplicate technical channel identifiers are regenerated deterministically. Malformed legacy web access
state is reset independently, so it cannot block channel migration; setup requests a new administrator when the
primary credential cannot be retained. Valid password verifier material, roles, authentication revisions, supported
access overrides, shared receiver preferences, and active supported channels are preserved exactly or converted
deterministically. Published Alpha profiles can contain the former shared browser
presentation values without any web account because those builds did not support administrator accounts. In that
valid case the migration retains the bounded legacy values until setup creates the primary administrator, assigns the
converted preferences to that account, and only then removes the superseded storage.

The format 5-to-6 step resets receiver-derived activity instead of translating the old external RadioResolve-based
conventional owner into the saved channel's configuration UUID. This avoids rejecting an otherwise valid profile over
ambiguous, conflicting, or malformed derived identities that the later format-15 migration discards. Administrator-owned
channel configuration is unchanged, and live traffic rebuilds activity using canonical configuration UUID ownership.

The format 6-to-7 step upgrades every complete per-user browser preference document from version 1 to version 2. It
preserves existing personal settings, enables conversation grouping with a four-call burst limit, and increments each
user's preference revision. It also removes the five retired global browser-audio capacity keys from portable Java
preferences while leaving every unrelated node and value intact. An unknown, incomplete, malformed, over-capacity, or
revision-exhausted user preference document is reset to a bounded default for that user and counted without changing
the account credential. Invalid portable-preference nodes are discarded independently while valid nodes survive.

The format 7-to-8 step upgrades every exact version-2 per-user browser preference document to version 3. It adds an
empty bounded list of disabled receiver-health alert codes, so every existing and newly introduced alert remains on
unless that account explicitly turns it off. Every existing personal preference is preserved and each affected
preference revision is incremented. An unusable or revision-exhausted preference document is defaulted for only that
user and counted; it does not block other accounts or receiver configuration.

The format 8-to-9 step upgrades every exact version-3 per-user browser preference document to version 4. It adds
active-trunked-channel filtering, retain-last-call-on-idle-rows, and clear-voice-quality-when-idle choices. Filtering
defaults off. The other two choices are copied from the former receiver-wide values into every existing account, or
default false when the corresponding shared value is absent. The step then removes only those two obsolete shared
keys from portable Java preferences while preserving traffic-grant age-out, the receiver-settings revision, and every
unrelated value. Every affected user preference revision is incremented; an unusable user document or portable
preference node is defaulted or skipped independently and counted.

The format 9-to-10 step introduces optional P25 bandplan override profiles and a saved-channel opt-in setting without
rewriting existing configuration. Existing channels remain opted out because an absent setting means disabled, and no
override profile is created until an administrator saves one. Every saved channel, application setting, and received
per-site P25 band observation is preserved unchanged.

The format 10-to-11 step restores missing factory Alias Lists once, including factory lists previously deleted by an
administrator. The factory names are `Default P25`, `Default DMR`, `Default NXDN`, and `Default Analog` (AM/NBFM).
A case-insensitive `Default NBFM` list in the analog family is renamed in place only when `Default Analog` is free;
its ID, aliases, recording policy, routing, and channel assignments are retained. If both names exist, neither list
is merged or removed. Other custom names are preserved. A factory target name owned by an incompatible family is
moved to a unique custom name with its ID and saved references retained. If a channel's Alias List scalar and JSON
disagree, the JSON is repaired from the scalar used by the previous runtime.
Only newly created lists get Default scan-list routing with unmatched recording disabled. Existing lists keep their
routing and recording policy. Compatible channels with a blank Alias List selection get their factory list; existing
selections stay unchanged except references to the renamed analog list. Preflight declares the possible changes and
completion reports the affected counts. This is a semantic-only version change with unchanged DDL. Normal startup remains validation-only and does
not recreate lists deleted after this migration.

The format 11-to-12 step upgrades every exact version-4 per-user browser preference document to version 5. It adds
an independent idle FFT channel-marker switch, initially off so the existing display is unchanged. Every existing
browser preference, account, credential, role, receiver setting, and channel assignment is preserved. Each affected
preference revision is incremented; an unusable or revision-exhausted preference document is defaulted for only its
user and counted. This is a semantic-only version change with unchanged DDL and no new runtime migration path.

The format 12-to-13 step adds the one bounded `setup_wizard` progress record described below. It preserves every
existing setting and marks an upgraded profile as previously configured. Current startup checks required
administrator and web readiness independently of optional setup choices.

## Shared Upgrade And Import Boundary

The global format catalog and adjacent chain runner are the shared migration path. Alpha-specific source
classification, current-versus-Alpha state, subsystem-tuple routing, and direct legacy-to-current dispatch are not
alternate fallbacks. Retained historical transformations belong to their corresponding adjacent steps.

Graphical VCE upgrades collect the manual-backup acknowledgement and history choice before large work. A new
destination uses an explicit same-filesystem portable-folder transfer; the selected old profile is no longer an
unchanged import source after that transfer. Full-history updates reuse the adjacent chain. Settings-only updates
prepare a configuration-only working database with empty history, retaining supported configuration semantics and
the same applicable adjacent conversions without first copying or scanning the discarded history.

Headless current-profile upgrades retain their existing backup default and in-place transaction. Headless
`--upgrade-data` and retained internal copy-import/replacement operations retain source approval, private snapshots,
child-process isolation, staged validation, and atomic promotion. These ownership boundaries share one format
catalog and adjacent migration chain; they are not separate Alpha and Nightly conversion engines.

The Swing Setup Wizard owns first-run graphical presentation. Only basic selection and bounded format/configuration
checks precede the backup acknowledgement and history decision. No whole-database copy, fingerprint, physical
scan, or history count is permitted before those choices. Graphical confirmation explicitly says the previous
installation must already have been backed up; the application does not verify or create that backup. Neither
review runs a throwaway migration. The completion report describes actual repairs, resets, omissions, and skipped items.
Source selection and review share the Your settings step, with explicit review headings. Transfer selection is
available only for an empty destination. Success continues to the next needed setup step without a countdown.
Errors remain compact and inline with expandable details and a Copy error action. After promotion, Back can review
the installed source/results but cannot repeat the import. Completed profiles return to setup only for required
administrator or web readiness, vault access, data-format preparation, or interrupted first setup. Optional JMBE and
calibration work and unread release notes alone do not cause a new setup visit.

Format 13 adds only the bounded `setup_wizard` record in `application_settings`. The adjacent 12-to-13 step preserves
all existing configuration and marks existing installations previously configured; runtime readiness checks decide
whether required startup work remains. Completed profiles do not reopen setup solely for optional JMBE or calibration
work, or for unread release notes. New databases begin incomplete. Copy imports reset this record on the staged
destination before validation/promotion, without changing the selected source. The record never stores credential
drafts, hardware inventories, benchmark data, or transcripts. Its absence or malformed contents in format 13 are
validation errors, not permission for startup to repair the database.

Format 14 adds one bounded `spectrum_snap_country` record in `application_settings`. Existing profiles without a
usable selection are assigned the `US` catalog explicitly; a usable premature selection is left untouched. The step
preserves every other setting and all receiver configuration. The country is
administrator-owned configuration with one row for the lifetime of the profile. Built-in regulatory scopes and their
optional snap rules remain
code-owned rather than copied into mutable database rows, so adding or correcting a shipped country catalog does not
create append-only storage or require pruning. The website reads the selected catalog once when opening the tuner
spectrum or its administration form; both queries are primary-key lookups through `application_settings`. Missing,
malformed, or unsupported country selections in format 14 are validation errors and are never repaired at startup.

The format 14-to-15 step makes saved channel UUIDs, radio-system keys, Alias List IDs, and broadcast-provider UUIDs
the durable internal identities. It preserves administrator-owned channels, Alias Lists, stream providers, valid
routes, accounts, credentials, settings, icons, and decoder-specific channel maps stored with saved channels. Channel
rows
replace the duplicated Alias List name with a foreign key and rename the RadioResolve upload-correlation field so it
is no longer mistaken for channel or system identity. The relational row is authoritative for channel display fields,
Alias List name, RadioResolve ID, and auto-start settings; stale or missing JSON copies of those values are discarded.
Usable channel documents are retained while missing, malformed, or colliding technical UUIDs are normalized or
regenerated, channel classification is recovered from supported decoder/source content, and query projections are
rebuilt from decodable configuration. DMR and NXDN rows that predate an explicit channel type
are converted once using the exact former defaults: a DMR row is trunked only when it has a usable
channel-to-frequency map, while an NXDN row is trunked. Broadcast providers receive a canonical stable UUID; an
existing canonical unique UUID is preserved and a missing one is generated deterministically. Site-bound
Broadcastify providers keep only the Alias List ID, not a duplicate display name. Alias streaming routes are
converted from provider names to provider UUIDs, so a later provider rename cannot break routing. Unresolved or
missing legacy relationships are cleared or removed only when their referenced Alias, Alias List, scan list, or
stream provider no longer exists; preflight identifies the possible change and completion reports its exact row count.
Missing relationships are cleared, ambiguous name-based routes are dropped, safe identities are generated
deterministically, and a malformed channel or provider row is skipped without preventing independent rows from being
recovered. A skipped or defaulted component is always counted; the migrator never invents a relationship between two
ambiguous records.

The same step removes the unused legacy named Channel Maps table and reports how many of those retired rows were
dropped. These are not the decoder channel maps stored inside saved DMR or NXDN channel configuration, which remain
intact.

Usable accounts and password verifiers are preserved exactly while the stricter current tables are rebuilt. An
unusable account or credential is skipped independently; if no usable primary administrator credential survives,
setup requires a new administrator password. Exact version-5
browser preferences are upgraded to version 6 by renaming conversation grouping to target grouping and incrementing
each preference revision. The old Systems and Conventional web access choices become one Radio choice using the more
restrictive saved level. Whole-site access is renamed to Web access, and the shared site-settings revision is renamed
to receiver-settings revision without changing its value. Default public access remains implicit instead of adding
redundant policy rows.

The old receiver context, site GUID, and trunked identity scope did not have one safe meaning across conventional,
P25, DMR, and NXDN operation. The migration therefore counts and separately reports resets of receiver activity and
call history, learned site observations, signal-quality observations, and radio-system/channel identity cache rows.
Live decoder observations rebuild them under the clean model. No administrator configuration is inferred from those
derived rows. New collection boundaries are recorded, the old trunked-identity boundary is replaced by the
radio-system boundary, and redundant subsystem schema-version metadata is removed.

The rebuilt format-15 radio-system model shares only native identities that can be proven from decoded facts: P25 by
WACN and System ID, standard DMR Tier III by model (`tiny`, `small`, `large`, or `huge`) and Network ID, and NXDN
Type-C by location category (`global`, `regional`, or `local`) and System ID. Capacity Plus, Connect Plus, Capacity
Max, Hytera Tier III, unknown DMR variants, incomplete DMR/NXDN observations, and NXDN Type-D remain scoped to one
saved channel. Site, frequency, RAN, logical-channel, and timeslot facts are resource context and never system
identity.

Because this activity is derived and reset by the 14-to-15 step, old channel-scoped rows are not guessed into the new
native groups. Live observations rebuild them. After migration, a DMR or NXDN channel can temporarily collect derived
facts under a channel-scoped fallback while native identity is incomplete. If later observations establish a supported
native identity, new activity moves to that native system. Already recorded fallback history stays under its exact
saved-channel identity until ordinary retention or an explicit clear removes it; it is never relabeled as activity on
a system that had not yet been proven. Current site and radio-presence state is cleared when the assignment changes so
it cannot be presented as current on both systems. Native grouping joins observations inside the migrated receiver
profile; it does not turn these values into a worldwide identifier for comparing separate installations.

The format 15-to-16 step requires every saved channel to reference an Alias List from the decoder's compatible family.
It preserves valid assignments, selects a preferred compatible list only for an invalid assignment, and creates a
family-specific default only when no usable list exists.

The format 16-to-17 step preserves every row and adds two indexes to the existing `alias` table. One supports bounded
Alias List scans in stable ID order; the other supports case-insensitive name sorting inside one list. No activity rows,
caches, or per-call records are added. The indexes are rebuilt naturally when administrator-owned aliases change and
are removed automatically with the database; they have no separate retention policy.

The format 22-to-23 step upgrades every exact version-6 per-user browser preference document to version 7. It adds
one bounded, sparse `collapsed_groups` list to each saved table layout and leaves it empty so every row group remains
expanded until that user collapses it. It preserves accounts, credentials, access policy, receiver settings, table
column order, visibility, and widths. Each usable preference revision is incremented. Only a malformed, oversized, or
revision-exhausted user's preference document is replaced with version-7 defaults. This semantic-only format shares
the format-22 DDL fingerprint, so the authoritative global marker is required when row invariants cannot distinguish
an otherwise markerless database.

The format 23-to-24 step preserves every row and adds six reproducible indexes for retained Activity filtering. The
radio-system and saved-channel indexes lead with their owner and then the selected action or event type, followed by
descending observation time and event ID so a bounded newest-first page does not scan unrelated retained history or
sort into a temporary B-tree. Two additional saved-channel indexes serve exact conventional-digital source and target
IDs in the same newest-first order. Radio-system indexes exclude channel-only events, event-type indexes exclude rows
whose decoder event type is unknown, and raw-ID indexes exclude rows without that role. Existing retention, pruning,
identity, encryption, and administrator-owned configuration are unchanged.

The format 24-to-25 step introduces per-channel opt-in suppression of encrypted P25, DMR, and NXDN traffic-channel
allocation without rewriting usable existing configuration. Every usable database row and saved channel JSON document
is preserved unchanged. Because formats 24 and 25 share the current row layout, when format 24 is the selected source
the step also applies the same bounded component repair policy that a damaged format-24 database received while it was
current; every repair, default, reset, or skipped row is reported exactly before the strict format-25 stamp. An absent
`ignoreEncryptedCalls` decoder setting means disabled, so existing P25 and DMR channels retain their prior behavior. An
explicit existing NXDN selection is preserved. Runtime constraints do not rewrite the administrator's saved
preference. Formats 24 and 25 share one DDL
fingerprint and row layout, so a markerless file is ambiguous and requires its authoritative global format marker.

The format 25-to-26 step preserves every detailed Activity event and event ID, every attached identity-member
relationship, and every compact conventional Activity summary. It rebuilds only the two tables whose event-type
checks change, then restores the complete format-24 index set and resolved Activity view. The expanded checks and view
retain radio inhibit, radio uninhibit, and their acknowledgements as four exact stable event types. Existing retention,
pruning, identity, encryption, and administrator-owned configuration are unchanged, and historical rows are not
reclassified. When format 25 is the directly selected source, the step first applies the same bounded repairs used for
recoverable current-format preference, administrative, receiver-derived, and configuration damage. An intermediate
format 25 produced by the preceding step does not repeat those repairs.

The format 26-to-27 step makes the fixed primary administrator the only ADMIN-tier account. Each usable ordinary
ADMIN account becomes a USER account. Its ID, normalized username, exact password verifier, password-change time,
authentication revision, preference document, preference revision, creation time, and update time are preserved, as
is the `web_user` allocator high-water mark. No account is renamed or deleted and no revision is incremented because
the transformation happens offline, before the migrated database can issue a session. The current schema and runtime
reject any future ordinary ADMIN account. When format 26 is the directly selected source, this step first applies the
same bounded repairs used for recoverable current-format preference, administrative, receiver-derived, and
configuration damage. An intermediate format 26 produced by the preceding step does not repeat those repairs.

The format 27-to-28 step establishes the saved meaning of a remote P25 trunked source. It preserves every usable
saved channel and application setting unchanged, and it never guesses a remote assignment for an existing local
channel. A newly selected remote source stores only the stable sender UUID, stable feed UUID, and RF frequency needed
to find the same feed after a reconnect. Connection, session, stream, and generation identifiers remain transient
transport state and are not saved. This semantic-only format shares the format-27 DDL fingerprint, so a markerless
format-27/28 file is ambiguous and requires its authoritative global format marker. When format 27 is the directly
selected source, the step first applies the bounded current-component repair policy; an intermediate format 27 does
not repeat those repairs.

The format 28-to-29 step adds a dedicated Recordings choice to the bounded setup progress record. Existing Classic or
Managed recording preferences and the previous step states are preserved. The new step is marked carried over for
existing profiles, so migration never changes where their next call is saved. New profiles begin with the step pending
and the wizard recommends Managed Recordings. A missing or malformed legacy progress record is replaced with a bounded
review state and counted; independent usable settings remain. Format 29 uses the same DDL fingerprint as format 28,
so a markerless file in this shared-layout family still requires its authoritative global format marker. When format
28 is the directly selected source, the step also applies bounded current-component repair before its final stamp;
an intermediate format 28 does not repeat that repair.

The format 29-to-30 step adds sparse encrypted-only Activity indexes ordered by saved-channel or radio-system owner,
observation time, and event ID. This lets encrypted Activity pages seek directly through the comparatively small set
of encrypted rows while preserving every retained event and identity relationship unchanged. Clear and unfiltered
queries keep their existing general-purpose indexes; format 30 deliberately does not duplicate those much larger
index populations. When format 29 is the directly selected source, the step first applies the bounded
current-component repair policy; an intermediate format 29 does not repeat that repair.

The format 30-to-31 step upgrades every exact version-7 per-user browser preference document to version 8. It adds
an explicit nullable `appearance.hue`: null selects the original shared palette, while an integer from 0 through 359
selects a custom brand hue. Existing light/dark selections, personal settings, table layouts, accounts, credentials,
and receiver configuration are preserved, and each usable preference revision is incremented. Green success/status
colors and categorical chart colors keep their existing meaning. Only a malformed or oversized preference document
is replaced with bounded version-8 defaults; an unusable or exhausted revision is rebased while usable personal
settings survive. When format 30 is the directly selected source, the step applies the bounded current-component
repair policy; an intermediate format 30 does not repeat those
repairs. If adding the hue to a usable document would exceed the unchanged storage bound, remove only the necessary
saved table layouts and report their exact count. Accounts, credentials, non-layout personal settings, and receiver
configuration remain intact. Format 31 shares the format-30 DDL fingerprint and uses the explicit
preference generation to distinguish populated markerless profiles; an ambiguous markerless file requires its
authoritative global format marker.

The format 31-to-32 step adds canonical P25 subscriber identities, best-effort serving-system WUID observations, and
an explicit canonical-subscriber Alias matcher. It preserves every existing Alias, including `RADIO_ID` matchers,
without converting or relabeling it. The canonical Alias association table and the WUID observation-summary table
start empty. Existing affiliation, presence, event, and call rows are not mined for
assignments, and even an already stored
full radio tuple is left unassociated rather than treated as proof of a historical WUID mapping. The existing
radio-system identity directory is preserved exactly and gains only a null canonical-subscriber reference. Because
that null means the old row is not proven canonical, website routes present a legacy P25 radio as a serving-system
local identity until new qualified evidence links it to a canonical subscriber. New qualified observations populate
that reference after upgrade. This makes the upgrade deterministic and avoids
inventing permanent identity relationships from derived history.

The format 32-to-33 step preserves every row and adds one partial covering index for historical source Working-ID
alias evidence. The index includes only detailed events with a source identity and a positive observed working
address. Canonical P25 radio alias enrichment can seek those observations directly without reading historical
local-only events that cannot supply its address. Ordinary local-address lookups, target evidence, compact
summaries, activity ordering, and retention keep their existing paths. The step reports one added index and resets
or drops no data. Earlier migration steps retain their historical lookup without requiring the new index.

The format 33-to-34 step is a prospective receiver-accounting semantic update with unchanged DDL. It preserves all
configuration and retained history. New observations retain their message-defined radio/group types, conventional
calls can gain late identity attribution without another call count, and cached site facts no longer gain fresh
reception counts merely through a new snapshot. Database-generated references retain their full SQLite width.
Historical conventional attribution and site reception times cannot be reconstructed exactly from the retained
aggregates, so this migration neither fabricates corrections nor resets those rows. Historical site counts retain
their earlier snapshot-update meaning. Future drop causes are classified; old cumulative drops remain unclassified.

The format 34-to-35 step upgrades every exact version-8 per-user browser preference document to version 9. It adds
`presentation.source_name_display`, selecting Talker Alias preferred (`talker_alias`) for existing accounts. Users can
instead prefer Source Alias (`source_alias`) or show both distinct names (`both`). The display falls back to the other
available name when the preferred one is absent. Every usable personal setting, account, credential, receiver setting,
and retained activity row is preserved; each usable preference revision is incremented. Only malformed or oversized
preference documents are defaulted for the affected user. Invalid or exhausted revisions are rebased without losing
usable personal settings. If the added choice exceeds the unchanged storage limit, remove only enough cached table
layouts to fit and report their exact count. Direct format-34 upgrades apply the bounded current-component repair
policy; an intermediate format 34 does not repeat those repairs. The DDL is unchanged and this shared semantic
format family still requires its authoritative global marker. Historical version-8 preference validation remains frozen.

The format 35-to-36 step upgrades exact version-9 per-user browser preference documents to version 10. It adds
`presentation.live_channel_sort`, initially `order_appeared` when the account shows only active trunked channels
and `lcn` when idle rows are shown. Each user can subsequently save `lcn`, `order_appeared`, or `frequency`
independently of that filter. Existing usable personal settings, including the source-name display choice, accounts,
credentials, receiver configuration, and retained history are preserved. Each preference revision is incremented;
an unusable or exhausted revision is rebased while usable settings survive. Only malformed or oversized documents
are defaulted. If the added field would exceed the unchanged storage bound, only necessary cached table layouts
are removed and their count is reported. Direct format-35 sources receive bounded current-component repairs;
intermediate format-35 sources do not repeat them. Format 36 shares the format-33/34/35 DDL fingerprint, whose
earlier receiver semantics cannot be distinguished from retained rows. A markerless file in this shared-layout
family requires its authoritative global format marker. Historical version-9 preference validation remains frozen.

The format 36-to-37 step preserves every row and adds one identity-first lookup index to logical-call
identity buckets, with statistics for SQLite's query planner. Routine retention and foreign-key checks can seek an
exact radio-system identity rather than scan all of its system's retained call history. No settings, preference
revisions, accounts, credentials, counters, or retained observations are rewritten. The bundled Application Migrator
adds the index transactionally; runtime startup continues to validate the existing schema without modifying it.

The format 37-to-38 step preserves every row, rebuilds four existing Activity lookup indexes as covering indexes,
and adds one narrow event/channel index. Source and target identity/time lookups retain their partial predicates and
place an explicit event ID immediately after observation time, preserving the previous implicit-row-ID tie order.
Saved-channel/action/time retains descending observation time and event ID. The identity-member lookup retains its
identity/event prefix and adds the observed address. The new event/channel index supplies the saved channel without
fetching each wide event-table row during historical member Alias evidence. The unique event/system parent key and
all foreign keys remain unchanged. The separate sparse source Working-ID evidence index also remains unchanged.
This adds no retained events, member relationships, summaries, or configuration fields. Preflight and completion
report exactly four rebuilt indexes and one added index, with no reset or drop of data; every administrator setting,
credential, personal preference, event ID, identity relationship, and allocator high-water mark is preserved. All
indexes follow existing event retention and deletion. Their larger entries and the narrow event projection add
storage and background write cost, as documented in the SQLite Activity Database Guidelines. Only the bundled
Application Migrator may change them; historical format creators keep their frozen definitions and normal startup
remains validation-only.

The format 38-to-39 step copies each identity member's immutable observation channel from its exact parent event,
without adding or dropping any event or membership. The existing member key and owner/kind foreign keys remain;
a new `(event_id, channel_id)` foreign key uses the existing event/channel index promoted to a unique parent key.
The current writers already know that channel and store it without an additional lookup. Preflight refuses members
with a missing or mismatched parent owner. The backfill preserves every old field and key, verifies the member
count, and final validation checks retained relationships.
The Activity identity/event index remains unchanged so patch-member paging retains event-ID order.

Two additional covering indexes serve separate queries: member `(identity_summary_id, channel_id,
observed_local_id)` skips duplicate channel/address evidence without parent-event joins; positive compact-call
`(identity_summary_id, observed_local_id, channel_id)` supports exact/range address seeks during configured-name
search. The member lookup keeps an explicit DISTINCT subquery before the outer UNION, preserving SQLite's indexed
duplicate skipping. Both indexes follow the existing retention and cascade paths and create no additional history.
All administrator settings, credentials, personal preferences and allocator high-water marks are preserved.
The added storage and background write cost are measured separately in the SQLite Activity Database Guidelines.
Format 38 remains a frozen supported source; only the bundled adjacent Application Migrator applies format 39,
and normal startup still refuses older formats without changing them.

The format 39-to-40 step adds two partial source/target local-address covering indexes. Each leads with the
identity summary and positive observed local address, then the saved channel. This lets scoped configured-name
search seek exact addresses or inclusive ranges and skips null, zero and unattributed evidence. Existing time/ID
indexes, table definitions, retained rows, relationships, settings, credentials, preferences and allocators remain
unchanged. The adjacent migration reports exactly two added indexes, retains frozen format 39 as a supported
source, and applies only through the bundled Application Migrator. The indexes follow existing event retention;
their measured storage and write costs are documented in the SQLite Activity Database Guidelines.

The format 40-to-41 step adds exactly two partial Activity lookup indexes while retaining every existing index
and table definition. The target/event-type/time cover seeks one target identity and event type in newest time/ID
order; the saved-channel/frequency/time cover seeks one channel and frequency while covering optional LCN,
timeslot, system and action filters. They serve existing detailed Activity rows without changing query predicates,
collection, cursor ordering or retention. Each index has at most one entry per qualifying retained event: a known
target identity for the first, and a known frequency for the second. Existing retention, explicit clears and event
deletion remove entries automatically.

The adjacent migration preserves all retained rows, IDs, relationships, allocator high-water marks, settings,
credentials and personal preferences, and reports two added indexes with no rebuild, reset or drop. Formats 38,
39 and 40 keep their frozen DDL and remain supported sources. Only fresh current creation and the bundled
Application Migrator apply the new definitions; normal startup remains validation-only. The separate bounded
storage and background write measurements are documented in the SQLite Activity Database Guidelines.

The format 41-to-42 step rebuilds exactly one existing target identity/time index, appending `radio_system_id`
after its existing seven columns. It covers SQLite's complete target identity foreign-key check so identity cleanup
can seek a target and check its system and kind without fetching unrelated event rows. The partial predicate,
time/ID order, index count, foreign keys, retention rules and every retained row remain unchanged. No history,
counter or configuration reset occurs. The adjacent Application Migrator preserves IDs, relationships, allocator
high-water marks, credentials and preferences; interruption rolls back the replacement and permits retry.
Formats 38 through 41 retain their exact frozen definitions, and normal startup refuses format 41 without changing
it. Fresh format 42 and migrated format 42 have the same schema. The added integer payload and background write
cost are described in the SQLite Activity Database Guidelines.

The format 42-to-43 step upgrades exact version-10 per-user browser preferences to version 11. It adds
`presentation.live_row_density`, initially `normal` for every existing account. Users can save `normal` or `dense`
independently of their other Live presentation settings. Each usable preference revision is incremented, while an
invalid or exhausted revision is rebased without losing usable settings. All other personal choices, table layouts,
accounts, credentials, receiver configuration, and retained history are preserved. Only malformed or oversized
preference documents are defaulted for the affected user. If adding the choice exceeds the unchanged storage bound,
only enough cached table layouts are removed to fit, with their exact count reported. Directly selected format-42
sources receive the existing bounded component repairs; intermediate format-42 sources do not repeat them.

This semantic-only step leaves every table and index definition unchanged. Formats 42 and 43 share a DDL fingerprint,
so markerless files require an authoritative global format marker when their retained values cannot distinguish them.
Version-10 preference validation and defaults remain frozen for older supported formats. Only the bundled adjacent
Application Migrator applies the upgrade; normal startup validates and never rewrites older preferences or formats.

The format 43-to-44 step widens the Alias List name constraint from 25 to 128 characters by replacing only that
parent table inside the migrator's existing transaction. Every usable existing name, ID, Alias, channel assignment,
recording and streaming policy, scan-list route, retained row, credential, personal preference and allocator
high-water mark is preserved. Existing names are neither expanded nor renamed, and no history is reset or dropped.
Directly selected format-43 sources receive the existing bounded configuration repair policy, with exact repair
counts reported. Intermediate sources retain independently repairable values until the runner's shared final
component repair and validation; they do not repeat prior component repair. All historical format creators retain
their exact 25-character definition. Only the bundled adjacent Application Migrator changes an existing table;
normal startup refuses format 43 without mutation, and fresh and migrated format-44 schemas match exactly.

The format 44-to-45 step consolidates home-system P25 radio identities. A number-only observation is keyed within
its receiving WACN and System ID. A fully qualified radio with that same home system and subscriber number uses the
same summary owner; only qualified evidence attaches the normalized subscriber reference. Different home systems
and different subscriber numbers keep their own identities. The step rebuilds the existing resolved Activity view
to expose the stored owner key consistently; it adds no table, column, index or relationship layer.

The migration moves safely scoped older unresolved rows to that receiving-system key and physically consolidates
matching duplicate summaries. It preserves detailed source and target Activity, logical-call and site buckets,
talkgroup relationships, current state, names and allocator high-water marks. Colliding aggregate keys combine their
previously accepted credits, and the latest state is retained before the duplicate is removed. Historical aggregates
do not retain individual logical-call keys, so overlapping old source/target credits cannot be reconstructed; system
and site totals remain unchanged. Future calls credit the shared owner once. Rows with conflicting retained foreign Working-ID
evidence remain unresolved, with their count reported; the migration does not assign ambiguous old history to a
subscriber. New observations use the receiving-home owner, while retained unresolved history keeps its distinct
local key and remains accessible without borrowing the new owner's identity or name.
Preflight declares the policy without scanning history; the transaction reports exact rekey, merge and unresolved
counts. Failure rolls back the step and permits retry.

All ID writers use the shared ownership rule, and Activity coalescing preserves new source and target identity
facts. Directory, talkgroup, Activity, recording-label and deletion paths resolve the same owner, including older
local links after consolidation. Ordinary home-system labels omit redundant fully qualified context; differing home
systems or observed Working IDs retain their distinguishing details. The resolved-view change gives format 45 its
own exact DDL fingerprint. Historical format 44 remains frozen and startup continues to validate without migration.

The format 45-to-46 step establishes optional receiver-confirmed DMR and NXDN discovery identity inside the
existing saved-channel document. Existing documents remain unchanged, with absent identity treated as unknown.
The migrator does not backfill identity from activity history. Newly discovered channels retain their verified native
system and site identity alongside their configuration so restart, history retention and activity clearing cannot
remove that identity. Receiving-setting edits and copied channels cannot inherit stale confirmation. No table, index,
relationship or retained row is added by this semantic step. Formats 45 and 46 share the exact DDL fingerprint and
require the authoritative global marker; the prior populated format-45 fixture remains supported.

The format 46-to-47 step adds receiver-owned alert topics and rules, anonymous devices and topic memberships,
temporary enrollment sessions, bounded alert history, and a durable delivery outbox. Eight explicit tables keep
history and delivery lifecycle separate from administrator settings. Rules retain bounded typed JSON; the existing
application-settings row retains the configuration revision and push method. Alert messages snapshot the topic
name, rule name, title and body independently, so renaming or deleting a rule does not rewrite past notifications.
Delivery rows snapshot relay recipient IDs so retries reuse one event and a stable recipient list.
Expired push subscriptions retain an explicit device expiry flag independently of guest ownership and preferences;
fresh subscription material reactivates the same managed device. Existing channels,
aliases, credentials, preferences and receiver history are preserved without deriving subscriptions from them.
Notification delivery starts disabled, and the built-in Administrator alerts topic starts hidden. Fresh creation uses
the same definitions and defaults; runtime startup validates only. The populated format-46 fixture and adjacent
migration cover preservation, rollback, retry, the exact registered schema fingerprint and SQLite integrity.

The format 47-to-48 step retires personal notification pause and quiet-hour settings. It removes their eight
columns from devices and enrollment sessions, including saved time zones used only by those personal preferences.
Existing management ownership, topic choices, pairing state, subscription expiry, notification history and delivery
snapshots remain intact. Existing subscribed devices receive normally after upgrade; unsubscribing, disconnecting,
subscription expiry, and administrator topic visibility or availability still control delivery. The migration counts
subscriptions and pairing sessions with non-default retired preferences. Custom rule schedules keep their time zones.
Rules can now select a source protocol family independently of an exact decoded event protocol; existing rules have
no family restriction. Format 47 retains its frozen creator and populated fixture. The adjacent Application Migrator
removes only the retired fields, and fresh and migrated format-48 schemas match exactly.

The format 48-to-49 step separates optional alert popup actions from notification-tap behavior. It appends an
explicit action kind and nullable canonical entity-reference JSON to the existing bounded alert-message table.
Existing notification taps, text, names, IDs, delivery snapshots, guest ownership, topic choices and rule schedules
are preserved. Existing Listen popup buttons become `live` actions; other old alerts have no action. Historical
channel, radio, talkgroup and system destinations cannot be recovered reliably and are never guessed from names,
current receiver assignments or unrelated activity history.

Absent rule action-button flags are filled from their former Listen setting. Existing valid boolean flags remain
unchanged, including an explicit disabled choice on a rule whose notification tap still opens Listen. The built-in
alert action-button choice starts disabled when absent; a valid premature choice is preserved. Malformed premature
flags or rule documents that cannot fit the additional setting are refused with a clear reason before schema changes.
The migration reports the exact numbers of defaulted rule flags, preserved Listen popup actions and newly defaulted
built-in choices. Fresh creation uses the same clean fields, while normal startup only validates the explicit settings.
Format 48 keeps its exact populated fixture and frozen schema/invariants; rollback and retry reach the same registered
format-49 fingerprint without rewriting any original history or notification-tap field.

The historical format 49-to-50 step added prospective structured tone evidence to optional individual Activity history. It
appends the stable `TONE` action and event categories and adds one tone-context row plus at most 32 ordered tone
facts per accepted completed physical call leg. Frequencies are stored as integer millihertz, separate from the RF
carrier frequency. Source and method distinguish encoded AMBE symbols from decoded-audio detection; unknown
frequencies, offsets and confidence stay unknown. These rows do not credit call, signaling, presence or Alias
counters. The step preserves every old row, ID, relationship, counter, setting, credential, Activity index, planner
statistic and event allocator. It infers no historical tones. Format 49 keeps its frozen signature and populated
fixture; only the bundled adjacent Application Migrator rebuilds the category constraints and creates the two empty
tables. Fresh and migrated format-50 databases share one exact signature. Normal startup refuses an older or mixed
layout without applying this upgrade.

The format 50-to-51 step enables prospective AM and NBFM talkgroup and range Alias statistics. It preserves existing
digital Alias counters, configured entities, retained history, relationships, and allocators. Previously unsupported
analog Alias rows become eligible without assigning any historical calls to them. A new compact summary records
received configured numbers by Alias List, saved channel, protocol, and assigned number; it starts empty. Explicit
Alias and channel reset timestamps prevent delayed observations from restoring cleared analog statistics. The
format-50 schema and fixture remain frozen. Fresh and migrated format-51 databases share the same exact signature;
normal receiver startup only validates that format.

The format 51-to-52 step admits an explicit nominal-frequency PCM tone method. New decoded-audio tone reports use
only predefined AMBE single-tone frequencies; encoded tone metadata keeps its existing identity. Existing measured
and encoded tone rows, sequence order, timing, events, counters, settings and allocators remain unchanged. The
adjacent Application Migrator updates only the tone-context method constraints. The populated format-51 fixture and
its exact signature remain supported, and fresh and migrated format-52 databases share the same target signature.

The format 52-to-53 step makes Remote Links connection and pairing settings, paging-tone detection, and voice
enhancement required settings in the existing `application_settings` table. The distinct keys are `remote.links`,
`audio.paging_tones`, and `audio.voice_enhancement`. Existing valid values, credentials, and revisions are preserved.
Absent records are created explicitly with Remote Links disabled, paging-tone detection enabled, and voice
enhancement disabled; the plan and completion report count these defaults. Malformed existing values refuse the
update without exposing their contents. Every other setting, retained observation, relationship, and allocator
remains unchanged.

Fresh format-53 creation installs the same records, and normal startup validates them without creating or repairing
missing values. Folder imports and the retained internal SQLite-file migration and replacement engine carry these
settings with the database contents. The table layout is unchanged, so the authoritative global marker distinguishes the semantic
formats. Format 52 keeps its frozen populated fixture and signature; the adjacent migration, rollback, and retry
checks reach the same exact format-53 contract.

The format 53-to-54 step versions the profile-scoped import of the three configuration files converted to SQLite:
`remote-links/settings.json`, `audio-analysis/paging-tones.json`, and `audio-models/selection.json`. For an owned
profile update or explicitly selected complete folder whose source format is 52 or earlier, the Application Migrator
captures bounded validated values and imports only absent database settings before the 52-to-53 step creates its
remaining defaults. Existing SQLite values always take precedence. Voice-enhancement version-1 and version-2 files
contribute only their enabled choice; the selected JMBE library owns the voice model.

Format-53 or later profiles retain every existing database value automatically. Recovery from a retained legacy file
requires an explicit selection and a reviewed expected default value; the same writer transaction refuses a changed
or non-default database value. This also permits explicit recovery after an ordinary update has already reached
format 54. No runtime service imports files or repairs settings at startup. Fresh creation uses the existing typed
SQLite defaults, and the table layout is unchanged. The authoritative format marker distinguishes this semantic
contract; populated format-53 fixtures remain frozen and supported.

Folder approval binds the three eligible files, including their absence, to immutable validated input. Import refuses
a source changed after review, and the bundled child reads only private staged copies. Internal SQLite-file migration
and replacement never read the source or destination's neighboring files. Malformed, oversized, symbolic-linked, or
unsupported eligible files refuse safely without displaying their contents or credentials. Original files are never
rewritten, removed, or permission-adjusted. Completion reports imported and explicitly recovered setting counts.
Upload-queue manifests, backup receipts, recordings, bundled resources, and diagnostic JSON are not these converted
configuration files and retain their existing ownership and lifecycle.

The format 54-to-55 step admits the analog PCM tone method while preserving every existing setting, Activity row,
tone fact, classification, counter and allocator. New results annotate the exact existing call row instead of creating
a standalone `TONE` row; historical standalone rows remain intact with no guessed reassociation or backfill. The
shared process-local token is only a bounded runtime receipt and adds no persisted call registry. P25 Phase 1 remains
single-tone PCM; AM/NBFM PCM can contain nominal single tones and waveform DTMF with explicit symbols and frequency
pairs. Existing encoded digital metadata is preserved. CTCSS is a separate analog squelch choice, defaults disabled
when absent, and never becomes tone evidence. The adjacent migrator changes only the tone-context method
constraints. Fresh creation and migration reach the same format-55 signature; normal startup validates only.

The format 55-to-56 step retires the application-managed voice-enhancement and continuous-gain choices. It removes
only `audio.voice_enhancement` from `application_settings` and `audio.output.continuous.agc.enabled` from the exact
`user/io/github/dsheirer/preference/audio` node of `portable_java_preferences_v1`. The plan declares both retirements;
the completion report counts the setting row and preference actually removed. If that preference was the node's
last value, the empty node is removed; all other portable values and their stored timestamp remain unchanged.
Existing bounded component repair remains responsible for independently unreadable portable settings.

Remote Links, paging-tone detection, MP3 normalization, recording and streaming settings, credentials, retained
Activity, tone facts, relationships and allocators are preserved. Current fresh databases create only the active
Remote Links and paging-tone rows. Historical formats 53 through 55 keep their frozen three-row validation and
migration contracts. The table layout is unchanged; the global format marker identifies the new retirement contract.
Normal startup validates the absence of retired options and never deletes or repairs them. Current folder imports
and recovery no longer inspect unused `audio-models/selection.json` files; this does not alter the earlier historical
migration policies. No receiver audio, installed JMBE library or bundled model is changed by the database update.

The format 56-to-57 step extends the admitted nominal single-tone frequencies for prospective analog PCM results
with QCII and Plectron paging frequencies. Method 4 still means analog PCM nominal single tones or waveform DTMF;
its existing frequency fields, ordering and provenance remain unchanged. P25 Phase 1 method 3 and encoded digital
metadata keep their existing frequency identities. No stored tone is renormalized, relabeled or backfilled, and every
setting, retained row, counter, relationship and allocator is preserved. This semantic-only step keeps the exact DDL
fingerprint; the new global marker prevents an older reader from accepting frequencies it cannot interpret. Normal
startup validates only, and the populated format-56 fixture remains frozen and supported.

The format 57-to-58 step adds one optional `source_call_uuid` to each existing detailed Activity event and alert
message, plus two sparse indexes for exact lookups. The reference is a canonical lowercase UUID identifying the
original received call across its independently stored Activity, alerts and recordings. It contains no audio,
message text, radio identity or timestamp. Existing rows remain NULL: the migration neither matches old timestamps
nor infers connections from talkgroups, channels or frequencies. Every old row, ID, setting, delivery state,
relationship, counter and allocator is preserved. Each dataset keeps its own retention, and no new main-database
table, cross-database foreign key or pending-link queue is introduced. Fresh creation and migration reach the same
exact format-58 signature; format 57 retains its populated fixture and runtime startup remains validation-only.

The format 58-to-59 step adds nullable integer `duration_ms` to existing detailed Activity call rows and exposes it
through the resolved Activity view. It records the shared accepted call's observed span, independently of recording
or streaming policy and audio-file length. Unknown and historical lengths remain NULL; the migration does not
reconstruct timing from recordings, adjacent events or timestamps in other tables. Completion enriches only the
same accepted call row through its existing exact call binding or source reference, without adding another call or
changing its identity. Non-call rows cannot carry duration, and known values are nonnegative integer milliseconds.
Every old row, ID, reference, relationship, setting, counter and allocator is preserved. No table or index is added;
existing history retention and exact-call lookup bounds remain unchanged. Fresh creation and the adjacent migration
reach the same exact format-59 signature. The populated format-58 fixture remains frozen and supported, and normal
runtime startup validates without migrating.

The format 59-to-60 step adds one bounded `activity_message_type` catalogue for shared message labels,
meanings and implemented P25, DMR and NXDN support. Existing Activity rows keep their action and decoder-event
codes in separate namespaces; the resolved view reads their names from this same catalogue. Prospective control
messages gain precise command or response types, and the existing signaling buckets and conventional summaries
gain zero-initialized counters. Every old row, ID, setting, counter, relationship, exact call reference and allocator
is preserved. Historical generic commands and responses remain unknown rather than receiving guessed subtypes.
Fresh creation and the adjacent Application Migrator reach the same exact format-60 signature. The populated
format-59 fixture remains frozen and supported; normal startup validates without rewriting definitions or history.

The format 60-to-61 step adds `web_user_tour_dismissal`, with one highest dismissed version for each account and
guide ID. Accounts, password verifiers, personal preference revisions, receiver settings, retained observations,
relationships and allocator high-water marks remain unchanged. The table starts empty because the database cannot
infer a browser's existing dismissals. A signed-in browser can subsequently merge its own previous local history.
Upgrades and password recovery preserve this account-owned history; deleting an account cascades to its rows.

The authenticated `/api/v1/me/tour-dismissals` endpoint reads only the current account and accepts additive updates.
Concurrent browser updates retain the union of guide IDs and the highest version for each ID, independently of
personal preference revisions. Each account retains at most 64 guides, with bounded lowercase stable IDs and
positive guide versions up to 1,000,000. The primary key supports bounded account reads and account-deletion cleanup;
no call, Activity or time-based retention process prunes these choices. Fresh creation and the adjacent Application
Migrator reach the same exact format-61 signature. Format 60 retains its populated fixture, and normal startup
continues to validate without creating or repairing the table.

The format 61-to-62 step upgrades exact version-11 per-user browser preferences to version 12. It adds the
explicit boolean `guides.automatic_page_guides`, enabled by default. A signed-in user can turn off automatic page
tours while retaining the help button for manually opening a guide. The choice belongs to that account and is shared
across browsers through the existing authenticated, revisioned `/api/v1/me/preferences` GET and PUT contract.
Missing, extra, duplicate, null, or non-boolean guide fields are refused at that boundary.

The adjacent conversion preserves every usable non-layout preference, valid account timestamps, credentials,
authentication revisions, tour dismissal history, receiver setting, retained row, relationship and allocator. Each
personal preference revision advances once so a stale browser cannot overwrite the upgraded document. Existing
bounded component repair defaults only unusable preference documents or revisions and reports the affected counts.
If the additional choice exceeds the unchanged 131,072-byte document bound, only the minimum necessary cached table
layouts are removed and counted; all non-layout choices remain intact. No table or index changes. Format 61 retains
its frozen populated fixture and version-11 validator; fresh creation and migration reach the same exact format-62
signature. Normal runtime startup validates the current contract without converting or repairing stored preferences.

## Configuration and receiver activity (format 63)

The application profile now has two matching SQLite files under `data/database`: `sdrtrunk.sqlite` owns saved
configuration and `activity.sqlite` owns receiver Activity, observations, learned sites, statistics, and receiver-health
history. Both remain inside the normal portable data folder. Copy, restore, or import both matching files together;
a missing companion or files from different profiles are refused. The separate Managed Recordings catalog and audio
retain their existing ownership. Account guide dismissals and the bounded notification service remain with their
configuration; they do not become receiver Activity.

Removing a saved channel or Alias no longer removes its receiver Activity. Existing historical owners retain the
last captured channel, Alias, and Alias List labels, so their observations remain understandable after configuration
is gone. Scheduled retention removes expired observations, then unused historical owners and detached compact
summaries. Active channels and Aliases keep their lifetime totals. Explicit Activity, statistics, and recording deletion
actions still perform their requested cleanup. Recording expiry remains governed by recording retention when enabled.

The adjacent 62-to-63 update preserves every usable configuration component and retained observation, counter,
relationship, identity, and allocator. The original large database becomes the activity file; the update does not copy
all detailed history into another database. It commits and checks a small extracted configuration database first, then
converts the original file in its existing migration transaction. Only after both roles are complete does the migrator
activate their final filenames. A private activation receipt lets the Application Migrator finish an interrupted
activation; normal startup refuses an incomplete transition and never creates an empty replacement history file.
The optional recovery snapshot retains its previous behavior. Earlier formats continue through every adjacent step.

Canonical P25 subscriber identities have independent dictionaries in the two stores. A cross-store match uses the
complete home WACN, home system ID, and subscriber ID tuple; an integer key from one dictionary never identifies a
row in the other. Each configured Alias also has an activity identity and optional reset time. Renaming or routing an
Alias keeps these values and its counters. Changing its matcher starts a new activity identity, preventing an old
counter from appearing under a new matcher while preserving old history for scheduled expiry. The existing
changed-row persistence, no-op saves, unaffected live Alias objects, and affected-list-only lookup rebuilding remain.

Historical channel ownership has no foreign key into configuration. Its own context holds the last observed display
fields, Alias List reference/name, frequency, and analog assigned number. Explicit channel statistics reset boundaries live in their own small history table, so clearing statistics never invents a received channel observation. Historical Alias summaries
hold their Alias activity identity and labels. Detailed events are unchanged; context is captured once per owner,
rather than repeated in every event. The resolved Activity view reads this historical context without requiring a
live configuration row. Configuration requests never wait for the history writer; history reads use a read-only
configuration attachment where current names or filters are needed.

The adjacent 63-to-64 step adds the bounded `receiver.nicknames` setting in configuration. It maps each existing
opaque receiver identifier to an optional administrator-chosen nickname; an empty nickname removes that mapping.
The map starts empty, and usable premature names are preserved rather than inferred from hardware labels. Tuner
configuration saves and reconnections do not overwrite nicknames. Every other setting, retained row, relationship,
counter and allocator is preserved. Invalid existing nickname settings refuse the update without exposing their contents.

Format 64 changes no table or index. The unchanged activity role retains its exact format-63 schema and semantics;
an existing paired activity file needs no write merely to upgrade configuration. When an older monolith traverses both
steps in one transaction, its activity marker may advance to 64 with the same unchanged role contract. Pair admission
accepts only these explicit role versions with matching profile identities. The frozen format-63 fixture, adjacent
migration, unchanged-source retry and exact-signature checks remain required; normal startup validates without repair.

## Schema-Change Rule

The optional Managed Recordings catalog is a separate SQLite file with its own adjacent format chain. Its format 2
adds a one-to-one `recording_transcript` table containing transcript text and its storage time in Unix epoch
milliseconds. The format-1-to-2 Application Migrator step preserves all recording rows, IDs, paths, metadata, and
counters, and adds no transcript rows. Catalog format 3 adds a constrained `transcription_status` to each indexed call
(`pending`, `complete`, or `failed`) and a pending-call lookup index. The format-2-to-3 step preserves every call and
transcript, marking calls with an existing transcript `complete` and other calls `pending`. Short calls stay pending;
the background worker applies its configured minimum duration when selecting work. A failed call is retried only by
an administrator action. Fresh format-3 catalogs use the same status default and index. Catalog format 4 adds a typed complete-call tone
context and at most 32 ordered tone facts for the recorded native-audio winner. Its adjacent format-3-to-4 step
preserves every recording, ID, path, access code, transcript, transcription status, relationship, counter and
allocator, adding no historical tone rows. The exact format-3 signature remains frozen independently of the current
creator; fresh and migrated format-4 catalogs share the same authoritative definitions. Catalog format 5 admits the
same new nominal-frequency PCM method, preserving every existing recording and tone fact through its adjacent
format-4-to-5 step. The format-4 signature remains frozen and the upgrade neither converts old measured frequencies
nor backfills tones. Catalog format 6 adds ordered lookup indexes for complete source and destination radio identities,
patch-member identities, legacy system contexts, protocol, call type, and voice type. Its adjacent format-5-to-6 step preserves all recording
rows, audio paths, transcripts, tone facts, relationships, counters, and allocator values. Format 5 retains its frozen
signature. Catalog format 7 stores captured auxiliary signaling identifiers with their explicit protocol, role and
text value, separately from the primary audio protocol and numeric radio fields. The adjacent format-6-to-7 step
adds an empty identifier table and its lookup index, preserving all existing recording data without inferring missing
historical identifiers. Format 6 retains its frozen signature. Catalog format 8 adds a disk-based full-text index
over stored transcripts, with insert, update and delete maintenance. The adjacent format-7-to-8 step indexes existing
transcript text while preserving every recording, audio path, transcript, tone, identifier, counter and allocator.
Format 7 retains its frozen populated fixture and exact signature. The index follows transcript retention and deletion;
it adds no activity summary or memory cache. Catalog format 9 admits the explicit analog PCM tone method for
nominal single tones and waveform DTMF while preserving all existing rows, relationships, transcript search data and
allocators. The adjacent format-8-to-9 step changes only tone-context method constraints; it creates no historical
tones or recording-to-alert links. Format 8 retains its frozen populated fixture and exact signature. Catalog format
10 extends the admitted method-4 nominal single-tone identities with QCII and Plectron frequencies without changing
any table, field or method code. Its adjacent format-9-to-10 step preserves all prior recording and tone data without
renormalization or backfill, changing only the format markers. Format 9 retains its exact populated fixture and DDL.
The new marker makes older readers refuse this expanded semantic contract. Catalog format 11 adds the small
`recording_source` table and a reverse lookup index. Each row connects one recording to one exact source call UUID;
several receiver copies may belong to one saved recording, and a source call may have several recorded parts. The
adjacent format-10-to-11 step creates an empty relationship table without guessing historical associations, preserving
all recording metadata, files, transcripts, tones, identifiers, counters and allocators. Deleting a recording cascades
to its own source references. Deleting Activity or alert history does not delete a recording or its source references.
The populated format-10 fixture remains frozen and supported. The main application database is independently at
format 64; recording-catalog migrations do not change it. Recognized older catalogs are updated in one transaction
at the pre-receiver setup boundary, with an optional SQLite-aware recovery snapshot
before changes. Normal catalog startup validates only. A catalog upgrade failure offers retry or continuation with
Managed Recordings unavailable, so optional catalog trouble does not prevent ordinary receiving.

Every change to persisted DDL or persisted meaning must land with all of the following:

1. The clean current-schema definition.
2. A global database-format bump.
3. One deterministic adjacent migration step from the prior format.
4. A populated prior-format fixture and tests that reach the exact new format signature.
5. An explicit declaration of data that is preserved, reset, dropped, or grounds for refusal.

This applies to schema-changing nightly and development builds as well as alphas and numbered releases. A build with
no persisted-format change reuses the existing version and needs no migration step. CI must reject a schema fingerprint
change without the matching version, step, fixture, and migration assertions.

Prefer small, explicit transformations. Rebuilding a table into its clean target DDL is acceptable when it is simpler
and safer than a long series of compatibility edits. A failed step must be safely repeatable from the unchanged source
database; the migrator does not resume by trusting a partially changed working copy.

## Data Policy

Migration support does not mean every historical value must survive. Each step classifies its affected data:

Selected older strict copy-imports that need the bounded receiver-derived repair discard the whole affected Activity/statistics
component in foreign-key-safe order, including its allocator state. They keep each frozen table layout while the
existing adjacent chain upgrades the empty tables. No historical counter, identity mapping or Activity row is
reconstructed. Once the current schema is reached, saved Alias configuration seeds one empty counter row per Alias.
Missing Alias summaries are also damage, even when no historical rows remain. Preflight declares the possible reset;
completion counts the discarded rows and the empty Alias counters. Usable configuration, opaque credentials and
recording files/catalogs remain outside this reset. The selected source remains unchanged, and failure before commit
rolls back the destination so the same source can be retried. Healthy retained history continues through its declared
adjacent preservation steps. This fixes the existing damaged-history repair policy without changing format 62 or its
persisted meanings.

| Data class | Required behavior |
| --- | --- |
| Administrator-owned configuration | Preserve or convert each valid component independently. Repair a safe technical default when possible; otherwise skip only the unusable row and count it. Never guess relationships or silently truncate data. |
| Credentials and secret material | Preserve opaquely when valid; reset only the affected authentication/provider setup when unusable, and never print values in plans, logs, or reports. |
| Bounded activity summaries and detailed history | Preserve when straightforward; otherwise reset when conversion cost or ambiguity is disproportionate. |
| Caches, indexes, and reproducible projections | Rebuild or reset. |
| State for intentionally retired features | Drop when it has no supported current representation. |
| Invalid, over-capacity, or ambiguous row state | Default or skip that bounded component and report it. Refuse only when the database structure, integrity, final consistency, or promotion cannot be made safe. |

Every possible reset or drop is named during preflight. The one real migration reports actual changes and counts
where available. A settings-only upgrade declares the entire omitted history component without scanning or counting
it just to produce a total. Numbered release notes additionally summarize user-visible policy for each format
introduced by that release.

## Upgrade Execution

The bundled Application Migrator is the only component allowed to change an existing supported database schema.
Current-profile upgrades run after the portable-data lock is acquired and before the main receiver window, tuners,
and database services start. The Setup Wizard can already be visible at this point.

The graphical workflow follows this order:

1. Resolve the selected profile using basic folder checks. Inspect only the registered format, exact schema, and
   bounded configuration needed to identify the supported operation. Refuse unknown, mixed, newer, or unsupported
   formats without guessing. No whole-source snapshot, digest, physical scan, or history count runs before the choices.
2. Collect the explicit manual-backup acknowledgement and **Keep activity and statistics** choice. Full retained
   history is opt-in. A selected folder first offers **Review upgrade**: inspect only its supported schema and bounded
   configuration under the source lock, then show source, destination, selected scope, and declared resets or drops
   before **Begin upgrade**. The review retains the choices and allows them to change. There is no whole-database
   review-copy phase, history scan, or throwaway conversion. The independent backup remains for manual recovery;
   VCE does not know its location or restore it automatically.
3. For a new installation, require an empty destination, independent source and target roots, the source profile
   lock, and the same filesystem. Transfer the portable `data` folder with an explicit transfer receipt rather than a
   recursive copy. The acknowledgement authorizes this ownership change; it does not assert that VCE verified a backup.
   The lock file keeps its exact filesystem identity at the destination. Where supported, the operating-system lock
   stays held across the rename. Windows briefly closes the inner lock for the atomic rename, then reacquires that
   exact moved lock file and checks ownership before activation. A competing owner or changed source stops activation
   and preserves the pending transfer; an occupied destination is refused.
   An existing active profile stays at its current location.
4. For settings-only, extract admitted configuration, required metadata, and exact configuration relationships into
   a private working database with empty history. Do not copy, hash, count, delete individual rows from, or physically
   scan the discarded history. Preserve exact supported source definitions and run applicable adjacent conversions
   on that bounded working data. For full history, retain the existing adjacent chain and its declared preservation
   and reset policies. Do not add a direct-to-current fallback.
5. Rebase supported saved paths within transferred data, including Managed Recordings and Recording Tuner files.
   Preserve external paths. Keep the vault, libraries, recording audio, and recording catalog together with their
   existing ownership. The application history choice does not clear those artifacts.
6. Check the final global format, exact role schemas and pair identity, required settings, and startup configuration
   before activation. A required Managed Recordings catalog update uses its own adjacent chain before receiving.
   Normal startup then validates only. The independent manual backup is the recovery copy; the graphical upgrade
   does not create another per-database backup.

Full-history owned-profile updates reserve the writer with `BEGIN IMMEDIATE`, recheck the admitted source, and apply
the adjacent chain in one transaction. The runner carries each exact target schema into the next step rather than
repeating source inspections. It preserves valid configuration and credentials, applies bounded component repair,
and reports actual effects. Ordinary startup and owned-profile upgrades do not perform whole-file physical-integrity
or derived-history scans. Damage detectable only through such scans can surface later; explicit inspection and the
strict copy-import engine retain their fuller checks.

Headless `--upgrade-current` retains full content and a SQLite-aware recovery snapshot by default. Its
`--no-upgrade-backup` flag omits only that snapshot. These headless choices do not change the graphical manual-backup
contract, and the same backup default applies to headless Managed Recordings updates.

A conversion or final-validation failure before a database transaction commits rolls it back. A committed
configuration/history split interrupted during filename activation is completed only by the Application Migrator
using its activation receipt; normal startup refuses an incomplete pair. Folder-transfer receipts likewise preserve
the operation state rather than treating a moved source as an unchanged import. The adjacent conversion steps do
not commit independently. After a successful upgrade, the prior build cannot open a newer incompatible format;
restoring that build requires the independent compatible backup. Statement, connection, temporary-file, or
progress-observer errors after commit are completion warnings, not permission to repeat an already completed update.
A healthy current-format profile needs no automatic format change.

Progress shows the current phase and elapsed time, with detailed output available on demand. The engine emits
`Step X of Y — description` before each adjacent conversion, then validation and commit phases. The commit-phase
message is not success; success is reported only after `COMMIT` returns. Confirmation cancellation, error retry, and
completion navigation remain in the wizard. Reports distinguish settings-only omissions, retained-history work,
folder transfer, path relocation, and optional catalog updates.

Headless `--upgrade-data` and retained internal copy-import/replacement use a different ownership boundary from the
graphical folder transfer, while reusing the same conversion chain. The
selected source and its containing folder need not be writable. After checking free space, copy its database and
readable journal/WAL into private destination scratch space, recover that copy, fingerprint its registered format,
and perform physical integrity checks. Verify the selected files unchanged and bind confirmation to the snapshot's
content digest; refuse a source that changed during review. Show source, target, scope, and declared effects before
mutation. Run the chain in the private child process, validate final schema, required rows, integrity and foreign
keys, then promote atomically. Cancelled or failed imports never promote partial data. Replacement retains a
current-profile backup and restores it if post-promotion validation fails. Normal application startup never repairs
or migrates an existing schema.

Internal database replacement requires database-owning callers to close their services while retaining the portable
lock. The retained engine backs up the active database, migrates a staged copy, validates it, and promotes atomically. The source and its
neighboring files remain unchanged. A post-promotion validation failure attempts to restore the retained backup;
when a safe active-database state cannot be proven, the error and backup remain for explicit recovery. Internal
replacement resets the bounded wizard-progress record before final validation and promotion, so any subsequently
started profile must review unfinished settings. Old in-memory preferences must not be reused. These invariants and
their failure-injection tests remain even though there is no public menu, flag, or source-picker replacement flow.

## Input Scope

The explicit graphical VCE upgrade transfers the whole portable data directory. Its supported saved paths are
rebased from the old data root to the new one; absolute paths outside that root remain external. Managed Recordings
audio and catalog, Classic audio, vault, JMBE, modules, logs, and other existing portable artifacts move together.
The operation never silently substitutes a recursive cross-filesystem copy or leaves the old installation labeled
as its backup.

Headless `--upgrade-data` and internal portable-profile copy-import may copy supported profile artifacts such
as the vault, JMBE library, optional modules, converted legacy JSON settings, and paths that need remapping. Their
legacy copy scope excludes recording audio, logs, streaming output, screenshots, and the Managed Recordings catalog;
their selected sources remain unchanged.
Remote Links settings and paging-tone choices already in the application database are preserved for every import scope.
The adjacent update removes retired voice-enhancement and continuous-gain choices and reports their counts.
Remote Links network
destinations and stable channel/sender identities are preserved without path remapping. An unavailable optional artifact is
reported and skipped independently rather than discarding an otherwise valid migrated database. Public selectors
accept complete profile folders/applications only. Preflight declares which optional artifact types the selected
profile can attempt; completion reports what was copied, unavailable, or skipped.

Internal database-only migration and replacement operate on values stored in SQLite. They leave source neighbors
and the target's existing non-database files unchanged, do not remap stored paths, and must never imply that external
artifacts were copied. Their approval identifies source, target, scope, and declared effects. A markerless internal
import initializes destination setup review: a usable primary administrator credential satisfies the administrator
step; otherwise startup requires a new one. These engine contracts do not expose standalone files in user setup.

All graphical and headless entry points use the same inspector, registry, chain runner, validator, and report model.
Do not add schema-specific launchers or separately maintained migration utilities.

## Required Tests

The retained fixture set is per database format, not per release label. It must include exact, populated examples for
every retained format from the Alpha 12 format-2 baseline and exercise:

- every adjacent step and every supported source format through the current target;
- preservation of representative valid administrator configuration and credentials without secret disclosure;
- mixed-defect fixtures proving malformed channels, providers, preferences, relationships, and optional artifacts are
  isolated while independent valid configuration survives;
- the declared repairs, defaults, resets, drops, exact completion counts, and structural refusals for each step;
- current-format no-op behavior and rejection of newer, unknown, mixed, tampered, and corrupt inputs;
- migration from internal SQLite-file inputs and public full portable-data inputs, plus refusal of standalone files
  by public source selectors;
- failure injection before and after each step, final validation failure, and atomic-promotion failure;
- strict copy-import backup retention, unchanged sources, safe retry, and no partially promoted result;
- early graphical acknowledgement/history choices before any whole-source copy, digest, scan, or history count;
- settings-only extraction from every supported source, exact configuration preservation, and empty current history
  without whole-history copying, hashing, counting, row deletion, or physical-integrity scans;
- same-filesystem folder transfer with stable lock-file identity, platform-appropriate ownership checks, external-path
  preservation, and refusal of cross-filesystem,
  overlapping, in-use, symbolic-link, or nonempty destination profiles before transfer;
- transfer interruption and failure recovery, including preservation of Managed audio/catalog, Classic audio, vault,
  libraries, optional modules, and existing Recording Tuner files; and
- registry completeness: no gap, duplicate source, duplicate target, or missing format-2-to-current route.

Tests compare the migrated database to the same exact current format signature used by normal startup validation. A
release cannot claim retained-format compatibility unless all retained source fixtures pass against its exact target
build.

## Explicit Non-Goals

The migration chain does not support pre-format-2 databases, down-migration, the retired `webfirst` managed-recording
catalog, or perfect preservation of expensive derived history. It does not reintroduce retired product features. An
older build is recovered by reopening or restoring the preserved older data, not by converting a newer database
backward.

</details>
