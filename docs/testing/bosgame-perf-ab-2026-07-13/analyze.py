#!/usr/bin/env python3
import csv
import itertools
import json
import math
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

import numpy as np
from scipy import stats

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('/tmp/bosgame-perf-publish')
OUTPUT_DIRECTORY = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('/tmp')
OUTPUT_DIRECTORY.mkdir(parents=True, exist_ok=True)
LOGICAL_PROCESSORS = 16


def percentile(values, p):
    return float(np.percentile(np.asarray(values, dtype=float), p))


def slope(x, y):
    return float(np.polyfit(np.asarray(x, dtype=float), np.asarray(y, dtype=float), 1)[0])


def parse_iso(value):
    value = value.replace('Z', '+00:00')
    value = re.sub(r'(\.\d{6})\d+(?=[+-]\d\d:\d\d$)', r'\1', value)
    return datetime.fromisoformat(value)


def parse_gc(path, uptime_start, uptime_end):
    pause_re = re.compile(r'\[(\d+(?:\.\d+)?)s\].*\[gc\s*\].*Pause (?:Young|Full).*? (\d+(?:\.\d+)?)ms$')
    safepoint_re = re.compile(r'\[(\d+(?:\.\d+)?)s\].*\[safepoint\s*\].*Total: (\d+) ns')
    pauses = []
    safepoints_ms = []
    with path.open(encoding='utf-8', errors='replace') as handle:
        for line in handle:
            match = pause_re.search(line.rstrip())
            if match:
                uptime, duration = float(match.group(1)), float(match.group(2))
                if uptime_start <= uptime <= uptime_end:
                    pauses.append(duration)
            match = safepoint_re.search(line.rstrip())
            if match:
                uptime, duration_ns = float(match.group(1)), int(match.group(2))
                if uptime_start <= uptime <= uptime_end:
                    safepoints_ms.append(duration_ns / 1_000_000.0)
    return {
        'gc_pause_count': len(pauses),
        'gc_pause_total_ms': sum(pauses),
        'gc_pause_max_ms': max(pauses, default=0.0),
        'safepoint_count': len(safepoints_ms),
        'safepoint_total_ms': sum(safepoints_ms),
        'safepoint_max_ms': max(safepoints_ms, default=0.0),
    }


def read_run(directory):
    metadata = json.loads((directory / 'run.json').read_text(encoding='utf-8-sig'))
    with (directory / 'samples.csv').open(encoding='utf-8-sig', newline='') as handle:
        rows = list(csv.DictReader(handle))
    values = {key: [float(row[key]) for row in rows] for key in (
        'ElapsedSeconds', 'ProcessCpuSeconds', 'WorkingSetBytes', 'PrivateBytes', 'PagedBytes',
        'VirtualBytes', 'Threads', 'Handles', 'ReadTransferBytes', 'WriteTransferBytes',
        'HostCpuPercent', 'HostAvailableMBytes')}
    elapsed = values['ElapsedSeconds']
    duration = elapsed[-1] - elapsed[0]
    measurement_start = parse_iso(rows[0]['TimestampUtc'])
    process_start = parse_iso(metadata['processStartedAt'])
    uptime_start = (measurement_start - process_start).total_seconds()
    uptime_end = uptime_start + duration
    result = {
        'run': metadata['run'],
        'run_number': int(metadata['run'].split('-')[0]),
        'build': metadata['build'],
        'block': (int(metadata['run'].split('-')[0]) - 1) // 4 + 1,
        'samples': len(rows),
        'duration_seconds': duration,
        'cpu_core_percent': slope(elapsed, values['ProcessCpuSeconds']) * 100.0,
        'cpu_host_percent': slope(elapsed, values['ProcessCpuSeconds']) * 100.0 / LOGICAL_PROCESSORS,
        'working_set_mean_mib': statistics.mean(values['WorkingSetBytes']) / 2**20,
        'working_set_p95_mib': percentile(values['WorkingSetBytes'], 95) / 2**20,
        'working_set_peak_mib': max(values['WorkingSetBytes']) / 2**20,
        'working_set_end_mib': values['WorkingSetBytes'][-1] / 2**20,
        'working_set_slope_mib_min': slope(elapsed, values['WorkingSetBytes']) * 60 / 2**20,
        'private_mean_mib': statistics.mean(values['PrivateBytes']) / 2**20,
        'private_p95_mib': percentile(values['PrivateBytes'], 95) / 2**20,
        'private_peak_mib': max(values['PrivateBytes']) / 2**20,
        'private_end_mib': values['PrivateBytes'][-1] / 2**20,
        'private_slope_mib_min': slope(elapsed, values['PrivateBytes']) * 60 / 2**20,
        'threads_mean': statistics.mean(values['Threads']),
        'threads_peak': max(values['Threads']),
        'handles_mean': statistics.mean(values['Handles']),
        'handles_peak': max(values['Handles']),
        'read_mib_sec': (values['ReadTransferBytes'][-1] - values['ReadTransferBytes'][0]) / duration / 2**20,
        'write_kib_sec': (values['WriteTransferBytes'][-1] - values['WriteTransferBytes'][0]) / duration / 2**10,
        'host_cpu_mean_percent': statistics.mean(values['HostCpuPercent']),
        'host_available_mean_mib': statistics.mean(values['HostAvailableMBytes']),
        'sqlite_unchanged': metadata['sqliteUnchanged'],
        'listener_count': len(metadata['listenersAfter']),
    }
    result.update(parse_gc(directory / 'gc.log', uptime_start, uptime_end))
    return result


def block_model(runs, metric):
    y = np.asarray([run[metric] for run in runs], dtype=float)
    # A is reference. Coefficient is B - A, controlling for crossover block.
    x = np.asarray([[1.0, 1.0 if run['build'] == 'B' else 0.0,
                     1.0 if run['block'] == 2 else 0.0,
                     1.0 if run['block'] == 3 else 0.0] for run in runs])
    inverse = np.linalg.inv(x.T @ x)
    beta = inverse @ x.T @ y
    residuals = y - x @ beta
    df = len(y) - x.shape[1]
    variance = float(residuals @ residuals / df)
    standard_error = math.sqrt(variance * inverse[1, 1])
    critical = stats.t.ppf(0.975, df)
    coefficient = float(beta[1])
    t_value = coefficient / standard_error if standard_error else math.inf
    p_value = float(2 * stats.t.sf(abs(t_value), df))

    observed = statistics.mean(run[metric] for run in runs if run['build'] == 'B') - \
        statistics.mean(run[metric] for run in runs if run['build'] == 'A')
    permuted = []
    blocks = [[run for run in runs if run['block'] == block] for block in (1, 2, 3)]
    choices = [list(itertools.combinations(range(4), 2)) for _ in blocks]
    for assignment in itertools.product(*choices):
        a_values, b_values = [], []
        for block_runs, a_indices in zip(blocks, assignment):
            a_indices = set(a_indices)
            for index, run in enumerate(block_runs):
                (a_values if index in a_indices else b_values).append(run[metric])
        permuted.append(statistics.mean(b_values) - statistics.mean(a_values))
    exact_p = sum(abs(value) >= abs(observed) - 1e-12 for value in permuted) / len(permuted)
    return {
        'effect_b_minus_a': coefficient,
        'ci95_low': coefficient - critical * standard_error,
        'ci95_high': coefficient + critical * standard_error,
        'model_p': p_value,
        'exact_block_permutation_p': exact_p,
        'df': df,
    }


runs = [read_run(path) for path in sorted(ROOT.iterdir()) if path.is_dir()]
metrics = [
    'cpu_core_percent', 'working_set_mean_mib', 'working_set_p95_mib',
    'private_mean_mib', 'private_p95_mib', 'threads_mean', 'handles_mean',
    'write_kib_sec', 'gc_pause_count', 'gc_pause_total_ms', 'gc_pause_max_ms',
    'safepoint_total_ms', 'host_cpu_mean_percent', 'working_set_slope_mib_min',
    'private_slope_mib_min'
]

summary = []
for metric in metrics:
    a = [run[metric] for run in runs if run['build'] == 'A']
    b = [run[metric] for run in runs if run['build'] == 'B']
    model = block_model(runs, metric)
    summary.append({
        'metric': metric,
        'vce_mean': statistics.mean(a),
        'vce_sd': statistics.stdev(a),
        'mainline_mean': statistics.mean(b),
        'mainline_sd': statistics.stdev(b),
        'vce_reduction_percent': (statistics.mean(b) - statistics.mean(a)) / statistics.mean(b) * 100.0
            if statistics.mean(b) else float('nan'),
        **model,
    })

with (OUTPUT_DIRECTORY / 'run-summary.csv').open('w', newline='', encoding='utf-8') as handle:
    writer = csv.DictWriter(handle, fieldnames=list(runs[0].keys()))
    writer.writeheader()
    writer.writerows(runs)

with (OUTPUT_DIRECTORY / 'statistics.csv').open('w', newline='', encoding='utf-8') as handle:
    writer = csv.DictWriter(handle, fieldnames=list(summary[0].keys()))
    writer.writeheader()
    writer.writerows(summary)

print('RUNS')
for run in runs:
    print(run['run'], 'cpu', f"{run['cpu_core_percent']:.3f}", 'ws', f"{run['working_set_mean_mib']:.2f}",
          'private', f"{run['private_mean_mib']:.2f}", 'gc', run['gc_pause_count'],
          f"{run['gc_pause_total_ms']:.2f}ms", 'host', f"{run['host_cpu_mean_percent']:.2f}")
print('\nSTATISTICS')
for row in summary:
    print(row['metric'], 'A', f"{row['vce_mean']:.4f}±{row['vce_sd']:.4f}",
          'B', f"{row['mainline_mean']:.4f}±{row['mainline_sd']:.4f}",
          'B-A', f"{row['effect_b_minus_a']:.4f}",
          'CI', f"[{row['ci95_low']:.4f},{row['ci95_high']:.4f}]",
          'p', f"{row['model_p']:.6f}", 'perm', f"{row['exact_block_permutation_p']:.6f}",
          'reduction', f"{row['vce_reduction_percent']:.2f}%")
