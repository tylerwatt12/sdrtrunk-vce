# RadioResolve Clean Reimplementation Plan

This document defines the RadioResolve and general workflow features that should be cleanly reimplemented in the
`SDRTrunk-bazineta-no-sdrconnect` build. The goal is to preserve useful behavior while avoiding accidental migration of
old branch clutter, debug-only code, or implementation shortcuts.

## Build Target

Primary target:

- `SDRTrunk-bazineta-no-sdrconnect`

Secondary target when ready:

- `SDRTrunk-bazineta-headless-web-ui`

Do not use this plan to modify stock builds.

## Reimplementation Rules

- Treat this file as the product contract. Reuse old RadioResolve code only when it is still the cleanest way to satisfy
  the contract.
- Keep RadioResolve call upload behavior inside the standard SDRTrunk broadcast/streaming provider path.
- Keep node services, RF telemetry, diagnostics, and local workflow helpers separate from the upstreamable streaming
  provider.
- Never log, print, screenshot, or document raw API keys.
- Keep debug-only tools removable and clearly named as debug-only.
- Prefer typed models over parsing strings from UI tables or activity summaries.

## Feature Status

| Feature | no-sdrconnect status | Reimplementation action |
| --- | --- | --- |
| Remove SDRconnect integration | Present | Keep |
| P25P1 squeak/beep guard | Present | Keep |
| Uncalibrated mixer scalar fallback | Present | Keep |
| Now Playing activity tabs | Present, still being refined | Keep and stabilize |
| Now Playing hang time/preferences | Present | Keep |
| Advanced P25 encryption display | Present | Keep and validate |
| P25 UI/metadata hysteresis | Present | Shared stabilizer now feeds UI facts and RadioResolve metadata |
| RadioResolve completed-call upload | Present | Validate with redirect-to-file before production upload |
| RadioResolve stream editor/status | Present | Continue refining test/status UX |
| Bulk alias stream assignment | Missing | Reimplement |
| Stable RadioResolve GUID identity | Present | Validate clone/copy behavior |
| RF telemetry upload | Present | Validate P25 site metadata schema with redirect-to-file |
| Node check-ins/status | Missing | Reimplement |
| RadioResolve doctor/clock checks | Missing | Reimplement |
| Safe remote commands | Missing | Reimplement carefully |
| Production call timing metadata | Partial | Current upload uses completed recording timing; grant/system-time enrichment remains future work |
| Control-channel discovery persistence | Missing | Reevaluate before reimplementing |
| Audio mute persistence | Missing or unverified | Verify, then reimplement if absent |
| RadioResolve diagnostics hooks | Missing | Reimplement only where still useful |

## Feature Contracts

### 1. RadioResolve Completed-Call Upload

Purpose:

- Upload completed SDRTrunk MP3 call recordings to the RadioResolve receiver-call API.

Inputs:

- Enabled RadioResolve stream configuration.
- Completed recording file path.
- Recording metadata from SDRTrunk:
  - start time
  - end time or duration
  - source radio ID and source alias
  - target talkgroup/radio ID and target alias
  - decoder/protocol
  - frequency
  - channel name
  - system/site labels when available
  - P25 NAC when available
  - RadioResolve GUID when available
  - encryption summary when available
- Stream runtime settings:
  - maximum recording age
  - queue limit
  - in-flight upload limit
  - retry policy

Outputs:

- Multipart HTTP request to `POST /api/node/upload-call`.
- MP3 file streamed from disk, not loaded fully into memory.
- Structured multipart fields for call metadata.
- Stream status updates in the SDRTrunk UI.
- Bounded retry queue.
- Logs that identify failures without exposing API keys.

Acceptance:

- Successful upload returns accepted status from server.
- Temporary failures retry.
- Auth failures stop retry noise and show a credential/config problem.
- Old recordings age out instead of growing an unbounded queue.

### 2. RadioResolve Stream Configuration And Editor

Purpose:

- Let the user configure RadioResolve as a normal SDRTrunk stream provider.

Inputs:

- Stream name.
- Enabled/disabled state.
- Server URL.
- API key.
- Node name.
- Node timezone.
- Maximum recording age.
- User action to test connection or refresh status.

Outputs:

- Playlist XML stream entry.
- Runtime `RadioResolveConfiguration`.
- UI test/status result:
  - connection ok
  - authenticated node identity when server returns it
  - auth failure
  - endpoint/network failure
- No raw API key in logs or UI output.
- Optional debug redirect writes call/site payload JSON files locally instead of uploading.

Acceptance:

- Configuration survives restart.
- Existing SDRTrunk stream selection model can assign aliases to the stream.
- Manual status refresh does not constantly poll the server.

### 3. Bulk Alias Stream Assignment

Purpose:

- Allow selecting many aliases/talkgroups and applying or clearing a stream assignment in one operation.

Inputs:

- Selected aliases in the alias list or bulk editor.
- Selected stream name from existing broadcast configurations.
- Action:
  - apply selected stream
  - clear all stream assignments

Outputs:

- `BroadcastChannel` alias IDs added to selected aliases on apply.
- Existing broadcast-channel IDs removed before applying a replacement stream.
- All broadcast-channel IDs removed on clear.
- Playlist marked dirty/updated through SDRTrunk's normal model events.

Acceptance:

- User can multi-select talkgroups and set one stream without opening each alias.
- The normal Streaming tab alias assignment still works.
- This feature does not require RadioResolve-specific stream code; it should work for any SDRTrunk stream name.

### 4. Stable RadioResolve RF Identity

Purpose:

- Give each configured RF source a stable identity that does not change when the user renames channels or sites.

Inputs:

- Configured channel creation/load.
- Channel copy/clone.
- Started processing chain.
- P25 traffic channel creation from a parent control channel.

Outputs:

- Persistent `radres_guid` on configured channels.
- Read-only GUID display in channel editor.
- Traffic channels inherit the parent/control channel GUID.
- Completed-call uploads include `radres_guid`.
- RF telemetry includes `radresGuid`.

Acceptance:

- Existing channels missing a GUID get one automatically.
- Cloned channels get a new GUID instead of duplicating the original.
- Trunked calls identify the site/control source, not a temporary traffic-channel object.

### 5. Production Call Timing Metadata

Purpose:

- Improve call ordering, duplicate matching, and ingest timing.

Inputs:

- P25 control-channel grant receiver timestamp.
- First audio buffer receiver timestamp.
- Optional P25 system time sample and quality.
- Recording start/end flow.

Outputs:

- MP3 metadata/comment fields:
  - `call_start_ms`
  - `call_start_source`
  - `p25_system_time_estimate_ms`
  - `p25_system_time_quality`
- Recording filename and ID3 date use the best receiver-local call start time.

Acceptance:

- `call_start_ms` is the canonical call time.
- P25 system time is informational only.
- If grant timing is unavailable, first-audio-buffer timing is used as fallback.

### 6. RF Telemetry Upload

Purpose:

- Send structured decoded RF/system state to RadioResolve so the server can learn systems, sites, channels, bandplans,
  neighbors, patches, and talker aliases.

Inputs:

- Active P25 control-channel processing chain.
- Stable channel GUID.
- Stabilized decoded P25 facts:
  - WACN
  - SysID
  - RFSS
  - Site
  - NAC
  - current control channel
  - secondary control channels
  - frequency bands/bandplan
  - neighbor sites
  - patch groups
  - talker aliases
- Enabled RadioResolve stream configuration and API key.

Outputs:

- JSON request to `POST /api/node/rf-state`.
- Summary hash for dedupe.
- Runtime upload status/logs.
- Optional debug redirect writes the same payload shape locally for inspection before live upload.

Acceptance:

- Telemetry is typed JSON, not parsed from UI strings.
- Identical snapshots are deduped with a summary hash.
- Slowly-changing RF facts are stabilized before upload.
- Missing facts may be null; bad decode bursts should not overwrite stable identity.

### 7. P25 RF Fact Stabilization

Purpose:

- Prevent decode errors from rapidly changing key system/site facts in UI or uploads.

Inputs:

- Raw decoded P25 network/status messages.
- Current stable value.
- Candidate value and observation count/time.

Outputs:

- Stable values for:
  - WACN
  - SysID
  - RFSS
  - Site
  - current control channel
  - alternate/secondary control channels
- Rejected or delayed candidate values until they are plausible and repeated.

Acceptance:

- First known valid values appear quickly.
- Later changes require confirmation.
- Obviously impossible frequency/system facts are ignored.
- UI and RF telemetry use the same stabilized source when practical.
- Current implementation uses a shared P25 stabilizer for both UI-facing facts and RadioResolve metadata snapshots.

### 8. RadioResolve Node Check-In

Purpose:

- Let SDRTrunk periodically tell RadioResolve that the receiver node is alive and what it can do.

Inputs:

- Enabled RadioResolve configuration.
- Node name/timezone.
- App version/build label.
- Capabilities:
  - call upload
  - RF telemetry
  - diagnostics
  - remote commands enabled/disabled
- Local clock information.

Outputs:

- JSON request to `POST /api/node/check-in`.
- Last successful check-in time.
- Last failed check-in time/message.
- Optional server command list.

Acceptance:

- Runs on a bounded interval.
- Does not run without an enabled RadioResolve stream.
- Auth failures are visible but not noisy.

### 9. RadioResolve Doctor And Clock Checks

Purpose:

- Give a quick health report for node-side integration problems.

Inputs:

- Active RadioResolve stream configuration.
- Server URL.
- API key presence.
- RadioResolve endpoint health.
- Auth test result.
- Local clock offset check.

Outputs:

- Human-readable doctor summary.
- Machine-usable command result when invoked by node service.
- UI status text.

Acceptance:

- Checks endpoint reachability, auth, and clock sanity.
- Does not expose secrets.
- Can run manually from UI and optionally from a safe remote command.

### 10. Safe Remote Commands

Purpose:

- Allow RadioResolve to ask SDRTrunk for safe status actions without giving it broad process control.

Inputs:

- Remote commands enabled preference.
- Command list from node check-in response.
- Supported command names.

Outputs:

- Command result JSON to `POST /api/node/command-result`.
- Action results for safe commands only.

Supported first-pass commands:

- send check-in now
- run doctor
- check clock offset

Explicitly not supported inside SDRTrunk:

- reboot host
- arbitrary shell command
- arbitrary file read/write
- playlist modification

Acceptance:

- Disabled by default unless we decide otherwise.
- Unsupported commands return a clear unsupported result.
- No command may expose API keys or local secrets.

### 11. RadioResolve Diagnostics Hooks

Purpose:

- Keep targeted diagnostics available for receiver wedge/debug work without polluting normal release behavior.

Inputs:

- Command-line flags or system properties.
- Optional trigger directory.
- Runtime processing-chain state.
- Tuner/control-channel rotation state.

Outputs:

- Processing diagnostic report.
- Thread dump report.
- Optional heartbeat file.
- Extra rotation logs only when enabled.

Acceptance:

- Disabled unless explicitly enabled.
- Clearly grouped under RadioResolve diagnostics code.
- Easy to remove later.

### 12. Control-Channel Discovery Persistence

Purpose:

- Optionally learn announced P25 control-channel frequencies and merge them into the configured source list.

Inputs:

- Decoded current and secondary control-channel messages.
- Existing source frequency configuration.
- User preference or channel setting enabling discovery.

Outputs:

- Updated multiple-frequency source configuration.
- Bounded list of learned control frequencies.

Acceptance:

- Disabled by default unless user enables it.
- Does not feed Now Playing table state directly.
- Does not add impossible/out-of-range values without validation.

Open question:

- Reevaluate whether this is still needed after the no-sdrconnect activity tabs and RF telemetry are stable.

### 13. Audio Mute Persistence

Purpose:

- Remember the user's audio mute state across restarts.

Inputs:

- Audio output mute toggle.
- User preferences storage.

Outputs:

- Persisted mute preference.
- Audio output starts muted/unmuted according to preference.

Acceptance:

- No audio leaks when user expects muted startup.
- Preference applies to the active audio output manager, not only the UI button.

### 14. Now Playing Activity View

Purpose:

- Provide a stable, readable activity view for conventional channels and P25 trunked sites.

Inputs:

- Started channel events.
- Channel metadata updates.
- P25 control-channel facts.
- P25 traffic grants.
- P25 encryption identifiers.
- User preferences:
  - retain idle call details
  - advanced P25 encryption status
  - traffic idle hang milliseconds

Outputs:

- One Conventional tab for non-trunked channels.
- One tab per started trunked site/channel instance.
- Rows sorted by frequency and timeslot.
- Current control and alternate control color coding.
- Idle/call/encrypted status.
- Optional advanced encryption display.
- Selection event for lower detail panes.

Acceptance:

- Rows do not flicker under normal traffic.
- Active calls appear immediately.
- Idle transition is delayed per row by hang time.
- Selection behavior is predictable for idle and active rows.

### 15. Messages/Events/Details Selection Behavior

Purpose:

- Make the lower detail panes follow user selection in a predictable way.

Inputs:

- Selected activity row.
- Processing chain associated with the row, if active.
- Talkgroup/context key.
- Same-talkgroup continuation versus new-talkgroup selection.

Outputs:

- Messages/events/details for the selected active channel when available.
- Clear or disabled/empty state when selected idle row has no active processing chain.
- Messages persist through the same talkgroup and clear on a new talkgroup.

Acceptance:

- Voice traffic does not cause rapid clearing/reverting.
- Idle row selection does not silently show stale data from a different row.

### 16. P25 Encryption Visibility And Debugging

Purpose:

- Make encrypted call behavior visible enough to validate Alg/Key information.

Inputs:

- P25 encryption identifiers from HDU/LDU/ESS/PTT/control events.
- Talkgroup and radio identifiers.
- Activity row state.
- User preference for advanced encryption display.

Outputs:

- Sticky encrypted status for the current call.
- Condensed Alg/Key display when enabled.
- Temporary debug CSV/log with talkgroup, radio, algorithm, key ID, and event source.

Acceptance:

- Brief decode gaps do not flip encrypted calls back to plain call status.
- Debug logging is removable for release builds.

## Out Of Scope For Clean Reimplementation

- Copying old branch code that only supports one debug incident and has no current product contract.
- Parsing UI text as the source of truth for uploads or telemetry.
- Creating new stock-build deviations.
- Server database migrations, except where documented separately for coordination.
- Headless web UI replacement work, except to keep APIs/models clean enough to reuse later.

## Suggested Implementation Order

1. Commit current no-sdrconnect UI/debug work into a known baseline.
2. Add bulk alias stream assignment, because it is small and immediately useful.
3. Reimplement RadioResolve stream configuration and completed-call upload.
4. Add production call timing metadata.
5. Add stable `radres_guid` identity.
6. Add RF telemetry using stabilized P25 facts.
7. Add node check-ins, doctor, clock checks, and safe remote commands.
8. Reevaluate control-channel discovery persistence.
9. Cleanly separate temporary debug code from release code.

## Validation Checklist

- Build compiles without SDRconnect classes.
- Existing SDRTrunk stream types still work.
- RadioResolve upload tests cover success, retry, auth failure, and queue limits.
- Alias bulk stream assignment works for multiple selected talkgroups.
- Recordings contain expected timing fields.
- Calls include stable GUID when available.
- RF telemetry does not change stable WACN/SysID/RFSS/Site because of one bad decode.
- No final logs, screenshots, docs, or test fixtures contain real API keys.
