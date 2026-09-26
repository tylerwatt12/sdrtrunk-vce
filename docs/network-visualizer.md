# Network Visualizer

Network Visualizer is a live, session-scoped view of observed radio activity. Open it from **Listen > Network
Visualizer**. Every visit starts empty and builds one shared canvas only from activity observed at that visit's live
edge. It does not load saved systems, historical calls, idle Live rows, or subscriber catalogs.

The 3D canvas uses a deliberately small wireframe vocabulary: spheres are radio systems, cubes are talkgroups or
conventional channels, triangular pyramids are observed source radios, and faceted aggregate nodes account for hidden
activity. The overview keeps those system spheres compact. Entering a system moves the camera inside its enclosing
wireframe sphere, where its talkgroups and their radios occupy the contained 3D volume. Positions show logical
relationships—not geography, subscriber location, RF coverage, or proof that a radio is listening.

Distance is deliberately visible: shapes and labels soften and fade into scene fog as they recede. Selected entities,
active talkgroups, and their observed source radios remain sharp even at a distance, and label stacking follows camera
depth so that nearer labels stay in front of farther ones.

## Reading the view

- A solid line is successful affiliation evidence currently retained for that observation scope.
- A dashed line is transmission activity without an inferred affiliation.
- Green **Grant** state means the shared Live feed has confirmed current RF transmission activity. It identifies an
  observed active source and its target; an unknown source lights only the target hub. A one-second visual release
  hold prevents short update boundaries from flashing. A control-channel grant by itself is not shown.
- Amber introduces a newly observed successful affiliation. A comparable, ordered change moves the same node and is
  recorded as **Observed affiliation change**. Conflicting site evidence is shown as ambiguous instead of inventing a
  sequence.
- Fading and removal are presentation aging only. They do not mean over-the-air de-affiliation, power-off, or listener
  departure. Only a supported explicit presence-clear event removes authoritative evidence.
- The lock treatment is encryption metadata, not a receiver error and not a fabricated audio waveform.

A large six-second affiliation alert appears only for an unambiguous, ordered change between comparable successful
observations for the same radio and observation scope. First sightings, repeated or older observations, requests,
denials, grants, transmissions, conflicting multi-site evidence, identity reconciliation, and post-gap recovery do not
trigger it. The alert is cleared rather than replayed across a live gap, map clear, or hidden-tab backlog.

P25 accepted or confirmed structured affiliation evidence is supported. An accepted location registration with a
real group is retained as distinct registration evidence; a group-less registration is presence only and does not
create a radio node. Requests, denials, grants, generic decoder events, and alias matches do not create affiliations.
DMR and NXDN calls retain the backend's canonical identity scope, but affiliation movement for those protocols is
currently reported as unsupported rather than inferred. Analog AM/NBFM activity lights its configured channel
without creating a synthetic radio or talkgroup.

The existing demand-driven `network_activity` multiplex subscription also carries bounded P25 `signaling_observed`
events for **Denial**, **Check**, **Emergency**, **Page**, and **Busy**. These are classified from structured decoder
semantics on the existing observer worker and do not imply affiliation or transmission. **Join** and **Logout** are the
existing accepted affiliation and explicit presence-clear events. Confirmed RF activity still comes only from the
shared `channel_activity` coordinator; the visualizer opens no per-entity connections and consumes no raw decoder
message stream.

## Controls

The 3D overview begins with observed systems only. Select a system to enter its live network, then select a talkgroup
to focus its retained radios. The breadcrumb back button returns through those levels without replacing the canvas.
The view remains three-dimensional at every level. Camera moves are animated unless reduced motion is enabled.
Left-dragging the canvas orbits the current level, right-dragging pans it, and the mouse wheel zooms toward the pointer.
Selecting a system or talkgroup also opens its inspector while the shared canvas drills into that level. **Auto rotate**
is enabled by default. It favors the system or talkgroup with the most current confirmed transmitters, requires a
candidate to lead for two seconds, holds a target for at least ten seconds, and preserves the current zoom while moving
the orbit center. Manual camera interaction suppresses attention moves for ten seconds; reduced motion disables them.

Use the toolbar to search retained entities, filter relationship detail, fit the current level, focus the current
selection, freeze only layout motion, open the bounded **Observed activity** drawer, enter fullscreen, or change
display density.
Graph objects are not draggable, so pointer gestures remain dedicated to predictable orbit, pan, and zoom controls.

Observed activity follows the newest event by default. Short bursts of routine Grant, Join, Logout, Denial, Check,
Page, and Busy observations are grouped while emergencies and affiliation changes remain individual. Scrolling away
from the newest event pauses following and keeps the reading position stable; the new-event control returns the drawer
to the latest activity.

**Clear map** clears this browser session's retained entities, activity history, selection, transitions, active effects,
and comparison state and establishes a new live edge. It does not stop receivers, delete receiver history, change
Hold/Avoid, or alter scan lists. Display settings remain separate.

The canvas omits retained-count, offscreen-entity, and persistent gap banners so they do not cover the network. Use
**Fit all** when activity may be outside the current camera frame. Transport loss changes the toolbar status badge to
**Live gap**; active indicators become uncertain and stop, and the view never animates across the missing interval.
Rendering pauses while the tab is hidden, while bounded live-state ingestion continues without an animation backlog.

## Balanced safety limits

The default profile renders at most 1,000 total nodes, 900 links, 80 labels, 150 effect particles, and 50 migration
trails. It softly targets 100 radios per talkgroup, 1,000 radios overall, 80 expanded talkgroups, and 8 expanded
systems. Suppressed entities are represented by explicit `+N` aggregates: known sources use retained-radio counts,
while excess unknown or duplicate active legs are identified as call legs. Retained session state is separately bounded
to 20,000 radios, 5,000 hubs, 64 regions, 5,000 semantic events, and 40,000 deduplication entries. Inactive,
unselected state is eligible for removal after 30 minutes and can be evicted sooner at capacity.

These are guardrails, not a claim about the receiver's whole subscriber population and not a guaranteed frame rate.
The current transport schema and loss semantics are documented in [Web API v1](api-v1.md#live-data).

## Deterministic development fixture

On a loopback host only, append `network_fixture=1` while opening the view to run the bounded deterministic fixture.
The page labels fixture mode explicitly and does not connect it to receiver activity. Without that exact local opt-in,
the view never substitutes demo events for a disconnected production feed.
