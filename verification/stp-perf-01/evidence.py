#!/usr/bin/env python3
"""Assert retained correctness gates and summarize before/after local measurements."""
import hashlib
import json
from pathlib import Path

here = Path(__file__).resolve().parent
raw = here / 'raw/implementation'
expected = '0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9'
result = {}
for phase in ('before', 'after'):
    folder = raw / f'petclinic-{phase}'
    fragment = folder / 'stp/coverage-fragment-v3.json'
    assert hashlib.sha256(fragment.read_bytes()).hexdigest() == expected
    assert hashlib.sha256((folder / 'repeat/coverage-fragment-v3.json').read_bytes()).hexdigest() == expected
    data = json.loads((folder / 'stp/asm-agent-diagnostic.json').read_text())
    leaf = sum(t['metrics']['rawMethodHits'] for t in data['runtimeEvents']['tests'])
    result[f'petclinic-{phase}'] = dict(fragmentSha256=expected, rawMethodHits=data['metrics']['rawMethodHits'],
                                      leafRawMethodHits=leaf, agentErrors=data['agentErrors'])
assert result['petclinic-before'] == result['petclinic-after']
for case, subdir, diagnostic in (
        ('asm12', 'module', 'work captured by closed JUnit container'),
        ('asm13', 'stp', 'ForkJoinTask: execution without observed submission')):
    folder = raw / case
    validation = json.loads((folder / 'validation.json').read_text())
    assert validation['completed'] is False
    assert validation['join'] == 'IllegalArgumentException: Fragment collection is not completed'
    assert validation['assignmentValidation'] == validation['join']
    codes = dict(line.split('=') for line in (folder / 'exit-codes.txt').read_text().splitlines())
    assert codes['mapping'] != '0' and codes['aggregation'] != '0'
    data = json.loads((folder / subdir / 'asm-agent-diagnostic.json').read_text())
    errors = [error for error in data['agentErrors'] if diagnostic in error]
    assert errors and all('ASYNC_SETUP_UNSUPPORTED:ERROR' in error for error in errors)
    reasons = {name: 0 for name in ('NO_ACTIVE_TEST', 'LATE_EVENT', 'UNKNOWN_CONTEXT', 'TEST_ALREADY_FINISHED')}
    events = data['runtimeEvents']['unattributedEvents'] + [
        event for test in data['runtimeEvents']['tests'] for event in test['unattributedEvents']]
    for event in events:
        reasons[event['reason']] = reasons.get(event['reason'], 0) + event['count']
    result[case] = dict(validation=validation, exitCodes=codes, reasons=reasons, errors=errors)
rows = [line.split('\t') for line in (raw / 'asm12/context-reuse.tsv').read_text().splitlines()]
assert len(rows) == 2 and rows[0][1:5] == rows[1][1:5], 'Spring context/server not shared'
observation = (raw / 'asm12/context.tsv').read_text().splitlines()
assert any('\tHIT\tmain\t' in line and 'PingController#ping' in line for line in observation)
assert any('\tHIT\thttp-' in line and '\tfalse\t' in line and 'PingController#ping' in line for line in observation)
(raw / 'correctness-gates.json').write_text(json.dumps(result, indent=2) + '\n')

before = json.loads((here / 'raw/before/summary.json').read_text())
after = json.loads((here / 'raw/after/summary.json').read_text())
table = ['| Case | Calls | Workers | Before control s | Before agent s | Before ratio | After control s | After agent s | After ratio | Agent speedup |',
         '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for old, new in zip(before, after):
    assert all(old[key] == new[key] for key in ('kind', 'calls', 'threads'))
    table.append(f'| {old["kind"]} | {old["calls"]:,} | {old["threads"]} | '
                 f'{old["control"]["medianSeconds"]:.6f} | {old["agent"]["medianSeconds"]:.6f} | {old["ratio"]:.2f}x | '
                 f'{new["control"]["medianSeconds"]:.6f} | {new["agent"]["medianSeconds"]:.6f} | {new["ratio"]:.2f}x | '
                 f'{old["agent"]["medianSeconds"]/new["agent"]["medianSeconds"]:.2f}x |')
(raw / 'comparison.md').write_text('\n'.join(table) + '\n')
print(json.dumps(result, indent=2))
print('\n'.join(table))
