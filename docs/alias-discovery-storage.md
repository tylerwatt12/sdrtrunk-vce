# Alias Discovery and Unmatched Talkgroup Storage

VCE can show talkgroups heard by your receiver before you give them an Alias. It can also record or route unmatched
talkgroups using an Alias List policy. This reference explains where those observations come from and how VCE keeps
their identities separate. For everyday Alias editing, see [Alias import and export](alias-import-export.md) and the
[Scan Lists guide](browser-listening-and-scan-lists.md); other guides are in the [documentation index](README.md).

> **Release scope:** This reference describes current `main` and Nightly behavior. Numbered Alpha builds may omit these
> newer Alias and website features.

## User-visible behavior

The Alias page can show P25, DMR, and NXDN talkgroups or patch groups observed for one selected Alias List. Exact
aliases are hidden by default. A covering range is reported without changing the identity received over the air. An
administrator may use a result to prefill a normal Alias.

Each P25, DMR, NXDN, or analog (AM/NBFM) Alias List can also define an Unmatched Talkgroups policy for recording,
streaming, and scan-list routing. The policy is not a catch-all alias: it has no matcher, display identity, or Stream As
value. It routes a completed call under the received identity only when that Alias List has no exact or covering-range match.

For NBFM and AM, the policy applies to the configured logical destination talkgroup. FleetSync and MDC-1200 signaling
identities remain separate Alias evidence and never replace that destination.

## One authoritative relationship

`configuration_channel.alias_list_id` is the only stored relationship between a saved channel and its Alias List.
Queries join `alias_list` by ID. They do not copy or match an Alias List name in activity tables. Renaming an Alias
List therefore changes display text without changing ownership or requiring synchronized activity updates.

Each observation is owned by the saved channel UUID through `receiver_channel.configuration_id`. A proven native
radio system can then collect system-wide summaries from several channels. Display names, system/site labels, decoder
choices, and RadioResolve identifiers do not participate in identity.

For trunked systems:

- complete P25 WACN/System identity can be shared by several receiver channels;
- standard DMR Tier III can be shared after its complete model (`tiny`, `small`, `large`, or `huge`) and Network ID
  are known;
- NXDN Type-C can be shared after its complete location category (`global`, `regional`, or `local`) and System ID are
  known;
- incomplete DMR/NXDN observations and unsupported native variants stay scoped to the saved channel, while incomplete
  P25 observations create no provisional radio system; and
- each channel keeps its exact Alias List assignment even when several channels share one native radio system.

Capacity Plus, Connect Plus, Capacity Max, Hytera Tier III, unknown DMR variants, and NXDN Type-D are always
saved-channel-scoped. A DMR timeslot describes the carrier resource that carried an observation; it is never part of
the radio-system identity.

Native grouping is local to this receiver profile. It joins the sites and saved channels that this installation sees;
it is not a claim that the resulting key is a worldwide registry identifier or safe to compare across installations.

A system-level alias is returned only when every applicable assigned list that resolves the identity produces the
same effective alias presentation. If two lists disagree, or only some applicable lists resolve it, the system-level
alias is left blank. Channel-level views always resolve through that channel's exact Alias List. No query picks an
arbitrary first list.

## Administrator-owned configuration

`alias_list` stores the unmatched-talkgroup recording flag. Stream and scan-list destinations use normalized child
tables keyed by the owning Alias List ID and destination ID. These are administrator-owned configuration rows: calls
never add them, and foreign-key cascades remove them only when an owner is deleted.

New Alias Defaults are separate configuration. The list stores `new_alias_record_enabled`, while
`alias_list_new_alias_stream` and `alias_list_new_alias_scan_list_membership` store its default destinations. An Alias
created with list defaults copies them into its own behavior and memberships. Editing the defaults
does not rewrite existing Aliases or the Unmatched Talkgroups policy.

`scan_list` is a bounded administrator catalog with stable IDs, display order, case-insensitive unique names, optional
description, publication state, and one required default. `alias_scan_list_membership` and the unmatched-policy
membership table use composite ID keys. Runtime routing loads these relationships into an immutable snapshot, so a
completed-call decision does not query SQLite.

The administration boundary caps scan lists at 100 definitions and each unmatched stream selection at 64. Normal
installations have zero to four routes per Alias List. Row count is bounded by administrator-owned configuration, not
receiver uptime.

## Observed identity sources

Discovery reads existing compact call buckets and summaries; it creates no new activity table and performs no writes.

### Trunked P25, DMR, and NXDN

`radio_system_identity_summary` stores one mutable row for each talkgroup, radio, or patch identity in a radio system.
Discovery joins that identity directory to accepted call evidence rather than offering every signaling-only identity.
P25 uses `p25_site_call_identity_bucket` rows from saved channels whose `configuration_channel.alias_list_id` matches
the selected list, preserving the observed destination address on each channel.

DMR and NXDN use `trunked_logical_call_identity_bucket`. For a shared native system, the selected Alias List finds
systems reached through its assigned channels, and results report system-wide call totals with no single channel
owner. A channel-scoped fallback retains its exact saved-channel owner. The selected list controls exact/range
matching; it does not expose another list's configuration. System-level presentation follows the unambiguous
resolution rule above.

DMR and NXDN may initially collect derived summaries under an isolated saved-channel fallback. Once a complete,
supported native identity is established, new observations build the native system summary. Earlier fallback facts
remain attached to the exact saved channel and are presented as historical activity until normal retention or an
explicit clear removes them. They are never relabeled as facts about a system that had not yet been proven.

The P25 call-evidence path uses this bounded shape (the full query also includes matching and presentation fields):

```sql
SELECT identity.id, calls.observed_local_id, configured.configuration_id,
       sum(calls.observed_call_count) AS logical_call_count
FROM configuration_channel AS configured
JOIN receiver_channel AS receiver
  ON receiver.configuration_id = configured.configuration_id
JOIN radio_system AS system
  ON system.id = receiver.radio_system_id
JOIN p25_site_call_identity_bucket AS calls
  ON calls.channel_id = receiver.id
 AND calls.radio_system_id = system.id
 AND calls.identity_role_code = 1
JOIN radio_system_identity_summary AS identity
  ON identity.id = calls.identity_summary_id
 AND identity.radio_system_id = calls.radio_system_id
WHERE configured.alias_list_id = ?
  AND system.protocol_code = 1
  AND identity.identity_kind_code IN (1, 3)
  AND calls.observed_local_id IS NOT NULL
GROUP BY receiver.id, identity.id, calls.observed_local_id
ORDER BY max(calls.last_observed_at_ms) DESC, identity.identity_id
LIMIT ?;
```

P25 identities use one canonical summary keyed by kind, home WACN/System, and native identity. Ordinary identities use
the serving WACN/System; fully-qualified identities use the decoded home tuple. A local ID, including zero for valid
fully-qualified evidence, remains observation/display data and does not collapse distinct home identities. Zero is
not offered as a usable Alias matcher. The system summary stores no cross-channel "last local ID"; Alias resolution
uses exact channel-local evidence when available and withholds ambiguous roaming aggregates. New canonical identities
are capped at 100,000 rows per radio system. Existing rows continue updating after the cap. Rows contain first/last
times and fixed counters, not immutable events or JSON.

### Conventional channels

Conventional P25 and NXDN discovery reads the protocol-neutral hourly call-identity bucket by `channel_id`.
Conventional DMR reads its carrier- and native-timeslot-specific talkgroup summary by `channel_id`. The saved channel's
`alias_list_id` determines the exact list used for matching.

```sql
SELECT summary.*
FROM configuration_channel AS configured
JOIN receiver_channel AS receiver
  ON receiver.configuration_id = configured.configuration_id
JOIN dmr_conventional_talkgroup_summary AS summary
  ON summary.channel_id = receiver.id
WHERE configured.alias_list_id = ?
ORDER BY summary.last_seen_ms DESC, summary.frequency_hz, summary.timeslot, summary.talkgroup_id
LIMIT ?;
```

The same talkgroup number on another conventional channel is a separate observed source because its `channel_id`
differs. For DMR, frequency and timeslot 1 or 2 further prevent unrelated traffic from collapsing together.

## Matching and response rules

Exact and range checks use the normal Alias matcher indexes. A source is excluded as exact only when the selected
list has an exact matcher for the same protocol and identity. A covering range is returned as useful context, not as a
replacement identity.

The storage and query layer uses these keys:

- `configuration_id` as the durable saved-channel identity, with numeric `channel_id` used only for internal joins;
- `radio_system_key` as the durable trunked-system identity within this receiver profile, with numeric
  `radio_system_id` used only for internal joins; and
- `channel_names` and `system_name` for current display text.

Public API and CSV rows expose `configuration_id` and `radio_system_key`, not the internal numeric owner IDs. Numeric
talkgroup IDs are never assumed to be globally unique. Every list is server-bounded to 500 rows.

## Write rate, retention, and cleanup

Discovery itself adds zero writes. Normal activity reaches the single background database writer through the bounded
statistics queue. Initial activity, late attribution, and completed recording/streaming output merge into an existing
summary key whenever possible. Normal steady-state row creation approaches zero after identities are learned.

Observed summaries use the configured Statistics retention period of 1 through 365 days. Time-first indexes select at
most 256 expired rows per cleanup task, within a shared 1,000-row maintenance-pass limit. Deleting a saved channel
cascades its channel-owned conventional facts.
Deleting a saved channel cascades any explicitly owned channel-scoped DMR or NXDN radio system and its summaries. A
shared native P25, standard DMR Tier III, or NXDN Type-C system remains while another receiver channel or retained
system fact uses it, then bounded orphan cleanup removes it. Incomplete P25 observations never create a provisional
system.

Alias policies, routes, and scan-list memberships are configuration and remain until an administrator changes or
deletes them. Statistics clear never deletes those configuration rows.

## Validation and query plans

Fresh databases create the current schema in the single startup schema routine. Existing databases change only through
the bundled Application Migrator. Current-profile upgrades use one in-place transaction with an optional recovery
snapshot; external imports use a staged copy. Startup validates exact table definitions, keys, cascading foreign keys,
and ordered indexes; it never creates or repairs a missing activity object.

Representative-volume tests require indexed searches for:

- selected Alias List to configured receiver channels by `alias_list_id`;
- receiver channels to radio systems by numeric IDs;
- P25 destination call evidence by `channel_id`, joined to its canonical identity;
- DMR/NXDN logical-call evidence by `radio_system_id`;
- conventional identities by `channel_id`; and
- exact and covering-range Alias matches by the existing matcher indexes.

The release audit found no successfully published nightly with the intermediate Alias v5 layout. The source-recovered
Alias v5 fingerprint is therefore a known unsupported developer state, not a guessed migration input. If a database
from that state was actually deployed, retain it with its matching `build_info.txt`; adding support requires an exact
deployed fixture and an explicit adjacent step.

The first published later format is Alias v6/P25 v26 (global format 2). The global format 1-to-2 step creates the
`Default` Scan List, maps every retained Alias whose effective priority is not `-1` into it, maps each converted
unmatched-talkgroup catch-all whose priority is not `-1` to the same list, and removes the retired priority columns. Normal startup remains
validation-only; the bundled Application Migrator runs this and every later registered step. See
[Database Migration Contract](database-migration.md).

The following global format 2-to-3 step (formerly P25 v26-to-v27) creates missing canonical factory Alias Lists and their
unmatched-talkgroup Default scan-list routes. A case-insensitive existing list in the correct family keeps its stored
spelling and existing routing, and compatible blank channels are assigned to that spelling. A custom list using a canonical name for the
wrong family is moved to a unique custom name with its ID, aliases, policy, routing, and saved references preserved;
the correct factory list is then created.

The discovery query never reads optional detailed Activity. It does not depend on names or RadioResolve IDs, and it
never chooses one Alias List merely because it was encountered first.
