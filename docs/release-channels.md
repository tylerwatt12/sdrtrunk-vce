# Choose a release channel

VCE offers **Nightly** and **numbered Alpha** downloads. Both are pre-release software, and their features can differ.

| Channel | What to expect | Download |
| --- | --- | --- |
| **Nightly** | The newest completed features and fixes. The main README and current guides describe this line. | [Download Nightly](https://github.com/tylerwatt12/sdrtrunk-vce/releases/tag/nightly) |
| **Numbered Alpha** | A more conservative feature set. Newer Nightly features may be absent or look different. | [Browse Alpha releases](https://github.com/tylerwatt12/sdrtrunk-vce/releases?q=alpha) |

Read the release notes for the build you download. The version-matched Alpha notes describe that Alpha's behavior;
a Nightly uses the documentation bundled with its build.

## Download the right package

Open **Assets** on the chosen release page and select the ZIP for your operating system and processor. Java is
included.

- **Apple Silicon Macs:** ARM64.
- **Intel Macs and Intel/AMD 64-bit PCs:** x86-64.
- **Windows or Linux ARM computers:** ARM64.

The rolling Nightly page can contain more than one build's files. Use the current build number identified on that
page to choose the matching package.

Extract the ZIP into a new writable folder and run its **Start VCE** launcher. The
[getting-started steps](../README.md#get-started) explain first launch and importing a mainline sdrtrunk playlist.

## What changes between channels

Nightly receives completed work from the development line more often. It moves most receiver administration into
the website, including Channels, Tuners, Streaming, and RadioReference. The local receiver window opens the website
and provides setup, database import, file access, and debug recording.

A numbered Alpha may retain older desktop tools or omit newer browser and receiver features. Its release number
and notes identify a fixed feature set; the Nightly page is updated as builds are published.

## Update your installation

Update checks follow the channel of your installed package:

- Alpha checks for Alpha updates.
- Nightly checks for Nightly updates.

The updater can open the matching download page. It does not download or install software, change your database,
restart VCE, or switch channels for you.

Before updating, back up the complete `data` folder. Extract the new package into a new folder, start VCE, and choose
**Copy a previous VCE installation / data folder** in setup. Review the import plan, completion report, and output
locations before receiving resumes. The built-in import upgrades supported settings without a separate program.
Existing recordings and logs are not copied; check their configured locations before removing the old installation.

See [portable setup and storage](portable-startup-and-storage.md) for the full workflow.

<details>
<summary>If an older build no longer offers updates</summary>

Alpha 10 and Nightlies published before the channel split used the same legacy update identity. That feed is frozen
because those packages cannot be assigned to different channels safely. Download and install a newer Alpha or
Nightly package manually once; that package will check its own channel afterward.

</details>

## Switching channels and data compatibility

Switching from Alpha to Nightly, or back, is an upgrade or downgrade. **Never open data used by a newer build with an
older build**, even if the older build belongs to your preferred channel.

Keep separate installation and data folders when comparing channels. Back up the source data and read the target
build's upgrade notes. If an Alpha uses an older database format than your Nightly, it cannot open that Nightly's
data.

Alpha and Nightly share the same forward-only database-format history. Compatibility depends on the actual format
and the target build's supported migrations, rather than the channel name. Setup reports supported imports and
required changes before receiving starts. The [database migration guide](database-migration.md) describes the
supported formats and import limits.

<details>
<summary>How releases are published</summary>

Nightly is built from current `main` after automated checks pass and the maintainer approves publication. A push to
`main` does not publish a Nightly by itself. Its release-page description is evergreen; use the documentation bundled
with a particular downloaded build for that build's details.

Numbered Alpha releases advance through reviewed fixes and release preparation. Their version-matched release notes
describe the changes included in each immutable numbered release.

</details>

[Browse the documentation index](README.md).
