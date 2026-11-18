# Operator tools

## vce-admin

`vce-admin.py` is a small, dependency-free CLI for the parts of a deployment that
otherwise need hand-written SQL against `sdrtrunk.sqlite`: which channels start
automatically, which talkgroups are recorded, which encryption keys are showing
up, and what the receiver is currently doing.

It reads the application root from `$VCE_HOME` (or auto-detects `/srv/*/app/vce-*`
and `~/vce/vce-*`), and expects the standard layout:

```
<root>/app/vce-*/data/database/sdrtrunk.sqlite
<root>/recordings/**/*.mbe
```

Override any path with `--db`, `--recordings`, `--home`, and the systemd unit name
with `--service` (default `sdrtrunk-vce`).

### Read-only commands

```
$ vce-admin status
service     sdrtrunk-vce: active (running)
uptime      0:52:05
jvm pid     13658
heap        max=8g initial=2g resident=1.8GB
channels    17 configured, 1 auto-start
talkgroups  7859 aliases, 327 recording
recording   731 calls in the last hour
tuners      0 'No Tuner Available' events in the last hour

$ vce-admin channels --autostart
$ vce-admin recording --show
$ vce-admin calls --since 30m --limit 10
$ vce-admin keys --since 24h --site "Site 04-04"
```

`keys` reports the algorithm, key ID, whether that ID is present in
`~/.vce-adp-keys.properties`, and which talkgroups carried it. Frames without an
encryption header are ignored, and a call only counts when at least 80% of its
encrypted frames agree on one key, so link-control noise does not show up as a
phantom key.

### Changing configuration

Anything that writes is a dry run unless `--apply` is passed, and every write
command reminds you to restart, because a running instance keeps the alias table
and channel state cached until it restarts.

```
# preview, then apply
$ vce-admin recording --groups "OSP District 8" --mode on
$ vce-admin recording --groups "OSP District 8" --mode on --apply

# single talkgroup
$ vce-admin recording --talkgroups 21411 --mode on --apply

# make one channel the only auto-start channel
$ vce-admin channels --autostart-only "Hamilton County Simulcast" --apply
```

Recording changes affect audio capture only. Bitstream recorders are configured
per channel in the application database and are not touched here, so `.mbe`
capture continues for every call regardless of the recording policy.
