# Portable Startup And Storage

SDRTrunk-VCE keeps each extracted distribution independent from stock SDRTrunk and from other VCE copies.

## Data Location

- Windows and Linux distributions: `<install>/data`
- macOS application: `<app-name>-data` beside the `.app` bundle
- Development launches: `<working-directory>/data`, unless `sdrtrunk.vce.data.root` is set

The data directory owns the SQLite database, vault, preferences, logs, recordings, event logs, screenshots, streaming
files, temporary bug-report bundles, JMBE libraries, and optional modules. Java Preferences are stored in the SQLite
`application_settings` table; the operating-system Java preference store is not used by the normal application.

## First Launch And Upgrades

The normal receiver launch is always headless, even when a desktop session is available. Swing and JavaFX radio
windows are not part of unattended startup. The only non-headless launch mode is the isolated local
`--server-admin-ui` maintenance utility described below.

When `data/database/sdrtrunk.sqlite` is absent, a local graphical maintenance launch first looks beside the current
app or install folder for portable data from an earlier sdrtrunk-vce build. The setup window offers these paths:

- Upgrade using a discovered previous data folder.
- Choose a previous `.app`, install folder, data folder, or `database/sdrtrunk.sqlite` file.
- Import an older XML playlist.
- Set up as new.

The Upgrade Assistant copies the previous SQLite database into a staging folder, updates only that staged copy, runs
schema, integrity, and foreign-key checks, and installs it only after every check succeeds. The previous database is
never replaced. The saved vault, JMBE libraries, and optional module files are also copied when present. Logs,
recordings, event logs, screenshots, and streaming output remain in the previous data folder instead of being
duplicated.

Saved output and library paths that point inside the previous data folder are changed to the matching location inside
the new data folder. Deliberately shared paths outside the previous data folder are left alone.

The first supported database upgrade is P25 activity schema v19 to v20. The migration runs in a bundled child process,
so the normal application startup path remains validation-only. A database already at v20 is copied and validated
without a schema change. Other versions are refused with an explanation.

If the current data folder itself contains a v19 database, startup offers **Upgrade and Start**. It first creates a
standalone backup under `data/database/backups`, migrates another staged copy, validates it, and then replaces the
current database atomically. If an upgrade fails, the application does not start and the completed backup is retained.

If no portable database is found, startup still searches `${user.home}/SDRTrunk/playlist` for `default.xml` and then
`playlist_v2.xml`. The legacy XML is read only.

Headless launches require one explicit option when the database is absent:

```text
--fresh
--import-xml <path>
--upgrade-data <previous-app-or-data-folder>
```

Fresh creation and XML import build the complete current schema in a temporary database, validate it, and then install
it atomically. `--upgrade-data` is the non-graphical equivalent of choosing previous portable data. When an existing
data folder contains a v19 database, `--upgrade-current` explicitly authorizes its one-time v19-to-v20 upgrade. Schema
changes are performed only by the bundled one-off upgrade helper against a staged database.

Once a portable database exists, the app holds an operating-system lock for that data folder until shutdown. A second
sdrtrunk-vce process receives a clear “already in use” error before it can validate, upgrade, or write the same data.

## Local Web Server Settings

The browser administrator account and host-level web-server settings are configured locally, outside the normal radio
runtime. Stop the normal sdrtrunk-vce process first, then launch the same installed package from the receiver's local
desktop with:

```text
--server-admin-ui
```

For example, the packaged Windows launcher accepts `bin\sdrtrunk-vce.bat --server-admin-ui`. The focused JavaFX
window owns the portable data directory exclusively while it is open and does not start tuners, decoders, audio,
recording, streaming, or the embedded web server. Close the window and restart sdrtrunk-vce normally after saving
changes. Never start this maintenance window as a second process beside a running receiver; the data-root lock will
reject that attempt.

The web listener uses one `host-or-IP:port` value and defaults to `127.0.0.1:8090`; LAN and Tailscale addresses are
ordinary bind addresses, not separate modes. HTTPS uses the same connector and port. The maintenance window can
generate a self-signed certificate or import a PEM certificate chain and unencrypted PKCS#8 private key into:

```text
data/security/tls/certificate.pem
data/security/tls/private-key.pem
```

No certificate or key bytes are stored in SQLite, and no certificate history is retained.
