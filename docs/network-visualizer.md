# P25 Visualizer

P25 Visualizer is a three-dimensional view of noteworthy P25 activity retained by VCE. Open it from **Listen > P25
Visualizer**. The existing `?view=network-visualizer` URL remains valid.

The visualizer reads the same stored Activity history as the Activity page. It does not subscribe to live decoder
messages, channel snapshots, call audio, or the completed-call queue. Newly recorded events normally appear after the
next history poll, so this view intentionally trails over-the-air signaling by the normal storage and polling delay.

Detailed P25 activity history must be enabled, and the browser session must be allowed to view Radio activity. An
empty view can mean that history is disabled, outside the selected time range, or contains no supported P25 records.

## Reading the scene

- A wireframe sphere is one canonical P25 radio system.
- A cube inside the sphere is a talkgroup observed in retained history.
- A triangular node is a canonical subscriber radio. Numeric IDs from different systems remain separate.
- A solid relationship records the latest accepted affiliation observed for a radio.
- A faint relationship records an earlier accepted talkgroup relationship within the selected window.

Positions express logical relationships, not subscriber location, geography, RF coverage, or proof that a radio is
currently listening. A radio never moves between system spheres.

Accepted P25 affiliation history establishes a radio's relationship to a talkgroup. The first accepted observation in
the selected window establishes its initial placement. A later comparable accepted observation for another talkgroup
is shown as an **Observed affiliation change**. Requests and denials never move a radio, and an affiliation inferred
only from ordinary voice traffic is not presented as proven.

The activity drawer is deliberately limited to grouped noteworthy history:

- observed affiliation changes and explicit logout;
- emergency observations;
- denials associated with a canonical radio identity;
- busy or queued responses;
- pages/call alerts and radio checks; and
- explicit patch activity when the stored record establishes it.

Repeated denials for the same system-scoped radio are combined into one current entry. The view omits ordinary grants,
calls, active-channel updates, generic acknowledgements, registration noise, and unresolved events.

Stored history does not currently retain enough detail to distinguish inhibit/uninhibit commands, remote-monitor
commands, call-preemption reasons, or individual denial reasons. P25 Visualizer does not guess at those meanings.
Emergency records also do not establish a reliable emergency-clear time, so an emergency is presented as an observed
event rather than persistent state.

## Time range and updates

Choose **1 hour** or **24 hours**. One hour is the default. Changing the range rebuilds the scene from a bounded,
filtered history query.

Initial history is reduced chronologically into the current scene without replaying animations or interrupting the
camera. After that initial load, the browser polls forward from its stored high-water mark. Overlapping records are
deduplicated, and every accepted record updates the graph even when it does not qualify for camera attention.

After loading, the view automatically enters the system with the most supported noteworthy activity. It never creates
spheres for saved systems that had no qualifying history in the selected window.

## Camera controls

**Auto** is the default. Inside a system it slowly orbits while idle. A qualifying newly stored event produces one
deliberate pan and zoom, a five-second hold, and a smooth return to the centered orbit. Automatic attention stays in
the currently viewed system and uses this priority:

1. emergency;
2. observed affiliation change;
3. denial;
4. other supported noteworthy activity.

At most one equal-priority focus begins during the eight-second cooldown. A strictly higher-priority event may
interrupt; equal- or lower-priority requests are discarded rather than queued. Camera throttling never suppresses the
underlying graph or activity-drawer update.

**Manual** disables all automatic camera movement. With the canvas focused, W/S move forward and backward, A/D
strafe, the arrow keys turn the view, mouse drag orbits, and the wheel zooms. Nodes are not draggable.

Selecting a system enters its sphere. The back control returns to the shared system overview without replacing the
canvas. Renderer resources, polling, timers, and input listeners are released when leaving the page.

## Data scope

This view intentionally supports canonical P25 trunked-system history only. Conventional channels, DMR, and NXDN are
not projected into P25 system spheres. The stored Activity rows remain the source of truth; P25 Visualizer does not
change receiver decoding, Hold/Avoid state, scan lists, aliases, or persisted activity.
