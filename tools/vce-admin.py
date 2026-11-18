#!/usr/bin/env python3
"""vce-admin - operator CLI for SDRTrunk VCE deployments.

Wraps the site-local database and recordings so routine receiver changes do not
require hand-written SQL against sdrtrunk.sqlite. Read-only commands are safe to
run while VCE is running; commands that change configuration warn, because the
running instance caches aliases and channel state until it is restarted.

Examples:

    vce-admin status
    vce-admin channels --autostart
    vce-admin channels --autostart-only "Hamilton County Simulcast"
    vce-admin recording --show
    vce-admin recording --groups "Cincinnati Police (Hamilton County)" --explain
    vce-admin recording --groups "Cincinnati Police (Hamilton County)" --apply
    vce-admin keys --since 24h
    vce-admin calls --since 1h --encrypted

Defaults come from $VCE_HOME (the application root) and can be overridden with
--db / --recordings / --service.
"""

from __future__ import annotations

import argparse
import collections
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timedelta

ALG_NAMES = {
    0x80: "clear",
    0x81: "DES-OFB",
    0x82: "2-key 3DES",
    0x83: "3-key 3DES",
    0x84: "AES-256",
    0x85: "AES-128",
    0xAA: "ADP",
}


def default_home() -> str:
    """Locate the VCE application root."""
    env = os.environ.get("VCE_HOME")
    if env:
        return env
    for candidate in sorted(glob.glob("/srv/*/app/vce-*")) + sorted(glob.glob(os.path.expanduser("~/vce/vce-*"))):
        if os.path.isdir(os.path.join(candidate, "data", "database")):
            return candidate
    raise SystemExit("cannot locate the VCE application root; set VCE_HOME or pass --db")


def resolve_paths(args) -> tuple[str, str]:
    home = args.home or default_home()
    db = args.db or os.path.join(home, "data", "database", "sdrtrunk.sqlite")
    # Layout is <root>/app/vce-*/ for the application and <root>/recordings for captures.
    recordings = args.recordings or os.path.join(
        os.path.dirname(os.path.dirname(home.rstrip("/"))), "recordings")
    return db, recordings


def connect(db: str, readonly: bool):
    import sqlite3

    if not os.path.exists(db):
        raise SystemExit(f"database not found: {db}")
    mode = "ro" if readonly else "rw"
    return sqlite3.connect(f"file:{db}?mode={mode}", uri=True, timeout=30)


def service_state(service: str) -> dict:
    """Return systemd state for the VCE service, or empty values when absent."""
    state: dict[str, object] = {"active": "unknown"}
    if not shutil.which("systemctl"):
        return state
    try:
        out = subprocess.run(["systemctl", "show", service, "-p", "ActiveState", "-p", "SubState",
                              "-p", "ActiveEnterTimestampMonotonic"],
                             capture_output=True, text=True, timeout=10).stdout
    except Exception:
        return state
    for line in out.splitlines():
        key, _, value = line.partition("=")
        if key == "ActiveState":
            state["active"] = value or "unknown"
        elif key == "SubState":
            state["substate"] = value
        elif key == "ActiveEnterTimestampMonotonic":
            try:
                micros = int(value)
                if micros:
                    state["up_seconds"] = time.clock_gettime(time.CLOCK_BOOTTIME) - micros / 1_000_000
            except (ValueError, OSError):
                pass
    return state


def find_jvm_pid() -> int | None:
    """Find the VCE JVM process id."""
    try:
        out = subprocess.run(["pgrep", "-f", "io.github.dsheirer.gui.SDRTrunk"],
                             capture_output=True, text=True, timeout=10).stdout.split()
    except Exception:
        return None
    return int(out[0]) if out else None


def jvm_facts(pid: int) -> dict:
    """Read heap flags and live usage from /proc."""
    facts = {}
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as handle:
            args = handle.read().decode("utf-8", "replace").split("\0")
        for arg in args:
            if arg.startswith("-Xmx"):
                facts["max_heap"] = arg[4:]          # later flags win in HotSpot
            elif arg.startswith("-Xms"):
                facts["initial_heap"] = arg[4:]
        with open(f"/proc/{pid}/status") as handle:
            for line in handle:
                if line.startswith("VmRSS:"):
                    facts["rss_kb"] = int(line.split()[1])
    except OSError:
        pass
    return facts


def human_bytes(value: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024 or unit == "TB":
            return f"{value:.1f}{unit}" if unit != "B" else f"{int(value)}B"
        value /= 1024
    return f"{value:.1f}TB"


def parse_since(text: str) -> float:
    """Parse a 'since' window such as 90m, 12h, 7d into a unix timestamp."""
    match = re.fullmatch(r"(\d+)([mhd])", text.strip())
    if not match:
        raise SystemExit(f"invalid duration {text!r}; use e.g. 30m, 12h, 7d")
    count, unit = int(match.group(1)), match.group(2)
    seconds = {"m": 60, "h": 3600, "d": 86400}[unit] * count
    return time.time() - seconds


def cmd_status(args) -> int:
    db, recordings = resolve_paths(args)
    state = service_state(args.service)
    pid = find_jvm_pid()

    print(f"service     {args.service}: {state.get('active')} ({state.get('substate', 'n/a')})")
    if "up_seconds" in state:
        print(f"uptime      {timedelta(seconds=int(state['up_seconds']))}")

    if pid:
        facts = jvm_facts(pid)
        print(f"jvm pid     {pid}")
        print(f"heap        max={facts.get('max_heap', '?')} initial={facts.get('initial_heap', '?')} "
              f"resident={human_bytes(facts.get('rss_kb', 0) * 1024)}")
    else:
        print("jvm pid     not running")

    con = connect(db, readonly=True)
    chan = con.execute("select count(*), sum(auto_start) from configuration_channel").fetchone()
    aliases = con.execute("select count(*), sum(record_enabled) from alias where matcher_type='TALKGROUP'").fetchone()
    print(f"channels    {chan[0]} configured, {chan[1] or 0} auto-start")
    print(f"talkgroups  {aliases[0]} aliases, {aliases[1] or 0} recording")
    con.close()

    cutoff = time.time() - 3600
    total = sum(1 for f in glob.glob(os.path.join(recordings, "**", "*.mbe"), recursive=True)
                if os.path.getmtime(f) >= cutoff)
    print(f"recording   {total} calls in the last hour")

    failures = 0
    if shutil.which("journalctl"):
        out = subprocess.run(["journalctl", "-u", args.service, "--since", "-1h", "--no-pager"],
                             capture_output=True, text=True, timeout=30).stdout
        failures = out.count("No Tuner Available")
    print(f"tuners      {failures} 'No Tuner Available' events in the last hour")
    return 0


def cmd_channels(args) -> int:
    db, _ = resolve_paths(args)
    con = connect(db, readonly=not args.apply)
    rows = con.execute("select id, name, auto_start from configuration_channel order by name").fetchall()

    if args.autostart_only:
        wanted = set(args.autostart_only)
        missing = wanted - {name for _, name, _ in rows}
        if missing:
            print("unknown channel(s): " + ", ".join(sorted(missing)), file=sys.stderr)
            return 2
        if not args.apply:
            print("would set auto_start=1 for: " + ", ".join(sorted(wanted)))
            print("and auto_start=0 for the other %d channels" % (len(rows) - len(wanted)))
            print("re-run with --apply to make the change")
            con.close()
            return 0
        con.execute("update configuration_channel set auto_start = 0")
        con.executemany("update configuration_channel set auto_start = 1 where name = ?",
                        [(name,) for name in wanted])
        con.commit()
        con.close()
        print(f"auto-start set for {len(wanted)} channel(s); restart {args.service} to apply")
        return 0

    for _, name, auto in rows:
        if args.autostart and not auto:
            continue
        print(f"  {'*' if auto else ' '} {name}")
    print(f"\n{sum(1 for _, _, a in rows if a)} of {len(rows)} channels auto-start")
    con.close()
    return 0


def cmd_recording(args) -> int:
    db, _ = resolve_paths(args)
    if not args.groups and not args.talkgroups:
        con = connect(db, readonly=True)
        for group, total, on in con.execute(
                "select group_name, count(*), sum(record_enabled) from alias "
                "where matcher_type='TALKGROUP' group by group_name "
                "having sum(record_enabled) > 0 order by sum(record_enabled) desc"):
            print(f"  {on:5} / {total:<5} {group}")
        grand = con.execute("select sum(record_enabled) from alias where matcher_type='TALKGROUP'").fetchone()[0]
        print(f"\n{grand or 0} talkgroup aliases recording")
        con.close()
        return 0

    con = connect(db, readonly=not args.apply)
    changes = []
    if args.groups:
        for group in args.groups:
            on = 1 if args.mode == "on" else 0
            changes.append(("group", group, on, con.execute(
                "select count(*) from alias where matcher_type='TALKGROUP' and group_name = ?", (group,)).fetchone()[0]))
    if args.talkgroups:
        for value in args.talkgroups:
            on = 1 if args.mode == "on" else 0
            changes.append(("talkgroup", value, on, con.execute(
                "select count(*) from alias where matcher_type='TALKGROUP' and value = ?", (str(value),)).fetchone()[0]))

    for kind, target, on, count in changes:
        print(f"  {kind:9} {target:45} -> record {'on' if on else 'off'}  ({count} aliases)")
    if any(count == 0 for *_, count in changes):
        print("warning: at least one target matched no aliases", file=sys.stderr)

    if not args.apply:
        print("\ndry run; re-run with --apply to make the change")
        con.close()
        return 0

    for kind, target, on, _ in changes:
        if kind == "group":
            con.execute("update alias set record_enabled=? where matcher_type='TALKGROUP' and group_name=?",
                        (on, target))
        else:
            con.execute("update alias set record_enabled=? where matcher_type='TALKGROUP' and value=?",
                        (on, str(target)))
    con.commit()
    con.close()
    print(f"\napplied; restart {args.service} so the running instance reloads aliases")
    return 0


def iter_calls(recordings: str, since: float, site: str | None = None):
    """Yield parsed recording metadata for calls newer than 'since'."""
    for path in glob.glob(os.path.join(recordings, "**", "*.mbe"), recursive=True):
        try:
            if os.path.getmtime(path) < since:
                continue
            with open(path, "rb") as handle:
                data = json.loads(handle.read().decode("utf-8", "replace"))
        except (OSError, ValueError):
            continue
        if site and data.get("site") != site:
            continue
        yield path, data


def cmd_keys(args) -> int:
    _, recordings = resolve_paths(args)
    since = parse_since(args.since)
    known = set()
    props = os.path.expanduser("~/.vce-adp-keys.properties")
    if os.path.exists(props):
        with open(props) as handle:
            for line in handle:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    known.add(int(line.split("=", 1)[0], 16))

    groups = collections.defaultdict(lambda: {"calls": 0, "frames": 0, "tgs": collections.Counter()})
    for _, data in iter_calls(recordings, since, args.site):
        frames = data.get("frames") or []
        if not frames:
            continue
        keyed = [frame for frame in frames if frame.get("encryption_key_id")]
        if not keyed:
            continue
        counter = collections.Counter(
            (frame.get("encryption_algorithm"), frame.get("encryption_key_id")) for frame in keyed)
        (alg, key_id), hits = counter.most_common(1)[0]
        # A call mixes unencrypted link/control frames with the encrypted voice frames, so compare
        # against the frames that actually carry an encryption header, and ignore isolated hits.
        if hits < 2 or hits < len(keyed) * 0.8:
            continue
        entry = groups[(alg, key_id)]
        entry["calls"] += 1
        entry["frames"] += len(frames)
        entry["tgs"][str(data.get("to"))] += 1

    if not groups:
        print("no encrypted calls in that window")
        return 0

    print(f"{'algorithm':12} {'key id':8} {'key':7} {'calls':>7} {'frames':>9}  talkgroups")
    for (alg, key_id), entry in sorted(groups.items(), key=lambda item: -item[1]["calls"]):
        name = ALG_NAMES.get(alg, f"alg=0x{alg:02X}" if isinstance(alg, int) else str(alg))
        have = "known" if key_id in known else "missing"
        tgs = ", ".join(f"{tg}({n})" for tg, n in entry["tgs"].most_common(3))
        print(f"{name:12} 0x{key_id:04X}  {have:7} {entry['calls']:7} {entry['frames']:9}  {tgs}")
    return 0


def cmd_calls(args) -> int:
    _, recordings = resolve_paths(args)
    since = parse_since(args.since)
    counts = collections.Counter()
    encrypted = collections.Counter()
    for _, data in iter_calls(recordings, since, args.site):
        tg = str(data.get("to")) if data.get("to") is not None else "(unit-to-unit)"
        counts[tg] += 1
        if data.get("encrypted"):
            encrypted[tg] += 1
    if not counts:
        print("no calls in that window")
        return 0
    print(f"{'talkgroup':12} {'calls':>7} {'encrypted':>10}")
    for tg, count in counts.most_common(args.limit):
        if args.encrypted and not encrypted[tg]:
            continue
        print(f"{tg:12} {count:7} {encrypted[tg]:10}")
    print(f"\n{sum(counts.values())} calls across {len(counts)} talkgroups in {args.since}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="vce-admin", description="Operator CLI for SDRTrunk VCE.")
    parser.add_argument("--home", help="VCE application root (default: $VCE_HOME or auto-detect)")
    parser.add_argument("--db", help="path to sdrtrunk.sqlite")
    parser.add_argument("--recordings", help="path to the recordings directory")
    parser.add_argument("--service", default="sdrtrunk-vce", help="systemd unit name (default: sdrtrunk-vce)")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("status", help="service, heap, channel and tuner summary").set_defaults(func=cmd_status)

    channels = sub.add_parser("channels", help="list channels and manage auto-start")
    channels.add_argument("--autostart", action="store_true", help="show only auto-start channels")
    channels.add_argument("--autostart-only", nargs="+", metavar="NAME",
                          help="make these the only channels that auto-start")
    channels.add_argument("--apply", action="store_true", help="write changes")
    channels.set_defaults(func=cmd_channels)

    recording = sub.add_parser("recording", help="inspect and change talkgroup recording policy")
    recording.add_argument("--show", action="store_true", help="group summary (default when no targets)")
    recording.add_argument("--groups", nargs="+", metavar="GROUP", help="alias group names")
    recording.add_argument("--talkgroups", nargs="+", metavar="TG", help="talkgroup alias values")
    recording.add_argument("--mode", choices=("on", "off"), default="on", help="record state to set")
    recording.add_argument("--explain", action="store_true", help="same as a dry run")
    recording.add_argument("--apply", action="store_true", help="write changes")
    recording.set_defaults(func=cmd_recording)

    keys = sub.add_parser("keys", help="report encryption keys seen in recordings")
    keys.add_argument("--since", default="24h", help="window, e.g. 90m, 24h, 7d (default 24h)")
    keys.add_argument("--site", help="restrict to one site name, e.g. 'Site 04-04'")
    keys.set_defaults(func=cmd_keys)

    calls = sub.add_parser("calls", help="talkgroup activity summary")
    calls.add_argument("--since", default="1h", help="window, e.g. 30m, 6h (default 1h)")
    calls.add_argument("--site", help="restrict to one site name")
    calls.add_argument("--encrypted", action="store_true", help="only talkgroups with encrypted calls")
    calls.add_argument("--limit", type=int, default=25, help="rows to show (default 25)")
    calls.set_defaults(func=cmd_calls)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
