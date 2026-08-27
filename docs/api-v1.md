# Web API v1

The supported web API is rooted at `/api/v1`. This is a hard version boundary: earlier unversioned read, export,
live, and call-audio paths are not registered.

## JSON contract

Successful object responses use:

```json
{"data": {}}
```

Successful collections place the rows in `data` and bounded paging or result information in `meta`:

```json
{"data": [], "meta": {"limit": 100, "offset": 0, "has_more": false}}
```

Compound resources name each result set inside `data` and keep only limits, cursors, and truncation state in `meta`.
For example, frequency bands use `data.home_bands` and `data.foreign_bands`; patch groups use `data.groups`,
`data.talkgroups`, and `data.radios`.

Errors use one shape at every v1 JSON endpoint:

```json
{"error": {"code": "invalid_parameter", "message": "limit must be between 1 and 500", "status": 400, "field": "limit"}}
```

Field names and query parameters are `snake_case`. Unknown or repeated parameters, malformed percent encoding,
unsupported sort fields, ambiguous identifiers, and invalid booleans are rejected. Resource identifiers in paths are
decimal unless the identifier is explicitly documented as an opaque string, such as a system scope token, receiver
GUID, conventional context key, tuner target, or call ID.

Path segments are decoded as strict UTF-8 exactly once. A literal `+` remains a plus in a path (it is not a space),
and double-encoded or separator-smuggling resource names are rejected.

## Read resources

| Resource | Purpose |
| --- | --- |
| `GET /api/v1/status` | Server, database, logging, live-stream, and web-call status. |
| `GET /api/v1/live/settings` | Non-sensitive receiver-wide Live presentation policy for viewers authorized for Live. |
| `GET /api/v1/dashboard` | Bounded summary counts, recent receivers, call activity, and top identities. |
| `GET /api/v1/quality` | Current quality across a bounded site page. Global requests cannot include history. |
| `GET /api/v1/alias-lists` | Paged alias-list catalog. |
| `GET /api/v1/alias-lists/{id}/observed-talkgroups` | Paged unmatched or observed talkgroup discovery for one alias list. |
| `GET /api/v1/aliases` | Paged alias catalog and bounded evidence metrics. |
| `GET /api/v1/aliases/{id}` | One alias and its bounded evidence breakdown. |
| `GET /api/v1/scan-lists` | Published scan lists available to the signed-in browser listener. |
| `GET /api/v1/systems` | Paged protocol-neutral system scopes with an optional bounded site preview. |
| `GET /api/v1/systems/{scope}` | One system scope and its summary. The scope token is opaque. |
| `GET /api/v1/systems/{scope}/sites` | Paged sites owned by the scope. |
| `GET /api/v1/systems/{scope}/group-identities` | Paged talkgroup and patch-group identities. |
| `GET /api/v1/systems/{scope}/group-identities/{talkgroup\|patch_group}/{id}` | One group identity. |
| `GET /api/v1/systems/{scope}/group-identities/{talkgroup\|patch_group}/{id}/activity` | Bounded activity time series for one group identity. |
| `GET /api/v1/systems/{scope}/radios` | Paged radio identities. |
| `GET /api/v1/systems/{scope}/radios/{id}` | One radio identity. |
| `GET /api/v1/systems/{scope}/talker-aliases` | Paged latest talker aliases. |
| `GET /api/v1/systems/{scope}/relationships` | Paged radio-to-group or group-to-radio relationships. |
| `GET /api/v1/sites/{guid}` | One protocol-neutral trunked site. P25 rows include the nullable configured `p25_decoder_mode` field (`C4FM` or `CQPSK`). |
| `GET /api/v1/sites/{guid}/channels` | Paged observed channels. |
| `GET /api/v1/sites/{guid}/group-identities` | Bounded leading group identities for a time range. |
| `GET /api/v1/sites/{guid}/quality` | Bounded current and historical site quality. |
| `GET /api/v1/sites/{guid}/frequency-bands` | P25 home bands plus paged foreign-system bands. |
| `GET /api/v1/sites/{guid}/neighbors` | Paged neighbors. |
| `GET /api/v1/sites/{guid}/patch-groups` | Paged P25 patch groups with bounded members. |
| `GET /api/v1/activity` | Cursor-paged detailed activity. |
| `GET /api/v1/activity/actions` | Activity action totals for a selected time range. Dashboard access is required. |
| `GET /api/v1/activity/radios` | Paged exact SOURCE-radio aggregation across retained matching activity events. Systems & Sites access is required. |
| `GET /api/v1/conventional-contexts` | Paged conventional receiver contexts. |
| `GET /api/v1/conventional-contexts/{context}` | One context and a paged RF summary. |
| `GET /api/v1/conventional-contexts/{context}/talkgroups` | Paged DMR conventional talkgroups. |
| `GET /api/v1/conventional-contexts/{context}/radios` | Paged DMR conventional radios. |
| `GET /api/v1/diagnostics/tuners` | Available passive tuner diagnostic targets. Administrator access is required. |

Common collection parameters are `limit`, `offset`, `q`, `sort`, and `direction`. `limit` defaults to 100 and must be
between 1 and 500; `offset` must be between 0 and 100,000. Detailed activity uses the positive `before_id` cursor.
The exact activity-radio aggregation is the one exception to the shared offset ceiling: its non-negative offset has
no fixed first-N window, while every response remains limited to at most 500 rows. Endpoint-specific filters are
documented by the returned resource and reject unknown names.

The systems directory accepts `include_site_preview=true`. Preview requests are limited to 25 parent systems and add
`site_preview` and `site_preview_truncated` to each system row. The page metadata reports the
fixed `site_preview_limit_per_system`. The preview is normally ordered by most recently observed site and then GUID.
When `q` matches a site, matching sites are placed first so that the bounded preview includes the result. Use the
independently paged `/api/v1/systems/{scope}/sites` resource for the complete collection.

Migration effects are specific to each registered database-format step. The format 3 to 4 step preserves supported
configuration, detailed P25 activity and site telemetry, conventional and DMR summaries, and trunked-site summaries.
It also carries conventional call-identity rows and non-call trunked signaling counters into their format-4 tables.
Legacy physical receiver-leg call, frequency, and talkgroup measures cannot be translated reliably into logical
calls, and the old trunked identity scopes cannot be split unambiguously by Alias List, so those projections restart
at an explicit collection boundary. Logical-call and P25 site-observation API totals therefore include only calls
resolved after migration, while the compatible activity and summary history remains available.

The two activity-analysis resources use `range=1h|6h|24h|7d|30d` without the former `group_by` compatibility mode.
`GET /api/v1/activity/actions` accepts only `range`. Its collection metadata is `range`, `from_ms`, `to_ms`, and
`total`; its `data` array contains the available action totals and omits `CONTINUE`. `total` is the sum of those
returned action counts across trunked and conventional hourly summaries.

`GET /api/v1/activity/radios` accepts `range`, the required `action`, and standard `limit` and `offset` paging.
`CONTINUE` is rejected because continuation events are intentionally not retained as detailed SOURCE-radio events.
SQLite aggregates every retained detailed event matching the requested range and action before applying the output
page, so there is no event cap, context-hour slice cap, sample, coverage mode, or hidden first-N result window. The
aggregation is exact for the retained detailed history; configured retention can still make the compact summary total
larger than the retained detailed-event count.

Each trunked result row represents one `(scope_token, radio_id)` pair, so the same numeric radio ID remains separate
when it appears on different systems. Activity without a trunked identity scope is grouped by
`(context_key, radio_id)`, which keeps conventional channels separate. Rows provide the applicable `scope_token` or
`context_key`, protocol and display context, `radio_id`, available `alias_name` and `alias_description`, `event_count`,
and `last_seen_ms`. They are ordered by `event_count` descending, then `last_seen_ms` descending, with scope/context
and radio ID as deterministic tie breakers before `limit` and `offset` are applied.

Radio-page metadata is `range`, `action`, `from_ms`, `to_ms`, `action_total`, `retained_event_count`,
`identified_event_count`, `unknown_source_event_count`, `total_count`, `limit`, `offset`, `has_more`, and
`next_offset`. `action_total` is the compact summary total for the selected action. `retained_event_count` counts its
retained detailed events, `identified_event_count` counts those with a SOURCE radio, and
`unknown_source_event_count` counts those without one. `total_count` is the number of aggregated SOURCE-radio rows
before paging. Removed parameters, including `group_by`, are rejected, and `/api/v1/activity-analytics` is not
registered.

Every database row materializer also has a 20,000-row emergency ceiling. This is a final guard against a future SQL
regression; normal endpoint, enrichment, member, history, and export limits are substantially smaller.

System radio pages and radio-to-group relationship pages accept `affiliated=true|false` and `site_guid={guid}`.
Both filters are applied by SQLite before the bounded page is selected, and `total_count` reflects the same filters.
A relationship request must include `radio_id` or `talkgroup_id`; `kind=patch_group` is valid only with
`talkgroup_id`. Patch groups are never current affiliations.

Radio and relationship rows expose `currently_affiliated`. A radio row also exposes
`affiliated_talkgroup_id` and `affiliation_confirmed_at_ms` when a current affiliation exists. Authoritative
site-local evidence is represented by one nullable `presence` object:

```json
{
  "presence": {
    "evidence": "registration",
    "confirmed_at_ms": 1786300000000,
    "site": {
      "guid": "receiver-site-guid",
      "protocol": "p25",
      "wacn": 781824,
      "system_id": 840,
      "nac": 1183,
      "rfss": 1,
      "site_id": 2,
      "configured_site": "North",
      "configured_name": "North Control",
      "channel_name": "North Simulcast"
    }
  }
}
```

`evidence` is `registration` or `affiliation`. Calls, grants, aliases, and generic last-seen traffic do not create
authoritative presence. `site.guid` is nullable when the decoder has authoritative protocol-native site identifiers
but no receiver GUID; the presence object is retained in that case. Registration-only presence does not set
`currently_affiliated` and is not included in
affiliation aggregates. A regular talkgroup detail reports `affiliated_radios` and the distinct
`affiliated_sites`; a system reports `affiliated_radios`; a site reports the `affiliated_radios` whose current
presence points to that site. The former `/api/v1/systems/{scope}/affiliations` collection is not registered.

Site channel pages collapse multiple logical observations of one physical downlink into one row. Each row carries
one bounded representative channel key, descriptor, and callsign plus `logical_channel_count`,
`logical_channels_included`, and `logical_channels_truncated`; tags are fixed protocol categories rather than an
unbounded aggregate of source strings. P25 site details expose `active_rfss_network_connection` and retain a
current-site System ID even when a Network Status Broadcast has not supplied a WACN. Callsigns remain available from
retained channel evidence after the current site facts are cleared.

Quality history uses `range`, `points`, and `include_history`. `points` must be between 60 and 360. Historical points
require a site-scoped request; this prevents a site count multiplied by a history count from creating an oversized
response.

## Protocol presentation

Persisted system resources use lowercase `protocol`: `am`, `p25`, `dmr`, `nxdn`, or `nbfm`. Live decoder records can also
use `ars`, `cellocator`, `dcs`, `fleetsync`, `ipv4`, `lojack`, `lrrp`, `mdc1200`, `tait1200`, `udp`, or `unknown`.
Database protocol, variant, scope, identity-domain, and surrogate system keys are not part of the API.

- System scopes expose `scope_kind`, `address_domain`, and opaque `scope_token`.
- System summary counts distinguish `group_identities`, ordinary `talkgroups`, and `patch_groups`.
- DMR and NXDN variants use stable names such as `tier_iii`, `capacity_plus`, `type_c`, and `type_d`.
- DMR sites expose `model`; NXDN sites expose `location_category`.
- Every trunked site uses `site_kind: "trunked"`; protocol site numbers use `site_id`.
- NXDN Type-D identities retain their decimal value and add a formatted `*_display` value.
- `capabilities` uses the same feature names on every protocol: `sites`, `group_identities`, `radios`, `activity`,
  `talker_aliases`, `current_affiliations`, `radio_site_presence`, `channels`, `neighbors`, `quality`,
  `frequency_bands`, and `patch_groups`. `radio_site_presence` and `current_affiliations` are independent
  capabilities; both are currently true only for P25.

Unsupported features are absent from the route's data or have a false capability; a protocol-specific alternate API
is not exposed.

## Exports, live data, and media

CSV exports use `GET /api/v1/exports/{dataset}.csv`. Supported datasets are `aliases`, `signal-health`,
`site-quality`, `system-talkgroups`, `system-radios`, `site-channels`, `site-neighbors`, `conventional-channels`,
`conventional-talkgroups`, and `conventional-radios`. One export can run at a time. Buffered exports stop at 10,000
rows or 16 MiB instead of attempting an unbounded materialization.

Alias-list and alias resources, including `aliases.csv`, require administrator Alias access. Resolved alias labels in
Live, Systems, conventional activity, and call playback remain part of those resources; only the configuration
catalog and export are administrator-only.

`system-radios` CSV exports accept the same `affiliated` and `site_guid` filters as the JSON collection. Nested
presence is not serialized into CSV; the export retains the scalar affiliation fields.

Pages that use live receiver features share one multiplexed connection per browser document:

- `GET /api/v1/live/multiplex?client_id={uuid}` opens the framed stream.
- `POST /api/v1/live/multiplex/control` replaces that client's logical subscriptions using a monotonically increasing
  `revision` and a `subscriptions` object.

Supported subscription names are `channel_activity`, `decode_events`, `decode_messages`, `channel_diagnostics`,
`tuner_diagnostics`, and `calls`. Their parameters use the same `snake_case` names and validation
as the corresponding REST scopes. `decode_messages` accepts exactly `configuration_id` and required positive
`frequency_hz`; `timeslot` is available for decoder events and channel diagnostics, but not decoder messages. Tuner
viewport changes update the existing logical subscription without reconnecting or rebuilding its shared source FFT.
Historical Activity views use bounded `GET /api/v1/activity` polling and are not multiplex subscriptions.
Channel-activity table snapshots include protocol-neutral `system_name`, `site_name`, and `channel_name` context plus
an `identifiers` list of `{group, label, value}` fields learned from the active protocol. Activity rows expose the
available callsign, source and target forms/IDs/aliases, talker alias, LCN, timeslot, signal level, decoder, and call
role without requiring a consumer to infer those fields from protocol-specific text. When control-channel quality is
available, `cc_last_valid_decode_ms` carries the last-valid-decode epoch-millisecond timestamp from the same immutable
activity snapshot.
Channel diagnostic state keeps the normalized `protocol` separate from the human-readable `decoder_profile`, which
may include the currently selected demodulator profile for an automatic decoder. Selected-channel diagnostics carry
a shared 512-bin signal FFT at five frames per second and bounded batches of demodulated symbols when the decoder
supports them. At most one selected-channel diagnostic producer runs globally; additional viewers of that channel
share it, while a different channel waits for capacity.

The stream uses a small fixed binary envelope around JSON event payloads and existing binary diagnostic frames.
Initial channel snapshots are capped at 128 tables, 256 rows per table, 2,048 rows total, and 1 MiB after encoding.
Unchanged subscribers share the cached encoded snapshot. Strings and tags are bounded before retention. Admission is
capped at 32 browser documents. Metadata uses one bounded FIFO per topic, so a burst cannot evict another topic.
Dense diagnostic topics use replaceable latest-value slots, and a persistently slow client is disconnected instead of
accumulating data or applying receiver backpressure. Calls and channel activity retain authoritative recovery
snapshots.

`decode_events` and `decode_messages` are deliberately live-only. Opening either subscription starts empty; the
server projects each new item on a bounded observer worker, publishes it, and forgets it. It does not preload decoder
history or retain a replay cache. A bounded queue or connection loss emits `live_gap` with the number of known dropped
items and then continues with new data. A decoder-event subscription first emits an authoritative `filter_catalog`
containing every event category and type, including types that have not yet been observed. Opening a decoder-message
stream emits its initial source status as `source_change`, and later source rebinds emit the same event. A bound source
state includes `filter_catalog`; an unbound source uses `null`. The catalog contains a deterministic `signature`, an
ordered tree of `{key, label, children}` nodes, and the source's available `timeslots`. Message rows carry the matching
stable `filter_key` and friendly `filter_label`. Catalog/source-state frames are coalesced authoritative state and
clear older queued rows for the same topic, but neither event causes a history snapshot or replay.

Browser filtering is local and does not change the subscription. Filter choices come from the complete source catalog,
not from rows already received, so an unseen or rare message type can be selected while the table is empty. The browser
retains only a bounded in-memory capture for the current page and selected channel, applies the selected types,
timeslots, validity, and text search before the display limit, renders the newest configured number of matching rows,
and clears that capture on page, tab, or selection change.

The `calls` logical subscription accepts `scan_list_id` as an array of positive IDs. Duplicate IDs are folded
together. Omitting the field selects the published default scan list; an unknown or unpublished ID is rejected. Each
completed call is published once with all matching `scan_list_ids`, so overlapping selections never duplicate it.
Call events also include `started_at_ms`, `order_sequence`, and `conversation_key`. If one listener's bounded server
queue overflows, that subscription receives a `missed` event with the exact `missed_calls` count and
`reason: "server_queue_overflow"`.

The browser orders calls inside each conversation by `started_at_ms`. It may play up to four calls from one
conversation before choosing the oldest waiting call from another conversation, favoring coherent exchanges without
allowing one busy talkgroup to monopolize playback. This grouping deliberately does not promise a perfect global
timeline.

Completed call audio is fetched from `/api/v1/calls/{id}/audio`. A call is rejected before encoding if its WAV would
exceed 16 MiB. Pending WAV work is limited to 16 MiB total. The operator can bound active audio listeners, selected
scan lists per listener, waiting calls per listener, cached calls, and cached audio MiB. The call ID must be one strict path
segment; encoded separator smuggling is rejected. Audio is retained only for completed calls that match at least one
published scan list while a listener is connected. The listener limit also bounds concurrent WAV responses; excess
audio fetches receive `429 too_many_audio_responses` and are counted on Listener Status.

## Authentication and administration

Authentication remains under `/api/v1/auth/*`. User, access-policy, alias-list, alias, scan-list, and web-audio
administration remain under `/api/v1/admin/*`. They use the same success/error envelopes and `snake_case` contract.
Mutations require the session's CSRF token and the capability enforced for that resource.

Scan-list administration uses:

- `GET, POST /api/v1/admin/scan-lists`
- `GET, PUT, DELETE /api/v1/admin/scan-lists/{id}`
- `PUT /api/v1/admin/scan-lists/{id}/members`
- `GET, PUT /api/v1/admin/web-audio`

Receiver-wide Live presentation uses:

- `GET, PUT /api/v1/admin/web-display`
- `GET /api/v1/live/settings`

The administrator document includes encryption-detail visibility, idle-row call-detail retention, control and voice
decode-quality visibility, voice-quality clearing, decode-quality format, trunked idle-grant retention, and
`live_detail_matching_row_limit` (25–500, default 200). The Live endpoint returns the same non-sensitive policy to a
viewer authorized for Live so presentation policy does not depend on Dashboard access. Updates may contain any
non-empty subset and are serialized as one receiver-wide settings mutation.

Web-audio configuration uses `waiting_calls_per_listener` as the administrator-owned browser waiting-call policy.
Listeners receive and obey the effective value but do not receive a control for changing it.

A scan-list definition contains `sort_order`, `name`, optional `description`, `published`, and `default`. Exactly one
list is the published default. Up to 100 definitions are supported. A membership update uses `operation` (`add`,
`remove`, or `replace`), `alias_ids`, and `unmatched_alias_list_ids`; each owner collection accepts at most 500 unique
IDs. The latter assigns a whole Alias List's unmatched talkgroups to the scan list. Detail responses report separate
totals and explicit truncation when either member set is larger than 500. Scan-list summaries likewise report
`alias_count` and `unmatched_alias_list_count`. Either owner collection may be omitted to leave that class of owners
unchanged, including during `replace`; an explicitly empty collection clears that class during `replace`.

RadioReference lookup administration is rooted at `/api/v1/admin/radioreference` and requires a valid Premium
session:

- `GET` reports account, stored-credential, and lookup-location state; `PUT`/`DELETE /session` sign in or out, and
  `PUT /location` updates the receiver's lookup region.
- `GET /countries` and `/states` provide the bounded lookup-region choices.
- `GET /frequencies` searches the configured lookup region for an exact frequency, and
  `GET /frequencies/details` loads bounded category, site, and channel-use details for one result.

The browser API does not import RadioReference systems, sites, talkgroups, or conventional channels. Use the desktop
Configuration Editor for those workflows.

Session `capabilities` is an object whose values are booleans. The administrator access-policy resource returns one
`capabilities` array; each entry has `id`, `display_name`, `required_tier`, `default_tier`, and `configurable`.
Alternate array, map, tier-object, numeric, and string capability representations are not supported.
The `site-access` capability is a global minimum applied before every feature capability. Setting it to `user` or
`admin` protects all receiver pages, APIs, live transports, audio, diagnostics, and exports; the static application
shell and `/api/v1/auth/*` remain reachable so a user can establish a session.

Wire enum values are explicit and case-sensitive; Java enum names and alternate casing are rejected:

- Access tiers are `public`, `user`, and `admin`.
- Alias-list families are `p25`, `dmr`, `nxdn`, and `nbfm`.
- Alias matcher types are `talkgroup`, `talkgroup_range`, `radio`, `radio_range`, `user_status`, `unit_status`,
  `tone_sequence`, `dcs`, and `esn`.
- Alias matcher protocols are `am`, `p25`, `dmr`, `nxdn`, `nbfm`, `fleetsync`, and `mdc1200`. A P25 matcher also requires
  `variant` set to `phase_1` or `phase_2`; internal names such as `APCO25_PHASE2` are never accepted or returned.
- Alias reads, creates, and updates include `scan_list_ids`. Creation assigns the durable Alias ID and saves its
  initial memberships in one configuration transaction. The scan-list member endpoint remains available for later
  bounded bulk changes.
- `unmatched_talkgroup_policy` includes `recordable`, `broadcast_channels`, and `scan_list_ids`. Its update replaces
  all three atomically. The scan-list IDs route calls only when the received talkgroup has no exact Alias or covering
  range in that Alias List; an exact or range Alias uses its own scan-list membership instead.
- Bulk alias `group_operation` values are `set` and `clear`; `stream_operation` values are `add`, `remove`, `replace`,
  and `clear`. DCS and tone option values are lowercase.

Alias-list delete impact is counted without materializing the matching aliases and stops at 501 items. The API refuses
to delete a list affecting more than 500 aliases or channels with status `413`, code `alias_list_delete_too_large`,
and field `alias_count` or `channel_count`.

Alias evidence and resolution are page-targeted instead of loading the full alias corpus. Enrichment accepts at most
1,000 aliases, 500 coverage scopes or ranges, 10,000 coverage/evidence pairs, 256 lists or systems, 512 system/list
pairs, 20,000 exact list/identity lookup pairs, 10,000 scope/range evidence targets, and 4,096 matching rules. Alias
list ownership remains correlated with each identity or range through rule lookup and scope projection, so data from
another selected list cannot consume the request's row budget. Only two enrichment requests run concurrently; excess
requests fail fast with `429` instead of multiplying their working sets.

An alias or unmatched-talkgroup policy can reference at most 64 broadcast channels, and channel names are limited to
256 characters. This is enforced by the shared configuration service after every mutation, including repeated bulk
`add` operations; the HTTP adapter and read model use the same invariant.

P25 patch-group pages are limited to 100 groups. They include at most 32 talkgroup and 32 radio members per group and
512 members of each type across the response, with original counts and explicit truncation metadata. Detailed activity
loads at most 500 events, and talkgroup filters include retained activity whose patch-member table contains the selected
talkgroup.
