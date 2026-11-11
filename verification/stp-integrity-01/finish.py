#!/usr/bin/env python3
"""Retain fresh suite results and bind all gate agents to the final tested artifact."""
import hashlib
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
from zipfile import ZipFile

here = Path(__file__).resolve().parent
root = here.parent.parent
raw = here / 'raw'
log = (raw / 'full-suites.log').read_text()
assert 'BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log
baseline = json.loads((root / 'verification/stp-perf-01/raw/implementation/suite-counts.json').read_text())
counts = {}
for module, minimum in baseline.items():
    assert f'> Task :{module}:test\n' in log, f'{module} was not freshly rerun'
    values = dict(tests=0, failures=0, errors=0, skipped=0)
    destination = raw / 'test-results' / module
    destination.mkdir(parents=True, exist_ok=True)
    files = list((root / module / 'build/test-results/test').glob('TEST-*.xml'))
    assert files
    for file in files:
        suite = ET.parse(file).getroot()
        for key in values: values[key] += int(suite.attrib.get(key, 0))
        shutil.copy2(file, destination / file.name)
    values['passed'] = values['tests'] - values['failures'] - values['errors'] - values['skipped']
    assert values['failures'] == values['errors'] == 0, (module, values)
    assert values['tests'] >= minimum['tests'] and values['passed'] >= minimum['passed'], (module, values)
    assert values['skipped'] == minimum['skipped'], (module, values)
    counts[module] = values
(raw / 'suite-counts.json').write_text(json.dumps(counts, indent=2) + '\n')
table = ['| Module | Tests | Passed | Skipped | Failures/errors |', '|---|---:|---:|---:|---:|']
for module, row in counts.items():
    table.append(f'| {module} | {row["tests"]} | {row["passed"]} | {row["skipped"]} | 0 |')
(raw / 'suite-counts.md').write_text('\n'.join(table) + '\n')

tested = root / 'stp-agent/build/libs/stp-agent.jar'
provenance = dict(testedAgentSha256=hashlib.sha256(tested.read_bytes()).hexdigest(), gates={})
for case in ('petclinic-after', 'asm12', 'asm13'):
    jars = list((here / '.work' / case / 'published/com/sap/oss/smart-test-picker/stp-agent').rglob('*.jar'))
    assert len(jars) == 1, jars
    measured = jars[0]
    with ZipFile(tested) as a, ZipFile(measured) as b:
        names = set(a.namelist()) | set(b.namelist())
        different = [name for name in names if name not in a.namelist() or name not in b.namelist()
                     or a.read(name) != b.read(name)]
    assert not different, (case, different)
    provenance['gates'][case] = dict(agentSha256=hashlib.sha256(measured.read_bytes()).hexdigest(),
                                    entryCount=len(names), differentEntryContents=different)
(raw / 'agent-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
print('\n'.join(table))
print(json.dumps(provenance, indent=2))
