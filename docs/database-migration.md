# Database Migration Contract

This document is the implementation contract for the bundled Application Migrator. It supersedes the former
immediately-previous-release, external-candidate, and sequential-upgrade design. This source contains the global
format catalog, complete Alpha 8-to-current chain, deterministic format fixture factories, and safety integration
described here. Numbered Alpha distributions keep version-matched release notes; rolling Nightlies use the bundled
current documentation and the migrator's preflight and completion reports.

## Support Boundary

The migrator supports every distinct sdrtrunk-vce database format distributed or deployed from Alpha 8 through the
format used by the running build. `main`, alpha, and nightly are build channels over one database-format sequence;
they are not separate migration tracks. Each previously distributed format must have a catalog entry and fixture;
omitting it from the catalog does not make it an allowed support exception.

- A valid exact current-format database is accepted without mutation. A structurally exact current database with
  recoverable row-level damage can be repaired only by the staged Application Migrator, with every change itemized.
- A structurally recognized older format is migrated forward through the linear chain. Recoverable row-level damage
  does not disqualify the whole database: each configuration component is preserved, repaired, defaulted, or skipped
  independently and the completion report gives non-secret counts.
- Alpha 8, Alpha 9, and Alpha 10 share one verified legacy format signature and therefore enter at the same baseline.
- Pre-Alpha 8, retired `webfirst`, unknown, mixed, structurally partial, and physically corrupt layouts are refused
  without changing the source.
- Migration is forward-only. A build must refuse a database whose format is newer than it understands.

Support is based on database structure, not a release label, filename, timestamp, or build number. This matters for
nightly builds, which may not contain durable release provenance.

## Legacy Inventory Gate

The first replacement change must perform a one-time audit of Alpha 8-and-later release tags, recoverable nightly and
schema-changing commits, archived artifacts, and available deployed database samples. The runtime format catalog is
also the checked-in legacy manifest; do not create a second hand-copied inventory. Each legacy entry records:

- its global format ID and complete format signature;
- expected subsystem metadata and critical invariants;
- known source tags, commits, build ranges, or archived samples;
- its populated fixture; and
- its preservation, reset, drop, and refusal policy.

Several builds may reference one entry when they are structurally and semantically indistinguishable. Tests enumerate
the catalog entries and their fixtures directly. If a known distributed build cannot be reconstructed or matched to a
safe signature, record it as unresolved and obtain an exact database sample; do not claim complete Alpha 8+ support or
guess a route until the entry is resolved.

The replacement audit recovered six formats from successful Alpha 8-or-newer alpha, nightly, and main builds: the
shared Alpha 8/9/10 and early-nightly baseline, the scan-list/P25-v26 format, the P25-v27 site-projection format, the
P25-v28 logical-call/site-observation format, the normalized web-user/saved-channel-identity format, and the current
canonical conventional-context identity format. The runtime catalog is the sole exact inventory of their
fingerprints, subsystem metadata, source references, fixtures, and data policies; this document intentionally does
not duplicate those values.

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

Legacy Alpha 8-or-newer databases that predate the global marker are admitted through the format registry. A signature
combines the complete schema fingerprint with exact subsystem metadata and critical structural/data invariants. A
unique signature may still contain independently repairable row damage. When several semantic formats share one
schema, their metadata and disambiguating invariants must identify exactly one source; the migrator refuses an
ambiguous markerless state instead of guessing which build produced it.

Migration steps form one ordered chain:

```text
format 1 (Alpha 8 family) -> format 2 -> format 3 -> format 4 -> format 5 -> format 6 -> format 7 -> format 8 -> format 9 -> format 10 -> format 11 -> format 12 -> format 13 -> format 14 -> format 15 (current)
```

Each step owns exactly one `N -> N+1` transformation. The runner repeatedly applies the next registered step until it
reaches the target. There is no release graph, alpha/nightly branch, path-cost planner, or second migration-history
table. A target build retains every step back to the Alpha 8 baseline.

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
ambiguous, conflicting, or malformed derived identities that current format 15 discards anyway. Administrator-owned
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
existing setting and marks an upgraded profile as previously configured while still requiring runtime readiness
checks for settings such as JMBE and the administrator account.

## Replacement Boundary

This is one replacement, not a second migrator layered beside the existing one. Replace the Alpha-specific source
classification, current-versus-Alpha state model, subsystem-tuple routing, and direct legacy-to-current dispatch with
the global format catalog and chain runner. Remove the old gate instead of retaining it as a fallback. Existing
transformation logic that is still correct is assigned to the appropriate adjacent step rather than exposed as an
alternate path.

Retain the existing launcher, child-process isolation, source backup, staged-copy workflow,
validation, and atomic promotion where they already meet this contract. Graphical setup, headless setup, and direct
SQLite-file selection are entry points to the same engine, not separate implementations.

The Swing Setup Wizard owns first-run graphical presentation. Migration preflight performs a read-only structural
inspection and quick integrity check, then shows the declared best-effort policy for every required step. It does not
run a redundant throwaway migration. The child process scans and migrates the staged copy once and the completion
report replaces unknown preflight counts with the exact observed repair, reset, and skip counts.
Preflight, progress, and completion stay on its Starting point page; completion offers Copy Message and a ten-second
continuation countdown. Errors remain compact and inline with expandable details and a Copy error action. Database
replacement and startup errors use the same bounded, expandable, copyable presentation instead of message-sized
dialogs. After promotion,
Back can review the installed source/results but cannot replace the database. The separately confirmed post-setup
SQLite replacement workflow is reached through **File > Import SQLite Database…** and uses that same Starting point
boundary. It retains
its existing service-stop, backup, validation, restart, and quit-blocking rules. Its source confirmation and completion
dialogs are reused; it does not reuse the new-install copy operation against an occupied data folder.

Format 13 adds only the bounded `setup_wizard` record in `application_settings`. The adjacent 12-to-13 step preserves
all existing configuration and marks existing installations previously configured; runtime readiness checks decide
whether required setup work remains. New databases begin incomplete. Copy imports reset this record on the staged
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

## Schema-Change Rule

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

| Data class | Required behavior |
| --- | --- |
| Administrator-owned configuration | Preserve or convert each valid component independently. Repair a safe technical default when possible; otherwise skip only the unusable row and count it. Never guess relationships or silently truncate data. |
| Credentials and secret material | Preserve opaquely when valid; reset only the affected authentication/provider setup when unusable, and never print values in plans, logs, or reports. |
| Bounded activity summaries and detailed history | Preserve when straightforward; otherwise reset when conversion cost or ambiguity is disproportionate. |
| Caches, indexes, and reproducible projections | Rebuild or reset. |
| State for intentionally retired features | Drop when it has no supported current representation. |
| Invalid, over-capacity, or ambiguous row state | Default or skip that bounded component and report it. Refuse only when the database structure, integrity, final consistency, or promotion cannot be made safe. |

Every possible reset or drop is named during preflight. Exact counts are determined by the one real staged migration
and repeated in the completion report, so every build reports its exact preservation and loss behavior. Numbered
release notes additionally summarize the user-visible policy for each format introduced by that release.

## Safe Execution

The bundled Application Migrator is the only component allowed to change an existing supported database schema. Its
single execution pipeline is:

1. Open the source read-only, fingerprint it, resolve its format, and run a quick physical integrity check. Source
   checks do not apply row `CHECK` rules that staged repair can fix; final validation remains strict. The selected
   file and its containing folder do not need to be writable. The database and any readable journal/WAL are copied
   into private scratch space on the selected destination's filesystem, after a free-space check. The original files
   are verified byte-for-byte unchanged, and SQLite recovery or WAL replay occurs only on that private copy. If the
   source changes during this copy, the attempt is refused and can be retried after closing the prior application.
2. Present the source, target, external-file scope, and declared resets or drops before mutation.
3. Create a recoverable backup or snapshot and a separate staged database.
4. Run the chain once, only against the staged copy in the migration child process. Legacy foreign-key enforcement is
   relaxed during the transaction so orphaned rows can be removed, but a complete foreign-key check is mandatory
   before commit.
5. Validate the final global version, exact schema fingerprint, required row invariants, SQLite integrity, and foreign
   keys.
6. Promote the staged result atomically only after every validation succeeds. Freed pages remain reusable by SQLite;
   migration does not run a temporary-space-intensive compaction pass.

For an import, the selected source database and previous installation remain unchanged. For an in-place upgrade, the
live database is replaced only after the staged result passes every check, and the pre-migration safety backup is
retained. On cancellation, a crash before promotion, failed conversion, or failed validation, the staged result is
not promoted. After the atomic promotion point, the already validated result may be live; in-place post-promotion
validation restores the retained backup on failure. Normal application startup after setup is exact-schema
validation-only and never creates, repairs, or migrates an existing schema.

After setup, **File > Import SQLite Database…** provides an explicit database-only replacement workflow. It safely
restarts into the pre-receiver setup boundary and first closes the receiver and its database-owning runtime services.
The wizard preflights the selected source and displays a bold red replacement warning before confirmation. With the
portable-data lock retained and setup preferences closed, it backs up the current active database, migrates a staged
copy of the selected source, validates it, promotes it atomically, and starts a new application process. The source is
never changed. Window close, Exit, and operating-system quit requests are refused while replacement is in progress.
The completion report has a Copy Message action and a visible countdown that continues automatically without waiting
for the operator to dismiss it. A failed replacement does not automatically relaunch the application when the final
active-database state cannot be proven; the retained backup and error are left for explicit recovery. Replacement
resets the existing bounded wizard-progress record on the staged copy before validation and promotion. Normal startup
therefore returns to unfinished setup and review even if the imported source was setup-complete or the restart was
interrupted. Old in-memory preferences are closed before replacement and are never reused to launch the new profile.

## Input Scope

Selecting an install or portable data directory allows the migration workflow to copy supported profile artifacts such
as the vault, JMBE library, optional modules, and paths that need remapping. An unavailable optional artifact is
reported and skipped independently rather than discarding an otherwise valid migrated database. Selecting only
`database/sdrtrunk.sqlite` migrates only values stored in SQLite. Preflight declares which optional artifact types the
selected scope can attempt; completion reports what was copied, unavailable, or skipped. File-only migration must never
imply that external artifacts were copied.

The after-setup SQLite wizard import also has database-only scope. It replaces the active SQLite contents rather than
merging rows, leaves the selected source and its neighboring files unchanged, and leaves the active data folder's
existing non-database files in place. Its confirmation must identify both the selected source and active target and
state these boundaries before replacement. Stored portable paths are not remapped for this database-only workflow. A
markerless imported database is initialized as a newly imported profile: a usable primary administrator credential
satisfies the administrator step, otherwise startup requires the operator to create one. Both cases require destination review.

All graphical and headless entry points use the same inspector, registry, chain runner, validator, and report model.
Do not add schema-specific launchers or separately maintained migration utilities.

## Required Tests

The retained fixture set is per database format, not per release label. It must include exact, populated examples for
every distinct Alpha 8-or-newer format and exercise:

- every adjacent step and every supported source format through the current target;
- preservation of representative valid administrator configuration and credentials without secret disclosure;
- mixed-defect fixtures proving malformed channels, providers, preferences, relationships, and optional artifacts are
  isolated while independent valid configuration survives;
- the declared repairs, defaults, resets, drops, exact completion counts, and structural refusals for each step;
- current-format no-op behavior and rejection of newer, unknown, mixed, tampered, and corrupt inputs;
- migration from both SQLite-file and full portable-data inputs;
- failure injection before and after each step, final validation failure, and atomic-promotion failure;
- backup retention, unchanged sources, safe retry, and no partially promoted result; and
- registry completeness: no gap, duplicate source, duplicate target, or missing Alpha 8-to-current route.

Tests compare the migrated database to the same exact current format signature used by normal startup validation. A
release cannot claim Alpha 8+ compatibility unless all retained source fixtures pass against its exact target build.

## Explicit Non-Goals

The replacement does not support pre-Alpha 8 databases, down-migration, the retired `webfirst` managed-recording
catalog, or perfect preservation of expensive derived history. It does not reintroduce retired product features. An
older build is recovered by reopening or restoring the preserved older data, not by converting a newer database
backward.
