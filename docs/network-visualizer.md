# P25 Visualizer

P25 Visualizer shows P25 systems, talkgroups, radios, and their observed relationships in an interactive 3D scene.
Open **Listen > P25 Visualizer** to explore observed affiliations and live activity from your receiver.

This guide describes current Nightly. The scene starts with live call activity and grows as new observations arrive.
It does not preload saved history or dormant radios. **Actions** counts and **Top Talkgroups** rankings start when
the page opens. Hidden tabs keep collecting updates while drawing and animations pause.

## Get started

1. Select a system sphere to enter it, then select a talkgroup to look more closely at its radios.
2. Open **Actions** to see action counts and choose **Zoom to** and **Highlight**, then **Save choices**.
3. Choose **Auto** to let the camera follow selected new events, or **Manual** to explore at your own pace.
4. Open **Top Talkgroups** to see the selected system’s busiest talkgroups since the page opened.
5. Leave the page open to collect new activity. Reloading it starts new counts.

Use **← System** to leave a talkgroup view and **← All systems** to return to the overview. The fullscreen control
gives the scene more room.

Your account must have access to radio activity, statistics collection must be running, and a P25 trunked channel
must be receiving. Detailed history supplies additional action observations; live calls and new affiliations can
update the scene while detailed logging is off. The status then reads **Live · Activity collection off**.
Conventional channels, DMR, and NXDN are not included in this P25 view.

## Read the scene

| Shape or line | Meaning |
| --- | --- |
| **Sphere** | One P25 radio system. |
| **Cube** | A talkgroup in current affiliations or recent activity. |
| **Triangle** | A radio, with its system identity kept separate from radios using the same number elsewhere. |
| **Solid line** | The latest accepted talkgroup affiliation observed for that radio. |
| **Faint line** | An earlier affiliation observed while this page was open. |
| **Dashed line** | Call or grant activity involving that radio and talkgroup. It does not prove affiliation. |

An affiliation is a radio's accepted association with a talkgroup. Solid links show the latest accepted affiliation
received for that radio. JOIN and LOGOUT events describe observations; they do not replace that accepted state.
A fresh current-state change on a comparable
observation channel can appear as an **Observed affiliation change**. A request, denial, or ordinary voice call does
not prove that change.

The view adds radios and talkgroups as they are heard. Select a talkgroup to narrow the scene. Quiet entries age
out after about 15 minutes, while active calls and fresh affiliation movements remain visible.

Positions represent radio relationships. They do not show geography, coverage, or proof that a radio is currently
listening. Use the website's **Map** page for decoded geographic positions when available.

## Choose highlights and camera behavior

**Actions** has separate **Highlight** and **Zoom to** choices. For example, you can highlight
routine calls while letting only emergencies and affiliation changes move the camera. Choices are saved in this
browser for the current web profile.

Calls and grants are highlighted by default, with automatic camera attention disabled for them. Their dashed lines
show heard activity separately from accepted affiliations. The Calls and grants choices control routine highlights
and camera attention. Current calls still appear in the scene.

In **Auto**, the camera slowly orbits inside a system while idle. A new action selected for **Zoom to** can briefly focus its radio or
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

## Read action counts and busy talkgroups

The **Actions** panel counts affiliation changes, emergencies, denials, busy or queued responses, radio checks,
pages, logouts, patch changes, and calls and grants. Each category has independent attention choices; counts
continue regardless of those choices.

**Top Talkgroups** ranks up to 20 talkgroups in the selected system by calls and grants observed since this page
opened. The rankings update while the panel is closed, and talkgroup names link to their detail pages.

An emergency means an emergency event was observed. It does not indicate a continuing emergency state.
The observations also lack enough detail to identify individual denial
reasons, inhibit/uninhibit commands, remote-monitor commands, or call-preemption reasons.

## If the scene is empty

- Check your radio-activity access and that statistics collection is running.
- Check that a P25 trunked channel is receiving, then wait for new activity.
- Open **Actions** to enable the highlights and camera attention you want.

Only systems with observed P25 affiliations or live activity appear. Saved channels without such activity do
not create empty system spheres.

<details>
<summary>How live updates and automatic attention work</summary>

The visualizer combines current Live call activity with new P25 observations. Highlights use the observation time,
so delayed details do not look like a new radio event. Confirmed affiliation moves keep both talkgroups visible.

A connection loss reconnects at the current live edge. Hidden tabs keep their subscriptions and session counts;
returning shows the latest state without replaying hidden movements.
The existing `?view=network-visualizer` URL remains valid.

The first available scene automatically enters a system. New activity keeps your selected system or
talkgroup when it remains in the scene. Radios stay within their system spheres.

Automatic focus briefly holds and returns smoothly. A newer action selected for **Zoom to** can replace the
current focus. Camera choices do not stop activity collection or session counts.

The visualizer does not change receiver decoding, Hold/Avoid, Scan Lists, aliases, or stored history. Leaving the
page cancels outstanding activity requests, unsubscribes from updates, and releases its rendering resources.

</details>

[Browse the documentation index](README.md).
