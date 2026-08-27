# Release Channels

sdrtrunk-vce has two active release channels with intentionally different feature sets.

## Numbered Alpha

[Numbered Alpha releases](https://github.com/tylerwatt12/sdrtrunk-vce/releases/latest) are the more conservative line.
They advance through reviewed fixes and release preparation, so a numbered Alpha may omit newer website, desktop, or
receiver features that already exist in Nightly.

## Nightly

The [rolling Nightly release](https://github.com/tylerwatt12/sdrtrunk-vce/releases/tag/nightly) is built from current
`main` after its automated checks pass. It contains the newest completed features and fixes, so its behavior can change
more often than the numbered Alpha line.

## Updates Stay On Their Channel

New Alpha packages check only the Alpha update feed. New Nightly packages check only the Nightly feed. The updater can
open the matching download page, but it never installs a build or switches channels automatically.

Alpha 10 and Nightlies published before the channel split both used the old `main` update identity at build 5. That
legacy feed is intentionally frozen because those packages cannot be distinguished safely. Choose and install one new
Alpha or Nightly package manually to enter its separate update channel.

## Database Compatibility

Alpha and Nightly share one forward-only database-format history; channel names do not select different migration
rules. This lets a later build recognize supported earlier formats without creating separate Alpha and Nightly schema
tracks.

A channel switch is still a software upgrade or downgrade. Read the exact target build's release notes before using
existing data. In particular, an Alpha whose database format is older than the Nightly you used must refuse that newer
database. Keep separate installation and data folders when comparing channels, back up the complete source data
folder, and never open newer data with an older build.
