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
- Normalize repeated system, site, receiver-context, talkgroup, radio, and alias identities. Reference their compact
  integer key from high-volume tables.
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
  actual scope, time range, and ordering used by the website.
- Use partial indexes when a query targets a sparse state and the predicate is stable.
- Never add indexes merely to silence a theoretical concern. Test the real query against representative row counts.

## Write-Path Rules

- Decoder, tuner, recording, and streaming threads must never perform SQLite work directly.
- Route records through the bounded statistics queue and single background writer.
- Batch related writes in one transaction and use atomic upserts for counters and summaries.
- A single observed call/output must increment each intended aggregate once. Patch-group fan-out, retries, or provider
  delivery attempts must not multiply the original-call count.
- Keep normal runtime paths validation-only for existing schemas. Schema creation belongs to the startup schema routine;
  supported deployed changes belong exclusively to the bundled Application Migrator, which must back up the database,
  migrate a staged copy, and complete schema, integrity, and foreign-key validation before atomic promotion.

## Website Query Rules

- Bound every time range, page size, result count, and chart point count on the server.
- Aggregate in SQL and return only fields rendered by the client. Avoid `SELECT *` on high-volume tables.
- Scope identifiers by their owning system/site/context; a talkgroup or radio number alone is not globally unique.
- Keep chart payloads coarse and predictable. Zero-fill missing buckets in the bounded API response rather than storing
  empty database rows.
- Do not make dashboard requests scan detailed event history. Dashboards and directory pages must use summaries and
  buckets only.

## Verification Required

Schema and query changes must include tests that cover:

- schema validation and Application Migrator behavior;
- duplicate-event/output suppression and aggregate correctness;
- system/site/context scoping;
- bounded ranges, pagination, and chart point limits;
- representative-volume query plans with no unintended full scan of detailed history; and
- `PRAGMA integrity_check` or `quick_check` after migration tests.

Any exception to these guidelines must document the user-visible purpose and the measured storage/query tradeoff in the
change that introduces it.
