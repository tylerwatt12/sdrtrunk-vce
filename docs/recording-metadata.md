# VCE Metadata in MP3 and WAV Recordings

MP3 and WAV audio recordings include named VCE fields whenever the recorded audio leg already has the corresponding
facts. There is no separate setting to enable these fields. An unavailable or invalid value is omitted, rather
than written as an empty string, `UNKNOWN`, or a guessed value.

The fields supplement existing recording metadata. Filenames, standard ID3 fields, and WAV `LIST/INFO` fields retain
their existing values and formatting, including the home-system radio-ID simplification. A standard field can retain
its familiar display text while a VCE field preserves a structured identity for software to read.

## Storage

Each VCE field is an ID3v2.4 user-defined text frame (`TXXX`) with UTF-8 text. The frame's description is the exact
field name in this document; its text value is the corresponding value. MP3 files store these frames in their ID3
tag. WAV files store the same frames in the existing embedded ID3 block, inside the `id3 ` RIFF chunk. The trailing
space in `id3 ` is part of that four-character chunk identifier.

For example, a frame with description `VCE_P25_WUID` and value `120045` represents the source radio's observed P25
working address. A reader must match the description, not depend on frame order. Readers should tolerate absent
fields and ignore additional field names they do not recognize.

Numeric text uses ASCII digits, a decimal point where specified, and no thousands separators. Hexadecimal values use
uppercase letters, fixed-width zero padding, and no `0x` prefix. Values contain no unit suffix; units are defined
below.

## Source and Destination Identities

The source is the transmitting radio. The destination is the called talkgroup, patch group, or individual radio.
Unqualified `VCE_P25_WUID` and `VCE_P25_SUID` always describe the source. Fields containing `DEST` describe an
individual destination radio, never the source or a talkgroup.

| Field name | Format | Meaning |
| --- | --- | --- |
| `VCE_P25_WUID` | Positive decimal integer, for example `120045` | Source radio's working unit address observed on the receiving system. |
| `VCE_P25_SUID` | `WWWWW.SSS.U`, for example `ABCDE.123.7654321` | Complete source subscriber identity: home WACN as five hexadecimal characters, home SysID as three hexadecimal characters, and subscriber Unit ID in decimal. |
| `VCE_P25_DEST_WUID` | Positive decimal integer | Individual destination radio's observed working unit address. |
| `VCE_P25_DEST_SUID` | `WWWWW.SSS.U` | Complete individual destination subscriber identity, with the same component formats as the source SUID. |

A WUID and a SUID are independent facts. An ordinary local radio address can supply a WUID while its SUID remains
unknown. A complete SUID can be available without an observed WUID. A qualified identity supplies a WUID only when
it carries a valid, explicitly observed working address; a zero placeholder does not create one.

A SUID is written only when the complete tuple was captured for that audio leg, either directly or through an
already confirmed working-address assignment. The writer does not manufacture a home identity by combining a
local address with the receiving WACN/SysID, infer an assignment from equal numbers, or look up an identity later.
The SUID's home system can differ from the receiving system.

## Reception and Call Fields

| Field name | Format / units | Meaning |
| --- | --- | --- |
| `VCE_P25_RX_WACN` | Five hexadecimal characters, for example `BEE00` | Captured receiving WACN. |
| `VCE_P25_RX_SYSID` | Three hexadecimal characters, for example `456` | Captured receiving System ID. |
| `VCE_P25_RX_RFSS` | Decimal integer | Captured receiving RF Subsystem ID. |
| `VCE_P25_RX_SITE` | Decimal integer | Captured receiving site ID. |
| `VCE_P25_NAC` | Three hexadecimal characters, for example `3A7` | Network Access Code present in the recorded leg's original identifiers. |
| `VCE_TALKGROUP` | Nonnegative decimal integer | Observed destination talkgroup address for an ordinary group call, including NXDN talkgroup `0`. |
| `VCE_PATCH_GROUP` | Positive decimal integer | Observed primary patch-group address for a patch call. |
| `VCE_FREQUENCY_HZ` | Positive decimal integer, hertz | Frequency captured for the recorded audio leg. |
| `VCE_PROTOCOL` | Uppercase protocol family, for example `APCO25`, `DMR`, or `NXDN` | Protocol of the recorded leg; P25 phases share `APCO25`. |
| `VCE_P25_PHASE` | `1` or `2` | P25 phase established by the captured decoder type. |
| `VCE_TIMESLOT` | Positive decimal integer | Captured timeslot, when applicable and available. |
| `VCE_ENCRYPTION` | `CLEAR` or `ENCRYPTED` | Captured encryption state of the recorded leg. Unknown state is omitted. |

`VCE_TALKGROUP` and `VCE_PATCH_GROUP` are mutually exclusive. A patch field describes its primary group, not a list
of patch members. Both fields are omitted for an individual radio call. Destination radio fields are omitted for
group and patch calls.

Receiving fields describe the receiver context captured with the audio leg, not the subscriber's home system or
the receiver's current configuration. NAC is included only if that leg's original captured identifiers contain it.
The writer does not substitute a current control-channel NAC, assume that a control and traffic channel use the
same NAC, or derive it from a site record. A P25 Phase 2 recording can therefore have a known receiving site and
no NAC field.

Protocol and phase are separate facts. A captured P25 radio identifier can establish `APCO25` without establishing
the phase; `VCE_P25_PHASE` is then omitted. An unavailable timeslot is also omitted.

## Existing Decode Quality Measurements

The writer uses quality measurements already captured for the physical leg whose audio was recorded. It does not
add measurement work to decoder callbacks. These counters are available only when the voice decoder supplied
quality observations; recordings made without those observations omit the voice-quality fields.

| Field name | Format / units | Meaning |
| --- | --- | --- |
| `VCE_VOICE_DECODED_FRAMES` | Nonnegative decimal integer, frames | Frames reported as decoded. |
| `VCE_VOICE_REPEATED_FRAMES` | Nonnegative decimal integer, frames | Frames reported as repeated. |
| `VCE_VOICE_CONCEALED_FRAMES` | Nonnegative decimal integer, frames | Frames reported as concealed. |
| `VCE_VOICE_MISSING_FRAMES` | Nonnegative decimal integer, frames | Existing estimate of expected frames without a reported outcome. |
| `VCE_VOICE_FEC_ERRORS` | Nonnegative decimal integer, error/correction count | Total FEC error count supplied by the decoder. |
| `VCE_VOICE_FEC_PROTECTED_BITS` | Positive decimal integer, bits | Total protected-bit count supplied for those FEC measurements. |
| `VCE_VOICE_FEC_CORRECTION_PERCENT` | Decimal percentage with exactly six fractional digits, for example `0.200000` | `100 × total FEC errors / total FEC protected bits`. |

Decoded, repeated, and concealed counts sum the outcomes already reported for the recorded physical leg. If a leg
spans linked recording chunks, its captured per-chunk totals are summed. Each nominal voice frame represents
20 milliseconds. Missing frames are an estimate based on expected elapsed frames and observed outcomes, not an
independent measurement of missing RF packets.

For a call received through multiple legs, the fields use only the selected audio leg's totals. They do not sum
losing legs, mix measurements across sites, or use the larger expected-frame baseline used to compare multiple
legs. Thus a field describes the audio's own captured reception history.

The FEC percentage is a ratio of summed counts, not the average of per-frame percentages. It is rounded to six
fractional digits, with halfway cases rounded up. FEC fields are included only when the protected-bit total is
positive. A measured zero error count is valid and gives `0.000000` percent.
This measure describes the decoder's reported FEC corrections; it is not a raw channel BER measurement.

RSSI, SNR, and BER are not currently captured with recorded audio legs, so this format writes no fields for them.
It does not borrow live signal displays, control-channel statistics, later database records, or measurements from
another leg.

## Tone Frequencies

When a completed tone sequence is available for the recorded leg, numbered `VCE_PAGING_TONE_n_FREQUENCY_1_HZ`
fields contain the reported frequency in decimal hertz. New single-tone PCM detections report the nearest allowed AMBE
single-tone frequency, from 156.25–3812.5 Hz in 31.25 Hz steps; an exact halfway case chooses the lower value.
For example, a detected pitch near 566.38 Hz is reported as `562.5`.

`VCE_PAGING_TONE_METHOD=p25_pcm_ambe_tone_v2` identifies these nominal reports. Older files using
`p25_pcm_tone_v1` retain their original measured values. Codec-provided `ambe_tone_v1` metadata retains its existing
single-tone or dual-tone values and symbols. There are no separate measured/matching tags in new reports.
The recording's audio, tone offsets and qualified durations are unchanged by assigning a nominal frequency.

AM/NBFM waveform detections use `VCE_PAGING_TONE_METHOD=analog_pcm_tone_v1`. Their ordinary single tones use the
same nominal frequencies. Waveform DTMF retains its symbol and both standard component frequencies without AMBE
rounding. CTCSS remains a channel receive-squelch setting and is not included in tone metadata.

## Synthetic Group-Call Example

The following exact text values are illustrative and do not represent a captured call. A source with home identity
`ABCDE.123.7654321` is observed using working address `120045` on receiving system `BEE00.456`. Its group call is
recorded from Phase 2 timeslot 1. Each line below represents one `TXXX` description and its text value, in either an
MP3 tag or a WAV embedded ID3 block:

```text
VCE_P25_WUID=120045
VCE_P25_SUID=ABCDE.123.7654321
VCE_P25_RX_WACN=BEE00
VCE_P25_RX_SYSID=456
VCE_P25_RX_RFSS=1
VCE_P25_RX_SITE=7
VCE_P25_NAC=3A7
VCE_TALKGROUP=3101
VCE_FREQUENCY_HZ=851012500
VCE_PROTOCOL=APCO25
VCE_P25_PHASE=2
VCE_TIMESLOT=1
VCE_ENCRYPTION=CLEAR
VCE_VOICE_DECODED_FRAMES=480
VCE_VOICE_REPEATED_FRAMES=8
VCE_VOICE_CONCEALED_FRAMES=4
VCE_VOICE_MISSING_FRAMES=8
VCE_VOICE_FEC_ERRORS=144
VCE_VOICE_FEC_PROTECTED_BITS=72000
VCE_VOICE_FEC_CORRECTION_PERCENT=0.200000
```

No destination-radio fields or patch field appear in this group-call example. If the source had only the observed
local address, the WUID would remain and the SUID field would be absent. If no quality observations were captured,
all seven voice-quality fields would be absent.

## Synthetic Individual-Call Example

For an individual call with the same source, an explicitly observed destination working address `220078` and
destination home identity `BCDEF.321.987654` would add:

```text
VCE_P25_DEST_WUID=220078
VCE_P25_DEST_SUID=BCDEF.321.987654
```

That individual call would omit `VCE_TALKGROUP` and `VCE_PATCH_GROUP`. The source fields would still identify the
transmitting radio. A destination with a complete home identity but no observed working address would have only
`VCE_P25_DEST_SUID`.
