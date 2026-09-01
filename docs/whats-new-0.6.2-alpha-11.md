# What’s New in sdrtrunk-vce 0.6.2 Alpha 11

## What

Alpha 11 improves USB tuner recovery, database migration, P25 control-channel handling, RadioReference imports,
completed calls, and the Now Playing screen. It also restores AM conventional decoding and makes Alias listening
simpler.

## Added

- **AM conventional decoding.** AM channels can be configured, imported, matched to Aliases, and shown in Activity,
  CSV exports, and website statistics.
- **USB tuner rescan.** The Tuners tab can find supported USB devices again after hardware is reconnected or changed.
- **RadioReference Import All.** One action imports every loaded talkgroup, including rows hidden by the current
  filter. A preview shows additions, updates, and unchanged rows. Updates preserve local colors, icons, actions,
  recording, listening, and streaming choices.
- **SQLite database import after setup.** **File > Import SQLite Database** replaces the active database through the
  Application Migrator, keeps a safety backup, and validates the result. It exits after success and restarts
  automatically when the launcher supports it; otherwise, start it normally.
- **AMBE tone muting.** A new preference can silence recognized tone audio while keeping the tone information. It
  requires JMBE 1.0.15 or newer and is off by default.

## Changed

- **Alias listening is now Listen on or off.** Existing enabled playback priorities become Listen entries in the
  Default list. Numeric priority ordering is retired. Eligible catch-all Aliases move to their Alias List's unmatched
  listening, recording, and streaming settings.
- **P25 control-channel handling is more reliable.** Learned controls stay with the correct site, remain separate from
  manually entered frequencies, and survive brief signal loss. New P25 trunked channels learn announced controls by
  default. RadioReference chooses LSM or C4FM from the site's details.
- **Completed calls use their final details.** Listening, recording, and streaming follow the final talkgroup or
  patch-group destination. Recording names use the completed call time and add a short suffix only when two filenames
  would otherwise match.
- **Now Playing keeps the selected channel.** Events, messages, spectrum, and squelch stay with the selected site
  through control-frequency changes and when the lower panel is reopened. Busy Events and Messages updates no longer
  make the decoder wait, and Clear no longer lets old rows reappear.
- **Migration progress stays visible.** Inspection and conversion run behind a progress window. The final report can
  be copied and continues automatically after a visible countdown.
- **Update checks follow the installed release.** Alpha and Nightly builds now check their own update feeds.

## Fixed

- **USB tuner recovery.** Repeated transfer errors no longer cause sample delivery to slowly fall off and eventually
  stop. Retries, startup failures, tuner closing, and shutdown now handle USB work safely.
- **P25 activity.** Registrations, affiliations, and site presence are more accurate. Talkgroups from connected
  systems remain separate instead of being folded into an ordinary local ID. Normal site-channel tags such as CWID and
  CURRENT_CONTROL merge quietly; real channel conflicts still produce a warning. P25 traffic channels no longer
  perform control-channel-only actions.
- **Tuner and desktop controls.** Raw recording status stays with the selected tuner, closing a tuner stops its
  recording, the RSP1 shows all four LNA settings, first-launch windows center correctly, popup menus keep the current
  theme, channel buttons stay visible, and encryption-vault edits take effect immediately.

## Removed

- **Desktop Submit Bug Report.** New reports use the project's GitHub Issues page.
- **Older specialized P25 Alias matches.** Migration removes Alias rows that combine talkgroup or radio IDs with
  network and system IDs, along with streaming choices used only by those rows.
- **Direct migration from Alpha 7 and older.** Alpha 7 data must first move to Alpha 10, then Alpha 11. Older Alpha data
  must first follow its historical path to Alpha 7.

## Before You Upgrade

- Stop sdrtrunk-vce, back up the complete portable `data` folder, and install Alpha 11 in a new empty folder.
- Alpha 8, Alpha 9, and Alpha 10 data moves through the Application Migrator. A database using a format newer than
  Alpha 11 is refused and requires a newer build.
- The migrator checks free space before it starts. Large databases need room for the safety backup and temporary
  copies. A full-profile migration also needs room for copied profile files.
- Channels, DMR channel maps, streams, settings, icons, ordinary Aliases, recording choices, tuner settings, the
  vault, JMBE library, and supported optional modules carry forward when migrating a full portable profile. Live
  traffic rebuilds current affiliations and learned radio/talkgroup relationships.
- Extensive history can make migration take several minutes or longer. Leave the progress and completion windows open
  until the application continues.
- **File > Import SQLite Database** pauses receiving and output. After final confirmation, calls still waiting to be
  recorded or streamed may be discarded. It imports only the selected database file: files beside it are not copied,
  the current vault, JMBE library, and modules stay in place, and saved paths are not changed.
- Keep Alpha 10 and its original data for rollback. Test receiving, recording, streaming, RadioReference import,
  website access, and administrator access before leaving Alpha 11 unattended.

## Downloads

Choose the download for your operating system and processor. Java 25 is included. JMBE is set up separately under
**Preferences > Decoder > JMBE Audio Library**. Verify the ZIP's SHA-256 checksum against `SHA256SUMS.txt`.
