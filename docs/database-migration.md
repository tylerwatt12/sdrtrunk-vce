# Database Migration Contract

This document is the implementation contract for the bundled Application Migrator. It supersedes the former
immediately-previous-release, external-candidate, and sequential-upgrade design. This source contains the global
format catalog, complete Alpha 8-to-current chain, deterministic format fixture factories, and safety integration
described here. Older distributions keep the behavior documented by their own version-matched release notes.

## Support Boundary

The migrator supports every distinct sdrtrunk-vce database format distributed or deployed from Alpha 8 through the
format used by the running build. `main`, alpha, and nightly are build channels over one database-format sequence;
they are not separate migration tracks. Each previously distributed format must have a catalog entry and fixture;
omitting it from the catalog does not make it an allowed support exception.

- An exact current-format database is accepted without mutation.
- An exact recognized older format is migrated forward through the linear chain.
- Alpha 8, Alpha 9, and Alpha 10 share one verified legacy format signature and therefore enter at the same baseline.
- Pre-Alpha 8, retired `webfirst`, unknown, mixed, partially migrated, and corrupt layouts are refused without changing
  the source.
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

The replacement audit recovered three formats from every successful Alpha 8-or-newer alpha and nightly publication:
the shared Alpha 8/9/10 and early-nightly baseline, the later scan-list/P25-v26 nightly format, and the current
P25-v27 format. The runtime catalog is the sole exact inventory of their fingerprints, subsystem metadata, source
references, fixtures, and data policies; this document intentionally does not duplicate those values.

Five intermediate schema fingerprints are source-recoverable, but the nightly workflow failed before packaging them.
They are cataloged as known unsupported developer states so the inspector can give a precise refusal instead of
guessing. If one was actually deployed, retain that database and its matching `build_info.txt`; support requires an
exact deployed sample and an explicit adjacent step.

## One Version And One Chain

Each new database stores one authoritative, monotonically increasing integer `database_format_version` in the existing
`database_metadata` table. That value describes the complete persisted SQLite contract, including schema semantics
that an exact DDL fingerprint alone cannot identify. Existing subsystem-version rows may remain useful for validation,
but they do not choose a migration route. Do not add another version or migration-history table.

Marker-bearing formats resolve by global version first and then validate that version's exact fingerprint, metadata,
and invariants. This permits a semantic-only format bump to share its predecessor's DDL fingerprint. Markerless legacy
formats resolve from all matching fingerprint candidates and are accepted only when metadata and invariants identify
exactly one; an ambiguous markerless state is refused.

Legacy Alpha 8-or-newer databases that predate the global marker are admitted through a small registry of exact format
signatures. A signature combines the complete schema fingerprint with the expected subsystem metadata and critical
structural/data invariants, then maps that state to its first global format version. A metadata or invariant mismatch
is an error, not a repair opportunity; the subsystem tuple alone is never sufficient. When several historical builds
share one signature, their common step must be safe for every legal database in that group; reset indistinguishable
derived state or refuse ambiguous critical configuration instead of guessing which build produced it.

Migration steps form one ordered chain:

```text
format 1 (Alpha 8 family) -> format 2 (published nightly) -> format 3 (current)
```

Each step owns exactly one `N -> N+1` transformation. The runner repeatedly applies the next registered step until it
reaches the target. There is no release graph, alpha/nightly branch, path-cost planner, or second migration-history
table. A target build retains every step back to the Alpha 8 baseline.

The format 2-to-3 step creates a missing canonical factory Alias List and its Default scan-list routing only when no
case-insensitive name match exists. An existing same-family match keeps its stored spelling and administrator-owned
routing; blank compatible channels use that stored spelling. A canonical name owned by the wrong family is refused
during preflight rather than renamed or repurposed.

## Replacement Boundary

This is one replacement, not a second migrator layered beside the existing one. Replace the Alpha-specific source
classification, current-versus-Alpha state model, subsystem-tuple routing, and direct legacy-to-current dispatch with
the global format catalog and chain runner. Remove the old gate instead of retaining it as a fallback. Existing
transformation logic that is still correct is assigned to the appropriate adjacent step rather than exposed as an
alternate path.

Retain the existing launcher and setup experience, child-process isolation, source backup, staged-copy workflow,
validation, and atomic promotion where they already meet this contract. Graphical setup, headless setup, and direct
SQLite-file selection are entry points to the same engine, not separate implementations.

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
| Administrator-owned configuration | Preserve it exactly, convert it unambiguously, or refuse the migration. Never guess or silently truncate it. |
| Credentials and secret material | Preserve opaquely when supported; never print values in plans, logs, or reports. |
| Bounded activity summaries and detailed history | Preserve when straightforward; otherwise reset when conversion cost or ambiguity is disproportionate. |
| Caches, indexes, and reproducible projections | Rebuild or reset. |
| State for intentionally retired features | Drop when it has no supported current representation. |
| Invalid, over-capacity, or ambiguous critical state | Refuse with a clear, actionable reason. |

Every reset or drop is named during preflight, counted when practical, and repeated in the completion report. Release
notes summarize the user-visible preservation and loss policy for every format introduced by that release.

## Safe Execution

The bundled Application Migrator is the only component allowed to change an existing supported database schema. Its
single execution pipeline is:

1. Open the source read-only, fingerprint it, resolve its format, and calculate the ordered steps.
2. Present the source, target, external-file scope, and declared resets or drops before mutation.
3. Create a recoverable backup or snapshot and a separate staged database.
4. Run the chain only against the staged copy in the migration child process.
5. Validate the final global version, exact schema fingerprint, required row invariants, SQLite integrity, and foreign
   keys.
6. Promote the staged result atomically only after every validation succeeds.

For an import, the selected source database and previous installation remain unchanged. For an in-place upgrade, the
live database is replaced only after the staged result passes every check, and the pre-migration safety backup is
retained. On cancellation, a crash before promotion, failed conversion, or failed validation, the staged result is
not promoted. After the atomic promotion point, the already validated result may be live; in-place post-promotion
validation restores the retained backup on failure. Normal application startup after setup is exact-schema
validation-only and never creates, repairs, or migrates an existing schema.

After setup, **File > Import SQLite Database** provides an explicit database-only replacement workflow. It preflights
the selected source before confirmation, displays a bold red replacement warning, closes all database-owning runtime
services while retaining the portable-data lock, backs up the current active database, migrates a staged copy of the
selected source, validates it, promotes it atomically, and starts a new application process. The selected source is
never changed. Window close, Exit, and operating-system quit requests are refused while replacement is in progress.
The completion report has a Copy Message action and a visible countdown that continues automatically without waiting
for the operator to dismiss it. A failed replacement does not automatically relaunch the application when the final
active-database state cannot be proven; the retained backup and error are left for explicit recovery.

## Input Scope

Selecting an install or portable data directory allows the migration workflow to copy supported profile artifacts such
as the vault, JMBE library, optional modules, and paths that need remapping. Selecting only
`database/sdrtrunk.sqlite` migrates only values stored in SQLite. The plan and completion report must clearly say when
external artifacts were unavailable; file-only migration must never imply that they were copied.

The after-setup SQLite menu import also has database-only scope. It replaces the active SQLite contents rather than
merging rows, leaves the selected source and its neighboring files unchanged, and leaves the active data folder's
existing non-database files in place. Its confirmation must identify both the selected source and active target and
state these boundaries before shutdown. Stored portable paths are not remapped for this database-only workflow. A
markerless imported database is initialized as a newly imported profile: an existing primary administrator satisfies
setup, otherwise startup requires the operator to create one.

All graphical and headless entry points use the same inspector, registry, chain runner, validator, and report model.
Do not add schema-specific launchers or separately maintained migration utilities.

## Required Tests

The retained fixture set is per database format, not per release label. It must include exact, populated examples for
every distinct Alpha 8-or-newer format and exercise:

- every adjacent step and every supported source format through the current target;
- exact preservation of representative administrator configuration and credentials without secret disclosure;
- the declared resets, drops, counts, and refusals for each step;
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
