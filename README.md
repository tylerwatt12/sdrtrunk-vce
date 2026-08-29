# sdrtrunk-vce

`sdrtrunk-vce` is an independent, enhanced version of
[SDRTrunk](https://github.com/DSheirer/sdrtrunk). It keeps the familiar receiver, decoder, recording, streaming, and
Java configuration tools while adding a browser activity screen, a built-in website, portable storage, browser
scan-list listening, long-term statistics, and performance improvements.

This project is currently pre-release software with two intentionally different release lines. Numbered **Alpha**
builds are the more conservative line; **Nightly** builds contain the current `main` feature set. Back up your
receiver data before installing or upgrading.

- [Download the numbered Alpha](https://github.com/tylerwatt12/sdrtrunk-vce/releases/latest)
- [Download the current Nightly](https://github.com/tylerwatt12/sdrtrunk-vce/releases/tag/nightly)
- [Choose a release channel](docs/release-channels.md)
- [Sponsor development](https://github.com/sponsors/tylerwatt12)
- [Report a problem or request a feature](https://github.com/tylerwatt12/sdrtrunk-vce/issues)

> **More features, less overhead.**
>
> In the project's same-receiver test, VCE used **8.5% less CPU** and had **25% fewer Java cleanup pauses** than the
> latest tested official SDRTrunk build. Results will vary depending on your computer, tuners, channels, and enabled
> features.

## Highlights

- **Stable browser activity screen:** Live Systems has one Conventional tab and stable tabs for each trunked site.
  Frequencies stay in place instead of constantly moving around.
- **Built-in webserver and scanner:** New profiles start the website on local-only HTTPS by default, while preserving
  an operator's saved enabled or disabled choice. View live systems, sites, channels, talkgroups, radios, activity,
  and statistics from a browser. Subscribe to one or more administrator-defined
  [Scan Lists](docs/browser-listening-and-scan-lists.md); assign normal Aliases or Alias List Defaults to them. Those
  defaults also normally initialize new talkgroup Aliases consistently across the desktop, web, Discover, and
  RadioReference workflows. Overlapping routes are deduplicated before completed calls enter the conversation-aware
  browser queue.
- **Portable setup:** Each VCE installation keeps its own database, settings, tuners, JMBE library, logs, recordings,
  statistics, and web files. It does not rely on the sdrtrunk in the userprofile, so you can rest assured it will not overwrite files from previous versions of sdrtrunk
- **Safe importing and upgrades:** VCE allows you to import an existing SDRTrunk XML playlist, or migrate to a new version from a previous VCE database with the built-in Application Migrator.
- **Clear channel types:** Conventional P25, DMR, and NXDN are kept separate from trunked systems, while one
  conventional analog Alias-list family serves both AM and NBFM channels.
- **Desktop and mobile listening:** The full desktop website and a separate touch-friendly mobile listener share the
  same completed-call audio service, scan-list subscriptions, queue limits, and playback state.

## Current Nightly Feature Set

Current `main` and Nightly builds expand the built-in website and strengthen P25 decoding. Numbered Alpha builds may
omit these newer features until they are deliberately included in that release line.

- **Secure web administration** adds automatic HTTPS, Public/User/Admin access tiers, user management, and custom
  certificate import without manually stopping the server.
- **Alias management moves into the website**, including bulk editing, per-list unmatched-talkgroup behavior, observed
  talkgroup discovery, and a RadioReference **Import All** action in JavaFX.
- **Live diagnostics add Events, Messages, and bounded Signal and Symbols views**, plus a demand-driven whole-tuner
  FFT and waterfall with zoom, smoothing, frequency snapping, and channel flags.
- **The Java receiver window is deliberately smaller:** Map (when enabled) and Tuners remain, while the old Systems
  workspace, embedded diagnostics, and receiver-local tuner Spectrum/Waterfall displays are removed. Both selected-
  channel and tuner-wide FFT/waterfall diagnostics remain available in the website.
- **P25 NAC, CRC, and error-correction handling is stricter**, while bounded weak-voice recovery preserves usable
  Phase 1 audio and late encryption details remain attached to the correct call.
- **The exact Alpha 8/Alpha 9 database layout uses the built-in migrator.** Alpha 8 and Alpha 9 shipped the same
  schema, so the database cannot identify which release created it. Most configuration and activity are preserved;
  retired fully-qualified P25 Alias rows are removed, and P25 affiliation history plus qualifier-sensitive identity
  summaries rebuild from new traffic.

This section describes the rolling Nightly line, not Alpha 10. For shipped Alpha behavior, use the version-matched
[Alpha release notes](https://github.com/tylerwatt12/sdrtrunk-vce/releases). The checked-in
[Alpha 10 What’s New](docs/whats-new-0.6.2-alpha-10.md) remains the historical document for that exact release.

## Screenshots

Click any screenshot to view it at full size.

<p align="center">
  <a href="docs/screenshots/web-live-systems.png"><img src="docs/screenshots/web-live-systems.png" width="85%" alt="Live Systems view showing stable conventional and trunked channel activity"></a>
  <br>
  <strong>Live Systems</strong> — stable channel rows with current calls, signal level, and decode quality.
</p>

<table>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/screenshots/web-system-overview.png"><img src="docs/screenshots/web-system-overview.png" alt="P25 system overview with site and activity totals"></a>
      <p align="center"><strong>System overview</strong></p>
    </td>
    <td width="50%" valign="top">
      <a href="docs/screenshots/web-control-channel-quality.png"><img src="docs/screenshots/web-control-channel-quality.png" alt="Control-channel signal and decode-quality charts"></a>
      <p align="center"><strong>Signal and decode quality</strong></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <a href="docs/screenshots/web-systems-sites-directory.png"><img src="docs/screenshots/web-systems-sites-directory.png" alt="Systems and Sites directory"></a>
      <p align="center"><strong>Systems and Sites directory</strong></p>
    </td>
    <td width="50%" valign="top">
      <a href="docs/screenshots/web-site-overview.png"><img src="docs/screenshots/web-site-overview.png" alt="Site overview with decoded information and top talkgroups"></a>
      <p align="center"><strong>Site overview</strong></p>
    </td>
  </tr>
</table>

<details>
  <summary><strong>More screenshots</strong></summary>
  <br>
  <table>
    <tr>
      <td width="50%" valign="top">
        <a href="docs/screenshots/web-dashboard-overview.png"><img src="docs/screenshots/web-dashboard-overview.png" alt="Dashboard with signal health, call volume, sites, talkgroups, and radios"></a>
        <p align="center"><strong>Operations dashboard</strong></p>
      </td>
      <td width="50%" valign="top">
        <a href="docs/screenshots/web-site-activity.png"><img src="docs/screenshots/web-site-activity.png" alt="Detailed site activity with calls, radios, talkgroups, frequencies, and security status"></a>
        <p align="center"><strong>Detailed site activity</strong></p>
      </td>
    </tr>
  </table>
</details>

## Which Release Channel Should I Use?

| Channel | What you get | Download |
| --- | --- | --- |
| **Numbered Alpha** | A more conservative feature set that advances through reviewed fixes and release preparation. | [Latest Alpha](https://github.com/tylerwatt12/sdrtrunk-vce/releases/latest) |
| **Nightly** | The latest completed `main` build, including newer features that may not be in Alpha yet. | [Current Nightly](https://github.com/tylerwatt12/sdrtrunk-vce/releases/tag/nightly) |

These are the only active release channels. See [Release Channels](docs/release-channels.md) for updater and database
compatibility details.

## Coming From Regular SDRTrunk?

Your existing SDRTrunk installation and XML files are safe. VCE only **reads** XML during import. It does not overwrite,
move, rename, delete, or write changes back to the XML.

The easiest way to switch is:

1. Leave regular SDRTrunk where it is.
2. Extract VCE.
3. Start VCE and choose **Use Found XML** or **Choose XML…**.
4. Review the imported channels and file locations.
5. Keep the old installation until VCE has been tested.

VCE normally finds:

```text
${user.home}/SDRTrunk/playlist/default.xml
${user.home}/SDRTrunk/playlist/playlist_v2.xml
```

You can also choose another XML file. First-launch import creates a new SQLite configuration. To add another playlist
later, use **File > Import Legacy Playlist XML**. VCE previews the supported contents, keeps existing configuration,
renames imported name conflicts, and creates a timestamped database backup before applying the import. Your original
XML remains unchanged and regular SDRTrunk can continue using it.

To replace the active profile from a supported older database after setup, use **File > Import SQLite Database**.
VCE shows the migration plan and an explicit replacement warning, retains the current database as a safety backup,
migrates only a staged copy of the selected file, validates it, and restarts. This imports only SQLite contents; it
does not copy files beside the selected database or remap stored portable paths. If the imported database has no
administrator, setup asks for a new administrator password after restart. A failed replacement does not restart
automatically when the final active-database state cannot be confirmed.

After importing:

- Check that P25 and DMR channels have the correct Conventional or Trunked type.
- Check tuner assignments and auto-start channels.
- Set up JMBE under **Preferences > Decoder > JMBE Audio Library** if needed.
- Check recording, event-log, screenshot, and streaming folders.
- Back up the new VCE data folder.

## Updating VCE

VCE checks for a newer build on the installed package's release channel when the desktop app starts. You can also use
**Help > Check for Updates**. An Alpha package checks only the Alpha feed; a Nightly package checks only the Nightly
feed.

The update check does **not** download, install, replace files, change the database, restart VCE, or switch channels.
You always choose when to install an update. Alpha 10 and older Nightlies used the same legacy update identity, so
entering the separated channels requires one manual download from the Alpha or Nightly link above.

Recommended update steps:

1. Close the old VCE version.
2. Back up its complete data folder.
3. Extract the new version into a new empty folder.
4. Start it and choose **Migrate Existing**.
5. Select the old app or data folder if VCE does not find it automatically.
6. Check your channels, tuners, JMBE library, file locations, web settings, and auto-start behavior.

The Application Migrator copies the setup, creates a safety backup, updates a separate copy of the database, and checks
it before the new version starts. The old installation is left unchanged, making it easy to go back.

Existing logs, recordings, screenshots, event logs, and streaming output are not copied into the new installation.
Check the saved folder locations before deleting an old version.

> **Database compatibility in this source:** The bundled Application Migrator supports every verified successfully
> published database format from Alpha 8 through the current format across alpha and nightly builds, without
> installing skipped releases first. It refuses pre-Alpha 8, retired `webfirst`, known-unpublished developer,
> unknown, mixed, and newer-than-the-app databases without changing the source. Older distributions retain the
> compatibility documented by their own version-matched release notes. See the
> [Database Migration Contract](docs/database-migration.md).

Alpha and Nightly are different feature channels, not different database universes. Follow the exact version-matched
upgrade notes for the build you install. Database changes are forward-only: never open or copy a database used by a
newer build into an older build, including when switching from Nightly to Alpha.

## Where VCE Stores Data

- Windows, Linux, and macOS packages: `<install>/data`
- Development builds: `<working-directory>/data`

This data folder contains the active database, settings, tuner setup, vault, JMBE library, optional modules, logs,
recordings, screenshots, event logs, streaming files, statistics, and editable website files.

VCE does not store its active setup in `~/SDRTrunk`, AppData, the registry, or your operating system's Java preferences.
The old `~/SDRTrunk` folder is used only as a possible source for XML import.

For more detail, see [Portable Startup And Storage](docs/portable-startup-and-storage.md).

## Supported And Removed Features

These older or experimental features are not included:

- Receiver-local speaker playback, output-device selection, and the desktop Hold, Avoid, priority, and backlog controls
- Receiver-local tuner Spectrum/Waterfall panels and separate spectrum windows; web diagnostics remain supported
- Local alias actions and the Actions editor
- LTR Standard, LTR-Net, Passport, and MPT-1327 decoders
- Funcube Dongle Pro/Pro+ tuners
- Legacy named Channel Maps formerly used by MPT-1327; decoder-embedded DMR and NXDN channel maps remain supported
- Heterodyne channelization
- Sound-card capture sources
- Shoutcast v2/Ultravox streaming

## Installation

1. Choose a [numbered Alpha](https://github.com/tylerwatt12/sdrtrunk-vce/releases/latest) or the
   [current Nightly](https://github.com/tylerwatt12/sdrtrunk-vce/releases/tag/nightly).
2. Extract it into a new writable folder.
3. Start `bin/sdrtrunk-vce` on macOS or Linux, or `bin\sdrtrunk-vce.bat` on Windows.
4. Import XML, migrate a previous VCE setup, or start fresh.
5. Review the imported channels and file locations before enabling auto-start.

Java is included in release packages. You do not need to install it separately.

## Building From Source

Development builds require Java 25.

```bash
./gradlew test
./gradlew clean build -PprojectVersion=local-dev -PupdateTrack=none -PupdateBuild=0
```

Use an explicit non-public version such as `local-dev` for development packages. Numbered package tasks stop while
their version-matched release notes are still marked as a draft.

Package tasks:

```bash
./gradlew runtimeZipCurrent -PprojectVersion=local-dev -PupdateTrack=none -PupdateBuild=0
./gradlew --no-configuration-cache runtimeZipWindows -PprojectVersion=local-dev -PupdateTrack=none -PupdateBuild=0
./gradlew --no-configuration-cache runtimeZipOthers -PprojectVersion=local-dev -PupdateTrack=none -PupdateBuild=0
```

Build output is written under `build/image`.

## More Information

- [Release channels](docs/release-channels.md)
- [Release notes](https://github.com/tylerwatt12/sdrtrunk-vce/releases)
- [Portable startup and storage](docs/portable-startup-and-storage.md)
- [How browser listening and Scan Lists work](docs/browser-listening-and-scan-lists.md)
- [How talker aliases work](docs/talker-alias-implementation.md)
- [Listening-delay findings](docs/sdrtrunk-latency-findings.md)

## Sponsor Development

If VCE is useful to you, you can
[sponsor development through GitHub Sponsors](https://github.com/sponsors/tylerwatt12).

Sponsorships are optional and are used only to offset the AI-assisted development costs of building and maintaining
this fork, including Codex subscriptions, coding-agent tools, and usage-based AI model/API charges. Unused funds are
saved for the same future development costs.

Sponsorship does not buy software, features, support, priority, early access, or influence over project decisions. VCE
remains free and open-source. Sponsorship supports this independent fork, not the original SDRTrunk project or other
upstream projects.

## Credits And License

SDRTrunk was created by Dennis Sheirer. `sdrtrunk-vce` includes work from the official SDRTrunk community and
optimization and platform work from the W6BAZ/bazineta experimental fork, followed by VCE-specific changes.

- [Official SDRTrunk project](https://github.com/DSheirer/sdrtrunk)
- [Official SDRTrunk wiki](https://github.com/DSheirer/sdrtrunk/wiki)
- [W6BAZ/bazineta fork](https://github.com/bazineta/sdrtrunk)

This project uses the GNU General Public License version 3. See [LICENSE](LICENSE) and [NOTICE](NOTICE). It is an
independent modified distribution and is not an official SDRTrunk release or support channel.
