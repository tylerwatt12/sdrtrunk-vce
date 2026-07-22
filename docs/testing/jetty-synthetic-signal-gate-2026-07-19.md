# Jetty synthetic signal gate — 2026-07-19

> Historical passive-fan-out evidence. The released design now has one exclusive authenticated-admin spectrum
> workspace; ten simultaneous users remains the call-audio workload. Current implementation/deployment evidence is in
> the [exclusive interactive spectrum report](bosgame-webfirst-interactive-spectrum-deployment-2026-07-19.md).

## Scope

This gate exercises the new embedded Jetty lifecycle, public/admin policy, same-origin WebSocket handshake, shared
binary FFT broker, reconnect grace, bounded one/ten-viewer fan-out, and deterministic cleanup before a physical tuner
is connected. It adds no schema or persistent runtime data.

The local probe ran on Java `25.0.1` with `-Xms64m -Xmx256m`. It is an architecture/lifecycle gate, not the BOSGAME
radio-regression result. The short physical Airspy/RTL and ten-audio-feed follow-up is recorded in the
[BOSGAME wideband canary report](bosgame-webfirst-wideband-canary-2026-07-19.md); longer paired regression and soak
gates remain open.

## Automated results

The combined focused suite passed with no failures:

```text
./gradlew test \
  --tests 'io.github.dsheirer.stats.*' \
  --tests 'io.github.dsheirer.web.*' \
  --tests 'io.github.dsheirer.spectrum.stream.*'

BUILD SUCCESSFUL
```

Covered behaviors include:

- Java 25 ephemeral bind, repeated start/close, and no retained named Jetty threads;
- all existing JSON, static, WAV, and SSE routes remounted behind Jetty;
- ten simultaneous SSE clients without reserving Jetty platform workers;
- anonymous HTTP/SSE/media access while public and authenticated-admin access when switched to admin-only, using an
  injected test subject resolver rather than a deployed login/session service;
- anonymous public and authenticated admin WebSocket handshakes, same-origin rejection, and immediate live anonymous
  revocation on `PUBLIC` to `ADMIN_ONLY`;
- one and ten signal viewers using one upstream source and one cached SFFT payload per frame;
- latest-only per-viewer delivery, fixed subscriber/session caps, reconnect inside producer grace, and clean shutdown;
- a fake running tuner tap whose DFT executor and sample listener terminate when the last subscription leaves.

The JPMS jar and current-platform jlink image also pass; see
[the Java 25 packaging gate](jetty-java25-packaging-gate-2026-07-19.md).

## Bounded lifecycle probe

The manually invoked `SyntheticSignalLifecycleProbe` reported:

| Phase | Heap used | Non-heap used | JVM threads | Sessions | Subscribers | Source starts/stops |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Jetty idle, no viewers | 7.89 MiB | 16.34 MiB | 8 | 0 | 0 | 0 / 0 |
| Ten viewers | 23.16 MiB | 28.25 MiB | 31 | 10 | 10 | 1 / 0 |
| Reconnect within grace | 44.47 MiB before the next forced GC | 29.79 MiB | 55 | 1 | 1 | 1 / 0 |
| Returned idle after grace + forced GC | 12.30 MiB | 29.74 MiB | 55 | 0 | 0 | 1 / 1 |
| Closed + forced GC | 12.23 MiB | 29.62 MiB | 30 | 0 | 0 | 1 / 1 |

The post-load heap returns below the ten-viewer level and remains effectively unchanged across returned-idle and
closed phases. The higher post-load non-heap/thread counts include loaded Jetty/JDK HTTP-client classes and the probe's
client executor; the dedicated repeated-lifecycle test separately verifies that no `sdrtrunk web` thread remains after
close. The historical probe capped Jetty at 16 platform threads, signal sessions/subscribers at 16, pending WebSocket
frames at two, and each signal subscriber at one replaceable pending spectrum frame. The current wideband transport
instead permits one exclusive authenticated-admin workspace/connection while retaining bounded latest-only delivery.

This short probe does not claim long-term leak freedom. The subsequent short BOSGAME hardware gate passed, but repeated
connect/disconnect windows and the later soak remain required, with heap/native/thread/socket trends and radio latency
compared to the approved build.

## Runtime safety decisions proven here

- USB/sample callbacks only enqueue into the existing bounded native-buffer manager. FFT, float-to-dB conversion,
  SFFT serialization, socket writes, browser work, and database work execute elsewhere.
- Frame publication uses atomic latest-only slots and never waits for a viewer.
- A frame is encoded lazily on a transport thread; the current single owner receives the latest read-only payload.
- Zero subscribers stops FFT work after a three-second reconnect grace.
- Waterfall history, palette, smoothing, pause, and view state remain browser-local and never enter SQLite.
- Wideband uses no RF-resource lease but holds one transient exclusive workspace slot. RF leases remain reserved for
  probes or commands that change RF/resource state.
