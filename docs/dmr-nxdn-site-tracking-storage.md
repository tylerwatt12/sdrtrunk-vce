# DMR and NXDN Site Tracking Storage

## Website functions

The persistent records in this design serve seven bounded website queries:

1. The Systems & Sites directory lists DMR and NXDN systems with their observed receiver sites.
2. A site information page shows the most recently decoded identity and service details.
3. A site channel page lists the bounded set of logical channels or repeaters observed for that site.
4. A site neighbors page lists the bounded set of adjacent sites advertised by that site.
5. The shared Quality page charts retained control-channel signal and decode health for P25, DMR, and NXDN sites.
6. A conventional DMR channel page lists talkgroups observed on each carrier and timeslot.
7. A conventional DMR channel page lists source and private-call target radios observed on each carrier and timeslot.

Live calls, current control-channel signal, decode health, and full protocol metadata use the bounded `/live/systems`
and `/live/sites` in-memory streams. Persistent site details are exposed through `/api/system-directory`, `/api/site`,
`/api/site/channels`, `/api/site/neighbors`, and the protocol-neutral `/api/quality` response. Conventional DMR
talkgroup and radio views use `/api/conventional/talkgroups` and `/api/conventional/radios` to read the compact
summaries described below. Both require one receiver context, default to 100 rows, and enforce the shared 500-row
server maximum. This design does not retain raw messages, per-call rows, site-change events, JSON, or general trunked
DMR/NXDN activity history.

## Tables

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
own all SQLite work. A changed snapshot writes the site summary and upserts its bounded channel and neighbor facts in
one transaction. Each child carries its own last-observed timestamp, so replaying a cumulative site snapshot does not
refresh an old channel or neighbor. Child facts older than the active retention cutoff are not reinserted. An unchanged
five-second liveness publication updates the site row's `last_seen_ms` and observation counter. It also refreshes at
most one synthetic current-control row when that row represents the receiver's actively tuned configured frequency;
learned channels and neighbors are not refreshed by the heartbeat.

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
at v2, so no public v1 migration is supported. The bundled Application Migrator adds v2 when the subsystem is absent,
and it does so only in a backed-up staged copy before validation and atomic promotion. An existing active database is
otherwise validation-only at startup. The P25 activity schema is v21; the same Application Migrator updates supported
v19 or v20 databases. Conventional DMR summaries use the independent `dmr_activity_schema_version=1` subsystem. New
databases create it in the same global routine; existing supported databases receive it only through the backed-up,
staged Application Migrator.

## Retention

These are mutable summaries, not time-series events, and they use the existing Statistics retention setting:

- channel and frequency facts with `last_seen_ms` older than the cutoff are deleted independently;
- neighbor facts with `last_seen_ms` older than the cutoff are deleted independently;
- site rows with `last_seen_ms` older than the cutoff are deleted after child cleanup, with the foreign key cascade
  removing any remaining descendants.
- conventional DMR talkgroup and radio summaries with `last_seen_ms` older than the cutoff are deleted independently.

Each SQL delete selects at most 1,000 rows through its time-first index. A maintenance pass repeats bounded batches until
the expired set is empty. Cleanup runs through the single statistics database writer at startup, periodically while the
application runs, immediately after retention is reduced, and while new statistics collection is disabled. Thus an
active site can retain current facts while obsolete frequencies and neighbors age out, and a removed receiver GUID
eventually disappears.

Site-specific clear and full statistics reset remove these learned rows consistently with P25. They do not delete
administrator-owned channels, aliases, preferences, or settings. Conventional DMR adds only the two bounded identity
summaries; no trunked DMR/NXDN talkgroups, radios, calls, raw messages, JSON payloads, or permanent call history are
added.

## Query-plan verification

Representative-volume tests must populate 100 sites, 102,400 channel facts, and 25,600 neighbor facts and assert:

- GUID site lookup uses the `trunked_site_snapshot` primary-key index;
- channel lookup uses the channel table primary key with `guid=?`;
- neighbor lookup uses the neighbor table primary key with `guid=?`;
- each bounded retention selection uses its corresponding `last_seen_ms` index and does not scan the summary table;
- conventional DMR recent-context queries use their `context_id, last_seen_ms` indexes;
- conventional DMR retention selections use their time-first indexes, and admission never exceeds 4,096 talkgroups
  or 32,768 radios per context;
- every API limit is bounded even when the database contains more rows.

The directory's bounded summary-table scan is intentional: it reads at most one compact row per configured site and
does not touch channel, neighbor, call, or detailed-event tables.

The conventional DMR plan fixture fills one context to both admission caps. SQLite reports
`SEARCH ... USING COVERING INDEX idx_dmr_conventional_*_context (context_id=?)` for recent identity lists and
`SEARCH ... USING COVERING INDEX idx_dmr_conventional_*_last_seen (last_seen_ms<?)` for retention selection. These
plans avoid full summary-table scans at the documented worst-case per-context volume.

## Shared control-channel quality buckets

P25, DMR, and NXDN use the same compact 10-second control-channel quality bucket shape. The deployed
`p25_control_channel_quality` table already keys every bucket by receiver GUID and frequency and has no P25 identity
foreign key, so it is used as the single shared quality store. Its historical name is retained to avoid a data-moving
schema migration that would change no row shape or query. The API joins each GUID to the appropriate protocol-specific
site summary and exposes one response contract.

The concrete website queries are a GUID-scoped latest-sample lookup and a bounded, server-aggregated time range for the
Quality charts. `idx_p25_control_quality_guid_time(guid, observed_at_ms DESC)` supports both site lookups. The API caps
the requested range at the configured Statistics retention period and caps the returned chart resolution at 1,000
points. Retention is a separate all-site access path, so v21 adds the covering
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
GUID-keyed table. New databases create the v21 index in the single startup schema routine. The Application Migrator
adds it to supported v19 and v20 databases only on a backed-up staged copy; ordinary application services never create
or repair the index.
