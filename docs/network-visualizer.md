# P25 Visualizer

P25 Visualizer shows P25 systems, talkgroups, radios, and their observed relationships in an interactive 3D scene.
Open **Listen > P25 Visualizer** to explore activity saved by your receiver.

This guide describes current Nightly. The scene uses stored activity history, so new events appear after the normal
saving and refresh delay.

## Get started

1. Choose **1h** or **24h** for the history to display. The default is one hour.
2. Select a system sphere to enter it, then select a talkgroup to look more closely at its radios.
3. Open **Events** to read noteworthy activity.
4. Choose **Auto** to let the camera follow selected new events, or **Manual** to explore at your own pace.
5. Use **Settings** to choose which events highlight the scene or move the automatic camera.

Use **← System** to leave a talkgroup view and **← All systems** to return to the overview. The fullscreen control
gives the scene more room.

Detailed activity history must be enabled under **Administration > Activity settings**, and your account must have
access to radio activity. Conventional channels, DMR, and NXDN are not included in this P25 view.

## Read the scene

| Shape or line | Meaning |
| --- | --- |
| **Sphere** | One P25 radio system. |
| **Cube** | A talkgroup observed in the selected history. |
| **Triangle** | A radio, with its system identity kept separate from radios using the same number elsewhere. |
| **Solid line** | The latest accepted talkgroup affiliation observed for that radio. |
| **Faint line** | An earlier accepted affiliation within the selected time range. |
| **Dashed line** | Call or grant activity involving that radio and talkgroup. It does not prove affiliation. |

An affiliation is a radio's accepted association with a talkgroup. The first accepted affiliation in the selected
history places the radio. A later comparable accepted affiliation to another talkgroup appears as an **Observed
affiliation change**. A request, denial, or ordinary voice call does not prove that change.

Positions represent radio relationships. They do not show geography, coverage, or proof that a radio is currently
listening. Use the website's **Map** page for decoded geographic positions when available.

## Choose highlights and camera behavior

**Settings** has separate choices for **Scene highlights** and **Automatic camera**. For example, you can highlight
routine calls while letting only emergencies and affiliation changes move the camera. Choices are saved in this
browser for the current web profile.

Calls and grants are highlighted by default, with automatic camera attention disabled for them. Their dashed lines
show heard activity separately from accepted affiliations. Turn off both Calls and grants choices to exclude routine
activity from the visualizer.

In **Auto**, the camera slowly orbits inside a system while idle. An enabled new event can briefly focus its radio or
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

- observed affiliation changes and explicit logout;
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

- Try **24h** if the last hour has little activity.
- Check **Administration > Activity settings** to confirm detailed history is enabled.
- Check your radio-activity access and that the receiver has saved supported P25 trunked activity.
- In Visualizer **Settings**, enable Calls and grants if you want ordinary traffic included.
- If the page says the range contains too much activity to load, choose **1h**.

Only systems with supported observations in the selected history appear. Saved channels without such activity do
not create empty system spheres.

<details>
<summary>How history updates and automatic attention work</summary>

The visualizer reads retained Activity history rather than live decoder messages, channel snapshots, or audio.
Initial history builds the current scene without replaying old camera animations. Later refreshes read forward,
deduplicate overlapping records, and update relationships even when an event does not receive camera attention.
The existing `?view=network-visualizer` URL remains valid.

After loading, the view automatically enters the system with the most supported activity. Changing the time range
rebuilds the scene. Radios stay within their system spheres.

An automatic focus holds for five seconds and returns smoothly. Emergencies have highest priority; affiliation
changes and denials share the next priority, followed by patch/busy/queued activity, then pages/checks/logout.
Routine calls have the lowest priority when their camera choice is enabled. An eight-second cooldown suppresses
equal- or lower-priority attention; a higher-priority event can interrupt. Suppressing camera movement does not
suppress the saved graph or Events panel.

The visualizer does not change receiver decoding, Hold/Avoid, Scan Lists, aliases, or stored history. Leaving the
page releases its rendering and refresh resources.

</details>

[Browse the documentation index](README.md).
