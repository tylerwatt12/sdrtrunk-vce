# How VCE uses your tuners

An SDR dongle receives a small window of frequencies at a time. VCE can receive several channels inside that window,
but frequencies farther apart need another tuner or a change of center frequency. The number of dongles alone does
not tell you whether every call can be received: their usable bandwidth, settings, and existing channel assignments
all matter.

This guide describes current Nightly behavior. Numbered Alpha releases may use a different allocation order or have
different controls. The frequencies below are made-up examples, not a particular radio system.

## The frequency window

A dongle's overall tuning range describes where it can move. Its **bandwidth** describes how much it can receive
at once. A dongle that can tune to both 700 MHz and 800 MHz cannot necessarily receive both bands simultaneously.

The **center frequency** is the middle of its current window. The **sample rate** determines the nominal width of
that window. VCE leaves safety margins at its edges, and some tuner types also have a small unusable area at the
exact center. The entire radio channel must fit in the usable area, including its width.

For example, an RTL-SDR with an R820T-family tuner at 2.4 MS/s has about **2.35 MHz of usable bandwidth**. It can
receive several nearby frequencies within that width. It cannot receive a control channel at 771 MHz and a voice
channel at 851 MHz together. Other tuner models and sample rates provide different usable widths.

VCE separates the frequencies inside a tuner's window into individual software channels. This lets one dongle feed
several decoders without scanning between those frequencies. Increasing the decoder count does not widen the window.

Two dongles provide two independent windows. They can cover separate frequency groups, but gaps between their
windows remain uncovered.

## How VCE chooses a tuner

**Allocation** means assigning a requested radio channel to a tuner. VCE uses this process when starting a
conventional channel, starting a trunked control channel, or receiving a request for a temporary voice channel.

Conventional channels and trunking control channels need the bandwidth **immediately available before they can
start**. That bandwidth can come from existing coverage or an allowed retune during startup. If no tuner can supply
the source then, the channel cannot start.

For ordinary channel requests, VCE tries these choices in order:

1. **Use existing coverage.** Check eligible tuners whose current windows already cover the requested channel.
2. **Tune an idle tuner.** If existing coverage does not work, try an available tuner with no assigned channels.
3. **Move a tuner already in use.** If neither choice works, try an unlocked tuner that can move while keeping every
   existing channel and the new channel within its usable window.
4. **Decline the new allocation.** If no eligible tuner can supply it, that channel cannot start.

There is one initial-placement exception: if a channel supplies a group of control frequencies or a broader working
range that fits the tuner, VCE may initially move an idle tuner to cover that group, even if the first requested
frequency was already visible.

**Preferred Tuner** is tried first within each step. A different tuner that already covers a frequency can therefore
be used before the preferred tuner that would need to move. The preference does not reserve hardware or override
frequency limits and center locks. P25 traffic requests carry their parent channel's preference.

An allocation can also fail while a tuner is temporarily busy with another tuner operation. A connected dongle is
not necessarily available for a new source at that instant.

## Which channels occupy a tuner

| Channel | How long its assignment remains |
| --- | --- |
| Started conventional channel | While the channel is running, including when nobody is transmitting. |
| Trunked control channel | While the site is receiving that control source. |
| Trunked voice or traffic channel | While its temporary decoder remains active; it is released after the call finishes and the decoder stops. |
| Saved channel that has not been started | It has no tuner assignment. |

An idle conventional frequency still matters if its channel is running. It can prevent a tuner from moving far
enough to receive another frequency.

When an assignment ends, VCE does not automatically recenter the tuner or move its remaining channels to another
dongle. The next request is evaluated against the assignments and centers that exist then.

## Settings that affect allocation

Open **Hardware > Tuners** to select a tuner and inspect its settings.

| Setting | What it means |
| --- | --- |
| **Live** | The tuner can supply ordinary receiver channels when it is otherwise available. |
| **Setup** | The tuner is available for adjustment, but ordinary channel allocation does not use it. |
| **Disabled** | The receiver does not use this tuner. |
| **Sample rate** | Sets the width of the receiving window. Usable bandwidth is smaller than the nominal width. |
| **Lock center** | Prevents automatic allocation from moving the center frequency. New channels must fit the current window. |
| **Minimum frequency / Maximum frequency** | Restrict which frequencies this tuner may receive. The whole requested channel and any new center must be within the permitted range. |

**Lock center** appears in the tuner's **Tuning** area. **Minimum frequency** and **Maximum frequency** are under
**Common tuning > Frequency limits**, with values in MHz.

Frequency limits do not make a dongle receive the whole allowed range at once. For example, allowing 760–870 MHz
still gives an RTL-SDR one narrow window somewhere within that range. Limits also do not assign it exclusively to
one site; other channels within the permitted range may use it.

Open **Manage > Channels**, edit the channel, and look under **Source** for **Preferred Tuner**.

### Site working frequencies are separate limits

Some trunked channel configurations also have **Minimum working frequency** and **Maximum working frequency**
under **Source**. These describe a broader frequency range for that channel or site, separately from each tuner's
limits.

When that whole range fits a tuner, VCE can use it to choose a useful initial center. When it is too wide, the tuner
allocator falls back to fitting the requested channel alongside its existing assignments. It does not automatically
divide the site's range among several dongles or reserve coverage for every possible voice frequency.

For P25, these working-frequency limits also act as a guard: a resolved voice frequency outside the saved range is
rejected before tuner allocation. Check them if a site starts using a valid frequency outside its saved range.
Do not set a site's working range to just the frequencies heard during a short observation period.

## Common scenarios

The RTL-SDR examples assume R820T-family tuners at 2.4 MS/s, with center locks off and frequency limits allowing the
requested channels, unless stated otherwise. Calls also need an available traffic decoder and a usable signal.

### One RTL-SDR with nearby control and voice frequencies

Suppose a site uses control at 771.0125 MHz and voice at 771.5125 and 772.0125 MHz. All three can fit inside one
RTL-SDR window. With a suitable center, VCE can decode control and both voice frequencies simultaneously without
moving the tuner for each call.

The number of conversations does not by itself determine the number of dongles needed. The frequency spread and
available decoding capacity matter.

### One RTL-SDR with control in 700 MHz and voice in 800 MHz

Suppose the running control channel is at 771.0125 MHz and a grant requests voice at 851.0125 MHz.

The dongle cannot fit both frequencies in one window. VCE does **not** tune away from the active control channel to
chase that voice call. The voice allocation fails, while the control source remains assigned. The receiver may show
the detected call because it heard the grant, even though it cannot receive its audio.

### Two RTL-SDRs with control in 700 MHz and voice in 800 MHz

With the same site and a second eligible idle dongle, one tuner continues receiving control at 771.0125 MHz. The
second can tune to the requested voice frequency at 851.0125 MHz as soon as a valid grant requests it. VCE does not
need to observe the site for a day before making that allocation.

Several nearby voice frequencies can share the second tuner. For example, 851.0125, 851.5125, and 852.0125 MHz can
fit together. But if another simultaneous call is at 855.0125 MHz, the voice tuner cannot hold both frequency groups,
and the control tuner cannot reach them while retaining its 700 MHz control channel.

Once the earlier voice assignments are released, the second tuner may move for a later call. It does not abandon
active voice channels to follow an incompatible request.

If that second dongle has **Lock center** enabled near 771 MHz, or its frequency limits exclude 851 MHz, it cannot
supply the voice call. Having it connected does not remove those restrictions.

### One tuner needs a small shift while control remains covered

Suppose an RTL-SDR is receiving control at 771.0125 MHz, but a voice request at 773.0125 MHz is outside its current
window. Those two frequencies are close enough to fit together at a different center.

VCE first checks other current windows and idle tuners. If none works, this tuner may move to include both
frequencies, provided every other channel using it also fits and **Lock center** is off.

That keeps the control frequency within range after the move. It does not guarantee continuous control decoding
through the move itself; see [Control reception during a retune](#control-reception-during-a-retune).

### Enough dongles but assignments leave no room

Consider two RTL-SDRs with these existing assignments:

| Tuner | Frequencies already assigned |
| --- | --- |
| A | 851.0125 and 853.0125 MHz |
| B | 852.0125 and 854.0125 MHz |

A new request at 855.0125 MHz cannot fit alongside all of A's channels or all of B's channels. Neither tuner is idle,
so the allocation fails.

The same hardware could cover all five frequencies with a different arrangement: A could receive 851.0125,
852.0125, and 853.0125 MHz, while B receives 854.0125 and 855.0125 MHz. The problem is where the existing channels
are assigned. Current VCE does not transfer active channels between dongles to build that arrangement.

This is why enough total hardware is different from having usable coverage available for a particular request.
A tuner already receiving channels should use its current coverage for a requested frequency that fits, subject to
its limits and availability. An idle tuner can still use the initial-placement exception described above. An
unexpected failure needs investigation of the actual request and tuner state.

### A rare voice frequency appears later

Control channels tell VCE where requested calls will occur. Some voice frequencies may appear quickly; others may
not be used for hours or days. A configured list or the frequencies observed so far may be incomplete.

When a valid new request arrives, VCE attempts it using the current tuning rules and configured limits. It does not
wait for a complete frequency list. An idle eligible tuner can move for it immediately. If every tuner is busy and
none can fit the frequency while keeping its existing channels, that allocation cannot succeed then.

Current VCE does not automatically maintain a reserved coverage plan for all frequencies ever observed. A quiet
period also cannot establish that every possible site frequency has been learned. When planning fixed centers,
include known alternate control frequencies and allow for valid frequencies discovered later.

### Several sites share the same tuners

Tuners form a shared pool. For example, two nearby control channels from different sites may share one dongle, and
their voice calls may use other dongles. A started conventional channel uses the same pool too.

Each new request must fit around the assignments already present. **Preferred Tuner** does not give a site exclusive
ownership of a dongle. A layout that covers one site by itself may be insufficient when another site or conventional
channel is running at the same time.

### Centers are locked

If each wanted frequency fits inside at least one eligible tuner's fixed usable window, locking the centers prevents
allocation from moving those windows. Requests outside every fixed window cannot be received through those tuners.

A lock can make a chosen layout stable, but it does not fill gaps or widen coverage. Check the channel edges and
the unusable center area as well as the listed frequencies. A new frequency or changed control channel may require
reviewing the layout.

The [Center Frequency Planner](../tools/sdrtrunk-center-frequency-calculator.html) can help compare frequency groups
and tuner bandwidths. Download the HTML file and open it in a browser. Its result is a coverage plan for the supplied
inputs; it does not apply settings to the receiver or account for its live assignments.

## Control reception during a retune

An automatic allocation must keep every existing channel on the chosen tuner inside the new usable window. This
includes control, conventional, and active voice channels. VCE rejects a request that cannot fit with them.

However, moving the center changes the physical tuner and the software's channel positions. That transition can
briefly disturb samples or decoding even when the control frequency fits both before and after the move. VCE does
not guarantee uninterrupted control messages or voice audio during a retune, and it does not establish a second
control receiver before moving the original tuner.

The allocator reduces unnecessary movement by using current coverage first and idle tuners next. **Lock center**
prevents allocation-driven center moves, but does not guarantee reception against weak signals, correction
adjustments, or other reception problems.

If the radio system itself changes control frequency, VCE's configured control-frequency list can let it try another
source. That search is separate from moving a tuner to receive a voice call and can have a reception gap.

## Understanding failures and missing audio

| What you see | What to check |
| --- | --- |
| A channel cannot obtain a tuner source, or an event reports a tuner unavailable | Eligible tuner states, current centers, active assignments, usable bandwidth, locks, and tuner frequency limits. |
| A P25 frequency is outside the site's working range | The channel's **Minimum working frequency** and **Maximum working frequency**, separately from tuner settings. |
| **MAX TRAFFIC CHANNELS EXCEEDED** | The trunked channel's **Maximum traffic channels** under **Manage > Channels > Edit > Decoder**. This is a decoder-slot limit, separate from tuner coverage. |
| A call is detected but has no usable audio | Allocation may have failed, or reception and decoding may have failed after a source was assigned. Read the event details and receiver status. |

Adding a dongle can help when another receiving window is needed. It does not automatically correct an unsuitable
assignment layout, a center lock, restrictive limits, or a full traffic-decoder pool. Increasing **Maximum traffic
channels** permits more decoders but does not widen the dongles' receiving windows.

Open **Administration > Status & support > Receiver status** to review active and recent receiver alerts. A tuner
allocation failure counts a failed source request, which can be for control, conventional, or traffic reception.
Repeated attempts can produce several failures for the same frequency or call; the count is not a count of unique
missed conversations.

For an unexplained failure, note its time and requested frequency, then record the tuner states, centers, sample
rates, locks, frequency limits, and active channels. Check the channel's working-frequency limits and traffic limit
too. **Report a problem** can collect a support report. Remove credentials and personal information before posting
screenshots or log excerpts publicly.

Current VCE does not store a complete history of normal automatic retunes. A later snapshot of tuner centers cannot
show exactly which assignments existed at the time of an earlier failure. A successful allocation or a clear status
page also does not prove that every control message or call decoded.

## Further reading

- [Browser listening and Scan Lists](browser-listening-and-scan-lists.md): choosing what to hear after the receiver
  receives calls. Listener selections do not change tuner coverage.
- [Upstream sdrtrunk tuner guide](https://github.com/DSheirer/sdrtrunk/wiki/Tuners): general SDR and bandwidth concepts;
  its desktop controls and allocation description can differ from VCE.
- [Upstream allocation FAQ](https://github.com/DSheirer/sdrtrunk/wiki/FAQ#unable-to-source-channel-from-preferred-tuner):
  background on preferred tuners and requests that cannot fit.
