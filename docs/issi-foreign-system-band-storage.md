# ISSI Foreign-System Band Storage

The ISSI pages can show band plans advertised for P25 systems other than the one being monitored. This reference
explains how VCE retains that received evidence and identifies the channel that heard it. For the related radio and
Working-ID resources, see the [Web API reference](api-v1.md); setup and listening guides are in the
[documentation index](README.md).

## User-visible purpose

A P25 Phase 1 control channel can advertise a band plan for a different WACN and System ID. Each fact remains stored
under the saved channel that heard it, preserving observation provenance. The website browses those facts in the
owning P25 radio system's bounded **ISSI** tab and resources:

- `/api/v1/radio-systems/{radio_system_key}/issi/foreign-systems` summarizes foreign WACN/System pairs discovered
  from canonical subscriber or call evidence, or from advertised band plans.
- `/api/v1/radio-systems/{radio_system_key}/issi/frequency-bands` lists their band definitions and the saved channel
  that supplied each observation.

The channel neighbor resource remains about RF-site neighbors, and the channel frequency-band resource remains the
monitored channel's effective home band plan.

The ordinary home band table cannot own these facts because home and foreign systems can reuse the same four-bit band
ID. Several foreign systems can also reuse it.

## Compact schema and storage budget

`p25_foreign_system_band` holds current stabilized facts.
`p25_foreign_system_band_summary` retains first/last-seen timestamps and an observation count. Both are
`WITHOUT ROWID` tables with the natural key:

```text
(channel_id, foreign_wacn, foreign_system_id, band)
```

`channel_id` is an internal foreign key to `p25_site_snapshot(channel_id)` with cascading deletion. The web and API
identify the owner with the saved channel's `configuration_id` instead of exposing the numeric row ID.

The payload is numeric: channel type, base frequency, spacing, transmit offset, and timestamps. Mode, bandwidth,
timeslots, and voice rate are derived at presentation time. No labels, decoder messages, JSON payloads, or immutable
per-call rows are copied into these tables.

Repeated broadcasts update the same rows, so retained-row growth stops after a band is learned. One foreign system
can advertise at most 16 band IDs, producing at most 16 current and 16 summary rows per monitored-channel/foreign-
system pair. A typical channel advertising two foreign systems with one or two bands uses four to eight rows total.

P25 site snapshots are published at most once every five seconds per monitored channel, or 12 snapshots per minute.
A repeated snapshot updates existing natural keys; it does not create another history row. A newly observed foreign
system/band pair creates one row in each table. The tables do not have a separate admission limit because every key is
protocol evidence used by the two website views. Their hard growth boundary is the configured time retention window,
and their write path is also protected by the receiver's bounded statistics queue.

A dense SQLite fixture with 65,536 distinct one-band foreign-system pairs measured approximately 58.4 bytes per
current row including its retention index and 68.4 bytes per summary row including its retention index. Both tables
together used about 8.3 MB for that fixture. This is a sizing estimate rather than a file-size guarantee: page fill,
SQLite version, and surrounding tables affect the actual database size.

## Retention and write path

The decoder publishes typed facts through the bounded statistics queue. The background statistics writer owns all
SQLite work. Current rows use `confirmed_at_ms` retention; summaries use `last_seen_ms`. Clearing one channel or all
Statistics removes both tables through the same channel-owned lifecycle. The normal fresh-profile retention setting
is 30 days and existing validated profile settings are preserved; the maintenance path enforces at least one day.

New databases create the exact current definitions in the global startup schema routine. Existing supported
databases change only through the bundled Application Migrator. Current-profile upgrades offer an optional recovery
snapshot; external imports use a staged copy. Normal application startup validates and never
creates or repairs these tables.

## Query access path

The Band Plans query resolves `radio_system_key`, joins its saved channels, and constrains each result to those
channel-owned rows. The Foreign Systems query also combines canonical subscriber and call evidence for foreign home
systems. This gives one system-wide ISSI view without discarding channel provenance or changing the band-storage key.
The natural primary key serves exact channel/system/band reads. Separate time-first indexes serve bounded retention
deletes:

```text
SEARCH p25_foreign_system_band_summary USING PRIMARY KEY (channel_id=?)
SEARCH p25_foreign_system_band USING PRIMARY KEY
  (channel_id=? AND foreign_wacn=? AND foreign_system_id=? AND band=?)
SEARCH p25_foreign_system_band USING INDEX idx_p25_foreign_system_band_retention
  (confirmed_at_ms<?)
SEARCH p25_foreign_system_band_summary USING INDEX idx_p25_foreign_system_band_summary_retention
  (last_seen_ms<?)
```

Representative-volume query-plan tests cover the detailed frequency-band read and both retention paths. The ISSI
Foreign Systems and Band Plans responses remain independently limited, so a large retained set cannot produce an
unbounded response.
