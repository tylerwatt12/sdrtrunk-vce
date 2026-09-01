# DMR and NXDN Site Tracking Storage

## Website functions

The persistent records serve these bounded website functions:

1. The Systems & Sites directory lists P25, DMR, and NXDN identity scopes and their receiver sites.
2. System pages list talkgroups, radios, talker aliases, evidence counters, and call/output totals.
3. Talkgroup and radio pages show one identity, its bounded activity, observed radio-to-talkgroup relationships, and
   authoritative current P25 affiliation/site-presence state.
4. Site pages show the latest decoded identity/service details, learned channels, neighbors, talkgroups, and quality.
5. Conventional DMR pages list carrier/timeslot-scoped talkgroups and radios.

The system APIs use a stored opaque `scope_token`; numeric IDs are never treated as globally unique. The relevant
bounded endpoints are `/api/system-directory`, `/api/system`, `/api/system/sites`, `/api/system/talkgroups`,
`/api/system/radios`, `/api/system/talker-aliases`, `/api/talkgroup`, `/api/radio`, `/api/relationships`, and the
site and conventional endpoints. List endpoints enforce the shared 500-row server maximum. Live calls remain in the
bounded in-memory streams; only compact, authoritative current radio affiliation/presence is persisted.

No table in this design stores raw decoder messages, JSON payloads, or an immutable row per call. Optional detailed
Activity remains separately retention-bound. Dashboard and directory queries read compact summaries or existing
hourly buckets, never the detailed-event table.

## Tables

### `trunked_identity_scope`

One ownership row for a trunked identity namespace. P25 sites with the same established WACN/System ID share one
linked-system scope. Each configured trunked DMR or NXDN receiver owns an independent scope; decoded DMR/NXDN network
numbers do not automatically merge sites. Canonical tokens are:

- `p25:<five-digit WACN hex>:<three-digit system hex>`;
- `dmr:guid:<receiver GUID>`;
- `nxdn:guid:<receiver GUID>`.

The context-ID token fallback is used only when a receiver has no GUID. `protocol_code` uses `1=P25`, `3=DMR`, and
`4=NXDN`; P25 Phase 1 and Phase 2 share the P25 family code. `identity_domain_code` distinguishes standard/unknown,
NXDN Type-C, and NXDN Type-D number interpretation. A unique P25 system key links only P25 scopes.

Concrete queries are unique-token lookup for every system request and the bounded system-directory scan. Expected
cardinality is one row per P25 WACN/System pair plus one row per configured DMR/NXDN trunked receiver: normally tens,
and approximately 100 rows on an unusually large receiver. These ownership rows do not grow with calls. They remain
until the final context mapping is cleared, the owning context is deleted, or statistics are reset.

### `trunked_identity_scope_context`

One row maps each trunked `receiver_context` to its scope. Several P25 contexts can map to one scope; DMR and NXDN
scopes contain one context. The primary key supports context-to-scope resolution, and
`idx_trunked_identity_scope_context_scope(scope_id, context_id)` supports the reverse site list. Site-metadata
ingestion creates this mapping before the first identity-bearing call, so zero-call DMR/NXDN sites still have a
working system page.

Expected cardinality is exactly the number of configured/observed trunked receiver contexts. A mapping is about
40–80 bytes including its reverse-index entry, so even hundreds of contexts remain small. Foreign-key cascades and
the explicit clear/reset paths remove mappings and their final orphaned scope.

### `trunked_identity_summary`

One mutable row per `(scope, identity kind, numeric ID)`. Kinds are talkgroup, radio, and P25 patch group. Each row
contains first/last observation times; fixed integer counters for the supported Activity actions; source and target
call counts; encrypted, recorded, and streamed counts; the latest counterpart and encryption facts; and the latest
typed over-the-air talker alias for a radio. Manual aliases, descriptions, and groups remain administrator-owned
configuration and are joined at read time. P25 schema v26 also stores a compact identity-evidence state and nullable
home WACN/System/talkgroup values so ordinary, stable fully-qualified, and conflicting observations are not collapsed
into one false Alias identity for positive local talkgroup IDs. These are protocol facts, not fully-qualified Alias
matchers.

The fixed action columns avoid a high-cardinality evidence table. The displayed Evidence total sums meaningful
signaling counters and excludes `call_count`, `continue_count`, and `unknown_count`. Unclassified `UNKNOWN` signaling
is not admitted to this directory projection, although optional detailed Activity can retain it.

Concrete queries:

```sql
SELECT ... FROM trunked_identity_summary
WHERE scope_id = ? AND identity_kind_code = ?
ORDER BY last_seen_ms DESC, identity_id
LIMIT ?
```

and a primary-key lookup for one talkgroup or radio. The primary key handles exact lookup;
`idx_trunked_identity_scope_kind_last_seen(scope_id, identity_kind_code, last_seen_ms DESC, identity_id)` handles the
recent directory. Sorts by a counter are restricted to one capped scope and never touch detailed events.

Normal steady-state row creation approaches zero after a system's identities are learned. Initial discovery is
normally tens to thousands of new identities per hour. Defensive admission is capped at 100,000 identity rows per
scope. Existing identities continue updating after the cap; only new keys are refused. Budgeting approximately
320–480 bytes per row including both indexes and the optional P25 qualifier fields gives a conservative saturated
estimate of 32–48 MiB per scope.

### `p25_zero_local_fq_talkgroup_summary`

One mutable row is retained per fully-qualified P25 talkgroup observed with local talkgroup ID zero. The normal
identity key `(scope_id, identity_kind_code, identity_id)` cannot preserve more than one fully-qualified tuple at local
ID zero, so this dedicated `WITHOUT ROWID` table uses
`(scope_id, home_wacn, home_system_id, home_talkgroup_id)` as its primary key. It cascades from the owning scope. WACN
is limited to `0..0xFFFFF`, System ID to `0..0xFFF`, and home talkgroup to `1..0xFFFE`; incomplete or reserved tuples
are not admitted as stable evidence.

Each row stores first/last observation times, the fixed Activity action counters, and encrypted, recorded, and
streamed counts. Initial activity, late attribution, and completed output update the same summary rather than adding
immutable call rows. Existing tuples continue updating after the independent 100,000-row-per-scope admission cap;
only new tuples are refused. Budgeting approximately 250–400 bytes per row and both indexes gives a conservative
saturated estimate of 25–40 MiB per scope.

The primary key supports exact tuple lookup. The bounded discovery query is:

```sql
SELECT ... FROM p25_zero_local_fq_talkgroup_summary
WHERE scope_id = ?
ORDER BY last_seen_ms DESC, home_wacn, home_system_id, home_talkgroup_id
LIMIT ?
```

`idx_p25_zero_local_fq_scope_last_seen(scope_id, last_seen_ms DESC, home_wacn, home_system_id,
home_talkgroup_id)` supports that scan. These rows are diagnostic and review-only because no usable local talkgroup
exists. A qualified observation with a usable local address updates the ordinary P25 talkgroup identity instead.

### `trunked_radio_talkgroup_summary`

One mutable row per observed `(scope, source radio, talkgroup or patch group)`. It stores first/last observation,
the same fixed action counters, encrypted/recorded/streamed counts, and latest encryption facts. It means “observed
relationship,” not “currently affiliated”: a call or signaling observation proves that the identities appeared
together but does not prove current registration state. Current affiliation and last confirmed site presence are
stored separately below. DMR/NXDN evidence is not promoted to current state without a trustworthy accepted/cleared
lifecycle.

The primary key supports a radio's group list. `idx_trunked_radio_talkgroup_reverse(scope_id, talkgroup_id,
target_kind_code, last_seen_ms DESC, radio_id)` supports a talkgroup's radio list. Normal systems create dozens to a
few thousand new relationships per hour during discovery and few new rows once stable. Admission is capped at
500,000 rows per scope, with existing rows continuing to update. Budgeting approximately 180–280 bytes per row and
indexes gives a conservative saturated estimate of 90–140 MiB per scope.

Together, the three saturated identity and relationship summaries are conservatively bounded at roughly 145–230 MiB
per scope. Normal systems should remain far below those defensive limits. The admission checks use the scope prefix
of each table's primary key and run only for a previously unseen key.

### `trunked_radio_affiliation`

One mutable row per `(scope, radio)` stores the radio's last explicitly accepted or confirmed talkgroup affiliation.
The row contains only the talkgroup ID and confirmation time. Registration-only messages do not erase it. Deleting
one receiver/site context from a shared P25 system does not erase the system-wide affiliation, while a
timestamp-guarded deregistration does. Calls, grants, talker aliases, packet traffic, generic observations, and patch
membership never create or change this state.

Talkgroup-radio lookup uses
`idx_trunked_radio_affiliation_talkgroup(scope_id, talkgroup_id, confirmed_at_ms DESC, radio_id)`; system-radio and
radio-detail queries use the primary key. Cleanup uses
`idx_trunked_radio_affiliation_retention(confirmed_at_ms, scope_id, radio_id)` in 1,000-row batches. There is no
unpaged affiliation endpoint.

Cardinality cannot exceed the 100,000-radio-per-scope identity ceiling, and new signaling updates the same row rather
than appending history. At roughly 50–100 bytes per row including indexes, the deliberately pessimistic saturated
cost is approximately 5–10 MiB per scope. Rows age out with the Statistics retention window, disappear with their
owning scope, or are removed by authoritative deregistration/reset.

### `trunked_radio_site_presence`

One mutable row per `(scope, radio)` stores the receiver context where an accepted registration or accepted/confirmed
affiliation was last decoded, its compact evidence code (`registration` or `affiliation`), and confirmation time. The
context is the opaque site reference; protocol-native P25 RFSS/Site identifiers and administrator-configured names are
joined at read time. A newer authoritative event moves the row. Equal-time observations use deterministic
evidence/context ordering, and deregistration clears the row with a timestamp guard. Calls and other non-authoritative
observations are excluded.

System-radio, talkgroup-radio, and radio-detail queries use the primary key. The bounded system-radio site filter and
site affiliated-radio count use
`idx_trunked_radio_site_presence_context(context_id, confirmed_at_ms DESC, scope_id, radio_id)`; cleanup uses
`idx_trunked_radio_site_presence_retention(confirmed_at_ms, scope_id, radio_id)`. Removing a site cascades this
site-local state without touching independent system-wide affiliation. Cardinality and estimated storage match the
affiliation table: at most 100,000 rows and approximately 5–10 MiB per saturated scope. P25 is the first producer;
DMR and NXDN remain disabled until their decoders expose an equally authoritative accepted/cleared lifecycle.

### `trunked_radio_presence_lifecycle`

One mutable row per `(scope, radio)` that has deregistered stores only the greatest authoritative clear timestamp. A
confirmation must be strictly newer than this watermark, so a delayed or equal-time confirmation cannot recreate
current affiliation or site presence after a clear. This state has no website endpoint and is never presented as
current presence.

A radio adds at most one row after its first clear, and later clears update that row. Admission requires the radio's
existing bounded identity, so cardinality cannot exceed the 100,000-radio-per-scope ceiling during a retention window.
Cleanup selects 1,000 rows at a time through
`idx_trunked_radio_presence_lifecycle_retention(cleared_at_ms, scope_id, radio_id)`; scope deletion also cascades the
row. At roughly 35–70 bytes per row including the index, a saturated scope adds approximately 4–7 MiB.

### Protocol identity limits

The writer applies the same protocol policy to initial calls, late attribution, completed recording/streaming output,
site buckets, directory summaries, and relationships:

- P25 excludes talkgroup zero/`0xFFFF` and radio zero/`0xFFFFFC–0xFFFFFF` from normal directory rows. The bounded
  exception is a stable fully-qualified talkgroup with local ID zero: it is retained only in the tuple-keyed summary
  when its WACN and System ID are in range and its home talkgroup is `1..0xFFFE`.
- DMR accepts real 24-bit talkgroups and radios, including IDs above 65,534, but excludes documented Tier III
  gateway and all-radio addresses.
- NXDN Type-C excludes its reserved/all-group and special infrastructure addresses. NXDN Type-D retains its encoded
  home-repeater plus 11-bit identity space, even when the flattened 16-bit value overlaps a Type-C special value.
- Special identities remain available as Activity/system signaling and do not create normal directory rows.

The existing `call_identity_bucket` remains the bounded hourly source/destination time series for calls, encryption,
recording, and streaming. It is not duplicated by the lifetime directory tables.

### `activity_event_talkgroup_member`

This optional detail companion contains one compact `(event, talkgroup)` row for each valid member talkgroup of a
patch event. It lets a member talkgroup's Activity tab retrieve the original patch event without duplicating that
physical event or its counters. It is written only when detailed Activity history is enabled, references the existing
retention-bound event row, and is deleted automatically with that event. Normal non-patch calls create no rows.

Expected volume is the number of member links in retained detailed patch events: ordinarily zero to a few dozen rows
per hour on a site, and at most the configured detailed-event retention window. The composite primary key prevents
duplicates. `idx_activity_event_member_talkgroup_event(talkgroup_id, event_id)` supports a member's Activity lookup;
the parent event primary key supplies the reverse/cascade path. No alias, message text, or other repeated metadata is
stored.

### `trunked_site_snapshot`

One mutable summary row per configured receiver GUID for DMR or NXDN. The row stores compact integer protocol and
variant codes, decoded numeric identity fields, configured labels once, current control frequency, current compact
service/status values, first/last observation times, an observation counter, and a snapshot hash.

Concrete queries:

- lookup by `guid` for the site information page;
- bounded scan ordered by protocol and decoded identity for the Systems & Sites directory.

Expected cardinality is one row per configured DMR/NXDN parent channel. A large receiver with 100 such channels stores
100 rows. At roughly 200–400 bytes per row plus text payloads, the expected site table remains tens of kilobytes at
that size. Rows are retained for the configured Statistics retention period; they are updated in place and do not grow
with time.

The retention query uses `idx_trunked_site_snapshot_last_seen(last_seen_ms, guid)` to select at most 1,000 expired GUIDs
per delete statement. The `guid` suffix makes the selection deterministic and covering. It adds one compact timestamp
and GUID index entry per site row.

### `trunked_site_channel_summary`

One mutable row per distinct `(guid, channel number, timeslot, frequency)` fact. It stores numeric inbound/outbound
channel numbers, downlink/uplink frequencies, compact role flags, first/last observation times, and an observation
counter.

Concrete query:

```sql
SELECT ... FROM trunked_site_channel_summary
WHERE guid = ?
ORDER BY channel_number, timeslot, frequency_hz
LIMIT ?
```

The primary key begins with `guid`, so this query is index-backed without a second index. Expected normal cardinality is
fewer than 100 rows per site. The defensive design limit is 1,024 rows per site, or 102,400 rows for 100 unusually large
sites. At roughly 80–120 bytes per row, the defensive example is about 8–12 MB before page overhead and indexes. Rows
are updated in place and do not grow with repeated observations.

The retention query uses
`idx_trunked_site_channel_last_seen(last_seen_ms, guid, channel_number, inbound_channel_number, timeslot,
frequency_hz)` to select at most 1,000 expired primary keys per delete statement. This time-first covering index adds
approximately one compact index entry of comparable key size per channel row. It exists specifically because the
GUID-first primary key cannot support retention cleanup without scanning every site's facts.

### `trunked_site_neighbor_summary`

One mutable row per distinct decoded neighbor identity for a receiver GUID. It stores only compact protocol variant,
numeric network/system/site/channel identity, optional frequency, first/last observation times, and an observation
counter.

Concrete query:

```sql
SELECT ... FROM trunked_site_neighbor_summary
WHERE guid = ?
ORDER BY network_id, system_id, site_id, channel_number
LIMIT ?
```

The primary key begins with `guid`, so this query is index-backed without a second index. Expected normal cardinality is
fewer than 32 rows per site. The defensive design limit is 256 rows per site, or 25,600 rows for 100 unusually large
sites. At roughly 100–140 bytes per row, the defensive example is about 2.5–3.5 MB before page overhead and indexes.
Rows are updated in place and do not grow with repeated observations.

The retention query uses
`idx_trunked_site_neighbor_last_seen(last_seen_ms, guid, variant_code, identity_domain_code, network_id, system_id,
site_id, channel_number, frequency_hz)` to select at most 1,000 expired primary keys per delete statement. This
time-first covering index adds approximately one compact index entry of comparable key size per neighbor row and avoids
a full-table retention scan.

### `dmr_conventional_talkgroup_summary`

One mutable row per `(receiver context, frequency, timeslot, talkgroup)` observed on an explicitly conventional DMR
channel. It stores only first/last timestamps, call and encrypted-call counters, and the last source radio ID. The
carrier and timeslot are part of the key because the same numeric talkgroup can be unrelated on different repeaters or
slots.

Concrete query:

```sql
SELECT ... FROM dmr_conventional_talkgroup_summary
WHERE context_id = ?
ORDER BY last_seen_ms DESC, frequency_hz, timeslot, talkgroup_id
LIMIT ?
```

`idx_dmr_conventional_talkgroup_context` supports this bounded recent-activity query. The normal expected cardinality
is tens or hundreds of talkgroups per configured repeater. Admission is defensively capped at 4,096 rows per receiver
context. Existing identities continue to update at the cap; new identities are ignored until retention or an
administrator clear frees capacity. Budgeting 100–140 bytes per table row and context-index entry gives a conservative
upper estimate of roughly 0.4–0.6 MiB per fully saturated context.

### `dmr_conventional_radio_summary`

One mutable row per `(receiver context, frequency, timeslot, radio)` observed on an explicitly conventional DMR
channel. It stores first/last timestamps, total participation, source/target, group/private, and encrypted-call
counters, plus the last talkgroup or private-call peer ID. Both participants in a private call are updated, while a
group call updates its source radio and talkgroup summary. Alias text remains administrator-owned configuration and is
resolved through the channel's stored alias-list name rather than copied into this hot table.

Concrete query:

```sql
SELECT ... FROM dmr_conventional_radio_summary
WHERE context_id = ?
ORDER BY last_seen_ms DESC, frequency_hz, timeslot, radio_id
LIMIT ?
```

`idx_dmr_conventional_radio_context` supports this query. Normal cardinality is expected to be hundreds or a few
thousand radios per receiver context. Admission is defensively capped at 32,768 rows per context with the same
existing-row update behavior as talkgroups. Budgeting 130–180 bytes per table row and context-index entry gives a
conservative upper estimate of roughly 4–6 MiB per saturated context. A pathological installation with 100 contexts
all at both identity caps would therefore consume approximately 440–660 MiB for these summaries and indexes; normal
installations should remain far below that bound.

The existing conventional frequency/hour summaries cannot answer either identity query because they intentionally
contain only action counters by carrier and timeslot. These two compact lifetime tables add the minimum identity
dimensions required by the Conventional page; no radio-to-talkgroup relationship table or per-talkgroup time-series
bucket is stored.

## Write behavior

Decoder threads publish immutable snapshots only. The existing bounded statistics queue and single background writer
own all SQLite work. One writer batch transaction updates physical site/hour totals, the existing hourly identity
bucket, the lifetime identity summaries, zero-local P25 tuples, relationships, and completed output counters together.
A call increments each applicable identity once; retry/fan-out handling occurs before the database writer. Late
attribution moves the already-counted hourly call from unknown to the newly valid identity and enriches the applicable
lifetime or zero-local tuple summary without adding another physical call. Recorded and streamed completion increment
output counters without inferring another call. P25 patch calls intentionally increment the patch and each valid
member talkgroup.

A changed metadata snapshot writes the site summary, ensures its scope mapping, and upserts its bounded channel and
neighbor facts in one transaction. Each child carries its own last-observed timestamp, so replaying a cumulative site
snapshot does not refresh an old channel or neighbor. Child facts older than the active retention cutoff are not
reinserted. A stale site snapshot is rejected before it can change scope ownership or alias-list selection. The latest
accepted snapshot is authoritative for its alias list, including removal of a previously assigned list.

Changing a receiver between DMR and NXDN creates a new protocol-owned context scope. Changing NXDN between Type-C and
Type-D retains physical site/frequency totals but clears identities, relationships, detailed-event identity fields,
and hourly identity buckets learned under the old number interpretation. This prevents the same numeric value from
being reinterpreted as a different subscriber or group. An unchanged five-second liveness publication updates the site
row's `last_seen_ms` and observation counter. It also refreshes at most one synthetic current-control row when that row
represents the receiver's actively tuned configured frequency; learned channels and neighbors are not refreshed by
the heartbeat.

The current `receiver_context` protocol owns site routing. Accepting a P25 site removes an incompatible DMR/NXDN site
projection for the same GUID, and accepting DMR/NXDN removes the incompatible P25 projection. A configured receiver
whose last decoded site snapshot ages out remains listed from its compact context row with zero current observations.

The five-second liveness interval produces 17,280 heartbeat site-row updates per day per continuously active receiver
GUID, plus at most 17,280 updates to its synthetic current-control row. At the defensive example size of 100 stable
active sites, that is 20 site-row updates and at most 20 current-control-row updates per second, or at most 3.456
million compact in-place updates per day; decoded configuration changes can add site and learned-child updates between
heartbeats. These are summary updates handled by the shared batch writer, not new history rows. The interval keeps
persistent site and active configured-control `last_seen_ms` close enough to live state to distinguish a recently
active receiver from an offline one; cumulative learned channels and neighbors are not rewritten unless their stable
snapshot hash changes.

Conventional DMR uses a separate immutable completed-call observation. The mutable decode event may be broadcast many
times while a call is active, but the completion observation is published once when that call closes. Each completed
call performs one conventional carrier/hour counter update, at most one talkgroup upsert, and at most two radio
upserts. Two consecutive calls with identical participants therefore count twice without a time-window dedupe, while
continuation bursts for one call count once. An active conventional repeater averaging one completed call every five
seconds produces 17,280 small in-place summary updates per day plus its source/target identity upserts, not 17,280
immutable rows.

No normal runtime path creates or repairs these tables or indexes. New databases create the independent
`trunked_site_schema_version=2` subsystem in the single startup schema routine. The subsystem was introduced publicly
at v2, so no public v1 migration is supported. Conventional DMR summaries use the independent
`dmr_activity_schema_version=1` subsystem. Protocol-neutral identity storage entered P25 activity schema v24 and
records the positive `trunked_identity_metrics_started_at_ms` boundary so pages do not imply that partially backfilled
DMR/NXDN totals cover time before collection began. Global database format 2 uses P25 activity schema v26, which adds
qualifier-safe P25 talkgroup evidence and the three bounded protocol-neutral current-affiliation,
authoritative-site-presence, and clear-lifecycle tables. New databases create every current subsystem in the same
global routine; existing databases remain validation-only except through the bundled Application Migrator.

Validation includes the exact zero-local tuple and radio-state DDL/column sets, primary keys, cascading foreign keys,
and ordered index definitions. A missing or mismatched table, constraint, key, or index is rejected; normal runtime
paths do not create or repair it.

Schema v24 removed the obsolete `p25_talkgroup_summary`, `p25_radio_summary`, and
`p25_radio_talkgroup_summary` tables rather than permanently dual-writing two directory models. P25 identity data is
projected into the shared tables. Schema v26 likewise replaces `p25_radio_affiliation` instead of retaining a
compatibility table or dual-write path; `p25_system` and P25 site/band/patch facts remain protocol capabilities.

Alpha 8, Alpha 9, and Alpha 10 shipped the same P25 activity schema v24 layout and exact whole-database fingerprint,
with no stored release provenance. The global catalog therefore maps that one exact layout to database format 1
without attempting to distinguish the release labels. Alpha 11's `1 -> 2` step resets reproducible shared trunked
identity evidence and legacy P25 affiliations, then creates the v26 identity, affiliation, site-presence, and
clear-lifecycle state empty so live traffic can rebuild it. Other unchanged bounded activity and supported
administrator-owned configuration are preserved. Pre-Alpha 8, unknown, mixed, and unregistered intermediate layouts
are refused. See [Database Migration Contract](database-migration.md).

## Retention

These are mutable summaries, not time-series events, and they use the existing Statistics retention setting:

- channel and frequency facts with `last_seen_ms` older than the cutoff are deleted independently;
- neighbor facts with `last_seen_ms` older than the cutoff are deleted independently;
- site rows with `last_seen_ms` older than the cutoff are deleted after child cleanup, with the foreign key cascade
  removing any remaining descendants.
- conventional DMR talkgroup and radio summaries with `last_seen_ms` older than the cutoff are deleted independently.
- trunked identity and relationship rows with `last_seen_ms` older than the cutoff are deleted independently;
- zero-local P25 tuple rows with `last_seen_ms` older than the cutoff are deleted independently;
- current affiliation and last confirmed site-presence rows with `confirmed_at_ms` older than the cutoff are deleted
  independently;
- authoritative clear watermarks with `cleared_at_ms` older than the cutoff are deleted independently;
- scope and mapping rows follow their configured context/system ownership lifecycle instead of call-history retention.

After all retention passes, an unconfigured trunked receiver context is removed only when no retained context, GUID,
scope-identity, relationship, affiliation, site-presence, or clear-lifecycle fact still depends on it. A configured
quiet/zero-call receiver is never pruned. Shared P25 scopes retain one deterministic historical owner until their
remaining system evidence expires, preventing both ghost directory rows and accidental deletion of still-retained
system history.

Each SQL delete selects at most 1,000 rows through its time-first index. A maintenance pass repeats bounded batches until
the expired set is empty. Cleanup runs through the single statistics database writer at startup, periodically while the
application runs, immediately after retention is reduced, and while new statistics collection is disabled. Thus an
active site can retain current facts while obsolete frequencies and neighbors age out, and a removed receiver GUID
eventually disappears.

Site-specific clear and full statistics reset remove these learned rows consistently with P25. They do not delete
administrator-owned channels, aliases, preferences, or settings. Clearing the last mapped context removes its
identity scope and cascades its directory, zero-local tuple, relationship, and current-state rows. No raw messages,
JSON payloads, or permanent per-call history are added.

## Query-plan verification

Representative-volume tests must populate 100 sites, 102,400 channel facts, and 25,600 neighbor facts and assert:

- GUID site lookup uses the `trunked_site_snapshot` primary-key index;
- channel lookup uses the channel table primary key with `guid=?`;
- neighbor lookup uses the neighbor table primary key with `guid=?`;
- each bounded retention selection uses its corresponding `last_seen_ms` index and does not scan the summary table;
- conventional DMR recent-context queries use their `context_id, last_seen_ms` indexes;
- conventional DMR retention selections use their time-first indexes, and admission never exceeds 4,096 talkgroups
  or 32,768 radios per context;
- scope-token lookup uses the unique scope-token index, and reverse site lookup uses
  `idx_trunked_identity_scope_context_scope`;
- observed-talkgroup discovery uses that reverse ownership index, the scope/context-leading summary primary keys,
  `idx_p25_zero_local_fq_scope_last_seen` for zero-local tuple discovery, and `idx_alias_talkgroup_value` for exact
  Alias matching; it never reads optional `p25_activity_event` rows;
- identity directory lookup uses `idx_trunked_identity_scope_kind_last_seen`;
- radio-to-group lookup uses the relationship primary key, while group-to-radio lookup uses
  `idx_trunked_radio_talkgroup_reverse`;
- patch-member Activity lookup uses
  `idx_activity_event_member_talkgroup_event(talkgroup_id, event_id)` and then the parent event primary key;
- bounded identity cleanup uses
  `idx_trunked_identity_retention(last_seen_ms, scope_id, identity_kind_code, identity_id)`;
- bounded zero-local tuple cleanup uses
  `idx_p25_zero_local_fq_retention(last_seen_ms, scope_id, home_wacn, home_system_id, home_talkgroup_id)`;
- bounded relationship cleanup uses
  `idx_trunked_radio_talkgroup_retention(last_seen_ms, scope_id, radio_id, talkgroup_id, target_kind_code)`;
- current talkgroup-affiliation lookup uses `idx_trunked_radio_affiliation_talkgroup`, site filtering/counting uses
  `idx_trunked_radio_site_presence_context`, and the time-first affiliation, site-presence, and lifecycle retention
  indexes avoid table scans;
- admission checks use the scope prefix of each primary key, existing rows continue updating at the cap, and new rows
  cannot exceed 100,000 ordinary identities, 100,000 zero-local tuples, or 500,000 relationships per scope;
- every API limit is bounded even when the database contains more rows.

The directory's bounded summary-table scan is intentional: it reads at most one compact row per configured site and
does not touch channel, neighbor, call, or detailed-event tables.

The conventional DMR plan fixture fills one context to both admission caps. SQLite reports
`SEARCH ... USING COVERING INDEX idx_dmr_conventional_*_context (context_id=?)` for recent identity lists and
`SEARCH ... USING COVERING INDEX idx_dmr_conventional_*_last_seen (last_seen_ms<?)` for retention selection. These
plans avoid full summary-table scans at the documented worst-case per-context volume.

The trunked identity plan fixture verifies exact primary-key lookup, both bounded directory directions, all
time-first retention selections including the zero-local tuple and current-state paths, and scope-prefix admission.
`EXPLAIN QUERY PLAN` must report indexed searches for each access path; a scan of optional detailed Activity is never
acceptable for a system, talkgroup, radio, or dashboard summary.

## Shared control-channel quality buckets

P25, DMR, and NXDN use the same compact 10-second control-channel quality bucket shape. The deployed
`p25_control_channel_quality` table already keys every bucket by receiver GUID and frequency and has no P25 identity
foreign key, so it is used as the single shared quality store. Its historical name is retained to avoid a data-moving
schema migration that would change no row shape or query. The API joins each GUID to the appropriate protocol-specific
site summary and exposes one response contract.

The concrete website queries are a GUID-scoped latest-sample lookup and a bounded, server-aggregated time range for the
Quality charts. `idx_p25_control_quality_guid_time(guid, observed_at_ms DESC)` supports both site lookups. The API caps
the requested range at the configured Statistics retention period and caps the returned chart resolution at 1,000
points. Retention is a separate all-site access path, so the schema includes the covering
`idx_p25_control_quality_retention(observed_at_ms, guid, frequency_hz, bucket_start_ms)` index. Cleanup selects at most
1,000 expired primary keys per statement in oldest-first order and drains those bounded batches until current. At
representative volume (100 sites and 102,400 buckets), `EXPLAIN QUERY PLAN` reports
`USING COVERING INDEX idx_p25_control_quality_retention (observed_at_ms<?)` with no quality-table scan.

At most one mutable row is retained per `(guid, frequency, 10-second bucket)`: 360 rows/hour, 8,640 rows/day, 259,200
rows at the default 30-day retention, and 3,153,600 rows at the maximum 365-day retention for a continuously monitored
site. Explicitly trunked DMR channels are accepted immediately; explicitly conventional DMR channels never create
control-channel quality history. NXDN samples are accepted only after the shared metadata classifier has identified a
known trunking variant on that exact running channel and decoder configuration. Evidence remains valid through
sustained decode loss and is cleared by the quality monitor's inactive shutdown snapshot, channel/configuration
replacement, statistics disablement, or writer shutdown. Samples use the existing bounded statistics queue and single
database writer. Existing retention, site-specific clear, and full reset paths already operate on this shared
GUID-keyed table. New databases create the index in the single startup schema routine. The global format-1-to-format-2
migration preserves these quality rows and indexes unchanged. Pre-Alpha 8, unknown, mixed, and unregistered
intermediate schemas are refused. Ordinary application services never create or repair the index.
