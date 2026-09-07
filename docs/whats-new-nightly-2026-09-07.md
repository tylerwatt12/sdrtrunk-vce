# What’s New in Nightly — September 7, 2026

This rolling Nightly contains the complete net change from the previous successfully published Nightly at
`d031c110` on September 3, 2026. It advances the portable database from format 10 to format 15. Back up the complete
portable data folder before upgrading and use the bundled Application Migrator; an older build cannot open a database
after the new Nightly upgrades it.

## Highlights

- **One guided setup and migration wizard.** First launch now stays in a resumable pre-receiver wizard for new setup,
  legacy XML import, full portable-data migration, and review. The same wizard is available later from **Help > Setup
  Wizard…** for legacy XML merges or a confirmed SQLite database replacement. Migration preflight, progress, compact
  errors, copyable details, completion, and the automatic continuation countdown remain in one bounded workflow. A
  successful database replacement returns to setup review before receiving resumes. The wizard supports light and
  dark presentation and preserves an existing administrator’s browser preferences when setup creates the primary
  administrator.
- **Reviewed Alias List import and export.** The web Alias editor now has separate **Import aliases…** and **Export
  aliases…** actions. VCE configuration CSV version 2 round-trips matchers, names, descriptions, groups, appearance,
  recording choices, Scan List memberships, named streaming destinations, and Stream As values. RadioReference
  talkgroup CSV files can also be imported into a compatible selected list. Import offers safe Add/Update and explicit
  Replace modes, automatic format detection, drag-and-drop file selection, filterable and paged change review, and a
  single transactional apply. Ambiguous assignments, invalid matchers, duplicate exact matchers, stale previews, and
  oversized files are refused instead of being guessed or partially applied. The ordinary Alias table download remains
  a reporting CSV and is clearly distinguished from the transferable configuration export.
- **Stable Radio Systems and Channels throughout the website.** The former name-based Systems, Sites, and Conventional
  presentation is replaced by two explicit resources: a Radio System for proven trunked network identity and a Channel
  for one saved receiver configuration. P25 systems can join by WACN and System ID, standard DMR Tier III by model and
  Network ID, and NXDN Type-C by location category and System ID. Unsupported or incomplete DMR/NXDN variants remain
  scoped to their saved Channel, while incomplete P25 observations do not create a provisional system. Channel names,
  site labels, frequencies, RadioResolve IDs, and timeslots are never treated as system identity.
- **Clearer live listening controls.** Browser playback adds bounded Pause and Resume behavior. Hold and Avoid now use
  an explicit stable playback target: analog calls follow their saved Channel, conventional timeslots can remain
  separate, and trunked calls follow their Radio System group identity. Native DMR timeslots remain carrier context and
  no longer split a trunked talkgroup target. Patch groups stay distinct from ordinary talkgroups in activity,
  navigation, statistics, and playback data.
- **More useful tuner spectrum controls.** Saved idle channel markers are independent from active channel flags,
  display options live in a focused modal, and frequency bands appear over the FFT. The built-in US catalog adds
  separate public-safety and MURS/service scopes, optional snapping, and persisted country selection. The original
  spectrum profile behavior is restored, channel markers are narrower, and the toolbar uses the shared icon style.

## Changed

- Saved channel UUIDs, Radio System keys, Alias List IDs, and broadcast-provider UUIDs are now the durable internal
  relationships. Renaming an Alias List or streaming provider no longer changes ownership or breaks a route. A shared
  native Radio System shows an Alias only when every applicable assigned list agrees; Channel views always use that
  Channel’s exact Alias List.
- The web API and CSV exports now expose opaque `radio_system_key` values for trunked systems and canonical
  `configuration_id` UUIDs for saved Channels. The Radio System directory can show bounded Channel previews, while
  complete Channel collections and protocol capabilities have dedicated routes. Activity, quality, affiliations,
  relationships, patch groups, learned frequencies, neighbors, and identity pages use the same ownership model.
- Receiver activity storage is rebuilt around bounded Radio System and Channel summaries. Routine retention work is
  capped, ISSI band queries remain index-backed, unknown frequencies are normalized consistently, P25 Phase 1 and
  Phase 2 traffic contributes to the same system totals, and Alias activity defaults to highest call count.
- P25 duplicate-call resolution now groups simultaneous receiver legs by native WACN/System identity instead of RF
  site or Alias List, so copies of the same call received on several sites can produce one completed call for
  recording, streaming, statistics, and browser playback. The best audio leg keeps ownership of its local Alias and
  recording policy; exact identity evidence from another copy may fill missing identity facts but cannot replace that
  winner-owned presentation. Contradictory fully qualified identities remain separate calls.
- The four factory Alias Lists are `Default P25`, `Default DMR`, `Default NXDN`, and `Default Analog`. A compatible
  `Default NBFM` list is renamed in place when possible. Missing factory lists are restored once by migration while
  existing aliases, list policies, routing, and Channel assignments are preserved.
- RadioReference’s desktop editor uses system county data when available. Imported RadioReference aliases update only
  the imported name, description, and group fields on existing matches; local appearance, recording, Scan List, and
  streaming choices remain intact. New fully encrypted talkgroups are not routed to recording, playback, or streaming.
- Site-bound streaming now requires exact observed trunked identity evidence. RadioResolve correlation remains an
  external delivery field rather than a receiver-selection or Radio System key. Alias streaming routes use provider
  UUIDs, and the obsolete broadcast-name length limit is removed.
- Classic per-call recording remains in the administrator-selected recording directory. This Nightly does not add a
  recording catalog or automatic recording retention. Alias configuration exports preserve the per-alias recording
  flag, and migrations preserve administrator-owned recording configuration.

## Database and Upgrade Behavior

- **Format 10 → 11:** restores missing factory Alias Lists, safely repairs factory-name collisions, renames the analog
  default when unambiguous, and repairs Channel/List scalar disagreement from the previously authoritative value.
- **Format 11 → 12:** adds the independent saved idle-FFT-marker preference without changing existing display choices.
- **Format 12 → 13:** adds bounded resumable setup-wizard progress. Existing configured installations are marked ready;
  imports and replacements return to setup review as required.
- **Format 13 → 14:** adds one receiver-wide country choice for code-owned spectrum band and snapping catalogs. Existing
  profiles explicitly retain the US behavior.
- **Format 14 → 15:** installs the stable Channel, Radio System, Alias List, provider, activity, and web-access identity
  model. It preserves administrator-owned Channels, Alias Lists, stream providers and routes, accounts, credentials,
  preferences, icons, decoder settings, DMR/NXDN embedded channel maps, and output configuration. The staged migrated
  database is compacted before validation and atomic promotion.
- Format 15 deliberately resets receiver-derived activity and call counters, affiliations, radio identities, learned
  site observations, signal/quality history, and old identity caches so live traffic can rebuild them under the new
  unambiguous ownership model. The migration reports those resets. It also removes redundant subsystem-version rows
  and the retired legacy named Channel Maps table; decoder-embedded DMR and NXDN channel maps are not removed.

## Fixed and Hardened

- Corrected extended and abbreviated P25 registration fields, retained valid abbreviated working-unit identities,
  preserved canonical P25 roaming identity, and kept incomplete identities out of playback controls.
- Fixed live quality timestamps, Radio dashboard initialization, live-table row churn, patch-group navigation, web
  identity links, statistics regressions, Radio System metric reset boundaries, and Alias transfer rendering.
- Setup now accepts unnamed auto-start Channels, preserves supported Alpha browser preferences through administrator
  setup, detects migration blockers during preflight, and uses bounded expandable error dialogs with Copy actions.
- Configuration saves release the SQLite writer promptly, stable configuration rows keep their IDs, and current
  databases reject retired metadata or transient identity fallbacks instead of silently repairing them.
- USB sample delivery is isolated from diagnostic observers, the tuner channelizer avoids a lock-order inversion, and
  retune allocation considers the pending center frequency before rejecting an otherwise usable tuner. Saturation and
  lifecycle tests cover full queues, blocked consumers, diagnostic contention, rebinds, and disconnects.
- Receiver Health now includes host CPU, heap, garbage-collection, and disk bars alongside tuner, queue, decoder,
  streaming, recording, web, and diagnostic measurements.

## Removed and Compatibility Notes

- The old name-based web Systems/Sites/Conventional routes, entity keys, topology fallback, and separate Systems and
  Conventional access choices are retired. API clients must use Radio System and Channel routes, opaque keys, explicit
  entity kinds, and advertised capabilities.
- Name matching is no longer used to connect Channels to Alias Lists or Aliases to streaming providers. The legacy
  RadioResolve/Site GUID aliases are confined to migration input and are not current runtime identities.
- The separate coordinated-startup dialog and countdown are replaced by the setup wizard. Screenshots of the retired
  web navigation were removed rather than documenting obsolete screens.

## Before You Upgrade

1. Back up the complete portable data folder, including the SQLite database, vault, JMBE library, optional modules,
   and administrator-owned configuration.
2. Extract the Nightly into a new empty folder and use **Migrate Existing**. Do not point an older Alpha or Nightly at
   data after format 15 has opened it.
3. Read the migration preflight. Expect receiver-derived activity and quality history to restart from zero; Channels,
   Alias Lists, accounts, credentials, recording choices, and streaming configuration should be preserved.
4. After migration, verify factory and custom Alias Lists, Channel assignments and auto-start selections, tuner
   assignments, JMBE, output paths, web access, Scan Lists, and streaming providers before leaving the receiver running.
5. If you use the web API or CSV automation, update it for the Radio System/Channel resource model and do not construct
   or parse opaque identity keys.

Nightly packages remain pre-release builds. The publication workflow creates Windows x86-64/ARM64, macOS
x86-64/ARM64, and Linux x86-64/ARM64 packages only after clean Java 25 builds and the full test suite pass on Windows,
macOS, and Linux.
