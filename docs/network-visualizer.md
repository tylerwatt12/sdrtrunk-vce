# P25 Visualizer

P25 Visualizer shows P25 systems, talkgroups, radios, and their observed relationships in an interactive 3D scene.
Open **Listen > P25 Visualizer** to explore current observed affiliations and activity saved by your receiver.

This guide describes current Nightly. The scene loads the receiver’s current accepted P25 affiliations plus the
latest 500 saved P25 activity records, then updates automatically. An open view keeps up to 5,000 recent Activity
records; current affiliations are kept separately. Use the Activity pages to browse retained history. The status
shows live updates, reconnection, or a pause while the tab is hidden.

## Get started

1. Select a system sphere to enter it, then select a talkgroup to look more closely at its radios.
2. Open **Events** to read noteworthy activity.
3. Choose **Auto** to let the camera follow selected new events, or **Manual** to explore at your own pace.
4. Use **Settings** to choose which recent saved events highlight the scene or move the automatic camera.
5. Use **Refresh** to reload current state, recent activity, and names when needed. Normal updates arrive automatically.

Use **← System** to leave a talkgroup view and **← All systems** to return to the overview. The fullscreen control
gives the scene more room.

Your account must have access to radio activity. Enable detailed activity history under
**Administration > Activity settings** to collect new history. Current affiliations continue updating while
detailed logging is off, and recent saved activity remains available. The status reads **Live affiliations · Activity
collection off**. Conventional channels, DMR, and NXDN are not included in this P25 view.

## Read the scene

| Shape or line | Meaning |
| --- | --- |
| **Sphere** | One P25 radio system. |
| **Cube** | A talkgroup in current affiliations or recent activity. |
| **Triangle** | A radio, with its system identity kept separate from radios using the same number elsewhere. |
| **Solid line** | The latest accepted talkgroup affiliation observed for that radio. |
| **Faint line** | A talkgroup from a recent saved affiliation observation. |
| **Dashed line** | Call or grant activity involving that radio and talkgroup. It does not prove affiliation. |

An affiliation is a radio's accepted association with a talkgroup. Solid links come from the receiver’s current
affiliation state, including associations older than the recent Activity records. Saved JOIN and LOGOUT events
describe observations; they do not replace that current state. A fresh current-state change on a comparable
observation channel can appear as an **Observed affiliation change**. A request, denial, or ordinary voice call does
not prove that change.

The current-state snapshot supports up to 10,000 associations. If it becomes unavailable, the view marks the last
complete state as stale until it recovers. A system scene displays at most 900 radios and 220 talkgroups; **Partial
view** indicates that some nodes are omitted. Select a talkgroup to narrow the scene.

Positions represent radio relationships. They do not show geography, coverage, or proof that a radio is currently
listening. Use the website's **Map** page for decoded geographic positions when available.

## Choose highlights and camera behavior

**Settings** has separate choices for **Scene highlights** and **Automatic camera**. For example, you can highlight
routine calls while letting only emergencies and affiliation changes move the camera. Choices are saved in this
browser for the current web profile.

Calls and grants are highlighted by default, with automatic camera attention disabled for them. Their dashed lines
show heard activity separately from accepted affiliations. Turn off both Calls and grants choices to exclude routine
activity from the visualizer.

In **Auto**, the camera slowly orbits inside a system while idle. A new saved event can briefly focus its radio or
talkgroup before returning to the centered view. Attention stays inside the system you are viewing.

In **Manual**, the camera stays under your control. Focus the scene, then use:

| Input | Movement |
| --- | --- |
| **W / S** | Forward / backward |
| **A / D** | Left / right |
| **Q / E** | Down / up |
| **Arrow keys** | Turn the view |
| **Mouse drag** | Orbit |
| **Mouse wheel** | Zoom at the cursor |

Nodes cannot be dragged to new positions.

## Read noteworthy events

The **Events** panel groups:

- saved affiliation observations, observed current-affiliation changes, and explicit logout;
- emergency observations;
- denials associated with an identified radio;
- busy or queued responses;
- pages/call alerts and radio checks; and
- patch activity supported by the stored record.

Repeated denials for the same radio and system are combined into one current entry. Routine calls and grants can
appear in the scene without filling this noteworthy-events panel.

An emergency means an emergency event was observed. Stored history does not establish a reliable clear time, so it
does not indicate a continuing emergency state. The history also lacks enough detail to identify individual denial
reasons, inhibit/uninhibit commands, remote-monitor commands, or call-preemption reasons.

## If the scene is empty

- Enable detailed history in **Administration > Activity settings** to include new saved events.
- Check your radio-activity access and that the receiver has saved supported P25 trunked activity.
- In Visualizer **Settings**, enable Calls and grants if you want ordinary traffic included.

Only systems with current P25 affiliations or supported recent activity appear. Saved channels without such activity do
not create empty system spheres.

<details>
<summary>How history updates and automatic attention work</summary>

The visualizer starts from shared current affiliation state and a small set of recent saved Activity records. It
builds the scene without replaying old camera animations. New saved activity and corrections to existing records update the scene
automatically. Highlights use the time an event was observed, so delayed details do not look like a new radio event.
When the open view reaches 5,000 records, the oldest observations leave as newer records arrive. This does not
erase current associations or their radio and talkgroup nodes.

A brief connection loss recovers missed updates automatically. If the receiver replaces its saved history or the
missed range is no longer available, the view reloads current state and the latest 500 records. A failed reload keeps the previous scene
visible while retrying. Hidden tabs pause their subscription and recover when you return.
The existing `?view=network-visualizer` URL remains valid.

After loading, the view automatically enters the system with the most supported recent activity. Refresh keeps
your selected system or talkgroup when it remains in the scene. Radios stay within their system spheres.

An automatic focus holds for five seconds and returns smoothly. Emergencies have highest priority; affiliation
changes and denials share the next priority, followed by patch/busy/queued activity, then pages/checks/logout.
Routine calls have the lowest priority when their camera choice is enabled. An eight-second cooldown suppresses
equal- or lower-priority attention; a higher-priority event can interrupt. Suppressing camera movement does not
suppress the saved graph or Events panel.

The visualizer does not change receiver decoding, Hold/Avoid, Scan Lists, aliases, or stored history. Leaving the
page cancels outstanding activity requests, unsubscribes from updates, and releases its rendering resources.

</details>

[Browse the documentation index](README.md).
