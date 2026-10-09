# Alias CSV import and export

Use CSV files to back up an Alias List, transfer its aliases to another VCE installation, or import talkgroup names
from RadioReference. Open **Manage > Aliases**, select a list, and choose **Import aliases…** or **Export aliases…**.

This guide describes current Nightly. Administrator access is required. Exporting **Current filtered results** also
requires CSV export access.

## Choose the right file

| File | What it transfers |
| --- | --- |
| **VCE alias export** | Names, descriptions, identifier matching, colors/icons, recording choices, Scan List memberships, streaming destinations, and Stream As overrides. |
| **RadioReference talkgroup CSV** | Talkgroup IDs, names, descriptions, and categories. New aliases use your chosen local defaults; existing aliases keep their local settings. |
| **Download table report** | A reporting spreadsheet for the Alias table. Use Export aliases when you need a file that can be imported again. |

Alias files do not include channels, activity, counters, credentials, or Call Handling Defaults. Select the destination
Alias List explicitly when importing. A source list's name is shown for review; importing does not create, rename, or
switch lists.

## Export aliases for backup or transfer

1. Select the source Alias List and choose **Export aliases…**.
2. Choose **All aliases in this Alias List** or **Current filtered results**.
3. Use the download button to save the CSV.

A filtered export includes every result matching the current search and filters, including results beyond the
visible table page.

For transfer to another receiver, first create a compatible destination Alias List and any needed Scan Lists or
streaming destinations. The CSV refers to those assignments by exact name. It cannot create them, and missing or
ambiguous names block import. Rename duplicate destination names before transferring.

## Import and review changes

1. Select the destination Alias List and choose **Import aliases…**.
2. Drop or choose a UTF-8 CSV file. VCE recognizes its own exports and RadioReference talkgroup CSV headers.
   If the format is not recognized, check the file and choose its type explicitly.
3. Choose **Add new aliases and update matches** for a normal import.
4. For a RadioReference file, review the new-alias defaults and optionally override them for this import.
5. Select **Review import**. Check Added, Updated, Unchanged, Removed, and Errors; expand rows to inspect the values
   or field changes.
6. Resolve any errors, review again, and choose **Import changes**.

Files can be up to **128 MiB**. Review has filters and pages of 100 rows. A change to the file, options, or destination
configuration requires a fresh review. A failed save does not partially import the list.

### Choose add/update or replace

**Add new aliases and update matches** keeps aliases absent from the file. A match uses the complete identifier
definition, including its protocol and exact ID or range. Alias names do not decide whether entries match.

**Replace this list's aliases** also deletes aliases absent from the file. Check the Removed rows carefully; when
there are deletions, the dialog requires confirmation naming the destination list. Replace keeps the list and its
Call Handling Defaults. Empty imports are rejected.

Matching aliases retain their existing IDs and activity. New aliases begin without observed activity. Reimporting
an unchanged file does not add more duplicate aliases.

### Know which settings will change

A **VCE export** is a complete per-alias configuration. Updating a match applies the file's supplied settings,
including replacing Scan List and streaming memberships. Empty optional values clear those fields. New aliases also
use the file's choices.

A **RadioReference CSV** updates only an existing alias's name, description, and group. Recording, Scan Lists,
streaming, appearance, and Stream As stay intact. New entries use **Call Handling Defaults > New Aliases**, unless
you choose an import-specific override.

New fully encrypted RadioReference talkgroups have recording, Scan Lists, and streaming disabled. Partially
encrypted talkgroups use the chosen defaults. Review shows the assignments before saving.

## If an import is blocked

- Check that the destination list's protocol family matches the file.
- Create the named Scan Lists and streaming destinations first, or correct their names in the file.
- Rename ambiguous destinations instead of guessing which one should receive calls.
- Check Errors in review for invalid identifiers, icons, or other configuration.
- Download a fresh VCE export if a spreadsheet changed its headers or encoding.

Repeated exact identifiers are allowed and preserved. Overlapping ranges still appear in the Alias Editor's overlap
diagnostics. The technical details below explain how repeated matches and file encoding are handled.

<details>
<summary>CSV format and matching details</summary>

Repeated exact matchers are paired with identical existing configurations first. Remaining occurrences are paired
in stable existing-ID order, and excess occurrences are added. Names and row IDs are not matching keys. Range
overlaps are distinct from repeated exact identities.

Syntax errors identify the first invalid CSV record; configuration errors appear in review. Invalid or incompatible
identifiers and unresolved assignments block the entire import. Changes save in one transaction, and completion
reports added, updated, deleted, and unchanged counts. There is no 10,000-alias row limit; the file limit is 128 MiB.

## VCE configuration CSV, version 3

Choose **Export aliases…** for the selected list, then choose either every alias in that list or every result matching
the current Alias table search and filters. A filtered export includes the complete server-side result, not just the
visible page. Every selected alias is written in ascending durable database-ID order, independent of the table's
current presentation sort, including aliases that share an exact matcher. That stable order preserves the same
last/highest-ID exact-alias match after an empty-list round trip. The importer requires this exact header, including
order and capitalization:

```csv
format_version,alias_list,name,description,group,color,icon,matcher_type,protocol,value,minimum,maximum,text,tones,home_wacn,home_system_id,subscriber_id,record_enabled,scan_lists,streaming_destinations,stream_as_talkgroup
```

All columns are required. `format_version` is `3`. Version 1 and version 2 VCE exports remain importable using their
original exact headers, but new exports always use version 3. A matching existing alias receives all the supplied
configuration, including membership replacements. Empty optional values clear those values. Unused matcher fields
must be empty.

| Columns | Values |
| --- | --- |
| `alias_list` | Name of the source alias list. Every row must contain the same non-empty name. It is review metadata; import always targets the list explicitly selected in the Alias Editor and never creates, renames, or switches lists. |
| `name`, `description`, `group` | Name is required. Existing database-valid text is preserved, subject to the file limits above. |
| `color` | Signed decimal integer, as written by the exporter. |
| `icon` | Exact configured icon name, or empty for none. |
| `matcher_type` | `TALKGROUP`, `TALKGROUP_RANGE`, `P25_SUBSCRIBER_IDENTITY`, `RADIO_ID`, `RADIO_ID_RANGE`, `STATUS`, `UNIT_STATUS`, `DCS`, `ESN`, or `TONES`. |
| `protocol` | For protocol matchers: `APCO25`, `APCO25_PHASE2`, `DMR`, `NXDN`, `AM`, `NBFM`, `FLEETSYNC`, or `MDC1200`, subject to destination-list compatibility. Otherwise empty. |
| `value` | Decimal exact talkgroup/radio ID, user status, or unit status. |
| `minimum`, `maximum` | Decimal inclusive range boundaries. |
| `text` | DCS code enum name or ESN text, according to matcher type. |
| `tones` | Ordered `TONE:duration` entries separated by semicolons, using exported tone names and integer durations. |
| `home_wacn`, `home_system_id`, `subscriber_id` | For `P25_SUBSCRIBER_IDENTITY`, exactly five uppercase hexadecimal WACN digits, exactly three uppercase hexadecimal System ID digits, and a decimal Subscriber ID. Otherwise empty. |
| `record_enabled` | Exactly `true` or `false`. |
| `scan_lists` | JSON array of exact configured scan-list names. `[]` or an empty cell means none. |
| `streaming_destinations` | JSON array of exact configured streaming-destination names. `[]` or an empty cell means none. |
| `stream_as_talkgroup` | Decimal integer 1–16777215, or empty for no override. |

For example, a scan-list cell can contain `["Dispatch","Fire"]`. A CSV library or spreadsheet handles the outer CSV
quoting. Names containing commas, semicolons, or quotation marks are preserved by the JSON array. Names must match
exactly and resolve to exactly one existing object. Import does not create scan lists or streaming configurations.
An export also refuses ambiguous destination names so it cannot produce an apparently transferable file that
would resolve differently on import. Rename ambiguous destinations before transferring.

VCE exports protect text cells beginning with a spreadsheet formula character or an apostrophe by adding one
apostrophe. Imports remove that prefix from text fields. Preserve this encoding when editing a CSV:
double an initial literal apostrophe. Quoted commas, line breaks, and Unicode text are supported.

For new aliases, the VCE row is authoritative: its appearance, recording choice, scan-list memberships, streaming
destinations, stream-as value, and matcher are used directly. The modal does not ask for redundant assignment
choices. Database IDs and derived `streamable`/overlap state are not exported. Within the 128 MiB file limit, a
round trip preserves the exported per-alias configuration when the destination list and named assignments are
compatible. Activity counters and timestamps are
omitted: matching aliases keep their existing activity, while newly imported aliases begin with no observed activity.

Large exports are read from the database and encoded in bounded batches. The server validates the complete CSV in a
temporary spool before sending download headers, then the browser performs a native download without buffering the
file in page JavaScript. A pre-download failure is shown in the export dialog. Once transfer begins, the declared file
length lets the browser reject an interrupted response instead of presenting a partial CSV as complete.

The ordinary Alias table CSV remains available for reporting, but it is not an import file.

## RadioReference talkgroup CSV

The accepted header is exactly:

```csv
Decimal,Hex,Alpha Tag,Mode,Description,Tag,Category
```

Import into a compatible P25, DMR, or NXDN list. `Decimal` supplies the talkgroup ID, `Alpha Tag` the alias name,
`Description` the description, and `Category` the group. `Hex` and service `Tag` are not imported.
The list supplies the protocol; the file cannot establish which system the talkgroup belongs to. Select the correct
destination list. Supported mode labels are `A`, `D`, `T`, `M`, `AE`, `Ae`, `DE`, `De`, `TE`, and `Te`.

Existing aliases update only name, description, and group. Their recording, scan lists, stream destinations,
appearance, and stream-as override remain intact. New aliases use **Call Handling Defaults > New Aliases** in the selected destination list.
The modal shows those effective defaults before previewing. Its optional override section can replace recording,
scan-list, or streaming defaults for this one import; leaving an override unset keeps that field's list default.
Enabling a scan-list or streaming override with no choices explicitly assigns none. Overrides use exact configured
names and do not create configuration objects.

New fully encrypted talkgroups (uppercase `E` suffix) have recording, scan-list memberships, and streaming disabled.
Partially encrypted modes (lowercase `e`) use the chosen defaults. Existing aliases retain their local settings
regardless of mode. Review shows the resulting assignments before saving.

</details>

[Browse the documentation index](README.md).
