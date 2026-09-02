# What’s New in sdrtrunk-vce 0.6.2 Alpha 12

## What

Alpha 12 is a focused Windows packaging repair. Alpha 11’s Windows archives omitted the launcher settings needed
to find the SDRplay API and use the native Windows look and feel. Its fallback loader also checked a filename without
the required `.dll` suffix. Together, those defects could make an installed SDRplay tuner appear unavailable. Alpha
12 corrects both paths and verifies the finished Windows archives before they can be released.

## Added

- **Windows package self-checks.** Both x86-64 and ARM64 archives must contain the SDRplay API search path and the
  required Windows desktop export. The packaged application JAR is also checked to ensure it does not contain the
  non-Windows compatibility class.

## Changed

- **Windows cross-packaging starts clean and targets Windows explicitly.** This prevents files produced for Linux or
  macOS from being reused in a Windows archive.
- **SDRplay load diagnostics are clearer.** The log now distinguishes a missing API file from a file that exists but
  could not be loaded.

## Fixed

- **SDRplay API discovery on Windows.** The fallback loader now resolves the real mapped filename,
  `sdrplay_api.dll`, under the standard 64-bit SDRplay API folder in Program Files.
- **All supported SDRplay models use the repair.** The shared loader covers RSP1, RSP1A, RSP1B, RSP2, both RSPduo
  tuners, RSPdx, and RSPdx-R2.
- **Windows launcher consistency.** The required JIDE Windows export is restored, and Windows packages no longer
  carry the non-Windows look-and-feel compatibility class.
- **Other tuner implementations are unchanged.** This release does not change Airspy, HackRF, HydraSDR, or RTL-SDR
  tuner code.

## Removed

- No supported features were removed in Alpha 12.

## Before You Upgrade

- Stop sdrtrunk-vce, back up the complete portable `data` folder, and install Alpha 12 in a new empty folder.
- The database remains at format 2. Alpha 11 data does not need a new schema migration for this update.
- On Windows, install the 64-bit SDRplay API in its standard Program Files location before starting sdrtrunk-vce.
- After upgrading, confirm that each SDRplay device appears in the Tuners tab and can start normally. Also verify your
  usual channels, recording, streaming, and website access before leaving the receiver unattended.
- Keep Alpha 11 and its original data available for rollback until Alpha 12 has passed those checks.

## Downloads

Choose the download for your operating system and processor. Java 25 is included. JMBE is set up separately under
**Preferences > Decoder > JMBE Audio Library**. Verify the ZIP’s SHA-256 checksum against `SHA256SUMS.txt`.
