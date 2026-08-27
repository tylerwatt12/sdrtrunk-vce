# What’s New in sdrtrunk-vce 0.6.2 Alpha 10

## What

Alpha 10 is mainly a bug-fix and stability update. Scanner-Map call uploads work again, Alias editor actions stay with
the correct alias, late encryption information stays with the correct call, and Configuration Editor and User
Preferences no longer flash white when opened in a dark theme. This release also improves P25 channel handling and
makes tuner shutdown and receiver overload safer.

Alpha 10 uses the same database format and portable `data` folder layout as Alpha 8 and Alpha 9. Alias storage remains
at version 4. This release does not change the recording format or administrator control of recordings,
RadioReference import behavior, streaming-provider settings, or the fields used for multipart call uploads.

## Added

- **Safer behavior while tuners start and stop.** If a channel needs a tuner while hardware is being enabled or
  disabled, sdrtrunk-vce immediately tries another usable tuner or ends the request cleanly instead of waiting for
  shutdown to finish.
- **Limits on backed-up receiver data.** Internal queues that carry live radio data now have firm size limits and clean
  themselves up when a decoder stops or falls behind. This prevents stale data and memory use from continuing to
  build up.
- **Dark-themed loading screens.** Configuration Editor and User Preferences now show a lightweight screen in the
  selected theme while the full editor is opening.

## Changed

- **Encryption key IDs are shown consistently.** P25, DMR, and NXDN event details now display key IDs as uppercase
  hexadecimal numbers without leading zeroes. Raw decoder diagnostic text is unchanged.
- **Live radio samples take priority during overload.** If processing falls behind, sdrtrunk-vce discards the oldest
  stale channel work and keeps newer samples. Memory used during a backlog can also be released.
- **Disabled tuners become unavailable sooner.** A tuner is removed from the available list before shutdown begins,
  and sdrtrunk-vce will not select a tuner that is no longer fully available.

## Fixed

- **Alias editor actions stay with the correct alias.** New and cloned aliases do not appear in the list until you
  click Save. Create, edit, multi-delete, and Move To operations continue to target the right rows after sorting or
  refreshing the table, including newly imported RadioReference aliases while they are being saved. If saving fails,
  the unsaved changes and selection remain on screen and an error is shown.
- **The Alias table is easier to use.** Talkgroup and radio IDs, including ranges, sort by number. The New, Clone,
  Move To, and Delete labels are fully visible, and saving one clone no longer shows a duplicate row.
- **Scanner-Map uploads work again.** Rdio Scanner connection checks and completed-call uploads now use the exact
  `User-Agent: sdrtrunk` value required by Scanner-Map. The product name remains sdrtrunk-vce everywhere else. This
  fixes [#46](https://github.com/tylerwatt12/sdrtrunk-vce/issues/46).
- **Late encryption information stays with the original call.** If the encryption algorithm, key, or encrypted status
  becomes known after a P25, DMR, or NXDN call starts, the existing Activity entry and live Activity view are updated.
  sdrtrunk-vce no longer creates a duplicate call or counts the call and encryption twice. This fixes
  [#35](https://github.com/tylerwatt12/sdrtrunk-vce/issues/35).
- **Encryption key numbers match the other status displays.** Event details no longer show a decimal key ID when the
  corresponding status display uses hexadecimal. This fixes
  [#38](https://github.com/tylerwatt12/sdrtrunk-vce/issues/38).
- **P25 implicit uplink frequencies are calculated correctly.** Some P25 messages use the reserved `0xFFFF` value to
  say that no separate uplink channel was supplied. sdrtrunk-vce now uses the downlink band plan in that case instead
  of treating the marker as a real channel, which could produce an incorrect frequency such as 216.375 MHz. This
  addresses
  [#24](https://github.com/tylerwatt12/sdrtrunk-vce/issues/24).
- **Ending a P25 traffic channel no longer risks locking the traffic manager.** Shutdown work is performed in a safe
  order, and stale queued channel data is released when a channel stops.
- **Disabling a tuner during channel assignment no longer exposes a half-removed device.** This prevents the Airspy
  missing-channel-manager crash reported in [#43](https://github.com/tylerwatt12/sdrtrunk-vce/issues/43). It does not
  fix the separate slow Activity API or repeated tuner-retry symptoms in that report.
- **Dark-mode Configuration Editor and User Preferences windows no longer flash white while opening.** Their content
  stays covered by the selected theme during loading and can recover cleanly if opening fails. This applies to
  application content; the native Windows title bar is still controlled by Windows.
- **Inactive P25 data announcements no longer create ghost channels.** An inactive announcement will no longer appear
  as a `DAT-A` channel with LCN `0-0`.
- **Incomplete P25 link-control messages are rejected.** A message must contain all 12 protected pieces before it can
  be accepted. Short messages are no longer padded with zeroes and treated as valid.
- **Windows packages retain the Java runtime's digital signatures.** The included BellSoft- and Microsoft-signed
  `.exe` and `.dll` files are restored after the runtime image is built and checked byte-for-byte against the original
  vendor-signed Java runtime. This avoids unsigned runtime files without changing the computer's security policy or
  promising that a particular policy will trust those publishers.

## Removed

- No decoder, channel type, Alias feature, recording feature, streaming provider, RadioReference feature, or database
  storage has been removed.
- No supported upgrade path has been removed. The Alpha 7 conversion offered by Alpha 9 remains available in Alpha 10.

## Before You Upgrade

- Stop sdrtrunk-vce and back up the complete portable `data` folder.
- **Alpha 8, Alpha 9, and Alpha 10 use the same database format.** An exact Alpha 8 or Alpha 9 profile opens without a
  database conversion or history reset. Channels, aliases, streams, tuners, preferences, calls, counts, Activity
  history, affiliations, site observations, identity evidence, and quality history remain intact.
- **Migrate Previous Data can copy an Alpha 8 or Alpha 9 portable profile into a new Alpha 10 installation.** The old
  installation is not changed. Portable paths are updated for the new location, while recordings remain in the
  administrator-selected recording folder.
- **The same Alpha 7 conversion remains available.** Supported configuration is preserved or converted, but current
  Activity and statistics storage starts empty, just as it did when upgrading to Alpha 9. To upgrade from Alpha 1
  through Alpha 6, first use Alpha 7 to create an exact Alpha 7 profile; Alpha 10 cannot open those older formats
  directly. Mixed schemas and development schemas remain unsupported.
- **Issues #41 and #43 are not fully fixed by this release.** Alpha 10 includes the P25 shutdown/backlog fix and the
  Airspy missing-channel-manager crash fix. Slow or hanging `/api/activity` requests, repeated preferred-tuner
  retries, physical unplug/error shutdown problems, and the other reported symptoms are outside this release.
- Before relying on Alpha 10 unattended, test normal multi-tuner use with an Airspy, simultaneous traffic channels,
  tuner disable, and unplug or error handling. At wide sample rates or under sustained load, also check buffer-drop
  logs and control-channel decode quality.
- Keep the previous installation until Alpha 10 has been tested with the receiver's normal configuration.

## Downloads

Choose the download that matches your operating system and processor. Java 25 is included. JMBE still requires
separate setup under **Preferences > Decoder > JMBE Audio Library**. Before installing, verify that the ZIP's SHA-256
checksum matches the value in `SHA256SUMS.txt`.
