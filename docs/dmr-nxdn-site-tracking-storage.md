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

One row owns protocol-neutral trunked identity summaries. Its stable key within this receiver profile is:

- `p25:<five-digit WACN hex>:<three-digit system hex>` after a complete P25 WACN and System ID are known;
- `dmr:tier3:<model>:<network>` for standard DMR Tier III after both the model and Network ID are known, where the
  lowercase model is `tiny`, `small`, `large`, or `huge` and the network is canonical decimal;
- `nxdn-c:<category>:<system>` for NXDN Type-C after both the location category and System ID are known, where the
  lowercase category is `global`, `regional`, or `local` and the system is canonical decimal; or
- `dmr:channel:<configuration UUID>`, `nxdn-c:channel:<configuration UUID>`, or
  `nxdn-d:channel:<configuration UUID>` when that saved channel does not have a supported, complete native identity.

The native numeric ranges follow their decoded address space: a DMR Network ID is `0..511` for Tiny, `0..127` for
Small, `0..15` for Large, or `0..3` for Huge. NXDN reserves the end values, so a Type-C System ID is `1..1022` for
Global, `1..16382` for Regional, or `1..131070` for Local. Canonical decimal has no leading zero.

Receiver channels in one profile share a radio system when they learn the same complete P25 WACN/System pair, the
same standard DMR Tier III model/Network pair, or the same NXDN Type-C location-category/System pair. Including the
DMR model and NXDN location category prevents equal numeric IDs from different native address spaces from being
merged. This is receiver-profile grouping, not a claim of worldwide uniqueness or a key to compare across separate
installations. Capacity Plus, Connect Plus, Capacity Max, Hytera Tier III, unknown DMR variants, and NXDN Type-D
remain saved-channel-scoped. Only a channel-scoped fallback has an explicit cascading configuration owner. Alias
Lists never participate in system identity.

A DMR timeslot belongs to site/channel/resource observations and conventional DMR summary keys. It never participates
in radio-system identity, so different timeslots on the same proven trunked system do not create separate systems.

`protocol_code` uses `1=P25`, `3=DMR`, and `4=NXDN`. `address_domain_code` describes how subscriber and talkgroup
addresses are interpreted (`standard`, NXDN Type-C, or NXDN Type-D). The native identity columns are mutually
exclusive: a P25 row stores WACN and System ID, a shared DMR row stores model and Network ID, and a shared NXDN Type-C
row stores location category and System ID. These values are not repeated on `receiver_channel`.

Incomplete P25 observations do not create a provisional radio system. Optional detailed activity remains
channel-owned with a null system reference until complete native identity is observed; system summaries are skipped.
A saved P25 channel is bound to its first complete, coherent WACN/System/RFSS/Site identity only when the decoded
source frequency is one of that site's advertised control channels. A complete JSON binding in
`configuration_channel` is authoritative. If its delayed save was interrupted, startup restores the same channel's
complete current learned site into memory; the next ordinary configuration save records that value in JSON. It never
infers a binding from a partial row or from another saved channel. A conflicting direct edit fails validation; the
next verified snapshot that exactly matches that complete saved value is the bounded recovery path. Without such a
complete saved override, later partial or complete observations cannot erase or move the first verified binding.
Complete delayed calls may still update their correctly identified historical system without moving the channel.

DMR and NXDN can use an isolated channel-scoped fallback before a supported native identity is complete. When later
site evidence establishes that identity, new activity moves to the native system. Bounded history already recorded
under the fallback stays attached to that exact saved-channel identity and is shown as earlier activity; it is not
relabeled under a native system that had not yet been proven. Site snapshots and current radio-presence state are
cleared when the current assignment changes, then rebuilt from new observations. A changed system generation uses the
assignment watermark so delayed observations cannot move the channel back to an older system.

Deleting a DMR or NXDN configuration cascades its channel-scoped fallback and its retained history coherently. A
detached fallback otherwise remains only while retained facts still refer to it, then bounded maintenance removes it.
A shared native P25, standard DMR Tier III, or NXDN Type-C row remains while another channel or retained system fact
uses it, then bounded maintenance removes it after its final owner and retained facts are gone. Expected cardinality
is one row per established native system plus at most one fallback for each observed DMR/NXDN channel. It does not
grow with calls.

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

Identity-bearing event, member, and call-bucket rows also carry a small integer kind discriminator. This adds no rows
or indexes: it is one integer on each existing `receiver_activity_event`, `activity_event_identity_member`,
`trunked_logical_call_identity_bucket`, and `p25_site_call_identity_bucket` row. Composite foreign keys use it to
require event sources to be radios, patch members to be talkgroups, target summaries to match the recorded target
kind, and source-role bucket rows to be radios; destination-role buckets may contain a talkgroup, patch group, or
radio. These rows keep their existing hourly or detailed-activity retention and cascading cleanup.

## Site snapshots

### P25 site tables

`p25_site_snapshot` has one latest row per receiver channel. Its children store the current and retained summaries for
channels, channel tags, frequency bands, foreign-system bands, neighbors, and patch members. Every child cascades
from the channel-owned snapshot. The schema validates P25 field widths, canonical SHA-256 snapshot hashes, booleans,
frequencies, timestamps, timeslot counts, patch versions, and radio/talkgroup ranges.

The network and current-site parts must agree on every System ID or NAC they both provide, even while RFSS or Site is
still unknown. They must form one coherent P25 identity before a complete site is stored, and the decoded source must
be an advertised current-site control channel. Independently stabilized values from two transition generations are
never combined. Once the first complete site is accepted, partial updates retain its RFSS/Site and conflicting
observations are rejected. A conflicting direct configuration edit is invalid unless a later complete, verified
snapshot establishes that exact saved override through the recovery path described above.

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
NXDN location category use separate, protocol-specific fields. The shared snapshot never stores a DMR model in
`observed_location_category_code`.

The table permits only protocol-appropriate combinations. DMR uses variants `0..5`, no NXDN RAN or System ID, and
native timeslots 1 or 2. NXDN uses variants `0..2`, location categories `0..5`, optional RAN `0..63`, and no DMR
model, brand, channel-type, or color-code fields.

A complete standard DMR Tier III snapshot can assign its channel to the native model/Network system. A complete NXDN
Type-C snapshot can assign its channel to the native location-category/System identity. Other DMR variants and NXDN
Type-D keep their channel-scoped fallback even when their site page has other useful facts. Site number, RAN,
frequency, logical channel number, and timeslot remain site or resource context rather than radio-system identity.

The table is updated in place, so its cardinality is one row per observed saved DMR/NXDN channel. A continuously
active receiver can refresh that row every five seconds (17,280 in-place updates per day) without creating new rows.

### `trunked_site_channel_summary` and `trunked_site_neighbor_summary`

These tables store one mutable row per distinct learned channel or neighbor key for a DMR/NXDN receiver channel.
Their primary keys start with `channel_id`, which serves the site-page lookup. Time-first indexes serve retention.
Repeated cumulative snapshots do not refresh an old child unless that child is observed again.

Neighbor rows repeat the parent protocol only so SQLite can enforce protocol-specific values and a composite foreign
key guarantees that it always matches the parent. DMR neighbor model and NXDN neighbor location category use the
separate `dmr_model_code` and `nxdn_location_category_code` columns. The API and CSV export likewise present these as
separate `model` and `location_category` fields.

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

`idx_trunked_control_quality_channel_time` serves a site's latest and chart queries.
`idx_trunked_control_quality_retention` lets maintenance select a bounded set of expired keys without scanning the
table. A conservative 512-byte planning allowance per row, including both indexes, is about 127 MiB at 30 days or
1.51 GiB at 365 days per continuously monitored channel/frequency. These are planning bounds, not measured file
sizes. Samples travel through the bounded statistics queue and single database writer; decoder and tuner threads never
wait for SQLite.

```sql
SELECT ... FROM trunked_control_channel_quality
WHERE channel_id = ? AND observed_at_ms BETWEEN ? AND ?
ORDER BY observed_at_ms
LIMIT ?;
```

## Storage budgets and representative query plans

The figures in this section are conservative planning allowances, not measured SQLite row sizes or hard file-size
guarantees. They include the table row, current secondary indexes, and a margin for B-tree/page overhead. WAL files,
unused pages awaiting reuse or compaction, unusual page fill, and other tables are separate. The P25 allowance assumes
normal decoder-produced descriptor and status text; those legacy text columns do not impose a byte-length ceiling.

Let `D` be the configured retention in days (`1..365`), `H = 24 * D` retained hourly buckets, `C` the number of saved
channels, `S` the number of established radio systems, `I` the admitted identities in one system (`I <= 100,000`),
and `L` the learned P25 sites in one system (`L <= 65,536`, from 256 RFSS values by 256 Site values). A table without a
separate admission cap states its retained maximum as a formula over the accepted event rate or distinct keys; the
time limit and indexed pruning remain its bounded-growth mechanism.

- **Optional detailed events and members:** Detailed history creates one `receiver_activity_event` for each accepted
  non-`CONTINUE` action. One P25 patch event can add at most 64 `activity_event_identity_member` links; ordinary events
  add none. There is no separate admission count, so an accepted rate of `E` events per hour retains at most `E * H`
  event rows and 64 times that many member rows in the all-patch worst case. Use 1 KiB per event and 256 bytes per
  member for planning, or at most 17 KiB for one worst-case patch event. Retention removes members before their parent
  events; channel clear/delete and system deletion cascade them. The bounded page and retention queries must use
  `idx_receiver_activity_event_channel_time` for channel paging,
  `idx_receiver_activity_event_system_time` for system paging,
  `idx_receiver_activity_event_retention` for pruning, and `idx_activity_event_member_identity_event` for member
  lookup, without a temporary sort for the normal time-ordered page.

- **Radio-system identity summaries:** A new `radio_system_identity_summary` row is created only when a system first
  sees a distinct canonical talkgroup, patch group, or radio; later observations update it. Admission stops at 100,000
  identities per system while existing rows remain writable. The conservative allowance is 2 KiB per row, including
  the indexes and the possible 160-code-point talker alias, or about 196 MiB at the hard per-system cap. Rows older
  than `D` are removed only after referencing relationships, current state, buckets, and optional details are gone;
  deleting the system cascades them. Recent-kind paging uses
  `idx_radio_system_identity_last_seen`, admission on the system-leading unique key, and pruning on
  `idx_radio_system_identity_retention`.

- **Radio/group relationships and current radio state:** A new `trunked_radio_group_summary` row represents one
  distinct radio/group pair and admission stops at 500,000 pairs per system. Use 1 KiB per relationship, or about
  489 MiB at that hard cap. Affiliation and presence update one current row per admitted radio and system; a clear
  watermark can have one row per admitted radio, system, and observing channel. Use 512 bytes per current-state row.
  All are pruned by their confirmed/last-seen time and cascade from their identity or system owners. Radio-to-group
  lookup uses the primary key, the reverse direction uses `idx_trunked_radio_group_reverse`, and pruning uses
  `idx_trunked_radio_group_retention`. Current-state plans use
  `idx_trunked_radio_affiliation_talkgroup`, `idx_trunked_radio_affiliation_retention`,
  `idx_trunked_radio_channel_presence_channel`, `idx_trunked_radio_channel_presence_retention`, and
  `idx_trunked_radio_channel_presence_clear_retention` for their talkgroup, channel, and retention queries.

- **Logical-call, site-call, signaling, and conventional hourly buckets:** A repeated call updates existing hourly
  keys instead of adding a call row. The retained system-total maximum is `S * H`; identity totals are at most
  `2 * I * H` per system for source/destination roles; P25 site totals are at most `L * H` per system; and P25
  site/identity rows are bounded by the distinct site, channel, role, and admitted-identity combinations observed in
  those `H` hours. Signaling is one row per distinct channel/system/hour. Conventional totals are one row per distinct
  channel/frequency/timeslot/hour, while conventional identity totals add only distinct role/kind/ID keys observed in
  that hour. No hourly bucket table has a separate admission count; its distinct key dimensions and `H`-hour lifetime
  are the bound. The non-hourly `conventional_activity_summary` has one row per saved
  channel/frequency/timeslot and therefore does not grow after those configured carrier keys are learned; channel
  clear/delete or a full reset removes it. Allow 512 bytes per bucket or conventional-summary row. Hour-aligned
  retention prunes every bucket family. Time-range and retention plans use `idx_trunked_logical_call_bucket_time`,
  `idx_trunked_logical_identity_dashboard_time`, `idx_p25_site_call_bucket_time`,
  `idx_p25_site_call_identity_time`, `idx_p25_site_call_identity_retention`,
  `idx_p25_site_call_identity_identity`, `idx_p25_site_call_identity_channel_time`,
  `idx_p25_learned_site_retention`, `idx_trunked_signaling_activity_time`,
  `idx_trunked_signaling_activity_system`, `idx_conventional_bucket_time`,
  `idx_conventional_bucket_dashboard_time`, and `idx_conventional_call_identity_dashboard_time` for time-range and
  retention work; the conventional lifetime summary uses its channel-leading primary key. The two P25 identity
  indexes are tested with 100,000 representative site/identity/hour rows: identity lookup is identity-leading, while
  saved-channel history is channel-and-time-leading and avoids both a table scan and a temporary sort.

- **P25 site children:** Each saved P25 channel has at most one snapshot. Current tables contain the latest distinct
  channel, tag, band, foreign-band, neighbor, patch, and patch-member keys; they are replaced when that structural
  snapshot changes and only have their confirmation time advanced when it does not. Matching summary tables add one
  row the first time each key is seen and then update it. A band number is `0..15`, so each home-band table has at most
  16 rows per channel. The other child families have no separate admission count and retain only distinct keys
  confirmed within `D`; child rows are removed before the snapshot and all cascade when the saved channel is deleted.
  Use 1 KiB per P25 child row as the conservative normal-decoder planning allowance. Site-page reads must stay on their
  channel-leading primary keys or `idx_p25_site_channel_frequency`, `idx_p25_site_neighbor_channel_site`,
  `idx_p25_site_patch_talkgroup`, and `idx_p25_site_patch_radio`; every cleanup must use its matching time-leading
  `*_retention` index.

- **DMR/NXDN site facts:** One `trunked_site_snapshot` row belongs to each observed saved DMR/NXDN channel. Admission
  permits at most 1,024 learned channel facts and 256 neighbor facts per channel, and repeated snapshots update those
  keys. At a 512-byte allowance per row including indexes, the defensive maximum is about 641 KiB per channel. Facts
  older than `D` are pruned before their snapshot; deleting the saved channel cascades all three tables. The existing
  cap-sized plan corpus must use `idx_trunked_site_snapshot_last_seen`, `idx_trunked_site_channel_last_seen`, and
  `idx_trunked_site_neighbor_last_seen` for retention, while channel pages use the channel-leading primary keys.

- **Conventional DMR identity summaries:** One completed call creates or updates at most one talkgroup and two radio
  rows, keyed by saved channel, frequency, timeslot, and identity. Admission is 4,096 talkgroups plus 32,768 radios per
  channel across all of its carriers and slots. A 512-byte allowance per row is about 18 MiB at both hard caps. Rows
  older than `D` are pruned, and deleting the channel cascades them. The talkgroup admission test fills its 4,096-row
  cap and verifies the channel and retention paths. Recent pages use
  `idx_dmr_conventional_talkgroup_channel` and `idx_dmr_conventional_radio_channel`, and
  `idx_dmr_conventional_talkgroup_last_seen` and `idx_dmr_conventional_radio_last_seen` for retention.

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
Deleting a saved channel does the same through its foreign key. System-wide native history remains when another
channel shares the system. A full statistics reset deletes derived activity but never administrator-owned channels,
aliases, credentials, preferences, recordings, or ordinary log files.

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
