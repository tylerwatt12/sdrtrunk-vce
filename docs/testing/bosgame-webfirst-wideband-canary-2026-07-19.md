# BOSGAME web-first wideband canary — 2026-07-19

> Historical foundation evidence: after this canary passed, the approved product contract changed to one permanently
> admin-only, exclusive interactive spectrum workspace. The ten-spectrum-viewer results below remain useful proof that
> browser/network fan-out could not backpressure the radio path, but later revisions replace that workload with one
> spectrum owner plus ten call-audio listeners, adaptive-resolution/viewport churn, occupied-slot rejection and release.
> The later locked-state deployment is recorded separately in the
> [exclusive interactive spectrum report](bosgame-webfirst-interactive-spectrum-deployment-2026-07-19.md).

## Result and scope

The first packaged web-first signal slice passed its short BOSGAME hardware canary with both the Airspy and RTL-SDR.
One shared, demand-driven FFT producer served one and ten passive WebSocket viewers, and the ten-viewer runs also held
ten independent public live-call feeds that fetched real WAV responses. The candidate is headless and capped at a 2
GiB heap. Browser cost is not included in receiver process measurements.

This is a short foundation gate, not the final N100-class acceptance test or a leak/decoder-parity soak. It does not
claim four simultaneous call coverage, RadioResolve upload continuity, full admin login, all-six-platform packaging,
or completion of the approved mockup.

## Candidate identity and isolation

| Item | Value |
| --- | --- |
| Application JAR SHA-256 | `15466582AEBB2577ED2BEDD9058B1DBF46BCD117A3BCCC8D3C8A98AE767CAEAB` |
| Windows x86-64 ZIP SHA-256 | `021A2BFAD15B10453B4D3F2148E94AF0C46B06C0A7E912007E3C8273162AAD8F` |
| Windows ZIP bytes | 100,320,462 |
| Application JAR bytes | 8,170,224 |
| JMBE SHA-256 | `2799BE21A2629802D7BD990815307BA837DD7DE2B254CB77B67866408E658DA2` |
| Runtime | Packaged Windows x86-64 Java 25 image, `-Xmx2g`, `java.awt.headless=true` |
| Candidate data root | `C:\Users\Owner\AppData\Local\SDRTrunkCanary\webfirst\data` |
| Protected rollback root | `C:\Users\Owner\AppData\Local\SDRTrunkRollback\webfirst` |

The candidate used a complete isolated copy of BOSGAME's own data. No CUBI or production streaming profile was copied.
The canonical BOSGAME data root remained offline to the candidate and its full file manifest was verified unchanged
after the final test. The active install's runtime and launcher are the candidate; only the canonical data root is
unchanged.

The fixed canary root has a 4 GiB cap and a review/removal date of 2026-08-02. Its final audited size was 1,601,548,353
bytes, below the cap.

## Test method

- The existing Swing FFT on/off A/B/A reference was captured first; see
  [the baseline](bosgame-swing-fft-baseline-2026-07-19.md).
- Synthetic Java 25, Jetty lifecycle, reconnect, one/ten-viewer, policy, and cleanup tests passed before a hardware
  tuner was connected.
- The physical sequence was one Airspy viewer, ten Airspy viewers plus ten audio feeds, one RTL-SDR viewer, then ten
  RTL-SDR viewers plus ten audio feeds.
- Each simulated user had its own HTTP client. Signal clients validated the bounded SFFT frame header and tracked
  sequence order, target generation, frame gaps, and stalls. Each audio client held its own SSE feed and fetched WAV
  content from announced call events.
- Process CPU is percentage of one logical core. Working set, private committed bytes, threads, and handles were sampled
  independently on BOSGAME. Averages below cover the 120-second measured windows.

No test changed tuner gain, frequency, sample rate, channel configuration, decoder configuration, or streaming
profiles. Tuner class selection used a non-identifying fail-closed startup selector.

## Physical-tuner results

### One viewer

| Target | Window | Delivered rate | Sequence/order/stalls | Maximum server send | Maximum delivery gap |
| --- | ---: | ---: | --- | ---: | ---: |
| Airspy, 4096 bins at 10 MHz | 30 s, 600 frames | 19.999 fps | 0 / 0 / 0 | 5.984 ms | 52.828 ms |
| RTL-SDR, 4096 bins at 2.4 MHz | 30 s, 600 frames | 19.999 fps | 0 / 0 / 0 | 7.128 ms | 53.304 ms |

The short one-viewer probes were used to verify the exact rebuilt artifact's pacing, protocol, and source lifecycle;
the process sampler was attached to the longer cross-feature windows below.

### Ten signal viewers plus ten audio feeds

| Metric | Airspy | RTL-SDR |
| --- | ---: | ---: |
| Measured duration | 120 s | 120 s |
| Total signal frames | 24,000 | 23,986 |
| Minimum/maximum frames per viewer | 2,400 / 2,400 | 2,395 / 2,401 |
| Frames per viewer-second | 20.000 | 19.988 |
| Sequence skips / out-of-order / target changes | 0 / 0 / 0 | 24 / 0 / 0 |
| Stalled viewers | 0 | 0 |
| Server frames published / delivered | 2,407 / 24,052 | 2,407 / 24,030 |
| Failed sends / tuner publication errors | 0 / 0 | 0 / 0 |
| Maximum server send / delivery gap | 26.901 / 64.652 ms | 22.283 / 101.254 ms |
| Jetty queue high-water observed | 0 | 0 |
| Call events observed | 20 | 110 |
| WAV requests succeeded / failed | 20 / 0 | 110 / 0 |
| Feed clients with successful WAV / required | 10 / 10 | 10 / 10 |
| WAV bytes | 1,008,880 | 2,884,840 |
| Process CPU average / maximum, one core | 122.648% / 203.608% | 112.812% / 188.904% |
| Working set average / maximum | 425,225,216 / 449,126,400 B | 454,635,930 / 476,934,144 B |
| Private commit average / maximum | 507,480,337 / 522,870,784 B | 516,430,711 / 530,894,848 B |
| Threads average / maximum | 98.13 / 107 | 93.65 / 103 |
| Handles average / maximum | 987.45 / 1,055 | 929.58 / 996 |

The final hardened gate required every configured feed client to fetch at least one call whenever calls were observed.
All ten Airspy clients completed two WAV requests each, and all ten RTL clients completed eleven each. The RTL run
coalesced 24 intermediate display frames across 23,986 deliveries (0.10%) during brief approximately 101 ms delivery
gaps. This is the intended latest-frame behavior: it produced no stall, backpressure, failed send, tuner error, or
control-channel degradation.

## Problems caught before the passing artifact

Two implementations were rejected during the canary:

1. Comparing the tuner's monotonic capture timestamp to wall-clock scheduling cut a requested 20 fps stream to about
   10 fps.
2. Moving that pacing to sleeps restored approximately 19 fps in the signal clients but caused individual audio
   responses to stall for 6.9–12.2 seconds under the ten-plus-ten workload.

The passing implementation uses source-timestamp token/deadline pacing without sleeping. The producer never waits for
a browser; slow clients retain only their latest pending frame.

A final audit also caught and corrected three edge paths before sign-off: partial USB listener attachment now always
attempts removal after an attach failure, WebSocket upgrades use the same remote-address admission rule as HTTP, and
policy revocation uses the browser-recognized admin-required close state. These fixes are covered by focused tests and
must remain in the final packaged review artifact.

## Idle recovery and radio-path observations

After clients disconnected and the three-second grace expired, status reported zero sessions, zero subscribers, and a
stopped signal source. Failed signal sends and tuner publication errors remained zero. Jetty's observed queue remained
zero.

The final Airspy review run reported summary logging active, detailed history off, 30-day retention, and zero dropped
statistics records. A contemporaneous active control-channel snapshot reported 100% decode health with 1,209 valid,
zero invalid, and zero dropped frames. The current-run application log contained no error- or warning-level entry and
no match for USB/sample/buffer/audio overrun or underrun categories. Client disconnect probes produced expected
closed-channel/EOF traces below warning level.

No new table, index, view, trigger, database column, or history write was introduced. Signal counters and access-policy
state in this slice are fixed-cardinality in-memory values; FFT bins, waterfall history, viewer state, and listener
identity are not persisted.

## Historical browser verification

The packaged public page was opened from another machine against BOSGAME. It rendered the real Airspy FFT and
browser-local waterfall at approximately 20 fps with 4,096 bins and zero reported drops. Public-view labeling,
readouts, responsive layout, and browser-local pause/resume worked, and the browser console contained no warning or
error. Closing the page returned the server to signal idle after the grace period.

That foundation runtime deliberately resolved every request as anonymous before the real admin login/session service
was implemented. Therefore `PUBLIC` worked end to end, while `ADMIN_ONLY` failed closed for that canary. The current
candidate implements one-account authentication and refuses anonymous spectrum, but authenticated live FFT still
awaits local credential provisioning.

## Historical foundation review state

At the final audit, candidate PID 11496 was the only Java process from the install, owned port 8090, used the Airspy
selector and isolated data root, and served the public web interface at `http://192.168.64.84:8090/`. The scheduled
task's enabled setting was false to
prevent its repeating watchdog trigger from launching another instance; Task Scheduler still displayed the current
`cmd /k` instance as running. The exact tested candidate was left running for review.

Temporary archives, extraction stages, launch variants, deployment scripts, metrics helpers, and audit helpers were
removed from BOSGAME after the final audit. The active install contained no entry outside the allowlisted runtime,
canonical data directory, and active launcher; the isolated canary and protected rollback roots were intentionally
retained.

Windows lifecycle caveat: stopping the scheduled task did not terminate its child JVM during tuner-selector changes,
so the deployment helper force-stopped only the previously resolved candidate PID after a bounded wait. Embedded
Jetty/service close tests pass, but graceful full-process shutdown through the current Windows task layout has not yet
been proven.

Longer paired radio regression, deterministic replay, reconnect/slow-client repetition, 24-hour and multi-day BOSGAME
soaks, the N100/8 GB one-system/four-call/ten-listener gate, BOSGAME credential provisioning/authenticated browser
validation, the interactive adaptive Airspy/RTL gate, and all-six-target packaging remain required before the
corresponding later migration/release gates can close.
