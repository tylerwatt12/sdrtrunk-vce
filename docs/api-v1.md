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
| `GET /api/v1/dashboard` | Bounded summary counts, recent receivers, call activity, and top identities. |
| `GET /api/v1/quality` | Current quality across a bounded site page. Global requests cannot include history. |
| `GET /api/v1/alias-lists` | Paged alias-list catalog. |
| `GET /api/v1/alias-lists/{id}/observed-talkgroups` | Paged unmatched or observed talkgroup discovery for one alias list. |
| `GET /api/v1/aliases` | Paged alias catalog and bounded evidence metrics. |
| `GET /api/v1/aliases/{id}` | One alias and its bounded evidence breakdown. |
| `GET /api/v1/systems` | Paged protocol-neutral system scopes. Sites are not embedded. |
| `GET /api/v1/systems/{scope}` | One system scope and its summary. The scope token is opaque. |
| `GET /api/v1/systems/{scope}/sites` | Paged sites owned by the scope. |
| `GET /api/v1/systems/{scope}/group-identities` | Paged talkgroup and patch-group identities. |
| `GET /api/v1/systems/{scope}/group-identities/{talkgroup\|patch_group}/{id}` | One group identity. |
| `GET /api/v1/systems/{scope}/group-identities/{talkgroup\|patch_group}/{id}/activity` | Bounded activity time series for one group identity. |
| `GET /api/v1/systems/{scope}/radios` | Paged radio identities. |
| `GET /api/v1/systems/{scope}/radios/{id}` | One radio identity. |
| `GET /api/v1/systems/{scope}/talker-aliases` | Paged latest talker aliases. |
| `GET /api/v1/systems/{scope}/relationships` | Paged radio-to-group or group-to-radio relationships. |
| `GET /api/v1/sites/{guid}` | One protocol-neutral trunked site. |
| `GET /api/v1/sites/{guid}/channels` | Paged observed channels. |
| `GET /api/v1/sites/{guid}/group-identities` | Bounded leading group identities for a time range. |
| `GET /api/v1/sites/{guid}/quality` | Bounded current and historical site quality. |
| `GET /api/v1/sites/{guid}/frequency-bands` | P25 home bands plus paged foreign-system bands. |
| `GET /api/v1/sites/{guid}/neighbors` | Paged neighbors. |
| `GET /api/v1/sites/{guid}/patch-groups` | Paged P25 patch groups with bounded members. |
| `GET /api/v1/activity` | Cursor-paged detailed activity. |
| `GET /api/v1/conventional-contexts` | Paged conventional receiver contexts. |
| `GET /api/v1/conventional-contexts/{context}` | One context and a paged RF summary. |
| `GET /api/v1/conventional-contexts/{context}/talkgroups` | Paged DMR conventional talkgroups. |
| `GET /api/v1/conventional-contexts/{context}/radios` | Paged DMR conventional radios. |
| `GET /api/v1/diagnostics/tuners` | Available passive tuner diagnostic targets. |

Common collection parameters are `limit`, `offset`, `q`, `sort`, and `direction`. `limit` defaults to 100 and must be
between 1 and 500; `offset` must be between 0 and 100,000. Detailed activity uses the positive `before_id` cursor.
Endpoint-specific filters are documented by the returned resource and reject unknown names.

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
unbounded aggregate of source strings.

Quality history uses `range`, `points`, and `include_history`. `points` must be between 60 and 360. Historical points
require a site-scoped request; this prevents a site count multiplied by a history count from creating an oversized
response.

## Protocol presentation

Persisted system resources use lowercase `protocol`: `p25`, `dmr`, `nxdn`, or `nbfm`. Live decoder records can also
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

`system-radios` CSV exports accept the same `affiliated` and `site_guid` filters as the JSON collection. Nested
presence is not serialized into CSV; the export retains the scalar affiliation fields.

Live streams are:

- `/api/v1/live/channel-activity`
- `/api/v1/live/decode-events`
- `/api/v1/live/decode-messages`
- `/api/v1/live/channel-diagnostics`
- `/api/v1/live/tuner-diagnostics`
- `/api/v1/live/sites`
- `/api/v1/live/calls`
- `/api/v1/live/activity`

SSE and diagnostic state JSON use the same `snake_case` and protocol presentation as REST. Initial channel snapshots
are capped at 128 tables, 256 rows per table, 2,048 rows total, and 1 MiB after encoding. Unchanged subscribers share
the cached encoded snapshot. Site snapshots are capped at 256 sites, strings and tags are bounded, and raw decoder
collections are reduced to scalar summaries before retention. Live HTTP admission is capped at 64 clients; each SSE
hub, message stream, diagnostic stream, queue, and frame buffer has its own smaller bound.

Completed call audio is fetched from `/api/v1/calls/{id}/audio`. A call is rejected before encoding if its WAV would
exceed 16 MiB. Pending WAV work is limited to 16 MiB total, the completed-call cache is limited to 512 calls and
128 MiB, and the call ID must be one strict path segment. The server does not accept encoded separator smuggling.

`/api/v1/live/decode-messages` accepts exactly `configuration_id` and required positive `frequency_hz` parameters.
`timeslot` is available on decoder-event and channel-diagnostic scopes, but is rejected for decoder-message streams
because that service does not filter messages by timeslot.

## Authentication and administration

Authentication remains under `/api/v1/auth/*`. User, access-policy, alias-list, and alias administration remain under
`/api/v1/admin/*`. They use the same success/error envelopes and `snake_case` contract. Mutations require the session's
CSRF token and the capability enforced for that resource.

Session `capabilities` is an object whose values are booleans. The administrator access-policy resource returns one
`capabilities` array; each entry has `id`, `display_name`, `required_tier`, `default_tier`, and `configurable`.
Alternate array, map, tier-object, numeric, and string capability representations are not supported.

Wire enum values are explicit and case-sensitive; Java enum names and alternate casing are rejected:

- Access tiers are `public`, `user`, and `admin`.
- Alias-list families are `p25`, `dmr`, `nxdn`, and `nbfm`.
- Alias matcher types are `talkgroup`, `talkgroup_range`, `radio`, `radio_range`, `user_status`, `unit_status`,
  `tone_sequence`, `dcs`, and `esn`.
- Alias matcher protocols are `p25`, `dmr`, `nxdn`, `nbfm`, `fleetsync`, and `mdc1200`. A P25 matcher also requires
  `variant` set to `phase_1` or `phase_2`; internal names such as `APCO25_PHASE2` are never accepted or returned.
- `unmatched_talkgroup_policy` uses the same fields in reads and writes: `listen_enabled`, `priority`, `recordable`,
  and `broadcast_channels`.
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
loads at most 500 events and 64 patch members per event.
