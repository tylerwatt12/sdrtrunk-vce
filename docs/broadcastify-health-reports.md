# Broadcastify Calls health reports

VCE sends Broadcastify Calls a **node health report** about how well it receives the sites and channels that feed a
destination. Reports are on by default, including for destinations saved before this setting was added. You can turn
them off for each Broadcastify Calls destination in **Streaming**.

Reports normally go every 5 minutes to `call-health` on the same server as your uploads
(`https://api.broadcastify.com/call-health`). A destination whose server address does not end in `call-upload` sends
none. An unavailable or busy server makes reports less frequent; uploads continue independently.

## Which systems a report covers

- A **Broadcastify Calls (Trunked Site)** destination reports the saved site channel you chose for it.
- A **Broadcastify Call** destination reports saved channels whose Alias List has an alias that streams to it.

Destinations that upload as the same System ID share one report, covering their feeding sites and channels.
Each site is reported whole: its totals include calls that do not stream to that destination.

VCE collects these measurements only for channels that can feed an enabled reporting destination. Destinations
sharing a channel reuse its collection. Coverage changes normally take effect within 10 seconds. Starting collection
establishes a new measured window rather than treating the earlier unmeasured time as quiet reception.

## What a report contains

Reports include reception and call totals, plus configuration and radio system metadata:

- **Trunked sites (P25, DMR, NXDN):** configured site or channel names; the site identity heard on the control
  channel (P25: WACN, System ID, RFSS, site, NAC; DMR: network, site, color code; NXDN: system, site, RAN); the
  control frequency; time decoding control messages; blocks decoded or failed; supported time-without-sync totals;
  calls announced and followed; and reasons calls were not followed, including an unavailable frequency, tuner or
  traffic channel. When several receivers hear the same site, entries can also include their configured channel names.
- **Conventional channels:** frequency, configured name, sample delivery time when measured, open time and call
  totals. Sample delivery time comes from the samples VCE receives; it does not independently detect every hardware loss.
- **Available tuners:** tuner name (which can include its hardware serial number), kind (`airspy`, `hackrf`, `rtlsdr`, and others), running time and measured
  frequency error when available. This is the available tuner set, which can include tuners serving other destinations.
- **VCE:** version and running time.
- **Destination:** System ID and API key, as used for call uploads.

Reports do not include talkgroup IDs, radio IDs, talker aliases or audio. Configured names are included as entered.
A DMR or NXDN channel that is not trunked is reported as a conventional channel when its sample delivery can be
measured. Remote P25 control-bitstream sources do not supply local call-allocation totals and are not included.

Calls are counted once their outcome is settled, when they end or their existing continuation period expires. A call
that initially had no available traffic channel and was followed on a later update counts only by its final outcome.
A call counted as followed was assigned to a traffic channel; it need not have been uploaded to this destination.
The report cannot count announcements VCE never decoded or explain an individual missing transmission. A quiet
conventional channel alone cannot establish whether its antenna is working.

If measurement collection is incomplete, VCE skips that interval and marks a gap in the next complete report. It
preserves reception instead of reporting partial totals as if no calls occurred.

## Turning reports off

In **Streaming**, open the destination, select **Settings**, and turn off **Send node health reports**. Save the
settings to apply the change. Calls continue uploading with reporting off.

Reports give Broadcastify reception history even when there are no calls to upload. The linked draft format currently
states that Broadcastify stores reports for 30 days and does not yet use them to select which node's calls it takes.
Possible future uses include comparing feeds and notifying operators about reception problems.

## The format

The shared [node health report, version 1](https://github.com/blantonl/bcfy-calls-node-health/blob/main/node-health.md)
describes the fields, delivery schedule and Broadcastify binding. Fields VCE does not measure are omitted.
