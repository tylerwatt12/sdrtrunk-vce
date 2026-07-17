# How Talker Aliases Work in sdrtrunk-vce

This guide explains how `sdrtrunk-vce` receives, checks, stores, and displays P25 talker aliases. It uses ordinary
language and focuses on what happens to the information rather than on the source code.

## What a Talker Alias Is

A talker alias is a short, human-readable name transmitted by a radio system. Examples include:

- `CDP #0134`
- `CEMS-MED24 P2`
- `MBH-4808`

Every radio already has a numeric radio ID, such as `1880134`. The talker alias gives that number a useful name. A
successful alias transmission can therefore tell SDRTrunk:

> Radio 1880134 identifies itself as “CDP #0134.”

This information comes from the radio network. It is separate from a name that an operator manually enters in an
SDRTrunk alias list. A transmitted talker alias can be useful even when there is no manually maintained name for that
radio.

## The Numbers That Identify a Radio

Several identifiers can be involved in one alias observation:

- **Radio ID** identifies the individual subscriber radio.
- **Talkgroup ID** identifies the group conversation the radio is using.
- **System ID** identifies the P25 radio system.
- **WACN** identifies the larger P25 network containing the system.

A radio ID is not always unique across every P25 system. The fuller identity is effectively:

```text
WACN + system ID + radio ID
```

For example, a complete observation may identify WACN 781824, system 1183, radio 1880134, and alias `CDP #0134`.
Keeping the network and system information with the radio ID prevents an alias from being attached to a similarly
numbered radio on another system.

## Why the Alias Arrives in Pieces

Motorola P25 Phase 1 does not carry the complete alias in one ordinary message. It divides the information into a
header followed by several numbered data blocks.

Think of it as a small numbered package:

1. The header describes what is coming.
2. Data block 1 carries the first piece.
3. Data block 2 carries the next piece.
4. More blocks follow when the alias needs them.
5. A checksum allows the receiver to verify the completed package.

A transmission may look like this:

```text
Header: talkgroup 56132, sequence 1, five blocks coming
Block 1
Block 2
Block 3
Block 4
Block 5
```

These messages can arrive very quickly, but SDRTrunk still has to collect every required piece before it can use the
alias.

## What the Header Says

The header supplies the instructions needed to assemble the data. It includes information such as:

- The talkgroup associated with the transmission.
- A sequence number.
- The number of data blocks that should follow.
- The text format used by the alias.

The sequence number works like a package tracking number. If SDRTrunk is collecting sequence 4 and receives a block
from sequence 5, it must not mix those pieces. They may belong to different alias transmissions or different radios.

When the sequence changes, the holding area is reset so incompatible pieces cannot be combined into a false name.

## What the Data Blocks Contain

After the numbered blocks are placed in order and joined together, the combined data contains:

- WACN
- System ID
- Radio ID
- Encoded alias text
- Checksum

The alias is not sent as ordinary readable letters. Motorola applies its own representation to the text. SDRTrunk
reverses that representation and reconstructs the characters that make up the name.

Some internal messages show a prefix such as `TA-`. That prefix identifies the type of information inside SDRTrunk. It
is not part of the name stored in the statistics table. For example, an internal identifier shown as `TA-CDP #0134`
is stored and displayed as `CDP #0134`.

## The Checksum Protects the Result

Radio reception is imperfect. A weak signal, interference, or a decoding error can change one or more bits. SDRTrunk
might receive every numbered block but still have incorrect contents.

The checksum lets SDRTrunk answer this question:

> Do the collected pieces mathematically agree with the check value sent by the radio?

If they agree, the completed alias is accepted. If they do not agree, the result is rejected instead of being written
to the tables.

An alias therefore needs all of the following before it can be accepted:

- A usable header.
- Every required block number.
- Compatible sequence numbers.
- A valid checksum.
- A usable radio identity.
- Non-empty alias text.

This is why seeing one or more alias blocks does not necessarily produce a table entry. It is safer to show no alias
than to attach a damaged or incorrect name to a radio.

## The Alias Assembler

The part that holds and joins the pieces is called an assembler. It is simply a small holding area for an incomplete
alias.

For a P25 Phase 1 traffic decoder, it remembers information similar to:

```text
Current header
Current sequence number
Block 1
Block 2
Block 3
...
```

Whenever another block arrives, the assembler checks whether all required block numbers are present. If something is
still missing, it waits.

When the complete set is available, it:

1. Places the blocks in numerical order.
2. Joins their bits together.
3. Removes unused padding from the end.
4. Checks the checksum.
5. Extracts the network, system, and radio IDs.
6. Converts the alias data back into readable text.
7. Produces one completed talker-alias observation.
8. Clears the temporary pieces so it is ready for the next alias.

## Traffic Frequencies and Their Decoders

A trunked P25 system normally has a control channel and several traffic frequencies.

The control channel directs radios to an available traffic frequency for each call. One call might use 854.3375 MHz,
while another uses 854.9625 MHz.

SDRTrunk creates a traffic decoder for a frequency being used. In this context, a decoder is the part listening to and
understanding that frequency. It handles voice-related signaling as well as the audio itself.

Each Phase 1 traffic decoder has its own Motorola alias assembler. Related blocks normally arrive together through
that decoder, so it is the natural place to hold the partial alias.

Ending a call and ending a decoder are separate actions. The call record can be closed when the conversation is over
while the frequency decoder remains available for trailing signaling and later calls. Keeping these responsibilities
separate also lets the assembler retain the pieces it needs until a full alias has been received.

## When a Complete Alias Is Accepted

Once an assembler produces a complete, checksum-valid alias, the result moves through several parts of VCE.

### 1. Update the Live Radio-to-Alias Map

While SDRTrunk is running, it keeps a fast in-memory map similar to:

```text
1880134 -> CDP #0134
1882011 -> CDP #2011
1830224 -> CEMS-MED24 P2
```

This allows later calls from a known radio to be labeled immediately, even when the later call does not carry another
complete alias transmission.

### 2. Announce the Completed Observation

VCE creates a completed-alias notification containing:

- The full radio identity.
- The readable alias.
- The talkgroup and other available call context.
- The time the alias was observed.
- The monitored channel and radio-system context.

This notification is separate from the lifetime of the call record. Talker-alias information can arrive near the end
of a conversation, so saving it must not depend on the call still being active.

### 3. Store the Alias in SQLite

The statistics service receives the notification and records the alias against the correct radio and system.

The stored radio information includes values such as:

- The latest talker alias.
- When that alias was last observed.
- The most recently associated talkgroup.
- The P25 system identity.
- Existing radio activity and call counts.

Repeated receptions of the same alias refresh its last-seen time. They do not need to create a new radio row for every
transmission. If the radio later sends a different alias, the latest accepted value becomes the current displayed
alias.

### 4. Display It in the Application and Website

The Java application and embedded website read the stored information and display it in their tables.

The full path is:

```text
Radio transmission
    -> Alias header and blocks received
    -> Blocks assembled
    -> Checksum accepted
    -> Radio and alias identified
    -> Live alias map updated
    -> Alias observation stored
    -> Tables display the result
```

The tables are the last part of this process. They display information that has already been received, checked, and
stored; they do not create the alias themselves.

## Why Both Memory and Database Storage Are Used

The live map and the SQLite database serve different purposes.

The live map is fast. It helps label calls immediately while the application is running:

> This call is from radio 1880134, which is currently known as CDP #0134.

The database is durable. It supports statistics tables, historical inspection, and information that remains useful
after the alias was first received.

Using both gives VCE fast live labeling and persistent operator-facing records.

## Phase 1 and Phase 2

P25 Phase 1 and Phase 2 carry Motorola talker aliases in different kinds of messages.

Phase 1 uses link-control messages handled by the Phase 1 traffic decoder and its alias assembler.

Phase 2 uses a different message format and has two timeslots on one radio frequency. Its assembler understands the
Phase 2 message structure and keeps the timeslot information associated with the result.

After either type produces a complete alias, both follow the same general VCE path:

```text
Check the completed alias
    -> Update the live radio-to-alias map
    -> Announce the observation
    -> Store it in SQLite
    -> Display it in tables
```

The collection details differ, but storage and display are shared.

## Motorola and L3Harris Talker Aliases

Motorola and L3Harris systems do not carry their aliases in exactly the same form, so they use separate assemblers.

Motorola's completed data includes the radio's full P25 identity. This provides a direct connection between the name
and the radio that sent it.

L3Harris alias handling can depend more on the radio identity already known from the current call. Its messages are
collected and interpreted according to the L3Harris format.

Once either format produces a usable alias and radio association, the result enters the same VCE live-map,
notification, database, and table path.

## Reasons an Alias May Not Appear

An alias can legitimately be absent when:

- The radio system does not transmit talker aliases.
- An agency has disabled the feature.
- A particular radio is not configured with an alias.
- SDRTrunk begins listening after the header or an early block.
- One required block is missed.
- A block cannot be corrected after a reception error.
- Blocks have incompatible sequence numbers.
- The completed checksum fails.
- The radio stops transmitting before the sequence is complete.
- The received text is empty or otherwise unusable.

On an active system, repeated transmissions usually provide additional opportunities to receive a complete sequence.
However, VCE intentionally requires a complete and trustworthy result before placing a name in the tables.

## Summary

Talker-alias handling has three main responsibilities:

1. A format-specific assembler collects the radio messages and produces a checked radio-and-name result.
2. VCE keeps that result available for live calls and stores an independent observation in SQLite.
3. The Java application and website display the stored alias with the corresponding radio and system information.

Keeping collection, storage, and display as separate responsibilities makes the information useful even when the alias
arrives at the end of a call, and it prevents incomplete or damaged transmissions from becoming incorrect table
entries.
