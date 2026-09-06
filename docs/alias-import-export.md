# Alias list CSV import and export

In the web Alias Editor, select a list and open **Import / Export**. Administrator access is required.
The modal transfers alias configuration. It does not import activity, counters, credentials, channels, or
Alias List Defaults. The existing table **Export CSV** action remains a reporting export; use the modal's
**Export list configuration CSV** action for files that can be imported again.

## Import modes and review

**Update/Add** is the default. It matches the complete matcher identity in the selected list, updates matching
aliases, adds new aliases, and keeps aliases absent from the file. Names and database row IDs are not identity keys.
**Replace** performs the same matching, then deletes aliases absent from the file. Matching aliases retain their
existing IDs. Neither mode deletes the list or changes its defaults. Empty imports are rejected.

Select a UTF-8 CSV, choose its exact format and import mode, and press **Build preview**. Files are limited to 8 MiB and
10,000 aliases. Review shows added, updated, unchanged, deleted, and error counts. Expand a row to see configuration
values or current/proposed field differences. Results are paged in groups of 100. Replace requires checking the
confirmation naming the destination list.

Duplicate matchers in a file, multiple existing aliases with the same matcher, incompatible or invalid matchers,
and unresolved assignments prevent applying the entire import. Fix the file and preview again. Syntax errors
identify the first invalid CSV record; configuration errors appear in review. Range overlaps are distinct from
duplicate identities and remain subject to the Alias Editor's existing overlap diagnostics.

Changes are saved in one transaction. A changed file, changed options, or changed configuration requires a new
preview. A failed save does not partially import the list. Completion reports added, updated, deleted, and
unchanged counts. Reimporting an unchanged file does not create duplicates.

## VCE configuration CSV, version 2

Export the entire selected list using the Export tab. The importer requires this exact header, including order
and capitalization:

```csv
format_version,alias_list,name,description,group,color,icon,matcher_type,protocol,value,minimum,maximum,text,tones,record_enabled,scan_lists,streaming_destinations,stream_as_talkgroup
```

All columns are required. `format_version` is `2`. Version 1 VCE exports remain importable using their original exact
header, but new exports always use version 2. A matching existing alias receives all the supplied configuration,
including membership replacements. Empty optional values clear those values. Unused matcher fields must be empty.

| Columns | Values |
| --- | --- |
| `alias_list` | Name of the source alias list. Every row must contain the same non-empty name. It is review metadata; import always targets the list explicitly selected in the Alias Editor and never creates, renames, or switches lists. |
| `name`, `description`, `group` | Name is required, up to 256 characters; description up to 4,096; group up to 256. |
| `color` | Signed decimal integer, as written by the exporter. |
| `icon` | Exact configured icon name, or empty for none. |
| `matcher_type` | `TALKGROUP`, `TALKGROUP_RANGE`, `RADIO_ID`, `RADIO_ID_RANGE`, `STATUS`, `UNIT_STATUS`, `DCS`, `ESN`, or `TONES`. |
| `protocol` | For protocol matchers: `APCO25`, `APCO25_PHASE2`, `DMR`, `NXDN`, `AM`, `NBFM`, `FLEETSYNC`, or `MDC1200`, subject to destination-list compatibility. Otherwise empty. |
| `value` | Decimal exact talkgroup/radio ID, user status, or unit status. |
| `minimum`, `maximum` | Decimal inclusive range boundaries. |
| `text` | DCS code enum name or ESN text, according to matcher type. |
| `tones` | Ordered `TONE:duration` entries separated by semicolons, using exported tone names and integer durations. |
| `record_enabled` | Exactly `true` or `false`. |
| `scan_lists` | JSON array of exact configured scan-list names. `[]` or an empty cell means none. |
| `streaming_destinations` | JSON array of exact configured streaming-destination names. `[]` or an empty cell means none. |
| `stream_as_talkgroup` | Decimal integer 1–65535, or empty for no override. |

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
choices. This makes an export/import round trip preserve all current per-alias configuration. Database IDs and
derived `streamable`/overlap state are intentionally not exported.

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
appearance, and stream-as override remain intact. New aliases use the selected destination list's Alias List Defaults.
The modal shows those effective defaults before previewing. Its optional override section can replace recording,
scan-list, or streaming defaults for this one import; leaving an override unset keeps that field's list default.
Enabling a scan-list or streaming override with no choices explicitly assigns none. Overrides use exact configured
names and do not create configuration objects.

New fully encrypted talkgroups (uppercase `E` suffix) have recording, scan-list memberships, and streaming disabled.
Partially encrypted modes (lowercase `e`) use the chosen defaults. Existing aliases retain their local settings
regardless of mode. Review shows the resulting assignments before saving.
