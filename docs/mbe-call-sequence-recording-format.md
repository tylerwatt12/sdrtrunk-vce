# MBE Call Sequence Recording Format

Status: implemented format, version 2

This document defines the JSON `.mbe` call-sequence format written by `sdrtrunk-vce`. The format stores ordered
vocoder frames and signaling metadata captured from radio transmissions. It does not store encryption keys or define
decryption behavior.

The format supports:

- DMR AMBE+2
- NXDN AMBE+ half-rate
- APCO P25 Phase 1 IMBE
- APCO P25 Phase 2 AMBE+2

An `.mbe` file does **not** contain an encryption key. `encryption_key_id` is only the numeric key selector transmitted
over the air.

## Format Principles

An `.mbe` file contains one JSON object representing one call sequence. Its `frames` array is chronological and
contains the vocoder payloads in playback order.

Encryption metadata is stateful. It is written as a context marker on the first voice frame associated with that
signaling context. The metadata is not repeated on every frame. A later marker records a newly observed context.

JSON object-property order is for readability only and has no semantic meaning. Numeric protocol values are JSON
numbers and are normally displayed in decimal, even when radio documentation describes them in hexadecimal.

## Versioning

The current format version is `2`.

Optional additive fields do not change the version number. A future incompatible structural change must use a new
version. Consumers should ignore unknown optional properties and should not reject version 2 files merely because an
optional field is absent.

Only version 2 is defined by this document.

## Top-Level Call Object

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `protocol` | string | Yes | Determines the radio protocol and vocoder-frame representation. |
| `version` | integer | Yes | Format version. This document defines version `2`. |
| `codec` | string | No | Optional codec detail, currently used by NXDN. |
| `call_type` | string | No | Descriptive call type obtained from signaling. |
| `from` | string | No | Source radio or other originating identifier. |
| `to` | string | No | Destination talkgroup, radio, or interconnect identifier. |
| `encrypted` | boolean | Yes | Indicates that the call sequence contains encrypted voice. |
| `system` | string | No | User-configured system name. |
| `site` | string | No | User-configured site name. |
| `frames` | array | Yes | Ordered voice-frame objects. |

Supported `protocol` values are:

| Value | Voice format |
| --- | --- |
| `DMR` | 72-bit AMBE+2 frame |
| `NXDN` | AMBE+ frame; current recording and conversion support is half-rate |
| `APCO25-PHASE1` | 144-bit IMBE frame |
| `APCO25-PHASE2` | 72-bit AMBE+2 frame |

`from` and `to` are strings because identifier formatting is protocol-dependent. Consumers should not require them to
be JSON numbers.

`call_type` is descriptive rather than a cross-protocol enumeration. Common DMR and P25 values include `GROUP`,
`INDIVIDUAL`, and `TELEPHONE INTERCONNECT`. NXDN uses labels from its transmitted call type, such as
`GROUP CONFERENCE`, `GROUP BROADCAST`, `INDIVIDUAL`, and `INTERCONNECT`.

For NXDN, `codec` is one of:

- `HALF_RATE`
- `FULL_RATE`

Older NXDN version 2 recordings may omit `codec`; consumers should interpret an omitted NXDN codec as the historical
half-rate default. `sdrtrunk-vce` does not currently convert NXDN full-rate `.mbe` recordings to audio.

### Meaning of `encrypted`

`encrypted: true` describes the call as a whole. It does not guarantee that a usable context marker is present. A
receiver can enter a call late or fail to decode the signaling that carried the algorithm, key ID, or IV.

`encrypted: true` can therefore appear without complete encryption metadata when the receiver enters a call late or
does not decode the applicable signaling. Missing optional metadata does not make the file structurally invalid.

## Voice-Frame Object

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `time` | integer | Yes | Frame timestamp in Unix epoch milliseconds. |
| `hex` | string | Yes | Vocoder frame bytes as uppercase hexadecimal with no `0x` prefix. |
| `tag` | string | No | Protocol-specific alignment or structure tag. Currently used by NXDN. |
| `encryption_algorithm` | integer | No | Raw transmitted algorithm or cipher-type identifier. |
| `encryption_fid` | integer | No | Raw DMR Feature Identifier/vendor value. |
| `encryption_key_id` | integer | No | Raw transmitted key selector; never the encryption key. |
| `encryption_mi` | string | No | Message indicator or IV as fixed-width uppercase hexadecimal. |

`hex` contains the transmitted vocoder/ECC frame representation expected by the corresponding decoder. The current
frame lengths are:

| Protocol | Bytes | Hex characters |
| --- | ---: | ---: |
| DMR | 9 | 18 |
| NXDN half-rate | 9 | 18 |
| APCO P25 Phase 1 | 18 | 36 |
| APCO P25 Phase 2 | 9 | 18 |

Frames are nominally 20 milliseconds apart. Consumers must preserve array order and should not reorder frames solely
from timestamps.

## Encryption Context Markers

A complete context marker contains both `encryption_algorithm` and `encryption_key_id`. `encryption_mi` is included
when an IV or message indicator was decoded. NXDN Scrambler does not use an IV. `encryption_fid` is optional and
currently applies only to DMR.

The marker records the signaling context associated with its frame. Subsequent frames omit duplicate metadata until
a newly observed context is recorded.

The encryption fields describe transmitted synchronization and key-selection metadata only. They do not contain key
material and do not provide a method for deriving a key.

## DMR

DMR recordings use:

```json
"protocol": "DMR"
```

DMR encryption metadata can come from a valid Privacy Indicator header or from DMRA late-entry signaling embedded in
the AMBE+2 frames.

The fields have these meanings:

- `encryption_algorithm` is the transmitted ALG ID.
- `encryption_fid` is the 8-bit Feature Identifier/vendor value from the Privacy Indicator header.
- `encryption_key_id` is the 8-bit transmitted key selector.
- `encryption_mi` is the 32-bit MI/IV, formatted as exactly eight hexadecimal characters.

The DMR recorder writes Privacy Indicator metadata on the next serialized voice frame. A complete DMRA late-entry
context is promoted at the next DMR voice-superframe start and written on that superframe's first serialized voice
frame.

Late-entry signaling does not carry a new FID. If a Privacy Indicator header previously established an FID, the
recorder preserves it on a later late-entry marker. Otherwise `encryption_fid` is omitted.

### DMR algorithm values

| JSON value | Hex | Meaning |
| ---: | ---: | --- |
| 1 | `0x01` | Hytera Basic Privacy |
| 2 | `0x02` | Hytera Enhanced Privacy/RC4 |
| 33 | `0x21` | DMRA RC4/Enhanced Privacy |
| 36 | `0x24` | DMRA AES-128 |
| 37 | `0x25` | DMRA AES-256 |
| 38 | `0x26` | Hytera Enhanced Privacy variant |

Consumers should preserve the raw numeric identifier even when they do not recognize the algorithm.

### DMR example

The values below are illustrative and do not represent a captured call:

```json
{
  "protocol": "DMR",
  "version": 2,
  "call_type": "GROUP",
  "from": "794",
  "to": "700",
  "encrypted": true,
  "system": "Example DMR System",
  "site": "Example Site",
  "frames": [
    {
      "encryption_algorithm": 36,
      "encryption_fid": 0,
      "encryption_key_id": 2,
      "encryption_mi": "AABBCCDD",
      "time": 1782967077824,
      "hex": "67282ED2DE24B6ADF5"
    },
    {
      "time": 1782967077844,
      "hex": "E63B68A8256257D9FF"
    },
    {
      "time": 1782967077864,
      "hex": "128750AF39DC4216E8"
    }
  ]
}
```

The first frame records the AES-128 marker. The following two frames omit the duplicate metadata.

## NXDN

NXDN recordings use:

```json
"protocol": "NXDN"
```

The fields have these meanings:

- `encryption_algorithm` is the transmitted NXDN cipher-type value.
- `encryption_key_id` is the transmitted key ID.
- `encryption_mi` is the transmitted IV in uppercase hexadecimal.
- `tag`, when present, identifies the SACCH structure associated with the first voice frame from an RF audio frame.

NXDN does not use `encryption_fid`.

### NXDN cipher values

| JSON value | Transmitted cipher type |
| ---: | --- |
| 0 | Unencrypted |
| 1 | Scrambler |
| 2 | DES |
| 3 | AES |

A normal NXDN voice-call IV is 64 bits and is written as exactly 16 hexadecimal characters. A Type-D IV is 23 bits
and is written, zero-padded, as exactly six hexadecimal characters.

For a complete 64-bit voice-call IV, the recorder places the marker on the first voice frame in the RF frame after the
RF frame that completed the IV signaling. For Type-D, it assembles the 11-bit and 12-bit fragments only when their
directions agree, then places the completed 23-bit IV marker at the next Type-D superframe boundary.

These activation calculations describe where the recorder places the metadata marker. Playback and other processing
behavior are outside the scope of this specification.

### NXDN SACCH tags

The optional `tag` values currently used for NXDN are:

- `SACCH 1`
- `SACCH 2`
- `SACCH 3`
- `SACCH 4`

They preserve the observed position in the repeating NXDN SACCH sequence. A tag is attached only to the first
serialized vocoder frame associated with that tagged RF audio frame. Other voice frames normally omit it.
Interpretation of these tags during playback or other processing is outside the scope of this specification.

### NXDN example

The values below are illustrative and do not represent a captured call:

```json
{
  "protocol": "NXDN",
  "version": 2,
  "codec": "HALF_RATE",
  "call_type": "GROUP CONFERENCE",
  "from": "1234",
  "to": "100",
  "encrypted": true,
  "system": "Example NXDN System",
  "site": "Example Site",
  "frames": [
    {
      "tag": "SACCH 1",
      "encryption_algorithm": 3,
      "encryption_key_id": 27,
      "encryption_mi": "0000000000000123",
      "time": 1782967077824,
      "hex": "001122334455667788"
    },
    {
      "time": 1782967077844,
      "hex": "112233445566778899"
    },
    {
      "time": 1782967077864,
      "hex": "2233445566778899AA"
    }
  ]
}
```

The first frame records the AES marker and 64-bit IV. The following frames omit the duplicate metadata.

## APCO P25

P25 Phase 1 recordings use:

```json
"protocol": "APCO25-PHASE1"
```

P25 Phase 2 recordings use:

```json
"protocol": "APCO25-PHASE2"
```

Both phases use the same encryption metadata:

- `encryption_algorithm` is the transmitted 8-bit P25 ALG ID.
- `encryption_key_id` is the transmitted 16-bit key identifier.
- `encryption_mi` is the 72-bit message indicator, formatted as exactly 18 hexadecimal characters.

P25 does not use `encryption_fid`.

For Phase 1, encryption synchronization can come from the Header Data Unit or the LDU2 encryption-sync parameters.
For Phase 2, it can come from Push-To-Talk signaling or an Encryption Synchronization Sequence. The recorder places
each context marker on the first serialized vocoder frame to which the synchronization data applies.

### Common P25 algorithm values

| JSON value | Hex | Meaning |
| ---: | ---: | --- |
| 128 | `0x80` | Unencrypted |
| 129 | `0x81` | DES-OFB |
| 130 | `0x82` | Two-key Triple DES |
| 131 | `0x83` | Three-key Triple DES |
| 132 | `0x84` | AES-256 |
| 133 | `0x85` | AES-128 |
| 137 | `0x89` | AES-128-OFB |
| 159 | `0x9F` | Motorola DES-XL |
| 170 | `0xAA` | Motorola ADP, 40-bit RC4 |

Other standardized, legacy, manufacturer-specific, and unknown ALG IDs can occur. Consumers should retain the raw
numeric value even when the algorithm is unsupported.

### P25 Phase 1 example

The values below are illustrative and do not represent a captured call:

```json
{
  "protocol": "APCO25-PHASE1",
  "version": 2,
  "call_type": "GROUP",
  "from": "1234567",
  "to": "1001",
  "encrypted": true,
  "system": "Example P25 System",
  "site": "Example Site",
  "frames": [
    {
      "encryption_algorithm": 132,
      "encryption_key_id": 2,
      "encryption_mi": "001122334455667788",
      "time": 1782967077824,
      "hex": "00112233445566778899AABBCCDDEEFF0011"
    },
    {
      "time": 1782967077844,
      "hex": "112233445566778899AABBCCDDEEFF001122"
    },
    {
      "time": 1782967077864,
      "hex": "2233445566778899AABBCCDDEEFF00112233"
    }
  ]
}
```

### P25 Phase 2 example

The values below are illustrative and do not represent a captured call:

```json
{
  "protocol": "APCO25-PHASE2",
  "version": 2,
  "call_type": "GROUP",
  "from": "1234567",
  "to": "1001",
  "encrypted": true,
  "system": "Example P25 System",
  "site": "Example Site",
  "frames": [
    {
      "encryption_algorithm": 132,
      "encryption_key_id": 2,
      "encryption_mi": "001122334455667788",
      "time": 1782967077824,
      "hex": "001122334455667788"
    },
    {
      "time": 1782967077844,
      "hex": "112233445566778899"
    },
    {
      "time": 1782967077864,
      "hex": "2233445566778899AA"
    }
  ]
}
```

## Clear-Voice Example

Clear calls use the same envelope and frame representation but do not contain encryption context fields:

```json
{
  "protocol": "DMR",
  "version": 2,
  "call_type": "GROUP",
  "from": "794",
  "to": "700",
  "encrypted": false,
  "frames": [
    {
      "time": 1782967077824,
      "hex": "67282ED2DE24B6ADF5"
    },
    {
      "time": 1782967077844,
      "hex": "E63B68A8256257D9FF"
    }
  ]
}
```

## Backward Compatibility

Consumers should expect older version 2 recordings with fewer optional fields:

- Older DMR files may have `encrypted: true` but no algorithm, key ID, FID, or MI.
- DMR files written before FID support may contain algorithm, key ID, and MI but no `encryption_fid`.
- Older NXDN files may have only `encrypted: true`, with no cipher, key ID, IV, codec, or SACCH tags.
- P25 recordings normally omit `codec`, `tag`, and `encryption_fid`.

The absence of optional metadata is not a parse error. It can, however, limit downstream interpretation or processing
of the recorded frames.

## Filename Convention

The filename is informational and is not part of the JSON schema. The current writer uses this general form:

```text
yyyyMMdd_HHmmss_<frequency-hz>_<call-number>[_<channel-tag>][_<to>][_<from>][_encrypted].mbe
```

DMR keeps independent call state per timeslot. P25 Phase 2 normally adds `TS1` or `TS2` as the channel tag.
Consumers should use the JSON content, not the filename, as the authoritative source for protocol and call metadata.

## Consumer Validation Checklist

A robust consumer should verify the following:

- The root value is a JSON object.
- `version` is supported.
- `protocol` is recognized or safely reported as unsupported.
- `frames` is an array and is processed in its stored order.
- Every frame has an integer `time` and an even-length hexadecimal `hex` value.
- A complete context marker has both `encryption_algorithm` and `encryption_key_id`.
- `encryption_mi` has the protocol-appropriate width when present.
- Missing optional encryption metadata does not make the JSON structure invalid.
- A context marker remains associated with the frame that carries it.
- Unknown algorithms, FIDs, call types, tags, and optional fields are preserved or ignored safely.

## Security and Privacy Notes

An `.mbe` recording can contain radio identifiers, talkgroup identifiers, user-assigned system and site names,
timestamps, encrypted or clear vocoder data, and transmitted encryption metadata. Treat recordings according to the
privacy and retention requirements that apply to the monitored system.

The file does not contain encryption keys. An ALG ID, FID, key ID, MI, or IV is protocol signaling metadata and is not
a substitute for key material.

## Implementation References

The corresponding implementation is maintained in:

- [`MBECallSequence.java`](../src/main/java/io/github/dsheirer/audio/codec/mbe/MBECallSequence.java)
- [`VoiceFrame.java`](../src/main/java/io/github/dsheirer/audio/codec/mbe/VoiceFrame.java)
- [`DMRCallSequenceRecorder.java`](../src/main/java/io/github/dsheirer/module/decode/dmr/audio/DMRCallSequenceRecorder.java)
- [`NXDNCallSequenceRecorder.java`](../src/main/java/io/github/dsheirer/module/decode/nxdn/audio/NXDNCallSequenceRecorder.java)
- [`P25P1CallSequenceRecorder.java`](../src/main/java/io/github/dsheirer/module/decode/p25/audio/P25P1CallSequenceRecorder.java)
- [`P25P2CallSequenceRecorder.java`](../src/main/java/io/github/dsheirer/module/decode/p25/audio/P25P2CallSequenceRecorder.java)

When the implementation and this document change together, the code and its tests are the final authority for the
serialized output.
