# Alias Discovery and Unmatched Talkgroup Storage

> **Release scope:** This design describes current `main` and Nightly behavior. Numbered Alpha builds may omit these
> newer Alias and website features.

## User-visible behavior

The Alias page can show P25, DMR, and NXDN talkgroups or patch groups observed for one selected Alias List. Exact
aliases are hidden by default. A covering range is reported without changing the identity received over the air. An
administrator may use a result to prefill a normal Alias.

Each P25, DMR, NXDN, or NBFM Alias List can also define an Unmatched Talkgroups policy for recording, streaming, and
scan-list routing. The policy is not a catch-all alias: it has no matcher, display identity, or Stream As value. It
routes a completed call under the received identity only when that Alias List has no exact or covering-range match.

For NBFM and AM, the policy applies to the configured logical destination talkgroup. FleetSync and MDC-1200 signaling
identities remain separate Alias evidence and never replace that destination.

## One authoritative relationship

`configuration_channel.alias_list_id` is the only stored relationship between a saved channel and its Alias List.
Queries join `alias_list` by ID. They do not copy or match an Alias List name in activity tables. Renaming an Alias
List therefore changes display text without changing ownership or requiring synchronized activity updates.

Observed activity is owned by the saved channel UUID through `receiver_channel.configuration_id`. Display names,
system/site labels, decoder choices, and RadioResolve identifiers do not participate in identity.

For trunked systems:

- complete P25 WACN/System identity can be shared by several receiver channels;
- provisional P25, DMR, and NXDN systems remain saved-channel-specific; and
- each channel keeps its exact Alias List assignment even when several channels share one P25 radio system.

A system-level alias is returned only when every applicable assigned list that resolves the identity produces the
same effective alias presentation. If two lists disagree, or only some applicable lists resolve it, the system-level
alias is left blank. Channel-level views always resolve through that channel's exact Alias List. No query picks an
arbitrary first list.

## Administrator-owned configuration

`alias_list` stores the unmatched-talkgroup recording flag. Stream and scan-list destinations use normalized child
tables keyed by the owning Alias List ID and destination ID. These are administrator-owned configuration rows: calls
never add them, and foreign-key cascades remove them only when an owner is deleted.

`scan_list` is a bounded administrator catalog with stable IDs, display order, case-insensitive unique names, optional
description, publication state, and one required default. `alias_scan_list_membership` and the unmatched-policy
membership table use composite ID keys. Runtime routing loads these relationships into an immutable snapshot, so a
completed-call decision does not query SQLite.

The administration boundary caps scan lists at 100 definitions and each unmatched stream selection at 64. Normal
installations have zero to four routes per Alias List. Row count is bounded by administrator-owned configuration, not
receiver uptime.

## Observed identity sources

Discovery reads existing bounded summaries; it creates no new activity table and performs no writes.

### Trunked P25, DMR, and NXDN

`radio_system_identity_summary` stores one mutable talkgroup, radio, or patch row per `radio_system_id`. Discovery
selects the radio systems reached through receiver channels whose `configuration_channel.alias_list_id` equals the
selected list, then reads talkgroup and patch identities through the summary primary key.

Shared P25 systems can be reached by channels assigned to different lists. Filtering remains channel-owned: selecting
one list does not silently expose another list's configuration. System-level presentation follows the unambiguous
resolution rule above.

The trunked discovery path is equivalent to this bounded shape:

```sql
SELECT summary.*
FROM configuration_channel AS configured
JOIN receiver_channel AS receiver
  ON receiver.configuration_id = configured.configuration_id
JOIN radio_system_identity_summary AS summary
  ON summary.radio_system_id = receiver.radio_system_id
WHERE configured.alias_list_id = ?
  AND summary.identity_kind_code IN (1, 3)
ORDER BY summary.last_seen_ms DESC, summary.identity_id
LIMIT ?;
```

Positive local P25 talkgroups may carry complete home WACN/System/talkgroup evidence in the ordinary identity summary.
A valid fully-qualified P25 talkgroup with local ID zero instead uses
`p25_zero_local_fq_talkgroup_summary`, keyed by
`(radio_system_id, home_wacn, home_system_id, home_talkgroup_id)`. This keeps different home tuples separate. Zero-local
rows are diagnostic and review-only because they cannot create a usable talkgroup-zero Alias.

New ordinary identities and zero-local tuples are each capped at 100,000 rows per radio system. Existing rows continue
updating after the cap. Rows contain first/last times and fixed counters, not immutable events or JSON.

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
- `radio_system_key` as the durable trunked-system identity, with numeric `radio_system_id` used only for internal
  joins; and
- `source_label`, `system_name`, and `site_name` for current display text.

Public API and CSV rows expose `configuration_id` and `radio_system_key`, not the internal numeric owner IDs. Numeric
talkgroup IDs are never assumed to be globally unique. Every list is server-bounded to 500 rows.

## Write rate, retention, and cleanup

Discovery itself adds zero writes. Normal activity reaches the single background database writer through the bounded
statistics queue. Initial activity, late attribution, and completed recording/streaming output merge into an existing
summary key whenever possible. Normal steady-state row creation approaches zero after identities are learned.

Observed summaries use the configured Statistics retention period of 1 through 365 days. Time-first indexes select at
most 1,000 expired rows per cleanup statement. Deleting a saved channel cascades its channel-owned conventional facts.
Deleting an unshared provisional DMR, NXDN, or P25 system cascades its system summaries. A native P25 system remains
while another receiver channel or retained system fact uses it, then bounded orphan cleanup removes it.

Alias policies, routes, and scan-list memberships are configuration and remain until an administrator changes or
deletes them. Statistics clear never deletes those configuration rows.

## Validation and query plans

Fresh databases create the current schema in the single startup schema routine. Existing databases change only in the
backed-up, staged Application Migrator. Startup validates exact table definitions, keys, cascading foreign keys, and
ordered indexes; it never creates or repairs a missing activity object.

Representative-volume tests require indexed searches for:

- selected Alias List to configured receiver channels by `alias_list_id`;
- receiver channels to radio systems by numeric IDs;
- recent system identities and zero-local P25 tuples by `radio_system_id`;
- conventional identities by `channel_id`; and
- exact and covering-range Alias matches by the existing matcher indexes.

The discovery query never reads optional detailed Activity. It does not depend on names or RadioResolve IDs, and it
never chooses one Alias List merely because it was encountered first.
