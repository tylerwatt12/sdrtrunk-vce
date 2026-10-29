# Using NXDN talkgroup 0 with other applications

> **Draft for review.** Confirm which VCE release includes the numeric ID export fix before publishing this article.

NXDN conventional calls can use talkgroup `0`. VCE can receive these calls, match an alias to TG 0, and record them.
Some versions of other recording and streaming applications treat zero as missing information, so they may reject
the call or fail to put it under the right talkgroup.

There are two separate issues: how VCE writes the ID, and whether the receiving application accepts that ID.

## Conventional and trunked channels

**Stream as talkgroup works with conventional and trunked calls on supported streaming destinations.** The setting
belongs to the matched alias; it is not limited to a conventional channel. Conventional repeaters are a common use
case because several channels may use the same talkgroup number.

The TG 0 example in this article is an NXDN conventional call. Other problems, including the old Type-D ID
formatting and the receiving application's file-import handling, can also affect trunked calls. A nonzero talkgroup
does not automatically rule out an import problem.

## Check your VCE build first

VCE builds containing the numeric ID export fix write TG 0 as plain `0` in recording filenames and audio tags.
Older recordings may contain text such as `0x0000 NULL GROUP`. The same fix writes complete numeric NXDN Type-D
addresses instead of their display text, such as `01-0001`.

The fix keeps the existing filename structure and standard audio tags. It does not rename or retag existing files.
Check your build's release notes to confirm that it includes this fix.

Corrected recordings imported successfully in our Trunking Recorder tests, with and without a configured TG 0 name.
Use Trunking Recorder's **SDRTrunk import** mode when reading these tags. Custom filename import uses its own rules.
Trunking Recorder did not need the workaround below for those corrected files.

## Workaround: stream under a different talkgroup number

If your receiving application rejects TG 0, you can choose a different number for the calls sent to it using VCE's
existing **Stream as talkgroup** setting.

**This is a direct-streaming workaround. It does not change saved recordings or fix an application watching a
recording folder.** The receiving application must support direct uploads from VCE.

For example, if `900001` is unused in the receiving system:

| Where you look | Talkgroup number |
| --- | --- |
| Decoded call and actual alias identifier in VCE | `0` |
| Saved VCE recording and its audio tags | `0` |
| Direct stream using the override | `900001` |
| Talkgroup configured in the receiving application | `900001` |

### Set up the override

1. Choose an unused positive talkgroup number and configure it in the receiving application. Check that its upload
   permissions allow that talkgroup. Do not reuse the number of an unrelated talkgroup.
2. Set up a supported direct streaming destination in VCE. For Rdio Scanner or a compatible ThinLine endpoint,
   use VCE's **Rdio Scanner** destination with the receiving application's upload address and credentials.
3. Open **Manage > Aliases** and select the Alias List assigned to your NXDN channel.
4. Edit the alias for talkgroup `0`, or create an NXDN talkgroup alias for `0` if needed. Keep its actual ID as `0`.
5. Open **Call Handling**. Under **Streaming destinations**, select the intended destination and enter your chosen
   number in **Stream as talkgroup**.
6. Select **Save Changes**, or **Create Alias** for a new alias.
7. Wait for a new call and check that it is saved and displayed under the chosen number in the receiving application.
   A successful connection test alone does not confirm that calls are being imported.

The override belongs to the alias. It can affect other supported streams using that alias, including other channels
sharing the same Alias List. It is not a separate override for just one destination. Source radio IDs are unchanged.

To remove the workaround, clear **Stream as talkgroup** and save after the receiving application supports TG 0.
Update its configuration and permissions to accept `0`. Calls already saved there keep the number they arrived with.

### What we verified

We tested actual VCE uploads using generated audio for a conventional NXDN TG 0 call and a trunked NXDN Type-D
TG `2049` call. With **Stream as talkgroup** set to `900001`, both uploads carried `900001`. The decoded IDs and
local recordings stayed at `0` and `2049`, respectively. Source radio ID `19` was preserved.

| Receiving application | Verified result |
| --- | --- |
| [Rdio Scanner 6.6.3](https://github.com/chuot/rdio-scanner/releases/tag/v6.6.3) | Both mapped uploads passed permissions and were saved under TG `900001`, with unchanged audio files. |
| [ThinLine Radio 26.10.29](https://github.com/Thinline-Dynamic-Solutions/ThinLineRadio/releases/tag/v26.10.29) | Both mapped uploads passed permissions and entered its call queue. Database storage and display were not verified. |

These isolated tests used permissions limited to the selected talkgroup. Without the override, both applications
rejected the TG 0 upload. The unmapped Type-D call was accepted with its original ID; working trunked calls can keep
their existing numbers.

## Why the receiving applications still need fixes

Full TG 0 support in the affected receiving applications requires changes from their maintainers. Their software needs to
recognize the difference between “talkgroup 0 was supplied” and “no talkgroup was supplied” throughout setup,
importing, permissions, and call storage. Changing VCE filenames or adding extra audio tags cannot provide that support.

In those published versions:

- **Rdio Scanner 6.6.3:** The normal talkgroup editor requires a number of at least 1. Its call validation also
  rejects zero, even when a name is supplied. Both setup and import handling need changes for native TG 0 support.
- **ThinLine Radio 26.10.29:** Its direct-upload validation rejects TG 0. Separately, the folder importer rejected
  calls before resolving their talkgroup, including the trunked TG `2049` test call. Its import handling needs fixes
  beyond accepting zero. Source-name handling also differs, so check displayed radio names separately.

Behavior can change between releases. Check the receiving application's release notes for fixes before deciding
whether to use or remove the override. This workaround changes the streamed talkgroup number; it does not add
native TG 0 support to the receiving software.

## If calls are still missing

Check the receiving application's logs, its system and talkgroup settings, and the import method in use. Include
both application versions and whether calls arrive through direct upload, SDRTrunk file import, or custom filename
import when reporting a problem.

For a recording problem, keep an original, unedited file from VCE. A filename may change after another application
imports it, so an original helps identify where the interpretation changed.

[Browse the documentation index](README.md).
