# Web API v1

> **Release scope:** This document describes the current `main` and Nightly interface. Numbered Alpha builds may omit
> newer endpoints or website features; use the documentation shipped with the installed Alpha.

The supported web API is rooted at `/api/v1`. This is a hard version boundary. Earlier unversioned read, export,
live, and call-audio paths are not registered.

## JSON contract

Successful object responses use:

```json
{"data": {}}
```

Collections put rows in `data` and bounded paging information in `meta`:

```json
{"data": [], "meta": {"limit": 100, "offset": 0, "total_count": 0, "has_more": false}}
```

Compound resources name each result set inside `data`. For example, frequency bands use `home_bands` and
`foreign_bands`, while patch groups use `groups`, `talkgroups`, and `radios`.

Errors have one shape:

```json
{"error": {"code": "invalid_parameter", "message": "limit must be between 1 and 500", "status": 400, "field": "limit"}}
```

Field names and query parameters are `snake_case`. Unknown or repeated parameters, malformed UTF-8, unsupported
sort fields, ambiguous identifiers, and invalid booleans are rejected. Path segments are decoded once. A literal
`+` remains a plus, and encoded separators are rejected.

## Identity model

The API has two top-level radio resources:

- A **radio system** groups trunked P25, DMR, or NXDN activity. Its public identifier is the opaque
  `radio_system_key`. Clients must not parse it or build one from labels.
- A **channel** is one saved receiver configuration, whether trunked or conventional. Its public identifier is the
  saved channel's canonical lowercase UUID in `configuration_id`.

Trunked talkgroup, patch-group, and radio IDs are meaningful only inside one radio system, so their detail paths
include `radio_system_key`. Conventional group and radio observations belong to one saved channel and are exposed
only under `/channels/{configuration_id}`. The same number on another system or channel is a different identity. A
channel's user-facing name, configured site label, Alias List, and frequency are properties of that channel; they are
not radio-system identity.

Resources can include an explicit `entity_ref` for browser navigation:

```json
{"kind": "channel", "key": "00000000-0000-0000-0000-000000000072"}
```

```json
{"kind": "talkgroup", "radio_system_key": "p25:bee00:49f", "id": 56735}
```

Valid kinds are `radio_system`, `channel`, `talkgroup`, `patch_group`, and `radio`. Consumers should use `kind`
instead of guessing a resource type from whichever fields happen to be present.

## Read resources

| Resource | Purpose |
| --- | --- |
| `GET /api/v1/status` | Server, database, logging, and live-stream status. |
| `GET /api/v1/me/preferences` | The signed-in user's bounded browser preference document and revision. |
| `GET /api/v1/dashboard` | Bounded summary counts, recent channels, call activity, and top identities. |
| `GET /api/v1/quality` | Current quality across a bounded channel page. Global requests cannot include history. |
| `GET /api/v1/alias-lists` | Paged Alias List catalog. |
| `GET /api/v1/alias-lists/{id}/observed-group-identities` | Paged unmatched talkgroup and patch-group discovery for one Alias List. |
| `GET /api/v1/aliases` | Paged Alias catalog and bounded activity metrics. |
| `GET /api/v1/aliases/{id}` | One Alias and its bounded activity breakdown. |
| `GET /api/v1/scan-lists` | Published Scan Lists available to the signed-in browser listener. |
| `GET /api/v1/radio-systems` | Paged radio systems with an optional bounded channel preview. |
| `GET /api/v1/radio-systems/{radio_system_key}` | One radio system and its summary. |
| `GET /api/v1/radio-systems/{radio_system_key}/channels` | Paged saved channels assigned to the radio system. |
| `GET /api/v1/radio-systems/{radio_system_key}/group-identities` | Paged talkgroups and patch groups. |
| `GET /api/v1/radio-systems/{radio_system_key}/group-identities/{talkgroup\|patch_group}/{id}` | One group identity. The path spelling is exactly `patch_group`. |
| `GET /api/v1/radio-systems/{radio_system_key}/group-identities/{talkgroup\|patch_group}/{id}/activity` | Bounded activity history for one group identity. |
| `GET /api/v1/radio-systems/{radio_system_key}/radios` | Paged radio identities. |
| `GET /api/v1/radio-systems/{radio_system_key}/radios/{id}` | One radio identity. |
| `GET /api/v1/radio-systems/{radio_system_key}/talker-aliases` | Paged latest over-the-air talker aliases. |
| `GET /api/v1/radio-systems/{radio_system_key}/relationships` | Paged radio-to-group or group-to-radio relationships. |
| `GET /api/v1/channels` | Paged saved trunked and conventional channels. |
| `GET /api/v1/channels/{configuration_id}` | One saved channel and its summary. |
| `GET /api/v1/channels/{configuration_id}/frequencies` | Paged learned or configured frequencies for a trunked channel. |
| `GET /api/v1/channels/{configuration_id}/group-identities` | Paged group identities observed on the channel. Trunked channels also accept a time range. |
| `GET /api/v1/channels/{configuration_id}/radios` | Paged radio identities observed on the channel. |
| `GET /api/v1/channels/{configuration_id}/quality` | Current and bounded historical control-channel quality. |
| `GET /api/v1/channels/{configuration_id}/frequency-bands` | Effective P25 home bandplan and ISSI-advertised foreign bands. |
| `GET /api/v1/channels/{configuration_id}/neighbors` | Paged RF-site neighbors learned by that channel. |
| `GET /api/v1/channels/{configuration_id}/patch-groups` | Bounded P25 patch groups and members learned by that channel. |
| `GET /api/v1/activity` | Cursor-paged detailed activity. |
| `GET /api/v1/activity/actions` | Activity action totals for a selected time range. Dashboard access is required. |
| `GET /api/v1/activity/radios` | Paged SOURCE-radio aggregation across retained matching activity events. Radio access is required. |
| `GET /api/v1/calls/feed` | Live-edge, cursor-based completed-call announcements for selected Scan Lists. |
| `GET /api/v1/calls/{id}/audio` | WAV audio for one call still in the shared bounded ring. |
| `GET /api/v1/diagnostics/tuners` | Passive tuner diagnostic targets. Administrator access is required. |
| `GET /api/v1/receiver-health` | Bounded receiver-health snapshot. Administrator access is required. |

Common collection parameters are `limit`, `offset`, `q`, `sort`, and `direction`. `limit` defaults to 100 and must be
between 1 and 500; `offset` must be between 0 and 100,000. Detailed activity uses the positive `before_id` cursor.
Every database materializer also has a 20,000-row emergency ceiling. Normal endpoint and export limits are lower.

### Radio-system directory

`GET /api/v1/radio-systems` accepts `include_channel_preview=true`. Preview requests are limited to 25 parent systems
and a fixed number of channels per system. Metadata reports `channel_preview_limit_per_system`. The browser adds its
own `directory_type` while flattening system rows and their channel previews; that display-only field is not part of
the API resource.

The complete channel collection is always available at
`/api/v1/radio-systems/{radio_system_key}/channels`. Radio-system responses report every distinct Alias List assigned
to their channels. A radio system never selects one arbitrary Alias List as its owner. A system-level identity Alias
is returned only when every applicable channel Alias List resolves to the same effective Alias; a conflict leaves the
Alias blank. Channel resources resolve Aliases through that exact channel's `alias_list_id`.

### Channels and protocol features

The channel directory accepts `type` and `protocol` filters and reports a `channel_kind` of `TRUNKED` or
`CONVENTIONAL`. Protocol-specific child resources are advertised in the channel's `capabilities`; unsupported
features are false or absent. There is no separate conventional information architecture.

AM and NBFM use the channel as the primary user-facing identity. Their configured numeric destination can still be
returned as target metadata because it participates in Alias and Scan List routing, but it must not be presented as
the identity controlled by Hold or Avoid. Conventional P25, DMR, and NXDN can expose real group identities. DMR rows
also preserve timeslot context.

Channel group and radio collections accept `q`, `sort`, `direction`, `limit`, and `offset`; search includes the
numeric ID and the Alias assigned by that exact channel's Alias List. Trunked group collections additionally accept
`range=1h|6h|24h|7d|30d`. Conventional group collections use their stored summaries and reject `range` instead of
silently ignoring it.

P25 channel details can include `p25_decoder_mode` (`C4FM` or `CQPSK`), NAC, RFSS, and RF site facts when known.
DMR channel details can include tier/model, color code, LCN, and timeslot facts. NXDN channel details can include
Type-C/Type-D address-domain, RAN, channel number, and location-category facts. These are facts about observations on
the saved channel, not alternate channel identifiers.

Quality history accepts `range`, `points`, and `include_history`. `points` must be between 60 and 360. Historical
points require a channel-specific request.

### Activity and affiliations

`GET /api/v1/activity/actions` accepts `range=1h|6h|24h|7d|30d`. Its metadata includes `range`, `from_ms`, `to_ms`,
and `total`. The response omits `CONTINUE`.

`GET /api/v1/activity/radios` accepts `range`, required `action`, and normal `limit`/`offset` paging. `CONTINUE` is
rejected because continuation events are not retained as detailed SOURCE-radio events. SQLite aggregates every
retained matching event before paging. Rows use `radio_system_key` for trunked activity or `configuration_id` for
channel-owned activity, so the same numeric radio ID on different systems or channels remains separate.

Radio-system radio and relationship pages accept `affiliated=true|false` and optional `configuration_id`. Both
filters are applied before paging. A relationship request must include `radio_id` or `group_identity_id`. A
`group_identity_id` also requires `group_identity_kind=talkgroup|patch_group`. Patch groups are never current
affiliations.

Authoritative channel-local registration or affiliation evidence is returned in a nullable `presence` object. Its
owner is the saved channel, identified by `configuration_id`; it can also include RF protocol facts such as RFSS and
Site ID. Calls, grants, aliases, and generic last-seen traffic do not create authoritative presence.

## Protocol presentation

Persisted resource protocols are lowercase: `am`, `p25`, `dmr`, `nxdn`, or `nbfm`. Live records can also use other
decoder-specific protocol labels. Database integer codes and internal surrogate row IDs are not part of the API.

- Radio-system summary counts distinguish group identities, ordinary talkgroups, and patch groups.
- DMR and NXDN variants use names such as `tier_iii`, `capacity_plus`, `type_c`, and `type_d`.
- NXDN Type-D identities keep their decimal value and can add a formatted display value.
- `capabilities` is the authoritative feature list. Do not infer support from protocol alone.

## Exports

CSV exports use `GET /api/v1/exports/{dataset}.csv`. Supported datasets are:

- `aliases`
- `signal-health`
- `radio-system-group-identities`
- `radio-system-radios`
- `channels`
- `channel-frequencies`
- `channel-group-identities`
- `channel-radios`
- `channel-neighbors`
- `channel-quality`

One export runs at a time. Buffered exports stop at 10,000 rows or 16 MiB. Radio-system exports use
`radio_system_key`; channel exports use `configuration_id`. Radio-system radio exports also accept `affiliated` and
`configuration_id`. Alias catalog reads and `aliases.csv` require administrator Alias access.

## Live data

Pages share one multiplexed connection per browser document:

- `GET /api/v1/live/multiplex?client_id={uuid}` opens the framed stream.
- `POST /api/v1/live/multiplex/control` replaces that client's subscriptions using a monotonically increasing
  `revision` and a `subscriptions` object.

Subscription names are `channel_activity`, `decode_events`, `decode_messages`, `channel_diagnostics`, and
`tuner_diagnostics`. Parameters use `snake_case`. Channel-owned subscriptions use `configuration_id`.
`decode_messages` also requires positive `frequency_hz`; `timeslot` is supported by decoder events and channel
diagnostics, not decoder messages.

The framed envelope is version 2. Metadata topics use bounded FIFOs; dense diagnostic topics use replaceable
latest-value slots. Slow clients can lose stale diagnostic frames or be disconnected, but never apply backpressure to
receiver processing. Decoder events and messages are live-only and do not preload history. Known bounded loss emits
`live_gap` and resumes at the live edge.

Channel-activity snapshots include `configuration_id`, protocol-neutral system/site/channel display labels, explicit
navigation references, and protocol facts. Target metadata remains available for Alias and routing consumers. The
browser uses the saved channel as the primary identity for analog AM/NBFM presentation and actions.

## Browser calls and audio

`GET /api/v1/calls/feed` repeats `scan_list_id` to select up to 16 published Scan Lists. Omitting IDs selects the
published default. The optional `cursor` is a non-negative decimal string. A request without a cursor starts at the
current live edge. A request with the returned cursor waits for up to five seconds and returns at most 64 matching
calls. A cursor outside the retained range returns `reset: true`; clients must still process any calls in that
response.

Completed calls include `configuration_id`, optional `radio_system_key`, `entity_ref`, identifiers, Aliases, channel
display context, protocol facts, encryption state, `scan_list_ids`, `playback_target`, and `audio_url`.
`playback_target.kind` declares what Hold and Avoid control:

- `channel` for AM, NBFM, and other channel-scoped calls;
- `channel_timeslot` when a conventional timeslot must remain separate;
- a radio-system group target for trunked talkgroup playback.

Channel and channel-timeslot targets use the saved channel label. This keeps action wording independent from a
configured analog routing number while preserving that number elsewhere in the call metadata.

Audio is fetched from `/api/v1/calls/{id}/audio`. While a feed is active, one shared ring holds at most 512 calls or
128 MiB. Entries expire after 30 minutes and one WAV cannot exceed 16 MiB. At most 16 feed requests and 16 audio
responses are active at once. These are safety bounds, not a replay-history guarantee.

## Authentication and administration

Authentication uses `GET /api/v1/auth/session`, `POST /api/v1/auth/login`, and `POST /api/v1/auth/logout`. Signed-in
users own one complete preference document at `GET, PUT /api/v1/me/preferences`. Mutations require the session CSRF
token and the capability assigned to the resource.

Central administration uses:

- `GET, POST /api/v1/admin/users`
- `PUT, DELETE /api/v1/admin/users/{username}`
- `GET, PUT /api/v1/admin/access`
- `GET, PUT /api/v1/admin/receiver-settings`
- `GET, PUT /api/v1/admin/p25-bandplan-overrides`

The receiver-wide settings document currently contains only the traffic-grant age-out value. P25 bandplan override
profiles are selected for a saved channel by `configuration_id`; their matching RF facts remain WACN, System ID, and
optional RFSS/Site ID.

Session `capabilities` is an object of booleans. The administrator access resource returns a `capabilities` array;
each entry has `id`, `display_name`, `required_tier`, `default_tier`, and `configurable`.

The `web-access` capability, displayed as **Entire web interface**, is the global minimum checked before feature
capabilities. The `radio` capability, displayed as **Radio systems and channels**, controls both radio-system and
channel pages and APIs. Setting `web-access` to `user` or `admin` protects all receiver pages, APIs, live transports,
audio, diagnostics, and exports. The static shell and `/api/v1/auth/*` remain reachable so a user can sign in.

Wire enum values are explicit and case-sensitive:

- Access tiers are `public`, `user`, and `admin`.
- Alias List families are `p25`, `dmr`, `nxdn`, and `nbfm`.
- Alias matcher types are `talkgroup`, `talkgroup_range`, `radio`, `radio_range`, `user_status`, `unit_status`,
  `tone_sequence`, `dcs`, and `esn`.
- Alias matcher protocols are `am`, `p25`, `dmr`, `nxdn`, `nbfm`, `fleetsync`, and `mdc1200`.

The browser API does not import RadioReference systems, sites, talkgroups, or conventional channels. Use the desktop
Configuration Editor for those workflows.

For a listener-facing explanation of browser playback, see
[How Browser Listening and Scan Lists Work](browser-listening-and-scan-lists.md).
