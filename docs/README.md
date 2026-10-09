# VCE documentation

Start with the [project README](../README.md) for features, screenshots, downloads, and first-launch instructions.
These guides help you set up VCE, choose what to hear, and understand what your receiver records.

The current guides describe **Nightly**. Numbered Alpha releases can have different features and setup screens;
check the notes for the version you install.

## Setup and everyday use

| I want to… | Start here |
| --- | --- |
| Choose Alpha or Nightly and understand updates | [Choosing a release](release-channels.md) |
| Install VCE, import a playlist, or move an existing setup | [Setup, importing, and storage](portable-startup-and-storage.md) |
| Check what an upgrade or database import preserves | [Upgrades and database imports](database-migration.md) |
| Listen in the browser and create listening groups | [Browser listening and Scan Lists](browser-listening-and-scan-lists.md) |
| Transfer aliases or import a RadioReference CSV | [Alias CSV import and export](alias-import-export.md) |
| Understand names transmitted by radios | [Talker aliases](talker-alias-implementation.md) |
| Explore P25 systems, talkgroups, and radios in 3D | [P25 Visualizer](network-visualizer.md) |

For a problem with receiving or playback, include your VCE version, what happened, and the steps that led to it in a
[bug report](https://github.com/tylerwatt12/sdrtrunk-vce/issues). Screenshots and relevant log excerpts can help; remove
credentials and private information before posting them.

## Technical references

You do not need these references to start listening. They explain integration, saved data, and implementation details
for advanced troubleshooting or development.

| Reference | What it covers |
| --- | --- |
| [Web API v1](api-v1.md) | Read resources, authentication, live data, audio, and administration. |
| [Activity and identity storage](dmr-nxdn-site-tracking-storage.md) | How channels, radio systems, sites, calls, and observed relationships are kept distinct. |
| [Alias discovery and defaults](alias-discovery-storage.md) | Observed talkgroups, Alias List defaults, and new-alias behavior. |
| [ISSI foreign-system bands](issi-foreign-system-band-storage.md) | Frequency-band information advertised for another P25 system. |
| [MBE recording format](mbe-call-sequence-recording-format.md) | The diagnostic voice-frame file format and conversion limits. |
| [Activity database guidelines](sqlite-activity-database-guidelines.md) | Storage bounds, indexed queries, retention, and writer requirements. |
| [P25 call-start delay study](sdrtrunk-latency-findings.md) | Historical measurements and limits of a July 2026 receiver investigation. |

The [upgrade guide](database-migration.md) also includes the technical migration contract beneath the operator
instructions.

## Historical release notes

These notes describe their named release, including the setup screens and upgrade rules available at that time.
They are not instructions for current Nightly. Use the [GitHub releases page](https://github.com/tylerwatt12/sdrtrunk-vce/releases)
for all published releases and their downloads.

- [Alpha 8](whats-new-0.6.2-alpha-8.md)
- [Alpha 9](whats-new-0.6.2-alpha-9.md)
- [Alpha 10](whats-new-0.6.2-alpha-10.md)
- [Nightly — September 7, 2026](whats-new-nightly-2026-09-07.md)
