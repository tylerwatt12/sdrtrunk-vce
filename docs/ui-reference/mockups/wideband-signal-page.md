# Wideband signal page — approved low-fidelity mockup

- Status: **implementation-approved** on 2026-07-19
- Approval scope: wideband tuner FFT/waterfall page only
- Visual maturity: low fidelity; spacing, typography, color, and final component styling remain iterative

This artifact records the approved information architecture, access states, responsive behavior, and runtime-safety
contract for the first browser signal page. It is not runtime HTML and is not a pixel-perfect acceptance image. Product
authorization to proceed with this slice was given in the design conversation; the remaining mockups in the
[reference catalog](../README.md#mockup-gate) are still required before the plan's complete design gate is closed.

## Scope and evidence

The page observes an already-running tuner target. It does not retune hardware, allocate an inactive-frequency probe,
or edit tuner configuration.

Legacy evidence:

- [MAIN-08 — tuner inventory](../images/bosgame-2026-07-18/main/tuners-inventory.png)
- [MAIN-09 — Airspy controls and information](../images/bosgame-2026-07-18/main/tuner-airspy.png)
- [MAIN-10 — RTL-SDR controls and information](../images/bosgame-2026-07-18/main/tuner-rtl-sdr.png)
- [MAIN-12 — Airspy and RTL-SDR spectrum/waterfall](../images/bosgame-2026-07-18/main/spectrum-waterfall-airspy.png)

The RTL-SDR MAIN-12 companion is [here](../images/bosgame-2026-07-18/main/spectrum-waterfall-rtl-sdr.png).

## Foundation snapshot and approved contract revision — 2026-07-19

The first packaged foundation implemented the SFFT binary protocol, a browser worker/canvas FFT and waterfall,
live readouts, browser-local history, bounded delivery, reconnect cleanup, and source shutdown. Its short
[BOSGAME Airspy/RTL canary](../../testing/bosgame-webfirst-wideband-canary-2026-07-19.md) is historical evidence for
that foundation's 4,096-bin transport and radio-path isolation. It predates the final exclusive-access contract and is
not evidence that the interactive release described below has passed its BOSGAME gate.

The packaged BOSGAME review candidate narrows the feature to one permanently admin-only workspace, one selected tuner,
and one active browser connection. It adds pointer-anchored wheel zoom, click-drag panning, a shared adjustable dB
floor, labeled dB grid lines, synchronized hover readouts, 4,096/8,192/16,384/32,768-point adaptive FFT tiers, and a
contiguous server-side crop of at most 4,096 visible bins. Zoom/pan first stretches the existing browser image and marks
it `Refining`; only newly acknowledged rows are sharp. Authentication, exclusivity and locked-state rendering are in
the package, but the isolated BOSGAME root has no admin credential. Real authenticated Airspy/RTL FFT is therefore
**NOT RUN** until that credential is provisioned through the local JavaFX path.

Still pending for full mockup parity are channel overlays, the remaining display controls, comprehensive
keyboard/touch/numeric fallback and accessibility acceptance, Vite live-proxy mode, and the planned
TypeScript/Vite/React frontend. The earlier public-access and concurrent-spectrum-viewer behavior is intentionally not
part of the release contract. The ten-listener acceptance target applies to call audio while the single spectrum owner
is active.

## Approved access and concurrency contract

- Wideband spectrum is permanently `ADMIN_ONLY` and is not included in the public-feature switches.
- The product persists exactly one admin account. That account may have multiple bounded in-memory web sessions, but
  only one browser connection can own the node-wide spectrum workspace at a time.
- A second authenticated browser receives a non-identifying “Spectrum is currently in use” state. An unclean disconnect
  retains a short in-memory reconnect grace, then releases the slot automatically. No owner, session or usage history is
  written to SQLite.
- The owning browser connection selects exactly one approved tuner and owns its viewport and adaptive FFT resolution.
  Switching targets detaches the web FFT from the old target before attaching to the new target; the web feature never
  runs two tuner FFT producers simultaneously.
- Selecting an already-running target does not retune or mutate it. Activating an otherwise-idle USB target is an
  explicit admin resource action and is never caused by an anonymous request.
- Palette, dB floor, hover, panel sizing and the immediate visual zoom/pan transform remain browser-local. Once wheel or
  drag interaction settles, the owner may request a bounded higher-resolution server viewport.
- The server computes one of 4,096/8,192/16,384/32,768 FFT points, crops to at most 4,096 contiguous visible bins before
  transmission, and may lower frame rate if required to protect core radio work. Requests are coalesced latest-only.
- Retune, gain, sample-rate, enable/disable and other tuner mutations remain outside the signal WebSocket.

## Wide desktop layout

```text
┌──────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ SDRTrunk   Live   Spectrum   Events / Messages   Audio   Tuners                     ADMIN   [Account] │
├──────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ Wideband spectrum                                                                                       │
│ Tuner [ Airspy 1 — Running ▾ ]   851.000000 MHz center   10.000 MHz span   10 channels                   │
│ Exclusive spectrum workspace   Live • 20 fps / 4096 sent • 16384 FFT          [Display controls ▾]     │
├───────────────────────────────────────────────────────────────────────────────┬──────────────────────────┤
│  -20 dB ───────────────────────── FFT ──────────────────────────────────────  │ Readouts                 │
│          ╭──╮         ╭╮                           ╭────╮                     │ Cursor  853.112500 MHz   │
│  -70 dB ─╯  ╰─────────╯╰───────────────────────────╯    ╰───────────────────  │ Power   -58.2 dB         │
│          846        848        850        852        854        856 MHz       │ Center  851.000000 MHz   │
│          ░ enabled channel    ▒ processing channel    │ selected marker       │ Span    10.000 MHz       │
├───────────────────────────────────────────────────────────────────────────────┤ Rate    20 fps           │
│                               WATERFALL                                      │ Bins    4096             │
│ newest  ───────────────────────────────────────────────────────────────────  │ Drops   0                │
│         ·······························································  │                          │
│         ·······························································  │ Channels                 │
│ oldest  ───────────────────────────────────────────────────────────────────  │ [All] Enabled  None      │
│                                                                               │                          │
│                                                                               │ dB floor [ -90 dB ─●─]   │
│                                                                               │ Palette  [Viridis ▾]     │
│                                                                               │ [Pause] [Reset zoom]     │
├───────────────────────────────────────────────────────────────────────────────┴──────────────────────────┤
│ LIVE   Last frame <1 s   Target generation 42   Browser history 90 s   [Keyboard help]                  │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Approved behavior:

- Tuner selection lists only admin-authorized targets using redacted stable labels and distinguishes running from idle.
- The FFT and waterfall share one frequency axis and channel-overlay model.
- The waterfall grows by one decoded row in a browser ring buffer; the server never retransmits waterfall history.
- Readouts remain visible beside the graphics so exact values do not depend on color or pointer use.
- The mouse wheel zooms around the pointer. Click-drag pans both plots while zoomed. Reset Zoom restores the full span.
- Zoom/pan immediately stretches the old raster waterfall with smoothing so it is visibly blurry. After a short debounce,
  the server refines the viewport; sharp new rows enter at the top while old blurred rows scroll away.
- The FFT and waterfall share one adjustable lower dB limit. Actual labeled horizontal dB lines replace unlabeled pixel
  divisions. Hovering either plot shows a synchronized vertical guide, the selected bin-center frequency and its latest
  relative FFT dB value; it must not claim calibrated dBm.

## Authenticated administrator state

The authenticated administrator is the only spectrum user. Acquiring the workspace creates at most one bounded FFT
producer and permits viewport refinement; it does not grant tuner mutation through the signal socket.

```text
┌──────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ Wideband spectrum                          ADMIN • Exclusive workspace                 [Tuner details]│
│ Tuner [ RTL-SDR 1 — Running ▾ ]   Refining • 4× zoom • 16384 FFT / 4096 sent                         │
│                                                                                                          │
│                         same read-only FFT / waterfall workspace                                         │
└──────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- `Tuner details` is an authenticated deep link. Any retune, gain, sample-rate, enable/disable, restart, recording, or
  other hardware command occurs through its validated command service, not through this signal socket.
- The feature has no public-access switch.

## Anonymous request while admin-only

The navigation may show a lock or omit the entry. A direct link has a stable, non-secret landing state and opens no
signal WebSocket.

```text
┌──────────────────────────────────────────────────────────────┐
│ Spectrum                                                     │
│                                                              │
│  This monitoring feature is available to administrators.     │
│                                                              │
│  [Sign in to view]                         [Back to Live]     │
└──────────────────────────────────────────────────────────────┘
```

After successful login, return to the requested signal route. Do not reveal target inventory, frequencies, hardware
identifiers, cached bins, or prior waterfall rows in the locked state.

## Authenticated request while the workspace is occupied

```text
┌──────────────────────────────────────────────────────────────┐
│ Spectrum                                                     │
│                                                              │
│  Spectrum is currently in use in another browser.            │
│  It will become available when that browser disconnects or   │
│  its short reconnect period expires.                         │
│                                                              │
│  [Try again]                               [Back to Live]     │
└──────────────────────────────────────────────────────────────┘
```

Do not expose the owning browser, address, timestamps or an accumulating access record. Initial release has no silent
preemption; a stale slot expires automatically.

## Connecting, degraded, reconnecting, and disconnected states

The status is persistent text, not color alone:

| State | Approved presentation and behavior |
| --- | --- |
| Connecting | Keep the page structure stable, show “Connecting to shared signal stream,” and do not draw fixture/old bins as live data. |
| Live | Show last-frame age, effective frame rate/bin count, and current target generation. |
| Refining | Keep the immediate browser-stretched FFT/waterfall visible and explicitly blurry/stale, show the requested zoom, and accept only the newest acknowledged viewport revision. |
| Panning | Click-drag moves the common frequency window without retuning. Wheel/drag requests are debounced and coalesced before server refinement. |
| Busy | An authenticated second browser receives a non-identifying occupied-workspace state and starts no FFT work. |
| Degraded | Show an amber text banner such as “Reduced to 10 fps due to server pressure,” effective versus requested quality, and dropped/coalesced frame count. Decoding continues unaffected. |
| Reconnecting | Fade or clear the plot, mark all numeric values stale, show last successful frame time and bounded retry progress, and allow manual retry. |
| Disconnected | Retain route/help context but clear live claims; explain whether the target disappeared, access changed, or the connection failed without leaking internals. |
| Target changed | Increment generation, clear the prior target's FFT/waterfall history, and wait for acknowledged metadata before drawing new bins. |
| Authentication lost | Close the socket, clear live buffers, release the workspace after the reconnect grace, and present the sign-in state. |

The sole viewer and its viewport control each keep only the latest pending value. The UI reports drops but never asks
the producer or radio path to wait.

## Narrow/mobile layout and numeric fallback

```text
┌──────────────────────────────────┐
│ SDRTrunk   Spectrum      [Menu]  │
│ ADMIN                  [Account]│
├──────────────────────────────────┤
│ Airspy 1 — Running          [▾]  │
│ 851.000000 MHz • 10.000 MHz      │
│ LIVE • 10 fps • Exclusive        │
├──────────────────────────────────┤
│             FFT                  │
│ wheel/pinch/key zoom • drag pan  │
├──────────────────────────────────┤
│          WATERFALL               │
│   reduced rows/rate as needed    │
├──────────────────────────────────┤
│ Exact signal readouts            │
│ Cursor frequency  853.112500 MHz │
│ Power             -58.2 dB       │
│ Center             851.000000 MHz│
│ Span               10.000 MHz    │
│ Last frame         <1 s          │
│ Dropped frames     0              │
├──────────────────────────────────┤
│ [Display] [Channels] [Pause]     │
└──────────────────────────────────┘
```

- Stack the plots and readouts; move secondary controls into accessible drawers rather than horizontally scrolling the
  whole page.
- Adapt resolution, frame rate, and waterfall depth to the viewport and device budget. Only the owning connection can
  request server refinement.
- Provide keyboard zoom/pan, touch gestures, visible focus, textual status, and an accessible numeric readout region.
- Respect reduced-motion and page visibility. A hidden tab pauses or lowers its subscription and resumes with explicit
  stale/reconnect state.
- When graphics acceleration or canvas rendering is unavailable, preserve target/status and continuously updated
  numeric readouts at a bounded rate; explain that the visual plot is unavailable.

## Permission and resource matrix

| Action | Anonymous | Authenticated admin | Spectrum slot/resource behavior |
| --- | --- | --- | --- |
| View spectrum metadata or FFT/waterfall | No | Yes | One node-wide in-memory slot |
| Choose another already-running tuner | No | Yes | Atomically replaces the slot target; never two FFTs |
| Activate an idle tuner for spectrum | No | Explicit admin action only | Subject to node resource admission and cleanup |
| Palette, dB floor, pause and local history | No | Yes | Browser-local only |
| Wheel zoom or click-drag pan | No | Yes | Immediate local transform; latest-only bounded refinement request |
| Open tuner configuration | No | Yes | Navigation does not use the spectrum socket |
| Retune/change sample rate or allocate another processing chain | No | Through an authenticated command service | Separate command/resource lease or hardware lock |

## Preserve/adapt/combine/retire annotations

| Decision | Evidence | Approved interpretation |
| --- | --- | --- |
| Preserve | MAIN-12 | Frequency scale, FFT, waterfall, channel overlays, center frequency, sample rate/span meaning, tuner context, and recognizable live/stale state. |
| Preserve | MAIN-08 | Running tuner identity/status, current center frequency, and active-channel relationship needed to choose an already-running target. |
| Preserve | MAIN-09, MAIN-10 | Device capability differences, units, lock/active state, and immutable information needed for truthful Airspy and RTL-SDR context. |
| Adapt | MAIN-12 | Desktop mouse/menu presentation becomes responsive browser-local display controls, keyboard/touch interaction, exact numeric readouts, and visible degraded/reconnect state. |
| Combine | MAIN-08, MAIN-09, MAIN-10, MAIN-12 | Separate inventory selection and device-specific spectrum windows become one target-selectable signal route with a separate authenticated tuner-details deep link. |
| Retire | MAIN-09, MAIN-10, MAIN-12 | Independent desktop spectrum windows, Swing split-pane geometry, AWT palette/menu implementation, process-wide selection, and hardware mutation from the spectrum display. Hardware capabilities remain supported through the later tuner-admin route. |

## Implementation acceptance attached to this mockup

- Exactly one authenticated admin browser can own the spectrum workspace, one target and one FFT producer. A second
  browser receives a busy state and cannot start DSP work.
- Authentication enforcement covers page metadata, target inventory, WebSocket handshake and every control update.
- The 4,096/8,192/16,384/32,768 adaptive tiers crop to at most 4,096 transmitted bins, carry explicit
  viewport revision/frequency metadata, and discard stale refinement results.
- Wheel zoom and click-drag panning preserve pointer/frequency anchoring. Old waterfall pixels stretch blurry
  immediately; matching higher-resolution rows arrive sharp without retaining raw tuner history.
- Latest-frame and latest-control queues are bounded, the browser never delays the producer, and zero owners stop
  visualization work after the reconnect/grace period.
- Rapid resize/zoom/pan and receiver switching cannot execute FFT, crop, JSON or network work on USB/sample, decoder,
  recording or audio callbacks.
- Desktop and narrow layouts expose exact numeric values, keyboard/touch operation, connection quality, stale state,
  dropped frames, and target generation without depending on color alone.
- This feature adds no database schema or append-only history. Browser-local waterfall history, presentation settings,
  connection state, workspace ownership, and subscription activity never enter SQLite.
- The mock/fixture, BOSGAME live-proxy, and packaged release frontend modes render the same contract and access states.

Final palette, typography, spacing, breakpoint values, default waterfall depth, and exact display-control grouping may
be polished during implementation. Measured FFT/frame-rate caps may be lowered to protect core radio work, but the
single-admin, one-workspace, one-target and cropped adaptive-resolution contract is fixed.
