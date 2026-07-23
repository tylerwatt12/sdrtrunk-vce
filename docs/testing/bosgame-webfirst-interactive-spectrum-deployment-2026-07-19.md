# BOSGAME exclusive interactive spectrum deployment — 2026-07-19

## Result and scope

The exclusive, permanently admin-only interactive spectrum revision is packaged and running headlessly on BOSGAME.
The browser page, single-admin authentication boundary, one-workspace limit, adaptive-resolution protocol, target
selection, zoom/pan controls, dB floor, grid/readouts, and bounded lifecycle are implemented. A focused local JavaFX
**Web Server Settings** utility is now also packaged, deployed, and graphically smoke-tested on BOSGAME; it provides
the previously missing way to create the single administrator without starting the radio runtime.

This is a **deployment, local provisioning, and locked-state radio-safety pass**, not the physical interactive FFT
acceptance pass. The owner has now created the one administrator locally and the restarted candidate reports the
credential as configured. An authenticated browser session, Airspy/RTL-SDR stream, 1x/2x/4x/8x refinement, target
switching, occupied-slot rejection, and ten-audio-listener concurrency still require the follow-up gate. No production
streaming profile was imported.

## Candidate identity and isolation

| Item | Value |
| --- | --- |
| Branch | `webfirst` |
| Application JAR SHA-256 | `3ff99fd5fcf003aa5ecdfe4f99a099dde24e37d04544cd2cd4febe56fcce566f` |
| Windows x86-64 ZIP SHA-256 | `04f9e5556d189fbed071b016078dee802aaf2cb47e7b4b3704b62b56aa0c3982` |
| Windows ZIP bytes | 100,438,101 |
| Application JAR bytes | 8,285,855 |
| Runtime | Packaged Windows x86-64 Java 25 image, `-Xmx2g`, `java.awt.headless=true` |
| Candidate data root | `C:\Users\Owner\AppData\Local\SDRTrunkCanary\webfirst\data` |
| Active install | `C:\Users\Owner\Desktop\sdrtrunk-vce` |

CUBI and the Mac SDRTrunk runtime were not started or changed. The package was built and tested on the Mac, then
deployed only to BOSGAME. The active BOSGAME install was replaced in place; the production streaming profiles were
not copied into the isolated canary data root.

## Implemented behavior

- Exactly one credential record is permitted in the existing `application_settings` table under `web.auth.v1`; no
  table, column, index, view, trigger, history, or per-session database row was added.
- Password verification uses PBKDF2-HMAC-SHA-256 with a 600,000-iteration floor, one low-priority bounded worker, a
  one-request queue, per-source throttling, and a fixed global pre-hash attempt limit.
- Ordinary admin sessions are transient, bounded, idle/absolute-expiring, CSRF-protected, and invalidated when the
  credential generation changes. Exactly one spectrum WebSocket permit is independent of the ordinary session cap.
- Wideband spectrum is permanently `ADMIN_ONLY`; a mutable public feature policy cannot expose it.
- Clear-HTTP login is refused outside loopback. Remote review requires HTTPS or a loopback SSH tunnel.
- The tuner callback only invalidates prepared state/enqueues bounded sample work. FFT, cropping, serialization,
  network delivery, authentication, JSON, browser rendering, and database access run elsewhere.
- Requested 1x/2x/4x/8x detail maps to 4,096/8,192/16,384/32,768 calculated FFT bins while the transmitted viewport is
  cropped to at most 4,096 bins. Controls are coalesced and stale revisions are rejected.
- Wheel zoom, click-drag pan, keyboard alternatives, lower display floor, horizontal dB grid, synchronized cursor
  frequency/relative-power guide, blurry immediate zoom, and sharp newest-at-top refinement are browser-side.
- With no subscriber, the signal source is stopped; frames, waterfall rows, hover state, and viewport history are not
  persisted.
- `--server-admin-ui` starts only the focused local JavaFX Web Server Settings window. It does not construct the tuner,
  decoder, audio, recording, streaming, or Jetty runtime, and changes apply on the next normal start.
- Normal and maintenance launches acquire an operating-system lock on the exact portable data root before database
  bootstrap. A second process using that same root exits clearly instead of sharing mutable database/settings state;
  another isolated root is not blocked.
- The administrator credential remains one record in the existing `application_settings` table. Provisioning/reset
  tests verify that no table, index, view, or SQLite schema-version change occurs.

## Automated verification

The final source state passed:

```text
./gradlew cleanTest test compileJpmsModuleInfo --no-daemon
suites=86 tests=326 failures=0 errors=0 skipped=1

node --check stats-web/assets/wideband-signal.js
git diff --check
```

The suite includes exact-root same-JVM and child-JVM lock contention, isolated-root launch argument/path behavior,
malformed/null credential repair, and schema-object/schema-version invariance around credential save/reset. The final
Windows x86-64 Java 25 runtime package also built successfully.

## Local administrator utility and duplicate-process mitigation

Before deployment, two Java processes were found using the same active install and isolated canary data root: intended
PID `5648` and newer duplicate PID `8252`. After validating the executable, command, install path, and data root, only
PID `8252` was stopped. The intended process remained healthy until the planned package replacement.

The deployed data-root lock was then exercised against the running replacement. A duplicate normal launch exited with
code 1 and reported that the exact portable data root was already in use; it did not disturb the running candidate
(PID `9100` at that point). This prevents a scheduled-task/manual-launch overlap from silently producing two writers.

The active launcher now has two deliberate modes:

- its normal no-argument path keeps `java.awt.headless=true`, the exact canary data root, and the Airspy startup
  selector; and
- `--server-admin-ui` uses `java.awt.headless=false`, the same exact data root, and no tuner selector.

A desktop shortcut named `sdrtrunk-vce Web Server Settings` invokes the maintenance mode. The shared-root lock means
the operator must first stop the normal candidate; this is deliberate protection, not a broken shortcut.

An interactive Owner-session graphical smoke test launched maintenance PID `9672` and observed the exact title
`sdrtrunk-vce - Local Web Server Settings` at 836 x 639 pixels. The process had zero listening sockets, used the exact
canary data root with `java.awt.headless=false`, and had no tuner selector. The window was closed through its normal
window-close action. The temporary smoke task was removed, the normal headless candidate restarted, and the
administrator state remained unconfigured at the end of this synthetic GUI smoke; no test password or credential was
created or changed. The owner later used the same window locally for the real one-account setup.

## Deployed boundary and browser checks

- After the maintenance smoke test, PID `5860` was observed as the only exact `bin\java.exe` process from the active
  install. It owned port 8090, used the isolated data root and Airspy startup selector, and retained the 2 GiB
  heap/headless options.
- The scheduled task was `Running` while its repeating enabled flag remained `false`. Its one action exactly matched
  the active visible-console launcher; the data-root lock, rather than task state alone, now rejects a duplicate
  manual or scheduled launch.
- `/api/status` was ready on port 8090. `/api/v1/auth/session` returned
  `{"configured":false,"authenticated":false}` after the synthetic GUI smoke and its restart. After the owner entered
  the credential locally and the candidate restarted again, it returned configured `true` and authenticated `false`,
  confirming only configured state without reading, transmitting, or logging the password.
- The earlier locked-state boundary check showed that a same-origin anonymous signal upgrade returned 401 and
  incremented the rejected-handshake counter without starting the source. A non-loopback clear-HTTP login returned 403
  before password work.
- The deployed browser showed **Administrator setup required**, exposed no impossible login form, and retained a retry
  action. The referenced local JavaFX Web Server Settings path now exists as the focused utility and desktop shortcut.
- The final idle state had zero signal sessions/subscribers, a stopped signal source, and zero tuner publication
  errors.

## Short core-radio watch

Three samples over about 20 seconds after the final GUI smoke/restart showed:

- the existing activity-writer success timestamp advanced on every sample;
- `recordsWritten` advanced from 8,188,905 to 8,189,317, an increase of 412;
- dropped activity records remained at zero;
- Jetty's observed queue stayed at zero;
- signal sessions/source work stayed at zero; and
- tuner publication errors stayed at zero.

The SQLite database remained 16,015,360 bytes and its WAL remained 4,251,872 bytes across the three samples. One
process sample for PID `5860` observed a 438,198,272-byte working set, 520,421,376 bytes of private memory, 76 threads,
and 840 handles. The interval is a short continuity check only; it does not claim leak freedom or prove call recording
or RadioResolve delivery. This gate deliberately did not import a production streaming destination.

After the real local account setup, the exact candidate restarted as PID `9392`. Over a further ten-second continuity
check, `recordsWritten` advanced from 8,215,226 to 8,215,458 (+232), the last-successful-write timestamp advanced, and
dropped activity records remained zero. Jetty queued work, signal sessions, tuner publication errors, and signal-source
activity all remained zero. One process sample observed a 387,272,704-byte working set, 416,698,368 bytes of private
memory, 84 threads, and 859 handles. This is still only a short restart/configuration check.

## Bounded-storage issue discovered

The isolated canary root already contained a 1,676,692,577-byte `sdrtrunk_app.log` as of 23:04:01. The current logger
has a ten-day time history but no per-file or total-byte cap, so one active day can grow without a hard bound. Earlier
web-server experiments logged Jetty much more verbosely; the large file's tail contains many Jetty entries, making
that a likely contributor, but the exact historical cause is not proven.

The final audit of the last 1,500 lines retained several findings instead of treating the tail as clean. Historical
lines before the final process included RTL-SDR/Airspy access-denied messages during a maintenance attempt at 22:21,
one stale polyphase-IFFT queue drop at 22:27, and Windows playback-line gain/recycle messages. For final PID `5860`,
started at 23:03:55, only Windows Primary Sound Driver playback gain/recycle messages appeared at 23:03:57; no current
USB access denial or buffer/queue drop was present in the reviewed tail. These are explicit playback-output/startup
findings, not evidence of a current tuner callback or decoder regression, but the short review cannot prove leak or
long-running buffer safety. The post-deploy activity writer continued to advance with zero reported dropped writes.

No log was deleted. Before production release, application diagnostics need size-and-time rotation, a total-byte cap,
and a tested policy for an already-oversized active file. That change must be reviewed separately because activating a
cap may roll or delete existing diagnostic data.

## Provisioning result and remaining physical gate

Local one-account provisioning and the configured-state restart are complete. The normal candidate holds the exact
data-root lock again; the administrator password remained local to the interactive JavaFX dialog.

1. Sign in through loopback/SSH tunnel or HTTPS.
2. Exercise Airspy and RTL-SDR separately at 1x/2x/4x/8x, wheel zoom, drag pan, rapid coalesced churn, floor/hover/grid,
   pause/reconnect, target switching, and disconnect during refinement.
3. Confirm a second browser receives the occupied-workspace response and that closing the owner releases all DSP,
   listeners, sockets, and permits.
4. Repeat the short protected-radio metrics while ten anonymous users listen to call audio. Expand to the longer paired
   regression/soak only if the short gate moves a protected metric.

The exact configured candidate is running on BOSGAME for owner review as PID `9392`; the scheduled task is `Running`
with its repeating enabled flag `false`. Port 8090 is owned by that process, the account reports configured, and no
admin session or spectrum source was active at the end of the restart check.

## 2026-07-20 simplified listener and HTTPS foundation update

The server configuration was simplified to one canonical listen-address value, default `127.0.0.1:8090`, plus one
HTTPS checkbox. The LAN/Tailscale mode and direct-peer address-range filter were removed; the configured socket bind is
now the network-exposure boundary. The one bounded Jetty connector is HTTP or HTTPS and continues to own HTTP, SSE,
static assets, and WebSockets together.

The retained JavaFX maintenance window now supports self-signed RSA-2048/SHA-256 generation and a two-file PEM import.
Certificate and private-key choices are validated as a complete matching pair before the fixed
`security/tls/certificate.pem` and `private-key.pem` files are atomically replaced. The private key format is
unencrypted PKCS#8. No certificate/key bytes, TLS history, rollback generation, or new schema were added to SQLite.
Bouncy Castle 1.84 is packaged only for cross-platform X.509 generation/parsing support; the live Jetty listener uses
the JDK SSL context.

Local verification passed:

```text
88 suites / 341 tests / 0 failures / 0 errors / 1 skipped
compileJpmsModuleInfo --rerun-tasks: passed
java --module-path build/jpms-mods --validate-modules: passed
Windows x86-64 runtime ZIP integrity: passed
```

The final Windows x86-64 artifact was:

```text
ZIP bytes:   109,765,321
ZIP SHA-256: a937891095dfd5e8d89775d785878da4f8ca57d01a42f8f5478a48be9fab2122
JAR bytes:   8,316,824
JAR SHA-256: 8c9845ca5e8817ca9b852f7025fe38c9f6fa9e69d716276490b97ec9976d5d75
Java:        25.0.1
```

Deployment replaced only the package-owned `LICENSE`, `NOTICE`, `bin`, `conf`, `legal`, `lib`, `release`, and
`stats-web` entries. The launcher, isolated database, administrator credential, JMBE jar, and TLS folder were not
replaced. A protected off-desktop runtime/database rollback snapshot is at
`C:\Users\Owner\AppData\Local\SDRTrunkRollback\webfirst\a937891095df`. There was no TLS pair before deployment, and
none was created by the package update. The first deployment-helper attempt encountered a Windows PowerShell 5 array
shape error before the runtime swap; inventory confirmed the old PID, listener, task state, and JAR were unchanged.
The corrected helper then completed the hash-checked replacement.

Final BOSGAME state:

- PID `4408` is the only exact active-install/isolated-root candidate and owns only `127.0.0.1:8090`.
- Its command line retains the 2 GiB heap, headless launch, exact isolated data root, and Airspy selector.
- The task is `Running` with its repeating enabled value `false`.
- The administrator is configured but unauthenticated; no spectrum session/source is active.
- Dropped activity records, Jetty queued work, and tuner publication errors are all zero.
- A later continuity sample showed `recordsWritten` advancing from 9,066,244 to 9,069,129 (+2,885), with the
  last-successful-write timestamp also advancing. Dropped records, queued Jetty work, active spectrum sessions/source,
  and tuner publication errors remained zero.
- HTTPS remains off until the owner stops the candidate, opens the local maintenance window, generates/imports a
  certificate pair, enables HTTPS, closes maintenance mode, and restarts the normal candidate.
- The transferred ZIP, staging directory, and temporary PowerShell deploy helper were removed. The exact candidate
  remains running for owner review.
