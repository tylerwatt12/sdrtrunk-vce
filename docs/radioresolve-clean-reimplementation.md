# RadioResolve Optimized Implementation Plan

This document tracks the remaining clean implementation work for `SDRTrunk RadioResolve Optimized`.
The source tree is still named `SDRTrunk-bazineta-no-sdrconnect` because that describes the technical ancestry:
bazineta optimized SDRTrunk with SDRconnect removed.

The goal is no longer to port the old RadioResolve branch. The goal is to keep the useful behavior that has already
been reimplemented, remove legacy/debug clutter, and keep the remaining code paths simple enough for one developer to
reason about.

## Build Target

Primary target:

- `SDRTrunk RadioResolve Optimized`
  - Source tree: `/Users/example/Documents/SDRTrunk-bazineta-no-sdrconnect`
  - Release/build slug: `radioresolve-optimized`

Secondary target when ready:

- `SDRTrunk-bazineta-headless-web-ui`

Do not use this plan to modify stock builds.

## Current Baseline

These features are already present in the primary build and should not be treated as future work:

- SDRconnect integration removed.
- bazineta optimized SDRTrunk base retained.
- P25P1 squeak/beep guard.
- Uncalibrated complex mixer scalar fallback.
- RadioResolve streaming provider.
- RadioResolve stream editor with:
  - server URL
  - API key
  - node name
  - node timezone dropdown
  - `Calls + Metadata` mode
  - `Metadata Only` mode
  - redirect-to-file debug mode
- Completed-call upload to `POST /api/node/upload-call`.
- Completed-call payload includes:
  - call time and duration
  - target and source IDs
  - target type
  - frequency
  - system/site labels when available
  - RadioResolve GUID
  - P25 NAC when available
  - logical channel when available
  - talkgroup label/group
  - talker alias when available
  - protection boolean, algorithm ID, and key ID when available
  - node name/timezone
  - agent version
  - original filename
- Persistent `radres_guid` stored on configured channels.
- Editable RadioResolve GUID field in the channel editor.
- Traffic channels inherit the parent/control channel GUID for call uploads.
- RF/site metadata upload to `POST /api/node/rf-state`.
- Repeated RF/site metadata upload acts as the node heartbeat.
- RF metadata status tab for viewing known/sent site metadata.
- Shared P25 fact stabilizer/hysteresis helper.
- Now Playing activity tabs and table column persistence.
- Advanced P25 protected status display.
- P25 protection CSV debug logger preference.
- Audio mute persistence.
- Audio playback queued-call counter.
- Maximum queued playback calls preference.
- Learn announced control channels checkbox and persistence.

## Removed From Scope

These older ideas should not be implemented unless they are explicitly re-scoped later:

- Separate RadioResolve node check-in endpoint from SDRTrunk.
- Remote command polling through node check-in.
- Safe remote commands inside SDRTrunk.
- Doctor/clock check workflow inside SDRTrunk.

Node liveness is represented by repeated `/api/node/rf-state` uploads from active, decoded site/control-channel
metadata. If no control channel is being decoded for a GUID, SDRTrunk should not keep sending RF/site heartbeat updates
for that GUID.

## Current RF Metadata Heartbeat

The existing heartbeat path is:

1. P25 decoder state builds a stabilized site metadata snapshot.
2. The decoder posts a `SiteMetadataEvent` when the snapshot changes or every 5 seconds internally.
3. `RadioResolveBroadcaster` dedupes by snapshot hash.
4. The broadcaster uploads changed snapshots, or repeats an unchanged snapshot after 30 seconds per GUID.
5. That repeated `/api/node/rf-state` upload is the RadioResolve heartbeat.

This path is mostly implemented, but the source boundary still needs cleanup. Current code can still publish incomplete
traffic-channel fragments and partial snapshots. That is the next important RF metadata fix.

Source-boundary reference:

- `docs/p25-control-vs-traffic-metadata.md`

## Remaining Work

### 1. RF/Site Metadata Source-Boundary Rewrite

Purpose:

- Make RF/site metadata come from one clean owner: the started standard/control-channel processing path.
- Keep traffic/voice channels responsible only for per-call metadata.

Required changes:

- Prevent `ChannelType.TRAFFIC` channels from publishing `SiteMetadataEvent`.
- Keep traffic-channel source, target, frequency, timeslot, talker alias, protection, and recording data in call upload.
- Do not merge traffic-channel RF-like fragments into the stable site profile.
- Keep control-channel patch group facts in RF/site metadata.
- Do not allow neighbor-only, band-only, or partial identity snapshots to upload.

Acceptance:

- Traffic voice calls no longer reset or pollute RF/site metadata.
- RF/site metadata contains one coherent profile per GUID.
- Call uploads still include voice-channel-specific data.

### 2. RF Publish-Ready Gate

Purpose:

- Separate "useful internally" from "complete enough to upload to RadioResolve."

Required fields before first upload:

- Channel GUID.
- WACN.
- System ID.
- RFSS.
- Site ID.
- At least one frequency band plan.
- Resolved current/primary control-channel frequency.

Behavior:

- Initial facts may be learned quickly.
- The first upload waits until the profile is publish-ready.
- Later unchanged profiles are uploaded every 30 seconds as heartbeat while the control channel remains active.
- If the current control channel is stale or missing, heartbeat upload stops.

Acceptance:

- RadioResolve does not receive partial site profiles as authoritative data.
- The server can use `last_updated` on the GUID/site row as a stale marker.

### 3. P25 Stabilizer Simplification

Purpose:

- Keep hysteresis in one place and make it easier to debug.

Required changes:

- Use the stabilizer only on facts from the correct source path.
- Treat bootstrap and later changes differently:
  - first coherent values appear quickly
  - identity changes require repeated observations over time
- Fix neighbor-site identity so unresolved and later-resolved frequencies update the same neighbor.
- Neighbor-site keys should use system, RFSS, site, and channel descriptor, not resolved frequency.
- Keep control, alternate control, traffic, and conventional row identities separate.

Acceptance:

- Bad decode bursts do not overwrite WACN, SysID, RFSS, Site, or control channels.
- Neighbor sites do not duplicate because a frequency changed from `0` to a resolved value.
- The stabilizer does not need traffic-channel fallback logic.

### 4. Bulk Alias Stream Assignment

Purpose:

- Allow selecting many aliases/talkgroups and applying or clearing a stream assignment in one operation.

Inputs:

- Selected aliases in the alias list or bulk editor.
- Selected stream name from existing broadcast configurations.
- Action:
  - apply selected stream
  - clear stream assignments

Outputs:

- `BroadcastChannel` alias IDs added to selected aliases on apply.
- Existing broadcast-channel IDs removed before applying a replacement stream.
- Broadcast-channel IDs removed on clear.
- Configuration state marked dirty through SDRTrunk's normal model events.

Acceptance:

- User can multi-select talkgroups and set one stream without opening each alias.
- The normal Streaming tab alias assignment still works.
- The feature is generic and not RadioResolve-specific.

### 5. Production Call Timing Metadata

Purpose:

- Improve call ordering, duplicate matching, and ingest timing.

Current state:

- Completed-call upload uses SDRTrunk recording/call timing.
- Grant/system-time enrichment is still future work.

Desired inputs:

- P25 control-channel grant receiver timestamp.
- First audio buffer receiver timestamp.
- Optional P25 system time sample and quality.
- Recording start/end flow.

Desired outputs:

- `call_start_ms`
- `call_start_source`
- `p25_system_time_estimate_ms`
- `p25_system_time_quality`

Acceptance:

- `call_start_ms` is the canonical call time.
- P25 system time is informational only.
- If grant timing is unavailable, first-audio-buffer timing is used as fallback.

### 6. Now Playing And Detail Pane Cleanup

Purpose:

- Keep the operator UI readable and deterministic while preserving low resource usage.

Current state:

- Activity tabs, RF metadata tab, selected-frequency behavior, column persistence, and protected status display exist.
- Some behavior is still being refined.

Remaining goals:

- Rows should not flicker under normal traffic.
- Selected idle rows should not show stale data from another system/frequency.
- Details, events, messages, channel, and RF metadata tabs should follow the selected activity context clearly.
- Bottom/details panels should stay optional so they can be disabled for resource savings.

Acceptance:

- Clicking a row gives predictable data for that row or a clear unavailable/blank state.
- Voice traffic does not cause rapid clearing/reverting.

### 7. Temporary Debug Cleanup

Purpose:

- Keep debug tools removable and avoid shipping accidental diagnostics in normal release builds.

Current debug pieces:

- P25 RF metadata debug harness.
- P25 protection CSV debug logger option.
- RF metadata status/debug tab.

Required cleanup:

- Remove the P25 RF metadata debug harness after RF thresholds/source-boundary behavior is proven.
- Keep the protection CSV logger as a user preference only if it remains useful.
- Keep the RF metadata status tab only if it remains low-cost and operator-useful.

Acceptance:

- Normal release builds do not create large debug files or network debug services.
- Debug-only code is easy to find and remove.

## Out Of Scope

- Copying old branch code that only supports one debug incident and has no current product contract.
- Parsing UI text as the source of truth for uploads or telemetry.
- Creating new stock-build deviations.
- Separate server database migrations, except where documented separately for RadioResolve coordination.
- Headless web UI replacement work, except to keep APIs/models clean enough to reuse later.
- RAM-only recording cache work; track that separately in `docs/ram-recording-cache-design-notes.md`.

## Suggested Implementation Order

1. Commit or otherwise preserve the current working baseline.
2. Rewrite RF/site metadata ownership so only standard/control-channel paths publish site metadata.
3. Add the RF publish-ready gate and stop heartbeat sends when control decode is stale/missing.
4. Simplify stabilizer keys and remove traffic-chain fallback behavior.
5. Remove the temporary P25 RF metadata debug harness after validation.
6. Add bulk alias stream assignment.
7. Add production call timing metadata.
8. Finish Now Playing/detail-pane cleanup as needed.

## Validation Checklist

- Build compiles without SDRconnect classes.
- Existing SDRTrunk stream types still work.
- RadioResolve call upload succeeds, retries temporary failures, and handles auth failures clearly.
- Redirect-to-file writes the same call/site payload shape that live upload would send.
- Calls include stable GUID when available.
- RF/site metadata does not upload until publish-ready.
- RF/site metadata heartbeat repeats every 30 seconds only while the control channel is actively decoded.
- RF/site metadata does not change stable WACN/SysID/RFSS/Site because of one bad decode.
- Traffic-channel RF-like fragments do not alter the stable site profile.
- Alias bulk stream assignment works for multiple selected talkgroups.
- No final logs, screenshots, docs, or test fixtures contain real API keys.
