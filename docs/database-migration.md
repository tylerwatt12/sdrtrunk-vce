# Database Migration Contract

This document is the implementation contract for the bundled Application Migrator. It replaces the former
Alpha-specific source gate, direct-to-current routing, and sequential-release upgrade policy. Historical releases keep
the behavior documented by their version-matched What's New documents.

## Support Boundary

The Alpha 11 migrator supports every verified sdrtrunk-vce database format distributed from Alpha 8 through its
current global format 2. Alpha and nightly are build channels over one database-format sequence; a release name,
filename, build date, or source directory never selects a migration route.

- An exact current format-2 database with its authoritative marker is accepted without mutation.
- An exact markerless format-2 database is validated and safely adopts the marker through the migrator.
- The shared Alpha 8, Alpha 9, and Alpha 10 format is recognized as global format 1 and migrates through the single
  adjacent `1 -> 2` step.
- Pre-Alpha 8, retired `webfirst`, known-unpublished developer, unknown, mixed, partially migrated, and corrupt layouts
  are refused without changing the source.
- Migration is forward-only. A database whose marker is newer than format 2 is refused even when its schema is not
  otherwise known to this build.

Support is based on the complete schema fingerprint, expected subsystem metadata, and critical structural and data
invariants. Omitting a previously distributed format from the catalog does not make it an allowed support exception.

## One Version And One Chain

The database stores one authoritative, monotonically increasing integer `database_format_version` in the existing
`database_metadata` table. It describes the complete persisted SQLite contract, including semantic changes that may
not alter DDL. Existing subsystem-version rows remain validation inputs but do not choose the migration route. There
is no second version table, Alpha/nightly fork, release graph, or alternate migrator.

Marker-bearing databases resolve by global version first and must then match that version's exact fingerprint,
metadata, and invariants. Markerless legacy databases resolve from exact catalog signatures only. Ambiguous or
metadata-mismatched layouts are refused rather than guessed.

Alpha 11 carries this complete chain:

```text
format 1 (shared Alpha 8/9/10 layout) -> format 2 (Alpha 11 current)
```

The checked-in runtime catalog is also the legacy manifest. Each entry records its source references, exact signature,
fixture, and preservation/reset/drop policy. Known developer layouts that did not produce a verified publication are
listed as explicit refusals so they cannot accidentally match a supported release.

## Format 1 To 2 Policy

The adjacent step makes the following deliberate changes:

- Administrator-owned channels, channel maps, broadcast streams, settings, icons, ordinary Alias matchers, recording
  choices, stream routes, and portable configuration are preserved.
- Numeric Alias playback priorities become the Default scan list's Listen on/off membership. Any enabled numeric
  priority remains enabled; retired ordering between enabled priorities is not retained.
- Eligible full-domain catch-all talkgroup Aliases become their Alias List's unmatched-talkgroup policy, including
  supported recording and streaming actions. Ambiguous catch-alls are refused instead of guessed.
- Retired fully-qualified P25 talkgroup and radio Alias matchers, plus stream routes owned only by those rows, are
  dropped because format 2 has no supported representation for them.
- Rebuildable P25 affiliation and protocol-neutral identity evidence is reset and recreated in the format-2 schema.
  Live traffic rebuilds current state. Other bounded activity data is preserved where its schema is unchanged.

Every transformation, reset, and drop is counted when practical and appears in both the preflight plan and completion
report. Credential values and other secrets are never included in those reports.

## Schema-Change Rule

Every persisted DDL or semantic change must land with all of the following:

1. The clean current-schema definition.
2. A global database-format bump.
3. One deterministic adjacent migration step from the prior format.
4. An exact populated fixture for the prior format and tests that reach the new format's exact signature.
5. An explicit declaration of preserved, converted, reset, dropped, and refused data.

This rule applies to development and nightly builds as well as numbered Alpha releases. A build with no persisted
format change reuses the current version and adds no migration step. A failed step is retried only from an unchanged
source or preserved copy; no code resumes from a partially modified database.

## Safe Execution

The bundled Application Migrator is the only component allowed to change an existing supported database schema. Its
pipeline is:

1. Inspect the source read-only, identify its exact format, and calculate the ordered steps.
2. Present the source and target formats, migration scope, and declared transformations, resets, or drops.
3. Create a recoverable backup or snapshot and a separate staged database.
4. Run the chain only against that staged copy in the migration child process.
5. Validate the final global marker, exact schema fingerprint, configuration semantics, SQLite integrity, and foreign
   keys.
6. Promote the staged result atomically only after every check succeeds.

For a copied-profile migration, the selected source and previous installation remain unchanged. For an in-place
upgrade, the live database is replaced only after the staged result validates, and the pre-migration safety backup is
retained. Cancellation or any failure before promotion leaves the active database unchanged. If in-place validation
after promotion fails, the retained backup is restored. Normal startup after setup is exact-schema validation-only;
it never creates missing objects, repairs a partial database, or runs migration steps.

Rollback never means opening format 2 with Alpha 10 or attempting a down-migration. Stop Alpha 11 and reopen the
preserved Alpha 10 installation/data copy, or restore the pre-migration safety backup with the older build.

## Input And Replacement Scope

Selecting an install or portable data directory permits supported profile artifacts such as the vault, JMBE library,
optional modules, and recognized portable paths to be copied or remapped. Selecting only
`database/sdrtrunk.sqlite` migrates only content stored inside that SQLite file. Preflight and completion reports state
which scope was used so a file-only import never implies that external artifacts were copied.

After setup, **File > Import SQLite Database** is a complete database replacement, not a row merge. It must show the
replacement warning and preflight plan, flush current configuration, stop database-owning services while retaining
the portable-data lock, retain the active database as a validated safety backup, migrate only a staged copy of the
selected file, promote atomically, and restart in a new process after confirmed success. The selected source and its
neighboring files remain unchanged, active non-database portable files remain in place, and stored portable paths are
not remapped for a file-only replacement. Exit and window-close requests are refused while replacement is active.

Graphical completion reports provide **Copy Message** and a visible bounded countdown that continues automatically.
Copying the report does not dismiss it, cancel it, or reset the countdown.

## Required Tests

The fixture and integration suites cover:

- every catalog entry and the complete route from each supported source to format 2;
- exact format-2 fresh creation, marker adoption, and current-format no-op validation;
- representative administrator configuration, settings, credentials, and portable-path handling without secret
  disclosure;
- numeric-priority projection, catch-all conversion, fully-qualified matcher removal, and declared activity resets;
- rejection of newer, unknown, mixed, tampered, malformed-configuration, and retired managed-recording inputs;
- both SQLite-file and full portable-data scopes;
- source/backup immutability, stale-plan and same-file guards, helper failure, final-validation failure, promotion
  failure, recovery, and safe retry; and
- symlink, hard-link, path-race, foreign-key, integrity, and lifecycle boundaries.

The migrated database must match the same exact fingerprint and global marker required by normal startup. A release
cannot claim Alpha 8+ support unless every retained supported-format fixture passes against its exact target build.

## Non-Goals

The migrator does not support pre-Alpha 8 databases, down-migration, the retired `webfirst` managed-recording catalog,
unknown developer layouts, or perfect preservation of inexpensive derived history. It does not reintroduce retired
features. Unsupported or ambiguous administrator-owned configuration is refused rather than silently discarded.
