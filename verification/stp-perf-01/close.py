#!/usr/bin/env python3
"""Derive close-out arithmetic from retained measurements; execute no benchmarks."""
import json
from pathlib import Path

here = Path(__file__).resolve().parent
old = json.loads((here / 'raw/before/summary.json').read_text())
new = json.loads((here / 'raw/after/summary.json').read_text())
before = [r for r in old if r['kind'] == 'contention']
after = [r for r in new if r['kind'] == 'contention']
lines = ['| Workers | No agent, before (s) | Agent, before (s) | No agent, after (s) | Agent, after (s) | Efficiency before | Efficiency after |',
         '|---:|---:|---:|---:|---:|---:|---:|']
rows = []
for a, b in zip(before, after):
    n = a['threads']
    assert n == b['threads'] and a['calls'] == b['calls'] == 10_000_000
    efficiency_a = before[0]['agent']['medianSeconds'] / (n * a['agent']['medianSeconds'])
    efficiency_b = after[0]['agent']['medianSeconds'] / (n * b['agent']['medianSeconds'])
    values = [a['control']['medianSeconds'], a['agent']['medianSeconds'],
              b['control']['medianSeconds'], b['agent']['medianSeconds']]
    lines.append('| ' + ' | '.join([str(n)] + [f'{v:.9f}' for v in values]
                 + [f'{efficiency_a:.2%}', f'{efficiency_b:.2%}']) + ' |')
    rows.append(dict(workers=n, seconds=values, efficiencyBefore=efficiency_a, efficiencyAfter=efficiency_b))
hot_before = next(r for r in old if r['kind'] == 'hot' and r['calls'] == 100_000_000)
hot_after = next(r for r in new if r['kind'] == 'hot' and r['calls'] == 100_000_000)
residual = {phase: (row['agent']['medianSeconds'] - row['control']['medianSeconds']) * 1e9 / row['calls']
            for phase, row in [('before', hot_before), ('after', hot_after)]}
def duration(seconds):
    seconds = round(seconds)
    return f'{seconds // 3600}h {(seconds % 3600) // 60:02}m {seconds % 60:02}s'
ratio = 18 / 138
predictions = []
lines += ['', 'Conditional predictions, not measurements:', '',
          '| Assumed original slowdown N | Predicted slowdown | 7h 13m shard | 6h 19m class |',
          '|---:|---:|---:|---:|']
for n in (2, 3, 4, 20):
    factor = 1 + (n - 1) * ratio
    shard = 25980 * factor / n
    test_class = 22740 * factor / n
    predictions.append(dict(assumedN=n, predictedSlowdown=factor, shardSeconds=shard, classSeconds=test_class))
    lines.append(f'| {n}x | {factor:.6f}x | {duration(shard)} | {duration(test_class)} |')
out = here / 'raw/close'
out.mkdir(exist_ok=True)
(out / 'arithmetic.json').write_text(json.dumps(dict(contention=rows, residualNs=residual,
                                                  predictions=predictions), indent=2) + '\n')
(out / 'tables.md').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))
print('Measured residual ns/call:', residual)
