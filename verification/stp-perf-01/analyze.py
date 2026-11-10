#!/usr/bin/env python3
"""Summarize the predeclared benchmark matrix without changing its measurements."""
import json
import math
import os
from pathlib import Path
import statistics

raw = Path(__file__).resolve().parent / 'raw' / os.environ.get('BENCH_PHASE', 'before')
samples = json.loads((raw / 'samples.json').read_text())
rows = json.loads((raw / 'summary.json').read_text())
assert len(samples) == 48 and len(rows) == 8
lines = ['| Case | Calls | Threads | Control s (median [min, max]) | Agent s (median [min, max]) | Ratio | Agent ns/call |',
         '|---|---:|---:|---|---|---:|---:|']
for row in rows:
    cells = [row['kind'], str(row['calls']), str(row['threads'])]
    for mode in ['control', 'agent']:
        d = row[mode]
        cells.append(f'{d["medianSeconds"]:.6f} [{d["minSeconds"]:.6f}, {d["maxSeconds"]:.6f}]')
    cells += [f'{row["ratio"]:.2f}x', f'{row["agent"]["medianNsPerCall"]:.2f}']
    lines.append('| ' + ' | '.join(cells) + ' |')
hot = [r for r in rows if r['kind'] == 'hot']
lines += ['', 'Agent tenfold-N growth factors: ' + ', '.join(
    f'{b["agent"]["medianSeconds"]/a["agent"]["medianSeconds"]:.4f}x'
    for a, b in zip(hot, hot[1:]))]
exponent = math.log(hot[-1]['agent']['medianSeconds']/hot[0]['agent']['medianSeconds'])/math.log(100)
lines.append(f'Endpoint scaling exponent over 100x calls: {exponent:.6f} (linear = 1)')
contention = [r for r in rows if r['kind'] == 'contention']
lines += ['', 'Agent contention time relative to one worker: ' + ', '.join(
    f'{r["threads"]} threads = {r["agent"]["medianSeconds"]/contention[0]["agent"]["medianSeconds"]:.4f}x'
    for r in contention)]
lines += ['', '| Case | Calls | Threads | Control process median s | Agent process median s |',
          '|---|---:|---:|---:|---:|']
for row in rows:
    values = [statistics.median(s['processSeconds'] for s in samples
              if (s['kind'],s['calls'],s['threads'],s['mode']) == (row['kind'],row['calls'],row['threads'],mode))
              for mode in ['control', 'agent']]
    lines.append(f'| {row["kind"]} | {row["calls"]} | {row["threads"]} | {values[0]:.6f} | {values[1]:.6f} |')
text = '\n'.join(lines) + '\n'
(raw / 'tables.md').write_text(text)
print(text)
