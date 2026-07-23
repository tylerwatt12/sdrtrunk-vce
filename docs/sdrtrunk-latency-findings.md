# SDRTrunk control-to-voice latency findings

Date: 2026-07-17

## Current conclusion

The measured development receiver and low-spec reference receiver do not show a present problem with SDRTrunk
starting traffic decoders too late to recover the beginning of normal P25 voice calls.

There are measurable places where the software can be made faster, but the tested improvements save tens of
milliseconds inside a normal setup margin of several hundred milliseconds. They are not currently expected to change
whether normal conversations are clipped.

No latency experiment code was merged into `main` when this document was created. The experiment branch was closed
after its results were condensed here. The production installations were restored after every test.

## Signal path being discussed

For the monitored trunked systems, the relevant sequence is:

1. A receiver supplies a continuous wideband stream of USB IQ samples.
2. The channelizer extracts the configured control channel from that stream.
3. The control decoder receives a channel grant identifying a traffic frequency.
4. SDRTrunk selects a receiver that can supply that frequency.
5. If the frequency already falls inside that receiver's sampled bandwidth, no hardware frequency change occurs.
6. If it does not fit, SDRTrunk changes the physical receiver's center frequency and waits for new sample blocks.
7. SDRTrunk creates the traffic decoder and supplies channelized IQ to it.
8. The traffic decoder synchronizes to P25, processes the header and logical data units, and then produces audio.

A traffic-frequency carrier can be active before the control grant finishes. Carrier onset is not the same as speech
onset. It can contain synchronization, terminators, headers, idle data, or other signaling before voice begins.

## Normal control-path measurements

Normal operation on the development receiver was measured across 134 grants with a 10 MHz Airspy covering the
configured control and traffic channels. No receiver retune was needed.

| Measurement | Median | 95th percentile | Maximum |
|---|---:|---:|---:|
| Control grant age when handled | approximately 0 ms | 8 ms | 11 ms |
| Synchronous traffic-start request | 1.50 ms | 2.05 ms | 8.64 ms |
| Complete traffic manager construction | 1.48 ms | 2.02 ms | 8.40 ms |
| First traffic-decoder IQ, processing time | 20.84 ms | 26.43 ms | 27.14 ms |
| First traffic IQ relative to the grant's RF timestamp | 11 ms | 20 ms | 22 ms |

Small negative grant-age values occurred because receiver timestamps and millisecond rounding are estimates. They do
not mean SDRTrunk handled a message before it arrived. The narrow age range is the useful result: the control decoder
was not developing an accumulating backlog.

Traffic-channel construction is synchronous with control-message handling. The control decoder cannot process its next
message until construction returns, even when no physical retune is needed. That statement is technically important,
but its normal measured cost was about 1.5–2 ms, not tens or hundreds of milliseconds.

One Airspy buffer was discarded during initial application and channelizer startup when the queue briefly exceeded
100 ms. No additional discard occurred in the following steady-state measurement window, and grant age remained at or
below 11 ms. This was a startup event, not evidence of continuing control-channel delay.

## RF timeline and beginning-of-call margin

Wideband IQ recordings were used to compare the grant, traffic-signal onset, first IQ delivered to the traffic
decoder, and subsequent P25 output on a shared RF timeline.

On the low-spec reference receiver, 17 calls had a clear traffic-frequency power transition:

- traffic carrier onset occurred about 86 ms before the grant at the median;
- first traffic IQ reached the new decoder about 10.5 ms after the grant on the RF timestamp scale;
- the apparent missing interval was therefore about 98 ms of traffic carrier;
- the first real P25 frame was normally a terminator/link-control unit, not voice;
- audio-call creation occurred 291–466 ms after the grant;
- the first decoded audio followed approximately 165–190 ms later.

The smallest measured interval between first decoder IQ and audio-call creation was approximately 270 ms. The median
interval was approximately 386 ms. This is much larger than the measured software startup time.

A separate forced-retune comparison on the development receiver reached the same conclusion. The first RTL traffic IQ
arrived about 42.5 ms after the grant. The first normally observed 180 ms voice-bearing LDU completed around 569 ms
after the grant, placing the beginning of that LDU around 389 ms after the grant. The resulting estimated setup margin
was about 334 ms. The forced Airspy path had approximately 379 ms of margin.

These results apply to the measured P25 systems and call sequences. Late entry into an existing call, unusual system
signaling, severe RF fading, or sustained computer overload can produce a different result.

## Channelizer scheduling and output-buffer experiment

The unchanged experimental baseline used 25 ms IFFT scheduling, 20 ms channel-output scheduling, and a 2,048-complex-
sample traffic output buffer. At a 50 kHz channel sample rate, that output buffer contains 40.96 ms of RF.

An experimental path used 5 ms scheduling intervals and a 1,024-sample output buffer containing 20.48 ms of RF.

| Traffic startup measurement | Original path | Faster experimental path |
|---|---:|---:|
| First decoder IQ, median | 41.11 ms | 16.95 ms |
| First decoder IQ, p95 | 61.66 ms | 30.65 ms |
| First valid traffic message, median | 60.85 ms | 50.53 ms |
| First decoded audio, median | 580.80 ms | 571.44 ms |

The smaller output buffer substantially improved delivery of the first IQ samples, but median decoded audio improved
by only about 9 ms. All measured grants started successfully under both settings. No large CPU penalty was visible on
the development receiver, although the smaller buffer creates twice as many downstream buffer deliveries.

This is a valid optimization candidate if a future system shows insufficient setup margin. The measurements do not
show that it is currently required to prevent clipped speech.

## Multiple receivers and hardware retuning

A receiver does not need to retune for each traffic channel when its existing wideband sample stream already covers
that channel. The channelizer can create multiple control and traffic channels from the same wideband stream.

During normal operation on the development receiver, the 10 MHz Airspy covered all 134 observed P25 traffic grants
and the configured control channel. The Airspy center frequency did not move.

The forced-retune tests deliberately narrowed or restricted receiver selection to measure the hardware path:

| Forced-retune stage | RTL-SDR median | Airspy median |
|---|---:|---:|
| Hardware tuning command | 32.79 ms | 0.54 ms |
| First channelized IQ at traffic decoder | 55.35 ms | 17.09 ms |
| First valid P25 frame | 114.57 ms | 96.47 ms |

The Airspy hardware command was much faster, but any physical retune changes the complete receiver stream. Every
active decoder using that receiver sees an RF discontinuity, regardless of how quickly the command returns.

In the forced narrow-band Airspy test, three retunes aligned with a missing 180 ms P25 LDU on an existing call. This
did not occur during normal 10 MHz operation on the development receiver because no retunes occurred. The important
optimization is therefore avoiding a physical retune, especially on a receiver carrying active calls, rather than
removing a few milliseconds from the hardware command.

A future receiver-selection policy should prefer, in order:

1. a receiver already covering the requested channel without retuning;
2. an idle receiver that can be positioned without interrupting another decoder;
3. a receiver carrying active traffic only when no non-disruptive choice exists;
4. the configured preferred receiver as a tie-breaker between equally safe choices.

This policy should use reported tuner bandwidth and current channel occupancy. It should not hard-code an Airspy or
RTL-SDR exception.

## RTL-SDR optimization experiments

Forced tests on the development receiver used an RTL-2832/R828D Blog V4 at a 2.4 MHz sample rate while the Airspy
remained on the control channel.

The following experimental changes were successful:

- caching the RTL2832 I2C-repeater state;
- writing the seven consecutive R8x PLL registers in one I2C message;
- skipping unchanged R828D multiplexer programming for repeated tunes inside the same hardware frequency range;
- reducing the RTL USB transfer block from 65,536 bytes to 32,768 bytes.

The command changes reduced the R828D median tuning command from approximately 32.8 ms to approximately 22.5 ms.
The smaller USB block reduced median first decoder IQ in the final comparison from 47.9 ms to 35.1 ms. The 32,768-byte
setting showed almost no change in total development-receiver CPU consumption.

These improvements are real but save only about 10–13 ms at the decoder-input stage in the final comparison. That is
small relative to the measured 300-plus-millisecond setup margin. They were documented as future candidates rather
than merged into `main`.

Two variations should not be repeated without a new reason and careful isolation:

- Leaving the Blog V4 I2C repeater enabled produced USB pipe errors and failed source allocations. The repeater should
  continue to be disabled after tuner access.
- A 16,384-byte RTL USB block reached the USB layer sooner but did not deliver first decoder IQ sooner than the
  32,768-byte setting. It also quadrupled callback frequency relative to 65,536 bytes.

The final RTL tests produced 18,572 valid P25 frame events with no runtime USB errors, channelizer overflow warnings,
PLL-lock failures, crash, or stalled decoder. They validated the development receiver's R828D in the tested
public-safety band. No R820T/R820T2 receiver was available, and the low-spec reference receiver had no RTL-SDR, so
broader hardware coverage was not established.

USB sample transfers continue while an RTL tuner command is running. A completed block can contain samples from the
old center frequency, the tuning transition, the new center frequency, or a combination. A future diagnostic can tag
each block with a retune generation. Automatically discarding transition blocks is not recommended without RF evidence
because a transition block may also contain usable beginning-of-call samples.

## Decoder-thread and web-work rule

Network requests, filesystem writes, database maintenance, and slow web-client work must not execute on a control or
voice decoder thread. Decoder-thread work should publish a small event or immutable snapshot and return immediately.
The consumer should run on a separate executor with a bounded queue and visible dropped-event counter.

The latency instrumentation followed this pattern: decoder threads placed small metric events into a bounded,
non-blocking queue, and a separate writer thread created the CSV files. If the reporting queue filled, diagnostic rows
rather than radio samples would be omitted.

This avoids the earlier failure mode where downstream web work could delay decoder progress and allow radio buffers to
accumulate. Future web features should preserve this separation and should not introduce an unbounded event queue.

## What would justify reopening latency work

Reopen this investigation when production evidence shows one or more of the following:

- control-grant age grows continuously instead of remaining near the measured 3–13 ms range;
- traffic first-IQ delay develops a long tail during sustained load;
- native receiver or channelizer buffers are discarded after startup and continue being discarded;
- a valid P25 header or voice LDU begins before the traffic decoder receives IQ;
- physical retunes occur during normal operation and correlate with missing LDUs or audio gaps;
- a newly monitored system has much less grant-to-voice margin than the systems measured here;
- web/database/reporting queues grow without returning to normal;
- CPU saturation, long garbage-collection pauses, or memory pressure coincide with increasing grant age.

When this happens, collect the control grant and voice frequency from one wideband IQ recording whenever possible.
That places control and traffic events on the same RF clock and distinguishes software delay from transmitter timing.

## Recommended future research order

1. Add long-running production counters for grant age, steady-state buffer discards, physical retunes, active calls
   affected by a retune, and time to first traffic IQ.
2. Improve receiver selection to avoid retuning a receiver that already carries active channels.
3. Capture a short, zero-drop wideband IQ recording during an observed clipped call and identify carrier, HDU, LDU,
   and decoder-IQ timing from the same sample timeline.
4. Tag native RTL blocks with a retune generation to identify old-frequency and transition samples.
5. Reconsider 5 ms channelizer scheduling and a 1,024-sample traffic output only if first-IQ margin is insufficient.
6. Reconsider the measured RTL command and 32,768-byte USB-block changes only for systems that actually require RTL
   hardware retunes.
7. Validate R820T/R820T2 hardware, Blog V4 VHF/HF ranges, range boundaries, and a resource-constrained RTL computer
   before publishing RTL changes as general defaults.

Do not begin by reducing or dropping the control-channel backlog. A forced resynchronization can be worse than a short
delay, and the measurements did not show a continuing control backlog. Do not move traffic construction to another
thread solely because it is synchronous; first demonstrate construction outliers large enough to justify the added
ordering, cancellation, and lifecycle complexity.

## Final decision

Latency is not considered an active problem for the measured deployments. The highest-value preventative rule is to
keep web and other slow work off decoder threads. If a future symptom appears, measure grant age, physical retuning,
first decoder IQ, and actual voice onset before changing buffer policies.
