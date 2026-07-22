# BOSGAME Swing wideband FFT on/off baseline — 2026-07-19

## Purpose

This directional A/B/A measurement records the cost of the existing Swing wideband FFT/waterfall before the web
signal implementation. It is a migration regression reference, not a statistically powered benchmark and not an
estimate of browser cost.

## Safety and test state

- Node: BOSGAME only. CUBI and the development Mac were not used as receiver runtimes.
- Approved application: `sdrtrunk-vce v0.6.2-alpha-5`, application JAR SHA-256
  `cd524254c3ca73ac29d68f0e8ac3ba6b7db7c93ee4756cbe4fe1d1d63aa076cf`.
- Exactly one Java process remained under the `SDRTrunk BOSGAME Launch` scheduled task.
- The normal Airspy trunked-radio workload continued. The existing Swing wideband display was already observing the
  enabled RTL-SDR at 2.4 MHz; the test did not change tuner selection, center frequency, gain, sample rate, channel
  configuration, decoder state, or streaming profiles.
- The only application mutation was clicking the existing `Spectrum` control off once and back on once. The original
  enabled state was visually confirmed after restoration.
- No database schema or test table was created. Normal application activity plus the two existing preference writes
  increased the main SQLite file by 16 KiB; the WAL size was unchanged at the final boundary.
- Temporary measurement files and the temporary interactive helper task were removed from BOSGAME after collection.

## Method

The same long-running Java process was observed in three consecutive windows:

| Window | State | UTC interval | Samples |
| --- | --- | --- | ---: |
| A1 | Swing FFT/waterfall enabled | 13:42:38–13:46:06 | 39 |
| B | Swing FFT/waterfall disabled | 13:46:46–13:50:15 | 40 |
| A2 | Swing FFT/waterfall enabled/restored | 13:51:22–13:54:51 | 40 |

Samples included cumulative process CPU time, working set, private committed bytes, threads, handles, process I/O,
host CPU, and available host memory. The first 30 seconds of each window were excluded. CPU is the ordinary least-
squares slope of cumulative process CPU seconds against elapsed wall time over the remaining approximately 176
seconds and is expressed as a percentage of one logical core.

## Results

| Metric | FFT on A1 | FFT off B | FFT on A2 | Enabled mean | On minus off |
| --- | ---: | ---: | ---: | ---: | ---: |
| Process CPU, % of one logical core | 144.869% | 112.890% | 146.615% | **145.742%** | **+32.852 points** |
| Process CPU, % of 16-thread host capacity | 9.054% | 7.056% | 9.163% | **9.109%** | **+2.053 points** |
| Average process threads | 92.94 | 90.53 | 93.18 | **93.06** | **+2.53** |
| Average process handles | 1403.35 | 1385.88 | 1403.59 | **1403.47** | **+17.59** |
| Average working set | 469.56 MiB | 550.60 MiB | 562.88 MiB | — | Inconclusive |
| Average private committed bytes | 745.73 MiB | 801.27 MiB | 808.08 MiB | — | Inconclusive |

The two enabled CPU windows bracket the disabled window closely, which makes the approximately 32.9%-of-one-core
increment a useful directional estimate of the current shared Swing FFT/waterfall work on this live workload. Relative
to the disabled state, enabling the existing display increased total process CPU by about 29.1% in this short run.

Memory cannot be attributed to the FFT toggle from this sequence: Windows working-set trimming and normal long-lived
process growth moved in the same direction across all three windows. The final enabled window's private committed
bytes changed by only 0.57 MiB during its analyzed portion, but this run is much too short to establish leak freedom.

The application log added three routine lines during the measured interval and contained zero matches for error or
exception, buffer/sample-loss, or USB/PLL-failure categories. No repeated USB, overflow, or decoder-stall symptom was
observed. Live RF variability means this is still a smoke baseline rather than deterministic decode-parity evidence.

## Migration gates derived from this baseline

- With zero web signal subscribers, synthetic and real spectrum services must be stopped after the short grace period;
  idle cost should track the FFT-off state rather than retaining visualization work.
- One authenticated spectrum owner uses one producer and selected target. A second browser is rejected without
  starting DSP. Exercise adaptive 4K-32K calculation/cropped-wire tiers while ten separate users listen to call audio.
- Candidate process CPU, allocation/GC behavior, threads, handles, sockets, queue depth, and dropped frames must be
  compared with this baseline while control/grant decoding, four-call recording/upload, activity tracking, and ten
  audio listeners remain healthy.
- A longer BOSGAME soak and deterministic replay are still required to establish memory, decoder, recording, and upload
  non-regression. This short result cannot replace those gates.

## State after baseline capture

The original FFT/waterfall enabled state was restored. Exactly one approved alpha-5 Java process remained running under
the unchanged `SDRTrunk BOSGAME Launch` scheduled task with both tuners enabled and the existing Stats API responding.
This records the state at the end of the baseline window, not the current node state. The later
[packaged web-first canary](bosgame-webfirst-wideband-canary-2026-07-19.md) replaced it in an isolated review run.
