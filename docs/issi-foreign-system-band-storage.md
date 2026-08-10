# ISSI Foreign-System Band Storage

## User-visible purpose

P25 Phase 1 control channels can broadcast an extended `IDEN_UP_TDMA` message containing a band plan for a foreign
WACN and System ID. The Stats Server uses these facts for two bounded website queries:

- `/api/v1/sites/{guid}/neighbors` shows one aggregated `ISSI System` row per advertised foreign WACN/System.
- `/api/v1/sites/{guid}/frequency-bands` shows the individual foreign-system band definitions separately from
  home-system bands.

The existing `p25_site_frequency_band` tables cannot safely store these facts because their key is only `(guid, band)`.
Home and foreign systems can reuse the same four-bit band ID, and multiple foreign systems can also reuse it.

## Compact schema and cardinality

`p25_foreign_system_band` holds current stabilized facts. `p25_foreign_system_band_summary` retains first/last seen and
an observation counter. Both use the natural key `(guid, foreign_wacn, foreign_system_id, band)` and `WITHOUT ROWID`.
The payload is numeric: channel type, base frequency, spacing, offset, and timestamps. Mode, bandwidth, timeslots, and
voice rate are derived from the channel-type code at presentation time; no repeated labels or decoded messages are
stored.

Repeated broadcasts upsert the same rows, so the expected retained-row growth rate is zero after a band is first
observed. A foreign system can advertise at most 16 band IDs, producing at most 16 current plus 16 summary rows per
home-site/foreign-system pair. A typical site advertising two foreign systems with one or two bands uses four to eight
rows total. The absolute protocol-space ceiling is 4,096 foreign System IDs times 16 bands per table per home site,
although real networks are expected to remain several orders of magnitude below that ceiling. Each row contains four
key integers, four value/timestamp integers, and SQLite B-tree overhead, with no secondary index.

## Retention and write path

The decoder publishes message-scoped facts through the existing bounded statistics queue and single background writer.
Ordinary runtime services never perform schema migration. Current rows use the standard `confirmed_at_ms` retention
path; summaries use `last_seen_ms`. Site-specific clearing and full statistics reset delete both tables. Alpha 9
shipped P25 activity schema v24. The current unreleased schema is v25 because the existing identity summaries now
retain qualifier-safe P25 talkgroup facts; the foreign-band tables themselves are unchanged. The next numbered
release migration is prepared from the exact preceding public release, and older or intermediate schema combinations
are rejected.

## Query access path

Both website queries constrain `guid`. Because `guid` is the leading primary-key column, SQLite uses the table primary
key directly and no additional index is needed. Representative-volume tests assert plans equivalent to:

```text
SEARCH p25_foreign_system_band_summary USING PRIMARY KEY (guid=?)
SEARCH p25_foreign_system_band USING PRIMARY KEY (guid=? AND foreign_wacn=? AND foreign_system_id=? AND band=?)
```
