# What’s New in sdrtrunk-vce 0.6.2 Alpha 9

## What

Alpha 9 is a focused bugfix release for playback and tuner stability. It prevents calls that should not be heard from
leaking tones or brief audio, makes playback status more accurate, adds browser volume control, and prevents an
out-of-range channel from moving the wrong tuner.

This release does not change the database schema, recording formats, streaming-provider configuration or upload
formats, RadioReference importing, or activity and statistics storage.

## Added

- **Browser playback volume.** The web player has a 0–100% volume control that is separate from Mute and remembered by
  that browser.

## Changed

- **Trunked traffic calls are identified explicitly.** Temporary P25 Phase 1, P25 Phase 2, DMR, and NXDN traffic
  channels carry an internal marker so the player can distinguish an unidentified trunked fragment from a legitimate
  conventional or direct call.
- **Conventional calls without a destination remain playable.** Desktop playback retains their opening audio while
  waiting briefly for optional destination information. Playback begins when the call completes or after approximately
  half a second, without discarding the beginning.
- **Playback status reflects usable audio.** The desktop queue excludes calls still waiting for identification or
  already suppressed. Browser Hold and Avoid remain unavailable until audio is actually playing.
- **Nightly packaging is more reliable.** Automated builds run from `main`, package without the incompatible
  configuration cache, and keep the nightly tag aligned with the uploaded files.

## Fixed

- **Suppressed calls no longer leak audio.** A Do Not Monitor call or suppressed duplicate is rejected before its start
  tone, drop tone, or opening voice reaches the speakers. Alias listening policy and duplicate status are applied
  before the call is first offered to playback.
- **Unidentified trunked fragments no longer reach desktop or browser playback.** This addresses the frequent
  beep/boop sounds and very short call fragments that were not shown as normal calls. Legitimate conventional and
  direct calls without talkgroup information are not discarded.
- **Browser playback controls no longer flash while a call is loading.** Hold and Avoid now represent the call that is
  actually playing.
- **Out-of-range tuner requests are rejected before tuner centering.** A wide-band tuner can no longer be pulled toward
  a channel outside its configured frequency range merely because it was given another site's frequency group. Valid
  in-range groups still use normal center-frequency selection.

## Removed

- No decoder, channel type, Alias feature, recording feature, configured streaming provider, RadioReference feature,
  or supported upgrade path is removed. The existing exact Alpha 7 conversion and supported original SDRTrunk
  playlist XML imports remain available.

## Before You Upgrade

- **Alpha 8 and Alpha 9 use the same database schema.** An exact Alpha 8 database opens without a data conversion.
  Channels, aliases, tuner settings, preferences, calls, counts, Activity, affiliations, site observations, talkgroup
  and radio evidence, and quality history remain intact.
- **Migrate Previous Data can copy an Alpha 8 portable profile into a new Alpha 9 installation.** The source
  installation remains unchanged, and portable directory references are adjusted for the new location. Existing
  classic recording files remain in their configured location; Alpha 9 does not move or convert them.
- **An exact Alpha 7 profile can still upgrade directly.** Supported channels, aliases, streams, tuners, and portable
  settings are preserved or converted, but calls, counts, Activity, affiliations, site observations, identity
  evidence, and quality history begin empty, just as they did when upgrading to Alpha 8. Older, mixed, or development
  layouts are refused without modification.
- `main` and `webfirst` must continue using separate portable data folders.
- Stop sdrtrunk-vce and back up the complete portable `data` folder before upgrading.
- The tuner fix prevents an incorrect allocation attempt. It does not eliminate CPU or USB buffer overload; a wide
  sample rate still requires enough processing and USB capacity.

## Downloads

Use the package that matches your operating system and processor. Java 25 is included. JMBE remains a separate setup
under **Preferences > Decoder > JMBE Audio Library**. Verify the downloaded ZIP with `SHA256SUMS.txt` before installing.
