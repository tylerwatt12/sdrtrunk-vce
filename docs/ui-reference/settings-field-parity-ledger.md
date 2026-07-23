# How to review the Settings migration

Status: complete inventory and first owner review, reconciled 2026-07-21
Implementation authorization: **none**

Open the [Settings migration review](settings-field-parity-ledger.html) in a browser. It works directly from disk and
does not need SDRTrunk or a web server to be running.

The normal view is written for an administrator. Each row answers four questions:

- What setting or action exists today?
- Where will it appear, and how will the administrator use it?
- Why does it matter, and what limits protect the receiver or database?
- Is the direction agreed, merely suggested, or still waiting for a decision?

Old JavaFX locations, source-file references, and engineering notes remain available under **Technical record
(optional)** in each row. They are hidden during normal review.

## Recording your review in the page

Each row now has a **My review** choice and an expandable note field:

- **Approve as written** accepts the proposed treatment.
- **Decision or change noted** records a different choice in the note.
- **Discuss later** marks something that needs more conversation.
- **Not reviewed** leaves the row open.

Notes save automatically in that browser on that computer. They do not write to SDRTrunk, its settings, or its
database. Browser storage for a local file is tied to the browser profile and usually the exact file path, so use
**Download review backup** for safekeeping. **Load/merge backup** restores that file without erasing review rows that
are not in the backup.

The **My review** filter can show unreviewed rows, a chosen review state, rows with notes, or rows without notes. The
filtered CSV includes your review choice, note, and last-changed time. To hand the completed review back without
copying text into chat, download the JSON backup and attach it or point Codex to the downloaded file.

## What is included

- Playlist Settings: Hardware, Channels, Aliases, Listen lists, Streaming, and RadioReference.
- General Settings: Calls, Audio, Voice decoders and keys, Performance, Display and radio IDs, Storage, Statistics, and
  Public access.
- The server and computer-level controls that stay in the local JavaFX setup utility.
- Features already marked for removal, including MPT-1327, Funcube, heterodyne processing, scripts, old raster icons,
  and receiver-computer speaker output.

Separate live pages such as Dashboard, Live Systems, Events, Messages, Map, playback, and selected-channel signal
views will receive their own checklists and detailed page mockups later.

## Current totals

| Review state | Entries |
| --- | ---: |
| Total | 303 |
| Agreed direction | 303 |
| Suggested but not approved | 0 |
| Needs your decision | 0 |
| Planned removals | 40 |

The inventory now also includes the source-added common tuner center-frequency lock and explicit NXDN Alias creation
parity. The browser-local **My review** choices and notes remain separate evidence of the review and are not silently
rewritten when the canonical ledger wording changes.

## Important details found during the inventory

- A multi-frequency channel can limit the full frequency range that SDRTrunk may use. This helps it position a tuner
  to cover traffic channels, not only the listed control frequencies.
- A multi-frequency channel can remember which frequency SDRTrunk should try first, even though the old editor does
  not normally show that value.
- Imported DMR and NXDN channel maps can contain an uplink frequency. Editing another field must never erase it.
- The AM logging checkboxes exist in JavaFX but currently do not load or save correctly. The web move should preserve
  them and repair that bug.
- An alias can optionally replace only the talkgroup/target number reported to a streaming provider. It does not change
  decoding or the separately reported transmitting radio ID. Leaving it blank sends the talkgroup SDRTrunk decoded.
- Each physical tuner needs its own identity, usually model and serial number, so two identical tuners can both be
  selected.
- The old RSPdx screen shows HDR Bandwidth, but the control appears unusable and its saved value may not reload.
- Different tuner models support different controls. Hardware pages must show only the controls that the selected
  receiver actually supports.
- Every supported tuner has one common **Keep center frequency fixed** choice. It prevents automatic retuning but is
  separate from the temporary lock shown while active channels are using a tuner.
- The main branch adds NXDN Talkgroup, Talkgroup Range, Radio ID, Radio ID Range, and shared AMBE tone Alias creation.
  The web design keeps all five choices and validates the decoded 16-bit NXDN identifier range.

Nothing above is removed simply because it was absent from an early mockup.

## Review result

The downloaded 291-row review and the follow-up questions have been incorporated. Every current row now has an agreed
direction. The review resolved the ambiguous points rather than treating a missing wireframe field as permission to
remove it. Later approved workflow decisions and source-backed parity additions bring the current inventory to 303
rows.

The old browser note for **AL-023** may still say “deny” because review notes are intentionally never overwritten. The
newer decision supersedes it: keep the field and explain it clearly.

## Directions already agreed

- Settings requires the single administrator account.
- Only one administrator browser session may be active. Tabs in that browser share the session; a newer successful
  login ends the older browser session.
- Existing read-only web features are public by default and can each be changed to administrator-only.
- Wideband spectrum is always administrator-only, and only one spectrum view can be active on a receiver.
- Spectrum opens only after selecting a tuner and choosing **View Spectrum**. It uses the full-width lower part of the
  Hardware page.
- A blank channel start order means do not start automatically. Numbers mean first, second, third, and so on. Assigning
  a number already in use shifts later channels automatically.
- Alias lists with thousands of entries load one page at a time and support search and selection of multiple rows.
- Every Settings field or field group has the same small circled information button beside its label. Hover or keyboard
  focus shows help temporarily; click or tap keeps it open. Essential errors, warnings, units, ranges, and disruption
  effects remain visible instead of being hidden in the help.
- Talkgroup, Radio, and Other matching fields remain editable inside Aliases. The old separate Identifier and Record
  tabs do not return.
- Fully qualified P25 Talkgroup and Radio matches require the final ID. WACN and System are either both exact or both
  **Any**, and exact matches take priority.
- **Stream this Alias as Talkgroup** remains in Alias Streaming. It changes only the talkgroup sent to streaming
  providers; the transmitting radio ID remains separate and unchanged.
- Streaming destinations are assigned from Aliases; the duplicate provider-level Aliases tab does not return.
- Built-in scalable icons replace old image icons and Icon Manager.
- Alias Actions and scripts are removed entirely.
- MPT-1327 and its named Channel Maps are removed. DMR LCN and NXDN channel-number maps remain.
- Funcube and heterodyne support are removed completely.
- Speaker/output-device controls for the receiver computer do not move to the headless web settings.
- Sound-card input, AM, LTR, LTR-Net, Passport, MPT-1327, the working-frequency envelope, and RSPdx HDR Bandwidth are
  removed. P25 Conventional, P25 Phase 1, P25 Phase 2, DMR, NBFM, and NXDN remain.
- Channel multi-select is used only for Start and Stop. Channel search remains, but the old All/Playing/Auto-Start
  filters and the proposed Protocol/Alias List filters do not move initially.
- Recording tuners select compatible I/Q WAV files from SDRTrunk's managed recordings folder; the web page does not
  upload them or browse arbitrary receiver paths.
- A tuner may have a short friendly name while its actual model and serial number remain visible.
- **Keep center frequency fixed** appears once in the shared tuner frequency controls for every supported tuner. It is
  off by default. When on, automatic channel allocation cannot move that tuner; an out-of-band channel must use
  another tuner or cannot start. An administrator can still choose another center frequency while the tuner is idle.
- Aliases support NXDN Talkgroup, Talkgroup Range, Radio ID, and Radio ID Range creation plus the shared ordered AMBE
  tone matcher. Exact/range values use the decoded 0–65,535 domain, and a range end must be greater than its start.
- The selected-channel FFT/power view and symbol graph remain reachable from Live through **View Signal** and
  **View Symbols**, but those actions are rendered only for the signed-in administrator. There is no duplicate
  active-channel list in Settings, and only one selected-channel diagnostic workspace runs at a time.
- Listen and Recordings share one browser-local player and bounded queue. Play forward uses the filters active when it
  starts and continues adding new matching calls until the listener turns **Follow new calls** off.
- Listen lists are saved administrator configuration made from existing talkgroups and channels. Membership is the
  authoritative live browser-audio gate: a matching call can play even when Record is off or a legacy Alias is marked
  Do Not Monitor. Lists do not start channels, retune receivers, or enable recording. Numeric Alias Priority may order
  competing eligible calls but cannot exclude a list member; the old Alias Listen checkbox retires.
- Recordings search is a separate successful-recording path. It contains only audio actually saved when the destination
  talkgroup had Record enabled; live-only calls never enter the catalog, and turning Record off affects future calls
  without hiding an already-retained recording before its normal recorded-call retention expires.
- Recorded-call retention is a separate Storage setting. It deletes expired audio and its compact lookup entry without
  changing Statistics or Detailed Event History retention.
- The recorded-call folder hierarchy is fixed in the application and cannot be customized. Under the configured
  recordings root it is organized by date, system, site, channel, and talkgroup, with one canonical file per call.
- Spectrum averaging remains an advanced browser display option. FFT-window and smoothing controls use built-in
  defaults and are not exposed.
- RadioReference creates channels and aliases while remaining in the importer; it does not automatically open Channels
  afterward.
- The voice-key page offers **Auto-unlock vault on launch — Unsafe!** only after a successful web unlock. With it off,
  SDRTrunk starts without decryption until the administrator unlocks the vault in the website.
- The administrator sets public display defaults for encryption detail and identifier style; each visitor may override
  them only in that browser. Fixed-width/leading-zero formatting is removed.
- The administrator Logs page reads the current application log and existing ten-day rotating log files. It creates no
  database or additional permanent log history.
- The local JavaFX utility keeps web-server binding, HTTPS certificates, the single-admin setup, computer-level
  services, and physical storage-folder recovery.

## Performance and storage promises behind every row

- Opening a page, saving a setting, importing data, or waiting on another website must never make tuner input,
  control-channel decoding, call handling, recording, uploading, or audio wait.
- While a tuner slider is moving, send only the newest value at a safe rate. Apply hardware changes one at a time for
  each physical receiver.
- Live browser queues, import previews, errors, task output, and waterfall history all have small limits and are
  discarded when no longer needed.
- Do not create permanent access logs, edit histories, task histories, calibration histories, connection-test
  histories, import histories, raw-message logs, or other data that grows forever.
- Current configuration can be saved. New time-based detail is not allowed unless it is the explicitly enabled
  Detailed Event History feature with automatic deletion after the selected number of days.
- Any unavoidable database-layout change is a planned, backed-up update. SDRTrunk never silently changes a deployed
  database during normal use.

## Next design step

Use this accepted checklist to build detailed browser mockups one page at a time. Each Settings mockup must show the
information button beside its fields and at least one open-help example, including keyboard and touch behavior. The
mockup still needs explicit approval before its Settings implementation begins.
