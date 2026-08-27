# Alias Discovery and Unmatched Talkgroup Storage

> **Release scope:** This design describes current `main` and Nightly behavior. Numbered Alpha builds may omit these
> newer Alias and website features.

## User-visible purpose

The Alias page's Discover tab calls `/api/v1/alias-lists/{id}/observed-talkgroups` to show P25, DMR, and NXDN talkgroups or
patch groups that have been observed for a selected alias list. Exact aliases are excluded by default, while a
covering range is reported without hiding the received identity. Administrators can use an observation to prefill a
normal alias without changing the identity that was received over the air.

Each P25, DMR, NXDN, or NBFM list also owns an Unmatched Talkgroups policy. The policy supplies recording, stream, and
scan-list destinations when a destination talkgroup has no exact or range alias in that Alias List. For the NBFM
family, this classification applies to the configured logical AM or NBFM destination talkgroup; FleetSync and
MDC-1200 signaling identities remain normal Alias evidence and do not replace that destination. The policy contains no
matcher, display identity, or Stream As value, so it does not create a false catch-all alias. It routes the completed
call under its received identity; creating a normal Alias for a newly identified talkgroup remains an explicit
administrator action. The Discover tab remains limited to the persisted P25, DMR, and NXDN observation catalog.

## Administrator-owned configuration

Alias schema v6 removes receiver-local playback priority from `alias` and `alias_list`. The remaining
`unmatched_talkgroup_record_enabled` field is a compact `0` or `1` value on each `alias_list` row.

`alias_list_unmatched_talkgroup_stream` stores one row per selected stream destination. Its unique
`(alias_list_id, channel_name)` constraint prevents duplicates and supplies the list-prefix access path used when
loading configuration. No name-only query exists, so no separate `channel_name` index is created. A typical list has
zero to four routes; the web request is capped at 64. These rows are created only by an administrator, remain until
the policy is changed, and are deleted by cascade with their alias list. They do not grow with calls or receiver
uptime.

`scan_list` stores administrator-owned definitions with a durable integer ID, display order, case-insensitive unique
name, optional bounded description, published flag, and default flag. A fresh database contains one published
`Default` definition. The immutable runtime model requires exactly one default, while
`idx_scan_list_one_default` prevents more than one default in storage. The administration boundary caps the catalog
at 100 definitions.

`alias_scan_list_membership` stores one row for each selected Alias and scan-list pair. It is `WITHOUT ROWID` with an
Alias-first composite primary key, which supports loading the scan-list IDs for a completed call's durable owner. The
reverse index `idx_alias_scan_list_by_list` supports bounded administrator catalog and membership queries by scan
list. Foreign-key cascades remove memberships when an Alias or scan list is deleted.

`alias_list_unmatched_talkgroup_scan_list_membership` stores one row for each Alias List global unmatched-talkgroup
route and scan-list pair. It is `WITHOUT ROWID` with an Alias-List-first composite primary key, which supports one
lookup when a completed call's frozen destination status is unmatched. The reverse index
`idx_alias_list_unmatched_talkgroup_scan_list_by_list` supports scan-list administration and member counts. Foreign-key
cascades remove routes when either owning definition is deleted. A matched exact or covering range Alias suppresses
the global fallback; source-radio matches alone do not. Duplicate routes from matched Aliases, unmatched policies, or
multiple receiver contexts are folded into one scan-list ID before delivery.

These are configuration rows, not call history. Their row count is bounded by the administrator-owned Alias catalog
multiplied by the capped scan-list catalog; calls never insert or update them. Runtime routing loads the membership
map into one immutable snapshot, so completed-call matching does not query SQLite. Rows remain until an administrator
changes membership or deletes an owning configuration object.

## Qualifier-safe P25 identity summaries

P25 activity schema v26 extends the existing `trunked_identity_summary` row with four integer fields:

- a state code for unknown, ordinary, stable fully-qualified, or ambiguous identity evidence; and
- nullable home WACN, System ID, and talkgroup ID values, present only for stable fully-qualified evidence.

For positive local IDs, repeated observations update the same
`(scope_id, identity_kind_code, identity_id)` summary. The four qualifier fields add no event rows or indexes and add
approximately 4 to 24 bytes to an existing row depending on SQLite varint sizes and record-header overhead; ordinary
rows normally use only the compact state value plus null markers. The existing defensive limit remains 100,000
identity rows per scope, so the new fields add at most a few MiB even at that abnormal saturation point.

A valid fully-qualified P25 talkgroup can use local ID zero. The existing summary key cannot represent this safely:
one `(scope, talkgroup, local ID)` row would collapse every different home tuple observed with local ID zero. The
dedicated `p25_zero_local_fq_talkgroup_summary` instead stores one `WITHOUT ROWID` row keyed by
`(scope_id, home_wacn, home_system_id, home_talkgroup_id)`. It accepts only local ID zero with WACN
`0..0xFFFFF`, System ID `0..0xFFF`, and home talkgroup `1..0xFFFE`; home talkgroup zero and `0xFFFF` are reserved,
and invalid or incomplete tuples are normalized to unknown evidence instead of becoming alias candidates.

Each tuple row is a mutable summary containing first/last observation times, the fixed Activity action counters
(including calls), and encrypted, recorded, and streamed counts. Initial activity, an untracked mid-call continuation,
late call attribution, and completed output update that one summary without creating immutable call or event rows.
New tuple admission is independently capped at 100,000 rows per scope; existing tuples continue updating at the cap.
Budgeting approximately 250–400 bytes per tuple row and its two indexes gives a conservative saturated estimate of
25–40 MiB per scope, although normal systems should remain far below that defensive limit.

P25 identity scopes are linked systems, not individual receiver sites. If several receiver contexts for one P25
WACN/System use different alias lists, each selected list can see the same system-wide observations once that list is
attached to a context in the scope. DMR and NXDN trunked scopes remain receiver-context owned. Conventional discovery
uses the existing DMR talkgroup summaries and protocol-neutral hourly call-identity buckets.

## Write rate and retention

Discovery adds no writes. It reads summaries already maintained for the Systems and Conventional pages. P25
qualifier evidence is carried through the existing bounded statistics queue and single database writer, then merged
into the applicable mutable identity or zero-local tuple row as call, signaling, recording, and streaming counters.
Normal steady-state row creation therefore remains zero after identities are learned; initial discovery is bounded
by the separate 100,000-row-per-scope admission limit for each summary.

Identity summaries and conventional summaries use the configured Statistics retention period, from 1 through 365
days. Hourly call-identity buckets use the same cutoff. Zero-local tuple cleanup selects at most 1,000 expired keys per
statement through `idx_p25_zero_local_fq_retention(last_seen_ms, scope_id, home_wacn, home_system_id,
home_talkgroup_id)` and repeats bounded batches until current. Site clear removes the tuple rows when the last context
for their scope is cleared, via the scope foreign-key cascade; full statistics reset removes them explicitly. Alias
policies and scan-list memberships are configuration and remain until an administrator changes or deletes them.

## Query access path

The endpoint first resolves one alias list by primary key. Its trunked branch selects only scopes owned by a receiver
context configured with that list, using
`idx_trunked_identity_scope_context_scope(scope_id, context_id)`, then reads talkgroup and patch rows through the
`trunked_identity_summary` primary-key scope prefix. For P25, it unions zero-local fully-qualified tuples through
`idx_p25_zero_local_fq_scope_last_seen(scope_id, last_seen_ms DESC, home_wacn, home_system_id,
home_talkgroup_id)` and returns them as local talkgroup ID zero with stable fully-qualified state and the complete home
tuple. Two different tuples remain separate, and an ordinary talkgroup whose local number equals one tuple's home
talkgroup remains a separate observation. The decoded home tuple remains diagnostic evidence only. Discover creates
ordinary P25 aliases from usable local talkgroup IDs; zero-local tuples remain review-only and can never create a
talkgroup-zero alias. Conventional DMR reads the
`dmr_conventional_talkgroup_summary` context-key primary key. Conventional P25 and NXDN read the
`call_identity_bucket` context-key primary key. Exact-alias checks use the existing alias matcher indexes.

The response uses the shared server-side page limit of 500 rows. The query never reads `p25_activity_event` or
depends on optional detailed history. Representative-volume tests explain the production SQL and require indexed
scope, context, summary, bucket, and alias searches with no detailed-event access.

Startup validation checks the tuple table's exact DDL and column set, its four-column primary key, its cascading scope
foreign key, and both indexes including column order and descending recency direction. Normal application services do
not create or repair a missing or mismatched table or index.

## Alpha 8/Alpha 9/Alpha 10 layout to current migration boundary

The bundled Application Migrator converts one exact Alias v4/P25 v24 source layout directly to current Alias v6/P25
v27 on a backed-up staged copy. Alpha 8, Alpha 9, and Alpha 10 shipped that same layout and schema fingerprint, with
no stored release provenance, so an exact profile from any of those releases satisfies the same single source gate. It converts only
one plain, structurally unambiguous full-domain talkgroup range per list; styled, multiple, or Stream As catch-alls
remain aliases for manual review. The v24 shared projection cannot establish qualifier-safe P25 history, so the clean
direct migration rebuilds that shared storage and projected P25, DMR, and NXDN identity history restarts. It then
recreates only the compact ordinary P25 identities and relationships required by preserved authoritative P25
affiliations. The zero-local tuple projection starts empty.

Existing system-wide P25 affiliations are re-keyed into the protocol-neutral affiliation table. Authoritative site
presence and presence-lifecycle state start empty because neither call history nor a source affiliation row proves
which site last accepted a radio or when it deregistered. Supported administrator-owned aliases and unrelated
configuration remain intact. Stored P25 fully-qualified talkgroup and radio Alias rows and their dependent routes are
removed; their home values are not converted into ordinary local aliases. Intermediate development schemas are not
accepted.

## Development migration boundary

Alias v6 and P25 activity v27 are the clean current schemas during unreleased development. Normal startup validates
them and does not upgrade an Alias v5 database. Existing Alias v5 development profiles require the narrowly scoped
external staged-copy candidate before they can use this branch. The candidate creates `Default`, maps every Alias
whose effective priority is not `-1` into it, maps every Alias List whose unmatched-talkgroup priority is not `-1` to
the same list, and then removes both receiver-local playback-priority columns. This preserves the prior per-Alias and
global unmatched listen-enabled populations as browser scan-list memberships. Development candidates remain outside
the repository and are not deployed product support.

During numbered-release preparation, the exact preceding public schema must be compared with the final release
schema and the required transition consolidated into the backed-up Application Migrator. Intermediate
development-only paths are discarded rather than accumulated in normal startup.
