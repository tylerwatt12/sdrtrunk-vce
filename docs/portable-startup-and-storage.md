# Portable Startup And Storage

SDRTrunk-VCE keeps each extracted distribution independent from stock SDRTrunk and from other VCE copies.

## Data Location

- Windows and Linux distributions: `<install>/data`
- macOS application: `<app-name>-data` beside the `.app` bundle
- Development launches: `<working-directory>/data`, unless `sdrtrunk.vce.data.root` is set

The data directory owns the SQLite database, vault, preferences, logs, recordings, event logs, screenshots, streaming
files, temporary bug-report bundles, JMBE libraries, and optional modules. Java Preferences are stored in the SQLite
`application_settings` table; the operating-system Java preference store is not used by the normal application.

## First Launch

When `data/database/sdrtrunk.sqlite` is absent, startup searches `${user.home}/SDRTrunk/playlist` for `default.xml`
and then `playlist_v2.xml`. A graphical launch offers to import the discovered XML, start fresh, browse for another
XML file, or quit. The old XML is read only.

Headless launches require one explicit option when the database is absent:

```text
--fresh
--import-xml <path>
```

Fresh creation and XML import build the complete current schema in a temporary database, validate it, and then install
it atomically. Existing databases are validated only. Schema-version upgrades remain external maintenance operations.
