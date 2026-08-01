# What’s New in sdrtrunk-vce 0.6.2 Alpha 8

**Alpha 8 brings DMR and NXDN much closer to P25 in the activity database and web interface, adds a searchable Alias
Catalog and CSV exports, introduces voice-call quality diagnostics, and fixes decoder, recording, streaming, tuner,
RadioReference, and RadioResolve problems reported during Alpha 7 testing.**

This is still alpha software. Stop sdrtrunk-vce and back up its complete portable `data` folder before installing or
upgrading.

> **Important Alpha 7 upgrade behavior:** Alpha 8 preserves or converts portable settings, tuners, supported channels,
> supported decoder-embedded DMR/NXDN frequency mappings, supported streams, the icon catalog, and supported aliases from the exact
> Alpha 7 database format. Calls, counts, Activity, affiliations, site observations, observed talkgroup/radio evidence,
> and quality history intentionally begin empty after the upgrade. **Migrate Previous Data** works on a validated
> staged copy and leaves the complete Alpha 7 installation unchanged as the rollback copy.

## Added

- **Protocol-neutral trunked system directories.** P25, DMR, and NXDN now share the same storage and web framework for
  systems, sites, talkgroups, radios, talker aliases, relationships, calls, recordings, streaming, encryption, and
  signaling wherever the decoder supplies those facts. DMR and NXDN system pages now include the applicable site,
  channel, neighbor, quality, Talkgroup, Radio, Talker Alias, and Activity views. Unlike P25, configured DMR and NXDN
  scopes remain receiver/site-local rather than automatically combining same-named sites into one multi-site system;
  a neighbor links to another monitored site only when there is one unambiguous identity match.
- **Broader conventional activity collection.** Conventional DMR has its own talkgroup and radio directories.
  Conventional NXDN now produces properly completed calls and detailed Activity rows. Conventional P25 and NBFM calls
  participate in the shared call, recorded, and streamed totals. NBFM does not invent digital talkgroup or radio
  identities, and conventional NXDN does not yet have the identity-directory pages available to conventional DMR.
- **A read-only web Alias Catalog.** Search and filter every configured alias, including aliases that have never been
  heard. Core configuration stays visible while a remembered checkbox menu can add call, recording, streaming,
  encryption, grant, join, registration, emergency, logout, relationship, covered-scope, observed-scope, and
  first/last-evidence columns. Scope coverage means where a compatible alias can be resolved, not geographic or RF
  coverage. A detail dialog shows per-scope information, and Alias List references elsewhere in the website link back
  to the catalog.
- **CSV export for radio system managers.** Exports are available for system talkgroups and radios, site channels and
  neighbors, conventional channels, conventional DMR talkgroups and radios, current signal health, retained site
  quality, and the Alias Catalog. Files include the identities, descriptions, counts, signaling evidence, channel or
  site facts, quality values, and timestamps that apply to each view. Exports are safely escaped and bounded to
  100,000 rows or 64 MiB.
- **Voice-channel decode-quality diagnostics.** P25, DMR, and NXDN digital voice calls can show decoded, repeated,
  concealed, and missing voice frames plus forward-error-correction information. The percentage stays `-` during the
  first 50 frames so a call does not begin with a misleading low-quality alarm. Preferences separately control
  control-channel and voice-channel quality, summary or detailed display, and whether voice quality clears at the end
  of a call. Completed summaries are written to MP3 and WAV metadata. Per-call voice quality is not stored as
  activity-database history.
- **Appearance controls.** The web interface has a persistent light/dark switch that is applied before the page draws.
  The desktop adds multiple light and dark themes and interface scaling from 50% to 200%.
- **Pause on Activity pages.** Live Activity tables can be paused while new rows collect in a bounded queue. Resume
  shows the pending count and applies late updates without duplicating the original event.
- **More useful links and labels.** Monitored neighbors show their resolved site name and link to that site when the
  decoded identity has one unambiguous match. Callsigns link to the RadioReference FCC lookup. Known P25 manufacturer
  IDs show the manufacturer name, and channel roles distinguish control, alternate control, voice, data, announced
  data, and CWID observations. Trunked channel groups use the configured system name as their heading and link it to
  the decoded system identity when one exists.
- **Safer startup choices.** If an auto-start digital voice channel needs JMBE, startup can open the builder, check
  again, continue without voice, or hide the warning. Webserver preferences now clearly distinguish Local Only from
  Any IP and warn that Any IP is unencrypted and has no login.

## Changed

- **Alias Lists now belong to a protocol family.** Each list is P25, DMR, NXDN, or NBFM. A channel can use any Alias
  List with the matching family; its system name no longer has to match the Alias List name. Creating a list prompts
  for the family. Each alias owns one protocol-compatible matcher, which removes ambiguous cross-protocol matching
  and fixes empty Alias List choices on otherwise compatible conventional channels. During migration, mixed-protocol
  lists are separated and an Alpha 7 alias with several compatible matchers becomes equivalent single-matcher rows
  while retaining its description, appearance, recording choice, priority, and stream routes.
- **DMR and NXDN channels store an explicit Conventional or Trunked mode.** The playlist editor exposes the choice
  directly. During migration, a legacy DMR channel with a usable channel map becomes Trunked; otherwise it becomes
  Conventional. NXDN follows its saved decoder configuration instead of relying on a web-only interpretation.
- **The web dashboard is divided into Calls and Health.** Calls emphasizes 24-hour Calls, Recorded, and Sent outcomes,
  activity over time, top destinations, and top sources. Top sources can show the configured alias, decoded
  over-the-air talker alias, and radio ID. Health emphasizes receiver, system, RFSS, Site ID, NAC, compact decoder
  mode, frequency, signal, and decode condition. Health stays sorted by receiver name. Signaling observations are kept
  separate from physical call outcomes instead of being scattered through unrelated columns.
- **Talkgroup outcomes and signaling evidence are separated.** Calls, Recorded, and Sent describe physical outcomes.
  One Evidence total combines non-call observations such as join, register, active, continue, emergency, and request;
  details show the individual action counts. This explains why a known talkgroup can have zero grants or calls without
  filling the normal table with many signaling columns.
- **Live signal bars have one clear meaning.** The number of bars represents signal strength in dBFS: four at
  `-65 dBFS` or stronger, three at `-75 dBFS` or stronger, two at `-85 dBFS` or stronger, and one below that. Bar color
  represents decode quality: healthy at 90% or better, degraded at 75–89%, and poor below 75%. Stale or unavailable
  readings are shown separately.
- **System signaling no longer pollutes normal identity directories.** Reserved and special values such as P25
  talkgroup 0 remain visible in Activity with a system/special label, but do not create ordinary talkgroup or radio
  rows. DMR and NXDN relationships are retained as evidence rather than falsely presented as a current affiliation;
  current affiliation state remains a P25 capability.
- **Patch calls explain both the radio event and its members.** One physical patch call increments the patch identity
  and every valid member talkgroup, while member Activity links back to the original event. This makes a member’s
  Calls, Recorded, and Sent totals useful without pretending several separate RF calls occurred.
- **Late information updates the original call.** A source radio, destination, alias, or encryption state received
  after call start enriches the retained event instead of incrementing call totals again. Successful Recorded and Sent
  outcomes are also counted once per logical call when a long call is divided into linked one-minute audio segments.
- **The best duplicate recording is selected with voice evidence.** When several receivers capture the same call,
  recording, browser audio, and streaming prefer the copy with fewer missing or concealed frames, then fewer repeated
  frames, then fewer normalized FEC corrections. Deterministic fallback rules still apply when voice evidence ties or
  is unavailable.
- **Encryption names are consistent.** P25, DMR, and NXDN use compact names in dense tables and full names in details or
  tooltips, with a plain `ENC` fallback when the exact algorithm is not known.
- **Dense web tables use space more carefully.** Full alias descriptions and talkgroup names appear where space permits,
  decoder modes use compact labels such as P25 P1/P2/Conv, numeric counts keep grouping separators, and radio,
  talkgroup, WACN, system, RFSS, Site, NAC, and channel identifiers do not receive misleading comma separators.
- **RadioReference importing is more deliberate.** State and county trunked systems are ordered by RadioReference’s
  Last Updated value. Talkgroups support Shift/Ctrl/Cmd multi-selection and an Import/Update Selected action. Import
  status refreshes immediately and reports how many rows were added, updated, or already current. Frequently used
  countries appear first in the desktop selector.
- **NXDN and DCS setup is more complete.** Conventional RadioReference frequency imports correctly select NXDN 4800
  or 9600. Trunked NXDN site imports select 4800, 9600, or Type-D from the RadioReference system type and include the
  site channel map. NXDN talkgroup and radio formatting and validation are corrected. DCS includes the expanded code
  set and safely clears an invalid stale editor selection.
- **Legacy XML merging is available again.** After initial setup, **File > Import Legacy Playlist XML** previews and
  non-destructively merges supported aliases, Alias Lists, channels, and streams. Existing configuration wins, naming
  conflicts receive an `(Imported)` suffix, a timestamped backup is created, and the source XML is never changed.
- **Old P25-only web identity links are replaced.** Clean protocol-neutral scope URLs are now used for P25, DMR, and
  NXDN system identities. Bookmarks to the Alpha 7 P25-specific identity pages are not preserved.
- **Documentation is reorganized.** The README now gives clearer installation, update, portable-storage, branch,
  supported-feature, build, and removal guidance and includes current interface screenshots.

## Fixed

- **Talker aliases are attached more carefully.** Motorola P25 aliases use the radio and talkgroup encoded in the
  alias message and update a live call only when both still match. Harris aliases, which do not contain the same
  independently verifiable pair, are accepted only when the accompanying source agrees with the tracked call. Late
  aliases can enrich the correct radio without being guessed onto the next call or a console radio. DMR and NXDN
  directory identities use shared protocol-aware checks that keep reserved or invalid IDs out of normal directory
  rows. Invalid NXDN alias fragments no longer fill the log with repeated messages.
- **P25 Phase 2 audio segments close at End PTT, with Hangtime fallback.** Hangtime closes only the audio segment when
  End PTT was missed; the traffic-call tracker retains its normal state. This prevents back-and-forth transmissions
  from being combined into one long recording or streaming segment without changing decoder Hangtime behavior.
- **Late P25 end-frame identifiers reach completed-call metadata safely.** A valid Phase 1 TDULC can fill a missing
  source, destination, or NAC before recording and upload metadata is frozen. Phase 2 End PTT can likewise supply a
  missing source, group or patch target, and NAC. Recovery only fills empty or identical fields on the active tracked
  call; it rejects invalid, reserved, conflicting, stale, or trackerless information and does not promise an identity
  for every very short fragment.
- **RadioResolve uploads cannot occupy a slot forever.** Each completed-call request has a 30-second overall timeout.
  A hung request enters the existing bounded retry and cleanup path instead of quietly blocking later uploads.
- **Conventional P25 no longer becomes CONTROL from an invalid frame.** A valid control-family frame is required, and
  rejected frames leave the existing state alone so the fix does not introduce flicker. Conventional P25 also emits
  one real call start instead of repeated mutable tracker updates, and C4FM/LSM selection is restored in its editor.
- **Decoded system facts require useful confirmation and stay independently fresh.** Initial valid facts can still
  appear quickly, while conflicting frequency bands, grants, control channels, DMR network facts, learned channels,
  and neighbors require repeated observations. Current controls remain current on receiver heartbeats, but learned
  channels and neighbors age from their own observations. Stale snapshots cannot overwrite newer state, obsolete
  mutable control roles are cleared, a protocol change clears incompatible child facts, and bounded publication
  avoids unnecessary database writes.
- **P25 patch membership is complete before analytics use it.** Patch calls, counts, recordings, streams, radio
  relationships, and member evidence use the assembled member list rather than a partial update.
- **Invalid P25 dates and time-zone offsets are handled correctly.** Zero or impossible month/day values are rejected
  instead of appearing as a clock reset near the epoch. Phase 1 and Phase 2 time-zone offset fields use the correct
  bit positions, and an invalid new clock clears a previously displayed clock rather than leaving stale certainty.
- **P25 control-channel rotation tolerates short fades after a lock.** A trunked P25 control channel configured with
  multiple candidate frequencies waits briefly after losing a real control lock before rotating, while initial
  seeking remains responsive.
- **Audio normalization no longer compounds between outputs.** Playback, recording, browser audio, and streamers work
  on private sample copies, preventing repeated normalization from making audio progressively louder, compressed, or
  clipped.
- **Recording metadata preserves the received destination.** A range or wildcard Alias can still decide that a call
  should be recorded, but the file keeps the actual talkgroup or destination received over the air instead of
  replacing it with the Alias matcher’s broader value.
- **Auto-PPM cannot run away on a quiet tuner.** Automatic correction is limited to approximately ±3 PPM from its
  starting or manual value. With no current measurement it does not keep moving. A manual change or re-enabling
  Auto-PPM establishes a new baseline.
- **Streamer status and selection no longer become stale or incorrect** when background updates arrive. Broadcastify
  Calls uploads also include the decoded over-the-air radio alias when available.
- **RadioReference imports update their visible state immediately.** A newly imported talkgroup no longer remains
  marked Not Present until the user changes Alias Lists. NXDN import types, site labels, and channel maps are also
  completed.
- **NBFM manual squelch override remains open until released** and then returns to the detector’s current squelch
  state.
- **Unsupported MASTER GAIN is no longer logged as an audio error.** Real audio-line failures are still reported.
- **Dark themes are readable throughout the updated tables.** Status cells, alternating rows, tabs, selected rows, and
  default Alias links inherit appropriate colors instead of retaining white backgrounds or black text. Explicit Alias
  colors remain honored.
- **The desktop Channels table includes Alias List,** and system tabs use a real close button while retaining custom
  tab titles.

## Removed

These are new removals since Alpha 7:

- AM decoder and editor.
- LTR Standard decoder and editor.
- LTR-Net decoder and editor.
- Passport decoder and editor.
- Local Alias **Beep** and **Play Clip** actions and the Actions editor.
- The legacy negative **Non-Recordable** Alias identifier. Supported positive per-Alias recording choices remain.
- Obsolete MIN, LTR-Net UID, retired-protocol, and other unsupported Alias matchers are not carried into the new Alias
  schema.
- Alpha 7’s P25-only web identity URLs. Their replacements are protocol neutral.

MPT-1327, Funcube Dongle Pro/Pro+ tuners, legacy named Channel Maps formerly used by MPT-1327, heterodyne
channelization, sound-card capture, and Shoutcast v2/Ultravox were already outside Alpha 7’s supported feature set;
they are not additional Alpha 8 removals. Decoder-embedded DMR and NXDN channel maps remain supported.

## Before You Upgrade

- **The only older schema Alpha 8 upgrades is exact Alpha 7.** Older alphas must first be brought to the exact Alpha 7
  format using an earlier release’s supported upgrade path. A `webfirst` database is not a supported input or a
  downgrade path for this main-branch release. Exact Alpha 8 profiles may also be copied between installations, but
  that is same-version validation and portable-path relocation rather than an older migration path.
- **Stop Alpha 7 and back up its complete portable data folder.** Extract Alpha 8 into a new empty folder and use
  **Migrate Previous Data**. Do not run two builds against the same database.
- **Supported configuration is preserved or converted.** The migration carries supported channels,
  supported decoder-embedded DMR/NXDN frequency mappings, supported aliases and Alias Lists, supported streams, physical and
  recording tuner settings, disabled tuner identities, application and interface preferences, the icon catalog and
  its stored path references, JMBE/modules, and the portable encryption-key vault. Retired decoders, streams, actions,
  and matchers listed under Removed are not converted into runnable configuration. Review the migration summary
  counts, then inspect the migrated Alias Lists and channels if anything invalid, matcherless, retired, or
  protocol-incompatible was skipped or removed.
- **Collected history intentionally starts over.** Calls, counts, affiliations, talkgroup/radio evidence, site facts,
  observed site-channel and neighbor evidence, patches, signal-quality history, and detailed Activity are not
  converted. Alpha 8 begins collecting them again from zero. Control frequencies already saved in channel
  configuration remain with that channel.
- **The old installation remains recoverable.** **Migrate Previous Data** changes and validates only a temporary staged
  Alpha 8 copy, promotes it atomically, and leaves the entire Alpha 7 folder untouched as the rollback copy. The less
  common in-place migration path creates a timestamped database backup first. Keep the Alpha 7 installation until
  Alpha 8 has been verified.
- **Large files and external images are not copied.** Existing classic recording files remain in place and are not
  backfilled into the new counts; a recording-directory preference inside the old portable data root is rebased to
  the corresponding new Alpha 8 root. Recording-tuner/baseband paths are neither copied nor rebased and continue to
  point at their old or external files. Custom icon catalog rows and paths are preserved, but the referenced image
  files are not copied. Review those paths if the application is moved to another folder or computer.
- Original SDRTrunk playlist XML versions 1–4 remain supported as read-only imports. VCE never writes back to the
  source XML.

After upgrading, verify channel auto-start, tuner assignments and correction values, disabled tuners, Alias List
families, JMBE voice, desktop and browser audio, recording, every configured streamer, file locations, and the webserver
listen mode before leaving the receiver unattended.

## Downloads

Use the package that matches your operating system and processor. Java 25 is included. JMBE remains a separate setup
under **Preferences > Decoder > JMBE Audio Library**. Verify the downloaded ZIP with `SHA256SUMS.txt` before installing.
