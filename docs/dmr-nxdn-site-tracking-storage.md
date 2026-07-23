# DMR and NXDN Site Tracking Storage

## Website functions

The persistent records in this design serve four bounded website queries:

1. The Systems & Sites directory lists DMR and NXDN systems with their observed receiver sites.
2. A site information page shows the most recently decoded identity and service details.
3. A site channel page lists the bounded set of logical channels or repeaters observed for that site.
4. A site neighbors page lists the bounded set of adjacent sites advertised by that site.

Live calls, current control-channel signal, decode health, and full protocol metadata use the bounded `/live/systems`
and `/live/sites` in-memory streams. Persistent site details are exposed through `/api/system-directory`, `/api/site`,
`/api/site/channels`, and `/api/site/neighbors`. This design does not retain raw messages, per-call rows, site-change
events, JSON, or DMR/NXDN control-channel quality history.

## Tables

### `trunked_site_snapshot`

One mutable summary row per configured receiver GUID for DMR or NXDN. The row stores compact integer protocol and
variant codes, decoded numeric identity fields, configured labels once, current control frequency, current compact
service/status values, first/last observation times, an observation counter, and a snapshot hash.

Concrete queries:

- lookup by `guid` for the site information page;
- bounded scan ordered by protocol and decoded identity for the Systems & Sites directory.

Expected cardinality is one row per configured DMR/NXDN parent channel. A large receiver with 100 such channels stores
100 rows. Rows are retained while statistics are retained; they are updated in place and do not grow with time.

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
sites. Rows are updated in place and do not grow with repeated observations.

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
sites. Rows are updated in place and do not grow with repeated observations.

## Write behavior

Decoder threads publish immutable snapshots only. The existing bounded statistics queue and single background writer
own all SQLite work. A changed snapshot writes the site summary and upserts its bounded channel and neighbor facts in
one transaction. An unchanged five-second liveness publication updates only the site row's `last_seen_ms` and
observation counter.

The five-second liveness interval produces 17,280 heartbeat site-row updates per day per continuously active receiver
GUID. At the defensive example size of 100 stable active sites, that is 20 site-row updates per second, or 1.728 million
updates per day; decoded configuration changes can add site and child-row updates between heartbeats. These are in-place
updates handled by the shared batch writer, not new history rows. The interval keeps persistent `last_seen_ms` close
enough to live state to distinguish a recently active receiver from an offline one; channel and neighbor rows are not
rewritten unless their stable snapshot hash changes.

No normal runtime path creates or repairs these tables. New databases create the independent
`trunked_site_schema_version=1` subsystem in the single startup schema routine. Existing P25 v20 databases require a
backed-up, staged, out-of-process trunked-site-v1 migration followed by schema validation, `foreign_key_check`,
`quick_check`, and atomic replacement. The P25 schema remains v20.

## Retention

These are current/lifetime summaries, not time-series events. Normal detailed-history retention does not delete them.
Explicit statistics reset removes all three tables' rows. Removing a configured channel does not immediately erase its
last learned site; the directory continues to show the last observation time so an offline or retired receiver remains
diagnosable.

## Query-plan verification

Representative-volume tests must populate 100 sites, 102,400 channel facts, and 25,600 neighbor facts and assert:

- GUID site lookup uses the `trunked_site_snapshot` primary-key index;
- channel lookup uses the channel table primary key with `guid=?`;
- neighbor lookup uses the neighbor table primary key with `guid=?`;
- every API limit is bounded even when the database contains more rows.

The directory's bounded summary-table scan is intentional: it reads at most one compact row per configured site and
does not touch channel, neighbor, call, or detailed-event tables.
