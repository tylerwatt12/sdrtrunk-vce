#!/usr/bin/env python3
"""
Live analyzer for the temporary Now Playing activity debug feed.

The feed is NDJSON from /now-playing-debug.  This script tracks each row key and
separates control-channel grant events, voice/detail refreshes, age-outs, and
Swing table updates so we can tell what is actually keeping a row in CALL.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from dataclasses import dataclass, field
from typing import Any


DEFAULT_URL = "http://100.84.57.60:17989/now-playing-debug"
CALL_STATES = {"ACTIVE", "CALL", "DATA", "ENCRYPTED"}
GRANT_ORIGINS = {"p25-traffic-grant"}
DETAIL_ORIGINS = {"p25-traffic-details"}
IDLE_ORIGINS = {"p25-traffic-idle", "p25-traffic-ageout"}
TABLE_PREFIX = "table-row"


def event_time_ms(event: dict[str, Any]) -> int:
    value = event.get("time")

    if not value:
        return int(time.time() * 1000)

    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        return int(parsed.timestamp() * 1000)
    except ValueError:
        return int(time.time() * 1000)


def age_ms(now_ms: int, then_ms: int | None) -> str:
    if then_ms is None:
        return "never"

    return f"{max(0, now_ms - then_ms)}ms"


def frequency_label(frequency: Any) -> str:
    if not isinstance(frequency, int) or frequency <= 0:
        return "unknown"

    return f"{frequency / 1_000_000:.6f}"


def compact(value: Any) -> str:
    if value is None:
        return ""

    return str(value).replace("\n", " ").strip()


def snapshot_from(event: dict[str, Any]) -> dict[str, Any] | None:
    if event.get("type") == "row":
        snapshot = event.get("after")
    elif event.get("type") == "table":
        snapshot = event.get("row")
    else:
        snapshot = None

    return snapshot if isinstance(snapshot, dict) else None


def before_snapshot_from(event: dict[str, Any]) -> dict[str, Any] | None:
    snapshot = event.get("before")
    return snapshot if isinstance(snapshot, dict) else None


def row_key(snapshot: dict[str, Any] | None) -> str | None:
    if not snapshot:
        return None

    key = snapshot.get("key")
    return str(key) if key else None


@dataclass
class RowTrack:
    key: str
    table: str = ""
    visible: bool = False
    state: str = ""
    table_state: str = ""
    frequency: int | None = None
    lcn: str = ""
    source: str = ""
    target: str = ""
    source_aliases: str = ""
    target_aliases: str = ""
    decoder: str = ""
    channel: str = ""
    first_ms: int | None = None
    last_ms: int | None = None
    last_grant_ms: int | None = None
    last_detail_ms: int | None = None
    last_idle_ms: int | None = None
    last_table_ms: int | None = None
    last_call_state_ms: int | None = None
    last_alert_ms: int | None = None
    last_state_report: str = ""
    model_events_seen: int = 0
    table_events_seen: int = 0
    counts: Counter[str] = field(default_factory=Counter)

    def update_from_snapshot(self, snapshot: dict[str, Any], event: dict[str, Any], now_ms: int, table_event: bool) -> None:
        self.table = compact(event.get("table")) or self.table
        self.visible = bool(event.get("tableVisible")) if "tableVisible" in event else self.visible
        self.frequency = snapshot.get("frequency") if isinstance(snapshot.get("frequency"), int) else self.frequency
        self.lcn = compact(snapshot.get("lcn")) or self.lcn
        self.source = compact(snapshot.get("source"))
        self.target = compact(snapshot.get("target"))
        self.source_aliases = compact(snapshot.get("sourceAliases"))
        self.target_aliases = compact(snapshot.get("targetAliases"))
        self.decoder = compact(snapshot.get("decoder")) or self.decoder

        channel = snapshot.get("channel")

        if isinstance(channel, dict):
            name = compact(channel.get("name"))
            channel_id = compact(channel.get("id"))
            self.channel = f"{channel_id}:{name}" if name else channel_id

        state = compact(snapshot.get("state"))

        if state:
            if table_event:
                self.table_state = state
            else:
                self.state = state

            if state in CALL_STATES:
                self.last_call_state_ms = now_ms

    def description(self) -> str:
        alias = self.target_aliases or self.target or "unknown-target"
        source = self.source_aliases or self.source or "unknown-source"
        return (
            f"{self.key} {frequency_label(self.frequency)}MHz lcn={self.lcn or '?'} "
            f"state={self.state or '?'} tableState={self.table_state or '?'} "
            f"src={source} tgt={alias} table={self.table or '?'}"
        )


class Analyzer:
    def __init__(
        self,
        stale_grant_ms: int,
        stale_detail_ms: int,
        alert_repeat_ms: int,
        summary_ms: int,
        visible_only: bool,
        json_output: bool,
        print_events: bool,
    ):
        self.stale_grant_ms = stale_grant_ms
        self.stale_detail_ms = stale_detail_ms
        self.alert_repeat_ms = alert_repeat_ms
        self.summary_ms = summary_ms
        self.visible_only = visible_only
        self.json_output = json_output
        self.print_events = print_events
        self.rows: dict[str, RowTrack] = {}
        self.last_summary_ms: int | None = None
        self.event_counts: Counter[str] = Counter()
        self.seq: int | None = None

    def emit(self, kind: str, message: str, **fields: Any) -> None:
        if self.json_output:
            payload = {"kind": kind, "message": message, **fields}
            print(json.dumps(payload, separators=(",", ":")), flush=True)
        else:
            details = " ".join(f"{name}={value}" for name, value in fields.items() if value not in (None, ""))
            suffix = f" {details}" if details else ""
            print(f"{kind}: {message}{suffix}", flush=True)

    def process(self, event: dict[str, Any]) -> None:
        now_ms = event_time_ms(event)
        origin = compact(event.get("origin"))
        event_type = compact(event.get("type"))

        if event_type == "status":
            self.emit(
                "STATUS",
                "feed status",
                clients=event.get("clients"),
                queued=event.get("queuedEvents"),
                dropped=event.get("droppedEvents"),
                file=event.get("file"),
            )
            return

        self.check_seq(event)
        self.event_counts[origin or event_type] += 1

        snapshot = snapshot_from(event)
        key = row_key(snapshot)

        if not key:
            if event_type == "miss":
                self.emit(
                    "MISS",
                    origin or "miss",
                    table=event.get("table"),
                    frequency=frequency_label(event.get("frequency")),
                    timeslot=event.get("timeslot"),
                    note=event.get("note"),
                )
            return

        row = self.rows.setdefault(key, RowTrack(key=key))
        row.first_ms = row.first_ms or now_ms
        row.last_ms = now_ms
        row.counts[origin or event_type] += 1

        table_event = event_type == "table"
        if table_event:
            row.table_events_seen += 1
        else:
            row.model_events_seen += 1

        before = before_snapshot_from(event)
        before_state = compact(before.get("state")) if before else ""
        row.update_from_snapshot(snapshot, event, now_ms, table_event)

        if self.visible_only and not row.visible:
            return

        if origin in GRANT_ORIGINS:
            row.last_grant_ms = now_ms
        elif origin in DETAIL_ORIGINS:
            row.last_detail_ms = now_ms
        elif origin in IDLE_ORIGINS:
            row.last_idle_ms = now_ms
        elif origin.startswith(TABLE_PREFIX):
            row.last_table_ms = now_ms

        after_state = compact(snapshot.get("state"))

        if self.print_events or (after_state and before_state and after_state != before_state):
            self.emit(
                "EVENT",
                origin or event_type,
                key=key,
                before=before_state,
                after=after_state,
                grantAge=age_ms(now_ms, row.last_grant_ms),
                detailAge=age_ms(now_ms, row.last_detail_ms),
                table=row.table,
                note=compact(event.get("note")),
            )

        self.check_row(row, origin, now_ms)
        self.maybe_summary(now_ms)

    def check_seq(self, event: dict[str, Any]) -> None:
        seq = event.get("seq")

        if not isinstance(seq, int):
            return

        if self.seq is not None and seq > self.seq + 1:
            self.emit("GAP", "debug feed sequence skipped", previous=self.seq, current=seq, missed=seq - self.seq - 1)

        self.seq = seq

    def check_row(self, row: RowTrack, origin: str, now_ms: int) -> None:
        state = row.state or row.table_state

        if state not in CALL_STATES:
            return

        grant_stale = row.last_grant_ms is None or now_ms - row.last_grant_ms > self.stale_grant_ms
        detail_fresh = row.last_detail_ms is not None and now_ms - row.last_detail_ms <= self.stale_detail_ms
        table_fresh = row.last_table_ms is not None and now_ms - row.last_table_ms <= self.stale_detail_ms

        if not grant_stale:
            return

        if row.last_alert_ms is not None and now_ms - row.last_alert_ms < self.alert_repeat_ms:
            return

        if row.model_events_seen == 0:
            if self.print_events:
                self.emit(
                    "TABLE_ONLY_CALL",
                    "table row is CALL, but analyzer has not seen this row's model history yet",
                    row=row.description(),
                    origin=origin,
                )
            return

        row.last_alert_ms = now_ms

        if origin in DETAIL_ORIGINS or detail_fresh:
            reason = "CALL is being refreshed by traffic/details, not fresh grants"
        elif origin.startswith(TABLE_PREFIX) or table_fresh:
            reason = "table model is refreshing CALL without a fresh grant"
        else:
            reason = "row remains CALL without a fresh grant or detail refresh"

        self.emit(
            "STALE_CALL",
            reason,
            row=row.description(),
            lastGrant=age_ms(now_ms, row.last_grant_ms),
            lastDetail=age_ms(now_ms, row.last_detail_ms),
            lastIdle=age_ms(now_ms, row.last_idle_ms),
            origin=origin,
        )

    def maybe_summary(self, now_ms: int) -> None:
        if self.summary_ms <= 0:
            return

        if self.last_summary_ms is not None and now_ms - self.last_summary_ms < self.summary_ms:
            return

        self.last_summary_ms = now_ms
        active = [row for row in self.rows.values() if (row.state or row.table_state) in CALL_STATES]
        confirmed_active = [row for row in active if row.model_events_seen > 0]
        stale = [
            row for row in confirmed_active
            if row.last_grant_ms is None or now_ms - row.last_grant_ms > self.stale_grant_ms
        ]
        by_table = Counter(row.table for row in active)
        top_tables = ",".join(f"{table or '?'}:{count}" for table, count in by_table.most_common(5))
        top_events = ",".join(f"{name}:{count}" for name, count in self.event_counts.most_common(8))

        self.emit(
            "SUMMARY",
            "row activity",
            tracked=len(self.rows),
            active=len(active),
            confirmedActive=len(confirmed_active),
            staleWithoutGrant=len(stale),
            topTables=top_tables,
            topEvents=top_events,
        )


def stream_events(url: str, timeout: float):
    request = urllib.request.Request(url, headers={"Accept": "application/x-ndjson"})

    with urllib.request.urlopen(request, timeout=timeout) as response:
        for raw in response:
            line = raw.decode("utf-8", errors="replace").strip()

            if not line:
                continue

            try:
                yield json.loads(line)
            except json.JSONDecodeError as error:
                print(f"WARN: invalid JSON line: {error}: {line[:200]}", file=sys.stderr, flush=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Analyze the live Now Playing debug feed.")
    parser.add_argument("--url", default=DEFAULT_URL, help=f"debug feed URL, default: {DEFAULT_URL}")
    parser.add_argument("--duration", type=float, default=0,
                        help="seconds to run; 0 means run until interrupted")
    parser.add_argument("--stale-grant-ms", type=int, default=2500,
                        help="CALL rows older than this since last p25-traffic-grant are suspicious")
    parser.add_argument("--stale-detail-ms", type=int, default=2500,
                        help="recent detail/table refresh window used for attribution")
    parser.add_argument("--alert-repeat-ms", type=int, default=5000,
                        help="minimum time before repeating the same row alert")
    parser.add_argument("--summary-ms", type=int, default=5000,
                        help="summary interval; 0 disables summaries")
    parser.add_argument("--visible-only", action="store_true",
                        help="ignore tables not visible in the UI")
    parser.add_argument("--json", action="store_true", help="emit analyzer findings as JSON")
    parser.add_argument("--print-events", action="store_true", help="print every row/table event")
    parser.add_argument("--timeout", type=float, default=20, help="HTTP connection timeout in seconds")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    analyzer = Analyzer(
        stale_grant_ms=max(0, args.stale_grant_ms),
        stale_detail_ms=max(0, args.stale_detail_ms),
        alert_repeat_ms=max(0, args.alert_repeat_ms),
        summary_ms=max(0, args.summary_ms),
        visible_only=args.visible_only,
        json_output=args.json,
        print_events=args.print_events,
    )
    started = time.monotonic()

    analyzer.emit("START", "connected analyzer", url=args.url, duration=args.duration or "until-interrupted")

    try:
        for event in stream_events(args.url, args.timeout):
            analyzer.process(event)

            if args.duration > 0 and time.monotonic() - started >= args.duration:
                break
    except KeyboardInterrupt:
        analyzer.emit("STOP", "interrupted")
    except urllib.error.URLError as error:
        analyzer.emit("ERROR", "debug feed connection failed", error=error)
        return 2

    analyzer.emit("STOP", "finished", tracked=len(analyzer.rows))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
