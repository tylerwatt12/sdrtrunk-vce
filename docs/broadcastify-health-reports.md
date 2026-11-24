# Broadcastify Calls health reports

When you upload to Broadcastify Calls, VCE also sends Broadcastify a short **node health report** every 5 minutes:
how well it hears the systems it uploads. It is on by default, and you can turn it off for each Broadcastify Calls
destination.

Reports go to `call-health` on the same server as your uploads (`https://api.broadcastify.com/call-health`). A
destination whose server address doesn't end in `call-upload` sends none.

## Which systems a report covers

- A **Broadcastify Calls (Trunked Site)** destination reports the one saved site channel you chose for it.
- A **Broadcastify Call** destination reports every saved channel whose Alias List has an alias that streams to it.

Destinations that upload as the same System ID send one report between them, with all of their sites: Broadcastify
takes one report per System ID.

Each site is reported whole: all of its calls, not only those that stream to that destination.

## What a report contains

Counts of what VCE heard, not scores:

- **For each trunked site (P25, DMR, NXDN):** the identity its control channel broadcasts (P25: WACN, System ID,
  RFSS, site, NAC; DMR: network, site, color code; NXDN: system, site, RAN) and the control channel's frequency; the
  seconds spent decoding it, the messages decoded and lost, and (P25, NXDN) the time without frame sync; the calls it
  announced, how many were recorded and why any weren't (encrypted, data, no tuner, every traffic channel busy, a
  channel with no frequency for it).
- **For each conventional channel:** its frequency and the name you gave it, the time it was heard and open, and its
  calls.
- **For each tuner those use:** its name, its kind (`airspy`, `hackrf`, `rtlsdr`, ...), the time it ran and its
  frequency error.
- **About VCE:** its version and how long it has been running.
- **Who sends it:** the destination's System ID and API key, as every upload does. Broadcastify doesn't keep the key
  with the report.

Nothing about people: no talkgroups, radio IDs, talker aliases or audio.

A DMR or NXDN channel that isn't trunked is reported as a conventional channel.

## Turning them off

In the destination's streaming settings, turn off **Send node health reports**. Uploads carry on as before, but
Broadcastify relies on these reports to look after your node, and without them:

- **Your node can't be compared with others.** When several nodes upload the same system, Broadcastify chooses whose
  calls to take by how well each one hears it. A node that doesn't report can't be compared, so it may be given a
  lower priority or left out.
- **Broadcastify can't tell you about problems.** It can't warn you of an outage, a lost control channel, rising loss
  or other trends on your node, and it can't tell a node that's quiet from one that's down.
- **Support can't see how your node has been doing** when you ask for help with it.

We recommend leaving them on.

## The format

The report format is shared with Trunk Recorder Pro and SDRTrunk:
[node health report, version 1](https://github.com/blantonl/bcfy-calls-node-health/blob/main/node-health.md).
