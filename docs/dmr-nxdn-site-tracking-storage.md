# Radio System, Site, and Activity Storage

## Purpose

The activity database supports a small set of bounded website and runtime queries:

1. list saved receiver channels and the radio systems they have observed;
2. show talkgroups, radios, affiliations, last-confirmed channel presence, and call/output totals for one radio system;
3. show the latest P25, DMR, or NXDN site facts, learned channels, neighbors, and control-channel quality;
4. show carrier- and timeslot-specific activity for conventional channels; and
5. remove old activity without deleting administrator-owned channel or Alias configuration.

The current model has two plain ownership levels. A `receiver_channel` is one saved channel configuration. A
`radio_system` is a trunked system identity. Every site, quality, conventional, and detailed-activity row belongs to a
receiver channel through its numeric `channel_id`. System-wide trunked summaries belong to a radio system through
`radio_system_id`. Retained detailed events store their observed radio-system and canonical identity references when
known; they do not borrow a channel's later system assignment.

Names, Alias List assignments, decoder choices, the configured primary frequency, and RadioResolve identifiers stay
in the configuration tables. Activity tables do not copy them. Readers join `configuration_channel` when they need
current display or configuration data.

Within `configuration_channel`, the row owns the channel UUID, trunked/conventional classification, display fields,
Alias List ID, optional RadioResolve ID, and auto-start settings; those fields are forbidden in `config_json`.
`decoder_type`, `address_domain_code`, and `primary_frequency_hz` are JSON-authoritative indexed projections and must
match the decoder/source document exactly. The row-owned `channel_kind` is derived through the shared classification
policy on save and checked against that same decoded document on load.

No table described here stores raw decoder messages, a complete JSON object, or an unbounded immutable call log.
Optional detailed Activity is retention-bound. Its P25 source/target references preserve canonical roaming identity,
while observed-local and event-time NAC/RFSS/Site columns preserve only facts seen with that event. Normal statistics
use mutable summaries and hourly or 10-second buckets.

## Saved channels and radio systems

### `receiver_channel`

One row is created when activity is first accepted for a saved channel. `configuration_id` is the canonical saved
channel UUID and has a cascading foreign key to `configuration_channel(configuration_id)`. The row contains only its
numeric ID, first and last observation times, an optional `radio_system_id`, and a monotonic assignment-generation
watermark used to reject stale current-state evidence.

Changing a channel name, system/site label, Alias List, or RadioResolve identifier does not change activity identity.
Deleting the saved channel cascades all channel-owned site, quality, conventional, and detailed activity. Expected
cardinality is one row per observed saved channel: normally tens, and at most the administrator-owned channel count.

### `radio_system`

One row owns protocol-neutral trunked identity summaries. Its stable `system_key` is:

- `p25:<five-digit WACN hex>:<three-digit system hex>` after a complete P25 WACN and System ID are known;
- `dmr:channel:<configuration UUID>` for DMR; or
- `nxdn-c:channel:<configuration UUID>` or `nxdn-d:channel:<configuration UUID>` for the saved NXDN address domain.

Two P25 receiver channels that learn the same complete WACN/System pair share one radio system. DMR and NXDN remain
saved-channel-specific until validated protocol rules can prove a better native identity; their system row has an
explicit cascading configuration owner. Alias Lists never participate in system identity.

`protocol_code` uses `1=P25`, `3=DMR`, and `4=NXDN`. `address_domain_code` describes how subscriber and talkgroup
addresses are interpreted (`standard`, NXDN Type-C, or NXDN Type-D). P25 WACN and System ID are stored only on the
native P25 system row; they are not repeated on `receiver_channel`.

Incomplete P25 observations do not create a provisional radio system. Optional detailed activity remains
channel-owned with a null system reference until complete native identity is observed; system summaries are skipped.
A newer contradictory partial site generation detaches the channel and advances its watermark, so delayed old
current-state evidence cannot reattach it. Complete delayed calls may still update their correctly identified
historical system without moving the channel.

Deleting a DMR or NXDN configuration cascades its channel-scoped system and derived rows coherently. A shared native
P25 row remains while another channel or retained system fact uses it, then bounded maintenance removes it after its
final owner and retained facts are gone. Expected cardinality is one row per established P25 system plus one per
observed configured DMR or NXDN channel. It does not grow with calls.

The directory and site-list access paths use these bounded query shapes:

```sql
SELECT ... FROM radio_system
ORDER BY last_seen_ms DESC, id
LIMIT ?;

SELECT receiver_channel.id, receiver_channel.configuration_id, ...
FROM receiver_channel
JOIN configuration_channel
  ON configuration_channel.configuration_id = receiver_channel.configuration_id
WHERE receiver_channel.radio_system_id = ?
ORDER BY receiver_channel.last_seen_ms DESC, receiver_channel.id
LIMIT ?;
```

## System-wide trunked summaries

### `radio_system_identity_summary`

One mutable row represents a talkgroup, radio, or P25 patch group on one radio system. It contains first/last times,
fixed action counters, logical-call and completed-output counters, optional latest counterpart/encryption data, and a
latest over-the-air talker alias. Administrator aliases remain in the Alias tables and are resolved at read time.

The authoritative natural identity is `(radio_system_id, identity_kind_code, home_wacn, home_system_id,
identity_id)`. P25 ordinary identities use the serving WACN/System as their home tuple; valid fully-qualified identities
use their decoded home tuple. DMR and NXDN leave the home tuple null. A surrogate row ID gives relationships and events
one compact foreign key. P25 local alias zero remains observation/display evidence and never replaces the positive
canonical home identity, so unrelated roaming identities cannot collapse.
The canonical summary deliberately stores no system-wide "last local ID"; local aliases are authoritative only in
the channel-scoped event, site bucket, presence, and affiliation rows that observed them.

The recent-identity and retention indexes begin with `radio_system_id` or `last_seen_ms` for the two concrete access
paths. New identities are capped at 100,000 rows per system; existing rows continue updating at the cap. Retention
uses the shared bounded maintenance pass described below.

```sql
SELECT ... FROM radio_system_identity_summary
WHERE radio_system_id = ? AND identity_kind_code = ?
ORDER BY last_seen_ms DESC, identity_id
LIMIT ?;
```

### `trunked_radio_group_summary`

One mutable row stores an observed radio-to-group relationship. `group_kind_code` distinguishes an ordinary talkgroup
from a patch group. `radio_identity_id` and `group_identity_id` reference the two canonical summary rows; observed
local addresses remain presentation evidence elsewhere. The row means the pair was observed; it does not claim that
the radio is currently affiliated. Exact radio and group directions are index-backed. Admission is capped at 500,000
rows per system, existing rows continue updating, and retention removes expired rows.

### `trunked_radio_affiliation`, `trunked_radio_channel_presence`, and
`trunked_radio_channel_presence_clear`

These tables hold compact current state, not event history:

- affiliation is the latest explicitly accepted or confirmed talkgroup for a radio;
- channel presence is the receiver channel that decoded the latest authoritative registration or affiliation; and
- channel-presence clear is a bounded per-channel canonical/local-alias watermark that prevents a delayed observation
  from recreating cleared state without suppressing the same local number on another receiver channel.

Affiliation, channel presence, and clear watermarks have composite foreign keys requiring their `channel_id` to belong
to the same `radio_system_id`. Calls, generic observations, and talker aliases do not invent current affiliation or
channel presence. Current presence and affiliation have at most one row per canonical radio and system; clear rows
are keyed by radio, system, and observing channel. Time-first indexes support bounded retention batches.

### Logical-call and P25 learned-site buckets

`trunked_logical_call_bucket` and `trunked_logical_call_identity_bucket` store hourly resolved-call totals by system.
`p25_learned_site`, `p25_site_call_bucket`, and `p25_site_call_identity_bucket` add an RFSS/Site dimension when it is
known. Composite foreign keys prevent a learned site from being paired with another radio system. A call updates a
fixed hourly key; it does not append an immutable row.

## Site snapshots

### P25 site tables

`p25_site_snapshot` has one latest row per receiver channel. Its children store the current and retained summaries for
channels, channel tags, frequency bands, foreign-system bands, neighbors, and patch members. Every child cascades
from the channel-owned snapshot. The schema validates P25 field widths, canonical SHA-256 snapshot hashes, booleans,
frequencies, timestamps, timeslot counts, patch versions, and radio/talkgroup ranges.

The page queries one `channel_id` and reads child rows through primary keys beginning with that ID. Normal systems
have tens of channels and neighbors. Repeated observations update existing keys. The Statistics retention pass ages
summary children independently and deletes the parent when it is no longer current.

```sql
SELECT ... FROM p25_site_channel_summary
WHERE channel_id = ?
ORDER BY channel_key
LIMIT ?;
```

### `trunked_site_snapshot`

One mutable row per DMR or NXDN receiver channel stores compact decoded site identity, service/status values,
frequencies, first/last times, an observation counter, and a canonical snapshot hash. DMR model/brand/mode data and
NXDN location category are separate fields: `location_category_code` never carries a DMR model value.

The table permits only protocol-appropriate combinations. DMR uses variants `0..5`, no NXDN RAN or System ID, and
native timeslots 1 or 2. NXDN uses variants `0..2`, location categories `0..5`, optional RAN `0..63`, and no DMR
model, brand, channel-type, or color-code fields.

The table is updated in place, so its cardinality is one row per observed saved DMR/NXDN channel. A continuously
active receiver can refresh that row every five seconds (17,280 in-place updates per day) without creating new rows.

### `trunked_site_channel_summary` and `trunked_site_neighbor_summary`

These tables store one mutable row per distinct learned channel or neighbor key for a DMR/NXDN receiver channel.
Their primary keys start with `channel_id`, which serves the site-page lookup. Time-first indexes serve retention.
Repeated cumulative snapshots do not refresh an old child unless that child is observed again.

Admission is capped at 1,024 channel facts and 256 neighbor facts per receiver channel. At an unusually large 100-site
installation, the defensive maximum is therefore 102,400 channel rows and 25,600 neighbor rows. Old rows are removed
through the shared bounded pass.

```sql
SELECT ... FROM trunked_site_channel_summary
WHERE channel_id = ?
ORDER BY channel_number, timeslot, frequency_hz
LIMIT ?;

SELECT ... FROM trunked_site_neighbor_summary
WHERE channel_id = ?
ORDER BY network_id, system_id, site_id, channel_number
LIMIT ?;
```

## Conventional activity

General conventional action totals use `conventional_activity_summary` and `conventional_activity_bucket`, keyed by
saved `channel_id`, carrier frequency, and optional native timeslot. The hourly bucket may use frequency zero only for
an event that identified its saved channel before a carrier frequency was available; the lifetime per-frequency
summary requires a positive frequency.

Conventional DMR additionally maintains:

- `dmr_conventional_talkgroup_summary`, capped at 4,096 identities per receiver channel; and
- `dmr_conventional_radio_summary`, capped at 32,768 identities per receiver channel.

Both keys include frequency and native timeslot 1 or 2, so the same numeric address on another carrier or slot stays
separate. Each completed call updates at most one talkgroup and two radio summaries. No per-call relationship table is
created. Existing rows continue updating at the cap, and time-first indexes support bounded retention.

```sql
SELECT ... FROM dmr_conventional_talkgroup_summary
WHERE channel_id = ?
ORDER BY last_seen_ms DESC, frequency_hz, timeslot, talkgroup_id
LIMIT ?;
```

## Control-channel quality

`trunked_control_channel_quality` is shared by P25, DMR, and NXDN. It stores at most one mutable sample per
`(channel_id, frequency, 10-second bucket)`. A continuously monitored channel can therefore hold 360 rows/hour,
8,640/day, 259,200 at the default 30-day retention, or 3,153,600 at the maximum 365-day retention.

The channel-and-time index serves a site's latest and chart queries. The time-first covering index lets maintenance
select a bounded set of expired keys without scanning the table. Samples travel through the bounded statistics queue and
single database writer; decoder and tuner threads never wait for SQLite.

```sql
SELECT ... FROM trunked_control_channel_quality
WHERE channel_id = ? AND observed_at_ms BETWEEN ? AND ?
ORDER BY observed_at_ms
LIMIT ?;
```

## Write, clear, and retention behavior

Decoder threads publish immutable observations to a bounded queue. One background writer performs all database work
and coalesces noisy updates. A stale site snapshot is rejected before it can change ownership. Late call attribution
moves or enriches already-counted summaries rather than counting another physical call. Recorded and streamed output
updates output counters without inventing another call.

The Statistics retention setting is 1 through 365 days. Time-based tables use ordered indexes. One routine
maintenance pass deletes at most 1,000 rows in total across the entire activity database, with at most 256 direct
deletes from any one task. A cursor in the bounded status store rotates the first task, so a large table cannot starve
later tables. Startup runs exactly one pass. If expired data remains, the writer schedules more passes at short safe
boundaries after live observation batches, while giving an empty receiver queue the earliest opportunity. A busy
receiver therefore keeps accepting and committing observations while cleanup converges instead of draining the whole
backlog before startup or in one hourly pause.

Routine parent deletes require every retained child to be gone first. The 1,000-row pass cap is therefore also the
total deleted-row cap: foreign-key cascades add no hidden child-row deletes. Explicit channel clear and full reset are
operator actions and intentionally remain complete rather than using the routine cap. SQLite may reuse freed pages
immediately; file compaction remains a separate maintenance action.

Clearing one saved channel deletes that channel's learned site, quality, conventional, and optional detailed facts.
Deleting a saved channel does the same through its foreign key. System-wide P25 history remains when another channel
shares the system. A full statistics reset deletes derived activity but never administrator-owned channels, aliases,
credentials, preferences, recordings, or ordinary log files.

Fresh databases create these exact tables in the single current-schema routine. Existing databases are changed only
by the backed-up, staged Application Migrator. Startup validates the current schema and never repairs it silently.

## Query-plan requirements

Representative-volume tests must show indexed searches for:

- configuration UUID to `receiver_channel` and system key to `radio_system`;
- recent system identities and reverse radio/talkgroup relationships;
- channel-owned P25/DMR/NXDN site, neighbor, and frequency facts;
- conventional DMR identities by channel, carrier, and timeslot;
- patch-member Activity through its member index and parent event key; and
- every time-first retention selection, including quality buckets.

Every website list remains server-bounded. A system, site, talkgroup, radio, quality, or discovery query must not scan
optional detailed Activity, and admission checks must use the owning primary-key prefix.
