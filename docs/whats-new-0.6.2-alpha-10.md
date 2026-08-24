# What’s New in sdrtrunk-vce 0.6.2 Alpha 10

## What

Alpha 10 is a focused compatibility, configuration-integrity, and receiver-stability release. It restores Scanner-Map
uploads through the Rdio Scanner integration, keeps late encryption details attached to the correct call, prevents
Alias editor operations from publishing or targeting the wrong row, keeps dark-themed JavaFX editors covered while
they load, corrects P25 channel handling, bounds stale receiver work during overload, and prevents tuner
disable/allocation races. Windows packages also preserve the Java runtime's vendor signatures.

This release does not change the database schema, recording format or ownership, RadioReference import fields or
update semantics, streaming-provider configuration, or multipart call-upload fields. Alias storage remains schema
v4, and the release retains Alpha 9's portable-storage layout and existing migration behavior.

## Added

- **Fail-fast tuner lifecycle coordination.** Channel allocation reserves a tuner's lifecycle without waiting. If
  hardware is being enabled or disabled, decoder-side allocation immediately tries another tuner or returns no source
  instead of waiting behind hardware teardown.
- **Bounded channel-output recovery.** Channelizer output queues and reusable result pools have explicit limits and
  cleanup behavior for overflow, stopped consumers, and blocked downstream processing.
- **Theme-aware JavaFX loading shells.** Playlist and Settings first render a lightweight shell whose background and
  text follow the active theme, then replace it with the fully constructed editor.

## Changed

- **Encryption key IDs display in hexadecimal.** P25, DMR, and NXDN decoded-event details use uppercase, unpadded
  hexadecimal key identifiers. Raw decoder diagnostic strings remain unchanged.
- **Receiver overload favors live samples.** When a channel-output consumer falls behind, the oldest stale work is
  released instead of allowing an unbounded backlog or retaining its high-water memory usage.
- **Disabled tuners leave allocation before teardown.** The available-tuner snapshot now requires both an enabled state
  and a fully instantiated tuner. Allocation safely rejects a missing channel-source manager.

## Fixed

- **Scanner-Map Rdio Scanner uploads connect and send calls again.** Connection probes and completed-call uploads use
  the exact protocol-compatible `User-Agent: sdrtrunk` value expected by Scanner-Map's SDRTrunk handler. Product
  branding elsewhere remains `sdrtrunk-vce`. This fixes issue
  [#46](https://github.com/tylerwatt12/sdrtrunk-vce/issues/46).
- **Late encryption details update the original call.** Algorithm, key, and encrypted status learned after a P25, DMR,
  or NXDN call begins enrich that same Activity row and live update without creating a duplicate call or incrementing
  call/encryption counters twice. This fixes issue
  [#35](https://github.com/tylerwatt12/sdrtrunk-vce/issues/35).
- **Event key IDs use the expected number format.** Event details no longer mix decimal key IDs with hexadecimal status
  displays. This fixes issue [#38](https://github.com/tylerwatt12/sdrtrunk-vce/issues/38).
- **P25 implicit uplinks resolve from the downlink band plan.** An explicit uplink field containing the reserved
  `0xFFFF` value is no longer interpreted as band 15/channel 4095, which produced incorrect frequencies such as
  216.375 MHz. This addresses issue [#24](https://github.com/tylerwatt12/sdrtrunk-vce/issues/24).
- **P25 traffic-channel shutdown avoids a manager-lock deadlock.** Synchronous channel-disable requests are issued
  after releasing the traffic manager lock, and queued channelizer results are released correctly when work is stale
  or a consumer stops.
- **Concurrent tuner disable no longer exposes a tuner during teardown.** Allocation cannot dereference a tuner or
  channel-source manager while disable teardown removes it. This addresses the Airspy/null-manager NPE portion of
  issue [#43](https://github.com/tylerwatt12/sdrtrunk-vce/issues/43); it does not claim to fix that report's separate
  API-latency or retry-storm symptoms.
- **Alias editor mutations keep their intended row.** New and cloned aliases remain detached drafts until Save commits
  them. Create, edit, multi-delete, and move operations persist before replacing live rows by durable schema-v4 ID;
  newly imported RadioReference rows retain their identity while delayed ID assignment completes; selection
  restoration no longer targets stale sorted-table instances; and talkgroup, talkgroup-range, radio, and radio-range
  identifiers sort numerically. Failed persistence leaves the dirty draft and selection intact and displays an error.
  The New, Clone, Move To, and Delete action buttons also retain their full labels. This prevents wrong-row edits,
  no-op deletes, and duplicate or unexpectedly reordered rows.
- **Dark-themed Playlist and Settings windows no longer flash white while loading.** The lightweight themed shell
  completes a render before expensive editor construction begins, remains theme-aware afterward, and recovers for a
  retry if setup fails. This covers editor content; native Windows title-bar contrast remains outside the application.
- **Non-autonomous P25 SNDCP announcements no longer create ghost data channels.** Channel fields are published only
  when autonomous access is active, preventing inactive announcements from appearing as a `DAT-A` channel with LCN
  `0-0`.
- **Short P25 TDULC candidates are rejected.** Golay correction no longer reads a partial codeword, and link-control
  data assembled without all 12 protected codewords cannot be accepted as valid after zero filling.
- **Windows packages retain the bundled Java runtime's vendor signatures.** Packaging restores the BellSoft and
  Microsoft Authenticode-signed runtime files after jlink image creation. Each Windows archive's runtime `.exe` and
  `.dll` is verified byte-for-byte against the pinned signed JDK, avoiding unsigned jlink natives without changing
  host security policy.

## Removed

- No decoder, channel type, Alias feature, recording feature, streaming provider, RadioReference feature, or database
  storage is removed.
- No supported migration path is removed. The exact Alpha 7 conversion available in Alpha 9 remains available in
  Alpha 10.

## Before You Upgrade

- **Alpha 8, Alpha 9, and Alpha 10 use the same database schema.** An exact Alpha 8 or Alpha 9 profile opens without a
  database conversion or history reset. Channels, aliases, streams, tuners, preferences, calls, counts, Activity,
  affiliations, site observations, identity evidence, and quality history remain intact.
- **Migrate Previous Data can copy an Alpha 8 or Alpha 9 portable profile into a new Alpha 10 installation.** The
  source installation remains unchanged, recognized portable paths are adjusted for the new location, and classic
  recording files remain in their administrator-configured location.
- **The exact Alpha 7 conversion remains available.** It preserves or converts supported configuration and starts the
  current activity/statistics storage empty, matching Alpha 9 behavior. Alpha 10 retains this compatibility path for
  the focused hotfix release; Alpha 1 through Alpha 6 and mixed or development schemas remain unsupported.
- **This release does not close issues #41 or #43.** The included fixes are limited to the P25
  manager-lock/channelizer-backlog path and the Airspy/null-manager NPE. The slow `/api/activity` request,
  preferred-tuner retry storm, physical unplug/error teardown, and other reported symptoms remain outside this
  release.
- Test multi-tuner operation with an Airspy, concurrent traffic grants, tuner disable, and unplug/error handling before
  relying on this build unattended. Also inspect buffer-drop logs and control-channel decode quality at wide sample
  rates or under sustained load.
- Stop sdrtrunk-vce and back up the complete portable `data` folder before upgrading. Keep the previous installation
  until Alpha 10 has been verified with the receiver's normal configuration.

## Downloads

Use the package that matches your operating system and processor. Java 25 is included. JMBE remains a separate setup
under **Preferences > Decoder > JMBE Audio Library**. Verify the downloaded ZIP with `SHA256SUMS.txt` before installing.
