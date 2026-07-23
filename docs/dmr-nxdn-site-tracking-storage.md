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

No normal runtime path creates or repairs these tables or indexes. New databases create the independent
`trunked_site_schema_version=2` subsystem in the single startup schema routine. Existing v1 databases require the
explicit external `TrunkedSiteV2DatabaseMigration` command while sdrtrunk-vce is stopped. That command checkpoints the
database after acquiring the portable data root's operating-system lock, creates a timestamped standalone file in the
database directory's `backups` folder, migrates a staged copy, validates the schema plus `integrity_check` and
`foreign_key_check`, and atomically replaces the original only after all checks pass. It refuses to run when the app
holds the lock or when the argument is not the canonical portable `database/sdrtrunk.sqlite` path. The lower-level
staged helper supports absent-to-v2 imports and v1-to-v2 migration but is never invoked by runtime startup. The P25
schema remains v20.

## Retention

These are mutable summaries, not time-series events, and they use the existing Statistics retention setting:

- channel and frequency facts with `last_seen_ms` older than the cutoff are deleted independently;
- neighbor facts with `last_seen_ms` older than the cutoff are deleted independently;
- site rows with `last_seen_ms` older than the cutoff are deleted after child cleanup, with the foreign key cascade
  removing any remaining descendants.

Each SQL delete selects at most 1,000 rows through its time-first index. A maintenance pass repeats bounded batches until
the expired set is empty. Cleanup runs through the single statistics database writer at startup, periodically while the
application runs, immediately after retention is reduced, and while new statistics collection is disabled. Thus an
active site can retain current facts while obsolete frequencies and neighbors age out, and a removed receiver GUID
eventually disappears.

Site-specific clear and full statistics reset remove these learned rows consistently with P25. They do not delete
administrator-owned channels, aliases, preferences, or settings. No DMR/NXDN talkgroups, radios, calls, raw messages,
JSON payloads, or permanent history are added.

## Query-plan verification

Representative-volume tests must populate 100 sites, 102,400 channel facts, and 25,600 neighbor facts and assert:

- GUID site lookup uses the `trunked_site_snapshot` primary-key index;
- channel lookup uses the channel table primary key with `guid=?`;
- neighbor lookup uses the neighbor table primary key with `guid=?`;
- each bounded retention selection uses its corresponding `last_seen_ms` index and does not scan the summary table;
- every API limit is bounded even when the database contains more rows.

The directory's bounded summary-table scan is intentional: it reads at most one compact row per configured site and
does not touch channel, neighbor, call, or detailed-event tables.
