# Browser listening and Scan Lists

Scan Lists let you choose what to hear from VCE in a browser. A list can combine police and fire talkgroups from one
system, transit from another, and conventional dispatch channels. Each listener chooses their own lists while the
receiver continues monitoring its configured channels.

This guide describes current Nightly. Numbered Alpha builds may have different controls or fewer features.

## Start listening

1. Open **Listen > Scanner** in the website.
2. Select one or more available Scan Lists. You can select up to 16.
3. Press the play button (**Listen live**).
4. Use the playback controls to pause, skip, hold, or avoid calls. You can continue browsing the website while audio
   plays in the player.

Selecting a list saves your choice but does not start audio. If you press play without an available list selected,
VCE selects the list marked as the default.

Browser audio plays **completed calls**. You hear a call after the receiver finishes receiving it, so listening has a
delay. When calls overlap, they can wait in your browser's queue instead of playing over each other. Browser listening
does not change recordings, external streams, or another listener's audio.

## Playback controls

| Control | What it does |
| --- | --- |
| **Pause / Resume** | Pause your audio while new calls continue to join the queue. Resume continues the current call, then the waiting calls. |
| **Skip** | Move past the current call. |
| **Hold / Release hold** | Keep listening to the current playback target until you release it. |
| **Avoid** | Skip the current target and future calls from it. Remove it from the **Avoid List** when you want to hear it again. |
| **Replay last call** | Replay the last call kept in this browser tab. It is unavailable while paused. |
| **Clear queue** | Remove waiting calls. |
| **Stop** | End listening and clear the current call and queue. Playing again starts with new calls. |

The playback target is usually a trunked talkgroup or a conventional channel. The call's details can still show a
digital talkgroup or radio ID. For conventional DMR, each timeslot is a separate target. A trunked DMR talkgroup keeps
the same target across timeslots. Identical talkgroup numbers on different systems stay separate.

Open **Scanner settings** to change **Group calls by playback target**. With grouping enabled, calls already waiting
for the same target play together before another target gets a turn. **Calls before switching targets** accepts
1–20 calls and defaults to four. With grouping disabled, waiting calls play in order of their start time. These
settings affect waiting calls; they cannot change audio that has already started.

### The queue has limits

Each browser tab can hold **100 waiting calls**, separate from the current call. At the limit, the oldest waiting
call is removed to make room for a new one. A long pause therefore cannot guarantee complete catch-up.

Refreshing the page, opening a new browser document, or losing playback access clears the queue. A temporary
connection interruption preserves calls already queued, but calls can be missed before the connection returns.
Changing Scan Lists, Hold, Avoid, Skip, or Clear queue can remove or bypass waiting calls.

Queued audio can also expire from the receiver's temporary cache. If a call is unavailable or fails to load, the
player skips it and continues. Use [Managed Recordings](../README.md#find-and-replay-recordings) for saved-call browsing;
the listening queue and Replay last call are not an archive.

## Create a Scan List

An administrator sets up the lists listeners can choose:

1. Open **Manage > Scan Lists** and create a list, such as `Dispatch`.
2. Leave **Available to listeners** enabled when the list is ready to use.
3. Open **Manage > Aliases**, select the appropriate Alias List, and add the wanted aliases to that Scan List.
   Bulk editing can assign several aliases together.
4. Repeat with aliases from other Alias Lists if you want to combine systems or protocols.
5. Open Scanner, select the list, and press play to check it.

An alias can belong to several Scan Lists. If a call matches more than one of your selected lists, the browser offers
it once. Hiding a Scan List removes it from listener choices while leaving it available for administrators to edit.

For example, `Dispatch` could include police and fire talkgroups from a P25 system, transit from another trunked
system, and an AM or NBFM dispatch channel. A listener can select `Dispatch` together with another list without
changing the channels the receiver monitors.

## Choose how unmatched calls and new aliases behave

Each saved channel uses one compatible Alias List: **P25**, **DMR**, **NXDN**, or **Analog** (AM/NBFM). Traffic channels
created from a trunked control channel inherit its assignment. Check that assignment in **Manage > Channels** if a
call is not reaching the lists you expect.

In **Manage > Aliases**, select the list and open **Call Handling Defaults**. It has two independent tabs:

- **Unmatched Calls** chooses recording, Scan Lists, and external streaming when the destination talkgroup or patch
  group has no exact alias or covering talkgroup range.
- **New Aliases** supplies the starting recording, Scan List, and streaming choices for new aliases created manually
  or imported from RadioReference. Changing these defaults does not rewrite existing aliases.

A matching destination alias supplies its own choices. If it belongs to no Scan List, the unmatched-call choices do
not fill in for it. Other matching aliases, such as a source-radio alias, can add Scan List memberships; a source-radio
match alone does not turn off the unmatched-call fallback.

Choose catch-all external streaming carefully: **Unmatched Calls** can send traffic you have not reviewed to a
third-party service. Leave its Streaming choices empty if only individually approved talkgroups should be uploaded.

New fully encrypted RadioReference talkgroups are created with recording, Scan Lists, and external streaming
disabled. Partial or unknown encryption status uses the chosen new-alias defaults. Updating an existing
RadioReference alias preserves its local listening, recording, and streaming settings. A
[VCE configuration CSV import](alias-import-export.md) instead uses the per-alias choices stored in the file.

## What a fresh setup includes

VCE creates a Scan List named **Default** and four Alias Lists: **Default P25**, **Default DMR**, **Default NXDN**, and
**Default Analog**. Their unmatched-call choices route clear calls to Default, so you can begin listening without
naming every talkgroup first.

New channels created in the Channel editor and RadioReference trunked-site imports initially use the matching
factory Alias List when it is available. You can choose another compatible list. RadioReference agency-frequency
imports do not assign an Alias List automatically; review those channels before listening.

A new custom Alias List starts without recording, external streaming, or Scan List defaults. Open **Call Handling
Defaults** to choose them. Importing a mainline sdrtrunk XML playlist adds listening-enabled aliases to the current
default Scan List; aliases marked **Do Not Monitor** remain outside it. Supported imported channels without an Alias
List receive a compatible factory list.

## If a call is missing

- Check that the channel is receiving and decoding the traffic in **Listen > Live**.
- Check the channel's Alias List and the destination alias's Scan List memberships.
- For a talkgroup without an alias, check **Call Handling Defaults > Unmatched Calls**.
- Check that the Scan List is **Available to listeners** and selected in Scanner.
- Release Hold or remove an entry from Avoid List if it excludes the call.
- Check the player for skipped-call notices. Queue limits, connection gaps, and expired audio can lose calls.

<details>
<summary>Technical playback scope and delivery limits</summary>

Hold and Avoid use stable identities, so renaming a channel or alias does not change their target:

- Conventional AM/NBFM and other conventional digital calls use the saved channel's `configuration_id`.
  Conventional DMR adds the timeslot.
- Trunked calls use a `radio_system_key` and a talkgroup, patch-group, or radio identity. P25 uses WACN and System ID;
  standard DMR Tier III uses its model and Network ID; NXDN Type-C uses its location category and System ID.
- Saved channels share a system target only inside this receiver profile and after complete system identity is
  learned. Capacity Plus, Connect Plus, Capacity Max, Hytera Tier III, unknown or incomplete DMR, incomplete NXDN
  Type-C, and NXDN Type-D remain separate for each saved channel.

The API supplies the target in `playback_target`. Its `label` is displayed to the listener and its `key` controls
selection. Channel names, alias names, and RadioResolve IDs are not target keys. System-scoped Avoid entries include
system context to distinguish repeated names or IDs.

After duplicate-call resolution, the receiver combines eligible Scan List memberships and offers one completed call
to selected listeners. Recording and external streaming have their own outputs and do not use the browser queue.

Play starts at the live edge: calls already in the receiver's cache are not backfilled. Each tab owns its waiting
queue and Replay last call audio. It fetches queued audio when playback reaches that call. A fetch exceeding
15 seconds, unavailable audio, or a decoding failure causes that call to be skipped.

While a browser feed is active, the receiver's shared temporary cache holds at most 512 calls or 128 MiB of audio;
entries expire after 30 minutes. After the last feed request and a five-second grace period, receiver maintenance
releases the cache. Pause keeps requesting calls and therefore keeps that shared cache active.

Receiver callbacks do not wait for browser matching or WAV encoding. One low-priority worker handles that work; no
per-listener playback queue is kept on the server. The receiver handoff, cache, network, and browser queue each have
limits, so the 100-call browser limit is not a delivery guarantee. A detected feed gap continues with valid new calls.
See [Web API v1](api-v1.md) for the complete API contract.

</details>

<details>
<summary>Older default-list upgrades</summary>

The upgrade from database formats older than 11 restores missing factory Alias Lists once, including previously
deleted defaults. A same-family `Default NBFM` becomes `Default Analog` when that name is available. Existing custom
lists and routes are preserved; a factory-name collision with another protocol family is renamed uniquely. Only
channels without a selected Alias List receive a compatible default. Later startups do not recreate deleted lists.
The canonical factory lists retain their unmatched-call route to Default when manually recreated.

</details>

<details>
<summary>Comparison with mainline sdrtrunk</summary>

This comparison was checked against upstream `master` at
[`80360029`](https://github.com/DSheirer/sdrtrunk/commit/80360029efb008dca993938d1e34ad4a7a8c15bd)
on August 28, 2026. Later mainline releases may differ.

| Behavior | Mainline sdrtrunk at that revision | VCE browser listening |
| --- | --- | --- |
| Audio | Sends an audio segment to desktop playback while a call is being received. | Plays a resolved, completed call. |
| Overlapping calls | Assigns active segments to available audio outputs; unassigned completed segments are removed. | Eligible completed calls can wait in each browser's bounded queue. |
| Listener choices | Listen switch and optional playback priority on each alias. | Reusable Scan Lists chosen by each browser listener. |
| Unmatched destination | Default playback priority applies unless another matching alias changes it. | Unmatched-call defaults choose recording, external streaming, and Scan Lists. |
| Alias List protocols | A list can contain identifiers from multiple protocols. | A list uses the P25, DMR, NXDN, or analog family. |

Recording and external streaming are separate from playback in both projects. VCE also retains receiver-local audio
playback; the completed-call queue in this guide describes browser listening.

</details>

## Related guides

- [Alias CSV import and export](alias-import-export.md)
- [Talker aliases](talker-alias-implementation.md)
- [Documentation index](README.md)
