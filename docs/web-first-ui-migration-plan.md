# Web-First Radio UI Migration Master Plan

Status: wideband-signal foundation implemented and its historical short BOSGAME Airspy/RTL canary passed 2026-07-19;
the exclusive interactive revision is implemented and deployed headlessly on BOSGAME, and its locked-state/browser
and short core-radio watch passed. The focused `--server-admin-ui` web-server/account utility and exact-data-root lock
are packaged, deployed, and GUI-smoke-validated on BOSGAME, including rejection of a concurrent launch. Administrator
credential entry and configured-state restart are complete. Authenticated browser and physical Airspy/RTL refinement
and exclusivity remain open, as do the full Phase-14 node/service utility, full Phase 4, long-soak, and later feature
gates. The expanded shared Listen/Recordings player, authoritative Listen-list Settings editor, retired web Alias Listen
gate, separate recorded-call retention, fixed `calls/v1` storage convention, and corrected admin-only Live Signal/Symbol
actions are review designs as of 2026-07-21; they do not authorize their backend or schema work yet.
Repository baseline: `main` at `ec9cae46`, audited 2026-07-18
Target product: `sdrtrunk-vce`

## 1. Purpose

This document maps the migration of `sdrtrunk-vce` from its current mixed Swing/JavaFX desktop interface to a
browser-based **radio** interface with policy-controlled public read-only monitoring and authenticated administration,
plus a deliberately retained local JavaFX node-administration utility.
The Java decoder, DSP, tuner, recording, playback, streaming, statistics, and portable-storage runtime remains the
application. The browser becomes the primary and only supported interface for radio monitoring, radio configuration,
and radio hardware control.

Before implementing a user-facing feature slice, complete the applicable entries in the BOSGAME
[legacy UI reference catalog](ui-reference/README.md) and approve that slice's annotated web-first page mockup. The
catalog preserves behavioral evidence; it is not a pixel-perfect clone specification. The
[wideband signal-page mockup](ui-reference/mockups/wideband-signal-page.md) is implementation-approved and its bounded
foundation is implemented. That approval does not authorize later user-facing slices whose mockups remain open.

The first user-visible migration wave, with each later item retaining its own mockup gate, is:

1. Wideband tuner FFT and waterfall.
2. Shared public/admin Listen player, bounded Recordings browser, and administrator-made Listen lists.
3. Selected-channel FFT, symbol graph, Events, and Messages.
4. Playlist/channel editor.
5. Alias editor.
6. Streaming settings and status.
7. RadioReference importer.
8. Tuner inventory, settings, and control.

JavaFX remains in the final product for local server settings, non-radio platform-service controls/configuration, and
local recovery, and owns the implemented local HTTPS certificate/private-key workflow.
This retained utility is not a fallback radio UI.
Swing and every JavaFX screen that configures or operates the radio are still retired.

This is a strangler migration, not a rewrite of the radio runtime. Each browser feature is built against a headless
service boundary, operated beside the legacy UI long enough to prove parity, then used to retire the corresponding
radio desktop code.

## 2. Executive recommendation

Build one Java application that can run headlessly with one embedded, same-origin web server and one packaged browser
SPA, plus a narrow optional local JavaFX node-administration adapter:

```text
Browser
  |-- HTTPS REST /api/v1/*          queries, validation, and commands
  |-- SSE /api/v1/events/*          low-rate state, events, messages, and jobs
  `-- WebSocket /api/v1/ws/signal   binary spectrum frames and symbol batches
                 |
       access-policy/auth gateway
                 |
       query services + command executor
                 |
     neutral runtime registries and brokers
                 |
 decoder/DSP | tuners | audio | configuration | SQLite

Local JavaFX Node Administration
  `-- typed node-administration services -- server/listener/TLS/platform-service settings
```

The recommended implementation direction is:

- Use the implemented general `WebApplicationService` on embedded Jetty 12.1.11 for HTTP, SSE, static assets, and
  WebSockets. Java 25, named JPMS modules, the current-platform jlink image, and Windows x86-64 packaging have passed;
  all-six-target images, signing/notarization, release CI, and the remaining receiver-node TLS gates remain open.
- Keep one port and one process. Do not run a second admin server beside the current stats server.
- Keep one runtime owner. The JavaFX utility runs in-process when explicitly launched or in exclusive maintenance mode;
  it must never become a second concurrent process that writes the portable database.
- Use REST for web/radio mutations, SSE for low-frequency updates, and binary WebSockets only for high-rate signal data.
  Do not add browser mutation routes for listener, TLS material, or local platform-service settings.
- Build a TypeScript/Vite SPA. React is a practical default for large typed forms, staged imports, virtualized tables,
  and live workspaces. Runtime packages contain compiled assets and do not require Node.js.
- Retain immutable, fingerprinted production assets. Keep an external asset-root override only for development.
- Never expose Jackson-serialized domain objects directly. Define explicit request/response records and generate or
  check TypeScript types against a versioned API contract.
- Put every configuration or hardware mutation through validated application services and serialized command
  executors. HTTP threads must not touch Swing/JavaFX models, decoder histories, or tuner controllers directly.
- Use stable persistent identities and revisions for editable aggregates. Require `If-Match`/revision on updates and
  return `412 Precondition Failed` for a failed HTTP `If-Match`; use `409 Conflict` for domain locks or a revision
  conflict carried in a command body.
- Keep transient spectrum, symbols, events, and raw messages out of SQLite. Use bounded memory and subscriber-driven
  production.
- Grandfather every database schema and retention behavior already implemented at this planning baseline. Apply the
  bounded-storage rule prospectively to every new table, column, index, view, trigger, write category, retained record
  type, or persistence behavior proposed by the web migration; do not turn this UI migration into a rewrite of
  existing statistics storage.

The server spike should use the current [Jetty server/WebSocket documentation](https://jetty.org/docs/jetty/12/programming-guide/server/websocket.html)
and [JDK 25 `jdk.httpserver` documentation](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/module-summary.html)
as baselines. Oracle characterizes the JDK implementation as a minimal server for simple use rather than a
full-featured, high-performance server, which supports replacing it before high-rate authenticated telemetry is added.

## 3. Definition of the final state

The migration is complete only when all of the following are true:

- The canonical unattended launcher starts a genuinely headless runtime without constructing Swing or JavaFX objects;
  an explicit local maintenance/interactive launch may construct the retained JavaFX node-administration window.
- A browser can perform every supported radio monitoring, configuration, and hardware-control workflow.
- The web admin surface has a real login, session management, CSRF protection, rate limiting, secure transport policy,
  authorization checks, secret redaction, and security headers.
- A local JavaFX node-administration surface owns server/listener settings, non-radio platform-service controls and
  recovery without exposing radio controls; it is the designated owner for any later certificate-import workflow.
- Wideband and selected-channel signal views are subscriber-driven, bounded, and do not block tuner or decoder threads.
- Playlist, aliases, streams, RadioReference imports, tuner settings, and all radio-affecting preferences use neutral
  server-side validation and transactional/serialized commands exposed through the web UI.
- Existing read-only stats pages, Live, Listen, Recordings, and their one shared browser-audio player are part of the
  same web application.
- The Swing shell, every desktop radio view/editor, and their event-bus navigation requests are deleted after parity
  gates pass; only the narrow local JavaFX node-administration adapter remains.
- JavaFX properties and Swing table models no longer serve as canonical runtime models.
- UI-only libraries and JPMS modules are removed when their last use disappears; the minimum JavaFX modules required by
  the retained node-administration utility remain packaged and tested.
- Java 25 packages still build for Windows, Linux, and macOS on x86-64 and ARM64.
- CUBI still runs with `-Xmx2g` and `-Dsun.java2d.d3d=false`, and both Windows nodes retain the required visible-console
  launcher and scheduled-task watchdog layout.
- Portable state remains in `<install>/data` on Windows/Linux and the sibling data directory on macOS.
- Existing SQLite schemas are validation-only at normal startup; all deployed schema upgrades use an explicit offline
  migration with backup and integrity validation.
- Existing database schemas remain supported without a retroactive retention redesign. No newly proposed statistics,
  history, job, audit, cache, or telemetry schema can retain unbounded data; current user-owned configuration may
  persist until the user deletes it.

### 3.1 Permanent UI ownership boundary

Use subject matter, not the word “service,” to decide ownership:

| Surface | Final owner | Examples |
| --- | --- | --- |
| Radio operation and monitoring | Web UI; public read-only or authenticated per feature policy, with wideband always admin-only | Live systems, audio, wideband/selected-channel FFT and waterfall, symbols, Events/Messages, recordings, call activity |
| Radio configuration and hardware | Authenticated web UI | Retained playlist/channels, aliases with vector icons, streams/broadcast destinations, RadioReference, tuner settings/control, calibration, recording/replay, activity/statistics controls, JMBE/voice modules and radio-affecting preferences; named MPT Channel Maps and node speaker output are excluded |
| Node server and non-radio platform services | Local JavaFX utility | Web enablement, one `host-or-IP:port` listen address, HTTP/HTTPS mode, platform-service enable/autostart/status/restart, local recovery and diagnostics needed before the web server is available |
| Certificate material | Local JavaFX utility | Generate a self-signed certificate or locally import a bounded PEM certificate chain and matching unencrypted PKCS#8 PEM key into fixed files under `<data>/security/tls` |
| Presentation-only choices | Admin public defaults plus browser-local overrides | Theme, layout, columns, identifier style, encryption detail, signal palette, zoom and per-browser filters; never one database row per visitor |
| Unattended provisioning/recovery | CLI plus non-secret protected configuration input | Database bootstrap/import, admin reset and server settings when no graphical session is available; secrets use interactive console/stdin |

A service that consumes RF, controls a tuner, changes decoding/recording/playback, or publishes decoded radio output is
a **radio service** and therefore belongs in the web UI. Calling it a service does not move it into JavaFX. The retained
JavaFX surface is local-only node administration, never a parallel radio editor and never a direct persistence owner.
The web radio UI and legacy radio adapter share neutral radio services. The web module receives only a redacted
`NodeStatusQueryService`; local JavaFX/CLI alone can reach node-mutation services. No UI stores canonical state in
toolkit properties.
`WebServerPreferenceEditor` is a possible source for the retained settings, after service extraction;
`StatsServerPreferenceEditor` remains radio/activity functionality and moves to the web despite “Server” in its name.
Every new or ambiguous control stays outside the JavaFX allowlist until the surface ledger classifies it explicitly.

## 4. Repository findings that shape the plan

### 4.1 The application uses two desktop UI toolkits

The main shell and most live views are Swing, but the playlist/channel, alias, streaming, RadioReference, icon, JMBE,
encryption-key, recording-viewer, and Preferences windows are JavaFX. A source audit currently finds approximately:

- 106 production Java files mentioning `javax.swing`;
- 104 mentioning `java.awt`;
- 168 mentioning `javafx`; and
- 297 mentioning at least one of those packages.

Some AWT/`java.desktop` use may remain for Java Sound or image work after Swing is gone, and a minimal JavaFX module set
now remains intentionally for local node administration. Final dependency decisions must therefore use `jdeps` and
actual imports rather than assuming that removing the radio desktop removes every UI module.

### 4.2 Runtime construction is coupled to the Swing shell

The initial audit found `src/main/java/io/github/dsheirer/gui/SDRTrunk.java` acting simultaneously as the main method,
service composition root, lifecycle manager, Swing window, menu system, spectrum owner, and shutdown coordinator. The
wideband foundation now prevents the headless path from constructing `SpectralDisplayPanel`,
`TunerSpectralDisplayManager`, or `JavaFxWindowManager`, and a packaged BOSGAME headless launch passes. `SDRTrunk`
still remains the broad composition root and retains desktop-toolkit references, so this is an interim guard rather
than the final toolkit-independent runtime architecture.

The remaining structural change is to extract the full headless runtime owner. The temporary legacy radio desktop
becomes an adapter, not its owner; the retained JavaFX node-admin launcher is a separate narrow adapter over node
services.

### 4.3 The existing web console is a valuable starting point, not the final platform

The repository already contains:

- `src/main/java/io/github/dsheirer/stats/StatsWebServerService.java`;
- `StatsWebDatabase`, `StatsLiveService`, and bounded `StatsLiveEventHub`;
- REST reads, bounded SSE for live systems/activity/calls, and browser call audio; and
- `stats-web/`, a hand-written SPA with roughly 2,835 lines of global JavaScript and 1,699 lines of CSS.

This proved same-process web delivery and provides useful read models. The current wideband foundation has remounted
those routes behind `WebApplicationService` and Jetty, moved bounded SSE delivery to virtual threads, added a binary
signal WebSocket, and added a feature-policy gateway. The active implementation also redacts asset, database, and
signal-source paths and raw database errors from the public status DTO. The deployed foundation includes the single
admin credential/session service, CSRF logout, throttling, same-origin checks, clear-HTTP loopback restriction, site
security headers, and permanent wideband admin-only enforcement. The isolated BOSGAME root still needs its local
credential exercised through an authenticated browser session before end-to-end hardware testing; local provisioning
and the configured-state restart are complete. TLS, password-change workflow, mutation endpoints, frontend modules,
and TypeScript remain open release gates. The historical packaged canary resolved all subjects as anonymous; the
current candidate instead fails closed and keeps the signal source idle.

Browser call audio still shares the compatibility access-policy path. Existing routes are now mounted behind the new
server as compatibility routes; they may be
ported to `/api/v1` handlers only after authentication/authorization policy, method allowlists, request limits,
security headers, and redacted DTOs are applied. They should not be discarded and rewritten all at once, nor carried
forward with their present exposure unchanged.

### 4.4 Core state is still implemented as UI state

Important examples include:

- `DiscoveredTunerModel` is both the tuner registry and a Swing `AbstractTableModel` that marshals through the EDT.
- `ChannelActivityModel` uses AWT `EventQueue` and a Swing `Timer`; `ChannelActivityTableModel` is a table model.
- `BroadcastModel` is at once a broadcaster lifecycle owner, Swing table model, and JavaFX observable-list owner.
- `ChannelModel`, `AliasModel`, `Alias`, channel maps, stream configurations, icon models, resource monitoring, and
  several decoder-facing classes expose JavaFX properties or collections.
- `ConfigurationManager` imports a GUI refresh interface and documents methods that must run on the JavaFX thread.

Deleting panels first would leave a headless server dependent on both UI toolkits. Neutral registries, snapshots,
listeners, services, and temporary UI adapters must be introduced before visible code is retired.

### 4.5 Current configuration writes are incompatible with concurrent web editing

`ConfigurationManager` observes mutable UI collections, waits roughly two seconds, and then:

- `ConfigurationDatabaseStore` deletes/reinserts all channels, maps, and streams; and
- `AliasDatabaseStore` deletes/reinserts all aliases and normalized children.

Database IDs are not loaded back into the domain models. Channel and stream IDs are runtime-only; a channel's
`radres_guid` is a site identity and must not be repurposed as a generic configuration identity. There are no revisions
or stale-edit checks.

Before web CRUD becomes authoritative, persistence needs stable IDs, aggregate-level transactions, durable-before-
success semantics, and optimistic concurrency. This is a foundation task, not editor polish.

### 4.6 Secrets already exist in portable configuration

Streaming passwords/API credentials are stored in stream `config_json`. RadioReference credentials are currently kept
inside the portable Java Preferences JSON. The web API must never return, log, echo, diff, or include these values in
diagnostic payloads. Credential migration and write-only DTO behavior are required before those screens move.

### 4.7 The database rule is forward-looking

Existing activity/statistics schemas, including their current summary semantics and retention behavior, are
grandfathered for this migration. Their read-only web routes may be carried forward without making a legacy statistics
redesign a prerequisite.

The stricter rule applies to anything newly proposed after this baseline: no new database object or retained data path
may introduce unbounded operational or statistical growth. The planned stable configuration identities, revisions,
alias-list records, admin identity, and typed settings are allowed because they represent bounded current state and do
not append with time. They must not grow a revision-history or audit-history trail.

## 5. Scope and product assumptions

### 5.1 In scope

- One browser application per SDRTrunk process/node.
- Localhost use and explicitly secured remote use over a LAN/private overlay.
- Read-only monitoring, browser audio, radio operator actions, and authenticated radio administration.
- Every present radio workflow, with web-native replacements where a literal port makes no sense.
- A narrow local JavaFX node-administration utility for server/listener settings, non-radio platform-service controls,
  and recovery, plus a documented ownership boundary for certificate import if implemented later.
- First-run setup, upgrades, backups, diagnostics, and web-first radio packaging with the optional local utility.

### 5.2 Not part of this migration unless separately approved

- Rewriting decoder, channelizer, tuner-controller, recorder, or streaming-provider implementations.
- Streaming raw IQ to browsers.
- Persisting every decoded raw message for later browser searches.
- Retrofitting or deleting already-implemented lifetime/statistics schemas solely to satisfy the new forward-looking
  persistence policy.
- A cloud control plane or one UI aggregating multiple receiver nodes.
- An Electron/native browser wrapper. The interface should work in a normal browser.
- Internet-hosted CDN assets or a Node.js runtime on receiver nodes.
- Remote arbitrary filesystem browsing or arbitrary script execution.
- Porting server/listener configuration or certificate/private-key upload into the browser. Those remain local JavaFX
  responsibilities. The implemented fixed-PEM workflow remains deliberately outside browser routes.
- Expanding the local PEM workflow into broad keystore support, automatic renewal, keychain integration, certificate
  history, or a polished certificate-management suite.

### 5.3 Recommended access policy

- Default binding: loopback only.
- Assign every read-only monitoring feature a stable server-enforced access policy: `PUBLIC` or `ADMIN_ONLY`. `PUBLIC`
  permits anonymous and authenticated viewing; `ADMIN_ONLY` requires login but does not disable the feature for the
  administrator. Configuration, hardware mutation, and administrative commands are always authenticated and cannot be
  made public by changing the surrounding page policy.
- Apply the same feature decision to navigation, direct HTTP reads, SSE, WebSocket subscriptions, and audio/media
  delivery. Hiding a navigation item is not authorization. If a policy changes from `PUBLIC` to `ADMIN_ONLY`, terminate
  existing anonymous streams promptly and require login on reconnect.
- Preserve separate policies for status/statistics, Live radio activity, Listen/browser audio, Recordings browse/
  playback, wideband signal, selected-channel signal/symbols, Events, Messages, and other read-only surfaces. Both
  status/statistics and audio default off for anonymous access on new profiles, and enabling one public feature must not
  silently enable another. Wideband signal and the Signal/Symbol actions rendered inside Live are the deliberate
  exceptions: they are permanently `ADMIN_ONLY` and cannot be changed to `PUBLIC`. Public Live still shows the same
  read-only radio rows but omits those actions entirely.
- Persist only the bounded current feature-policy map in `application_settings`; do not create access-history,
  per-viewer, or subscription rows.
- Non-loopback administration: refuse clear-HTTP login and require embedded HTTPS.
- Remote browser administration never includes listener/bind/TLS-material or local platform-service mutation;
  those require the local JavaFX/CLI maintenance path under the node's OS account.
- Initial identity model: exactly one persisted admin credential record, with authorization permissions expressed
  internally as `VIEW`, `OPERATE`, and `ADMIN` so future roles do not require route rewrites. Reset replaces that
  record; it never appends another account or credential-history row.
- The initial admin identity has exactly one bounded in-memory browser session; tabs in that browser share it and a newer
  successful login ends the older session. Radio commands remain serialized and high-rate signal work is independently
  capped. Anonymous listeners never create database identities or durable per-listener history.
- Guarantee and benchmark ten simultaneous remote browser-audio listeners. Ten is an acceptance workload, not
  automatically a permanent product maximum.
- Other low-cost read-only features may receive separately approved bounded subscriber limits. Wideband FFT/waterfall
  instead has one exclusive authenticated-admin workspace per node because its owner controls target, viewport and
  bounded adaptive DSP resolution. The ten-listener workload remains an audio requirement, not a spectrum-viewer
  requirement.
  Enforce node-wide connection, frame-rate, resolution and bandwidth caps with latest-frame/latest-control delivery.
- Browser-local palette, dB floor, immediate visual zoom/pan, pause and layout do not acquire a radio-resource lease.
  The wideband feature does acquire one short-lived in-memory **spectrum workspace slot** so only one authenticated
  admin can select a target and request server-side resolution refinement. This slot stores no durable identity or
  history and is distinct from leases for temporary RF probes or resource-changing tuner commands.
- Future multi-user accounts/RBAC: a later, separately justified schema migration.

### 5.4 Design-first legacy UI reference and mockup gate

Before migration implementation begins, capture the safely reachable Swing and radio-facing JavaFX interface on
BOSGAME and maintain the [legacy UI reference catalog](ui-reference/README.md). Inventory every source-identified
surface. Mark each entry `captured`, `synthetic`, `source-only`, `hardware unavailable`, or `retire`; a missing image is
not completed coverage. Record toolkit/source area, entry path, task and behavior, meaningful state, sensitivity, web
destination, and retirement decision.

The catalog is a loose behavioral/layout reference, not a visual-regression oracle. Preserve radio terminology,
information relationships, units, defaults, status meaning, validation, warnings, and workflow ordering. Adapt desktop
menus, split panes, dense tables, windows, and modals into responsive routes, panels, drawers, progressive disclosure,
and web-native navigation. Do not preserve desktop chrome, fonts, colors, pixel geometry, or control placement merely
for parity.

Capture on BOSGAME only, using the approved profile read-only where safe. Never expose credentials, API keys, stream
endpoints/tokens, RadioReference credentials, encryption keys, or sensitive paths. Missing, destructive,
credential-bearing, provider-specific, or unavailable-hardware states require an isolated BOSGAME synthetic data root
with dummy/local-only data. Never copy a production streaming profile or contact a real streaming destination for UI
capture.

Use the catalog to create annotated low-fidelity public-listener, authenticated-radio-admin, and retained-local-JavaFX
mockups. Each annotation states which legacy behavior is preserved, adapted, combined, or retired. Approve the
applicable catalog evidence and mockup before changing production runtime code, schemas, dependencies, packages, or
receiver-node state for that feature. Cross-cutting foundation work may proceed only when an approved first slice gives
it a concrete contract and its own Phase-0 safety gates pass.

Keep those annotations in the review material, not in the finished interface. Settings pages must not display
retirement summaries, absent-feature explanations, account-model notes, storage-policy notes, or other migration
commentary unless the information directly helps the administrator understand or complete a current action.

The [wideband signal-page mockup](ui-reference/mockups/wideband-signal-page.md) is implementation-approved as of
2026-07-19. Its layout may receive iterative visual polish, but its permanent admin-only policy, exclusive workspace,
responsive information hierarchy, and preserve/adapt/combine/retire decisions are the baseline for Phase 4. The other
required mockups remain open, so this approval authorizes only Phase 0 and the wideband foundation, not those later
feature slices.

The prior public-listener v1 approval is preserved only as a historical completed-call/FIFO baseline. The
[shared Listen and Recordings v2 mockup](ui-reference/mockups/public-listener-recordings-v2.html) and
[Listen-list Settings editor](ui-reference/mockups/settings-shell-listen-lists-v1.html) now supersede it for future
implementation. Both are review drafts. Their design work authorizes no recorded-call table/index, retention cleanup,
audio route, or receiver-node deployment until the §6.7 admission record and their feature approvals are complete.

### 5.5 Minimum receiver target and non-interference invariant

The minimum acceptance workload is a headless Intel N100-class host with 8 GB RAM, excluding browser cost: one trunked
radio system; continuous control-channel and grant decoding; four simultaneous voice calls; recording, activity
tracking, and upload enabled; and ten simultaneous remote audio listeners. The candidate passes only with no USB/sample
loss, missed deterministic grants, audio gaps, incomplete recordings/uploads, decoder stalls, or monotonic heap,
native-memory, thread, socket, listener, or queue growth.

The USB-to-samples-to-decode-to-audio/record/upload path is the protected fast path. Tuner callbacks, channelizers,
control/grant decoders, audio assemblers, recorders, and upload handoff may only make bounded O(1) offers to web-facing
queues. They never wait for HTTP, DNS, RadioResolve or another provider, WebSocket clients, SQLite, serialization,
compression, rendering, authentication, or browser acknowledgements. Every new feature receives paired approved-build
and candidate measurements before its implementation slice can retire legacy code.

## 6. Target backend architecture

### 6.1 Runtime composition

Extract the current constructor body of `gui.SDRTrunk` into renderer-neutral owners such as:

```text
io.github.dsheirer.application
  SdrTrunkServer                 canonical main class at final cutover
  SdrTrunkRuntime                owns initialized services
  RuntimeBuilder                 constructs services in dependency order
  LifecycleManager              start, readiness, shutdown, and failure cleanup

io.github.dsheirer.web
  WebApplicationService         embedded server and route composition
  auth/*                        credential, session, CSRF, and policy services
  api/*                         explicit DTOs, query handlers, command handlers
  live/*                        bounded SSE and WebSocket transport

io.github.dsheirer.application.service
  ChannelConfigurationService
  AliasConfigurationService
  BroadcastConfigurationService
  ListenListConfigurationService
  BrowserAudioPlaybackService       bounded transient Listen-list matching and shared call encoding
  RecordedCallCatalogService        successful retained-recording artifacts only
  RadioReferenceImportService
  TunerRegistry / TunerCommandService
  LiveContextResolver
  LiveHistoryService
  SpectrumStreamService
  SymbolTelemetryService
  ChannelSignalInspectionService
  NodeStatusQueryService              redacted read-only status; web-exportable
  LocalNodeAdministrationService      local-only desired settings/apply/rollback
  PlatformServiceLifecycleService     local-only non-radio service control
  TlsMaterialService                  implemented, local-only fixed-PEM certificate workflow

io.github.dsheirer.nodeadmin
  LocalNodeAdministrationApplication   optional JavaFX adapter/entry point
  ServerSettingsEditor                 listen address, HTTPS and platform-service settings
```

Names may change; ownership must not. The core runtime must be startable and stoppable without importing a `gui`,
Swing, JavaFX, `nodeadmin`, or `source.tuner.ui` class. The optional `nodeadmin` package may depend on narrow immutable
local-node DTOs and mutation commands; the runtime must never depend back on it. The `web` module may import only
`NodeStatusQueryService`, never the local mutation, lifecycle, rollback or TLS-material contracts.

During coexistence:

- `LegacyDesktopApplication` creates Swing/JavaFX adapters around `SdrTrunkRuntime`.
- `LocalNodeAdministrationApplication` is the permanent, explicitly launched JavaFX adapter for the allowlisted local
  node settings; it is not part of `LegacyDesktopApplication` and survives radio-desktop retirement.
- `WebApplicationService` is created by the runtime, not by a panel.
- web and temporary legacy radio views subscribe to the same neutral radio snapshots/commands; the JavaFX node-admin
  adapter receives only node/server/platform-service snapshots and commands;
- only the services own persistence and hardware mutations; and
- a test starts the complete runtime with `java.awt.headless=true`.

Add architecture fences: `nodeadmin` may import only local-node-administration contracts and general presentation
helpers. It may not import tuner, channel, decoder, alias, broadcast/stream, RadioReference, spectrum, recording,
playback, or radio-preference UI/models, and it must not call SQLite/Java Preferences directly. `web` may not import or
reflectively reach local-node mutation contracts. A local JavaFX command and an equivalent CLI/runtime command use the
same validator and typed desired-settings service.

Phase 0 must choose the launch mechanism without violating single ownership: either show the JavaFX window inside an
explicit interactive runtime launch or enter exclusive maintenance mode while the scheduled runtime is stopped. Never
launch a companion JVM against the same portable data root. Offline settings are staged for the next restart; live
service actions are offered only when the JavaFX adapter is attached to the owning runtime.

### 6.2 Query/command separation

Use separate paths for reads and writes:

- Query services return immutable snapshots and bounded pages. They never expose mutable runtime objects.
- Command handlers parse allowlisted DTOs, authenticate, authorize, validate, and submit work.
- An `ApplicationCommandExecutor` serializes configuration-wide changes.
- Per-tuner executors serialize hardware changes without blocking unrelated tuners.
- Long actions return `202 Accepted` with an operation ID; progress and completion arrive through SSE.
- Durable configuration commands commit SQLite before reporting success, then update/publish runtime state.
- When post-commit runtime application can fail, report an explicit terminal state such as `PERSISTED_AND_APPLIED` or
  `PERSISTED_APPLY_FAILED`, retain desired versus effective state, and define safe retry/compensation. Never display a
  generic success when a stream, running channel, or tuner still uses the old effective configuration.
- Runtime actions that cannot be made durable (start/stop/restart/test) publish explicit terminal outcomes.

Use a consistent error envelope:

```json
{
  "code": "REVISION_CONFLICT",
  "message": "This stream was changed by another session.",
  "fieldErrors": {},
  "operationId": null,
  "currentRevision": 17
}
```

Do not include stack traces, SQL, local absolute paths, credentials, or raw provider responses.

### 6.3 API shape

Establish a checked-in `/api/v1` contract before editor work. A representative route map is:

| Area | Representative routes | Notes |
| --- | --- | --- |
| Auth | `POST /auth/login`, `POST /auth/logout`, `GET /auth/session`, `POST /auth/password` | Same-origin session cookie and CSRF token |
| Health | `/health/live`, `/health/ready` | No sensitive paths or configuration |
| Runtime | `/runtime/status`, `/runtime/resources`, `/operations/{id}` | Bounded diagnostic snapshots |
| Live systems | `/live/systems`, `/contexts/{selectionId}` | Port existing stats/live behavior |
| Listen | `/listen/lists`, `/listen/lists/{listId}/calls`, `/listen/lists/{listId}/calls/{callId}/audio` | Bounded transient completed-call delivery matched to the chosen list, independent of legacy Listen/Do Not Monitor and Record settings; no listener rows |
| Recordings | `/recordings`, `/recordings/{callId}`, `/recordings/{callId}/audio` | Keyset-paged metadata and range-capable media; access policy applies to all three |
| Events/messages | `/contexts/{id}/events`, `/contexts/{id}/messages`, SSE delta routes | Snapshot plus sequence-based deltas |
| Signal | `/tuners/{id}/spectrum`, `/contexts/{id}/signal`, `/ws/signal` | REST metadata; binary WebSocket frames |
| Playlist | `/configuration/channels`, `/configuration/channels/{id}` | Revisioned CRUD plus async runtime actions |
| Aliases | `/configuration/alias-lists`, `/configuration/aliases/{id}` | Bulk and reference-impact previews |
| Listen lists | `/configuration/listen-lists`, `/configuration/listen-lists/{id}` | Revisioned current configuration containing stable channel/talkgroup references |
| Streams | `/configuration/streams`, `/configuration/streams/{id}`, `/streams/{id}/test` | Secret-redacted DTOs and live status |
| RadioReference | `/radioreference/browse/*`, `/imports/radioreference/*` | Plan/preview/commit jobs |
| Tuners | `/tuners`, `/tuners/{id}/capabilities`, `/tuners/{id}/configuration`, `/tuners/{id}/commands` | Hardware-specific typed capabilities |
| Radio preferences | `/preferences/radio/*` | Radio-affecting settings only; local node server/service settings have no browser mutation route |

Exact endpoints should follow the API contract, not be inferred from domain class names.

### 6.4 Stable identity and revision strategy

Before enabling writes:

1. Add persistent UUIDs and monotonically increasing revisions for channel, alias-list, alias, and stream aggregates.
2. Keep `radres_guid` as its existing site/source identity.
3. Use aggregate UUIDs in URLs and live events; never use mutable names.
4. Require the revision in `If-Match` or the command body for update/delete/rename.
5. Perform `UPDATE ... WHERE id = ? AND revision = ?`, incrementing the revision atomically.
6. Return the new representation/revision only after commit.
7. Publish a configuration-changed event so other browser sessions invalidate their cached query.

Alias lists need first-class identity rather than deriving identity from the repeated `alias_list_name` value currently
stored on aliases. The migration should introduce one low-cardinality alias-list record per list, with a stable UUID,
revision, unique display-name index, and explicit alias/channel references. It directly serves list/edit/reference-
impact queries, has no event row rate or retention requirement, and prevents a rename from changing aggregate identity.
Apply the same persistent-identity/revision rule before writable tuner-setting and typed radio-preference aggregates
become authoritative; do not treat a mutable display name or deferred JSON snapshot as a concurrency token.
Typed persistence remains UI-neutral: radio settings are web-edited, node server/platform-service settings are edited
locally through JavaFX or protected CLI, and both use services rather than toolkit state.

Classify these additions in the new-work admission ledger as user-owned current configuration: they persist until the
owning entity is deleted, their row counts are bounded by configuration cardinality, and they do not retain prior
revisions or change history.

This requires a configuration schema revision. Follow the database rules:

- update the single new-profile schema creation/validation routine;
- write an explicit one-off external migration under `tools/sqlite-migrations`;
- stop the application and checkpoint/backup the database first;
- prefer a validated clean rewrite into a temporary database over accumulating runtime compatibility branches;
- assign identities deterministically or record a source-to-target mapping;
- run `PRAGMA quick_check`/`integrity_check` and validate row counts/references;
- test representative query plans and volumes; and
- rollback only by restoring both the previous package and its matching pre-migration database.

Do not add `ALTER`, schema repair, or version stamping to normal runtime services or web routes.

### 6.5 DTO rules

- DTOs contain primitives, bounded lists, codes, labels, and explicit units.
- Frequency values are integer hertz; durations/timestamps are integer milliseconds.
- Protocol/provider/device variants use allowlisted discriminators.
- Unknown fields fail for mutation requests unless the API contract explicitly permits them.
- Domain objects containing JavaFX properties, AWT objects, listeners, controllers, or credentials are never directly
  serialized.
- Secret response fields are represented only by `configured: true/false` and optional `lastChangedAt`.
- In a PATCH, omitted secret means unchanged; an explicit dedicated clear operation removes it.
- Browser display formatting never becomes the stored canonical value.
- Existing unknown/unsupported decoder, source, stream, or alias-identifier variants remain losslessly readable and
  exportable. Show an explicit unsupported state with safe delete/export options; do not silently drop or rewrite them
  during migration. Mutation stays disabled until a typed validator exists.

### 6.6 Forward-only database admission and retention policy

This policy governs new persistence introduced by this migration. It does not require changing existing database
tables, counters, summary semantics, or retention behavior.

Apply it together with [the SQLite activity-database guidelines](sqlite-activity-database-guidelines.md). The admission
record below adds a forward-only migration gate; it does not relax startup-schema or external-migration rules.

For new work, this section is stricter than the existing activity-database guideline's allowance for lifetime totals:
that allowance grandfathers already-implemented totals but cannot justify a new lifetime counter, column, table, or
retained relationship.

Classify every newly proposed database object or retained record path before implementation:

| Classification | Rule for new work |
| --- | --- |
| User-owned current configuration | May persist until explicitly deleted. Cardinality is bounded by configured channels, aliases, lists, maps, streams, icons, tuners, accounts, or typed settings. Store the current revision only, not revision history. |
| Current runtime/discovered state | At most one current/recent row per bounded entity. Observed entities require a finite stale-state TTL, a hard cardinality cap, and automatic cleanup. |
| Rolling statistic/aggregate | Allowed only for a named interface query, with a finite default and hard-maximum retention plus automatic pruning. Reuse only an existing bucket/summary whose retention semantics are compatible; a grandfathered lifetime summary may be read unchanged but cannot receive a new lifetime counter or record category. |
| Detailed event history | The only acceptable new row-per-event history. It must be explicitly named, opt-in, disabled by default, finite-retention, size/row capped, and automatically pruned. |
| Transient web state | Never SQLite. Keep sessions, CSRF state, login throttles, operation/job results, RadioReference browse caches/import previews, SSE replay, live Events/Messages, FFT, waterfall, symbols, filters, and layouts in bounded memory or browser-local state with TTLs. |
| Managed artifact | Keep only current owner-referenced files plus an explicitly bounded number of rollback generations. Apply per-file, per-owner, count, and total-byte caps; delete staging files on success/cancel/timeout/restart and delete managed files with their owner. |
| Audit/diagnostic output | Bounded size-and-time-rotated files or temporary artifacts, never an append-only SQLite audit table. |

Current-baseline exception discovered during the 2026-07-19 BOSGAME interactive deployment: the legacy application
logger is time-rotated but has no per-file or total-byte cap, and the isolated canary root contains a 1.67 GB active
log accumulated by earlier builds. Do not silently delete that evidence. Before production web-first release, add a
separately reviewed size-and-time policy, a total-byte cap, and an explicit migration/cleanup decision for an already
oversized active file. This is a non-SQL bounded-storage gate, not justification for a new database audit table.

Every new table, column, index, view, trigger, persistent write category, or SQL/non-SQL retained record path needs a
checked-in admission record containing:

1. The exact runtime or web query it serves.
2. Why an existing current-state row, summary, or bucket cannot serve it.
3. Expected rows per hour, worst-case cardinality, maximum retained rows, and estimated table-plus-index bytes.
4. Default and hard-maximum retention or, for configuration, the user/entity deletion rule.
5. Automatic cleanup owner, trigger, batch behavior, disable/restart behavior, and indexed deletion path.
6. Representative-volume `EXPLAIN QUERY PLAN`, query-latency, write-amplification, and BOSGAME size evidence.

Phase 0 checks in a machine-readable baseline manifest/hash of existing tables, columns, indexes, views/triggers, and
known persistent write categories. CI compares the generated schema and persistence ledger to that baseline so an
addition to a grandfathered `CREATE TABLE` or write path cannot be mislabeled as legacy.

For any new temporal schema, collection and deletion are separate responsibilities: turning collection off must not
stop expiration, reducing retention must schedule cleanup, and restart must catch up missed cleanup. Use bounded,
indexed delete batches outside decoder/tuner threads. A feature disabled from first startup must create no
feature-owned temporal, cache, job, or audit rows. An explicit offline migration may still seed required current
configuration identities or revisions even while its web editor is disabled.

Do not add SQLite tables for operation history, import history, admin audit history, raw messages, signal telemetry,
stream-test attempts, tuner telemetry, or browser state. Any exception requires an explicit product decision naming it
as detailed event history and documenting finite retention. CI/review should reject an unclassified new persistence
path. Only unchanged baseline objects and write behavior are excluded from this new-work admission test. A new
column/index/view/trigger or write category on a grandfathered table is still new work and requires admission, even when
the table's prior rows and retention semantics remain grandfathered.

### 6.7 Recorded-call catalog and retention admission

The Recordings page is an explicit media-library feature, not a statistics or generic event-history loophole. It may
introduce one compact catalog entry for each retained recording only because that entry is the lookup metadata for one
current managed audio artifact. A catalog entry must be deleted with its audio and cannot outlive the configured
**Recorded call retention** period. Detailed Event History and activity/statistics retention remain separate and do not
keep recording metadata alive.

Catalog admission begins only after the call's destination talkgroup matched an Alias whose **Record** setting was
enabled at call time and `AudioRecordingManager` successfully wrote a non-empty managed audio file. The recorder should
publish an immutable `RecordedCallArtifact` containing the resulting
managed path, format, byte size, call snapshot, completion time, and duration through a bounded non-blocking handoff.
Neither the generic completed-call web feed nor a pre-write `recordAudio` flag may create a catalog row. A failed/full-
disk write creates no searchable call. Recordings filter choices are derived from the retained catalog, so a talkgroup
with no retained Record-enabled calls is absent without a permanent talkgroup-statistics row. Turning Record off affects
future calls; already-retained calls remain searchable until the normal recorded-call retention removes them. Before
implementation, characterize the current behavior where a Record-enabled Radio ID or other non-talkgroup Alias can set
the call-wide record flag. Such a trigger alone must not admit the call to the Recordings catalog unless the destination
talkgroup itself was Record-enabled.

Before any schema is written, check in a dedicated persistence-admission record and one-off external migration covering:

1. Exact queries: newest/oldest retained calls filtered by system, site, talkgroup, radio, channel, receiver-local time
   range, and inclusive duration; batch resolution of guest-playlist IDs; and one call-to-audio lookup.
2. Storage shape: one physical audio file in the fixed §6.8 hierarchy; compact numeric/stable identity and directory-
   bucket references in each call row, integer timestamps/durations/status codes, and no repeated full path, repeated
   high-cardinality catalog names, JSON call payload, raw message, hard links, or duplicate audio copies.
3. Scale evidence: measure representative and peak completion rates first, including ten concurrent calls and bursts of
   0.25-second calls; publish expected rows/hour, retention-derived maximum rows/bytes, catalog/index write
   amplification, and representative 1M/5M/10M-row size and latency results. The schema remains unapproved until those
   numbers, the default retention, and the hard maximum retention are explicit.
4. Query shape: opaque keyset pagination ordered by completion timestamp plus immutable call ID, 50 rows by default,
   bounded batch ID lookup, index-backed `EXPLAIN QUERY PLAN`, no offset/page-number scans, and no exact-million total
   count in normal page loads.
5. Expiration: bounded indexed delete batches on a dedicated low-priority maintenance executor, independent of whether
   new recording is enabled, catch-up after restart, immediate scheduling when retention is reduced, and deletion of
   both catalog row and audio artifact without running on tuner, decoder, audio assembly, recording, playback, upload,
   provider, or HTTP request threads.
6. Failure behavior: successful recording-file completion makes only a bounded non-blocking handoff to catalog work. A full or failed
   catalog queue never delays or loses decoded/recorded audio; it increments a bounded current health counter and a
   separately scheduled bounded reconciliation pass can discover an uncataloged managed recording later.

The new Storage setting uses a fixed days unit and a finite range. A shared playlist, playback queue, open browser, or
admin bookmark does not extend retention. Lowering retention requires an impact confirmation and cleanup progress, but
the cleanup job keeps no history row.

### 6.8 Fixed recorded-call directory convention

The directory convention below the configured recordings root is an application contract, not a setting. Do not add a
template field, folder-order control, token expander, per-provider override, or alternate layout. Version the convention
in code so later software can recognize it, but keep `v1` immutable once released:

```text
recordings/calls/v1/YYYY/MM/DD/
  <system-name>~<system-id>/
    <site-name>~<site-id>/
      <channel-name>~<channel-uuid>/
        <talkgroup-id>-<talkgroup-name>~<matcher-id>/
          <UTC-timestamp>-<call-id>.<configured-audio-extension>
```

Use one canonical file only. The hierarchy is date first for bounded retention cleanup, followed by system, site,
channel, and destination talkgroup so an administrator can browse it directly. Conventional or genuinely absent values
use fixed `_conventional`/`_unknown` components. Radio IDs remain metadata and filename/catalog data rather than
high-cardinality directories. The base recordings root and existing audio format remain their separate node/radio
settings; the hierarchy underneath the root cannot be changed.

Each human-readable component is a bounded cross-platform-safe slug plus a stable short identity suffix. Reject path
separators, Windows reserved names, control characters, trailing dots/spaces, `.`/`..`, overlong segments, symlink
escapes, and case-fold collisions. A renamed System, Site, Channel, or Alias affects only future files; never move or
recompute an existing path from current names.

Do not repeat the full relative path in every catalog row. The persistence admission should use one compact directory-
bucket record for each unique date/system/site/channel/talkgroup path and let calls store its integer ID plus the data
already needed to derive the fixed filename. The bucket stores the validated relative directory once, is created only
when its first successful recording is cataloged, and is removed after its final call expires. Measure its expected row
rate and indexes with the call catalog; it is retention-bound managed-artifact metadata, not permanent configuration or
history.

Write to a bounded staging filename inside the final date bucket, finish and close the audio file, atomically rename it
to the canonical final name, and only then emit `RecordedCallArtifact` for catalog admission. Cleanup and reconciliation
operate only inside the recognized `calls/v1` root, work in bounded date/index batches, and delete neither an arbitrary
path nor an unrecognized file. Existing legacy recordings are never silently moved or renamed at startup; any optional
legacy import/reorganization requires a backed-up explicit one-off migration with dry-run and rollback.

This writer, path validator, collision behavior, cleanup boundary, and restart reconciliation must pass before the
Recordings catalog/API is considered functional.

## 7. Authentication and administration

### 7.1 Initial admin account

The single-admin first release uses one current-value record in the existing `application_settings` table without
adding an authentication schema:

- Store exactly one versioned `web.auth.v1` record containing normalized username, salted password hash, algorithm
  parameters, password-change time, and an auth-generation counter. Setup/reset atomically replaces this current value;
  it never creates additional accounts or password-history rows.
- Use a Java-standard, cross-platform password KDF such as PBKDF2-HMAC-SHA-256 with a random salt and reviewed work
  factor. Keep the hasher behind an interface so a later Argon2id migration is possible after all target packages are
  validated.
- Compare derived values in constant time.
- Never store a recoverable admin password.
- Store no session in SQLite. Restarting the application logs all sessions out.
- Allow exactly one active administrator browser session for the receiver, held only in bounded memory. Tabs in that
  browser share the session; a newer successful browser login invalidates the older session so an abandoned browser
  cannot lock out the administrator. Spectrum and selected-channel signal views each retain their own one-connection
  runtime slot within that single session.

Create/reset the initial credential through the local JavaFX bootstrap/recovery path or protected CLI; normal password
change and session management remain in the authenticated web admin interface.

The implementation must receive a focused security review before LAN administration is enabled.

### 7.2 First-run and recovery

Current headless startup requires `--fresh` or `--import-xml` when no database exists. Retaining local JavaFX makes a
temporary browser setup server unnecessary as the primary interactive path:

1. If no database exists, the normal unattended launch does not start tuners/decoders or an unauthenticated LAN server;
   it enters a distinct `MAINTENANCE_REQUIRED` state with a clear local recovery instruction.
2. On watchdog-managed receiver nodes, disable the scheduled task for planned bootstrap. If state is lost unexpectedly,
   the launcher must recognize `MAINTENANCE_REQUIRED` and keep one inert local process/status under `IgnoreNew` (or use
   another explicitly tested non-repeating hold) instead of producing a one-minute restart/log storm.
3. After the task is disabled and the prior Java process is confirmed gone, an explicit local
   `--server-admin-ui`/maintenance launch acquires the exclusive data-root lock. It offers Start Fresh or whole-profile
   local-file-picker XML import, listener/service defaults, and initial browser-admin creation. It never selectively
   edits imported radio configuration.
4. Invoke the existing atomic database bootstrap path and validate the result.
5. During coexistence with the legacy preferences record, install `SqlitePreferencesFactory` before constructing
   `UserPreferences` and preserve the current voice-vault/bootstrap order. After Phase 14 migrates the final fields,
   bootstrap typed settings services directly and omit those legacy components.
6. Create and persist the browser admin identity only after `application_settings` exists.
7. Close maintenance mode, release the exclusive data-root lock, and start the normal runtime through its canonical
   launcher/task.

Current transition slice: `--server-admin-ui` opens a focused JavaFX web-server/account editor after the existing
database bootstrap/validation and portable-preferences install. It constructs no tuner, decoder, audio, recording,
streaming, or embedded-web runtime service. Normal runtime and this utility share an OS file lock under the exact
portable data root, so a second process fails before touching SQLite. The full Phase-14 node/service/certificate
utility, watchdog `MAINTENANCE_REQUIRED` behavior, typed node-settings cutover, and protected CLI fallback remain
future work. This transition slice is packaged and deployed on BOSGAME: the real JavaFX window opened and closed in the
interactive Owner session with zero listening sockets, the normal headless candidate resumed afterward, and a
concurrent exact-root launch was rejected without disturbing the owner process. That smoke did not create an
administrator credential and does not count as authenticated physical Airspy/RTL signal testing.

Extract neutral bootstrap operations from `SdrTrunkDatabaseBootstrap`; the JavaFX window is only a thin local adapter
and must not embed persistence logic. Preserve voice-vault creation/validation and atomic cleanup behavior. Keep
`--fresh`, `--import-xml <path>`, non-secret owner-readable configuration input, and protected admin-reset commands for
unattended/headless nodes. Passwords and keystore passphrases use an interactive local console/stdin only; do not put
them in argv, persistent provision files, backups, logs, or generic settings. Any future unattended secret source needs
a separately reviewed OS credential-store design and explicit lifecycle. A future optional loopback setup route would
require the full setup-code, Host/Origin, body-limit, and DNS-rebinding threat model; it is not required for this
migration.

### 7.3 Sessions and request protection

- Generate random 256-bit opaque session IDs.
- Store sessions in a bounded in-memory map with idle and absolute expiration.
- Rotate IDs at login and invalidate all sessions when the password/auth generation changes.
- Cookies are `HttpOnly`, `SameSite=Strict`, limited to the application path, and `Secure` under HTTPS.
- Every mutation requires a per-session CSRF token in a custom header and a valid same-origin `Origin`.
- Validate `Origin`, feature policy, connection limits, and requested target during every WebSocket handshake and
  subscription. Require a valid session when the feature is `ADMIN_ONLY`; when it is `PUBLIC`, admit a bounded anonymous
  read-only subscription without granting any command or mutation capability.
- Rate-limit login by normalized username and source address with bounded memory and generic failures.
- Limit request bodies, form field lengths, JSON depth, uploads, WebSocket frames, connections, and sessions.
- Add CSP, `frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, strict referrer policy, and no-store headers for
  authenticated/secret responses.
- Escape all displayed provider responses, decoded text, alias names, and log content.
- Do not trust `X-Forwarded-*`; this embedded-listener design has no trusted-proxy mode.

Treat the current OWASP [Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
and [WebSocket Security](https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html) cheat sheets
as required review inputs, not as a substitute for the application-specific threat model and tests.

Write web authentication/radio mutations and local JavaFX/CLI node mutations to a bounded, rotated structured
application log, not a new high-rate SQLite activity table. Record time, action, affected entity UUID or node-setting
key, outcome, surface, and redacted browser-session/source or local-OS actor metadata when safely available. Never
record credentials, full session IDs, provider payloads, decoded-call content, unrestricted request bodies, private-key
material, or secret file paths. Define retention, download/redaction, and disk-full behavior before enabling remote
administration.

### 7.4 Network transport

- Loopback HTTP may be used for local operation.
- Do not send admin credentials or session cookies over clear LAN HTTP.
- Network scope is defined only by one listen-address text value, default `127.0.0.1:8090`. Exact LAN, wildcard, or
  Tailscale addresses are ordinary bind addresses; there are no separate LAN/Tailscale modes or source-range filters.
- HTTPS uses the same one connector and port. The local JavaFX utility generates or imports the fixed
  `<data>/security/tls/certificate.pem` and `private-key.pem` files; there is no browser certificate-upload route.
- Listener address and HTTPS-mode changes are local JavaFX operations and take effect on the next normal start. A bad
  bind or invalid/missing TLS pair disables only the web listener; it must not stop radio reception or streaming.
- If the current anonymous stats console remains available over LAN HTTP, isolate it as explicit read-only policy;
  admin and command routes still refuse insecure non-loopback use.

### 7.5 Local JavaFX node-administration boundary

The retained JavaFX utility is authorized through the local OS account/session, not through a browser session. It is
never remotely served. Restrict the maintenance launcher, portable data root, node-settings files and recovery inputs
to the owning account or an explicit local-administrator group; refuse privileged mode when ownership/permissions are
unsafe. Console/RDP access is an OS-level administrative path and must be documented and audited as such.

Its mutation allowlist is intentionally narrow:

- embedded web-server enable/start/stop, one listen address, and HTTP/HTTPS mode;
- explicitly classified non-radio platform-service enablement, autostart, desired settings, effective status and
  lifecycle;
- allowlisted managed storage-root definition and node-local path changes, without exposing arbitrary paths to the web;
- browser-admin reset and node recovery;
- whole-profile offline bootstrap/XML import/restore only while no runtime/database owner exists—never selective radio
  editing; and
- local self-signed certificate generation and PEM certificate/private-key import.

It may not configure statistics/activity collection, tuners, decoders, channels, aliases, recorders, playback/audio,
streaming/broadcast providers, RadioReference, JMBE/voice behavior, or any other RF-derived workflow. Those remain web
radio functions. Browser routes may expose redacted read-only node status but no local-only mutation.

JavaFX settings call neutral validation and listener lifecycle services. Persist only bounded current **desired**
configuration in existing typed `application_settings`; do not create settings-revision or certificate-history data.
Recompute effective/running/readiness state from the owning runtime
after every start and keep it in memory; never persist it as a second truth. Do not add revision history,
service-control history, or a new audit table. Privileged actions go to the bounded rotated redacted log. A web-listener
restart should leave radio decoding running; whole-process restart is a separate high-friction, watchdog-aware action.

The implemented workflow is intentionally Apache-like and small. It stores only `certificate.pem` and
`private-key.pem` under the portable security folder, never SQLite or Java Preferences. Imports are local, bounded,
parsed, normalized, staged, and atomically replaced; the complete pair is cryptographically matched before runtime
use. The private key must be unencrypted PKCS#8 so headless startup needs no stored password. Self-signed generation
uses RSA-2048/SHA-256 and explicit DNS/IP SANs. There is no renewal service, certificate history, browser upload,
platform keychain, retained rollback generation, trust tutorial, or certificate-warning system.

### 7.6 Web admin interface areas

The authenticated web navigation should eventually contain:

- Security: browser-admin password and active sessions, plus read-only network exposure and TLS status.
- Runtime: health, resource usage, listeners, dropped live frames/events, command queue, and database status; listener
  and local platform-service mutations are absent.
- Logs: admin-only bounded live tail plus view/download of the existing current and ten-day rotating application log
  files; do not copy log lines into SQLite or create a second log archive.
- Configuration: channels, aliases with built-in vector icons, Listen lists, streams, and RadioReference. Named MPT
  Channel Maps and the legacy raster Icon Manager are retired.
- Tuners: hardware inventory, status, settings, restart/enable/disable, recordings.
- Storage: read-only managed-directory status, separate recorded-call retention, grandfathered-versus-new schema
  inventory, new-schema budgets/retention, downloadable radio/data export, radio/activity maintenance, and safe radio-artifact
  downloads/uploads. Whole-node backup/restore, XML bootstrap import, and host-path mutation remain offline JavaFX/CLI
  operations with exclusive ownership.
- Modules: JMBE, optional modules, and voice-key vault state.
- About/support: version, What's New, credits/licenses, diagnostic bundle and bug report.

Administration does not imply arbitrary filesystem access or shell execution.

## 8. Frontend architecture

Create a build-time-only project such as:

```text
web-ui/
  package.json
  package-lock.json
  vite.config.ts
  src/
    app/                  router, authentication, query client, error boundaries
    api/                  generated/checked contract types and transport
    components/           accessible controls, tables, dialogs, status elements
    features/
      live/
      listen/
      recordings/
      signal/
      playlist/
      aliases/
      streams/
      radioreference/
      tuners/
      radio_preferences/
    workers/              FFT frame decode, smoothing, waterfall ring buffer
  tests/
```

Recommended principles:

- Same-origin requests only; no CORS is needed for production.
- Route-based code splitting so opening Live does not load every editor.
- A typed API client and one query cache; live events invalidate/update cached snapshots.
- Error boundaries around each workspace, with reconnect and stale-data state visible.
- Virtualized tables for channels, aliases, events, and messages.
- One shared accessible `FieldHelp` component beside every Settings field or field group. It uses a local vector
  circled-information icon and bundled plain-language copy; opening help never performs a web request, database write,
  log entry, or radio-runtime action.
- Browser-local storage for presentation-only state: theme, column widths, last route, panel layout, FFT palette, zoom,
  averaging, pause, selected Listen list, bounded playback queue/history, Follow state, recording filters, and an
  in-progress guest playlist. FFT-window and smoothing values use built-in defaults and are not exposed as settings.
- Web-edited radio settings for DFT resource caps, history bounds, channel configuration, tuner controls,
  recording/streaming, radio statistics and retention. “Server-side persistence” does not mean every setting has a web
  editor; node listener/TLS/platform-service settings remain local JavaFX/CLI mutations.
- Canvas/WebGL and a Web Worker for signal views. Do not create thousands of DOM/SVG nodes per frame.
- No early service worker/PWA cache; fingerprinted assets and normal HTTP caching reduce stale-UI/version mismatch risk.
- An API/build version handshake that forces a clean reload when frontend and backend contracts differ.
- All assets packaged locally; no CDN dependency.

### 8.1 Frontend development modes and Java rebuild boundary

Support three explicit modes without creating three product architectures:

1. **Mock/fixture UI mode.** Run Vite against versioned recorded or synthetic API, SSE, and signal-frame fixtures. This
   mode needs no JVM, tuner, receiver node, or live credentials. It is the fastest loop for layout, responsive states,
   accessibility, component tests, worker decoding, and deterministic reconnect/degraded/error cases; it cannot be used
   as hardware, radio-continuity, authentication-integration, or performance evidence.
2. **BOSGAME live-proxy mode.** Keep the approved Java backend running on BOSGAME and use the Vite development server
   with HMR as the browser origin. Its development-only proxy forwards `/api/*` HTTP/SSE traffic and WebSocket upgrades
   for `/api/v1/ws/*` to BOSGAME, so production CORS remains disabled. Frontend-only TypeScript, CSS, layout, and worker
   changes reload without rebuilding or restarting Java. Bind the development server narrowly, use only approved test
   credentials/data roots, and never turn this proxy into a deployed receiver path.
3. **Packaged release mode.** Gradle/CI builds the optimized, fingerprinted SPA and embeds it in SDRTrunk's runtime
   image. The application serves those same-origin assets from the single production listener; Node.js, Vite, fixture
   servers, source maps containing sensitive paths, and development proxies are absent from the receiver runtime.

During development, a frontend-only change against an unchanged checked contract does not require a Java rebuild in
mock/fixture or BOSGAME live-proxy mode. Backend routes or DTOs, API contracts, authentication/authorization and public-
access enforcement, session/WebSocket handshake behavior, binary wire protocol or encoding, and radio/runtime service
changes require a Java rebuild/restart and matching contract/fixture updates. Producing a release candidate always runs
the complete frontend build and Java packaging pipeline even when the source change was frontend-only; that packaging
verification does not redefine the development rebuild boundary.

### 8.2 Build/package integration

Build/package integration is part of the platform work, not cleanup:

- Pin the Node toolchain and lockfile; invoke the production SPA build from Gradle and CI, while keeping Node out of the
  packaged runtime.
- Replace the current `stats-web` distribution copy and `stageMacWebAssets` flow with one fingerprinted web bundle and
  include frontend dependency licenses in NOTICE/license output.
- Retire `StatsWebPath`, production asset-root overrides, and install-directory asset creation after compatibility
  cutover; preserve an explicit development-only asset override.
- Update the application main class, `module-info.java`, `compileModules`, `runtimeModules`, jlink/package tasks, and
  eventually remove `jdk.httpserver`.
- Preserve release-note approval gates and change both GitHub workflows from the stale `master` target to canonical
  `main` before those workflows become migration gates.

## 9. Live signal, Events, and Messages migration

This is the first user-visible feature slice after the runtime/web foundation.

### 9.1 Wideband tuner FFT and waterfall

Use the implementation-approved
[wideband signal-page mockup](ui-reference/mockups/wideband-signal-page.md) as the Phase-4 information architecture,
access-state, responsive, and preserve/adapt/combine/retire baseline. Visual polish remains iterative.

Current desktop ownership:

- `spectrum/SpectralDisplayPanel.java`
- `SpectrumPanel.java`
- `WaterfallPanel.java`
- `OverlayPanel.java`
- `SpectrumFrame.java`
- `source/tuner/ui/TunerSpectralDisplayManager.java`
- `spectrum/menu/*`

Reusable headless pieces include `ComplexDftProcessor`, `NativeBufferManager`, DFT-size/window definitions, converters,
and native sample providers. Renderer, menu, mouse, AWT color model, split-pane, and window behavior should not be reused.

The foundation hardened `ComplexDftProcessor` disposal so its owned executor cancels, shuts down, and joins
deterministically. The interactive revision adds latest-only resize requests and target generations without clearing a
producer-owned USB buffer from another thread. The real-tuner adapter exercises that lifecycle when the owner leaves.
Packaged BOSGAME validation must still prove target switching, rapid viewport changes, disconnect during refinement,
and empty/stale-input behavior without touching the protected radio path. Packaging/deployment alone is not live-
hardware credit. The sole admin credential is now locally provisioned, but the candidate still needs the authenticated
browser and physical hardware gate.

Implement `SpectrumStreamService` with these rules:

- One exclusive authenticated-admin owner, one selected target and one FFT producer per node. Switching target fully
  detaches the web FFT from the old target before attaching to the new one; never run two web tuner FFTs concurrently.
- Tuner/sample callbacks perform only O(1), non-blocking enqueue work.
- Start computation when the authenticated owner subscribes; detach when it closes or its short reconnect grace
  expires.
- Bound FFT size to the implemented 4,096/8,192/16,384/32,768 tiers, requested frame rate and encoded bandwidth.
- Derive the tier from the visible viewport, crop to at most 4,096 contiguous bins before transmission and allow a lower
  frame rate at deeper zoom when required by the radio-workload budget.
- Give the owner one latest-frame slot and the control plane one latest-request slot. Rapid wheel/drag requests are
  coalesced and never delay the producer.
- Define a normative compact binary header: protocol version, target ID/generation, sequence, timestamp clock/meaning,
  center frequency, sample rate, bin count, byte order, encoding, bin/DC ordering, frequency formula, window/gain and dB
  normalization convention, quantization scale/offset, and reset/gap/stale flags.
- Benchmark float32 against visually acceptable int16/int8 quantization before locking the wire format.
- Send only the contiguous bins for the acknowledged visible viewport; never send the higher-resolution whole-span
  array merely because deeper zoom is active.
- Publish dropped-frame, encoded-byte, subscriber, and DSP-time metrics locally.

Use one authenticated, access-checked signal WebSocket for the owning page/worker. A second authenticated browser gets
a non-identifying occupied-workspace response and starts no DSP work; anonymous handshakes never receive target or
frequency metadata. The client sends small schema-validated `subscribe`/`update`/`unsubscribe` controls with a monotonic
request ID, stable target ID and bounded viewport. The server acknowledges refining/live state and binary frames carry
target/view generation, full tuner center/sample rate, FFT size, first transmitted bin and cropped bin count. Context
or viewport switches replace the prior generation, stale work is discarded, hidden/closed pages release the slot, and
short in-memory TTL cleanup covers an unclean disconnect. These are resource-bounded visualization controls, never
tuner retune/gain/sample-rate commands.

Classify every existing spectrum control before UI parity sign-off:

- server caps: one owner connection plus maximum FFT bins, frame rate, and bandwidth;
- owner requests: target and viewport within the cap; the server chooses FFT tier and safe effective frame rate;
- browser-local presentation: palette, averaging, dB floor, immediate zoom/pan transform, pause and layout; smoothing
  uses a built-in default rather than an exposed control;
- operator commands: tuner retune/disable and View/Edit Channel deep links through authenticated services; resource-
  changing commands use their command-specific lock/lease rules and never travel over the read-only signal socket.

The channel overlay DTO must provide stable channel/deep-link ID, label, configured frequency/range/bandwidth, and
enabled/processing/selected state. Preserve All/Enabled/None overlay filtering in browser-local state.

The browser:

- draws the current FFT line from a typed array;
- keeps waterfall history in a circular texture/ring buffer;
- performs palette mapping, adjustable shared dB floor, labeled dB grid, pause, cursor labels, built-in smoothing, and
  optional averaging locally;
- anchors wheel zoom under the pointer and supports click-drag horizontal panning on either plot;
- immediately crops/stretches existing waterfall pixels with smoothing so old history is visibly blurry, then appends
  sharp rows only after the newest refined viewport is acknowledged;
- reports page visibility/unsubscribe so hidden tabs stop server work; and
- overlays center/sample-rate/channel metadata from low-rate snapshots.

Do not send images or the entire waterfall history per update. The current desktop waterfall shifts roughly 2.9 MB for
each 4096-bin row; the web implementation should add only one row to a ring buffer. Golden characterization must decide
whether to preserve or intentionally correct the current waterfall averaging expression that sums `length - 1` bins but
divides/subtracts as `sum / length - 1`; do not turn an accidental formula change into an undocumented visual change.

### 9.2 Selected-channel FFT and symbol graph

The interactive [selected-channel diagnostics mockup](ui-reference/mockups/selected-channel-diagnostics-v1.html) is the
design-approved 2026-07-21 baseline for this workspace. It follows the existing Live selection, opens Signal or Symbols in the same
administrator-only bounded workspace, and includes capability-specific NBFM squelch and inactive-frequency RF-probe
states. Implementation has not begun.

This is a channel FFT, not another tuner-wide waterfall. Relabel the legacy `Noise Floor` display-scale spinner as
**Lower display limit** so it is not mistaken for an RF measurement. P25, DMR, and NXDN use the bounded Symbols panel;
NBFM uses its audio-squelch state, threshold, override/reset, and short history panel; inactive RF probes and decoders
without feedback telemetry explain why Symbols is unavailable. Only the visible Signal or Symbols panel produces its
high-rate stream.

Current ownership is `gui/channel/ChannelSpectrumPanel.java` and `FrequencyOverlayPanel.java`, with an embedded JavaFX
`gui/symbol/SymbolView`, `gui/power/SignalPowerView`, and `gui/squelch/NoiseSquelchView`.

Keep **View Signal** and **View Symbols** on the existing Live selection, but render both only for the signed-in
administrator. Public Live shows the same read-only activity rows and neither diagnostic action. Do not create a second
active-channel list inside Settings. Both actions open the same bounded selected-channel diagnostic workspace on the
appropriate panel. Exactly one such workspace may be open for the receiver; opening another target replaces or is
refused by that single bounded workspace policy.

Add:

- `LiveContextResolver`: resolves a stable browser selection ID into owner channel, frequency, timeslot, site scope,
  and current processing chain.
- `ChannelSignalInspectionService`: owns temporary inactive-frequency RF probes with authentication, a strict
  concurrent probe limit, bounded in-memory leases, TTL, reference counting, and disconnect cleanup. The lease covers
  the RF resource allocation, not bounded readers of an already-running context.
- `ChannelMeasurementControlService`: publishes low-rate carrier-offset/viewed-frequency, current/peak power, FFT noise
  floor/scale, noise/hysteresis history, squelch thresholds/state, and Inspect RF state; it serializes authorized
  threshold +/-/auto-track, squelch override/reset, and inspection commands instead of mutating decoder configuration
  or sending `SourceEvent` directly from a view.
- `SymbolTelemetryService`: batches/decimates decoder symbols into a bounded queue and never runs network work on a
  demodulator thread.

`FeedbackDecoder` currently supports one synchronous symbol listener. Replace or augment it with a neutral multicast
telemetry tap. With no subscribers, it must do no batching/render work. The browser renders a bounded circular point
buffer on Canvas/WebGL.

Selection must include timeslot. The current processing-chain lookup can be ambiguous for same-frequency TDMA traffic;
fix and test that before the web view is authoritative.

Selection begins as browser/tab state but acquires the receiver's one selected-channel signal slot before telemetry
starts. Each `ChannelActivitySnapshot` row must carry a stable selection key plus explicit scope, owner, channel,
frequency, and timeslot fields. The server resolves it to an internal `SelectedFrequencyContext` handle; that live
object is never serialized. Merely reading an already-running context needs no RF lease; only an inactive-frequency RF
probe or resource-changing command acquires its specific bounded lease.

Define persistence separately for each signal/squelch control: browser scale is local presentation state, live override
is a runtime command, and a threshold/configuration edit is a revisioned durable channel change. A parity fixture must
account for every current power/squelch/inspection field before those views are deleted.

### 9.3 Events

The interactive [Events and Messages mockup](ui-reference/mockups/events-messages-v1.html) is the design-approved
2026-07-21 baseline
for Sections 9.3-9.5. It demonstrates site-wide Events, exact control/traffic-chain Messages, bounded fast-burst
inspection, browser-local filters/Clear/history limits, and reconnect/replay/resnapshot states. Implementation has not
begun.

Preserve the current semantics from `DecodeEventPanel`:

- selecting a site/control row shows site-wide grant events even when each event carries its traffic frequency;
- selecting an exact traffic/conventional context filters by frequency/timeslot;
- a temporary owner-chain/control-channel gap keeps visible site history and rebinds to the replacement chain; and
- mutable events update their existing browser row rather than becoming duplicates.

Introduce explicit event DTOs whose external identity is unique across a context and processing-chain replacement, for
example `(contextId, chainGeneration, chainLocalEventId)`. Include the generation in snapshots/replay; a new chain can
never overwrite preserved rows from the old generation. Keep the mutable-event identity map bounded and evict it with
history. Include raw identifier values and resolved alias/icon/color fields separately. Do not serialize
`IDecodeEvent` implementations.

The desktop implementation currently remembers a selected TDMA timeslot but filters Events only by frequency. The web
contract intentionally corrects that ambiguity: exact TDMA selections match both frequency and timeslot, while a
site/control selection remains site-wide.

### 9.4 Messages

Preserve `MessageActivityPanel`/`MessageActivityModel` behavior:

- exact processing-chain selection;
- identity deduplication;
- filtering out `StuffBitsMessage`;
- protocol-validity/timeslot filtering; and
- bounded history.

Normalize message text off decoder threads and return an explicit DTO with sequence, timestamp, valid flag, protocol,
timeslot, normalized text, and selected identifiers. Do not persist raw messages merely to support the tab.

The desktop processing-chain lookup and Message filter currently remember but do not enforce the selected TDMA
timeslot. The web route must match both frequency and timeslot. A site selection follows its one control processing
chain and is labeled as control-channel Messages; it is not presented as a site-wide aggregation.

### 9.5 Snapshot/delta contract

Events and Messages should use:

1. a bounded REST snapshot or initial SSE snapshot;
2. monotonically increasing per-context sequence IDs;
3. `upsert`, `remove`, and reset/gap events;
4. SSE `Last-Event-ID` replay from a small bounded ring when possible; and
5. an explicit resnapshot response when replay is no longer available.

Preserve the current filter editor, Clear action, and 0-2000 visible-row retention control as per-browser state. The
server publishes a filter taxonomy and a bounded shared history, while each tab stores enabled filters and visible-row
limit locally. Clear establishes a local sequence watermark/reset; it does not erase process-wide history. Define how
that watermark and filters behave across reconnect and explicit context changes.

Batch/coalesce bounded SSE deltas by context so high message rates and mutable-event upserts do not create one network
write per decoder callback. Message normalization, event mapping, and alias/icon resolution all happen off decoder
threads. Harden `HistoryModule` concurrency and bounds before sharing it with server threads.

### 9.6 Retirement gate for the first slice

After parity and a canary release:

- Delete the Swing/JavaFX renderers and menu classes listed above. Delete `TunerSpectralDisplayManager` only after the
  remaining Swing tuner View Spectrum/New Spectrum actions open web deep links; otherwise retain that narrow adapter
  until tuner UI retirement in Phase 11.
- Retain/move DFT processors, buffers, converters, sample taps, filter taxonomy, and history contracts into neutral
  packages.
- Remove the spectrum split/toggle state from `gui.SDRTrunk`.
- Remove Events/Messages/Channel selected-context wiring from `NowPlayingPanel`.
- Keep temporary desktop adapters only if another legacy view still needs the neutral service.
- Prove listener/executor/native-buffer cleanup across panel disposal, chain replacement, RF-probe failure, tuner
  hotplug, and repeated connect/disconnect. Replace method-reference removal with retained listener tokens/instances so
  the dual-running desktop cannot remain subscribed after cutover.

## 10. Writable configuration foundation

Complete this before any web editor accepts writes.

### 10.1 Required services

- Aggregate repositories for channels, alias lists/aliases, and streams.
- Stable identities and revision checks.
- Pure validation services for every variant.
- Reference-impact planning for renames/deletes.
- One serialized configuration command executor.
- Transactional bulk operations and post-commit runtime publication.
- A consistent bounded-memory operation/progress service for long-running work, with payload/count/TTL limits and no
  operation-history database table.
- Safe backup/export before destructive batch operations.
- Temporary JavaFX/Swing adapters so legacy editors use the same services during coexistence.

### 10.2 Reference integrity

Current references are often mutable names:

- channels refer to alias-list names;
- aliases refer to stream names;
- stream assignments live inside alias identifiers;
- RadioReference imports may create both channels and aliases; and
- legacy alias actions are migration inputs to remove, not web capabilities.

Every rename/delete API must first return a bounded impact preview and then apply all accepted reference changes in one
transaction. Long term, internal references should use stable IDs while names remain display values.

An impact preview that spans aggregates returns a configuration revision or signed/opaque plan token plus every
impacted revision. Revalidate the complete set immediately before commit and reject with a re-preview requirement if
any channel, alias, list, or stream changed; one root `If-Match` is insufficient.

### 10.3 Coexistence rule

Do not allow one browser editor to write through new repositories while the corresponding JavaFX editor still mutates
observable lists and triggers whole-table replacement. For each area, first route the desktop editor through the command
service, or make it read-only/disabled when the web editor becomes authoritative.

## 11. Playlist/channel editor

### 11.1 Current code to extract from

- `gui/configuration/channel/ChannelEditor.java`
- `ChannelConfigurationEditor.java` and per-decoder editors
- `gui/configuration/source/*`
- `gui/configuration/decoder/AuxDecoderConfigurationEditor.java`
- `gui/configuration/eventlog/EventLogConfigurationEditor.java`
- `gui/configuration/record/RecordConfigurationEditor.java`
- `controller/channel/Channel`, `ChannelModel`, and `ChannelProcessingManager`

The reusable logic belongs in services/validators, not in JavaFX controls.

### 11.2 Required web behavior

- Search System, Site, Name, and decoder. Do not port the old All/Playing/Auto-Start filters or initially add Protocol
  and Alias List filters.
- Create P25 Conventional, P25 Trunked Phase 1, P25 Trunked Phase 2, DMR, NBFM, and NXDN channels, in that order;
  clone/delete one channel at a time and manage nullable auto-start order 1–99 with conflict shifting.
- Multi-select exists only for Start Selected and Stop Selected. Preview selected count and tuner capacity before Start;
  do not add bulk delete, assignment, logging/recording, auto-start, clone, or decoder-field editing.
- Edit system/site/name, stable site GUID, alias list, source configuration, frequency rotation, preferred tuner, decoder
  options, auxiliary decoders, event loggers, and recorders. Remove the minimum/maximum working-frequency envelope.
- Display server-side validation before save; preserve protocol-specific bounds and cross-field rules.
- Retire AM, LTR, LTR-Net, Passport, MPT-1327, and the named MPT Channel Map subsystem. Keep the independent DMR LCN
  and NXDN channel-number/frequency maps inside their decoder forms.
- A clone receives a new aggregate UUID/revision and follows the current new-channel site-GUID behavior; it must not
  reuse the source channel's standard site GUID accidentally.
- Expose JMBE/module capability status before starting an MBE-producing decoder. Preserve the missing-JMBE warning and
  provide a temporary setup bridge or web deep link until the module admin page lands.
- Show playing/locked state and the impact of a change.
- For a running channel, require an explicit policy: save for next start, stop/apply, or stop/apply/restart. Return an
  operation ID and make failure visible.
- Serialize start/stop/restart actions; never run them on an HTTP worker. Move Clear Statistics for This Site to the
  dedicated Statistics Management page.
- Warn on delete, stop first if required, and make the durable/runtime result unambiguous.
- Preserve import/export/backup behavior and never modify legacy XML input.

### 11.3 API design

Do not expose one enormous polymorphic Java object. Return a common channel DTO plus typed source/decoder configuration
variants with explicit allowed values, ranges, defaults, and units. The frontend may render variant forms from shared
capability/field metadata, but server validation remains authoritative.

### 11.4 Retirement gate

- Desktop and web parity fixtures pass for every retained decoder/source variant; retirement/migration fixtures cover
  AM, LTR, LTR-Net, Passport, MPT-1327, sound-card input, and the removed working-frequency envelope.
- DMR LCN and NXDN map parity passes, and named MPT Channel Map removal/migration behavior is proven before deleting
  the old Channels tab.
- All start/stop/restart and running-edit races have deterministic tests.
- Unknown decoder/source configurations remain losslessly readable/exportable/deletable as unsupported entries.
- JavaFX navigation requests deep-link to the web editor or are removed.
- `ConfigurationEditor` no longer constructs the Channels tab.
- Delete the JavaFX channel/source/decoder/event-log/record editor classes only after a full canary cycle.

## 12. Alias editor

### 12.1 Current code to extract from

- `gui/configuration/alias/AliasConfigurationEditor.java`
- `AliasItemEditor`, `AliasBulkEditor`, identifier/recording views
- `gui/configuration/alias/identifier/*`
- `gui/configuration/alias/action/*`
- `alias/Alias`, `AliasModel`, `AliasList`, `AliasFactory`, `alias/id/**`, and `alias/action/**`

`AliasList` contains useful lookup/index/overlap behavior, but it and `Alias` must be decoupled from JavaFX properties
before the JavaFX Alias editor can be removed. The retained node-administration JavaFX package must never depend on
alias models.

### 12.2 Required web behavior

- Alias-list create/rename/delete with impact preview across channels and aliases.
- Search/filter, create, clone, move, delete, and bounded bulk operations.
- Edit name, group, color, vector icon, priority, recordability, stream-as-talkgroup, and broadcast assignments; derive
  streamable state from assignments rather than exposing it as an unrelated editable flag. Stream-as-talkgroup is
  optional and accepts 1–65,535: blank sends the decoded talkgroup, while a value replaces only the outgoing streamed
  target/talkgroup for matched calls. It never changes decoding or the separately reported transmitting radio ID.
- Do not port the Alias **Listen** checkbox or expose **Do Not Monitor** as a web eligibility choice. The selected
  Listen list replaces that gate. Keep numeric Priority independently as an ordering hint among simultaneously eligible
  list-matched calls; blank means Default, and no Priority value may exclude a Listen-list member.
- While receiver-computer playback still exists during migration, preserve its stored legacy `Do Not Monitor` value for
  that legacy path only. Web Listen ignores it. Remove the sentinel and obsolete RadioReference encrypted-talkgroup
  muted import option in the explicit retirement migration when desktop playback is removed; normal startup never
  rewrites them.
- Keep identifier editing inside the primary Alias form with Talkgroup, Radio, and Other categories. Do not port the
  old separate Identifier or Record projection tabs.
- Use the packaged vector-icon set. Do not migrate legacy raster icon assets or build an Icon Manager/upload route.
- Support all current identifier kinds, ranges, protocols, P25 fully qualified identifiers, tones/status identifiers,
  and overlap warnings.
- Explicitly include the main-branch NXDN additions: Talkgroup, Talkgroup Range, Radio ID, Radio ID Range, and the
  shared AMBE Audio Tones matcher. NXDN exact/range values use the decoded 16-bit 0–65,535 domain, and range start must
  be lower than range end. Correct the Java editor's accidental generic 24-bit fallback before parity sign-off rather
  than reproducing it in the website.
- Enforce server-side required values, normalization, uniqueness, protocol bounds, list-name limits, and stream-as-
  talkgroup range.
- Rebuild alias lookup indexes after commit without blocking decoder threads.
- Publish changes so live displays update aliases without a restart where safe.

### 12.3 Alias actions retirement

Retire Beep, Audio Clip, and Script alias actions completely. Do not create a read-only Actions pane, managed clip
upload, arbitrary path selector, script editor, or replacement execution engine in either the website or retained
JavaFX utility. The explicit backed-up migration reports existing actions before removing their configuration; normal
startup never performs schema or compatibility repair.

### 12.4 Alias/stream dependency

Aliases reference stream names, while stream rename/delete must account for alias assignments. Implement alias basics,
then stream CRUD and atomic reference commands, then enable full alias stream assignment. Do not allow either feature to
silently leave dangling names.

### 12.5 Retirement gate

- Identifier validation/overlap golden tests pass for every type.
- NXDN golden tests cover exact and range Talkgroups, exact and range Radio IDs, and ordered AMBE tones, including the
  16-bit boundaries and rejection of equal or reversed range endpoints.
- Rename/delete/bulk reference tests pass transactionally.
- Live alias refresh is measured under representative alias counts.
- The JavaFX Alias tab is removed only after retained fields pass parity and Beep, Audio Clip, and Script actions pass
  their explicit backed-up retirement migration.
- Unknown identifier variants remain visible and exportable. Unknown action variants are reported by the migration and
  are never executed or exposed as web capabilities.

## 13. Streaming settings and status

### 13.1 Current code to extract from

- `gui/configuration/streaming/StreamingEditor.java`
- `AbstractBroadcastEditor`, `AbstractStreamEditor`, editor factory and provider editors
- `audio/broadcast/BroadcastConfiguration`, `BroadcastModel`, `ConfiguredBroadcast`, `BroadcastFactory`
- provider runtime implementations under `audio/broadcast/**`

`BroadcastModel` must be split into a neutral broadcast registry/lifecycle service plus temporary Swing/JavaFX table
adapters.

Streaming cutover also needs an early neutral slice of the RadioReference service: credential/session handling,
Broadcastify `getUserFeeds()` discovery, and talkgroup conversion types currently imported from the RadioReference GUI
package. Extract that slice before the full importer UI.

### 13.2 Required web behavior

- CRUD for every retained provider: Broadcastify stream/calls, Icecast HTTP/TCP, Shoutcast v1, Rdio Scanner, OpenMHz,
  and RadioResolve.
- Common fields plus typed provider fields with strong URL/host/port/range/time-zone validation.
- Unique names enforced on the server.
- Write-only credentials with `configured` indicators and leave-unchanged semantics.
- Broadcastify feed refresh/discovery and create-from-feed, using the authenticated neutral RadioReference client.
- Explicit credential clearing and password/API-key rotation.
- Async connection/configuration test with a terminal operation result.
- Broadcastify Calls scheduled test-upload enable/interval settings, distinct from a one-shot connection test.
- Enable/disable/restart lifecycle commands with status, errors, queue depth, age-outs, and retry state.
- Atomic rename/delete impact handling for aliases.
- A prominent, admin-only warning for any certificate-verification bypass option.
- A checked provider-field parity matrix covering every current typed property, default, range, dependency, secret, and
  runtime restart effect, including lossless unsupported-stream read/export/delete behavior.

### 13.3 Secret handling

- Never include secrets in GET responses, WebSocket/SSE data, audit details, logs, error messages, operation payloads,
  screenshots, exports, or diagnostics.
- Redact provider responses before displaying them.
- Move RadioReference and streaming credentials behind a `SecretStore` abstraction early. The initial implementation may
  preserve the existing portable storage format, but its API must be write-only and permission restricted. Design a
  separately reviewed encrypted-at-rest migration rather than inventing transparent encryption with an embedded key.

### 13.4 Retirement gate

- Provider validation/lifecycle/secret-redaction matrices pass.
- Tests prove a configuration change tears down/restarts only the intended broadcaster.
- No secret appears in API snapshots or captured test logs.
- The JavaFX Streaming tab and Swing broadcast status panel can then be removed.

## 14. RadioReference importer

### 14.1 Current code to extract from

- `service/radioreference/RadioReference.java` and `CachingRadioReferenceService.java`
- `gui/configuration/radioreference/*`
- especially `RadioReferenceDecoder`, site/frequency/channel creation, and talkgroup conversion logic
- `preference/radioreference/RadioReferencePreference.java`

The current service exposes JavaFX login properties and importer logic is embedded in UI controls. Extract a neutral
client/session service and pure import planner.

### 14.2 Required workflow

1. Admin configures or replaces write-only RadioReference credentials.
2. A masked account-status/test operation confirms access without returning credentials.
3. Browse/search endpoints cover country -> state -> county, state/county systems, and national/state/county agencies,
   with bounded caching, timeouts, cancellation, and provider-rate awareness.
4. The user selects agencies/systems/sites/frequencies/talkgroups and import options.
5. The server creates a deterministic import plan containing additions, updates, duplicates, conflicts, warnings, and
   the exact target alias list/channel grouping.
6. The user confirms the plan.
7. One serialized transactional command creates channels and aliases, or rolls back the entire commit.
8. Progress/result events show success, skips, and failures without provider secrets.

### 14.3 Rules to preserve

- Conventional agency-frequency import.
- Control-only, control+alternate, selected, and all-frequency site choices.
- One multi-frequency channel versus one channel per frequency.
- P25 FDMA/TDMA control decisions and hybrid P25 voice behavior.
- DMR timeslot, NXDN map, P25 LSM and protocol conversion rules.
- Selected/all talkgroups and duplicate/current-alias handling.
- Optional do-not-monitor behavior for encrypted talkgroups.
- Idempotency using provider identity plus target configuration, not mutable display names.
- Retain **Store Login Credentials** through the protected write-only current secret setting; never return the saved
  password or keep prior credentials or login-attempt history. Put preferred country/state/county/system/agency and
  import-option defaults in typed server settings when operationally shared, or browser-local settings when purely
  navigational.

### 14.4 Retirement gate

- Import plans are deterministic against saved fixtures.
- Commit/rollback, cancellation, duplicate, stale-plan, and rate-limit tests pass.
- No JavaFX property is required by the client/service.
- Talkgroup conversion helpers and GUI-owned `Level`/planner types used by stream runtimes have moved to neutral
  packages, and `gui/configuration/radioreference/**` has zero external imports before deletion.
- RadioReference credentials have left generic Java Preferences or are isolated behind the secret abstraction.
- The JavaFX RadioReference tab and login dialog can then be removed.

## 15. Tuner inventory, settings, and control

### 15.1 Current code to extract from

- `source/tuner/ui/TunerViewPanel.java`, `DiscoveredTunerEditor`, `TunerEditor`, `EmptyTunerEditor`
- `source/tuner/manager/DiscoveredTunerModel.java`
- every device `*TunerEditor.java`
- `source/tuner/TunerFactory#getEditor`
- reusable managers/controllers/configurations under `source/tuner/**`
- `source/tuner/configuration/TunerConfigurationManager` and `TunerSettings`

The factory must stop constructing UI classes. Split a thread-safe `TunerRegistry` from a temporary Swing table adapter.

### 15.2 Staged implementation

1. Read-only tuner inventory/status/capability API. This is also needed by the spectrum and preferred-tuner selectors.
2. Common correction/extent controls: PPM/auto-PPM where supported, user minimum/maximum tunable extent, Reset extents,
   measured PPM/error display, and the default-off **Keep center frequency fixed** setting for every tuner. The fixed-
   center setting is separate from the temporary active-channel lock.
3. Device-specific typed settings and capability metadata.
4. Serialized enable/disable/restart plus lock-aware center-frequency, sample-rate, and other disruptive changes. Center
   frequency is not assumed safe while channels are active.
5. Wideband recording controls.
6. Recording-tuner add/remove and managed file handling last.

### 15.3 Capability contract

Each tuner response should identify:

- stable device identity and admin-only serial details;
- type/model/status/error;
- current center frequency/sample rate/correction and measured frequency/PPM error;
- persisted and effective fixed-center state, separately from active-channel count and temporary lock reasons;
- hardware and user minimum/maximum tunable extents, with current-center-in-range and
  `(userMaximum - userMinimum) >= sampleRate` validation plus Reset capability;
- active-channel count and lock reasons;
- common operations supported;
- hardware-specific fields with type, units, range/step, enum choices, current value, restart/disruption requirement,
  and writable state; and
- master/slave or coupled-device relationships.

The frontend renders this descriptor but does not decide validity.

### 15.4 Command safety

- One serialized executor per physical device/coupled group.
- Apply fixed-center changes through that serialized tuner command path. Changing the checkbox itself does not retune
  the device: enabling it restricts later allocation to the current usable passband, and disabling it only permits a
  later allocation to retune. Manual center-frequency changes remain separately lock-checked and disruptive.
- Put all access to `TunerConfigurationManager` behind the tuner service; its current configuration lookup is explicitly
  not thread-safe and must never be called concurrently by HTTP handlers.
- Give the persisted tuner-settings aggregate a revision and require an expected revision/ETag for writes. If it remains
  one `tuner.settings` JSON record initially, treat that whole record as the compare-and-swap boundary and publish a new
  snapshot only after durable commit.
- Acknowledged configuration writes are synchronous and durable, not the current deferred save. Return desired and
  effective hardware state separately, including `PERSISTED_APPLY_FAILED`, retry/compensation guidance, and recovery
  after reconnect when persistence succeeds but hardware application fails.
- Return `409` with a concrete lock reason for in-use sample-rate or other unsafe changes.
- Require confirmation for commands that interrupt active channels and enumerate affected channels.
- Recheck the precondition immediately before hardware mutation.
- Save the configuration only after a successful apply, or report clearly when persisted and live states differ.
- Hotplug/removal cancels outstanding commands and publishes a terminal result.
- RSPduo master/slave changes are one coupled transaction.
- No arbitrary client-side path is accepted for recordings; use managed server storage/upload.

### 15.5 Hardware coverage

Create fake-controller, capability-contract, validation, persistence, and failure-recovery coverage for every retained
family: Airspy, Airspy HF, FCD1/FCD2, HackRF, HydraSDR, RTL unknown/E4K/FC0013/R8x/R828D, SDRplay
base/RSP1/1A/1B/2/Duo/Dx, and recording tuners. That matrix is required even when a physical device is unavailable.

Keep physical hardware evidence as a separate gate. BOSGAME proves only the Airspy and the RTL model actually detected
there. Before deleting a family-specific Swing editor, obtain a named physical canary result for that retained family
on BOSGAME or another controlled node. Fake-controller, replay, or a related model's result does not satisfy this gate.
If no device can be obtained, the migration remains incomplete for that retained family and its legacy editor may stay
only during coexistence, or a separately approved support-retirement decision must preserve safe read/export behavior.
No family-specific radio editor remains in the final JavaFX node-administration utility.

The parity matrix must enumerate, at minimum:

- Airspy gain mode, IF/mixer/LNA gains and AGC; Airspy HF attenuation, LNA, and AGC;
- FCD1 DC/IQ phase/gain plus LNA/mixer, and FCD2 LNA/mixer;
- HackRF LNA/VGA/amplifier and HydraSDR Bias-T;
- RTL sample rate/Bias-T and E4K/FC0013/R8x/R828D gain/AGC variants; and
- SDRplay common gain/AGC plus each model's notches, Bias-T, antenna/ports, external reference, HDR, and bandwidth.

For every field, record type, units, range/step/enums, default, controller method, persistence key, active-channel lock,
restart effect, and fake/hardware test coverage before its Swing editor is eligible for deletion.

For the common fixed-center setting, every retained tuner-family contract proves: default off; save/reload and restart
restore the current value; an in-band channel can still allocate; an out-of-band request never retunes the fixed tuner
and may fall back to another eligible tuner; enabling it leaves existing channels untouched; and disabling it does not
retune until a later allocation requires a move.

### 15.6 Retirement gate

- `DiscoveredTunerModel` is no longer the canonical registry or EDT owner.
- All consumers, including spectrum menus/panels and `BugReportBundleBuilder`, use `TunerRegistry`; the old model has
  zero imports before deletion.
- `TunerFactory` has no UI factory method.
- All common and device-specific capability matrices pass, and each retained family has its named physical-canary
  evidence or an explicitly approved support-retirement decision.
- Hotplug, active-channel locking, restart, and failure recovery are proven for the hardware in scope.
- The Tuners tab and all Swing tuner editors can then be removed.

## 16. Remaining radio desktop parity and local-control split

Complete a surface ledger and assign each item one of `web-radio`, `browser-local`, `retain-local-javafx`,
`CLI/headless`, or `retire`. The likely remaining work is:

### 16.1 Shared Listen and Recordings player

Use one player instance across Listen and Recordings rather than separate route-local audio players. It owns only bounded
browser state: current call, capped up-next queue, capped recently played list, playback position, volume, current source,
and Follow state. It creates no listener, queue, play, filter, or playback-history database row. The first audio start
still requires a browser user gesture.

The persistent desktop dock and mobile mini-player/sheet provide Previous, Play/Pause, Next/Skip, seek with elapsed and
total time, mute/volume, and Queue. Every queued call is deduplicated by stable call ID and ordered deterministically by
completion time plus call ID. Pausing audio does not stop the current Follow source. Queue pressure uses the existing
bounded skip-oldest behavior and visible notice; it never backpressures recording or radio work.

Listen displays administrator-made **Listen lists**. A list is current revisioned configuration containing a short name,
description, availability flag, and stable references to existing talkgroups/channels. It has no listener-specific
priority/order and no usage history. Selecting a list is the authoritative live browser-audio gate. A matching completed
call can enter that browser's queue even when Alias Record is off, no channel technical recorder is selected, or a
retained desktop Alias value says Do Not Monitor. It does not start/stop channels, enable recording, retune a receiver,
or change streaming. Numeric Alias Priority may order competing eligible calls but cannot exclude a list member.
Changing lists removes only pending auto-fed calls from the prior list, lets the current call finish unless skipped, and
turns Follow new calls on for the new list.

The first release keeps the current lean completed-call model instead of adding an in-progress audio-frame tap. The
`AudioCallCoordinator` makes one bounded non-blocking offer to `BrowserAudioPlaybackService`; an off-thread worker
matches an immutable in-memory Listen-list index, encodes an interested call once, and shares the same bounded cached
audio with every matching subscriber. Zero listeners means zero web encoding. A full queue, slow browser, disconnect,
or failed web encode drops only web work and never delays decoding, grants, recording, streaming, or uploads.
The current `StatsWebCallService` check that rejects `snapshot.isDoNotMonitor()` must be removed from the web path;
duplicate handling remains separate, and local desktop playback may keep that check only until its retirement.

Talkgroup members require a stable matcher identity plus Alias-list/system context so equal numeric talkgroups on two
systems never cross-match. Channel members require the stable parent channel UUID to flow into dynamic trunked traffic
call snapshots. Member references cannot use current replace-on-save database row IDs or repurpose `radres_guid`.
The audio request revalidates that its call matched the requested available list; a guessable/global call URL cannot
bypass list membership.

The eligibility contract is:

| Matches selected Listen list | Record eligible and successfully saved | Live Listen | Recordings |
| --- | --- | --- | --- |
| Yes | No | Yes | No |
| Yes | Yes | Yes | Yes |
| No | No | No | No |
| No | Yes | No | Yes |

Legacy Alias Listen/Do Not Monitor values do not change any row in this table. Duplicate-call suppression remains a
separate post-membership rule.

### 16.2 Recordings browse and playback

The Recordings route contains only audio that SDRTrunk actually retained through its Record-enabled recording function
and admitted after a successful file write. Live-only Listen-list audio is never searchable. It supports:

- System path browsing (`System -> Site -> Talkgroup/Radio/Channel`) or a direct searchable Site entry point;
- receiver-local date/time range, inclusive minimum/maximum duration with hundredth-second precision, and newest/oldest
  sort;
- 50-row keyset pages with **Load 50 older** and **Back to latest**, no offset pagination and no exact-million count;
- coalesced `N new recordings — Show` notice so a burst never reshuffles a page under the pointer; and
- explicit empty corpus, no matches, expired/deleted, stale prior results, loading, unavailable, access-locked, and
  0.25-second-call states.

The result click mode is always visible:

- **Play now** interrupts and starts the selected call while preserving the remaining queue.
- **Add to queue** appends one deduplicated call without interrupting playback.
- **Play forward** starts the selected call, replaces upcoming automatically selected items with a bounded lazy window of
  newer calls matching a frozen snapshot of the current filters, and enables **Follow new calls**. At the newest retained
  call it waits for newly completed matches. It continues until the listener turns Follow new calls off; already queued
  calls still finish. Editing visible filters never silently changes an active Play-forward run.

### 16.3 Guest event playlists without server persistence

A guest may select, reorder, remove, and optionally name recorded calls, then create a shareable versioned URL fragment.
The fragment contains only compact binary call IDs (delta-varint numeric IDs or raw UUID bytes), compression, and
unpadded Base64url; do not put a JSON array of repeated string IDs in the URL. The initial safety envelope is at most 200
call IDs and 8 KiB encoded. Larger playlists use a downloadable playlist file, also created in the browser with no
database write.

The fragment is not authorization. Opening it performs one bounded batch resolution under the current Recordings
feature policy. It does not extend media retention. Missing or expired entries remain in their ordered position as
disabled `Recording removed by retention` items and are skipped automatically. Reject invalid versions, excessive
encoded/decompressed sizes, excessive ID counts, duplicate bombs, and malformed compression before ID resolution.

| Desktop surface | Web-first outcome |
| --- | --- |
| Systems/Now Playing | Merge the existing Live Systems web page with contextual Details/Events/Messages/Channel views; Signal and Symbols remain there but are visible only when signed in as administrator |
| Desktop audio panels | One shared browser-local Listen/Recordings player and bounded queue; receiver-computer speaker controls retire |
| Broadcast status | Live stream status/queue/error page |
| Map | Browser map using packaged/local tiles or an explicitly configured provider; decide tile license, attribution, privacy, and offline-distribution policy before deleting vendored SwingX map code; any new tile cache is size/age capped with automatic eviction, or disabled |
| Resource footer | Runtime health bar and admin diagnostics |
| Details/Open Web links | Normal routes/deep links inside the SPA |
| Named Channel Maps | Retire with MPT-1327; keep DMR LCN and NXDN channel-number maps inside their retained channel editors |
| Icon manager | Retire legacy raster assets and management; use the packaged vector-icon set in the web Alias editor |
| Message recording viewer | Managed upload/open workflow and browser decoder viewer, or explicit retirement if diagnostic-only |
| JMBE/module editor | Staged install/update workflow in portable `data/jmbe`/`data/modules`; never accept an arbitrary JAR |
| Voice-key vault | Separate authenticated vault unlock/lock/key-management flow over secure transport |
| Calibration | Web radio workflow or automated headless calibration with web status; no retained radio JavaFX |
| Node startup/bootstrap/recovery | Focused local JavaFX utility plus unattended CLI; never part of the retained radio desktop |
| Web server/listener settings | Retain/refactor into focused local JavaFX settings backed by typed services; browser status is read-only |
| Non-radio platform-service controls | Retain only explicitly classified enable/autostart/status/restart/configuration in local JavaFX; radio services remain web-owned |
| HTTPS certificate/private-key management | Implemented local JavaFX generation/import over fixed PEM files; no browser upload, keystore catalog, or certificate history |
| What's New/credits/licenses | Static/versioned SPA routes |
| Bug report | Consent page, preview, redact, upload/download, and progress |
| File explorer menu items | Safe listings/downloads for allowlisted portable directories; never general server browsing |
| Screen capture | Browser/OS capture; do not reimplement a server-side desktop screenshot |
| Exit/restart | Local JavaFX/CLI high-friction platform action, watchdog-aware; web may show status but does not own process control |
| Developer Swing/JavaFX viewers | Move needed radio diagnostics to tests/web diagnostics; delete unused viewers while retaining only the focused node-admin JavaFX package |

Port radio startup behaviors such as calibration, voice-vault unlock, and channel auto-start confirmation to web or
explicit unattended policy. With no saved vault unlock secret, headless startup leaves decryption disabled until the
administrator unlocks the vault through the website. Only after a successful unlock, offer the separate setting
**Auto-unlock vault on launch — Unsafe!**. Enabling it stores the local launch secret without returning it through the
API; disabling it removes that saved secret. Do not recreate the Java startup password prompt or countdown. Keep node
bootstrap, server recovery, and platform-service startup controls in the focused local JavaFX/CLI plane. Release notes
may be shown in both the web About route and the local utility without becoming mutable shared state.

JMBE and optional-module artifacts are executable code. Permit only an allowlisted built-in download or a locally
selected/staged artifact whose source, signature or pinned SHA-256, license, version, and Java/application compatibility
are verified. Require high-friction local/admin approval, record a redacted audit result, install atomically for the next
restart, retain a verified last-known-good rollback artifact in protected storage, and never hot-load an uploaded JAR
into the running process.

## 17. Preference partition and retained JavaFX node administration

Do not retain the broad `UserPreferencesEditor` as the permanent console. Existing preference pages mix radio,
presentation, and host-control fields. Phase 0 classifies them field by field, then replaces the shell with a narrowly
allowlisted `LocalNodeAdministrationApplication`/`--server-admin-ui` adapter.

Use this ownership map:

| Existing preference area | Final owner |
| --- | --- |
| Call Management, MP3 and Record | Web radio settings |
| Recorded call retention and catalog cleanup status | Web General Settings > Storage; separate from Statistics retention and directory warning thresholds |
| Playback/Tones | Retire receiver-speaker output, local playback queue and start/drop tones; browser listeners own their output device |
| Channel Events timestamp and other retired desktop-only display preferences | Retire after the final desktop consumer is removed |
| Physical tuner preferences | Hardware web settings; RSPduo mode belongs to its physical device |
| Heterodyne/polyphase selector | Retire the selector and heterodyne implementation; polyphase is the sole channelizer |
| Vector calibration | Web radio workflow or automated headless operation with web status |
| JMBE, voice-decryption behavior and radio module/vault state | Authenticated web radio/module administration |
| Systems and talkgroup/radio formatting | Web when shared radio behavior; browser-local when presentation only |
| Stats Server/activity collection, retention and maintenance | Web radio/statistics administration despite the “Server” label |
| Web Server listen address, HTTP/HTTPS mode and enablement | Retained local JavaFX node administration; one `host-or-IP:port` field and one connector |
| Application settings | Split field by field: radio/channel auto-start goes web; truly node-local startup/platform-service settings may remain JavaFX/CLI |
| Directories | JavaFX/CLI defines allowlisted managed storage roots; web radio settings select only safe logical destinations/subdirectories, never arbitrary host paths |
| Certificate/private-key generation and import | Retained local JavaFX only; implemented with fixed portable PEM files and no browser mutation route |

All runtime-affecting settings use typed neutral services and current-state records in `application_settings` regardless
of editing surface; presentation-only settings stay browser-local. The JavaFX adapter must not read or write SQLite,
`ApplicationPreference`, or generic Java Preferences directly.

At the ownership cutover:

1. Provide web editors and tests for every radio-affecting preference.
2. Complete the focused local JavaFX node-administration editor for the explicit host/server/platform-service allowlist.
   Inventory every retained field/action and prove read, validation, write, desired-versus-effective display, listener/
   service restart, readiness, failure and rollback parity before deleting its legacy source editor.
3. Move applicable data from `portable_java_preferences_v1` to typed `application_settings` records using an explicit
   settings migration tool under `tools/settings-migrations`, storing current values only.
4. Every web editor query returns the current saved/effective application values plus the real built-in defaults needed
   for an unset field. The browser displays those values and never treats blank mockup fields, placeholders, or
   frontend constants as defaults. Preserve defaults and notify runtime services through neutral listeners/commands:
   web and temporary legacy adapters share radio commands, while local JavaFX and CLI share only local-node commands.
   The web sees node status solely through `NodeStatusQueryService`.
5. Remove the broad `UserPreferencesEditor`, radio preference editors, radio navigation from `JavaFxWindowManager`,
   `JavaFxPreferences`, obsolete stage monitors and request events. Retain or rebuild only the focused node-admin entry
   point and allowlisted editors.
6. Remove `SqlitePreferencesFactory` and `java.prefs` when no non-UI code needs them; retaining JavaFX does not require
   retaining generic Java Preferences persistence.

Network/listener settings validate before persistence and apply on the next normal start. TLS imports stage and
atomically replace fixed files; runtime validates the complete key/certificate pair before binding. A failed bind or
TLS load leaves radio decoding running and fails only the web listener. This intentionally small design keeps no
listener revision history and no certificate rollback generations.

## 18. End-to-end implementation phases

Sizes below are relative work packages, not calendar promises: S is a focused change, M is several related changes, L is
a major feature slice, and XL requires multiple independently reviewable PRs.

| Phase | Deliverable | Size | Depends on | Exit gate |
| --- | --- | --- | --- | --- |
| Design | BOSGAME legacy UI inventory, sanitized screenshot catalog, preserve/adapt/combine/retire ledger, and annotated listener/admin/local-node mockups | S | none | Before each user-facing slice, its source-identified surfaces are classified as captured, synthetic, source-only, unavailable, or retired; secret review and its mockup approval pass before that slice starts |
| 0 | Baselines, ownership/surface ledger, forward-only database admission ledger, bounded canary instrumentation, replay-fixture manifest, threat model, API conventions, JavaFX dependency baseline/new-package fence, Jetty/WS/JPMS packaging spike | M | Design | New core/web code cannot import JavaFX; nodeadmin imports only its allowlist; legacy coupling is baselined/ratcheted; all target images package; canonical BOSGAME/CUBI state is inventoried read-only; approved-build isolated BOSGAME references and canary measurements are reproducible without SQLite history |
| 1 | Headless runtime owner/lifecycle, thin temporary legacy-radio adapter, and focused retained local JavaFX node-admin shell/spike | XL | 0 | Full headless smoke constructs no UI; optional shell works over fake allowlisted node services and never creates a second data-root owner |
| 2 | General web server, auth/session/CSRF/security headers, `/api/v1`, SPA shell, current stats compatibility, and minimal real local node-settings/lifecycle slice | XL | 1 | Auth/security suite; typed current listen-address/HTTPS-mode/platform-service desired settings work through JavaFX but not web; one port; packaged assets; grandfathered stats schema unchanged |
| 3 | Neutral tuner inventory and live-context identity/resolver | L | 1-2 | Stable browser selection/tuner IDs and reconnect snapshots |
| 4 | Exclusive adaptive FFT/waterfall WebSocket and browser renderer | XL | 2-3 | Approved wideband mockup is implemented; one authenticated admin owns one target/producer; 4K-32K refinement crops to the visible wire payload; BOSGAME Airspy/RTL radio-safety and exclusivity gates pass |
| 5 | Channel FFT, symbols, Events, and Messages | XL | 3-4 | Site/timeslot/gap/reconnect parity; BOSGAME live P25 plus the pinned replay-manifest gates pass; decoder threads never block |
| 6 | Stable configuration identities/revisions, external migration, aggregate repositories, command executor | XL | 1-2 | Migration/rollback/quick-check and conflict tests pass on an isolated disposable BOSGAME data-root copy |
| 7 | Alias basics, packaged vector icons, and reference-impact service | L | 6 | Primary Alias form, Talkgroup/Radio/Other matching, validation, overlap, bulk, and rename/delete parity; no separate Identifier/Record tabs |
| 8 | Neutral RR feed/conversion slice; stream CRUD/status/secrets; then alias stream assignments | XL | 6-7 | Feed/provider/secret/lifecycle matrices pass |
| 9 | Playlist/channel editor and runtime channel commands | XL | 3, 6-8 | Every retained decoder/source form, DMR/NXDN map, retirement migration, and running-edit policy passes, including BOSGAME disposable-data-root writes |
| 10 | RadioReference browse, plan/preview, and transactional commit | XL | 7-9 | Fixture/idempotency/rollback/cancel tests pass |
| 11 | Full tuner settings/control and recording tuners | XL | 3, 6 | Fake-controller matrix plus BOSGAME RTL-first/Airspy hardware gates pass |
| 12A | Shared player, public/admin Listen route, and revisioned administrator Listen-list editor | L | 2, 6-9 | Approved shared-player and Listen-list mockups; membership—not legacy Listen/Do Not Monitor or Record—is the live browser gate; one shared encode/cache serves ten listeners; no listener/queue/history persistence; list edits cannot alter tuner/channel/recording behavior |
| 12B | Fixed recorded-call `calls/v1` writer, explicit catalog migration, separate media retention, Recordings browse/playback, and no-database guest playlists | XL | 2, 6, 12A | Immutable path convention/writer/validator and successful-write artifact handoff pass first; persistence admission and external migration/rollback pass; only successfully saved Record-eligible calls are searchable; 1M/5M/10M query-plan/latency/size gates; bounded cleanup/restart catch-up; 0.25-second/ten-concurrent-call and ten-listener non-interference tests pass |
| 12C | Remaining non-Preferences radio surfaces, browser radio onboarding, and node setup/recovery ownership split | XL | 2-12B | Every non-Preferences radio surface is ported, replaced, or explicitly retired; every retained/non-radio surface is classified; radio Preferences remain the explicit Phase-14 bridge |
| 13 | Make web authoritative/default for migrated radio surfaces; legacy migrated views read-only/optional; multi-release soak | L | 4-12C | BOSGAME full soak/restore passes; the still-unported radio Preferences bridge remains writable until Phase 14 and node-admin mutations remain local |
| 14 | Partition Preferences, migrate typed settings, and complete the focused permanent JavaFX node-admin utility | XL | 12-13 | Every radio preference has web parity; every retained node field/action has read/write/validation/restart/readiness/rollback parity; migration, JavaFX boundary, both launches, and fresh BOSGAME disposable-root/package gates pass |
| 15 | Delete Swing and legacy/radio JavaFX code/adapters/dependencies; default headless launcher plus optional local node-admin launcher | XL | 14 | Swing reaches zero; JavaFX is confined to the allowlist; both launch modes package; fresh BOSGAME task/package/restore and soak evidence passes |
| 16 | Web-radio and retained-node-admin UX/performance/accessibility consolidation | L | 13-15 | Product budgets and both-surface usability/accessibility gates plus a fresh BOSGAME regression/soak pass; any CUBI reference run follows the separate 15-minute isolation/restoration rule |

Phase 6 may proceed in parallel with Phases 3-5 once the headless/web foundations are stable. RadioReference is on the
configuration critical path because it depends on both channel and alias commands. Full alias stream assignment depends
on stream identity/reference handling. Tuner read-only inventory is intentionally early because both spectrum and
playlist forms need it.

Phase 13 authority is feature-scoped. It does not make an unported radio preference read-only or remove its temporary
legacy bridge. Phase 14 establishes final web authority for all radio preferences and proves retained node-admin parity;
only then may Phase 15 delete the broad Preferences shell.

The intentionally small JavaFX PEM workflow is part of the server foundation, not a separate radio-UI phase. Broader
keystore formats, automatic renewal, OS keychains, browser certificate upload, and certificate-history systems remain
out of scope unless separately authorized.

BOSGAME is the development node and the only host for long-running candidate, hardware, soak, leak, and migration
testing. Use it at the relevant phase gates beginning with the authenticated server and read-only signal work; Phase 13
is the final web-radio-default soak, not its first use. CUBI remains on its last-known-good production package, profile,
streaming destinations, and database except during an explicitly bounded minimum-hardware reference run.

Phase 13 evidence qualifies only the artifact tested there. Every later Phase 14–16 candidate repeats the applicable
static/CI packaging checks and BOSGAME disposable-root, migration/rollback, headless/JavaFX launch, live package/task,
and soak gates before any optional CUBI reference run. Do not deploy or launch a migration candidate on the development
Mac. CUBI never substitutes for a BOSGAME soak.

## 19. Suggested PR/work-package sequence

Keep changes small enough to review and roll back. A practical sequence is:

1. Finish the source-identified surface inventory and BOSGAME sanitized screenshot catalog; create annotated
   public-listener, radio-admin, signal, configuration, and retained-local-JavaFX page mockups and obtain approval. Make
   no runtime, schema, dependency, package, or receiver-node implementation change in this work package.
2. After design approval, add source-dependency checks, a five-way UI ownership/surface ledger, the JavaFX dependency
   baseline/ratchet and node-admin import allowlist, the forward-only database admission ledger, and BOSGAME
   runtime/storage baselines. Inventory CUBI read-only without changing its production package, profile, streams, or
   database. Do not deploy or run the candidate on the Mac.
3. Add bounded non-persistent canary instrumentation and a checked-in replay-fixture manifest, then prototype Jetty,
   access-policy-aware SSE and binary WebSocket transport, jlink, and signed/package images.
4. Introduce `SdrTrunkRuntime`/lifecycle without behavior changes.
5. Make headless startup/shutdown independent of spectrum/UI and land only the optional JavaFX node-admin shell/spike
   over fake node services, proving it is lazy, local, and not a second persistence owner.
6. Extract neutral resource and tuner snapshots.
7. Introduce `WebApplicationService`; remount existing stats routes without redesigning their grandfathered schema.
8. Add the local JavaFX/CLI bootstrap slice and minimal real `LocalNodeAdministrationService` for typed current
   bind/port/HTTPS-mode/platform-service desired settings, browser admin login/session/CSRF, read-only TLS status, and
   security tests; certificate material remains pre-provisioned.
9. Add TypeScript SPA shell and port current stats routes one at a time.
10. Add stable live selection IDs and `LiveContextResolver`.
11. Extract a generic bounded SSE hub with replay/resnapshot semantics.
12. Add shared wideband spectrum broker and browser worker/renderer.
13. Add channel inspection and selected-channel FFT.
14. Add symbol tap/broker and browser graph.
15. Extract Events/Messages DTOs, histories, and live deltas.
16. Pass the relevant BOSGAME Airspy/RTL read-only soak and retire the first desktop visualization panels.
17. Implement and ship the explicit configuration identity/revision migration after isolated disposable-data-root and
    complete-manifest rollback proof on BOSGAME.
18. Replace whole-table saves with aggregate repositories and command services.
19. Port alias basics with the packaged vector-icon set.
20. Extract neutral RadioReference auth/feed/conversion types; port stream CRUD/status/secrets, then alias stream
    references.
21. Port retained playlist/channel forms, DMR/NXDN maps, and runtime actions; retire named MPT Channel Maps.
22. Implement the shared browser player against synthetic/completed-call fixtures, then add the Listen route and
    revisioned Listen-list configuration without any listener or playback-history persistence. Remove the web
    Do-Not-Monitor rejection, match immutable list snapshots off-thread, and prove Record-off calls remain live-only.
23. Implement and freeze the cross-platform `recordings/calls/v1` writer/validator first. Then write and approve the
    recorded-call catalog admission record, measure the completion-rate envelope, choose the finite default/hard-
    maximum media retention, and ship the explicit one-off migration with backup/rollback and 1M/5M/10M query-plan/
    size evidence.
24. Add the retention-bound Recordings API/UI over successful destination-talkgroup-Record artifacts only, lazy Play
    forward/Follow behavior, bounded ten-listener media delivery, and guest URL-fragment/download playlists; prove
    cleanup and indexing never block protected radio work.
25. Extract RadioReference planner, then build preview/commit UI.
26. Port tuner settings by common controls—including the per-tuner fixed-center choice—and then device families;
    physically gate RTL first and Airspy second on BOSGAME.
27. Port every remaining non-Preferences radio surface and migrate the classified host-control fields/actions into the
    focused local JavaFX utility or protected CLI.
28. Make web the default for migrated radio surfaces on BOSGAME, while keeping any unported radio Preferences bridge
    writable; complete the long soak/restore drill. An optional CUBI minimum-spec reference run occurs only under the
    15-minute isolated no-stream-profile rule and is followed immediately by full production restoration and a new
    successful RadioResolve call upload.
29. Complete preference partition, radio-setting parity, retained node-admin field/action parity, and migration of
    generic Java Preferences to typed current settings where appropriate.
30. Remove Swing and all JavaFX outside the allowlisted node-admin package, then remove only dependencies/modules/assets
    no longer used by the retained utility.
31. Perform the dedicated web-radio and local-node-admin UX/performance release.

Each numbered item may require multiple PRs. Do not combine schema migration, a large editor, and desktop deletion in one
change.

## 20. Radio desktop retirement and retained-local-utility ledger

Delete radio desktop code only when its replacement gate passes. Retained-local-JavaFX rows are refactored and
constrained rather than deleted.

| Group | Current code | Retain/extract | Delete after parity |
| --- | --- | --- | --- |
| Composition | `gui/SDRTrunk`, `ControllerPanel`, `NowPlayingPanel`, `JavaFxWindowManager` | Runtime construction/lifecycle into `application`; focused node-admin entry point into `nodeadmin` | Swing frames/menus/splits, radio window state/navigation, and the broad legacy `JavaFxWindowManager` after the focused replacement exists |
| Wideband signal | `spectrum/*Panel`, `SpectrumFrame`, menu items, `TunerSpectralDisplayManager` | DFT/buffer/converter math; temporary tuner-button deep-link adapter | Swing rendering, AWT palette, mouse/menu/window code; manager after no tuner UI caller remains |
| Channel signal | `gui/channel/ChannelSpectrumPanel`, `gui/symbol/*`, squelch/power views | Sample taps, decoder feedback, pure measurement logic | Swing/embedded JavaFX renderers and RF-probe UI |
| Events/messages | `DecodeEventPanel`, `MessageActivityPanel`, table models/items/history widgets, `FilterEditor` | Histories, filter taxonomy, explicit DTO mappers | Swing tables/renderers/filter dialogs |
| Channel activity | `ChannelActivityPanel`, `ChannelActivityTable*`, UI parts of `ChannelActivityModel` | Neutral state machine and immutable snapshots | EDT/timer/table adapters |
| Playlist | `gui/configuration/channel/**`, `gui/configuration/source/**`, decoder/log/record subeditors | Domain configs, factories, pure validation | JavaFX channel editor controls |
| Aliases | `gui/configuration/alias/**` | Identifier domain and headless lookup/indexes | JavaFX editors/bindings/adapters |
| Streams | `gui/configuration/streaming/**`, `BroadcastStatusPanel`, UI facets of `BroadcastModel` | Provider configs and lifecycle | JavaFX/Swing editors/tables |
| RadioReference | `gui/configuration/radioreference/**`, UI properties in service | API client/cache, extracted conversion/planner rules | JavaFX editors/login/browse controls |
| Tuners | `source/tuner/ui/**`, all `*TunerEditor`, recording dialog, UI factory path | Managers/controllers/configurations | Swing table/editors/EDT adapter |
| Audio/map | playback panels, `MapPanel`, painters/listeners, vendored `org.jdesktop.swingx` | Audio services and neutral geospatial data | Swing renderers and bundled SwingX map sources |
| Secondary radio tools | icon/JMBE/encryption/recording viewers, calibration and radio startup UI, bug-report UI | Non-UI services/builders and web radio/admin workflows | Radio JavaFX/Swing dialogs and request events |
| Radio preferences | radio portions of `gui/preference/**`, `JavaFxPreferences`, `SwingPreference`, table/stage monitors | Typed radio-settings services, browser-local presentation state and migration | Broad/radio preference editors and adapters after web parity |
| Local node administration | `WebServerPreferenceEditor` plus explicitly classified host-control fields | Rebuild as focused `nodeadmin` JavaFX adapter over typed `LocalNodeAdministrationService`; retain indefinitely | Direct Preferences/SQLite access, radio fields, broad navigation, and any unclassified service control, only after field/action parity |
| Utilities | `SwingUtils`, `ColorIcon`, split-pane helpers, desktop icon helpers | Pure utilities plus JavaFX helpers used only inside the allowlisted node-admin package | Swing and radio-toolkit-specific utilities |
| Dev viewers | channelizer/filter/squelch/sync/message viewer apps | Tests or guarded web diagnostics if still useful | Production radio Swing/JavaFX diagnostic applications |

After all four configuration tabs are gone, close the shell explicitly: delete `ConfigurationEditor`,
`ConfigurationEditorApplication`, `ConfigurationEditorRequest`, `ViewConfigurationRequest`, tab/view request classes,
and remove `ConfigurationManager`'s `IAliasListRefreshListener` and JavaFX-thread contract. Require zero external imports
into `gui/configuration/**` rather than assuming per-tab deletion completes this work.

After radio UI source references reach zero, remove dependencies/modules in separate changes:

- JIDE OSS, MigLayout Swing, tablefilter-swing, jiconfont-swing;
- JavaFX Swing bridge, radio chart/UI libraries, and ControlsFX/jiconfont-javafx if the focused node-admin utility does
  not use them;
- bundled SwingX sources;
- related JPMS `requires`, `--add-exports`, `jdk.accessibility`, and UI-only launch flags; and
- `jdk.httpserver` after compatibility handlers are fully on the new server.

Retain only the JavaFX base/graphics/controls modules and packaging support proven necessary by the focused utility.
Run `jdeps` before removing `java.desktop`; Java Sound or image work may still require it. The goal is zero Swing and
zero JavaFX outside the allowlisted node-administration adapter—not an unverified module deletion.

## 21. Testing and verification program

### 21.1 Backend unit and characterization tests

- Golden DTO mapping for events, messages, aliases, channels, streams, and tuner capabilities.
- Validation matrices for every decoder, source, identifier, provider, and tuner variant.
- Mutable-event upsert identity and message deduplication/StuffBits filtering.
- DSP golden tests for complex tones/noise across FFT sizes/windows, half-shift/bin-to-frequency mapping, dB/window-gain
  scale, hot DFT-size/target switches, empty-input/stale behavior, quantized binary round trips, and the intentional
  waterfall averaging decision.
- Site/control versus exact-frequency/timeslot selection and chain-replacement gaps.
- Rename/delete reference-impact and transaction behavior.
- Secret redaction in JSON, exceptions, logs, operations, exports, and diagnostics.
- Command serialization, cancellation, idempotency, timeouts, and shutdown.
- Fake broadcasters, RadioReference fixtures, fake tuners/controllers, and hotplug races.
- Listen-list membership matching across system/Alias-list context and parent traffic-channel UUIDs; exercise all four
  Listen-list/Record combinations, both legacy Listen/Do Not Monitor values, same-number talkgroups on different
  systems, normal Priority ordering, stable-ID deduplication, completion-time-plus-ID ordering, Play now/queue/forward
  transitions, frozen-filter Follow behavior, bounded lazy refill, retention expiry, and guest-playlist binary decoder
  limits. Include ten concurrent calls and bursts of 0.25-second completions.
- Fixed `calls/v1` path construction on Windows/macOS/Linux semantics: safe slugs, reserved names, Unicode, case-fold
  collisions, maximum segment/path lengths, unknown/conventional components, rename-after-record, staging/atomic move,
  one-file-only behavior, and refusal to escape through separators, `..`, or symlinks.
- Phase 0 records the existing legacy JavaFX dependency baseline and ratchets it downward. New core/web code cannot
  import JavaFX; `nodeadmin` cannot import radio UI/models or direct SQLite/Java Preferences; `web` cannot import local-
  node mutation/lifecycle/TLS contracts. Phase 15 upgrades the ratchet to zero JavaFX outside the allowlist.
- The JavaFX adapter, protected CLI and runtime lifecycle use the same node-setting/service validators, desired-settings
  model, in-memory effective-status derivation and rollback commands.

### 21.2 Embedded-server integration tests

- Ephemeral-port startup/readiness/shutdown.
- First admin setup, login/logout, session rotation/expiry, password change/reset.
- CSRF, origin, permissions, secure-cookie policy, rate limits, and generic auth failures.
- Security headers, traversal rejection, JSON/body/upload limits, unsafe content escaping.
- SSE snapshot/replay/resnapshot and disconnect cleanup.
- WebSocket feature-policy/authentication/origin enforcement, permanent wideband admin-only enforcement, binary
  viewport framing, one-owner conflict/reconnect/expiry, stale-refinement rejection, size/rate limits and last-owner
  detach. Other signal features test their own separately approved concurrency contracts.
- Short, deadline-bound, index-backed web reads; no SSE/WebSocket lifetime may retain a SQLite transaction or
  connection.
- Enforce independent Live, Listen, and Recordings policies on navigation, metadata, SSE/completed-call delivery,
  guest-playlist batch resolution, and audio/range requests. Public Live omits Signal/Symbol actions; authenticated Live
  retains them. A locked Recordings route reveals no call metadata.
- One shared player survives Listen/Recordings route changes without a second server subscription. Ten slow/fast mixed
  clients cannot grow an unbounded queue, file handle, range request, executor task, or memory buffer. A list-scoped
  audio request is rechecked against that list, zero listeners performs zero encodes, and ten matching listeners share
  one encoded cache entry. Slow clients and a full web queue drop only web work.
- Read-only listener/TLS/platform-service status routes are redacted, and the API contract contains no mutation route
  for listen address, TLS material, or local platform-service configuration.
- Send `POST`/`PUT`/`PATCH`/`DELETE` to guessed node-control paths and generic preferences/settings/operation-command
  surfaces. Require `404`/`405`, no SQLite/settings-file diff, and no listener or platform-service state change; hidden
  controls alone are not enforcement.
- Default headless boot does not initialize JavaFX classes or require a display. Local maintenance mode acquires the
  exclusive data-root owner and cannot coexist with another SDRTrunk process.
- Verify Windows ACL and POSIX owner/mode enforcement for the maintenance launcher, portable data root, node settings
  and recovery inputs; unsafe ownership/permissions must refuse privileged mode and the audit record must identify the
  redacted local OS principal.
- Missing-database startup enters `MAINTENANCE_REQUIRED` without starting radio/web services and without a one-minute
  scheduled-task restart/log storm. Whole-profile XML bootstrap/restore works only with the task/runtime stopped and
  cannot become a selective JavaFX radio editor.
- Bootstrap/reset passwords and keystore passphrases are accepted only from local interactive fields/console/stdin;
  tests confirm they never enter argv, provision files, settings, logs, diagnostics or backups.
- API version mismatch and production asset behavior.

### 21.3 Persistence and migration tests

- New schema creation only in the startup routine.
- Existing schema validation with no runtime mutation.
- Treat schemas implemented at the planning baseline as grandfathered; this migration does not require rewriting their
  existing summary or retention semantics.
- Offline migration from every supported predecessor version with backup/rollback instructions.
- Row counts, stable identities, revisions, foreign/reference integrity, and `quick_check`/`integrity_check`.
- Aggregate CRUD, optimistic conflict, bulk commit rollback, and durable-before-success.
- Representative-volume query plans and bounded pagination.
- A complete admission record for every new table, column, index, view, trigger, write category, or retained data path,
  including the query served, row/byte budget, retention/deletion rule, and representative query plan.
- New current-configuration tables contain only current revisions and delete with their owning user entity; no
  configuration history accumulates.
- Repeated JavaFX/CLI node-setting and platform-service changes replace one bounded current desired record. Where
  needed, one managed last-known-good artifact outside SQLite is atomically replaced. Effective status is recomputed in
  memory; no service-control, settings-revision, listener-change, rollback-generation, or certificate-selection history
  rows append.
- New current/discovered-state storage expires stale entities at its documented TTL across boundary, clock, disabled-
  collection, and restart cases, and enforces its hard cardinality cap under a burst of previously unseen identities.
- Any new rolling/detailed-history schema has finite defaults and hard maximums, indexed automatic pruning, cleanup
  when collection is disabled, retention-reduction/restart catch-up, and a measured retained-volume plateau. Test the
  configured maximum boundary; reject or clamp values above it; ingest bursts above the forecast rate; prove row and
  byte caps evict or reject deterministically; and verify query latency remains bounded at the cap.
- Interrupt cleanup mid-batch and prove retry/idempotency, orphan prevention, bounded transaction duration, and no work
  on decoder or tuner threads. A long-duration plateau alone is not a substitute for these fault and cap tests.
- A disabled web feature creates no feature-owned temporal, cache, job, or audit rows. An offline current-configuration
  identity/revision migration is allowed, but no new stats/activity schema is introduced for raw Events/Messages,
  operations, imports, audits, signal data, tuner telemetry, or browser state.
- Detailed event history is the sole new statistics/event-history row-per-event SQLite datastore exception and remains
  explicit, opt-in, finite, automatically pruned, and row/byte capped. The separately approved recorded-call catalog is
  managed-artifact lookup metadata under §6.7: each compact row must correspond to one retained audio file and expire
  with it; it cannot become a generic call/event history.
- For the recorded-call catalog, validate 1M/5M/10M representative volumes; every system/site/talkgroup/radio/channel/
  time/duration/sort query plan; 50-row keyset boundaries with timestamp ties; batch playlist resolution; media deletion
  plus row deletion; retention reduction; cleanup while recording is off; restart catch-up; interrupted batches; orphan/
  missing-file handling; and bounded reconciliation after a deliberately failed catalog handoff. Prove a catalog row is
  created only after the canonical file exists and a failed/full-disk write creates none; live-only calls and calls whose
  destination talkgroup is not Record-enabled do not appear in Recordings or its talkgroup filter choices.
- Validate the fixed `calls/v1` tree as retained data: only recognized relative paths are cataloged or deleted; date-
  bucket cleanup remains bounded; current name changes never relocate old files; crash-left staging files are cleaned;
  exact filename collisions resolve deterministically; restart reconciliation is idempotent; legacy files remain
  untouched unless an explicit backed-up migration is invoked.
- Exercise every non-SQL retained path: size/time-rotated admin diagnostics, setup/import files and temporary artifacts,
  pre-provisioned/generated test TLS material and any JMBE/module rollback artifact. Verify bounded
  generations or owner deletion, restart/crash cleanup where applicable, atomic replacement, permissions, and
  predictable failure under a full disk; no path may become an unbounded archive.

### 21.4 Web frontend tests

- TypeScript compile/lint and API contract checks.
- Component tests for every field variant, validation, redaction, loading, empty, error, conflict, and reconnect state.
- Shared field-help tests cover hover, keyboard focus, click/tap pinning, Enter/Space, Escape, outside-click and
  open-another dismissal, viewport fitting, and association with the correct field.
- Playwright end-to-end flows for live selection, signal views, editor CRUD, imports, tuner locks, session expiry, and
  permission/transport failures.
- Shared-player flows across Listen and Recordings: first audio gesture, route persistence, Previous/Play/Pause/Skip/
  seek/volume, capped queue/history, list switching, all three result click modes, Follow caught-up/new-call/reconnect/off,
  frozen filters, duplicate add, queue pressure, 0.25-second calls, and ten-current-call display.
- Recordings flows for System-path/direct-Site filtering, 50-row older/latest cursors, coalesced new-results notice,
  empty/no-match/expired/stale/unavailable/locked states, guest playlist reorder/share/open/partial-expiry, malformed/
  oversized fragments, no-persistence proof, and access rechecking.
- Browser refresh/back/forward/deep links and stale edit recovery.
- Contract/component tests prove local-only node/server/TLS controls do not appear as browser mutations; redacted status
  may link operators to documented local maintenance steps.
- Visual tests for anchored FFT/waterfall wheel zoom, click-drag pan, blurry-old/sharp-new refinement, shared dB floor,
  labeled grid, hover guide/readout, pause/resize and bounded symbol history.
- Playwright Chromium/Firefox/WebKit coverage plus manual Safari testing on supported macOS; Playwright WebKit is not a
  substitute for the shipping Safari browser.

### 21.5 Accessibility and usability

- WCAG 2.2 AA target.
- Complete keyboard navigation, visible focus, labels/descriptions/errors, semantic tables/forms, and reduced motion.
- Contextual help is never hover-only or a `title` attribute. Each circled-information icon is a real labeled button
  with at least a 24×24 pixel target, visible focus, expanded-state semantics, and a screen-reader relationship to its
  help content.
- Automated checks plus manual keyboard and screen-reader review.
- Touch/tablet layouts for monitoring; complex administration may remain desktop-optimized but must stay usable.
- Test the retained JavaFX utility separately for keyboard traversal, visible focus, labels/errors, scaling and basic
  screen-reader semantics; it must remain usable without inheriting the radio desktop shell.

### 21.6 Performance and soak tests

The first directional legacy reference is the
[2026-07-19 BOSGAME Swing FFT on/off A/B/A baseline](testing/bosgame-swing-fft-baseline-2026-07-19.md). It measured an
approximately 32.9%-of-one-core increment for the existing enabled RTL-SDR Swing FFT/waterfall, while also documenting
why a longer paired canary is still required for memory, decode, recording, and upload conclusions.

The pre-hardware embedded-server and shared-stream evidence is the
[2026-07-19 Jetty synthetic signal gate](testing/jetty-synthetic-signal-gate-2026-07-19.md), with its separate
[Java 25 JPMS/jlink packaging result](testing/jetty-java25-packaging-gate-2026-07-19.md). The subsequent
[BOSGAME packaged wideband canary](testing/bosgame-webfirst-wideband-canary-2026-07-19.md) passed short Airspy and
RTL-SDR one-viewer plus ten-viewer/ten-audio-feed stages. It does not replace the longer paired core-radio regression,
N100/four-call acceptance workload, reconnect repetition, or soak evidence still required below. That canary predates
the exclusive interactive contract and grants no physical interactive pass credit to the new admin-only,
adaptive-resolution revision.

The later
[exclusive interactive deployment](testing/bosgame-webfirst-interactive-spectrum-deployment-2026-07-19.md) passed
package identity, exact-path lifecycle/cleanup, locked admin boundary, public-status redaction, real-browser layout,
and a 60-second active-radio watch while leaving visualization DSP off. Because the isolated root had no admin
credential at that historical gate, it granted no physical authenticated FFT/refinement/exclusivity pass credit. The
later local provisioning/configured-state restart also grants no such credit until the browser and hardware stages run.

Use a risk-tiered test loop so ordinary UI revisions stay quick without weakening radio-path protection:

1. Browser-only layout, styling, labels, and local presentation changes run syntax/unit/fixture checks plus focused
   browser interaction checks. They do not require a new receiver-node soak.
2. Protocol, authorization, queue, renderer, or lifecycle changes add focused Java/Jetty synthetic tests for bounds,
   reconnect, shutdown, stale-work rejection, and leak cleanup before touching hardware.
3. FFT, tuner attachment, packaging, or any code near sample/decoder/audio/record/upload paths receives a short isolated
   BOSGAME Airspy/RTL canary with before/after timing and queue/memory checks. Test only the affected matrix when the
   shared contract is unchanged; expand immediately if any protected metric moves.
4. Milestone cutovers, shared-runtime changes, dependency removal, persistence work, and release candidates receive the
   full paired BOSGAME regression and required soak/N100-class workload. An optional CUBI reference happens only after
   those BOSGAME gates and follows the separate 15-minute restoration procedure.

Deeper zoom may use more bounded CPU, but no tier may accept a USB/sample buffer regression, decoder/grant delay, audio
gap, recording/upload delay, or monotonic resource growth. Fast iteration changes how much unaffected surface is
retested, not the pass criteria for affected paths.

Measure candidates against a no-web and no-owner baseline:

- idle web server with no spectrum owner;
- one spectrum owner plus ten simultaneous remote browser-audio listeners as the guaranteed minimum cross-feature
  workload, repeated with all ten following Listen lists, all ten browsing/playing Recordings, and a mixed split;
- exclusive-slot conflict, reconnect expiry and release; a second browser must start no second producer;
- 4,096 through 32,768 calculated FFT bins, cropped to the bounded visible payload at each tested frame rate;
- rapid wheel/drag coalescing, stale-revision rejection, target switching and disconnect during refinement;
- browser CPU/GPU/memory and waterfall history over hours;
- JVM heap, GC, native buffers, threads, sockets, network bytes, and dropped frames/events;
- SQLite command/query contention with stats logging active;
- recorded-call catalog insert/lookup/cleanup/reconciliation at representative retention volume, with ten concurrent
  active calls and bursts of 0.25-second calls; measure file descriptors, range-request buffers, queue high-water marks,
  query p95/p99, write latency, cleanup batch time, and retained byte plateau;
- default headless startup with no JavaFX initialization, plus repeated optional JavaFX utility open/close when the
  selected launch model supports it;
- web-listener restart and failed-setting rollback while radio decoding/recording/streaming continuity is measured;
- streaming/recording/audio continuity; and
- 24-hour and multi-day node soak on BOSGAME under CUBI's 2 GB heap cap. CUBI runs never exceed 15 minutes and are not
  soak or leak tests.

The minimum end-to-end acceptance run is headless on an Intel N100-class/8 GB host with browser cost excluded: one
trunked system, continuous control/grant decoding, four simultaneous calls, recording/activity/upload enabled, and ten
remote audio listeners. Compare the approved build and candidate over the same RF/replay workload and capture USB/sample
loss, buffer discards/overruns, control decode quality, grant latency, call setup/completion, audio gaps, recording
completion, upload handoff/completion, queue high-water marks, CPU, heap/GC, native memory, threads, sockets, and database
latency/size. A feature does not pass on average CPU alone.

Acceptance principles:

- decoder/tuner callbacks never block on network, browser, or SQLite work;
- queue sizes and connection counts are bounded;
- one node has at most one web FFT computation and one selected target;
- zero owners means zero visualization work after grace shutdown;
- resource use returns to baseline after repeated connect/disconnect; and
- no listener, executor, RF probe, WebSocket, or session leaks remain.

### 21.7 BOSGAME hardware canary

BOSGAME is the named first Windows canary because it does not carry a production system and currently provides both an
Airspy and an RTL-SDR. Its physical evidence covers those detected devices only; recent evidence identifies an
RTL2832/R828D Blog V4, but each test records the actually detected model. Do not claim physical coverage for RTL
R820T/R820T2, E4K, FC0013, Airspy HF, FCD, HackRF, HydraSDR, or SDRplay without separate hardware.

Use staged gates with an immediately usable rollback manifest. A failed gate restores the approved node state. After a
successful review-candidate gate, leave that exact candidate running on BOSGAME so the owner can inspect it; do not
silently replace it with the prior build merely because automated measurement finished.

Every disposable BOSGAME stage uses an isolated **data root**, not merely a copied database. Disable the `SDRTrunk
BOSGAME Launch` scheduled task, stop the application, and confirm that no Java process remains before copying or
starting anything. Run the same single active package manually against one protected, off-desktop disposable root via
`-Dsdrtrunk.vce.data.root=<absolute-test-path>`; never install a second package and never run the canonical and
disposable roots concurrently. Browser-admin setup, `web.auth.v1`, test TLS material, and JavaFX/CLI web-server settings
are writes, so this isolation begins with the approved-build capped baseline—not only with radio editor tests. At the
end of a successful review-candidate window covering any subset of Stages 1–7, remove temporary measurement helpers,
confirm exactly one candidate process, and leave that tested candidate running for owner review. Keep its isolated data
root explicit and do not import or migrate production streaming profiles. Do not delete a running candidate's data
root; once review ends, stop the process before either deleting the disposable root or promoting the candidate through
Stage 8. Any retained review/evidence state needs an owner, protected location, total-byte cap, and fixed deletion date;
never accumulate canary databases indefinitely. On a failed gate, stop and verify the candidate process, restore the
approved package from its protected matching manifest, verify its hash and that the canonical root was unchanged,
ensure the data-root override is absent from the unchanged launcher/task, and only then re-enable the task.

As documented in [the latency findings](sdrtrunk-latency-findings.md), the current runtime does not yet provide every
quantitative canary metric as a production measurement. Before accepting the Phase-0 baseline, add bounded,
non-persistent instrumentation for control-channel grant age, physical retunes, and post-startup buffer discards. Use
in-memory counters/histograms and/or size-and-time-rotated redacted diagnostic files; never create an SQLite event
history for this evidence. Define reset points, units, sampling windows, and missing-data behavior so approved and
candidate runs are comparable. Where the approved package lacks a hook, build a separately hashed, instrumentation-only
reference from the approved commit and apply the identical hook/configuration to the candidate. Run both only through
the isolated procedure, measure the hook's overhead against the uninstrumented approved package, and never install that
reference build as the canonical release.

1. **Baseline:** inventory the canonical approved node read-only: approved package and JMBE hashes, launcher/task state,
   tuner identities/configuration, systems, JVM flags, listeners, CPU/GC/heap/native memory/threads/sockets, decode
   health, grants, retunes, buffer discards, database integrity/size, WAL size, and table fingerprints. Then use the
   isolated-data-root procedure to run the **approved** package manually with a 2 GB heap and establish the capped
   performance reference. Use the approved-commit instrumentation-only reference above solely for otherwise unavailable
   metrics; do not install or run the migration candidate in this stage. Every later candidate comparison and soak uses
   the same 2 GB cap even though BOSGAME has more host memory, so the result is relevant to CUBI.
2. **Authenticated idle server:** use the disposable/restorable data root defined above; loopback first, then explicitly
   secured LAN HTTPS using locally generated/imported disposable PEM material;
   expose no radio editor/tuner write APIs and attach no signal subscribers. Exercise listener/HTTPS-mode/platform-
   service settings through the local JavaFX utility and protected CLI only, and prove the browser has no
   equivalent mutation route. Account and node-setting writes must remain inside that root. Test both default headless
   and optional JavaFX launch behavior, auth, CSRF, redaction, readiness, manual-launch health, and idle overhead. Only
   inventory/checksum the unchanged task and launcher here; do not claim candidate watchdog behavior.
3. **Read-only Airspy:** use the existing wideband P25 workload for inventory, FFT/waterfall, selected-channel
   FFT/symbols, Events/Messages, and audio. Wideband has one owning browser plus a second occupied-slot attempt; keep ten
   simultaneous call-audio listeners active and exercise slow, disconnecting, reconnecting, zooming, panning, and
   target-changing owner behavior without starting a second web FFT.
4. **Read-only RTL-SDR:** exercise inventory, capabilities, and signal views while the secondary tuner is idle. Do not
   force an Airspy retune or disturb active channels merely to create coverage.
5. **Protocol parity:** use strong and marginal live P25 controls and observed traffic/timeslots. Use deterministic
   recordings/replay for retained P25 variants, DMR, NXDN, NBFM, and auxiliary decoders that live BOSGAME RF does not
   actually exercise. Separate migration fixtures prove that AM, LTR/LTR-Net, MPT-1327, and Passport configurations
   are reported and retired safely rather than decoded. A checked-in fixture manifest pins each recording's
   checksum and protected-artifact location, frequency, sample rate, channel configuration, protocol/timeslot cases,
   and expected Events/Messages/symbol outcomes. Report replay evidence as replay only—never as BOSGAME live-RF or
   physical-tuner coverage.
6. **Writable editors:** continue with the disposable, recoverable data root and a protected off-desktop backup.
   Disable statistics collection for the deterministic editor window and require an exact expected diff for
   configuration and settings objects. Test local stream sinks and RadioReference preview/commit without affecting
   real external destinations; remove temporary configuration afterward.
7. **Tuner commands:** mutate the idle RTL-SDR before the Airspy and drive every field test from the capability
   contract's disruption classification. Bias-T is allowed only when the attached RF chain is explicitly approved.
   While channels are active, Airspy writes are limited to fields explicitly proven non-disruptive. Correction,
   tunable extents, center frequency, sample rate, enable/disable, restart, and every field classified as disruptive
   require the affected channels to be stopped. Also send those requests while active and require `409` with no
   hardware change and no persistence change. Verify apply/persist ordering and restart recovery after the stopped-
   channel cases. Exercise the fixed-center checkbox separately while active: it must not retune or interrupt existing
   channels. Then prove in-band allocation succeeds, out-of-band allocation leaves that tuner unmoved and falls back
   when another tuner is eligible, saved state survives restart, and unlocking alone does not cause a retune.
8. **Web-default soak:** after the explicit canonical-data backup/migration gate, intentionally place the candidate in
   the single active package directory and bind it to the canonical data root under the complete rollback manifest.
   Only here test the candidate through the real scheduled-task watchdog in default-headless mode, the supported local
   JavaFX maintenance/interactive launch, controlled application/task restart, Windows reboot, reconnect, and approved
   hotplug. Follow with at least a 72-hour soak and, before CUBI, a seven-day stability/size-trend soak plus complete-
   manifest restore drill. Include the implemented fixed-PEM HTTPS workflow in its own disposable-root verification;
   do not treat an HTTP-only radio canary as TLS coverage.

Lock quantitative thresholds from the Phase-0 paired baseline before running the candidate. Provisional gates are:
exactly one Java process whenever the application is running; the scheduled task remains checksum-verified and
intentionally disabled during disposable-root Stages 1–7, then healthy under the candidate only in Stage 8; no
crash/watchdog loop or steady-state USB/PLL/buffer errors; strong-control rolling decode health no more than two
percentage points below its paired baseline; once the Phase-0 measurement is validated, grant-age p95 no more than 10 ms
above its paired baseline; authenticated-idle web overhead no more than 5% of one logical core; and heap, native memory,
threads, sockets, and signal-processing work returning to their expected band after viewers disconnect. A missing or
unverified grant-age source fails the evidence gate rather than being recorded as zero. Treat marginal RF systems as
crossover/trend evidence rather than assigning an absolute decode-health floor.

For database changes, baseline the grandfathered tables but judge only the migration's incremental behavior. Confirm
that no unexpected schema object appears, inactive features add no rows, new configuration rows remain bounded by user
entities, and any newly approved temporal schema reaches its documented plateau and still prunes after collection is
disabled and after restart. Seed aged rows in a disposable database and run at least two maintenance cycles rather than
waiting a long real retention horizon; the separate seven-day soak proves stability and size trend.

Abort and roll back on database integrity/schema anomalies, unexpected new persistent data, startup/watchdog loops,
tuner loss or repeated USB/PLL failures, unplanned retunes, sustained decode/grant-latency regression, post-startup
buffer discards, monotonic heap/native/thread/socket growth, secret/auth exposure, write corruption, or persisted versus
effective tuner mismatch. Collect bounded redacted evidence off the desktop. Disable the scheduled task, stop the
candidate, and confirm the Java process is gone before any restore; the one-minute watchdog must not race SQLite or
package replacement. Restore atomically from the matching manifest, including the package, coherently backed-up SQLite
state, and any changed portable JMBE/modules, TLS, or vault artifacts. Re-run `quick_check`, schema/version validation,
task/launcher checksum checks, both-tuner discovery/state, autostart/decode, and expected API/listener/bind checks before
re-enabling the unchanged task. Then verify exactly one process and remove every temporary package, script, backup, and
override from the desktop and launch path.

### 21.8 Packaging tests

- Build/package Windows, Linux, and macOS on x86-64 and ARM64.
- Treat all six current release images as full packages containing the optional JavaFX node-admin utility. Build and
  inspect all six in CI, but do not deploy or launch a migration candidate on the development Mac. Runtime launch and
  hardware smoke evidence comes from BOSGAME; CUBI is limited by its separate 15-minute rule. A future slim
  headless-only artifact would be a separate packaging decision with an explicitly reduced test matrix.
- Verify Java 25, native tuner libraries, JMBE/modules, static assets, NOTICE/license output, and no target Node runtime.
- Verify portable data paths, JavaFX/CLI database bootstrap, first-run browser-admin creation, restart persistence, and
  existing database rejection when an offline migration is required.
- Package all default-headless and focused JavaFX node-admin launchers; use static module/import/launcher inspection and
  platform CI tests for every image, and run the actual Windows launch modes on BOSGAME. Verify minimum JavaFX modules,
  no-display startup, the import allowlist, single data-root ownership, and matching typed setting behavior.
- Revalidate macOS app signing/notarization after server/assets change.
- Live-smoke BOSGAME's launcher and scheduled task without changing their required layout. CUBI candidate launch is an
  optional final minimum-spec reference, never a packaging gate or soak substitute, and follows §23.2 exactly.
- Gate releases on operator documentation for local JavaFX/CLI bootstrap, first browser login, TLS provisioning/trust
  and firewall exposure, admin reset, headless versus maintenance launch, browser support, migration/backup/restore,
  rollback, and receiver-node deployment.

### 21.9 JavaFX PEM certificate verification

The implemented bounded workflow must pass these gates before HTTPS is treated as receiver-node ready:

- certificate PEM chains and unencrypted PKCS#8 private-key PEM use strict file/count/byte limits and defensive parsing;
- private-key/certificate match, chain order, SANs, validity, key strength, and supported algorithms are validated;
- no private material enters SQLite, Java Preferences, APIs, logs, diagnostics, or browser import/export routes;
- fixed staging files are atomically replaced, interrupted paired installs fail closed, and staging remains bounded;
- HTTP/HTTPS selection creates only one listener, HTTPS/WSS/auth cookies behave correctly, and a bad TLS pair fails
  only the web listener; and
- BOSGAME disposable-data-root generate/import, packaged Java 25/JPMS launch, and short radio-continuity evidence pass
  before any corresponding CUBI change.

## 22. Web-first design rules and post-parity optimization

Establish the information architecture and annotated low-fidelity mockups before implementation. Do not reproduce radio
desktop windows as a collection of modal browser dialogs. During each feature slice, apply the approved web-first
patterns; after parity, perform a dedicated product/performance/accessibility pass on both final surfaces without
expanding the JavaFX allowlist:

### 22.1 Information architecture

- Operations: Dashboard, Live Systems, Events/Messages, Listen, Recordings, and one shared browser-audio player.
- Administrator Settings — Playlist: Hardware and Tuners (including the full-width on-demand Spectrum workspace),
  Channels, Aliases, Listen lists, Streaming, and RadioReference. There are no separate Channel Maps or Icon Manager
  pages.
- Web administration: Radio Preferences, activity/statistics storage, browser password/sessions, Diagnostics, Modules,
  About/Support, plus redacted read-only node/listener/TLS/platform-service status.
- Local JavaFX node administration: Server/Listener, allowlisted Platform Services, Bootstrap/Recovery, and a disabled-
  until-implemented Certificate/Keystore area. These are not browser routes.
- Keep selected system/site/channel context while moving between Live, Events, Messages, and Signal views.
- Use deep links for every stable entity and recover gracefully when a runtime entity disappears.

### 22.2 Large-data interaction

- Opaque keyset/cursor pagination and indexed filtering for persisted data; Recordings loads 50 at a time and never uses
  offset pagination or an exact-million count in the normal browse path.
- Browser virtualization for bounded live tables.
- Debounced search with cancellation and stale-response protection.
- Batch endpoints instead of one HTTP request per alias/channel.
- Column presets, saved filters, and browser-local widths.
- Avoid loading full alias/channel/provider datasets into every route.
- Coalesce bursts of newly completed recordings behind an explicit `N new recordings — Show` action instead of
  reshuffling the current result page.

### 22.3 Signal UX

- Adaptive frame rate/resolution based on viewport, visibility, device capability, and server pressure.
- Shared color/scale semantics between wideband and channel signal views.
- Keyboard/touch zoom and accessible numeric readouts alongside graphics.
- Visible dropped-frame/reconnect/degraded state without interrupting decoding.

### 22.4 Listen and Recordings UX

- Keep one player/queue alive across Listen and Recordings navigation; never duplicate or reset it per route.
- Keep queue, recently played calls, volume, selected Listen list, Follow state, and guest-playlist draft bounded and
  browser-local.
- Preserve useful precision for short calls, including `0.25 sec`, and test a peak display of at least ten concurrent
  calls plus a short recently-ended buffer.
- Keep Live activity separate from the audio queue. A row may be actively receiving before its completed audio is ready
  to play.
- Treat the selected Listen list as the only live browser-audio eligibility control. Do not expose or consult the old
  Alias Listen checkbox; show Record only where future saved/searchable audio is configured.
- Make Play now, Add to queue, and Play forward explicit. Follow new calls stays on until the listener turns it off;
  pausing playback does not turn it off.
- Guest playlist links create no database write, do not grant access, and do not extend recorded-call retention.

### 22.5 Form UX

- Progressive disclosure for device/provider/decoder-specific fields.
- Put the same small circled-information button beside every Settings field or field group. Hover or keyboard focus
  shows its bundled plain-language explanation temporarily; click or tap keeps it open. Escape, clicking elsewhere,
  or opening another help explanation closes it.
- Help explains what the field changes, when to use it, what blank/default means, and important side effects. Required
  values, validation errors, safety warnings, units, ranges, and restart/disruption effects remain visible beside the
  control instead of being hidden only in help.
- Units, valid ranges, defaults, and restart/interrupt effects beside controls.
- Dirty-state and revision-conflict handling that preserves user input.
- Preview/impact pages for deletes, renames, imports, bulk changes, and disruptive tuner/channel actions.
- Secrets never prefilled; show only configured state and replacement/clear actions.

### 22.6 Asset and runtime optimization

- Fingerprinted, compressed static bundles and route-level lazy loading.
- Web Worker parsing/rendering for signal data.
- Coalesced low-rate events and bounded query payloads.
- CSS design tokens, consistent status colors, dark/light themes, and reduced-motion support.
- No outbound product telemetry. Expose local diagnostic counters to the administrator.

## 23. Rollout and rollback

### 23.1 Feature flags during coexistence

Use explicit, temporary flags for:

- general web application/auth;
- each web editor;
- web signal telemetry;
- legacy **radio** desktop write access; and
- web-radio-default versus legacy-radio-desktop-default launch.

Flags are migration tools, not permanent compatibility branches. Every flag must have a removal issue and gate.
The retained JavaFX node-administration utility is a permanent launch mode and is not placed behind a retirement flag.

### 23.2 Canary order

1. Cross-platform compile, package, contract, module, and static launcher checks without deploying or launching the
   migration candidate on the development Mac.
2. BOSGAME authenticated-idle and read-only Airspy/RTL-SDR stages.
3. BOSGAME disposable-data-root editor tests and RTL-first/Airspy tuner-command stages.
4. BOSGAME deterministic replay for protocols/workloads not naturally available on its two tuners.
5. BOSGAME web-radio-default soak, default-headless plus optional-JavaFX launch checks, N100/8 GB/ten-listener workload,
   and complete
   package/database/portable-artifact restore drill.
6. Optional CUBI minimum-spec reference run only after all corresponding BOSGAME gates pass, for no more than 15 minutes
   elapsed time, followed immediately by restoration and production-upload verification. CUBI is not a soak host or
   continuing candidate deployment stage.

Preserve Java 25 and receiver-node launcher/watchdog requirements throughout. Do not leave comparison builds, backups,
deploy scripts, or old release folders on receiver desktops.

CUBI reference runs are headless and exclude browser workload from the receiver. Use isolated scratch state or a
schema-compatible read-only candidate; do not migrate, alter, or start the candidate against CUBI's production database.
Never import, copy, or migrate its production streaming profiles, credentials, or destinations into the test state, and
never copy a BOSGAME profile/database to CUBI. Do not exercise real provider destinations during the candidate run.

Before a CUBI run, record the approved production package/profile/task/launcher/JMBE hashes and state, stop the scheduled
task and production JVM, confirm no Java process remains, and start the candidate under an explicit wall-clock guard.
At or before 15 minutes, stop the candidate and remove its temporary package, data-root override, test state, and helper
artifacts. Restore the exact approved production build and unchanged production profile, launcher, task, JMBE/modules,
JVM flags (`-Xmx2g`, `-Dsun.java2d.d3d=false`), and data root. Verify exactly one production JVM, tuner discovery,
autostart/control decoding, recording, and listener/API health, then observe a **new** production call upload
successfully to RadioResolve. A failed restoration or upload keeps the test failed and triggers recovery; it never
extends the candidate window.

### 23.3 Retirement policy

For each radio-facing desktop feature:

1. Land neutral services and tests.
2. Run web and desktop reads from the same source.
3. Route desktop writes through the same command service.
4. Complete automated parity and operator acceptance.
5. Make web authoritative and legacy view read-only/hidden.
6. Soak for at least one complete canary/release cycle.
7. Delete the radio desktop code in a later change.

For a retained local JavaFX surface, use a different gate: extract the typed node service, classify each field against
the allowlist, delete radio/unclassified fields and direct persistence access, prove headless independence and package
both launch modes, then retain the focused adapter. It must never duplicate a web radio mutation.

### 23.4 Database rollback

Once a schema version changes, application rollback requires both:

- the previous application package; and
- the matching pre-migration database backup.

Never try to make an older package open a newer schema. Document and rehearse restore before deployment.
Store migration backups in an approved protected backup location with free-space, checksum, access-permission, and
restore checks; copy off-node when policy permits. Do not leave database backups beside comparison builds or deploy
scripts on receiver-node desktops.

On Windows, disable the repeating scheduled task before stopping the application and confirm that no Java process
remains. Create the pre-change backup with a coherent SQLite method: checkpoint and close cleanly before copying, or use
the SQLite backup API; never copy only the main file while an unaccounted WAL is live. Record a rollback manifest that
binds package hash, database/schema version and backup hash, launcher/task hashes, JMBE/modules, and any TLS/vault or
other portable mutable artifact changed by the candidate. For rollback, keep the task disabled, restore that complete
matching set atomically, deal with database/WAL/SHM files as one coherent state, and validate `quick_check`, expected
schema/version, file hashes and permissions before re-enabling the task. After restart verify one Java process, both
tuners and their state, configured autostart/decode, and the expected API/listener/bind; otherwise keep the node stopped
and continue recovery.

## 24. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| HTTP threads mutate UI/runtime objects | Immutable DTOs, neutral services, serialized commands, architecture tests |
| LAN trust is mistaken for admin security | Real auth/CSRF/session controls; HTTPS required off loopback |
| Spectrum multiplies CPU or state per client | One exclusive authenticated owner, one target/producer, one latest-frame slot, and strict DSP/wire caps |
| Decoder thread blocks on symbol/messages | O(1) taps, bounded queues, worker normalization, drop-oldest policy |
| Whole-table saves lose edits/identities | Stable IDs/revisions, aggregate transactions, conflict responses |
| Direct serialization leaks credentials | Allowlisted DTOs, write-only secrets, automated redaction tests |
| Retiring radio JavaFX breaks runtime models | Neutral canonical models; JavaFX confined to a node-admin adapter with no radio imports |
| Tuner change disrupts decoding | Per-device executor, precondition recheck, impact preview/confirmation |
| RadioReference creates partial config | Deterministic preview and one transactional commit |
| Browser assets/backend drift | Fingerprinted assets and API/build version handshake |
| Web UI has no current test harness | TypeScript contract, component tests, Playwright from platform phase onward |
| JPMS/jlink/server change breaks packages | Phase-0 packaging spike and six-target CI/smoke gates |
| Unattended startup acquires a JavaFX/display dependency | Headless construction/class-load tests, safe defaults, and protected CLI provisioning/recovery |
| Schema rollback is impossible in place | External migration, matching backup, rehearsed package+DB restore |
| New web persistence grows without bound | Grandfather existing schemas, but require admission records, finite limits, automatic pruning, and BOSGAME evidence for every new temporal data path |
| A first Windows test disrupts production reception | Use BOSGAME as the exclusive first Windows canary and hold CUBI until the matching gates pass |
| Retained JavaFX grows into a second radio UI | Named field-level ownership ledger, deny-by-default node-admin allowlist, and architecture tests |
| Web and JavaFX both mutate node settings | No browser mutation routes; one neutral node-settings/service owner shared only with local JavaFX and CLI |
| A bad bind/TLS setting locks out remote operators | Local JavaFX recovery remains available; listener failure is isolated from radio runtime; fixed PEM files can be repaired offline |
| A JavaFX sidecar corrupts live state | Same-JVM or exclusive-maintenance design; process/data-root lock; no companion direct SQLite writer |
| Local OS/RDP compromise reaches privileged node controls | Treat local session as the trust boundary, enforce OS ACLs and confirmations, and record bounded redacted audit events |
| Certificate material leaks through API/temp/log/backup | No web route; fixed managed directory, strict redaction, bounded staging cleanup, and admin-controlled filesystem backup |
| Restarting a “service” disrupts radio | Classify radio services as web-owned; isolate web-listener restart and test decode/record/stream continuity |

## 25. Decision register before implementation

The recommended technology defaults below should be confirmed in Phase 0. The general per-feature public/admin-only
model, permanently admin-only exclusive wideband workspace, and RF-probe/resource-command lease boundary are
implementation-approved product decisions; Phase 0 measures safe numerical caps but does not reopen those semantics.

| Decision | Recommended default |
| --- | --- |
| Embedded server | Jetty core/WebSocket on the current single port, after Java 25/JPMS packaging spike |
| Frontend | TypeScript + Vite + React; compiled assets packaged with the application |
| Frontend development | Mock/fixture UI, BOSGAME live-proxy with Vite HMR, and packaged release modes; frontend-only work needs no Java rebuild until packaged verification |
| High-rate transport | Binary server frames plus small access-policy-checked JSON subscribe/update/unsubscribe control frames; wideband is one authenticated owner and sends cropped adaptive viewport frames |
| Low-rate transport | SSE with bounded replay/resnapshot |
| Anonymous access | Per-feature `PUBLIC`/`ADMIN_ONLY`; Live, Listen, Recordings, Events, and Messages are independently configurable and off by default for new profiles; wideband and Live's Signal/Symbol actions are permanently `ADMIN_ONLY` |
| Listener acceptance | Ten simultaneous remote browser-audio listeners on the minimum headless receiver workload, tested as Listen-only, Recordings-only, and mixed; no durable listener identities/history |
| Shared player | One bounded browser-local player/queue/history persists across Listen and Recordings; no route-local duplicate players and no server-side listener state |
| Play forward | Freeze the current recording filters, lazily load newer matches, and keep following new calls until the listener turns Follow new calls off |
| Listen lists | Current revisioned administrator configuration containing stable talkgroup/channel references; membership is the authoritative live browser-audio gate and bypasses legacy Listen/Do Not Monitor, Record, and channel technical-recorder choices without changing them; stores no use/history records |
| Guest event playlists | Versioned compressed ID payload in the URL fragment, initially capped at 200 calls/8 KiB; no database write, no authorization grant, and no retention extension |
| Initial account model | Exactly one current admin credential in `application_settings`; reset replaces it and permission-ready route guards remain |
| Remote admin | HTTPS only; loopback HTTP permitted |
| Admin sessions | Exactly one bounded in-memory browser session; tabs share it, a newer successful login ends the older session, and restart logs out |
| High-rate signal concurrency | Wideband permits one authenticated browser connection, one selected tuner, and one node-wide web FFT; other signal features require separate bounded contracts |
| Configuration concurrency | Stable UUID + revision with optimistic conflict |
| Existing database schemas | Grandfathered at the planning baseline; no retroactive statistics/retention redesign required by this migration |
| New database persistence | Current user configuration may persist; all new operational/statistical/history data must be classified and bounded; no revision/audit/job history tables |
| Recorded-call catalog | Explicit managed-artifact metadata exception under §6.7: only a successfully written call whose destination talkgroup was Record-enabled receives one compact row; delete it with the file under separate finite media retention after admission/migration/query-plan evidence |
| Recorded-call directory layout | Fixed immutable `recordings/calls/v1/YYYY/MM/DD/system/site/channel/talkgroup/file` application convention under the configured root; one canonical audio file, no template or layout setting, and no silent legacy-file relocation |
| New detailed event history | Explicit opt-in exception only, disabled by default, finite retention, row/byte capped, and automatically pruned |
| Transient event/message storage | Bounded memory only; no raw-message database table |
| FFT ownership | One node-wide web producer for the owner's one selected target; browser owns display history, palette, and optional averaging while smoothing/window choices use built-in defaults |
| Radio preferences | Authenticated web UI over typed neutral services |
| Presentation preferences | Administrator-set public defaults with browser-local visitor overrides; no per-listener database rows |
| Settings contextual help | Universal accessible circled-information button with bundled static help; no runtime request or stored help activity |
| Node server/platform-service settings | Typed current settings edited by local JavaFX or protected CLI; web status read-only, with no mutation routes |
| Legacy radio desktop | Temporary adapter, feature-by-feature read-only, then deletion |
| Retained JavaFX utility | Permanent narrow node-admin adapter; no radio imports/controls, direct persistence, or second data-root owner |
| Launch model | Default unattended headless launch plus explicit local JavaFX interactive/maintenance mode; exactly one owning JVM |
| HTTPS certificate material | Implemented local JavaFX self-signed generation and fixed PEM import; no browser upload route or retained history |
| Alias Listen/Do Not Monitor | Do not port as a web control; Listen-list membership replaces its live eligibility role, while numeric Priority remains an ordering hint and legacy stored values survive only until desktop playback retirement |
| Alias actions | Retire Beep, Audio Clip, and Script completely; no web or retained-JavaFX editor/executor, managed clip upload, or replacement engine |
| Web assets | Immutable/fingerprinted in production, external override in development only |
| First Windows canary | BOSGAME with Airspy and detected RTL-SDR; CUBI is held until the corresponding staged gate passes |
| Successful test disposition | Remove temporary helpers and leave the exact tested candidate running on BOSGAME for owner review; failed gates restore the approved build |
| Tuner-editor retirement | Contract/fake coverage for every retained family plus named physical evidence per family; BOSGAME alone gates only its detected Airspy and RTL-SDR |
| Fixed tuner center frequency | Default off per tuner; prevents automatic allocation/pre-position retunes and accepts only channels in the current usable passband; remains distinct from active-channel locks and does not block an idle administrator's explicit center-frequency change |
| CUBI reference use | Optional headless run of 15 minutes or less with isolated/no-production-stream state, then exact production restoration and a new verified RadioResolve upload; never a soak or ongoing candidate deployment |

## 26. First implementation milestone after design approval

The applicable [legacy UI reference catalog](ui-reference/README.md) evidence and first-slice mockup must be approved
first. The wideband mockup now satisfies that gate for this foundation milestone; it does not authorize another radio
editor or user-facing slice. This milestone should prove the architecture safely:

Implementation snapshot, updated 2026-07-20: Jetty/WebApplicationService, compatibility-route remounting, bounded SSE,
feature-policy enforcement, the SFFT stream, browser worker/canvas rendering, headless guards, Java 25
JPMS/current-platform jlink, Windows x86-64 packaging, and the historical short BOSGAME Airspy/RTL foundation canary
are implemented. The packaged BOSGAME review candidate additionally supplies the one-account credential/session/CSRF
foundation, clear-HTTP loopback restriction, permanent admin-only access and one spectrum connection, one Airspy-or-
RTL target selector, adaptive 4,096-32,768-point FFTs, a transmitted crop of at most 4,096 bins, wheel zoom, click-drag
pan, blurry/refining replacement, shared dB floor, labeled grid, and synchronized hover readouts. It adds no database
schema or retained signal/session history. The same package includes the focused `--server-admin-ui` web-server/account
utility and exact-data-root lock. Both normal headless launch and the actual JavaFX window were smoke-tested on BOSGAME;
the window opened and closed with no web listener, a concurrent launch against the occupied root failed closed, and the
normal headless candidate resumed afterward. The server configuration now uses a single canonical listen address,
default `127.0.0.1:8090`, with no LAN/Tailscale mode. The same bounded Jetty listener can use HTTPS backed by fixed
portable PEM files; local JavaFX can generate self-signed material or select both certificate and key files for one
validated atomic pair import. No
database schema, certificate history, or retained TLS rollback generation was added.

The current page remains transitional plain JavaScript inside `stats-web`; it does not replace the planned
TypeScript/Vite/React frontend or BOSGAME Vite live-proxy workflow. Local credential entry and the configured-state
restart are complete. Authenticated BOSGAME browser/login validation, the new exclusive/adaptive BOSGAME Airspy/RTL
gate, persistent policy controls for other features, local node-setting ownership, the expanded permanent Phase-14
node/service utility, complete runtime extraction, all-six packages, signing, receiver-node TLS verification, long
regression/soak gates, and
later radio slices remain open. The focused
transition utility and its BOSGAME launch smoke do not complete those broader Phase-14 responsibilities. The historical
canary's anonymous/public mode documents only the superseded foundation and is not an allowed release configuration for
wideband spectrum.

1. Add the five-way ownership/surface ledger, JavaFX dependency baseline/ratchet and node-admin import allowlist,
   forward-only database admission ledger, and baseline tests.
2. Extract enough runtime lifecycle for the default path to start without constructing `SpectralDisplayPanel` or
   initializing JavaFX; separately smoke a minimal optional node-admin adapter over a fake typed service.
3. Prototype the new embedded server on an ephemeral port with one authenticated REST route, one bounded SSE route, one
   binary WebSocket echo/synthetic FFT stream, and the minimal real local-only node-settings/lifecycle slice for
   listen-address/HTTPS-mode/platform-service desired settings. Persist current desired settings only; use locally
   generated or imported test TLS material and expose node status—but no node mutation—to the browser.
4. Build a minimal TypeScript page that logs in and renders the synthetic signal stream in a worker/canvas.
5. Build/package default-headless and optional-JavaFX launch modes for Mac, Windows, and Linux images under Java 25;
   perform runtime smoke on BOSGAME, not the development Mac or CUBI.
6. Measure idle and active cost under the 2 GB heap limit.
7. Run the packaged candidate through BOSGAME's authenticated-idle gate on the isolated disposable/restorable data
   root without schema changes, radio editor/tuner write APIs, or signal subscribers; browser-auth and local JavaFX
   node-setting writes remain confined to that root, and the browser exposes no node-setting mutation.
8. Remove the prototype path or turn it into tested platform infrastructure only after the spike passes.

That milestone de-risks the server, authentication, local node-setting ownership, transport, frontend build, JPMS,
jlink, and packaging before the project touches live tuner data or **radio** configuration persistence. Browser-auth and
minimal node desired settings are intentionally the only configuration persistence in the milestone.

## 27. Completion checklist

- [ ] The source-identified legacy UI catalog classifies every surface as captured, synthetic, source-only, hardware
      unavailable, or retired, and its secret/sensitivity review passes.
- [ ] Each annotated public-listener, radio-admin, signal, configuration, radio-preference, and retained-local-JavaFX
      mockup is approved before its corresponding runtime feature slice begins and cites preserve/adapt/combine/retire
      evidence; the wideband signal mockup is approved.
- [ ] Every surface is classified as web-radio, browser-local, retain-local-javafx, CLI/headless, or retire.
- [ ] True headless startup/shutdown passes without JavaFX initialization or a display.
- [ ] The retained JavaFX utility is confined to the node-admin allowlist, calls typed services only, and cannot run as
      a second concurrent data-root owner.
- [ ] Every retained node/server/platform-service field and action passes read/write/validation/restart/readiness/
      rollback parity before its broad legacy Preferences source is deleted; deferred certificate import is excluded.
- [ ] One same-origin server owns all routes and enforces each route/stream's public-versus-admin-only policy.
- [ ] Admin threat model and security tests pass.
- [ ] Browser contracts expose no mutation route for listener, bind, TLS material, or local platform services.
- [ ] Wideband FFT/waterfall uses one authenticated-admin workspace, one target/producer and cropped adaptive viewport;
      a second browser starts no DSP work. Other signal features follow their separately approved bounded concurrency
      and access contracts.
- [ ] Events/Messages preserve site, frequency, timeslot, mutation, and reconnect semantics.
- [ ] Stable configuration IDs/revisions and offline migration are deployed safely.
- [ ] Every new database object, write category, and SQL/non-SQL retained path has an admission record; no new
      unbounded statistics, history, operation, audit, cache, signal, or browser-state persistence exists.
- [ ] Any new temporal schema proves finite automatic pruning with collection disabled and after restart; detailed
      event history remains the only explicit row-per-event exception.
- [ ] Playlist, aliases, streams, RadioReference, and tuner settings pass parity gates.
- [ ] Legacy script alias actions, editor, execution path, configuration support, and UI are retired with no replacement
      remote script engine.
- [ ] Remaining radio audio/map/tools/onboarding surfaces are ported, replaced, or explicitly retired.
- [ ] Preference fields are partitioned: radio to web, presentation to browser-local, node/server/service to focused
      JavaFX/CLI; generic Java Preferences persistence is removed when no non-UI code needs it.
- [ ] Legacy radio desktop writes are disabled before radio desktop code deletion.
- [ ] Swing UI references reach zero; JavaFX references are confined to the allowlisted node-admin package and its
      tests/packaging support.
- [ ] Unused UI dependencies/JPMS modules are removed, the minimum retained JavaFX set is justified by `jdeps`, and all
      six target packages pass build/static/module/launcher checks; both actual Windows launch modes pass on BOSGAME
      without deploying the candidate to the development Mac.
- [ ] BOSGAME Airspy/RTL staged gates, N100-class headless/four-call/ten-listener workload, long soak, and restore drill
      pass before any optional CUBI reference run.
- [ ] Each later preference-partition, radio-desktop-removal/local-node-admin-boundary, and optimization artifact repeats
      its applicable BOSGAME package/data-root/restore and soak gates; CUBI never substitutes for them.
- [ ] Every CUBI reference run, if performed, lasts no more than 15 minutes, uses no migrated production streaming
      profile or production database migration, restores the exact production build/profile/task, and verifies a new
      successful RadioResolve call upload.
- [ ] Web-radio and retained-node-admin UX/accessibility plus bandwidth, browser-memory, JVM-memory, and SQLite budgets
      pass.
- [ ] Legacy radio API/assets/feature flags/adapters are removed; the intentional local JavaFX adapter remains.
- [ ] The implemented PEM/HTTPS workflow passes every applicable §21.9 gate before receiver-node release.
