# BOSGAME SDRTrunk Mainline A/B Test — 2026-07-13

## Builds

- **A:** `sdrtrunk-vce` 0.6.2-alpha-1, archive SHA-256 `0239748fa402dbb8090d3c721842b7a736c155fd84c30e4a2fd07e8a6ebbd3f0`
- **B:** DSheirer nightly commit `d60720aab77ba8924f4317128ee292728df2d8f4`, with only the Java signal/decode columns, a portable preference backend, the P25 power tap required by the Signal column, and structured test sampling
- Java 25.0.1 and JMBE 1.0.9 were used by both builds.
- Upstream calibration reported zero remaining uncalibrated DSP components before testing.

## Method

The test used a sequential B-A-B crossover on the same BOSGAME host, Airspy tuner, antenna, channel configuration, and JMBE library. Each window was 10 minutes.

| Window | Epoch range (ms) |
|---|---:|
| B1 | 1783960382000–1783960982000 |
| A | 1783961110435–1783961711000 |
| B2 | 1783961791000–1783962391000 |

Decode health used the same 30-second formula in both builds:

`valid / (valid + invalid + (sync-loss bits + dropped bits) / 196) * 100`

The primary comparison uses the average rolling decode-health value. Frame-weighted health is included as a cross-check. Signal differences under 0.3 dB are too small to explain the observed decode differences by themselves.

## Results

### Individual runs

| Control | Run | Avg signal (dBFS) | Avg rolling health | Frame-weighted health |
|---|---|---:|---:|---:|
| MARCS-CuyCoSimul, 773.83125 MHz | B1 | -52.101 | 97.821% | 97.818% |
| MARCS-CuyCoSimul, 773.83125 MHz | A | -51.746 | **98.897%** | **98.891%** |
| MARCS-CuyCoSimul, 773.83125 MHz | B2 | -51.701 | 96.018% | 95.974% |
| Medina, 771.50625 MHz | B1 | -63.628 | 8.016% | 8.585% |
| Medina, 771.50625 MHz | A | -63.307 | **11.310%** | **12.216%** |
| Medina, 771.50625 MHz | B2 | -63.507 | 7.179% | 7.450% |

### A versus combined B windows

| Control | A rolling health | Combined B rolling health | A advantage | A/B signal delta |
|---|---:|---:|---:|---:|
| MARCS-CuyCoSimul | **98.897%** | 96.919% | +1.978 points | +0.155 dB |
| Medina | **11.310%** | 7.586% | +3.724 points | +0.259 dB |

One-minute block ranges were 94.192–100.000% for A versus 84.662–99.910% for B on MARCS, and 6.329–17.032% for A versus 2.713–11.158% for B on Medina.

## Earlier degraded A interval

The earlier A history was not representative of the matched steady-state A run. MARCS averaged only 55.75% rolling health from approximately 09:04–09:32 despite a similar -52.31 dBFS signal.

The detailed sequence identifies a likely reacquisition/state problem:

- From 09:04 through 09:20, 773.83125 MHz generally remained around 27–49% health with high sync-loss counts.
- At 09:21:10 the channel rotated through 774.28125 MHz and acquired 774.53125 MHz at roughly 89–97% health.
- It returned to 773.83125 MHz at 09:22:10 and recovered from 72.1% to 89.4%, 93.5%, and then 100% by 09:22:40.
- The same A binary later produced 98.897% in the controlled window without a code change.

This pattern is more consistent with an intermittent decoder/tuner synchronization state that is cleared by frequency rotation or channel restart than with generally inferior decoding in the VCE binary.

## Conclusion

The matched crossover does **not** show mainline decoding better than the current VCE build. A was modestly better on the strong MARCS control and materially better on the marginal Medina control in this test.

The user reports are still credible because A exhibited a long degraded-lock interval at essentially unchanged signal strength. The next targeted fix should be automatic control-channel reacquisition: when a strong control has persistently poor decode health, rotate/restart that control chain instead of allowing the degraded state to continue indefinitely. A conservative starting rule would be health below 60% for 30–60 seconds while signal is stronger than -60 dBFS, with cooldown and hysteresis to prevent rotation loops.

## Final BOSGAME state

A was restored after testing. The scheduled task is running with one Java process, the stats API is healthy, and the B profile/log remains under `data/mainline-ab` for reference. Temporary archives, tools, deployment scripts, and backup runtimes were removed from BOSGAME.
