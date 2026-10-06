# SQLite Activity Database Guidelines

These rules apply to SDRTrunk statistics, activity history, site state, and website-facing SQLite data. The database is
part of the receiver hot path and may grow for months on small nodes, so storage and query cost are product constraints.

## Required Purpose Before Schema Growth

Every proposed table, column, index, or retained event type must identify:

1. The concrete runtime or website function and query it serves.
2. Why an existing summary, hourly bucket, status row, or normalized identity cannot serve that function.
3. Expected rows per hour and worst-case retained rows at the maximum retention setting.
4. Expected cardinality and bytes per row, including duplicated text and index cost.
5. Retention and deletion behavior.
6. The index-backed access path and a representative `EXPLAIN QUERY PLAN` result.

Do not create speculative tables for possible future analysis. Do not create immutable per-call disposition or audit
rows when counters in an existing talkgroup, site, frequency, or time bucket answer the product question.

## Prefer Aggregates Over Full Events

- Put lifetime totals in the existing entity summary row when the website only needs a current total.
- Put time-series totals in bounded hourly or other coarse buckets. Add counters to an existing bucket when the key and
  retention semantics match.
- Keep individual detailed events optional, disabled by default, and retention-bound. Summary collection must not
  depend on detailed history being enabled.
- Store a full event only when users need to retrieve that specific event as a row. A chart, total, ratio, or health
  indicator is not sufficient justification for full events.
- Do not persist transient states such as pending work, retry attempts, or terminal outcomes unless a named user-facing
  diagnostic requires them and no existing global operational counter provides it.

## Compact Representation

- Use `INTEGER` category codes backed by stable Java enums/mappings for actions, event types, target kinds, protocols,
  channel roles, and similar bounded categories. Do not repeat category names in hot tables.
- Use integers for epoch milliseconds, frequencies in hertz, identifiers, counts, and booleans (`0`/`1`). Avoid text
  encodings of numeric values.
- Normalize repeated radio-system, saved-channel, group-identity, radio, and alias identities. Reference their compact integer
  key from high-volume tables.
- Store descriptive text once in the appropriate identity/configuration table. Do not copy channel names, aliases,
  decoder names, or formatted labels into every event or bucket.
- Do not store JSON, serialized Java objects, raw decoded messages, audio payloads, or arbitrary metadata maps in the
  activity database without an explicit approved retrieval requirement.
- Use `NULL` for genuinely unknown optional values. Do not add multiple status strings that can be derived from one
  compact code or timestamp.
- Prefer composite natural keys and `WITHOUT ROWID` for high-volume aggregate tables when the composite key is the
  lookup path. Use synthetic event IDs only when individual event retrieval requires them.

## Index Discipline

- Add an index only for a demonstrated query path. Each index increases database size, WAL traffic, checkpoint work,
  migration time, and write amplification.
- Avoid indexes whose leading columns duplicate an existing primary key or index. Prefer one index that supports the
  actual owner, filters, time range, and ordering used by the website.
- Use partial indexes when a query targets a sparse state and the predicate is stable.
- Never add indexes merely to silence a theoretical concern. Test the real query against representative row counts.

## Write-Path Rules

- Decoder, tuner, recording, and streaming threads must never perform SQLite work directly.
- Route activity records, including best-effort P25 Working-ID observations, through the bounded statistics queue and
  single background writer. Live Working-ID assignments stay in receiver memory and never wait for SQLite.
- Batch related writes in one transaction and use atomic upserts for counters and summaries.
- A single observed call/output must increment each intended aggregate once. Patch-group fan-out, retries, or provider
  delivery attempts must not multiply the original-call count.
- Keep normal runtime paths validation-only for existing schemas. Schema creation belongs to the startup schema routine;
  supported deployed changes belong exclusively to the bundled Application Migrator. Current-profile upgrades use
  one adjacent-chain transaction with the selected recovery snapshot and final exact-format validation; external
  imports retain staged-copy integrity, foreign-key validation, and atomic promotion.

## Website Query Rules

- Bound every time range, page size, result count, and chart point count on the server.
- Aggregate in SQL and return only fields rendered by the client. Avoid `SELECT *` on high-volume tables.
- Key trunked identities by their owning radio system and channel-owned facts by their saved channel. A group or radio
  number alone is not globally unique.
- Keep chart payloads coarse and predictable. Zero-fill missing buckets in the bounded API response rather than storing
  empty database rows.
- Do not make dashboard requests scan detailed event history. Dashboards and directory pages must use summaries and
  buckets only.

### Alias Editor large-list indexes

The web Alias Editor never builds a whole-list activity snapshot. SQLite applies the selected Alias List, search, and
configuration or activity filters first, sorts the complete matching result, and returns only the requested page.
There is no candidate-count ceiling on Activity browsing. `alias_activity_summary` keeps one fixed-size row per Alias
for the sortable call, signaling, and first/last-seen values; page and export requests never rebuild it from retained
identity history.

The background statistics writer updates this projection after it accepts an observation. It uses the saved channel's
currently assigned, protocol-compatible Alias List and the normal exact-before-range matcher precedence. Reassigning
a channel changes attribution for later observations without moving or deleting earlier counters. Appearance,
routing, and imported metadata edits preserve the row; changing the matcher resets it, and a new or cloned Alias starts
without observed activity. The format-19-to-20 Application Migrator performs the only full reconstruction, seeding the
projection from retained compact activity where it can still determine an owner.

The global **Reset Stats** action clears every Alias Activity counter and immediately reseeds one empty row per Alias.
A channel-only statistics clear does not try to subtract that channel's historical contribution from durable Alias
counters because the projection deliberately keeps no per-channel attribution ledger.

`idx_alias_list_id(alias_list_id, id)` provides deterministic whole-list/export order, while
`idx_alias_list_name_sort(alias_list_id, lower(coalesce(name, '')), id)` serves normal name browsing. List-first
matcher, identity-type, group, and displayed-value indexes serve the other sortable Alias fields. The summary's
list-first indexes serve Calls, Signaling, and Last Seen sorts. Dedicated list-first partial range indexes serve the
prospective writer's range winner lookup without replacing the older protocol-first matcher indexes used by bounded
page enrichment. These indexes contain no time-series rows: their size follows administrator-owned Alias
configuration, not receiver uptime.

### Canonical P25 subscribers and WUID observations

`p25_subscriber_identity` stores one normalized row for an exact home WACN, home System ID, and assignable subscriber
ID. It is the shared target for qualified radio-directory rows and explicit canonical Alias matchers; an ordinary
local radio number alone never creates or attaches one. Growth follows distinct proven subscriber tuples and
administrator-created canonical aliases, not message rate. Each row is four integers plus the primary-key and unique
tuple indexes, with no repeated formatted text. A bounded retention task removes only rows that are no longer
referenced by the radio directory, Working-ID observation history, or an Alias. Tuple resolution uses the unique
`(home_wacn, home_system_id, subscriber_id)` index; reverse Alias and directory lookups use
`idx_alias_p25_subscriber_identity` and the partial `idx_radio_system_identity_p25_subscriber` index.

`p25_wuid_assignment_observation_summary` keeps one bounded aggregate for each observed
`(radio_system_id, working_id, p25_subscriber_identity_id)` relationship. It records first and last observation,
registration and affiliation counts, last evidence, and nullable saved-channel provenance. It never claims that a
relationship is current and is never read by the decoder. Reuse and reassignment legitimately leave several
historical pairs. Admission is capped at 500,000 pairs per radio system and ordinary activity retention removes old
rows in indexed batches. The table starts empty on upgrade; legacy radio numbers are never guessed into it.
Repeated messages update the existing pair, so new rows per hour equal newly observed distinct pairs rather than the
message rate. An isolated 10,000-pair SQLite sample with millisecond timestamps and all three indexes occupied
1,294,336 bytes (about 129 bytes per pair), projecting roughly 62 MiB at the per-system cap. This is a planning
estimate rather than a hard byte limit: integer widths, page occupancy, the shared subscriber directory, and WAL
traffic add variable cost. Several received systems each have their own pair cap.

The live decoder registry is bounded, process-local state. Accepted registration establishes a mapping, accepted
affiliation can refresh an exact mapping, and deregistration clears it. Decoder reads remain fixed-cost and database
or statistics-queue loss can only lose history. A new app process deliberately starts with no Working-ID mappings and
relearns them from new signaling.

### Historical P25 alias evidence

Talkgroup and radio pages first use existing compact call, affiliation, presence, and identity-member evidence to
find the saved channel's Alias List and observed address. Some retained identities have no usable compact evidence,
so their labels need a detailed-event fallback. Format 33 adds only
`idx_receiver_activity_event_source_working_evidence(source_identity_summary_id, channel_id, source_observed_working_id)`
with the predicate `source_identity_summary_id IS NOT NULL AND source_observed_working_id > 0`.

The source fallback splits ordinary local-address identities from canonical subscribers before seeking events.
The canonical branch uses `SEARCH event USING COVERING INDEX idx_receiver_activity_event_source_working_evidence
(source_identity_summary_id=?)`. Its index contains every required event value and excludes historical observations
whose missing working address makes them unusable. The ordinary branch retains the source/time index; target
evidence and compact summaries remain unchanged. This does not add a summary, retain extra events, copy text, or
infer a working address from a local radio number.

There is one index entry per retained detailed event with a positive source working address. New entries per hour
equal qualifying accepted detailed events, and worst-case cardinality equals all retained source-linked detailed
events. At the maximum 365-day retention, an observed rate of R qualifying events per hour projects 8,760 × R entries.
Detailed history remains optional. Retention, explicit statistics clears, event deletion, and attribution updates
maintain the index through the existing background writer and SQLite transactions.

An isolated 293,531-event projection contained 315 qualifying source events; the new index occupied 12 KiB, about
39 bytes per qualifying entry including page overhead. At 1,000 qualifying events per hour over 365 days, that sample
ratio projects approximately 326 MiB. Integer widths, page occupancy, and WAL costs vary, so neither the sample's
sparsity nor the estimate is a storage cap. The same 100-identity evidence query returned identical aliases and took
approximately 10 ms rather than 28 ms with the index and split. These are warm projection timings, not production
page timings. For that sparse canonical-source query, widening both existing source/target indexes achieved similar
query time but added roughly 6 MiB in the projection and was omitted from format 33. A second sparse working-evidence
target index had no measured benefit and remains omitted. Format 38 addresses the separate broad local/target
evidence and exact Activity ranking paths described below.

### Covering Activity lookup replacements

Format 38 replaces four existing Activity indexes rather than retaining duplicate narrow and covering copies, and
adds one narrow event/channel projection for historical member evidence:

```sql
CREATE INDEX idx_receiver_activity_event_source_time
ON receiver_activity_event(source_identity_summary_id, observed_at_ms, id, channel_id,
    source_observed_local_id, source_observed_working_id)
WHERE source_identity_summary_id IS NOT NULL;

CREATE INDEX idx_receiver_activity_event_target_time
ON receiver_activity_event(target_identity_summary_id, observed_at_ms, id, channel_id,
    target_observed_local_id, target_observed_working_id, target_kind_code)
WHERE target_identity_summary_id IS NOT NULL;

CREATE INDEX idx_receiver_activity_event_channel_action_time
ON receiver_activity_event(channel_id, action_code, observed_at_ms DESC, id DESC,
    radio_system_id, source_identity_summary_id, source_observed_local_id);

CREATE INDEX idx_activity_event_member_identity_event
ON activity_event_identity_member(identity_summary_id, event_id, observed_local_id);

CREATE INDEX idx_receiver_activity_event_id_channel
ON receiver_activity_event(id, channel_id);
```

The identity/time indexes serve historical Alias evidence when an identity has no usable compact call, affiliation,
presence, or member evidence. Friendly-name search must consider every matching identity before sorting and paging;
a compact lifetime total cannot supply the saved channel and observed address that select the correct Alias List.
Including target kind covers the exact local-versus-Working-ID address expression without changing its meaning.
The explicit ID before added payload columns preserves newest-first time/ID order, including equal-time pagination.
Representative plans report `SEARCH event USING COVERING INDEX idx_receiver_activity_event_source_time
(source_identity_summary_id=?)` and the corresponding target index. Ordered Activity probes use these seeks without
a temporary sorting B-tree. The format-33 sparse positive-Working-ID source index remains unchanged because it avoids
examining local-only events for qualified canonical subscribers.

The saved-channel/action index serves the existing exact Dashboard Activity radio ranking. That view is an explicit
exception to the aggregate-only dashboard rule: its chosen action/hour projection needs exact source identity and
local-address counts, including conventional channel ownership, that the existing signaling buckets do not store.
It uses bounded channel/action/time seeks instead of scanning unrelated event history, and its representative plan
reports `SEARCH event USING COVERING INDEX idx_receiver_activity_event_channel_action_time
(channel_id=? AND action_code=? AND observed_at_ms>? AND observed_at_ms<?)`. The replacement introduces no new
history dependency, event collection, summary, or receiver callback. Other Dashboard totals and charts retain their
summary and bucket paths.

An isolated 300,000-event integer-only projection returned identical results for seven evidence, ranking, and
Activity-order probes. Five warm-run medians reduced correlated unmatched source evidence from 606 ms to 35 ms,
target local/Working-ID CASE evidence from 21 ms to 1.2 ms, and an action-slice radio grouping from 24 ms to 6.4 ms.
These are SQL projection timings, not full production-page results. The source and target ordered probes retained
their time/ID seek without a temporary sort. Placing payload columns before the implicit ID instead would require a
temporary tree for the last ordering term, which is why format 38 makes the ID explicit.

All three indexes contain at most one entry per qualifying retained detailed event; the source and target indexes
still exclude events without that identity role. New entries per hour equal qualifying accepted detailed events per
hour, and maximum 365-day retention projects at most 8,760 × R entries per index for R accepted events per hour.
The synthetic replacements grew from 14.2 MB to 20.5 MB, adding 6.3 MB across the three indexes, approximately 21 bytes
per event at that sample's role distribution. Additional sampled cost was about 10 bytes per source entry, 11 per
target entry, and 7 per channel/action entry. An all-roles-present planning estimate is roughly 28 additional bytes
per event, or 234 MiB at 1,000 events per hour for 365 days. Integer widths and page occupancy vary; these are estimates,
not storage caps. No descriptive text is duplicated. Existing retention, explicit clears, and event deletion maintain
all entries through the background writer's transactions.

In the same synthetic projection, three committed 10,000-event WAL insertion samples increased median transaction
time from 49 ms to 61 ms and generated WAL from 8.7 MB to 10.8 MB. Those samples contain only these replacements and
the sparse Working-ID index, so their roughly 26% time and 25% WAL increases cannot predict the complete receiver
writer's cost. The measured read benefit justifies replacing the existing indexes for these demonstrated paths;
receiver queues and checkpoints still require operational verification. Fresh schema creation uses the covering DDL,
the adjacent 37-to-38 Application Migrator rebuilds these three indexes and the member index below, adds one narrow
event/channel index, and reports four rebuilt plus one added index. Prior formats keep exact frozen definitions.
No runtime startup repair or migration is added.

Historical member evidence retrieves the address recorded for each identity-member relationship and the saved
channel that chooses its Alias List. It contributes alongside compact call, affiliation, and presence evidence;
only identities lacking all usable compact evidence reach the detailed source/target fallback. The existing member identity/event index finds the right member rows but does not
cover their address. Its replacement appends the address after both primary-key columns, preserving ordering and
identity uniqueness. The narrow event-ID/channel index is an explicit exception to the duplicate-leading-key rule:
the integer event primary key locates a row but requires a wide event-table page fetch to obtain its saved channel.
This secondary index supplies that projection directly. It does not replace the unique `(id, radio_system_id)` key
used by member foreign keys. Queries select the demonstrated covering event path explicitly; merely creating the
index did not reliably make SQLite choose it.

A separate SQLite 3.51.0 projection used 1,000,000 wide events, 771,824 total member rows, and 101 requested group
identities containing 471,824 members across four channels. The largest requested group contained 181,599 members.
The complete distinct evidence lookup returned the same 1,212 identity/channel/address triples. Three warm-run
medians improved from 964 ms to 452 ms with both covering seeks, about 2.1 times faster. Plans report
`SEARCH member USING COVERING INDEX idx_activity_event_member_identity_event (identity_summary_id=?)` followed by
`SEARCH event USING COVERING INDEX idx_receiver_activity_event_id_channel (id=?)`. The distinct projection still
requires its temporary deduplication tree. These are complete SQL evidence projections with explicit hints,
not production page timings; both variants passed quick and foreign-key checks.

The member replacement added 2,105,344 bytes across 771,824 members, about 2.7 additional bytes per member in that
sample. The new event/channel index occupied 13,885,440 bytes, about 14 bytes per event. Each has at most one entry
per existing corresponding row. New entries per hour equal accepted retained events or accepted member rows; the
existing patch-member limit is 64 talkgroups per event. At maximum 365-day retention and R accepted events per hour,
the event projection has at most 8,760 × R entries and the member index at most 64 × 8,760 × R entries. These bounds
retain no extra events or memberships. Integer widths and page occupancy affect actual bytes. Existing retention,
explicit clears, and event deletion through the unchanged foreign keys remove both indexes' entries automatically.

Three committed insertion samples in that projection each added 10,000 events and 7,715 members. Median transaction
time increased from 35 ms to 46 ms, about 33%, and median WAL from 4.19 MB to 4.86 MB, about 16%. These samples isolate
the member/event indexes and parent keys; they do not contain the full receiver index set, writer queue or checkpoint
schedule. Their storage and write cost is the measured tradeoff for eliminating the demonstrated wide-row fetches,
and cannot be added to the percentages from the separate three-index projection above to predict production cost.

### Covering channel-local evidence (format 39)

Format 38's covered member/event join still revisits one event-index leaf for each retained member. Sorting those
lookups and increasing a connection's page cache did not remove the demonstrated large-request cost. Format 39
stores the existing parent observation channel on each member and retains the format-38 identity/event cover for
Activity paging. An additional cover groups historical channel/address evidence:

```sql
CREATE UNIQUE INDEX idx_receiver_activity_event_id_channel
ON receiver_activity_event(id, channel_id);

CREATE INDEX idx_activity_event_member_identity_channel_local
ON activity_event_identity_member(identity_summary_id, channel_id, observed_local_id);

CREATE INDEX idx_p25_site_call_identity_identity_address
ON p25_site_call_identity_bucket(identity_summary_id, observed_local_id, channel_id)
WHERE observed_local_id > 0;
```

The event/channel definition replaces the prior non-unique index rather than adding another event index.
Member `channel_id` is a positive, non-null integer copied from the exact parent. The composite foreign key
`(event_id, channel_id)` enforces that relationship alongside the existing event/owner and identity/owner/kind keys.
Neither runtime attribution path changes an event's observation channel. Both member insertion paths already
have that channel, so they add no lookup or work on the real-time decode path. Saved-channel reassignment does
not change retained ownership; no new foreign key binds the member to the channel's current system generation.

For bounded Alias evidence, `identity_summary_id IN (SELECT ... FROM requested)` and a nested `SELECT DISTINCT`
use the grouped member cover before the outer UNION. The DISTINCT subquery is necessary: placing DISTINCT on
the UNION arm alone let SQLite remove it and scan repeated evidence. The new address cover instead serves exact
identity/address equality and inclusive range bounds, with named Alias candidates compared before channel/list
lookups. Search still matches the complete scoped set before ordering and paging, and the old compact-before-detail,
canonical/local-address and assigned-list ownership rules remain unchanged.

A fresh SQLite 3.51.0 integer projection compared the final two-member-index design with format 38 on 1,000,000
wide events and 771,824 member rows. The 101 requested identities contained 471,824 members. The covered parent
join took 451.39 ms warm; nested DISTINCT inside UNION took 1.034 ms and returned the same 1,212 triples, using
52 rather than thousands of progress callbacks. Original member fields, committed-insert survivors and bounded
retention survivors matched exactly, foreign-key checks passed, wrong channels were rejected, and parent deletion
cascaded. These are isolated synthetic measurements, not complete production page timings.

The stored channel added 2,215,936 bytes to that member table; the new grouped cover used 12,050,432 bytes.
The existing member/event index sizes were unchanged. Each new member-index entry corresponds to an existing
member, and each positive call-address entry to an existing compact-call bucket. They retain no additional rows
and are removed by the same bounded retention and explicit-clear operations. Integer width and page occupancy
affect real storage; the existing limit of 64 patch members per event still bounds membership growth.

Three committed synthetic insertion samples each added 10,000 events and 7,715 members. The final two-member-index
and composite-FK design changed median time from 43.19 to 161.72 ms and WAL from 5.27 to 15.87 MB. A bounded
1,000-event/1,000-member retention pass changed 12.82 to 21.65 ms and WAL 2.10 to 4.97 MB. These fixtures exclude
the new compact-call address index, the rest of the receiver index set, background queues and checkpoint scheduling;
the percentages cannot be added to the earlier format-38 measurements or treated as a production writer forecast.
A separate 440,000-bucket negative-address fixture returned identical matches and changed the original name filter
from 156.8 to 5.51 ms with the address cover; exact/range plans used identity and address bounds. Full copied-data
route measurements and receiver continuity checks remain required operational evidence.

### Positive detailed address covers (format 40)

With format 39's compact bucket and member covers, configured-name search still spent substantial time proving
that detailed source/target addresses did not match. Format 40 adds two partial indexes with a different second
key from Activity's time order:

```sql
CREATE INDEX idx_receiver_activity_event_source_identity_address
ON receiver_activity_event(source_identity_summary_id, source_observed_local_id, channel_id)
WHERE source_identity_summary_id IS NOT NULL AND source_observed_local_id > 0;

CREATE INDEX idx_receiver_activity_event_target_identity_address
ON receiver_activity_event(target_identity_summary_id, target_observed_local_id, channel_id)
WHERE target_identity_summary_id IS NOT NULL AND target_observed_local_id > 0;
```

Every search using these covers includes identity equality and a positive observed address. Exact name candidates
seek address equality; range candidates seek inclusive address bounds. Namespace/compact-suppression checks keep
their original Boolean rules and saved-channel/list ownership. Activity paging keeps its existing time/ID indexes;
canonical Working-ID Alias evidence keeps its separate sparse cover. No runtime write path changes.

The current Alias resolver also uses the source address cover for ordinary local-address fallback. Its positive
address predicate makes the partial index eligible and all three selected event columns are covered. Historical
migration consensus keeps the old source/time path, and canonical Working-ID or mixed target evidence retains
its existing index so a positive Working ID with a null local address remains usable.

A 440,000-event negative-address fixture using the format-38 time covers changed 38.9 ms / 6.24 million VM steps
to 7.04 ms / 80,000 VM steps with the new address covers. Exact/range plans and 174 ordered comparisons against
the original predicate passed. These isolate predicate work rather than measuring complete page latency.

A separate bounded fresh projection used the exact event table and all 17 existing explicit event indexes, with
20%, 60% and 100% assigned-positive observations plus null, zero and unattributed positives. Three committed
10,000-row insertion samples had baseline/new median times of 168.18/175.40, 185.68/184.58 and 185.25/199.93 ms.
Bounded 1,000-row deletion medians were 15.75/26.78, 20.59/18.95 and 23.21/46.73 ms. Single late-attribution samples
were noisy and do not establish a steady-state cost. Original row digests matched after every insert, attribution
and deletion. The new indexes held 14,800/40,400/66,000 qualifying entries and occupied 0.266/0.715/1.152 MiB,
about 18.31–18.82 bytes per entry. Integer width and page occupancy affect real storage.

Each event can contribute at most one source and one target entry; null/zero addresses and missing identity owners
contribute none. At maximum 365-day retention and R retained events per hour, each index has at most 8,760 × R
entries. Existing retention and explicit clears remove them automatically. The fixture excludes parent foreign-key
lookups, member/summary writes and checkpoint time and ran while other tests were active; these figures are
synthetic event costs and cannot predict receiver latency or be added to earlier migration percentages. The
Application Migrator adds exactly these two indexes, preserving frozen format-39 DDL and all retained contents.

### Target-type and channel-frequency Activity covers (format 41)

Two existing Activity filters could still traverse a busy identity or channel before finding the requested events.
A secondary target identity plus event type needs that exact combination; an action-first index cannot provide its
seek when the action predicate only excludes grants. A saved channel plus frequency needs the frequency seek before
applying optional LCN and timeslot predicates. Hourly counters cannot return these individual event IDs and cursor
positions. Format 41 adds two covers for the existing detailed Activity rows:

```sql
CREATE INDEX idx_receiver_activity_event_target_event_type_time
ON receiver_activity_event(target_identity_summary_id, event_type_code,
    observed_at_ms DESC, id DESC, radio_system_id, action_code)
WHERE target_identity_summary_id IS NOT NULL;

CREATE INDEX idx_receiver_activity_event_channel_frequency_time
ON receiver_activity_event(channel_id, frequency_hz, observed_at_ms DESC, id DESC,
    lcn_band, lcn_number, timeslot, radio_system_id, action_code)
WHERE frequency_hz IS NOT NULL;
```

The first plan seeks `(target_identity_summary_id=? AND event_type_code=?)`; the second seeks
`(channel_id=? AND frequency_hz=?)`. Both are covering candidate lookups, and their explicit descending time/ID
prefix preserves equal-time ordering before the payload columns. The target cover supplies the residual system and
non-grant predicates. The channel cover supplies LCN, timeslot, system and action predicates without fetching each
event. SQLite chooses the demonstrated indexes with unchanged candidate SQL; no additional selection heuristic or
population probe is introduced. Existing role/time, system/type, channel/time, frequency/time, encrypted and other
indexes remain intact for their own filter and ordering paths, including forward polling.

A fresh SQLite 3.51.0 target projection kept the exact event table and all 19 existing explicit event indexes. It
started with 101,000 events, including a busy target with 80,000 observations mostly of other types, rare target event
types, the same types on unrelated targets, timestamp ties and 1,000 unattributed positive observations. Three-sample
medians for the matching target/type query changed from 24.99 to 0.044 ms, a backward cursor from 31.52 to 0.046 ms,
and an empty subtype from 20.11 to 0.015 ms. Matching query work fell from approximately 561,000 to 1,000–2,000 VM
operations with 1,000-operation sampling. Ordered candidate rows matched exactly. These measure candidate selection rather than full page latency.

That target fixture committed three additional 10,000-event batches, attributed 1,000 pending rows, then deleted
three batches of 1,000 events. Complete ordered event-row digests matched after every operation. Baseline/new median
insertion time was 754.54/830.44 ms and deletion time was 311.57/492.84 ms; one late-attribution batch measured
71.79/87.22 ms. The additional index occupied 5.742 MiB for 128,000 qualifying entries, approximately 47 bytes per
entry or 44.9 MiB per million entries at those integer widths and page occupancy. Parent foreign-key lookups and
other receiver writes were excluded, and checkpoints ran outside timed commits. Concurrent test work and cache
pressure limit interpretation of the three-sample timing differences.

A separate channel/frequency projection started with 40,000 events, including 12,000 observations on one channel
with only 120 known frequencies. Five-sample medians for an empty channel/frequency lookup changed from 1.469 to
0.016 ms, a matching lookup from 1.321 to 0.038 ms, and a missing LCN from 1.269 to 0.007 ms. Ordered rows matched
for all four queries, including another channel. Three committed 1,000-event insertion samples had baseline/new
medians of 9.97/11.07 ms, and row digests matched after inserts and a bounded 1,000-event deletion. The new index
occupied 1,990,656 bytes for 31,120 qualifying entries, approximately 64 bytes per entry or 61 MiB per million at
that distribution. This fixture used the exact event table and old indexes while excluding parent foreign-key
lookups and other receiver writes; its committed-write timing used SQLite's default checkpoint settings.

Each cover has at most one entry per qualifying retained event. The target predicate includes all target-linked
events, including rows with an unknown event type or a zero local address; it is not limited to rare event types.
The frequency predicate excludes unknown frequencies while retaining events whose LCN or timeslot is unknown.
Accepted detailed events and later attribution maintain these entries through the background writer. For R accepted
events per hour, maximum 365-day retention bounds each index at 8,760 × R entries. Existing retention, explicit
statistics clears and event deletion remove entries automatically. No descriptive text or additional event history
is stored, and no runtime write path changes.

These independent projections measure added event-index cost, not the complete receiver writer or checkpoint
schedule. Their timings and storage estimates cannot be added to earlier migration percentages or treated as fixed
byte caps. The Application Migrator adds exactly these two indexes; every prior table/index definition, retained row,
relationship and allocator is preserved. Formats 35, 36 and 37 remain frozen, and startup does not migrate them.

### Covering target identity cleanup (format 42)

SQLite generates its own child lookup when deleting an identity referenced by a composite foreign key. The explicit
retention query's index hint does not govern that separate lookup. Approximate planner statistics can give a system
and a target identity the same estimated event count, making a system-only index look competitive. The previous
target/time cover includes target kind but lacks the system value needed to check the complete foreign key.

Format 42 replaces that existing index, appending one integer after every previous ordering and payload column:

```sql
CREATE INDEX idx_receiver_activity_event_target_time
ON receiver_activity_event(target_identity_summary_id, observed_at_ms, id, channel_id,
    target_observed_local_id, target_observed_working_id, target_kind_code, radio_system_id)
WHERE target_identity_summary_id IS NOT NULL;
```

The implicit check compares `target_identity_summary_id`, `radio_system_id` and `target_kind_code`. Its representative
plan is `SEARCH receiver_activity_event USING COVERING INDEX idx_receiver_activity_event_target_time
(target_identity_summary_id=?)`, including when sampled target and system estimates tie. The existing target/type
cover lacks target kind, and system-first indexes cannot seek an identity within the system. Widening the existing
target/time cover supplies all three foreign-key values without another index. Its identity/time/ID prefix still
serves ordered Activity and historical Alias evidence without a temporary sorting tree. Source lookups and all
other index definitions remain unchanged.

There is still at most one entry per retained target-linked detailed event. Unattributed events remain outside the
partial index; no additional observation, summary, relationship or descriptive text is stored. At R qualifying
events per hour, maximum 365-day retention projects at most 8,760 × R entries. The extra integer's serialized width,
record header and page occupancy determine the storage increase, so it is not a fixed byte cap. Insertions and late
attribution maintain one larger existing index entry through the background writer rather than adding another
index operation; retention and explicit clears remove those entries with their events. The migration rebuild cost
scales with retained target-linked events. A separate fresh synthetic projection using the complete event-index
set and 50,000 integer-only events added 112 KiB of database allocation and approximately 1.1% WAL. Six alternating
insertion samples with 1,250-row transactions showed no measured slowdown. This sample does not establish a
throughput guarantee or include the complete receiver queues, summary work and checkpoint schedule.
Focused SQLite tests verify the implicit plan under tied estimates,
unchanged event ordering, current-evidence protection, complete application-row and allocator preservation, and
rollback/retry after an interrupted populated index build. These are correctness and query-plan checks, not a
complete receiver throughput forecast. Only fresh current creation and the adjacent Application Migrator apply
the replacement; formats 38 through 41 keep their frozen seven-column definition and startup remains validation-only.

### Identity retention lookup

Format 37 adds `idx_trunked_logical_identity_identity(identity_summary_id, radio_system_id, identity_kind_code)`
to the existing logical-call identity bucket table. Both the retention descendant probe and SQLite's exact
identity foreign-key check use an identity-first `SEARCH` through this index. The migration analyzes this table
once so the planner can compare populated index selectivity for the implicit foreign-key lookup after upgrade. The existing system/time primary key
and time-first dashboard index cannot seek that identity without scanning the system's complete bucket history.
Detailed-event source and target probes remain separate seeks through their existing identity indexes.

There is one index entry per existing logical-call identity bucket, with no additional observations or duplicated
names. New entries per hour equal newly populated system/hour/role/identity tuples. With R such tuples per hour,
365-day retention gives at most 8,760 × R entries. Ordinary retention and explicit saved-data cleanup remove entries
with their bucket rows; upserts maintain the index on the background writer. A 50,000-row synthetic sample using
wide system and identity integers occupied 1,138,688 bytes (about 22.8 bytes per row), projecting about 21 MiB for a
981,975-row table. Integer widths, page occupancy, and WAL traffic vary; this is a planning estimate.

Routine cleanup also has a 1,000-row pass cap, a 256-row statement cap, a 250 ms SQLite execution budget per
statement, and a one-second execution budget per pass. SQLite interruption rolls back only the current standalone
statement; earlier completed batches stay committed. The fair cursor advances past a deferred task, and follow-up
passes wait at least five seconds so ingestion and user saves can acquire the writer. These execution budgets
protect receiver continuity; they do not replace the index-backed lookup paths.

### Receiver status alert history

The `receiver_health_incident` table retains the lifecycle of recent Receiver status alerts so a debug report that
includes the SQLite database also includes the issue name, affected scope, explanation, suggested next check, and
open or resolved times. Existing hourly statistics cannot reconstruct those alert-specific fields or their lifecycle.

Rows are written only when an alert opens, changes severity or title, or resolves. A healthy receiver writes zero rows
per hour. Even if an alert repeatedly clears and reopens, the table retains no more than 200 occurrences. Each row has
at most 9,048 bytes of bounded UTF-8 text plus integer, row, and unique-index overhead, so the retained raw text is
bounded below 1.8 MiB and normal usage is much smaller. Lifecycle changes update the same occurrence row, and the
oldest rows are deleted in the same background-writer transaction after the limit is exceeded.

The unique `(process_started_at_ms, occurrence_id)` index supports lifecycle upserts. The integer primary key supports
newest-first reads and pruning without another index. Representative-volume tests require `EXPLAIN QUERY PLAN` for
`ORDER BY id DESC LIMIT 200` to use the primary-key order without a temporary B-tree.

## Verification Required

Schema and query changes must include tests that cover:

- the global format bump, expected prior and target format signatures, adjacent migration step, and populated
  prior-format fixture for every persisted schema or semantic change;
- schema validation, every registered Alpha 8-or-newer source-to-current route, and Application Migrator behavior;
- duplicate-event/output suppression and aggregate correctness;
- radio-system and saved-channel ownership isolation;
- bounded ranges, pagination, and chart point limits;
- representative-volume query plans with no unintended full scan of detailed history; and
- `PRAGMA integrity_check` or `quick_check` after migration tests.

CI must reject persisted-schema fingerprint drift without the matching format bump, migration step, fixture, and
preservation/reset/drop assertions. See [Database Migration Contract](database-migration.md).

Any exception to these guidelines must document the user-visible purpose and the measured storage/query tradeoff in the
change that introduces it.
