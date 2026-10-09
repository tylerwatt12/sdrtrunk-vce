# Talker aliases: names sent by radios

A talker alias is a name transmitted over the air by a radio system. VCE can display that name beside a radio ID,
which helps you recognize who is transmitting without naming every radio yourself.

This guide covers the P25 Motorola and L3Harris formats handled by current Nightly. Numbered Alpha builds may behave
differently; use the documentation for the release you installed.

## Talker aliases and your Alias Lists

A radio might transmit the name `DEMO-ENGINE-4` with its radio ID. That name is a **talker alias**, also shown in some
VCE tables as **OTA Alias**. OTA means “over the air.”

An **Alias List** contains the names and settings you maintain in VCE, such as `Engine 4` or `Fire Dispatch`.
Receiving a talker alias does not create or overwrite one of those configured entries.

Both names can be useful. A transmitted name may describe a radio you have not named yet, while a configured alias
lets you use your own consistent labels. The transmitted text is whatever the system sends.

## Find received names

Open **Listen > Main** and use the **Radio Directory** to open a P25 system. Its **Talker Aliases** tab lists retained
names, radio identities, and the time each alias was last observed. You can also open a radio's detail page to see
its talker alias and related activity. Access to these pages depends on the permissions for your account.

To choose which name is used for a call's source display, open **My Settings > Change Source Names**:

| Choice | What you see |
| --- | --- |
| **Talker Alias preferred** | The received name, with your configured source alias used when it is unavailable. |
| **Source Alias preferred** | Your configured source alias, with the received name used when it is unavailable. |
| **Both** | Each distinct name once. |

This changes presentation for your account. It does not edit Alias Lists or change listening, recording, or streaming
choices.

## Why a name may be missing

Talker aliases depend on what the system transmits and what VCE can receive. A blank field can mean:

- The system or radio does not transmit a name.
- VCE joined the call after part of the name had already been sent.
- Reception errors or a missed message prevented a usable result.
- The name could not be associated safely with the transmitting radio and call.
- Activity collection was off, so the receiver did not save the observation for the directory.

A later transmission can provide another opportunity to receive the name. Good audio alone does not guarantee a
complete alias: the name travels in signaling messages, which have their own reception and validation requirements.

A retained name and its observation time describe what VCE last accepted. They do not prove that the radio is
currently transmitting, affiliated with a talkgroup, or still using that name.

## Keep the radio's system with its ID

A radio ID is not globally unique. Different P25 systems can use the same number for unrelated radios. A complete
P25 subscriber identity includes the home **WACN**, **System ID**, and **Radio ID**.

For example, fictional radio `1001001` on one system must remain separate from radio `1001001` on another. When a radio
roams through ISSI, VCE also distinguishes its home identity from the temporary Working ID observed on the visited
system. Received names are associated with the available system and subscriber context rather than copied to every
radio with the same number.

## How VCE checks and keeps the name

Motorola and L3Harris use different formats. VCE follows the requirements of the decoded format, then checks that the
text is usable and that any available radio and call context agree.

For Motorola, the receiver collects a header and numbered blocks, keeps compatible sequences together, reconstructs
the text, and checks the completed checksum. A complete Motorola observation also contains the radio's P25 identity.
Phase 1 and Phase 2 carry the pieces in different messages; Phase 2 also keeps the two timeslots separate.

L3Harris text does not carry the same independently verifiable radio-and-talkgroup information. VCE uses the associated
traffic and call context and rejects conflicting source-radio information rather than guessing. The Motorola
header, sequence, and checksum rules should not be applied as a description of every L3Harris alias.

An accepted name can update the receiver's live alias cache. Applying it to a current call requires the appropriate
radio, destination, time, and system context. A late name is not allowed to replace the name on an unrelated call
that has since started on the same frequency.

Saving the observation also requires a matching call with usable system context and enabled activity collection. Those observations
are handed to the activity service; they are not direct database writes from the decoder. Retained summaries keep the
latest accepted name and its observation time with the corresponding radio. Repeated observations update that
information without creating a new radio for every transmission.

<details>
<summary>Implementation references</summary>

These sources are useful when investigating a particular format or identity mismatch:

- [P25 traffic-channel alias handling](../src/main/java/io/github/dsheirer/module/decode/p25/P25TrafficChannelManager.java)
- [Live alias cache and system-scoped identity matching](../src/main/java/io/github/dsheirer/identifier/alias/TalkerAliasManager.java)
- [Motorola Phase 1 assembler](../src/main/java/io/github/dsheirer/module/decode/p25/phase1/message/lc/motorola/LCMotorolaTalkerAliasAssembler.java)
- [Motorola Phase 2 assembler](../src/main/java/io/github/dsheirer/module/decode/p25/phase2/message/mac/structure/motorola/MotorolaTalkerAliasAssembler.java)
- [L3Harris Phase 1 assembler](../src/main/java/io/github/dsheirer/module/decode/p25/phase1/message/lc/l3harris/HarrisTalkerAliasAssembler.java)
- [Activity collection](../src/main/java/io/github/dsheirer/stats/activity/ReceiverActivityService.java)

</details>

## More help

- [Documentation guide](README.md)
- [Browser listening and Scan Lists](browser-listening-and-scan-lists.md)
- [Activity and identity storage reference](dmr-nxdn-site-tracking-storage.md)
