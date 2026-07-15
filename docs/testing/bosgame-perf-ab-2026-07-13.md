# BOSGAME SDRTrunk performance A/B test — 2026-07-13

## Publishable conclusion

Under the tested live two-control-channel BOSGAME workload, `sdrtrunk-vce` used less CPU and created materially less garbage-collection pressure than the patched DSheirer mainline nightly.

| Metric (six independent runs/build) | VCE, mean ± SD | Mainline, mean ± SD | VCE reduction | Block-adjusted 95% CI for mainline − VCE | Exact block permutation p |
|---|---:|---:|---:|---:|---:|
| Process CPU, % of one logical core | **79.71 ± 1.82** | 87.11 ± 2.43 | **8.50%** | 4.45 to 10.36 points | **0.0093** |
| Process CPU, % of 16-thread host capacity | **4.98%** | 5.44% | **8.50%** | 0.28 to 0.65 points | **0.0093** |
| GC pauses per five minutes | **432.7 ± 54.8** | 579.7 ± 42.6 | **25.36%** | 74.1 to 219.9 pauses | **0.0093** |
| Total GC pause time per five minutes | **1,071.9 ± 126.6 ms** | 1,292.2 ± 128.0 ms | **17.05%** | 34.3 to 406.3 ms | **0.0370** |
| Maximum GC pause | 5.05 ± 0.76 ms | 5.01 ± 2.13 ms | No difference | −2.28 to 2.20 ms | 0.9722 |
| Private committed memory | **693.6 ± 49.7 MiB** | 763.5 ± 28.0 MiB | 9.15% | 10.1 to 129.6 MiB | 0.0648 |
| Working set | 541.6 ± 38.9 MiB | 514.8 ± 63.0 MiB | No reliable difference | −86.5 to 33.0 MiB | 0.3148 |
| 95th-percentile working set | 605.9 ± 18.5 MiB | 610.6 ± 70.4 MiB | No reliable difference | −59.4 to 68.9 MiB | 1.0000 |

CPU was lower for VCE in every replicate. VCE's highest run was 83.40% of one core; mainline's lowest was 84.27%. The CPU result is therefore both statistically strong and operationally consistent. The private-memory result favors VCE, but it does not cross 0.05 under the conservative exact block permutation test and should be described as suggestive. Windows working set was affected by OS trimming after startup and does not establish a memory advantage for either build.

## Important configuration caveat

The VCE and mainline runtime profiles were not perfectly feature-matched, so this is an as-configured comparison. It is not scientifically valid to assign a numerical cost to any one optional component or claim the same exact effect size for a feature-matched comparison without another test. This caveat should accompany any published result.

## Builds

- **A — VCE benchmark image:** `sdrtrunk-vce` 0.6.2-alpha-1, based on repository commit `021cd25e449f8f23d4be7bc7c26bd00094d5461a` plus the then-current signal/decode-health worktree. Benchmark archive SHA-256: `c231df433fc146e63206e668c9eae8ec0c5bded02cebb720048f28deb0eb4b2d`.
- **B — mainline benchmark image:** DSheirer commit `d60720aab77ba8924f4317128ee292728df2d8f4`, patched only for the Java signal/decode columns, required P25 power tap, portable test preferences, and benchmark read-only mode. The prior once-per-second `AB_QUALITY` test logger was removed before this test. Benchmark archive SHA-256: `ee83d7d4f99e623604491e225338d2210c96aed9172967702d87dd9bce4c6129`.
- Both used Java 25.0.1, JMBE 1.0.9, `-Xmx2g`, compact object headers, Java2D D3D disabled, and the same native/vector JVM flags.
- A benchmark-only `-Dsdrtrunk.benchmark.readOnly=true` switch made preferences and tuner-configuration persistence no-ops in both builds. Reads and DSP paths were unchanged. The switch was removed from the VCE worktree after the benchmark and was never deployed in production.

## Host and workload

- BOSGAME: AMD Ryzen 7 5825U, 8 physical cores/16 logical processors, 31,034,081,280 bytes of RAM.
- Windows 11 Pro 10.0.26200.
- One Airspy at 10 MHz, producing the same 400-channel/25 kHz polyphase channelizer path in every run.
- Exactly two configured channels auto-started in every run.
- The same tuner gain, sample rate, zero frequency correction, disabled auto-PPM, muted playback, disabled spectrum, channel configuration, antenna, and JMBE jar were used.
- The embedded web server was disabled. Every process had zero listening sockets at both measurement boundaries.
- VCE's SQLite files were SHA-256 fingerprinted at both boundaries of every measured window and were byte-for-byte unchanged. Mainline created no SQLite database.
- Normal application/GC logs remained enabled equally; the results therefore include ordinary logging overhead but no stats/history database or web-server overhead.

## Experimental design

The accepted sequence was `A B B A / B A A B / A B B A`. Each of the three four-run blocks contained two runs of each build, balancing build order against time-of-day and live-radio traffic changes.

Every run used:

1. A fresh Java process and fresh copy of the immutable configuration profile.
2. A two-minute warm-up excluded from analysis.
3. A five-minute measured window.
4. Five-second samples of cumulative process CPU time, working set, private bytes, threads, handles, process I/O, host CPU, and available host memory.
5. JVM unified GC and safepoint logging.

There were 60 samples per run, 12 accepted runs, and 720 raw samples. The run—not the five-second observation—was the independent statistical unit.

For each run, CPU consumption was the ordinary least-squares slope of cumulative process CPU seconds against elapsed wall time. The primary model was:

`metric = intercept + build + crossover_block`

It had 12 run-level observations and 8 residual degrees of freedom. Confidence intervals are two-sided 95% t intervals for the build coefficient. Exact p-values enumerate all 216 assignments that preserve two A and two B labels inside each four-run block. This prevents the 720 autocorrelated samples from falsely inflating statistical significance.

## Code-path interpretation

The result establishes lower end-to-end CPU cost for VCE under this workload; it does not isolate a single optimized method. Two observations help narrow the interpretation:

- VCE generated about 25% fewer GC pauses with the same heap/JVM settings. That is direct evidence of lower allocation pressure or longer object lifetimes in its active paths, not merely different Windows CPU accounting.
- The calibrated P25 Phase 1 soft-sync path differed: VCE selected `VECTOR_SIMD_64`, while the mainline profile selected `SCALAR`. This is a plausible contributor because soft synchronization is continuously exercised, but the test did not include method-level profiling and cannot assign a percentage of the CPU difference to it.

The P25 Phase 1 CQPSK/LSM demodulation loop itself is effectively the same in these sources. The observed difference therefore comes from the complete active application path—calibrated vector choices, processing-chain/event handling, audio/traffic activity, allocation behavior, and UI quality updates—rather than a demonstrated rewrite of the core LSM loop.

## Limitations

- This was a live-RF crossover, not deterministic IQ replay. The balanced block design controls gradual drift, but call mix and traffic-channel activity were not identical second-for-second.
- The VCE and mainline runtime profiles were not feature-matched, as described above.
- Six independent runs per build are enough for the large CPU/GC effects seen here, but are modest for memory effects.
- Working set is controlled by Windows memory trimming and should not be presented as heap usage. Private committed bytes are more stable, while GC behavior is the strongest memory-allocation evidence.
- No JFR or native sampling profiler was enabled, so the study supports end-to-end efficiency claims, not per-method attribution.

## Reproducibility artifacts

- [Run-level summary](bosgame-perf-ab-2026-07-13/run-summary.csv)
- [Block-adjusted statistics](bosgame-perf-ab-2026-07-13/statistics.csv)
- [All 720 raw samples](bosgame-perf-ab-2026-07-13/samples.csv)
- [Per-run validation and SHA-256 manifest](bosgame-perf-ab-2026-07-13/validation.csv)
- [Raw samples, GC logs, run metadata, and SQLite fingerprints](bosgame-perf-ab-2026-07-13/raw-evidence.tar.gz)
- [Analysis program](bosgame-perf-ab-2026-07-13/analyze.py)
- [Windows collection program](bosgame-perf-ab-2026-07-13/collect.ps1)

## Final BOSGAME state

After collection, the temporary benchmark task and comparison runtimes were removed. Production `sdrtrunk-vce` was restored through `SDRTrunk BOSGAME Launch`; verification showed one production Java process and HTTP 200 from `/api/status`.
