# Setup, updates, and your saved data

This guide describes the current Nightly build. For a numbered Alpha, use its bundled documentation and
version-matched release notes.

Each extracted SDRTrunk VCE download has its own `data` folder. This keeps its settings separate from mainline
sdrtrunk and from other VCE installations. Keep this folder when updating or moving your receiver: it contains your
channels, aliases, accounts, preferences, and other saved data. See the [documentation index](README.md) for related guides.

## Choose a starting point

Launch VCE normally to open the Setup Wizard. On a new installation, choose the option that matches what you have:

| Your starting point | Choose | What to expect |
| --- | --- | --- |
| You are new to VCE | **Start fresh** | Create a new profile, then set up your administrator account, web access, audio, and radios. |
| You already use VCE | **Copy a previous VCE installation / data folder** | Bring over its database and available voice libraries, optional modules, and encryption vault. This is the most complete import choice. |
| You have a VCE database file | **Import a SQLite database only** | Bring over settings and history stored in that file. Other files and saved output paths are not moved. |
| You use mainline sdrtrunk or an older XML playlist | **Import legacy XML** | Bring over supported channels, aliases, and streaming settings from the playlist. The XML file stays unchanged. |

For a previous VCE installation, select a nearby installation or browse to its folder. Click **Review import**, read
the plan, then click **Confirm import**. VCE checks and updates the copied database with its bundled Application
Migrator; there is no separate tool to download. Supported older macOS `.app` installations can also be selected.

The previous installation stays unchanged. Keep it until you have checked the new installation and confirmed that
your channels receive and your recordings or streams work as expected.

## Work through setup

The wizard takes you through Starting point, Administrator, Web access, Digital audio, RadioReference,
Statistics & history, Recordings, Your radios, Optimize decoding, and Review & finish. Usable imported choices show
**Carried over**. Continue skips those completed steps; select any step to review or change it.

- **Administrator:** Create the password for the fixed `admin` account if the imported profile does not already have
  a usable one. Imported accounts are retained when valid. Changing an existing administrator password requires
  the current password or account recovery.
- **Web access:** New profiles use HTTPS on port **8090**, accessible from this computer. Choose **Other devices**
  if you want remote access. This makes the service reachable through the computer's network interfaces, subject
  to its firewall; it does not open firewall or router ports. An imported disabled web server is enabled locally,
  with the change shown in setup.
- **Digital audio:** Set up JMBE to hear supported digital voice calls. Use an existing library or allow the wizard
  to download and build it. If that fails, retry or set it up later; digital voice needs a working library.
- **RadioReference:** Connect your account if you want directory lookups and imports. A saved password and a
  successfully tested connection are shown separately. You can set this up later.
- **Statistics & history:** New profiles collect summary statistics. Saving individual activity events is optional.
  Time-based activity retention defaults to 30 days and can be set from 1 to 365 days. This does not control recording
  retention or ordinary log files; imported Off choices stay Off.
- **Recordings:** Choose Managed Recordings for searchable call details and browser playback, or Classic for ordinary
  audio files. Imported recording choices are preserved. Review the recording folder before starting reception.
- **Your radios:** A device marked **Detected** has been found; reception has not been tested. No hardware or a missing
  driver does not prevent completing setup. Use **Rescan** after connecting hardware or fixing its driver.
- **Optimize decoding:** Run the recommended tests to choose efficient signal processing for this computer. Pause
  CPU-heavy work first. Completed valid results are reused; Retry keeps them. **Skip this time** allows you to continue.

The sun/moon button changes Light or Dark appearance without losing entered settings. Downloads, library building,
and decoding tests show progress in the wizard. Navigation waits for a running operation to finish, fail, or stop
safely. Exit keeps accepted settings and discards unsaved password entries.

On **Review & finish**, check the actual output folders, web access, and channels selected to start automatically.
Unlock the vault if requested. Reception starts after required setup and decoding preparation finish. Import and
update reports have **Copy Message** and a visible ten-second continuation countdown; copying the report does not
pause the countdown.

## What an import brings over

| Saved item | Copy an installation / data folder | Import a SQLite database only |
| --- | --- | --- |
| Channels, aliases, accounts, preferences, and application activity stored in the database | Imported, with any required conversion and declared repairs or resets | Imported, with the same conversion policy |
| Encryption vault, JMBE library in `jmbe`, and optional files in `modules` | Copied when present and usable; missing or skipped items appear in the report | Not copied |
| Logs, call audio, event logs, screenshots, streaming output, and the separate Managed Recordings catalog | Not copied; stay at their existing locations | Not copied; stay at their existing locations |
| Saved output and library paths inside the previous data folder | Changed to the matching location in the new data folder | Kept as stored |
| Deliberately shared paths outside the previous data folder | Kept as stored | Kept as stored |

The migration plan names possible activity resets and retired settings that will be removed. The completion report
gives the actual repair, reset, and skipped-item counts. Usable configuration is carried over; an unusable component
may need to be set up again without preventing other settings from importing.

A database-only import can still point to absolute folders in the previous installation. Relative paths resolve
inside the new `data` folder. Check the folders on **Review & finish** before recording or streaming. If you need old
call audio or logs in the new installation, keep and copy those files separately after stopping the applications;
the settings import does not transfer them.

## Update an existing profile

When a new build finds an older supported database in its active `data` folder, setup offers **Update** before
receiving starts. Leave **Create a recovery backup first** selected unless you already have the recovery copy you
intend to use. The backup is saved under `data/database/backups`; its exact location is reported.

This updates the active database in place. All required changes run in one transaction. If conversion or final
validation fails before completion, that transaction rolls back. A successful update may make the database
incompatible with the previous build. Its recovery backup remains for manual restoration; VCE does not automatically
switch the database back after a completed update. A healthy database already at this build's format needs no update
or upgrade backup.

Alpha and Nightly share one forward-only database history. Supported VCE sources start at Alpha 8; you do not need
to install every intervening build. An older build cannot open a database already converted to a newer format.
Keep separate data folders when comparing builds, and retain the older installation or a backup for going back.
For a numbered Alpha, also read its version-matched release notes. See [Moving and updating VCE data](database-migration.md)
for the support boundary and detailed conversion contract.

## Review setup or import more settings later

Use **Help → Setup Wizard…** and accept the restart prompt. Receiving stops while you review setup. The Starting
point page then offers:

- **Keep my current settings:** Review the existing profile without importing anything.
- **Import a legacy XML playlist:** Add supported channels, aliases, and streaming settings. Existing configuration
  stays in place; conflicting imported names are renamed. VCE saves a database backup before committing the import.
- **Replace settings from a SQLite database:** Replace the complete application database, including channels,
  aliases, accounts, preferences, and stored activity. This does not merge two profiles.

**File → Import SQLite Database…** opens the same replacement flow. Review its warning and plan before confirming.
VCE backs up the current database, updates and checks a private copy of the selected file, installs it, and restarts
into setup review. The selected source is unchanged. Files beside it are not copied; the active `data` folder's
non-database files remain in place, and stored paths are not remapped. An imported profile without a usable
administrator account asks for a new password before reception starts.

Selecting an option or going Back does not import anything. After an import finishes, Starting point shows its
results instead of offering to repeat it. A replacement that cannot establish a safe final state does not restart
automatically; keep its error and reported recovery backup for troubleshooting.

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

An older supported Managed Recordings catalog gets its own backed-up update before receiving starts. Its recording
entries are preserved. If the catalog update fails, retry or choose **Continue without managed recordings**;
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
--upgrade-data <previous-install-data-folder-or-sqlite-file>
```

Use only one of these options. Fresh creation and XML import create and validate a temporary current-format database
before installing it. `--upgrade-data` imports a supported external folder or SQLite file with the same scope rules
as graphical setup; it cannot replace a database already in the active data folder.

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

Use `--setup-wizard` for a graphical setup review; it requires a desktop. Normal startup validates existing databases.
Only the bundled Application Migrator changes their formats. The format catalog, adjacent conversion requirements,
and historical data policies are retained in the [technical migration reference](database-migration.md#technical-migration-reference).

</details>
