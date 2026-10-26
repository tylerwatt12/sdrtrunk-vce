# Setup, updates, and your saved data

This guide describes the current Nightly build. For a numbered Alpha, use its bundled documentation and
version-matched release notes.

Each extracted SDRTrunk VCE download has its own `data` folder. This keeps its settings separate from mainline
sdrtrunk and from other VCE installations. Keep this folder when updating or moving your receiver: it contains your
channels, aliases, accounts, preferences, and other saved data. See the [documentation index](README.md) for related guides.

## Choose a starting point

On first launch, VCE opens the Setup Wizard. A new installation offers **Start fresh** or **Bring my settings**.
Choose a previous installation folder, supported macOS `.app`, or XML playlist; VCE checks the selection and shows
what will be brought over before making changes. Standalone SQLite files are not accepted.

| Your starting point | Choose | What to expect |
| --- | --- | --- |
| You are new to VCE | **Start fresh** | Create a new profile, then set up your administrator account, web access, audio, and radios. |
| You already use VCE | **Bring my settings**, then choose its folder | Bring over its database and available voice libraries, optional modules, and encryption vault. |
| You use mainline sdrtrunk or an older XML playlist | **Bring my settings**, then choose the playlist | Bring over supported channels, aliases, and streaming settings. The XML file stays unchanged. |

Review the selected source and import scope, then continue. For an older database, **Keep activity and statistics**
starts unchecked: settings are kept while Activity, statistics, learned observations, and alert history start fresh.
Select it to carry forward usable history through the supported updates. Any declared historical resets still apply.
Neither choice deletes recording audio or its separate catalog.

The previous installation stays unchanged. Keep it until you have checked the new installation and confirmed that
your channels receive and your recordings or streams work as expected.

## Work through setup

First setup takes you through Your settings, Administrator, Web access, Digital voice, RadioReference,
Statistics & history, Recordings, Your radios, Improve performance, and Review & finish. Usable imported choices show
**Carried over**. Continue skips those completed steps; select any step to review or change it.

A healthy completed profile starts normally. Optional JMBE checks, decoding optimization, and unread release notes
alone do not reopen setup. Interrupted first setup resumes; required data updates, administrator or web-access
repairs, and a required vault unlock are handled before reception starts.

- **Administrator:** Create the password for the fixed `admin` account if the imported profile does not already have
  a usable one. Imported accounts are retained when valid. Changing an existing administrator password requires
  the current password or account recovery.
- **Web access:** New profiles use HTTPS on port **8090**, accessible from this computer. Choose **Other devices**
  if you want remote access. This makes the service reachable through the computer's network interfaces, subject
  to its firewall; it does not open firewall or router ports. An imported disabled web server is enabled locally,
  with the change shown in setup.
- **Digital voice:** Set up JMBE to hear supported digital voice calls. Use an existing library or allow the wizard
  to download and build it. If that fails, retry or set it up later; digital voice needs a working library.
  After migration, setup checks a carried-over library for updates. You can update it or keep the current version;
  a failed update check does not prevent you from continuing with a working library.
- **RadioReference:** Connect your account if you want directory lookups and imports. A saved password and a
  successfully tested connection are shown separately. You can set this up later.
- **Statistics & history:** New profiles collect summary statistics. Saving individual activity events is optional.
  Time-based activity retention defaults to 30 days and can be set from 1 to 365 days. This does not control recording
  retention or ordinary log files; imported Off choices stay Off.
- **Recordings:** Choose Managed Recordings for searchable call details and browser playback, or Classic recordings for ordinary
  audio files. Imported recording choices are preserved. Review the recording folder before starting reception.
- **Your radios:** A device marked **Radio found** has been found; reception has not been tested. No hardware or a missing
  driver does not prevent completing setup. Use **Rescan** after connecting hardware or fixing its driver.
- **Improve performance:** Run the recommended tests to choose efficient signal processing for this computer. Pause
  CPU-heavy work first. Completed valid results are reused; Retry keeps them. **Use defaults for now** allows you to continue.

The sun/moon button changes Light or Dark appearance without losing entered settings. Downloads, library building,
and decoding tests show progress in the wizard. Navigation waits for a running operation to finish, fail, or stop
safely. Exit keeps accepted settings and discards unsaved password entries.

On **Review & finish**, check the actual output folders, web access, and channels selected to start automatically.
Unlock the vault if requested. Reception starts after required setup and decoding preparation finish. Successful
imports and updates continue to the next needed step; their report remains available without a countdown.

## What an import brings over

| Saved item | Copy an installation / data folder |
| --- | --- |
| Channels, aliases, accounts, preferences, and selected application history | Imported, with any required conversion and declared repairs or resets |
| Remote Links pairing and connection settings and paging-tone detection | Imported from the database, including eligible legacy settings kept with an older profile |
| Encryption vault, JMBE library in `jmbe`, and optional files in `modules` | Copied when present and usable; missing or skipped items appear in the report |
| Logs, call audio, event logs, screenshots, streaming output, and the separate Managed Recordings catalog | Not copied; stay at their existing locations |
| Saved output and library paths inside the previous data folder | Changed to the matching location in the new data folder |
| Deliberately shared paths outside the previous data folder | Kept as stored |

XML import creates a new profile from supported channels, aliases, and streaming settings. It does not merge a
playlist into an existing profile or replace an existing database.

The migration plan names possible activity resets and retired settings that will be removed. The completion report
gives the actual repair, reset, and skipped-item counts. Usable configuration is carried over; an unusable component
may need to be set up again without preventing other settings from importing.

Check the folders on **Review & finish** before recording or streaming. If you need old call audio or logs in the
new installation, keep and copy those files separately after stopping the applications; the settings import does
not transfer them.

## Update an existing profile

When a new build finds an older supported database in its active `data` folder, setup reviews the update before
receiving starts. Its backup checkbox starts unchecked. Select it if you want a recovery copy of the old database;
the copy is saved under `data/database/backups`. Without it, returning to the previous build requires another backup.

This updates the active database in place. All required changes run in one transaction. If conversion or final
validation fails before completion, that transaction rolls back. A successful update may make the database
incompatible with the previous build. If selected, its recovery backup remains for manual restoration; VCE does not automatically
switch the database back after a completed update. A healthy database already at this build's format needs no update
or upgrade backup.

Alpha and Nightly share one forward-only database history. Supported VCE sources start with the format used by
Alpha 12; Alpha 11 has that same format and remains compatible. Update older formats with Alpha 12 first. You do not
need every intervening build after that. An older build cannot open a database already converted to a newer format.
Keep separate data folders when comparing builds, and retain the older installation or a backup for going back.
For a numbered Alpha, also read its version-matched release notes. See [Moving and updating VCE data](database-migration.md)
for the support boundary and detailed conversion contract.

## Change settings after setup

Use the running application's **Settings** button or the web interface. Setup is a startup workflow; there is no
Help-menu wizard, SQLite-file import, or XML merge into an existing profile. To bring over another profile or
playlist, start VCE in a new folder.

| Setting | Where to change it |
| --- | --- |
| Appearance | Desktop **Settings → Application → Appearance**, or web **My Settings** |
| Primary administrator password and web access | Desktop **Settings → Stats & Web → Web Server** |
| JMBE file, download/build, or update | Desktop **Settings → Decoder → JMBE Audio Library** |
| RadioReference account and location | Web **RadioReference** |
| Statistics, individual activity events, and activity retention | Web **Admin → Activity settings** |
| Managed or Classic recording mode and Managed age retention | Web **Admin → Recording settings** |
| Classic recording and other output folders | Desktop **Settings → File Storage → Directories** |
| Radios | Web **Tuners** |
| Channel auto-start | Web **Channels → Auto-start** |
| Decoding optimization | Desktop **Settings → CPU → Vector Calibration** |
| Unlock saved encryption keys | Desktop **View → Encryption Keys → Unlock Vault** |
| Release notes | Desktop **Help → What's New** |

On **Web Server**, **Change Password** requires the current password; **Reset Password** provides explicit local
account recovery. **Use HTTPS** can be changed locally and remains required for **Other devices**.

JMBE setup remains local to the desktop application. The desktop statistics panel provides maintenance actions;
collection and retention choices are in the web interface. The Managed recording folder is shown in Recording
settings and is separate from the Classic output folder. Changing Classic's folder does not move the Managed library.

## Where files are stored and how to back them up

Windows, Linux, and current macOS downloads use `<install>/data`. The application database is
`data/database/sdrtrunk.sqlite`. Settings, the encryption vault, JMBE libraries, and optional modules belong to this
profile. Recording and other output folders can be changed, so their effective locations may be elsewhere.

To make a complete manual backup, stop VCE and copy its entire `data` folder. Also copy any recording or output
folders you deliberately placed outside it. Keep the backup with the matching VCE build if you may need to restore
that version. The automatic migration backup covers the database being updated, not every file in the profile.

Classic recordings are ordinary files in the configured recording directory. Managed Recordings uses its configured
audio directory plus the separate `data/database/managed-recordings.sqlite` catalog. Preserve both the audio and
catalog when backing up that library. Managed recording retention is independent of activity retention; Classic
recordings have no automatic retention.

An older supported Managed Recordings catalog gets its own update before receiving starts. Its recording entries
are preserved; the graphical backup checkbox also starts unchecked for this update. If the catalog update fails,
retry or choose **Continue without managed recordings**;
ordinary receiving can continue while that library is unavailable. The retired `webfirst` catalog is unsupported.

Only one VCE process can use a given `data` folder. If you see **already in use**, close the other instance before
trying again. Keep the source and copy the error if an import is refused as unsupported, newer, mixed, or damaged.
Do not remove the database to get past the error. If the destination already contains data, choose a separate new
installation or preserve the existing folder before changing its location.

<details>
<summary>Command-line setup and storage reference</summary>

Development launches use `<working-directory>/data`. The Java property `sdrtrunk.vce.data.root` selects an explicit
data folder for any launch. Java Preferences are stored in the application database's `application_settings` table,
not the operating-system Java preference store. Legacy macOS `.app` imports use the old bundle's sibling
`<app-name>-data` folder. XML discovery checks the mainline sdrtrunk playlist folder for `default.xml`, then
`playlist_v2.xml`; it reads the selected XML without changing it.

Headless launches need an explicit starting option when the database is absent:

```text
--fresh
--import-xml <path>
--upgrade-data <previous-installation-folder>
```

Use only one of these options. Fresh creation and XML import create and validate a temporary current-format database
before installing it. `--upgrade-data` accepts a supported previous installation folder, portable data folder, or
macOS application bundle with the same profile scope as graphical setup. A standalone SQLite file is refused.
These options apply only when the active data folder has no database; they cannot merge into or replace an existing
profile. The retired `--import-sqlite` flag is refused rather than treated as a normal launch.

For a new profile without a usable administrator credential, add:

```text
--admin-password-file <path>
```

The protected UTF-8 file should contain only the password, which must be 7–256 characters. Secure or remove the input
file afterward. VCE stores a salted password verifier. Numbered Alpha builds may predate this setup requirement;
follow the documentation bundled with that build.

For an existing active database, `--upgrade-current` authorizes the in-place update and any recoverable bounded
configuration repairs. It retains a recovery snapshot by default. Add `--no-upgrade-backup` only if you intend to
omit that snapshot; this does not change the conversion or final checks.

`--upgrade-current` also authorizes a required Managed Recordings catalog update. To update only that catalog, use
`--upgrade-managed-recordings`. If the main database is absent but an older catalog remains, combine
`--upgrade-managed-recordings` with the chosen `--fresh`, `--import-xml`, or `--upgrade-data` option. The main profile
and administrator are prepared first. `--no-upgrade-backup` applies to these catalog updates too. A failed optional
catalog update is reported and receiving can continue with Managed Recordings unavailable.

Use `--server` to run without the local window, even when a desktop is available. Graphical startup opens setup only
when the profile needs it; `--setup-wizard` does not force a healthy completed profile back through optional pages
and cannot be used with `--server`. Normal startup validates existing databases. Only the bundled Application Migrator
changes their formats. The format catalog, adjacent conversion requirements,
and historical data policies are retained in the [technical migration reference](database-migration.md#technical-migration-reference).

</details>
