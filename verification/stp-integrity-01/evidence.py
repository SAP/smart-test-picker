#!/usr/bin/env python3
"""Check fresh local gates and retain a machine-readable integrity-task summary."""
import hashlib
import json
from pathlib import Path
import subprocess

here = Path(__file__).resolve().parent
root = here.parent.parent
raw = here / 'raw/implementation'
expected = '0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9'
pet = raw / 'petclinic-after'
fragment = pet / 'stp/coverage-fragment-v3.json'
assert hashlib.sha256(fragment.read_bytes()).hexdigest() == expected, 'STOP: fragment changed'
data = json.loads((pet / 'stp/asm-agent-diagnostic.json').read_text())
leaf = sum(t['metrics']['rawMethodHits'] for t in data['runtimeEvents']['tests'])
assert data['metrics']['rawMethodHits'] == 3816 and leaf == 3079
assert data['agentErrors'] == []
result = {'petclinic': dict(fragmentSha256=expected, rawMethodHits=3816, leafRawMethodHits=leaf,
                            agentErrors=[], tests=69)}
for case, folder, required in [('asm12', 'module', 'work captured by closed JUnit container'),
                              ('asm13', 'stp', 'ForkJoinTask: execution without observed submission')]:
    directory = raw / case
    validation = json.loads((directory / 'validation.json').read_text())
    assert validation['completed'] is False
    assert validation['assignmentValidation'] == validation['join'] == 'IllegalArgumentException: Fragment collection is not completed'
    codes = dict(line.split('=') for line in (directory / 'exit-codes.txt').read_text().splitlines())
    assert codes['mapping'] != '0' and codes['aggregation'] != '0'
    diag = json.loads((directory / folder / 'asm-agent-diagnostic.json').read_text())
    assert any(required in error and 'ASYNC_SETUP_UNSUPPORTED:ERROR' in error for error in diag['agentErrors'])
    events = diag['runtimeEvents']['unattributedEvents'] + [event for test in diag['runtimeEvents']['tests']
                                                          for event in test['unattributedEvents']]
    reasons = {reason: sum(event['count'] for event in events if event['reason'] == reason)
               for reason in ['NO_ACTIVE_TEST', 'LATE_EVENT', 'UNKNOWN_CONTEXT', 'TEST_ALREADY_FINISHED']}
    if case == 'asm12': assert reasons['LATE_EVENT'] > 0
    result[case] = dict(validation=validation, exitCodes=codes, reasons=reasons, diagnostics=diag['agentErrors'])
rows = [line.split('\t') for line in (raw / 'asm12/context-reuse.tsv').read_text().splitlines()]
assert len(rows) == 2 and rows[0][1:5] == rows[1][1:5], 'shared context/server not established'
result['hotPathUnchangedFiles'] = {}
for name in ['RuntimeHooks', 'RuntimeContextService', 'MethodHitOwner', 'MethodProbe',
             'RuntimeEventAggregator', 'TestExecutionContext']:
    path = f'stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/{name}.java'
    before = subprocess.check_output(['git', 'show', f'3884d49:{path}'], cwd=root)
    after = (root / path).read_bytes()
    assert before == after, f'Hot path changed: {path}'
    result['hotPathUnchangedFiles'][path] = hashlib.sha256(after).hexdigest()

# Catalog signatures/error callbacks changed; the actual ordinal and counter bodies did not.
def body(text, marker):
    start = text.index('{', text.index(marker))
    level = 0
    for end in range(start, len(text)):
        if text[end] == '{': level += 1
        elif text[end] == '}':
            level -= 1
            if level == 0: return text[start:end + 1]
    raise AssertionError(marker)

result['hotPathUnchangedBodies'] = []
for filename, markers in [('AgentRuntime', ['private void methodHit(']),
                          ('MethodCatalog', ['public synchronized int registerProbe(', 'public com.sap.oss.smarttestpicker.runtime.MethodProbe probe(', 'public Map<Long, Long> hitSnapshot(']),
                          ('MethodEntryClassFileTransformer', ['private byte[] instrument('])]:
    path = f'stp-agent/src/main/java/com/sap/oss/smarttestpicker/agent/{filename}.java'
    before = subprocess.check_output(['git', 'show', f'3884d49:{path}'], cwd=root, text=True)
    after = (root / path).read_text()
    for marker in markers:
        assert body(before, marker) == body(after, marker), (filename, marker)
        result['hotPathUnchangedBodies'].append(f'{filename}: {marker}')
(raw / 'correctness-gates.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
